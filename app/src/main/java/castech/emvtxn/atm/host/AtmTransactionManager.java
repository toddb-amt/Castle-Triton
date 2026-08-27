package castech.emvtxn.atm.host;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ATM Transaction Manager
 *
 * Orchestrates the complete ATM transaction flow:
 * 1. Card reading (via Castle SDK)
 * 2. PIN entry and encryption
 * 3. Host communication
 * 4. Response handling
 * 5. Reversal processing (if needed)
 *
 * Manages sequence numbers, retries, and automatic reversals.
 */
public class AtmTransactionManager {

    private static final String TAG = "AtmTxnManager";

    // Dependencies
    private final ProcessorConfig config;
    private final AtmHostConnection connection;
    private final CastleKeyManager keyManager;
    private final HyosungMessageBuilder builder;
    private final HyosungMessageParser parser;
    private final EmvTagEnhancer emvTagEnhancer;
    private ReversalPersistenceManager reversalManager;

    // State
    private final AtomicInteger sequenceNumber;
    private TransactionRequest currentRequest;
    private TransactionResponse currentResponse;
    private final java.util.concurrent.atomic.AtomicBoolean transactionInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Reversal tracking state
    private volatile boolean requestSentToHost;      // True after request sent, before response
    private volatile boolean approvalReceived;       // True if host approved the transaction
    private volatile boolean currentIsBalanceInquiry; // True while a balance inquiry is in flight

    /**
     * Whether balance inquiries may generate reversals.
     *
     * FALSE (current): only cash withdrawals reverse. A balance inquiry moves no
     * money, so there is nothing to unwind; emitting a Type 86 for one produced a
     * reversal storm on 2026-08-09 (5 failed drain attempts) that then blocked
     * subsequent customer transactions.
     *
     * Set TRUE to restore the previous "reversals are generic across all
     * transaction types" behaviour (BlueVerse IsReversalCondition() parity).
     */
    private static final boolean REVERSALS_FOR_BALANCE_INQUIRY = false;
    private long currentAmountCents;                 // Amount for current transaction
    private long currentSurchargeCents;              // Surcharge for current transaction

    /**
     * Pre-send reversal record created BEFORE the transaction is sent to the host.
     * Cleared on clean approval (no reversal needed), promoted to PENDING on send
     * failure or timeout. Survives terminal crash/power loss between socket write
     * and response handler. See {@link ReversalPersistenceManager#createPreSendReversal}.
     */
    private volatile ReversalPersistenceManager.PendingReversal currentPreSendReversal;

    // Threading
    private final ExecutorService executor;
    private final Handler mainHandler;

    // Heartbeat scheduler for H0 status monitoring
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;
    private boolean heartbeatRunning = false;

    // Listener
    private TransactionListener listener;

    /**
     * Creates a new transaction manager.
     *
     * @param config Processor configuration
     * @param keyManager Key manager for PIN encryption
     */
    public AtmTransactionManager(ProcessorConfig config, CastleKeyManager keyManager) {
        this.config = config;
        this.keyManager = keyManager;
        this.connection = new AtmHostConnection(config);
        this.builder = config.createMessageBuilder();
        this.parser = config.createMessageParser();
        this.emvTagEnhancer = new EmvTagEnhancer();
        this.sequenceNumber = new AtomicInteger(1);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.transactionInProgress.set(false);

        // Set up connection listener
        connection.setConnectionListener(new AtmHostConnection.ConnectionListener() {
            @Override
            public void onConnected(String processorName) {
                Log.d(TAG, "Connected to " + processorName);
            }

            @Override
            public void onDisconnected(String processorName) {
                Log.d(TAG, "Disconnected from " + processorName);
            }

            @Override
            public void onError(String processorName, String error) {
                Log.e(TAG, "Connection error: " + error);
            }
        });
    }

    /**
     * Sets the transaction listener.
     */
    public void setTransactionListener(TransactionListener listener) {
        this.listener = listener;
    }

    /**
     * Sets the reversal persistence manager for automatic reversal handling.
     */
    public void setReversalManager(ReversalPersistenceManager reversalManager) {
        this.reversalManager = reversalManager;
    }

    /**
     * Resets reversal tracking state for a new transaction.
     */
    private void resetReversalState() {
        requestSentToHost = false;
        approvalReceived = false;
        currentAmountCents = 0;
        currentSurchargeCents = 0;
        currentPreSendReversal = null;
        currentIsBalanceInquiry = false;
    }

    /**
     * True when the in-flight transaction must never produce a reversal.
     * Balance inquiries move no money, so there is nothing to unwind.
     */
    private boolean reversalsSuppressedForCurrentTxn() {
        return currentIsBalanceInquiry && !REVERSALS_FOR_BALANCE_INQUIRY;
    }

    /**
     * Removes the pre-send reversal record. Called when the transaction
     * completes cleanly (host approval received, no reversal needed).
     */
    private void clearPreSendReversal() {
        ReversalPersistenceManager.PendingReversal preSend = currentPreSendReversal;
        if (preSend != null && reversalManager != null) {
            reversalManager.removePendingReversal(preSend.getTransactionId());
            Log.d(TAG, "Cleared pre-send reversal record: " + preSend.getTransactionId());
        }
        currentPreSendReversal = null;
    }

    /**
     * Promotes the current pre-send reversal to a recovery-eligible status (so
     * the recovery loop will process it). Branches on the failure mode to set
     * the correct three-state target (task #16):
     *
     * <ul>
     *     <li>Timeout (no response) → STATUS_PENDING_RECONNECT_AND_REVERSE
     *         (we don't know if host got the request; need to reconnect and reverse)</li>
     *     <li>Connection error after send → STATUS_PENDING_RECONNECT_AND_REVERSE
     *         (host may have processed; reconnect first)</li>
     *     <li>Parse error / generic error with response present → STATUS_PENDING
     *         (response received, just dispatch reversal directly)</li>
     * </ul>
     *
     * <p>If no pre-send record exists, falls back to the legacy
     * {@link #storePendingReversalIfNeeded(String)} path.</p>
     *
     * @param reason reversal reason code (see {@link HyosungProtocol})
     */
    private void promoteOrCreatePendingReversal(String reason) {
        // Balance inquiries never reverse — nothing to unwind.
        if (reversalsSuppressedForCurrentTxn()) {
            Log.d(TAG, "Balance inquiry failed (" + reason + ") — no reversal generated");
            currentPreSendReversal = null;
            return;
        }

        ReversalPersistenceManager.PendingReversal preSend = currentPreSendReversal;
        if (preSend != null && reversalManager != null) {
            // Update auth data from response if we now have one
            // (timeout case = currentResponse stays null, authData stays "")
            String authData = "";
            if (currentResponse != null && currentResponse.getAuthorizationData() != null) {
                authData = currentResponse.getAuthorizationData();
            }
            if (!authData.isEmpty()) {
                preSend.setAuthData(authData);
            }

            // Decide which of the three pending states to use:
            //   - No response received → host might or might not have got it,
            //     and connection may be broken → RECONNECT_AND_REVERSE
            //   - Response received but parse/generic error → connection alive,
            //     just dispatch → PENDING
            String targetState;
            boolean isTimeoutOrConnLoss = HyosungProtocol.REV_REASON_TIMEOUT.equals(reason)
                    || (currentResponse == null);
            if (isTimeoutOrConnLoss) {
                targetState = ReversalPersistenceManager.PendingReversal
                        .STATUS_PENDING_RECONNECT_AND_REVERSE;
            } else {
                targetState = ReversalPersistenceManager.PendingReversal.STATUS_PENDING;
            }

            reversalManager.promoteToStatus(preSend.getTransactionId(), reason, targetState);
            currentPreSendReversal = null;
            return;
        }
        // No pre-send record (e.g. reversalManager was null at send time) —
        // fall back to legacy post-failure path
        storePendingReversalIfNeeded(reason);
    }

    /**
     * Stores a pending reversal for later processing.
     * Called when transaction fails after request was sent to host.
     *
     * @param reason Reversal reason code (see HyosungProtocol.REV_REASON_*)
     */
    private void storePendingReversalIfNeeded(String reason) {
        // Balance inquiries never reverse — nothing to unwind.
        if (reversalsSuppressedForCurrentTxn()) {
            Log.d(TAG, "Balance inquiry (" + reason + ") — no pending reversal stored");
            return;
        }

        if (reversalManager == null) {
            Log.w(TAG, "No reversal manager set - cannot store reversal");
            return;
        }

        if (currentRequest == null) {
            Log.w(TAG, "No current request - cannot store reversal");
            return;
        }

        // Only store reversal if we sent request to host (potential authorization)
        if (!requestSentToHost) {
            Log.d(TAG, "Request not yet sent to host - no reversal needed");
            return;
        }

        // Get auth data from response if we have one
        String authData = "";
        if (currentResponse != null && currentResponse.getAuthorizationData() != null) {
            authData = currentResponse.getAuthorizationData();
        }

        Log.d(TAG, "Storing pending reversal - reason: " + reason +
              ", approved: " + approvalReceived +
              ", authData: " + (authData.isEmpty() ? "(none)" : authData.substring(0, Math.min(10, authData.length())) + "..."));

        reversalManager.createAndStorePendingReversal(
            config.getTerminalId(),
            currentRequest.getSequenceNumber(),
            authData,
            currentRequest.getTrack2Data(),
            currentRequest.getPinBlock(),
            currentAmountCents,
            currentSurchargeCents,
            reason
        );

        Log.d(TAG, "Pending reversal stored for sequence " + currentRequest.getSequenceNumber());
    }

    /**
     * Gets the next sequence number (1-9999, wraps).
     */
    private int getNextSequenceNumber() {
        // W3 fix: atomic wrap-around using getAndUpdate
        return sequenceNumber.getAndUpdate(n -> n >= 9999 ? 1 : n + 1);
    }

    // =========================================================================
    // Cash Withdrawal
    // =========================================================================

    /**
     * Performs a cash withdrawal transaction.
     *
     * @param cardData Card data from Castle SDK
     * @param amountCents Amount in cents
     * @param surchargeCents Surcharge in cents
     * @param accountType Account type (CA, SA, CR)
     */
    public void performCashWithdrawal(final CastleCardData cardData, final long amountCents,
            final long surchargeCents, final String accountType) {

        if (!transactionInProgress.compareAndSet(false, true)) {
            notifyError("Transaction already in progress");
            return;
        }

        // transactionInProgress already set by compareAndSet above
        resetReversalState();
        currentAmountCents = amountCents;
        currentSurchargeCents = surchargeCents;
        notifyProgress("Starting cash withdrawal...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO: H0 status monitoring before transaction is disabled temporarily
                    // The H0 is included in Field 12 of the transaction itself
                    // Standalone H0 heartbeat can be re-enabled once connection handling is fixed
                    // try {
                    //     Log.d(TAG, "Sending H0 status monitoring before transaction...");
                    //     connection.sendStatusMonitoring();
                    //     Log.d(TAG, "H0 status monitoring sent successfully");
                    // } catch (Exception e) {
                    //     Log.w(TAG, "H0 status monitoring failed (continuing with transaction): " + e.getMessage());
                    // }

                    // Build request
                    TransactionRequest request = TransactionRequest.createCashWithdrawal(
                        config.getTerminalId(),
                        cardData.getTrack2ForTransaction(),
                        cardData.getEncryptedPinBlock(),
                        amountCents,
                        surchargeCents,
                        accountType
                    );
                    request.setRoutingId(config.getRoutingId());
                    request.setSequenceNumber(getNextSequenceNumber());

                    // Add status monitoring (Field 12) - REQUIRED by processor
                    request.setStatusMonitoring(EmvTagEnhancer.buildStatusMonitoring());
                    Log.d(TAG, "Added status monitoring to request");

                    // Add and enhance EMV data with missing terminal tags
                    if (cardData.hasEmvData()) {
                        String baseEmv = cardData.getEmvData();
                        // Determine POS entry mode (chip vs contactless)
                        String posEntryMode = cardData.isContactless() ?
                            EmvTagEnhancer.POS_ENTRY_CONTACTLESS : EmvTagEnhancer.POS_ENTRY_CHIP;
                        // Get proper transaction type
                        byte txnType = EmvTagEnhancer.getTransactionType(false, accountType);
                        // Enhance EMV data with missing tags
                        // ATM mode: Override 9F34 to 420000 (Online PIN verified)
                        String enhancedEmv = emvTagEnhancer.enhanceEmvData(
                            baseEmv, txnType, posEntryMode, true, true);  // isAtmMode=true
                        request.setEmvData(enhancedEmv);
                        Log.d(TAG, "Enhanced EMV data: base=" + baseEmv.length() +
                            " chars, enhanced=" + enhancedEmv.length() + " chars");
                    }

                    // Add encrypted Track 2 + KSN if available (from DUKPT encryption)
                    if (cardData.getEncryptedTrack2() != null && cardData.getEncryptedTrack2().length > 0
                        && cardData.getTrack2KSN() != null && cardData.getTrack2KSN().length > 0) {
                        // Convert byte arrays to hex strings for the request
                        String encTrack2Hex = bytesToHex(cardData.getEncryptedTrack2());
                        String ksnHex = bytesToHex(cardData.getTrack2KSN());
                        request.setEncryptedTrack2(encTrack2Hex);
                        request.setTrack2Ksn(ksnHex);
                        Log.d(TAG, "Added encrypted Track 2 to request");
                        Log.d(TAG, "  Encrypted: " + encTrack2Hex.substring(0, Math.min(32, encTrack2Hex.length())) + "...");
                        Log.d(TAG, "  Track2 KSN: " + ksnHex);
                    }

                    // Add PIN KSN if available (for DUKPT PIN decryption)
                    // IMPORTANT: PIN KSN may differ from Track2 KSN due to DUKPT counter increment
                    if (cardData.getPinBlockKSN() != null && cardData.getPinBlockKSN().length > 0) {
                        String pinKsnHex = bytesToHex(cardData.getPinBlockKSN());
                        request.setPinKsn(pinKsnHex);
                        Log.d(TAG, "Added PIN KSN to request: " + pinKsnHex);
                    }

                    currentRequest = request;
                    notifyProgress("Connecting to host...");

                    // KEY-LOADED flag check (BlueVerse compliance — task #17):
                    // Defensive verification that working key is loaded before sending the
                    // transaction. Equivalent to BlueVerse's devCmn->[0x888] check after
                    // dispatch start. For non-DUKPT mode, the working key MUST be loaded
                    // before we send — otherwise the host can't decrypt the PIN block.
                    boolean isDukptMode = castech.emvtxn.GlobalPara.atmDukptEnabled;
                    if (!isDukptMode && keyManager != null && !keyManager.isWorkingKeyLoaded()) {
                        Log.e(TAG, "KEY-LOADED check failed: non-DUKPT mode but no working key loaded");
                        notifyError("Working key not loaded — cannot complete transaction");
                        return;
                    }

                    // Pre-persist reversal BEFORE send so that a crash/power loss between
                    // the socket write and the response handler does not orphan an
                    // authorized transaction. Cleared on clean approval, promoted on failure.
                    // Matches the deployed Hyosung BlueVerse pre-send persistence pattern.
                    if (reversalManager != null) {
                        // EFX/Pulse TC86 needs F3 (retrieval ref) + F8 (status monitoring) +
                        // F9 (EMV TLV) from the ORIGINAL 85 to match the reversal on the host.
                        // Build the retrieval ref now (terminal clock + seq) — the host echoes
                        // the same value in its 85 response, so this gives us field parity.
                        String retrievalRef = ReversalRequest.buildRetrievalReference(
                                new java.util.Date(), request.getSequenceNumber());
                        currentPreSendReversal = reversalManager.createPreSendReversal(
                            config.getTerminalId(),
                            request.getSequenceNumber(),
                            amountCents,
                            surchargeCents,
                            retrievalRef,
                            request.getStatusMonitoring(),
                            request.getEmvData());
                        Log.d(TAG, "Pre-persisted reversal record for seq "
                            + request.getSequenceNumber()
                            + " (id=" + currentPreSendReversal.getTransactionId()
                            + ", rrn=" + retrievalRef + ")");
                    }

                    // Mark that we're sending to host - if we fail after this, may need reversal
                    requestSentToHost = true;

                    // Send to host — use protocol-appropriate path
                    TransactionResponse response;
                    AtmProtocol protocol = connection.getProtocol();
                    if (protocol != null && config.isTritonProtocol()) {
                        Log.d(TAG, "Using Triton transaction request");
                        connection.ensureConnected();
                        byte[] requestMsg = protocol.buildTransactionRequest(request);
                        byte[] responseMsg = connection.sendAndReceiveRaw(requestMsg);
                        connection.completeTritonHandshake();
                        response = protocol.parseTransactionResponse(responseMsg);
                        connection.disconnect();
                    } else {
                        Log.d(TAG, "Using Hyosung transaction request");
                        response = connection.sendTransaction(request);
                    }
                    currentResponse = response;

                    // Clean response received — process and decide whether to keep or
                    // clear the pre-persisted reversal record (decided in processTransactionResponse)
                    processTransactionResponse(response, amountCents);

                } catch (AtmHostConnection.ConnectionException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    // If request was sent, we may need a reversal (timeout = possible approval)
                    if (requestSentToHost) {
                        boolean isTimeout = e.getMessage() != null &&
                            (e.getMessage().contains("timeout") || e.getMessage().contains("Timeout"));
                        String reason = isTimeout ? HyosungProtocol.REV_REASON_TIMEOUT :
                                                   HyosungProtocol.REV_REASON_HOST_ERROR;
                        Log.w(TAG, "Connection error after sending - promoting reversal (reason: " + reason + ")");
                        promoteOrCreatePendingReversal(reason);
                    } else {
                        // Send didn't happen — clear the pre-persist
                        clearPreSendReversal();
                    }
                    handleConnectionError(e);
                } catch (HyosungMessageParser.ParseException e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    if (requestSentToHost) {
                        Log.w(TAG, "Parse error after sending - promoting reversal");
                        promoteOrCreatePendingReversal(HyosungProtocol.REV_REASON_HOST_ERROR);
                    } else {
                        clearPreSendReversal();
                    }
                    notifyError("Invalid response from host: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Transaction error: " + e.getMessage());
                    if (requestSentToHost) {
                        Log.w(TAG, "Transaction error after sending - promoting reversal");
                        promoteOrCreatePendingReversal(HyosungProtocol.REV_REASON_HOST_ERROR);
                    } else {
                        clearPreSendReversal();
                    }
                    notifyError("Transaction failed: " + e.getMessage());
                } finally {
                    // Always disconnect - server closes connection after each transaction
                    connection.disconnect();
                    Log.d(TAG, "Disconnected after cash withdrawal");
                    transactionInProgress.set(false);
                }
            }
        });
    }

    // =========================================================================
    // Balance Inquiry
    // =========================================================================

    /**
     * Performs a balance inquiry.
     *
     * @param cardData Card data from Castle SDK
     * @param accountType Account type (CA, SA, CR)
     */
    public void performBalanceInquiry(final CastleCardData cardData, final String accountType) {

        if (!transactionInProgress.compareAndSet(false, true)) {
            notifyError("Transaction already in progress");
            return;
        }

        // Reset reversal tracking — same pattern as performCashWithdrawal.
        // NOTE: balance inquiries do NOT generate reversals (see
        // REVERSALS_FOR_BALANCE_INQUIRY). Nothing moves money, so there is nothing
        // to unwind; the previous "generic across all transaction types" behaviour
        // (BlueVerse IsReversalCondition() parity) caused a reversal storm.
        resetReversalState();
        currentIsBalanceInquiry = true;

        // transactionInProgress already set by compareAndSet above
        notifyProgress("Checking balance...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO: H0 status monitoring before transaction is disabled temporarily
                    // The H0 is included in Field 12 of the transaction itself
                    // try {
                    //     Log.d(TAG, "Sending H0 status monitoring before balance inquiry...");
                    //     connection.sendStatusMonitoring();
                    //     Log.d(TAG, "H0 status monitoring sent successfully");
                    // } catch (Exception e) {
                    //     Log.w(TAG, "H0 status monitoring failed (continuing with transaction): " + e.getMessage());
                    // }

                    String track2Value = cardData.getTrack2ForTransaction();
                    Log.d(TAG, "Track2 (BI): [masked, length=" + (track2Value != null ? track2Value.length() : 0) + "]");

                    TransactionRequest request = TransactionRequest.createBalanceInquiry(
                        config.getTerminalId(),
                        track2Value,
                        cardData.getEncryptedPinBlock(),
                        accountType
                    );
                    request.setRoutingId(config.getRoutingId());
                    request.setSequenceNumber(getNextSequenceNumber());

                    // Add status monitoring (Field 12) - REQUIRED by processor
                    request.setStatusMonitoring(EmvTagEnhancer.buildStatusMonitoring());
                    Log.d(TAG, "Added status monitoring to balance inquiry request");

                    // Add and enhance EMV data with missing terminal tags
                    if (cardData.hasEmvData()) {
                        String baseEmv = cardData.getEmvData();
                        // Determine POS entry mode (chip vs contactless)
                        String posEntryMode = cardData.isContactless() ?
                            EmvTagEnhancer.POS_ENTRY_CONTACTLESS : EmvTagEnhancer.POS_ENTRY_CHIP;
                        // Get proper transaction type for balance inquiry
                        byte txnType = EmvTagEnhancer.getTransactionType(true, accountType);
                        // Enhance EMV data with missing tags
                        // ATM mode: Override 9F34 to 420000 (Online PIN verified)
                        String enhancedEmv = emvTagEnhancer.enhanceEmvData(
                            baseEmv, txnType, posEntryMode, false, true);  // isAtmMode=true
                        request.setEmvData(enhancedEmv);
                        Log.d(TAG, "Enhanced EMV data (BI): base=" + baseEmv.length() +
                            " chars, enhanced=" + enhancedEmv.length() + " chars");
                    }

                    // Add encrypted Track 2 + KSN if available (from DUKPT encryption)
                    if (cardData.getEncryptedTrack2() != null && cardData.getEncryptedTrack2().length > 0
                        && cardData.getTrack2KSN() != null && cardData.getTrack2KSN().length > 0) {
                        String encTrack2Hex = bytesToHex(cardData.getEncryptedTrack2());
                        String ksnHex = bytesToHex(cardData.getTrack2KSN());
                        request.setEncryptedTrack2(encTrack2Hex);
                        request.setTrack2Ksn(ksnHex);
                        Log.d(TAG, "Added encrypted Track 2 to balance inquiry request");
                    }

                    // Add PIN KSN if available (for DUKPT PIN decryption)
                    if (cardData.getPinBlockKSN() != null && cardData.getPinBlockKSN().length > 0) {
                        String pinKsnHex = bytesToHex(cardData.getPinBlockKSN());
                        request.setPinKsn(pinKsnHex);
                        Log.d(TAG, "Added PIN KSN to balance inquiry request: " + pinKsnHex);
                    }

                    Log.d(TAG, "BI request built, track2 present: " + (request.getTrack2Data() != null && !request.getTrack2Data().isEmpty()));

                    currentRequest = request;
                    notifyProgress("Connecting to host...");

                    // KEY-LOADED flag check (BlueVerse compliance — task #17, parity with withdrawal):
                    // For non-DUKPT mode, the working key MUST be loaded before we send — otherwise
                    // the host can't decrypt the PIN block and balance inquiry will fail with 76.
                    boolean isDukptModeBI = castech.emvtxn.GlobalPara.atmDukptEnabled;
                    if (!isDukptModeBI && keyManager != null && !keyManager.isWorkingKeyLoaded()) {
                        Log.e(TAG, "KEY-LOADED check failed (BI): non-DUKPT mode but no working key loaded");
                        notifyError("Working key not loaded — cannot complete balance inquiry");
                        return;
                    }

                    // Pre-persist reversal BEFORE send (BlueVerse parity — IsReversalCondition is
                    // state-based, not type-based, so BIs need the same protection as withdrawals).
                    // If a connection-close happens mid-receive after the host approved, this record
                    // gets promoted to RECONNECT_AND_REVERSE and drained on the next session.
                    // Amount = 0 because BI doesn't move money — the Type 86 still echoes the original
                    // Type 85 fields (including the BI account-type) which is what the processor needs
                    // to identify and unwind the orphan approval.
                    if (reversalManager != null && REVERSALS_FOR_BALANCE_INQUIRY) {
                        // See withdrawal path for the EFX/Pulse TC86 layout rationale.
                        String retrievalRef = ReversalRequest.buildRetrievalReference(
                                new java.util.Date(), request.getSequenceNumber());
                        currentPreSendReversal = reversalManager.createPreSendReversal(
                            config.getTerminalId(),
                            request.getSequenceNumber(),
                            0L,   // amount — balance inquiry doesn't move money
                            0L,   // surcharge
                            retrievalRef,
                            request.getStatusMonitoring(),
                            request.getEmvData());
                        Log.d(TAG, "Pre-persisted reversal record for BI seq "
                            + request.getSequenceNumber()
                            + " (id=" + currentPreSendReversal.getTransactionId()
                            + ", rrn=" + retrievalRef + ")");
                    } else {
                        Log.d(TAG, "Balance inquiry — no reversal record armed (BI does not reverse)");
                    }

                    // Mark that we're sending to host - if we fail after this, may need reversal
                    requestSentToHost = true;

                    // Send to host — use protocol-appropriate path
                    TransactionResponse response;
                    AtmProtocol protocol = connection.getProtocol();
                    if (protocol != null && config.isTritonProtocol()) {
                        Log.d(TAG, "Using Triton balance inquiry request");
                        connection.ensureConnected();
                        byte[] requestMsg = protocol.buildTransactionRequest(request);
                        byte[] responseMsg = connection.sendAndReceiveRaw(requestMsg);
                        connection.completeTritonHandshake();
                        response = protocol.parseTransactionResponse(responseMsg);
                        connection.disconnect();
                    } else {
                        Log.d(TAG, "Using Hyosung balance inquiry request");
                        response = connection.sendTransaction(request);
                    }
                    currentResponse = response;

                    // Clean response received — processBalanceInquiryResponse already calls
                    // clearPreSendReversal() on both approved and declined paths, so the
                    // pre-persisted record is removed when the round-trip completed cleanly.
                    processBalanceInquiryResponse(response);

                } catch (AtmHostConnection.ConnectionException e) {
                    Log.e(TAG, "Connection error (BI): " + e.getMessage());
                    // If request was sent, we may need a reversal (timeout/conn-close = possible approval)
                    if (requestSentToHost) {
                        boolean isTimeout = e.getMessage() != null &&
                            (e.getMessage().contains("timeout") || e.getMessage().contains("Timeout"));
                        String reason = isTimeout ? HyosungProtocol.REV_REASON_TIMEOUT :
                                                   HyosungProtocol.REV_REASON_HOST_ERROR;
                        Log.w(TAG, "BI connection error after sending — promoting reversal (reason: " + reason + ")");
                        promoteOrCreatePendingReversal(reason);
                    } else {
                        // Send didn't happen — clear the pre-persist
                        clearPreSendReversal();
                    }
                    handleConnectionError(e);
                } catch (HyosungMessageParser.ParseException e) {
                    Log.e(TAG, "Parse error (BI): " + e.getMessage());
                    if (requestSentToHost) {
                        Log.w(TAG, "BI parse error after sending — promoting reversal");
                        promoteOrCreatePendingReversal(HyosungProtocol.REV_REASON_HOST_ERROR);
                    } else {
                        clearPreSendReversal();
                    }
                    notifyError("Invalid response from host: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Balance inquiry error: " + e.getMessage());
                    if (requestSentToHost) {
                        Log.w(TAG, "BI error after sending — promoting reversal");
                        promoteOrCreatePendingReversal(HyosungProtocol.REV_REASON_HOST_ERROR);
                    } else {
                        clearPreSendReversal();
                    }
                    notifyError("Balance inquiry failed: " + e.getMessage());
                } finally {
                    // Always disconnect - server closes connection after each transaction
                    connection.disconnect();
                    Log.d(TAG, "Disconnected after balance inquiry");
                    transactionInProgress.set(false);
                }
            }
        });
    }

    // =========================================================================
    // Configuration / Key Download
    // =========================================================================

    /**
     * Requests configuration/key download from host.
     *
     * @param configType Configuration type (1=full, 3=key only, etc.)
     */
    public void requestConfiguration(final String configType) {
        notifyProgress("Downloading configuration...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AtmProtocol protocol = connection.getProtocol();

                    if (protocol != null && config.isTritonProtocol()) {
                        // Triton: Config Download (code 60)
                        Log.d(TAG, "Using Triton config download (code 60)");
                        connection.ensureConnected();
                        byte[] requestMsg = protocol.buildConfigDownloadRequest(config.getTerminalId());
                        byte[] responseMsg = connection.sendAndReceiveRaw(requestMsg);
                        ConfigResponse response = protocol.parseConfigResponse(responseMsg);

                        connection.disconnect();
                        Log.d(TAG, "Disconnected after Triton config request");

                        if (response != null && keyManager.loadWorkingKey(response)) {
                            notifyProgress("Working key loaded successfully");
                            notifyConfigComplete(response);
                        } else {
                            notifyError("Failed to load working key from Triton config");
                        }
                    } else {
                        // Hyosung: Config Request (type 88)
                        Log.d(TAG, "Using Hyosung config request (type 88)");
                        ConfigRequest request = new ConfigRequest();
                        request.setTerminalId(config.getTerminalId());
                        request.setRoutingId(config.getRoutingId());
                        request.setConfigType(configType);

                        ConfigResponse response = connection.sendConfigRequest(request);

                        connection.disconnect();
                        Log.d(TAG, "Disconnected after Hyosung config request");

                        if (keyManager.loadWorkingKey(response)) {
                            notifyProgress("Working key loaded successfully");
                            notifyConfigComplete(response);
                        } else {
                            notifyError("Failed to load working key");
                        }
                    }

                } catch (AtmHostConnection.ConnectionException e) {
                    notifyError("Configuration download failed: " + e.getMessage());
                } catch (HyosungMessageParser.ParseException e) {
                    notifyError("Invalid configuration response: " + e.getMessage());
                } catch (Exception e) {
                    notifyError("Configuration error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Downloads working keys from the host (async).
     */
    public void downloadKeys() {
        requestConfiguration(HyosungProtocol.CONFIG_KEY_ONLY);
    }

    /**
     * Downloads working keys from the host synchronously (blocks until complete).
     * Includes retry logic to handle transient first-connection failures.
     *
     * @throws Exception if key download fails after all retries
     */
    /**
     * STATIC on purpose: MainActivity re-initializes the host service on settings
     * re-apply (opening the Admin screen, CasHUB config, startup sequencing), and
     * each re-init constructs a NEW AtmTransactionManager. With an instance-level
     * lock, every instance serialized only against itself — three instances put
     * three parallel Type 88s on the wire (observed as request bursts on the MUX).
     * One process talks to one host: the download slot is app-wide.
     */
    private static final Object keyDownloadLock = new Object();

    /**
     * Window in which a just-completed key download satisfies a queued caller.
     * Sized well under any key lifetime — this only collapses the pile-up of
     * concurrent requests, it never suppresses a genuinely later refresh.
     * Static for the same reason as the lock.
     */
    private static final long KEY_DOWNLOAD_COALESCE_MS = 30_000L;
    private static volatile long lastKeyDownloadSuccessMs = 0L;

    /**
     * Downloads working keys, coalescing concurrent requests.
     *
     * <p>This lock is the ONE choke point every key download passes through —
     * startup renewal, the admin "Download Keys" and "Request New Key" buttons,
     * openSession(), scheduled renewal and RC-76 key-sync all land here. It used
     * to only {@code synchronized} around the download, which QUEUES rather than
     * rejects: the second caller waited its turn and then sent its own redundant
     * Type 88. The host answers one, the other times out and its retries clear the
     * key the winner just loaded — the terminal ends up keyless and "not ready".
     * (Very visible on EFX, where a response takes ~50s.)</p>
     *
     * <p>So once inside the lock we re-check: if a download succeeded moments ago,
     * this request is already satisfied and returns without touching the wire.</p>
     */
    public void downloadKeysSync() throws Exception {
        synchronized (keyDownloadLock) {
            long sinceSuccess = System.currentTimeMillis() - lastKeyDownloadSuccessMs;
            if (lastKeyDownloadSuccessMs > 0 && sinceSuccess < KEY_DOWNLOAD_COALESCE_MS
                    && keyManager != null) {
                // Another instance may have completed the download (the manager is
                // recreated on every settings re-apply), so THIS instance's flag can
                // lag reality. The session key itself is in the secure element —
                // re-sync our in-memory state from the persisted metadata before
                // deciding a redundant Type 88 is needed.
                boolean loaded = keyManager.isWorkingKeyLoaded()
                        || keyManager.resyncHardwareKeyState();
                if (loaded) {
                    Log.d(TAG, "Key download coalesced: a working key loaded " + sinceSuccess
                            + "ms ago — skipping redundant Type 88");
                    return;
                }
            }
            downloadKeysSyncInternal();
            if (keyManager != null && keyManager.isWorkingKeyLoaded()) {
                lastKeyDownloadSuccessMs = System.currentTimeMillis();
            }
        }
    }

    private void downloadKeysSyncInternal() throws Exception {
        Log.d(TAG, "Downloading keys synchronously...");

        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Log.d(TAG, "Key download attempt " + attempt + " of " + maxRetries);

                // Disconnect first if we had a previous failed attempt
                if (attempt > 1) {
                    Log.d(TAG, "Retry: disconnecting before reconnect...");
                    connection.disconnect();
                    // Brief delay before retry
                    Thread.sleep(500);
                }

                if (!connection.isConnected()) {
                    Log.d(TAG, "Connecting to host...");
                    connection.connect();
                }

                ConfigResponse response;
                AtmProtocol protocol = connection.getProtocol();

                if (protocol != null && config.isTritonProtocol()) {
                    // Triton: Config Download (code 60)
                    Log.d(TAG, "Using Triton config download (code 60) - sync");
                    byte[] requestMsg = protocol.buildConfigDownloadRequest(config.getTerminalId());
                    byte[] responseMsg = connection.sendAndReceiveRaw(requestMsg);
                    // Complete Triton handshake: send ACK, wait for EOT
                    connection.completeTritonHandshake();
                    response = protocol.parseConfigResponse(responseMsg);
                } else {
                    // Hyosung: Config Request (type 88)
                    Log.d(TAG, "Using Hyosung config request (type 88) - sync");
                    ConfigRequest request = new ConfigRequest();
                    request.setTerminalId(config.getTerminalId());
                    request.setRoutingId(config.getRoutingId());
                    request.setConfigType(HyosungProtocol.CONFIG_KEY_ONLY);
                    response = connection.sendConfigRequest(request);
                }

                // Disconnect after config - server may close after handshake
                connection.disconnect();
                Log.d(TAG, "Disconnected after sync config request");

                // DUKPT mode: Type 88 round-trip is success on its own (hardware key, no working key to load)
                if (castech.emvtxn.GlobalPara.atmDukptEnabled) {
                    Log.d(TAG, "DUKPT mode: Type 88 round-trip successful on attempt " + attempt + " (no working key load needed)");
                    return;
                }

                // MKSK mode: load the working key from response
                if (keyManager.loadWorkingKey(response)) {
                    Log.d(TAG, "Working key loaded successfully (sync) on attempt " + attempt);
                    return; // Success!
                } else {
                    throw new Exception("Failed to load working key from response");
                }

            } catch (AtmHostConnection.ConnectionException e) {
                Log.w(TAG, "Key download attempt " + attempt + " failed (connection): " + e.getMessage());
                lastException = e;
                // Continue to next retry
            } catch (HyosungMessageParser.ParseException e) {
                Log.w(TAG, "Key download attempt " + attempt + " failed (parse): " + e.getMessage());
                lastException = e;
                // Continue to next retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Key download interrupted");
            }
        }

        // All retries exhausted
        Log.e(TAG, "Key download failed after " + maxRetries + " attempts");
        throw new Exception("Key download failed after " + maxRetries + " attempts: " +
            (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    // =========================================================================
    // Key Renewal
    // =========================================================================

    /**
     * Schedules a key renewal to run asynchronously.
     * Called when processor indicates configuration is required (Field 11 = "01")
     * or when response code 76 (key sync error) is received.
     */
    public void scheduleKeyRenewal() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "=== Scheduled Key Renewal Starting ===");
                try {
                    // Brief delay to allow current transaction to complete
                    Thread.sleep(1000);

                    downloadKeysSync();
                    Log.d(TAG, "Scheduled key renewal completed successfully");
                    notifyProgress("Working key renewed");

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Key renewal interrupted");
                } catch (Exception e) {
                    Log.e(TAG, "Scheduled key renewal failed: " + e.getMessage());
                    notifyError("Key renewal failed: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Checks if the working key needs renewal and downloads if necessary.
     * Call this before starting a transaction to ensure valid keys.
     *
     * @return true if key is valid (either already valid or successfully renewed)
     */
    public boolean ensureValidWorkingKey() {
        if (keyManager == null) {
            Log.e(TAG, "ensureValidWorkingKey: keyManager is null");
            return false;
        }

        // Check if renewal is needed (expired, missing, or approaching expiry)
        if (!keyManager.needsRenewal()) {
            Log.d(TAG, "ensureValidWorkingKey: Key is valid - " + keyManager.getKeyStatus());
            return true;
        }

        Log.d(TAG, "ensureValidWorkingKey: Key needs renewal - " + keyManager.getKeyStatus());

        // Try to download new key synchronously
        try {
            notifyProgress("Renewing working key...");
            downloadKeysSync();
            Log.d(TAG, "ensureValidWorkingKey: Key renewed successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "ensureValidWorkingKey: Failed to renew key - " + e.getMessage());

            // If key was just approaching expiry but still valid, we can continue
            if (keyManager.isWorkingKeyLoaded() && !keyManager.isKeyMissingOrExpired()) {
                Log.w(TAG, "ensureValidWorkingKey: Renewal failed but existing key still valid");
                return true;
            }

            return false;
        }
    }

    /**
     * Gets the current working key status for display.
     *
     * @return Human-readable key status string
     */
    public String getKeyStatus() {
        if (keyManager == null) {
            return "Key manager not initialized";
        }
        return keyManager.getKeyStatus();
    }

    /**
     * Checks if the working key is loaded and valid.
     *
     * @return true if a valid working key is available
     */
    public boolean hasValidWorkingKey() {
        return keyManager != null &&
               keyManager.isWorkingKeyLoaded() &&
               !keyManager.isKeyMissingOrExpired();
    }

    // =========================================================================
    // Health Check
    // =========================================================================

    /**
     * Sends a health check to verify host connectivity (Type 89 - standard Hyosung).
     */
    public void sendHealthCheck() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HealthCheckRequest request = HealthCheckRequest.create(config.getTerminalId());
                    request.setRoutingId(config.getRoutingId());

                    HealthCheckResponse response = connection.sendHealthCheck(request);

                    if (response.isOk()) {
                        Log.d(TAG, "Health check successful");
                        notifyHealthCheckResult(true);
                    } else {
                        Log.w(TAG, "Health check returned status: " + response.getStatus());
                        notifyHealthCheckResult(false);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Health check failed: " + e.getMessage());
                    notifyHealthCheckResult(false);
                }
            }
        });
    }

    /**
     * Sends a status monitoring request (Type H0 - EFX/Switch Commerce style).
     * This is the preferred method for testing connectivity with EFX processors.
     */
    public void sendStatusMonitoring() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Sending status monitoring request...");
                    boolean success = connection.sendStatusMonitoring();

                    if (success) {
                        Log.d(TAG, "Status monitoring successful");
                        notifyHealthCheckResult(true);
                    } else {
                        Log.w(TAG, "Status monitoring returned no response");
                        notifyHealthCheckResult(false);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Status monitoring failed: " + e.getMessage(), e);
                    notifyHealthCheckResult(false);
                }
            }
        });
    }

    /**
     * Starts the H0 heartbeat scheduler.
     * Sends H0 status monitoring messages at the configured interval.
     */
    public void startHeartbeat() {
        if (heartbeatRunning) {
            Log.d(TAG, "Heartbeat already running");
            return;
        }

        int intervalMs = config.getHealthCheckIntervalMs();
        if (intervalMs <= 0) {
            intervalMs = 360000; // Default 6 minutes
        }

        Log.d(TAG, "Starting H0 heartbeat scheduler (interval: " + intervalMs + "ms)");

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Sending H0 heartbeat...");
                    boolean success = connection.sendStatusMonitoring();
                    if (success) {
                        Log.d(TAG, "H0 heartbeat successful");
                    } else {
                        Log.w(TAG, "H0 heartbeat failed - no response");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "H0 heartbeat error: " + e.getMessage());
                }
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        heartbeatRunning = true;
        Log.d(TAG, "H0 heartbeat scheduler started");
    }

    /**
     * Stops the H0 heartbeat scheduler.
     */
    public void stopHeartbeat() {
        if (!heartbeatRunning) {
            return;
        }

        Log.d(TAG, "Stopping H0 heartbeat scheduler");

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            heartbeatScheduler = null;
        }

        heartbeatRunning = false;
        Log.d(TAG, "H0 heartbeat scheduler stopped");
    }

    /**
     * Checks if the heartbeat scheduler is running.
     */
    public boolean isHeartbeatRunning() {
        return heartbeatRunning;
    }

    // =========================================================================
    // Host Totals
    // =========================================================================

    /**
     * Callback interface for host totals response.
     */
    public interface HostTotalsCallback {
        void onHostTotalsReceived(HostTotalsResponse response);
        void onError(String error);
    }

    /**
     * Requests host totals (Type 87) from the processor.
     *
     * @param reset If true, resets totals after query; if false, query only
     * @param callback Callback for the response
     */
    public void requestHostTotals(final boolean reset, final HostTotalsCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Requesting host totals (reset=" + reset + ")...");

                    // Force fresh connection for host totals requests
                    // Some hosts close connection after each request
                    if (connection.isConnected()) {
                        Log.d(TAG, "Disconnecting stale connection before host totals request...");
                        connection.disconnect();
                    }
                    Log.d(TAG, "Connecting for host totals request...");
                    connection.connect();

                    // Send host totals request — protocol-appropriate path
                    HostTotalsResponse response;
                    AtmProtocol protocol = connection.getProtocol();
                    if (protocol != null && config.isTritonProtocol()) {
                        Log.d(TAG, "Using Triton host totals request");
                        byte[] requestMsg = protocol.buildHostTotalsRequest(
                            config.getTerminalId(), 0, reset, 0, 0, 0, 0);
                        byte[] responseMsg = connection.sendAndReceiveRaw(requestMsg);
                        connection.completeTritonHandshake();
                        response = protocol.parseHostTotalsResponse(responseMsg);
                    } else {
                        Log.d(TAG, "Using Hyosung host totals request");
                        response = connection.sendHostTotalsRequest(reset);
                    }
                    Log.d(TAG, "Host totals response: " + response);

                    if (callback != null) {
                        callback.onHostTotalsReceived(response);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Host totals request failed: " + e.getMessage(), e);
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                }
            }
        });
    }

    // =========================================================================
    // Reversal
    // =========================================================================

    /**
     * Reports a dispense failure for the current transaction.
     * If transaction was approved, stores a pending reversal.
     *
     * Call this when cash dispense fails after host approval.
     */
    public void reportDispenseFailure() {
        if (!approvalReceived) {
            Log.d(TAG, "No approval received - no reversal needed for dispense failure");
            return;
        }

        Log.w(TAG, "Dispense failure reported after approval - storing reversal");
        storePendingReversalIfNeeded(HyosungProtocol.REV_REASON_DISPENSE_FAIL);
    }

    /**
     * Reports a partial dispense for the current transaction.
     * If transaction was approved, stores a pending reversal.
     *
     * Call this when only partial cash was dispensed.
     */
    public void reportPartialDispense() {
        if (!approvalReceived) {
            Log.d(TAG, "No approval received - no reversal needed for partial dispense");
            return;
        }

        Log.w(TAG, "Partial dispense reported after approval - storing reversal");
        storePendingReversalIfNeeded(HyosungProtocol.REV_REASON_PARTIAL_DISPENSE);
    }

    /**
     * Reports a customer cancellation for the current transaction.
     * If transaction was approved, stores a pending reversal.
     *
     * Call this when customer cancels after host approval but before completion.
     */
    public void reportCustomerCancel() {
        if (!approvalReceived) {
            Log.d(TAG, "No approval received - no reversal needed for customer cancel");
            return;
        }

        Log.w(TAG, "Customer cancel reported after approval - storing reversal");
        storePendingReversalIfNeeded(HyosungProtocol.REV_REASON_CUSTOMER_CANCEL);
    }

    /**
     * Sends a reversal for the current transaction.
     *
     * @param reason Reversal reason code
     */
    /**
     * Sends a fully-constructed reversal that was built from a persisted record
     * (i.e. survives an app restart, unlike the in-memory currentRequest/
     * currentResponse path used by {@link #sendReversal(String)}). Called by
     * {@code AtmHostService.sendReversalSync} when draining records that were
     * written to disk in a previous session.
     *
     * @return true if the host accepted the reversal (response code "00")
     */
    public boolean sendReversalDirect(ReversalRequest reversal) {
        if (reversal == null) {
            Log.w(TAG, "sendReversalDirect: null reversal request");
            return false;
        }
        try {
            ReversalResponse response = sendReversalWithRetry(reversal);
            if (response != null && response.isAccepted()) {
                Log.d(TAG, "Reversal accepted (direct, responseCode="
                        + response.getResponseCode() + ")");
                notifyReversalComplete(true);
                return true;
            } else {
                String code = (response != null) ? response.getResponseCode() : "(null response)";
                String desc = (response != null) ? response.getResponseDescription() : "no response object";
                Log.w(TAG, "Reversal not accepted (direct) — responseCode=" + code + " (" + desc + ")");
                notifyReversalComplete(false);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Reversal direct send failed: " + e.getMessage());
            notifyReversalComplete(false);
            return false;
        }
    }

    public void sendReversal(final String reason) {
        if (currentRequest == null || currentResponse == null) {
            Log.w(TAG, "No transaction to reverse");
            return;
        }

        notifyProgress("Processing reversal...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ReversalRequest reversal = ReversalRequest.createFromTransaction(
                        currentRequest, currentResponse, reason);

                    ReversalResponse response = sendReversalWithRetry(reversal);

                    if (response != null && response.isAccepted()) {
                        Log.d(TAG, "Reversal accepted (responseCode=" + response.getResponseCode() + ")");
                        notifyReversalComplete(true);
                    } else {
                        // Surface enough detail to identify *why* the host rejected — we
                        // were previously logging just "not accepted" which hides whether
                        // it's a wire/parse issue or a real host-side decision.
                        String code = (response != null) ? response.getResponseCode() : "(null response)";
                        String desc = (response != null) ? response.getResponseDescription() : "no response object";
                        Log.w(TAG, "Reversal not accepted — responseCode=" + code + " (" + desc + ")");
                        notifyReversalComplete(false);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Reversal failed: " + e.getMessage());
                    notifyReversalComplete(false);
                }
            }
        });
    }

    /**
     * Sends reversal with retry logic.
     */
    private ReversalResponse sendReversalWithRetry(ReversalRequest reversal)
            throws AtmHostConnection.ConnectionException, HyosungMessageParser.ParseException {

        int maxRetries = config.getMaxReversalRetries();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Log.d(TAG, "Reversal attempt " + attempt + " of " + maxRetries);
                return connection.sendReversal(reversal);
            } catch (AtmHostConnection.ConnectionException e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                Log.w(TAG, "Reversal attempt " + attempt + " failed, retrying...");
                try {
                    Thread.sleep(config.getRetryDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AtmHostConnection.ConnectionException("Interrupted");
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Response Processing
    // =========================================================================

    /**
     * Processes a transaction response.
     */
    private void processTransactionResponse(TransactionResponse response, long requestedAmount) {
        Log.d(TAG, "processTransactionResponse: responseCode=[" + response.getResponseCode() + "]" +
              ", isApproved=" + response.isApproved() +
              ", displayMessage=[" + response.getDisplayMessage() + "]");
        if (response.isApproved()) {
            // Track that we received approval - dispense failure after this needs reversal
            approvalReceived = true;
            Log.d(TAG, "Transaction APPROVED - responseCode=" + response.getResponseCode());

            // Check for partial approval
            if (response.isPartialApproval()) {
                Log.d(TAG, "Partial approval - may need to adjust dispense amount");
            }

            // Check for configuration required - schedule key download
            if (response.requiresConfiguration()) {
                Log.d(TAG, "Configuration required - scheduling key download");
                scheduleKeyRenewal();
            }

            // Clean approval — no reversal needed; clear the pre-persisted record
            // (Dispense failure or customer cancel after this point will create
            // a new reversal via storePendingReversalIfNeeded.)
            clearPreSendReversal();

            notifyApproved(response);

        } else {
            Log.d(TAG, "Transaction DECLINED: " + response.getResponseCode());

            // Check for card retention
            if (response.shouldRetainCard()) {
                Log.w(TAG, "Card should be retained");
            }

            // Check for key sync
            if (response.requiresKeySync()) {
                Log.d(TAG, "Key sync required - initiating key download");
                downloadKeys();
            }

            // Decline = host received and explicitly rejected.
            // No reversal needed; clear the pre-persisted record.
            clearPreSendReversal();

            notifyDeclined(response);
        }
    }

    /**
     * Processes a balance inquiry response.
     */
    private void processBalanceInquiryResponse(TransactionResponse response) {
        Log.d(TAG, "processBalanceInquiryResponse: responseCode=" + response.getResponseCode() +
              ", isApproved=" + response.isApproved());
        if (response.isApproved()) {
            Log.d(TAG, "processBalanceInquiryResponse: APPROVED - calling notifyBalanceReceived");
            // Clean approval — clear pre-persisted reversal
            clearPreSendReversal();
            notifyBalanceReceived(
                response.getResponseCode(),
                response.getAccountBalanceCents(),
                response.getAvailableBalanceCents()
            );
        } else {
            Log.d(TAG, "processBalanceInquiryResponse: DECLINED - calling notifyDeclined");

            // Key sync trigger (parity with processTransactionResponse): code 76
            // signals "your key doesn't match what host expects" → re-download
            if (response.requiresKeySync()) {
                Log.w(TAG, "processBalanceInquiryResponse: Key sync required (code 76) - initiating key download");
                downloadKeys();
            }

            clearPreSendReversal();
            notifyDeclined(response);
        }
    }

    /**
     * Handles connection errors.
     */
    private void handleConnectionError(AtmHostConnection.ConnectionException e) {
        Log.e(TAG, "Connection error during transaction");

        // For timeout errors, we may need to send a reversal
        if (e.getMessage() != null && e.getMessage().contains("timeout")) {
            Log.w(TAG, "Timeout occurred - reversal may be required");
            // The reversal would be sent if we had approved a transaction
        }

        notifyError("Connection error: " + e.getMessage());
    }

    // =========================================================================
    // Connection Management
    // =========================================================================

    /**
     * Connects to the host processor.
     */
    public void connect() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connection.connect();
                    notifyProgress("Connected to " + config.getName());
                } catch (AtmHostConnection.ConnectionException e) {
                    notifyError("Failed to connect: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Disconnects from the host processor.
     */
    public void disconnect() {
        connection.disconnect();
    }

    /**
     * Checks if connected to host.
     */
    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * Disconnects from the host UNLESS keep-alive is enabled in the processor
     * config. Task #13: BlueVerse skips Open when the host is already open, but
     * that only works if we don't tear down the connection after every
     * transaction. With keep-alive on, the connection is retained between
     * transactions. With keep-alive off (default), this method behaves identically
     * to {@link AtmHostConnection#disconnect()}.
     */
    public void disconnectUnlessKeepAlive() {
        if (config != null && config.isKeepAlive()) {
            Log.d(TAG, "disconnectUnlessKeepAlive: keep-alive on — retaining connection");
            return;
        }
        connection.disconnect();
    }

    /**
     * Checks if a transaction is in progress.
     */
    public boolean isTransactionInProgress() {
        return transactionInProgress.get();
    }

    /**
     * Shuts down the transaction manager.
     */
    public void shutdown() {
        disconnect();
        executor.shutdown();
    }

    // =========================================================================
    // Notification Methods (callbacks on main thread)
    // =========================================================================

    private void notifyProgress(final String message) {
        Log.d(TAG, "Progress: " + message);
        if (listener != null) {
            // Call directly - listener handles thread safety
            try {
                listener.onProgress(message);
            } catch (Exception e) {
                Log.e(TAG, "Error in onProgress callback: " + e.getMessage());
            }
        }
    }

    private void notifyError(final String error) {
        Log.e(TAG, "Error: " + error);
        Log.d(TAG, "notifyError: listener=" + (listener != null ? "SET" : "NULL"));
        if (listener != null) {
            // Call directly - critical for latch countdown
            try {
                Log.d(TAG, "notifyError: calling listener.onError()...");
                listener.onError(error);
                Log.d(TAG, "notifyError: listener.onError() returned");
            } catch (Exception e) {
                Log.e(TAG, "Error in onError callback: " + e.getMessage(), e);
            }
        } else {
            Log.e(TAG, "notifyError: LISTENER IS NULL - cannot notify!");
        }
    }

    private void notifyApproved(final TransactionResponse response) {
        if (listener != null) {
            // Call directly - critical for latch countdown
            try {
                listener.onApproved(response);
            } catch (Exception e) {
                Log.e(TAG, "Error in onApproved callback: " + e.getMessage());
            }
        }
    }

    private void notifyDeclined(final TransactionResponse response) {
        Log.d(TAG, "notifyDeclined: listener=" + (listener != null ? "SET" : "NULL") +
              ", responseCode=" + response.getResponseCode());
        if (listener != null) {
            // Call directly - critical for latch countdown
            try {
                Log.d(TAG, "notifyDeclined: calling listener.onDeclined...");
                listener.onDeclined(response);
                Log.d(TAG, "notifyDeclined: listener.onDeclined returned");
            } catch (Exception e) {
                Log.e(TAG, "Error in onDeclined callback: " + e.getMessage(), e);
            }
        } else {
            Log.e(TAG, "notifyDeclined: LISTENER IS NULL - cannot notify decline!");
        }
    }

    private void notifyBalanceReceived(final String responseCode, final long accountBalance, final long availableBalance) {
        if (listener != null) {
            // Call directly - critical for latch countdown
            try {
                listener.onBalanceReceived(responseCode, accountBalance, availableBalance);
            } catch (Exception e) {
                Log.e(TAG, "Error in onBalanceReceived callback: " + e.getMessage());
            }
        }
    }

    private void notifyConfigComplete(final ConfigResponse response) {
        if (listener != null) {
            // Call directly
            try {
                listener.onConfigurationComplete(response);
            } catch (Exception e) {
                Log.e(TAG, "Error in onConfigurationComplete callback: " + e.getMessage());
            }
        }
    }

    private void notifyReversalComplete(final boolean success) {
        if (listener != null) {
            // Call directly
            try {
                listener.onReversalComplete(success);
            } catch (Exception e) {
                Log.e(TAG, "Error in onReversalComplete callback: " + e.getMessage());
            }
        }
    }

    private void notifyHealthCheckResult(final boolean success) {
        if (listener != null) {
            // Call directly
            try {
                listener.onHealthCheckResult(success);
            } catch (Exception e) {
                Log.e(TAG, "Error in onHealthCheckResult callback: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Converts a byte array to a hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // =========================================================================
    // Listener Interface
    // =========================================================================

    /**
     * Listener for transaction events.
     */
    public interface TransactionListener {
        void onProgress(String message);
        void onError(String error);
        void onApproved(TransactionResponse response);
        void onDeclined(TransactionResponse response);
        void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents);
        void onConfigurationComplete(ConfigResponse response);
        void onReversalComplete(boolean success);
        void onHealthCheckResult(boolean success);
    }
}
