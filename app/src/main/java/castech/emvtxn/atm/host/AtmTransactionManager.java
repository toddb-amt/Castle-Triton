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
    private boolean transactionInProgress;

    // Reversal tracking state
    private volatile boolean requestSentToHost;      // True after request sent, before response
    private volatile boolean approvalReceived;       // True if host approved the transaction
    private long currentAmountCents;                 // Amount for current transaction
    private long currentSurchargeCents;              // Surcharge for current transaction

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
        this.transactionInProgress = false;

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
    }

    /**
     * Stores a pending reversal for later processing.
     * Called when transaction fails after request was sent to host.
     *
     * @param reason Reversal reason code (see HyosungProtocol.REV_REASON_*)
     */
    private void storePendingReversalIfNeeded(String reason) {
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
        int seq = sequenceNumber.getAndIncrement();
        if (seq > 9999) {
            sequenceNumber.set(1);
            seq = 1;
        }
        return seq;
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

        if (transactionInProgress) {
            notifyError("Transaction already in progress");
            return;
        }

        transactionInProgress = true;
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

                    // Mark that we're sending to host - if we fail after this, may need reversal
                    requestSentToHost = true;

                    // Send to host
                    TransactionResponse response = connection.sendTransaction(request);
                    currentResponse = response;

                    // Process response
                    processTransactionResponse(response, amountCents);

                } catch (AtmHostConnection.ConnectionException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    // If request was sent, we may need a reversal (timeout = possible approval)
                    if (requestSentToHost) {
                        boolean isTimeout = e.getMessage() != null &&
                            (e.getMessage().contains("timeout") || e.getMessage().contains("Timeout"));
                        String reason = isTimeout ? HyosungProtocol.REV_REASON_TIMEOUT :
                                                   HyosungProtocol.REV_REASON_HOST_ERROR;
                        Log.w(TAG, "Connection error after sending - storing reversal (reason: " + reason + ")");
                        storePendingReversalIfNeeded(reason);
                    }
                    handleConnectionError(e);
                } catch (HyosungMessageParser.ParseException e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    // Parse error after sending = possible approval, need reversal
                    if (requestSentToHost) {
                        Log.w(TAG, "Parse error after sending - storing reversal");
                        storePendingReversalIfNeeded(HyosungProtocol.REV_REASON_HOST_ERROR);
                    }
                    notifyError("Invalid response from host: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, "Transaction error: " + e.getMessage());
                    // Generic error after sending = possible approval, need reversal
                    if (requestSentToHost) {
                        Log.w(TAG, "Transaction error after sending - storing reversal");
                        storePendingReversalIfNeeded(HyosungProtocol.REV_REASON_HOST_ERROR);
                    }
                    notifyError("Transaction failed: " + e.getMessage());
                } finally {
                    // Always disconnect - server closes connection after each transaction
                    connection.disconnect();
                    Log.d(TAG, "Disconnected after cash withdrawal");
                    transactionInProgress = false;
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

        if (transactionInProgress) {
            notifyError("Transaction already in progress");
            return;
        }

        transactionInProgress = true;
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

                    // Debug: Log track 2 before creating request
                    String track2Value = cardData.getTrack2ForTransaction();
                    Log.d(TAG, "DEBUG TRACK2 (BI): cardData.getTrack2ForTransaction() = [" + track2Value + "]");

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

                    // Debug: Log track 2 after setting in request
                    Log.d(TAG, "DEBUG TRACK2 (BI): request.getTrack2Data() = [" + request.getTrack2Data() + "]");

                    currentRequest = request;
                    notifyProgress("Connecting to host...");

                    TransactionResponse response = connection.sendTransaction(request);
                    currentResponse = response;

                    processBalanceInquiryResponse(response);

                } catch (AtmHostConnection.ConnectionException e) {
                    handleConnectionError(e);
                } catch (HyosungMessageParser.ParseException e) {
                    notifyError("Invalid response from host: " + e.getMessage());
                } catch (Exception e) {
                    notifyError("Balance inquiry failed: " + e.getMessage());
                } finally {
                    // Always disconnect - server closes connection after each transaction
                    connection.disconnect();
                    Log.d(TAG, "Disconnected after balance inquiry");
                    transactionInProgress = false;
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
                    ConfigRequest request = new ConfigRequest();
                    request.setTerminalId(config.getTerminalId());
                    request.setRoutingId(config.getRoutingId());
                    request.setConfigType(configType);

                    ConfigResponse response = connection.sendConfigRequest(request);

                    // Disconnect after config - server may close after handshake
                    // This forces fresh connection for subsequent transactions
                    connection.disconnect();
                    Log.d(TAG, "Disconnected after config request (server closes after handshake)");

                    // Load the working key
                    if (keyManager.loadWorkingKey(response)) {
                        notifyProgress("Working key loaded successfully");
                        notifyConfigComplete(response);
                    } else {
                        notifyError("Failed to load working key");
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
    public void downloadKeysSync() throws Exception {
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

                ConfigRequest request = new ConfigRequest();
                request.setTerminalId(config.getTerminalId());
                request.setRoutingId(config.getRoutingId());
                request.setConfigType(HyosungProtocol.CONFIG_KEY_ONLY);

                ConfigResponse response = connection.sendConfigRequest(request);

                // Disconnect after config - server may close after handshake
                connection.disconnect();
                Log.d(TAG, "Disconnected after sync config request");

                // Load the working key
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

                    // Send host totals request using connection's method
                    HostTotalsResponse response = connection.sendHostTotalsRequest(reset);
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
                        Log.d(TAG, "Reversal accepted");
                        notifyReversalComplete(true);
                    } else {
                        Log.w(TAG, "Reversal not accepted");
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
            notifyBalanceReceived(
                response.getResponseCode(),
                response.getAccountBalanceCents(),
                response.getAvailableBalanceCents()
            );
        } else {
            Log.d(TAG, "processBalanceInquiryResponse: DECLINED - calling notifyDeclined");
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
     * Checks if a transaction is in progress.
     */
    public boolean isTransactionInProgress() {
        return transactionInProgress;
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
