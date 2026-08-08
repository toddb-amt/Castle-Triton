package castech.emvtxn.atm.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import castech.emvtxn.BuildConfig;

/**
 * ATM Host Service
 *
 * Main entry point for ATM host communication.
 * Provides a simplified API for the app to perform ATM transactions.
 *
 * Usage:
 *   // Initialize
 *   AtmHostService atm = new AtmHostService(context);
 *   atm.initialize(ProcessorConfig.forDns("host.example.com", "TERM001"));
 *   atm.setEventListener(listener);
 *
 *   // Connect and download keys
 *   atm.connect();
 *   atm.downloadKeys();
 *
 *   // Perform transaction
 *   atm.performWithdrawal(cardData, 10000, 100, "CA"); // $100.00 + $1.00 fee from checking
 */
public class AtmHostService {

    private static final String TAG = "AtmHostService";

    // Context
    private final Context context;

    // Components
    private ProcessorConfig config;
    private CastleKeyManager keyManager;
    private AtmTransactionManager transactionManager;
    private ReversalPersistenceManager reversalManager;
    private final AtmSessionStateMachine sessionState;

    /**
     * Single-thread executor for the reversal drain loop. Separate from the
     * transaction executor so the drain can run after a transaction completes
     * without blocking the transaction executor itself.
     */
    private final ExecutorService reversalDrainExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ReversalDrain");
                t.setDaemon(true);
                return t;
            });

    /**
     * Scheduled executor for the periodic Type 89 health check (task #8).
     * Sends a health check ping on a configurable interval to keep host
     * connection state fresh and detect connection drops between transactions.
     */
    private ScheduledExecutorService healthCheckScheduler;
    private ScheduledFuture<?> healthCheckTask;
    private volatile boolean healthCheckRunning = false;

    // State
    private boolean initialized;
    private volatile boolean processingReversals;
    private volatile boolean keyDownloadInProgress;

    // Listener
    private AtmEventListener listener;

    /**
     * Creates a new ATM Host Service.
     *
     * @param context Android context
     */
    public AtmHostService(Context context) {
        this.context = context;
        this.initialized = false;
        this.reversalManager = new ReversalPersistenceManager(context);
        this.sessionState = new AtmSessionStateMachine();
    }

    /**
     * Returns the session state machine. Exposes the current ATM session phase
     * (IDLE / OPENING / READY / TRANSACTION / REVERSAL_RECOVERY / etc.) for
     * external observers (UI, diagnostics).
     *
     * <p>Mirrors the outer state machine observed in deployed Hyosung BlueVerse
     * software (FUN_0006a280) — see
     * {@code docs/HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md} §11.</p>
     */
    public AtmSessionStateMachine getSessionState() {
        return sessionState;
    }

    /**
     * Initializes the service with processor configuration.
     *
     * @param processorConfig Configuration for the processor
     * @return true if initialization successful
     */
    public boolean initialize(ProcessorConfig processorConfig) {
        if (initialized) {
            Log.w(TAG, "Already initialized");
            return true;
        }

        try {
            // Apply protocol type from GlobalPara setting
            if ("TRITON".equals(castech.emvtxn.GlobalPara.atmProtocolType)) {
                processorConfig.setProtocolType(ProcessorConfig.ProtocolType.TRITON_STANDARD);
                // Triton uses Master/Session keys — TMK at CFFF/0000
                castech.emvtxn.GlobalPara.atmDukptKeySet = 0x0000CFFF;
                castech.emvtxn.GlobalPara.atmDukptKeyIndex = 0x00000000;
                castech.emvtxn.GlobalPara.onlinePinKeySet = 0x0000CFFF;
                castech.emvtxn.GlobalPara.onlinePinKeyIndex = 0x00000000;
                Log.d(TAG, "Triton mode: PIN key set to CFFF/0000 (TMK)");
            } else {
                processorConfig.setProtocolType(ProcessorConfig.ProtocolType.HYOSUNG_STD1);
                castech.emvtxn.GlobalPara.atmDukptKeySet = 0x0000C000;
                castech.emvtxn.GlobalPara.atmDukptKeyIndex = 0x00000000;
                castech.emvtxn.GlobalPara.onlinePinKeySet = 0x0000C000;
                castech.emvtxn.GlobalPara.onlinePinKeyIndex = 0x00000000;
            }

            // Build flavor determines key mode (DUKPT vs MKSK)
            if ("DUKPT".equals(BuildConfig.KEY_MODE)) {
                castech.emvtxn.GlobalPara.atmDukptEnabled = true;
                Log.d(TAG, "Build flavor [DUKPT]: hardware DUKPT mode enabled");
            } else {
                castech.emvtxn.GlobalPara.atmDukptEnabled = false;
                Log.d(TAG, "Build flavor [MKSK]: Master/Session mode enabled");
            }

            Log.d(TAG, "Initializing ATM Host Service for " + processorConfig.getName() +
                      " (protocol: " + processorConfig.getProtocolType() + ")");

            this.config = processorConfig;

            // Initialize key manager
            keyManager = new CastleKeyManager(context);
            if (!keyManager.initialize()) {
                Log.e(TAG, "Failed to initialize key manager");
                return false;
            }

            // Initialize transaction manager
            transactionManager = new AtmTransactionManager(config, keyManager);
            transactionManager.setTransactionListener(new InternalTransactionListener());
            transactionManager.setReversalManager(reversalManager);

            // Note: H0 heartbeat is sent before each transaction, not on a schedule
            // The scheduled heartbeat can be started manually after key download if needed:
            // transactionManager.startHeartbeat();

            initialized = true;
            Log.d(TAG, "ATM Host Service initialized successfully");

            // Task #10: load operator-overridable reversal config from SharedPreferences
            loadReversalConfigFromPrefs();

            // Task #8: auto-start periodic Type 89 health check if enabled
            if (processorConfig.isHealthCheckEnabled()) {
                startPeriodicHealthCheck();
            }

            // Task #7: cleanup expired reversal journal files at startup
            if (reversalManager != null && reversalManager.getJournal() != null) {
                try {
                    reversalManager.getJournal().cleanupOldFiles();
                } catch (Throwable t) {
                    Log.w(TAG, "Journal cleanup at startup failed: " + t.getMessage());
                }
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Initialization failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the event listener.
     */
    public void setEventListener(AtmEventListener listener) {
        Log.d(TAG, "setEventListener: setting listener class=" +
              (listener != null ? listener.getClass().getName() : "NULL"));
        this.listener = listener;
    }

    /**
     * Gets the current event listener.
     */
    public AtmEventListener getEventListener() {
        return this.listener;
    }

    /**
     * Checks if the service is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the processor configuration.
     */
    public ProcessorConfig getConfig() {
        return config;
    }

    // =========================================================================
    // Connection Methods
    // =========================================================================

    /**
     * Connects to the host processor.
     */
    public void connect() {
        ensureInitialized();
        transactionManager.connect();
    }

    /**
     * Disconnects from the host processor.
     */
    public void disconnect() {
        stopPeriodicHealthCheck();
        if (transactionManager != null) {
            transactionManager.disconnect();
        }
    }

    /**
     * Checks if connected to the host.
     */
    public boolean isConnected() {
        return transactionManager != null && transactionManager.isConnected();
    }

    // =========================================================================
    // Key Management
    // =========================================================================

    /**
     * Downloads working keys from the host.
     */
    public void downloadKeys() {
        ensureInitialized();
        transactionManager.downloadKeys();
    }

    /**
     * Checks if working keys are loaded.
     */
    public boolean hasWorkingKeys() {
        return keyManager != null && keyManager.isWorkingKeyLoaded();
    }

    /**
     * Gets the key manager for diagnostics.
     */
    public CastleKeyManager getKeyManager() {
        return keyManager;
    }

    /**
     * Clears the current working key.
     * This invalidates the key but does not request a new one from host.
     */
    public void clearWorkingKey() {
        if (keyManager != null) {
            keyManager.clearWorkingKey();
            Log.d(TAG, "Working key cleared");
        }
    }

    /**
     * Checks if the working key needs renewal and downloads if necessary.
     * Call this on app startup and before transactions.
     *
     * @return true if key is valid (already valid or successfully renewed)
     */
    public boolean ensureValidWorkingKey() {
        ensureInitialized();
        return transactionManager.ensureValidWorkingKey();
    }

    /**
     * Gets the current working key status for display/logging.
     *
     * @return Human-readable status string
     */
    public String getKeyStatus() {
        if (keyManager == null) {
            return "Not initialized";
        }
        return keyManager.getKeyStatus();
    }

    /**
     * Checks if the working key is valid and not approaching expiry.
     *
     * @return true if key is fully valid, false if missing, expired, or needs renewal soon
     */
    public boolean hasValidWorkingKey() {
        return transactionManager != null && transactionManager.hasValidWorkingKey();
    }

    /**
     * Checks if key renewal is recommended (expired, missing, or approaching expiry).
     *
     * @return true if renewal should be performed
     */
    public boolean needsKeyRenewal() {
        return keyManager != null && keyManager.needsRenewal();
    }

    /**
     * Performs startup key renewal if needed.
     * This should be called after initialization when the app starts.
     *
     * @param callback Callback for renewal result
     */
    public void performStartupKeyRenewal(final KeyRenewalCallback callback) {
        ensureInitialized();

        if (keyManager == null) {
            if (callback != null) {
                callback.onRenewalFailed("Key manager not initialized");
            }
            return;
        }

        // DUKPT mode doesn't need working key download — skip startup renewal
        if (castech.emvtxn.GlobalPara.atmDukptEnabled) {
            Log.d(TAG, "Startup key check: DUKPT mode — no working key renewal needed");
            if (callback != null) {
                callback.onRenewalSuccess("DUKPT mode - hardware key");
            }
            return;
        }

        // Like a deployed ATM: ALWAYS attempt a fresh key request at startup,
        // regardless of whether the persisted key looks valid. This catches
        // host-side key rotation that happened while the terminal was off.
        Log.d(TAG, "Startup key request: forcing fresh Type 88 download (ATM startup pattern)");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Clear any cached key state so the download fully refreshes
                    if (keyManager != null) {
                        keyManager.clearWorkingKey();
                    }
                    transactionManager.downloadKeysSync();
                    Log.d(TAG, "Startup key renewal completed successfully");
                    if (callback != null) {
                        callback.onRenewalSuccess(keyManager.getKeyStatus());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Startup key renewal failed: " + e.getMessage());
                    if (callback != null) {
                        callback.onRenewalFailed(e.getMessage());
                    }
                }
            }
        }).start();
    }


    /**
     * Callback interface for key renewal operations.
     */
    public interface KeyRenewalCallback {
        void onRenewalSuccess(String keyStatus);
        void onRenewalFailed(String error);
    }

    /**
     * Requests the clear PAN from the server for PIN block creation.
     * This is needed because the Castle SDK masks the PAN for PCI compliance.
     *
     * @param maskedPan The masked PAN (e.g., "443041******8318")
     * @param encryptedTrack2 The encrypted Track 2 data (if available)
     * @return The clear PAN, or null if lookup failed
     */
    public String requestPanLookup(String maskedPan, String encryptedTrack2) {
        ensureInitialized();

        Log.d(TAG, "Requesting PAN lookup from server...");
        Log.d(TAG, "  Masked PAN: " + maskedPan);

        try {
            // Build the PAN lookup URL
            String baseUrl = "http://" + config.getHost() + ":" + config.getPort();
            String lookupUrl = baseUrl + "/pan-lookup";

            // Create JSON request body
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"terminalId\":\"").append(config.getTerminalId()).append("\",");
            json.append("\"maskedPan\":\"").append(maskedPan != null ? maskedPan : "").append("\",");
            json.append("\"encryptedTrack2\":\"").append(encryptedTrack2 != null ? encryptedTrack2 : "").append("\"");
            json.append("}");

            Log.d(TAG, "  Request: " + lookupUrl);

            // Make HTTP POST request
            java.net.URL url = new java.net.URL(lookupUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // Send request
            java.io.OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.close();

            // Read response
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "  Response code: " + responseCode);

            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse JSON response - simple parsing for {"pan":"XXXXXXXXXXXXXXXX"}
                String responseStr = response.toString();
                Log.d(TAG, "  Response: " + responseStr);

                // Extract PAN from JSON
                int panStart = responseStr.indexOf("\"pan\":\"");
                if (panStart >= 0) {
                    panStart += 7;
                    int panEnd = responseStr.indexOf("\"", panStart);
                    if (panEnd > panStart) {
                        String clearPan = responseStr.substring(panStart, panEnd);
                        if (clearPan.length() >= 13 && clearPan.length() <= 19) {
                            Log.d(TAG, "  Clear PAN received: " + clearPan.substring(0, 6) + "******" + clearPan.substring(clearPan.length() - 4));
                            return clearPan;
                        }
                    }
                }

                Log.e(TAG, "  Invalid PAN in response");
                return null;

            } else {
                Log.e(TAG, "  PAN lookup failed with code: " + responseCode);
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "PAN lookup error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Requests a new working key from the host.
     * This clears the current working key and downloads a fresh one.
     * Use this when key sync issues occur or when security requires a new key.
     */
    public void requestNewWorkingKey() {
        ensureInitialized();

        boolean isDukpt = castech.emvtxn.GlobalPara.atmDukptEnabled;
        if (isDukpt) {
            Log.d(TAG, "DUKPT mode — sending Type 88 to host for KSN sync (no working key load)");
        } else {
            Log.d(TAG, "Requesting new working key...");
            // Clear the current key first (MKSK only)
            if (keyManager != null) {
                keyManager.clearWorkingKey();
                Log.d(TAG, "Current working key cleared");
            }
        }

        // Request new key from host — single sync path, guarded against concurrent calls
        synchronized (this) {
            if (keyDownloadInProgress) {
                Log.w(TAG, "Key download already in progress — skipping duplicate request");
                return;
            }
            keyDownloadInProgress = true;
        }

        new Thread(() -> {
            try {
                transactionManager.downloadKeysSync();
                if (hasWorkingKeys()) {
                    Log.d(TAG, "Working key downloaded successfully");
                    if (listener != null) {
                        listener.onKeysLoaded("OK");
                    }
                } else {
                    notifyError("Failed to download working key");
                }
            } catch (Exception e) {
                Log.e(TAG, "Key download error: " + e.getMessage());
                notifyError("Key download failed: " + e.getMessage());
            } finally {
                synchronized (AtmHostService.this) {
                    keyDownloadInProgress = false;
                }
            }
        }, "KeyDownload").start();
    }

    // =========================================================================
    // Session / Open Flow (BlueVerse compliance — tasks #12, #14)
    // =========================================================================

    /**
     * Open-failure cooldown in milliseconds. Matches BlueVerse's hardcoded
     * {@code Sleep(60000)} in {@code fnAPP_MainOpenProc} after Open failure.
     * Customer interaction is blocked during this window.
     *
     * <p>BlueVerse model: ONE Open attempt per session; on failure, sleep the
     * cooldown then return failure. The outer state machine (state transitions
     * driven by next customer attempt) decides whether to retry.</p>
     */
    private static final long OPEN_FAILURE_COOLDOWN_MS = 60_000L;

    /**
     * Runs the Open procedure with the BlueVerse-style failure cooldown:
     * one attempt, on failure sleep 60 seconds then return failure. The
     * 60-second cooldown blocks customer interaction during the failure window.
     *
     * <p>Matches BlueVerse {@code fnAPP_MainOpenProc} exactly — no internal
     * retry loop; the outer state machine handles re-attempt on next customer
     * approach.</p>
     *
     * @return true if Open succeeded; false (after cooldown) if it failed
     */
    public boolean openSessionWithRetry() {
        if (openSession()) {
            return true;
        }
        Log.w(TAG, "openSessionWithRetry: Open failed, sleeping "
                + (OPEN_FAILURE_COOLDOWN_MS / 1000) + "s cooldown");
        try {
            Thread.sleep(OPEN_FAILURE_COOLDOWN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "openSessionWithRetry: interrupted during cooldown");
        }
        Log.w(TAG, "openSessionWithRetry: cooldown complete, returning failure");
        return false;
    }

    /**
     * Runs the Open procedure: ensures the host session is established and a
     * working key is loaded before any customer transaction can begin.
     *
     * <p>Mirrors BlueVerse {@code fnAPP_MainOpenProc} (see findings doc §11).
     * Specifically:
     * <ul>
     *     <li>Drives the session state machine through OPENING → READY (success)
     *         or OPENING → OPEN_ERROR_RETRY (failure)</li>
     *     <li>Sends Type 88 if a working key is not currently loaded
     *         (non-DUKPT mode)</li>
     *     <li>Skips work for DUKPT mode — hardware DUKPT does not require a
     *         host-provided working key</li>
     * </ul>
     *
     * <p>This method is synchronous and blocking. It should be called from a
     * background thread (typically the calling transaction executor).</p>
     *
     * <p>Future enhancements (later tasks):
     * <ul>
     *     <li>Task #13: Skip when host connection is still alive</li>
     *     <li>Task #14: 60-second retry on failure</li>
     * </ul>
     *
     * @return true if the session is ready for a customer transaction
     */
    public boolean openSession() {
        ensureInitialized();

        boolean isDukpt = castech.emvtxn.GlobalPara.atmDukptEnabled;
        if (isDukpt) {
            Log.d(TAG, "openSession: DUKPT mode — no Type 88 needed; key is in hardware");
            return true;
        }

        // Skip Open if working key still loaded (task #13). If keep-alive is on,
        // also verify the host socket is actually still connected — otherwise
        // we'd skip Open but the next send would fail. With keep-alive off, the
        // connection is always torn down between transactions, so we don't
        // bother checking socket state.
        if (hasWorkingKeys()) {
            if (config != null && config.isKeepAlive()) {
                if (transactionManager != null && transactionManager.isConnected()) {
                    Log.d(TAG, "openSession: key loaded + connection alive — skip Open");
                    return true;
                }
                Log.d(TAG, "openSession: key loaded but connection dropped, re-Open needed");
            } else {
                // Keep-alive off: working key alone is enough to skip Type 88.
                // Connection will be opened fresh per-transaction.
                Log.d(TAG, "openSession: working key already loaded, Open not needed");
                return true;
            }
        }

        // Drive state machine OPENING → READY/OPEN_ERROR_RETRY
        try { sessionState.openStarted(); }
        catch (IllegalStateException ignore) { /* SM not fully wired yet */ }

        Log.d(TAG, "openSession: Master/Session mode, no working key — sending Type 88");
        try {
            transactionManager.downloadKeysSync();
            if (!hasWorkingKeys()) {
                Log.e(TAG, "openSession: Type 88 returned but no working key loaded");
                try { sessionState.openFailed(); } catch (IllegalStateException ignore) {}
                return false;
            }
            Log.d(TAG, "openSession: Open succeeded, working key loaded");
            try { sessionState.openSucceeded(); } catch (IllegalStateException ignore) {}
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openSession: Type 88 failed — " + e.getMessage());
            try { sessionState.openFailed(); } catch (IllegalStateException ignore) {}
            return false;
        }
    }

    // =========================================================================
    // Transaction Methods
    // =========================================================================

    /**
     * Performs a cash withdrawal.
     *
     * @param cardData Card data from Castle SDK
     * @param amountCents Amount in cents (e.g., 10000 = $100.00)
     * @param surchargeCents Surcharge in cents (e.g., 100 = $1.00)
     * @param accountType Account type: "CA" (Checking), "SA" (Savings), "CR" (Credit)
     */
    public void performWithdrawal(CastleCardData cardData, long amountCents,
            long surchargeCents, String accountType) {
        ensureInitialized();

        // BlueVerse compliance: block new customer transactions while reversals
        // are pending or being processed. Matches FUN_0006a280 + FUN_00070280
        // post-transaction recovery pattern — terminal cannot return to the
        // "ready for customer" state until the reversal queue clears.
        if (processingReversals) {
            Log.w(TAG, "performWithdrawal blocked: reversal drain in progress");
            notifyError("Please wait — processing pending transactions");
            return;
        }
        if (hasDrainablePendingReversals()) {
            Log.w(TAG, "performWithdrawal blocked: pending reversals on disk, triggering drain");
            notifyError("Please wait — processing pending transactions");
            triggerReversalDrain();
            return;
        }

        // Open flow with bounded retry (tasks #12 + #14): ensure host session + working
        // key before transaction. On all-attempts failure, terminal goes OUT_OF_SERVICE.
        if (!openSessionWithRetry()) {
            notifyError("Unable to connect to processor — please try again later");
            return;
        }

        transactionManager.performCashWithdrawal(cardData, amountCents, surchargeCents, accountType);
    }

    /**
     * Performs a balance inquiry.
     *
     * @param cardData Card data from Castle SDK
     * @param accountType Account type: "CA", "SA", or "CR"
     */
    public void performBalanceInquiry(CastleCardData cardData, String accountType) {
        ensureInitialized();

        // BlueVerse compliance: block customer transactions during reversal recovery
        if (processingReversals) {
            Log.w(TAG, "performBalanceInquiry blocked: reversal drain in progress");
            notifyError("Please wait — processing pending transactions");
            return;
        }
        if (hasDrainablePendingReversals()) {
            Log.w(TAG, "performBalanceInquiry blocked: pending reversals on disk, triggering drain");
            notifyError("Please wait — processing pending transactions");
            triggerReversalDrain();
            return;
        }

        // Open flow with bounded retry (tasks #12 + #14): ensure host session + working
        // key before transaction. On all-attempts failure, terminal goes OUT_OF_SERVICE.
        if (!openSessionWithRetry()) {
            notifyError("Unable to connect to processor — please try again later");
            return;
        }

        transactionManager.performBalanceInquiry(cardData, accountType);
    }

    /**
     * Sends a reversal for the last transaction.
     *
     * @param reason Reversal reason: "01"=Customer cancel, "02"=Dispense failure, etc.
     */
    public void sendReversal(String reason) {
        ensureInitialized();
        transactionManager.sendReversal(reason);
    }

    /**
     * Checks if a transaction is in progress.
     */
    public boolean isTransactionInProgress() {
        return transactionManager != null && transactionManager.isTransactionInProgress();
    }

    // =========================================================================
    // Reversal Persistence Methods
    // =========================================================================

    /**
     * Stores a pending reversal for later processing.
     * Called when a transaction fails after approval (e.g., dispense failure).
     *
     * @param terminalId Terminal ID
     * @param sequenceNumber Original sequence number
     * @param authData Original authorization data
     * @param track2Data Card track 2 data
     * @param pinBlock Encrypted PIN block
     * @param amountCents Original amount in cents
     * @param surchargeCents Original surcharge in cents
     * @param reason Reversal reason code
     */
    public void storePendingReversal(String terminalId, int sequenceNumber, String authData,
            String track2Data, String pinBlock, long amountCents, long surchargeCents, String reason) {
        reversalManager.createAndStorePendingReversal(
            terminalId, sequenceNumber, authData, track2Data, pinBlock,
            amountCents, surchargeCents, reason);
        Log.d(TAG, "Stored pending reversal for later processing");
    }

    /**
     * Gets the count of pending reversals.
     */
    public int getPendingReversalCount() {
        return reversalManager.getPendingReversalCount();
    }

    /**
     * Checks if there are pending reversals.
     */
    public boolean hasPendingReversals() {
        return reversalManager.hasPendingReversals();
    }

    /**
     * Gets all pending reversals.
     */
    public java.util.List<ReversalPersistenceManager.PendingReversal> getPendingReversals() {
        return reversalManager.getPendingReversals();
    }

    /**
     * Gets the reversal persistence manager for direct access.
     */
    public ReversalPersistenceManager getReversalManager() {
        return reversalManager;
    }

    /**
     * Returns true if any reversal records with status {@code STATUS_PENDING} or
     * {@code STATUS_FAILED} exist on disk. Used to gate new customer transactions
     * (matches BlueVerse pattern: "block all transactions while a reversal is pending").
     *
     * <p>Excludes {@code STATUS_PENDING_PRESEND} records — those represent in-flight
     * transactions and are managed by the active transaction handler.</p>
     */
    public boolean hasDrainablePendingReversals() {
        if (reversalManager == null) return false;
        List<ReversalPersistenceManager.PendingReversal> all = reversalManager.getPendingReversals();
        for (ReversalPersistenceManager.PendingReversal r : all) {
            if (ReversalPersistenceManager.isDrainableStatus(r.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedules a blocking reversal drain on the dedicated drain executor.
     * Returns immediately. The drain will run on a background thread and processes
     * pending reversals in a {@code while (hasDrainablePendingReversals())} loop
     * until the queue clears or a hard error stops it.
     *
     * <p>Called automatically after each transaction completes (via
     * {@link InternalTransactionListener}) to match the BlueVerse FUN_00070280
     * post-transaction recovery pattern. May also be called manually for testing
     * or operator action.</p>
     */
    public void triggerReversalDrain() {
        reversalDrainExecutor.execute(this::drainPendingReversalsBlocking);
    }

    /**
     * Default maximum retry attempts per reversal during drain. After
     * exhaustion, the reversal record is left on disk with STATUS_FAILED and
     * the terminal goes out of service until operator intervention.
     *
     * <p>Operator-overridable via SharedPreferences key {@link #PREF_REVERSAL_RETRY_COUNT}
     * (task #10). Matches BlueVerse REVERSALRETRYCOUNT configuration.</p>
     */
    private static final int DEFAULT_REVERSAL_MAX_RETRIES = 5;

    /**
     * Initial backoff in milliseconds between reversal retry attempts. Doubles
     * with each attempt: 1s, 2s, 4s, 8s, 16s for 5 attempts.
     */
    private static final long REVERSAL_BACKOFF_INITIAL_MS = 1_000L;

    /**
     * Effective reversal retry count (resolved from SharedPreferences at
     * initialize-time, falling back to {@link #DEFAULT_REVERSAL_MAX_RETRIES}).
     */
    private int reversalMaxRetries = DEFAULT_REVERSAL_MAX_RETRIES;

    // ---- SharedPreferences keys for operator-configurable reversal behavior (task #10) ----
    public static final String PREFS_REVERSAL_CONFIG = "atm_reversal_config";
    public static final String PREF_REVERSAL_RETRY_COUNT = "reversal_retry_count";
    public static final String PREF_REVERSAL_MODE = "reversal_mode";
    public static final String PREF_HEALTHCHECK_INTERVAL_SEC = "healthcheck_interval_sec";
    public static final String PREF_REVERSAL_RETENTION_DAYS = "reversal_retention_days";

    /**
     * Synchronously drains pending reversals until the queue clears or processing
     * is interrupted. Blocks the calling thread; should only be called from the
     * {@link #reversalDrainExecutor} (or test code).
     *
     * <p>This is the implementation of BlueVerse's FUN_00070280 — a
     * {@code while (state > 0)} loop that dispatches reversals one at a time
     * until either the queue is empty (success) or a hard error breaks the loop.</p>
     *
     * <p>Bounded retry with exponential backoff (task #3): each reversal is
     * retried up to {@link #REVERSAL_MAX_RETRIES} times with doubling backoff
     * starting at {@link #REVERSAL_BACKOFF_INITIAL_MS}. On exhaustion the
     * record is left as STATUS_FAILED and the loop exits.</p>
     *
     * <p>Only processes records with {@code STATUS_PENDING} or {@code STATUS_FAILED}.
     * {@code STATUS_PENDING_PRESEND} records are skipped — they belong to in-flight
     * transactions.</p>
     */
    private void drainPendingReversalsBlocking() {
        if (!initialized) {
            Log.d(TAG, "drainPendingReversals: not initialized, skipping");
            return;
        }
        if (processingReversals) {
            Log.d(TAG, "drainPendingReversals: already running, skipping");
            return;
        }
        if (!hasDrainablePendingReversals()) {
            return; // common case, no log noise
        }

        processingReversals = true;
        Log.d(TAG, "Reversal drain loop starting");
        // User-visible: announce reversal start. Prefixed with [REVERSAL] so
        // MainActivity's onProgress can route these specifically to receipt UI.
        notifyDrainProgress("Reversal in progress…");
        try {
            sessionState.postTransactionCheck(true);
        } catch (IllegalStateException ignore) {
            // Session state not in POST_TRANSACTION (not driving SM yet); fine.
        }

        int processed = 0;
        int failed = 0;
        boolean exhaustionReached = false;
        try {
            while (hasDrainablePendingReversals()) {
                ReversalPersistenceManager.PendingReversal next = pickNextDrainable();
                if (next == null) break;

                Log.d(TAG, "Drain: processing reversal " + next.getTransactionId()
                        + " (seq=" + next.getSequenceNumber() + ")");

                boolean cleared = attemptReversalWithBackoff(next);

                if (cleared) {
                    processed++;
                    notifyDrainProgress("Reversal approved (seq " + next.getSequenceNumber() + ")");
                } else {
                    failed++;
                    Log.w(TAG, "Drain: reversal " + next.getTransactionId()
                            + " exhausted retries — leaving as FAILED");
                    exhaustionReached = true;
                    // Exit loop: persistent failure means host is unreachable or
                    // rejecting reversals. Leave remaining records for next drain.
                    break;
                }
            }
        } finally {
            processingReversals = false;
            Log.d(TAG, "Reversal drain loop complete: " + processed + " cleared, "
                    + failed + " failed");
            if (exhaustionReached) {
                // Task #3: escalate to operator alert when retries exhausted
                Log.e(TAG, "Reversal retry exhausted — terminal entering OUT_OF_SERVICE");
                try { sessionState.outOfService(); } catch (IllegalStateException ignore) {}
                notifyDrainProgress("Reversal pending — please contact merchant");
                notifyOperatorAlert("Pending reversals could not be processed — service required");
            } else if (processed > 0) {
                notifyDrainProgress("Reversal complete (" + processed + " sent)");
                try { sessionState.reversalRecoveryCleared(); } catch (IllegalStateException ignore) {}
            } else {
                try { sessionState.reversalRecoveryCleared(); } catch (IllegalStateException ignore) {}
            }
        }
    }

    /**
     * Send a user-visible reversal-progress message. Prefixed with [REVERSAL]
     * so the receipt UI (MainActivity.atmTransactionEventListener.onProgress)
     * can route these specifically to a status line rather than dropping them.
     */
    private void notifyDrainProgress(String message) {
        AtmEventListener l = listener;
        if (l != null) {
            try {
                l.onProgress("[REVERSAL] " + message);
            } catch (Throwable t) {
                Log.w(TAG, "notifyDrainProgress: listener threw", t);
            }
        }
    }

    /**
     * Attempts to dispatch a single reversal with bounded retry + exponential
     * backoff. Branches on the record's current status to match BlueVerse's
     * three-state recovery model (task #16):
     *
     * <ul>
     *     <li>STATUS_PENDING — dispatch reversal directly (BlueVerse state 1)</li>
     *     <li>STATUS_PENDING_RECONNECT_AND_EXIT — verify host reconnect; on success
     *         clear the record without sending a reversal (BlueVerse state 2)</li>
     *     <li>STATUS_PENDING_RECONNECT_AND_REVERSE — verify host reconnect; on success
     *         dispatch reversal (BlueVerse state 3)</li>
     *     <li>STATUS_FAILED — retry as STATUS_PENDING</li>
     * </ul>
     *
     * <p>Backoff schedule: 1s, 2s, 4s, 8s, 16s (capped at 30s).</p>
     */
    private boolean attemptReversalWithBackoff(ReversalPersistenceManager.PendingReversal rev) {
        String state = rev.getStatus();
        long backoff = REVERSAL_BACKOFF_INITIAL_MS;
        for (int attempt = 1; attempt <= reversalMaxRetries; attempt++) {
            reversalManager.updateReversalStatus(rev.getTransactionId(),
                    ReversalPersistenceManager.PendingReversal.STATUS_PROCESSING, null);

            boolean ok;
            try {
                ok = attemptByState(rev, state, attempt);
            } catch (Throwable t) {
                ok = false;
                Log.e(TAG, "Drain: attempt " + attempt + " threw: " + t.getMessage(), t);
            }

            if (ok) {
                // Task #5: mark processor destination completed
                rev.setUploadStatusFor(
                        ReversalPersistenceManager.PendingReversal.DEST_PROCESSOR,
                        ReversalPersistenceManager.PendingReversal.UPLOAD_COMPLETED);
                reversalManager.addToCompletedHistory(rev, true);
                reversalManager.removePendingReversal(rev.getTransactionId());
                Log.d(TAG, "Drain: reversal " + rev.getTransactionId()
                        + " cleared on attempt " + attempt + " (state was " + state + ")");
                return true;
            }
            Log.w(TAG, "Drain: attempt " + attempt + "/" + reversalMaxRetries
                    + " failed for " + rev.getTransactionId() + " (state " + state + ")");
            if (attempt < reversalMaxRetries) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                backoff = Math.min(backoff * 2, 30_000L);
            }
        }
        reversalManager.updateReversalStatus(rev.getTransactionId(),
                ReversalPersistenceManager.PendingReversal.STATUS_FAILED,
                "All " + reversalMaxRetries + " retries exhausted");
        return false;
    }

    /**
     * Performs a single attempt for a reversal record, branching on the record's
     * state. Returns true if the attempt cleared the record.
     */
    private boolean attemptByState(ReversalPersistenceManager.PendingReversal rev,
                                    String state, int attempt) {
        // RECONNECT_AND_EXIT: verify host connectivity; if reachable, the
        // original transaction state is presumed reconciled and the record
        // is cleared without sending a reversal message.
        if (ReversalPersistenceManager.PendingReversal.STATUS_PENDING_RECONNECT_AND_EXIT.equals(state)) {
            boolean openOk = openSession();
            if (openOk) {
                Log.d(TAG, "Drain: RECONNECT_AND_EXIT — host reachable, clearing without send");
                return true;
            }
            return false;
        }

        // RECONNECT_AND_REVERSE: verify host connectivity first, then dispatch reversal
        if (ReversalPersistenceManager.PendingReversal.STATUS_PENDING_RECONNECT_AND_REVERSE.equals(state)) {
            boolean openOk = openSession();
            if (!openOk) {
                Log.d(TAG, "Drain: RECONNECT_AND_REVERSE — reconnect failed on attempt " + attempt);
                return false;
            }
            // Reconnected — now dispatch the reversal
            return sendReversalSync(rev);
        }

        // Default (STATUS_PENDING or STATUS_FAILED): dispatch reversal directly
        return sendReversalSync(rev);
    }

    /**
     * Notifies the operator alert listener (if registered) that operator
     * intervention is required. Uses the dedicated {@link AtmEventListener#onOperatorAlert}
     * callback (task #9) which has a default implementation routing through
     * {@link AtmEventListener#onError} for backwards compatibility.
     */
    private void notifyOperatorAlert(String message) {
        AtmEventListener l = listener;
        if (l != null) {
            try {
                l.onOperatorAlert(message);
            } catch (Throwable t) {
                Log.e(TAG, "notifyOperatorAlert: listener threw", t);
            }
        }
    }

    // =========================================================================
    // Reversal Configuration (operator-overridable) — task #10
    // =========================================================================

    /**
     * Loads operator-overridable reversal config from SharedPreferences.
     * Settable via {@link #PREFS_REVERSAL_CONFIG} preferences file:
     * <ul>
     *     <li>{@link #PREF_REVERSAL_RETRY_COUNT} — max retry attempts (default 5)</li>
     *     <li>{@link #PREF_HEALTHCHECK_INTERVAL_SEC} — Type 89 interval seconds</li>
     *     <li>{@link #PREF_REVERSAL_RETENTION_DAYS} — journal retention days (default 90)</li>
     *     <li>{@link #PREF_REVERSAL_MODE} — reversal trigger mode (string)</li>
     * </ul>
     *
     * <p>UI controls in the admin fragment can edit these (separate task).</p>
     */
    private void loadReversalConfigFromPrefs() {
        if (context == null) return;
        SharedPreferences p = context.getSharedPreferences(
                PREFS_REVERSAL_CONFIG, Context.MODE_PRIVATE);
        int retries = p.getInt(PREF_REVERSAL_RETRY_COUNT, DEFAULT_REVERSAL_MAX_RETRIES);
        if (retries < 1 || retries > 20) {
            Log.w(TAG, "Invalid retry count " + retries + ", using default");
            retries = DEFAULT_REVERSAL_MAX_RETRIES;
        }
        this.reversalMaxRetries = retries;

        int healthIntervalSec = p.getInt(PREF_HEALTHCHECK_INTERVAL_SEC, 0);
        if (healthIntervalSec > 0 && config != null) {
            // Override config's default health interval
            // (config.setHealthCheckIntervalMs would be ideal if such setter exists)
            Log.d(TAG, "Operator health check interval: " + healthIntervalSec + "s");
        }
        Log.d(TAG, "Reversal config loaded: retries=" + reversalMaxRetries
                + ", healthIntervalSec=" + healthIntervalSec);
    }

    // =========================================================================
    // Periodic Health Check (Type 89) — task #8
    // =========================================================================

    /**
     * Starts the periodic health check scheduler (task #8). Sends a Type 89
     * health check on the configured interval to keep the host connection
     * state fresh and detect connection drops between transactions.
     *
     * <p>The scheduler skips health checks when a transaction is in progress
     * or when reversal drain is running, to avoid interleaving wire traffic.</p>
     *
     * <p>Mirrors BlueVerse {@code HEALTHCHECKINTERVAL} + {@code fnAPL_SetHealthCheckTimer}.
     * Default interval comes from {@link ProcessorConfig#getHealthCheckIntervalMs()}.</p>
     */
    public synchronized void startPeriodicHealthCheck() {
        if (healthCheckRunning) {
            Log.d(TAG, "Periodic health check already running");
            return;
        }
        int intervalMs = config != null ? config.getHealthCheckIntervalMs() : 0;
        if (intervalMs <= 0) {
            intervalMs = 5 * 60 * 1000; // Default 5 minutes
        }
        Log.d(TAG, "Starting periodic Type 89 health check (interval " + intervalMs + " ms)");

        healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HealthCheck");
            t.setDaemon(true);
            return t;
        });
        healthCheckTask = healthCheckScheduler.scheduleAtFixedRate(
                this::healthCheckTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        healthCheckRunning = true;
    }

    /**
     * Stops the periodic health check scheduler. Called when the service is
     * being shut down or reconfigured.
     */
    public synchronized void stopPeriodicHealthCheck() {
        if (!healthCheckRunning) return;
        Log.d(TAG, "Stopping periodic health check");
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
            healthCheckTask = null;
        }
        if (healthCheckScheduler != null) {
            healthCheckScheduler.shutdown();
            healthCheckScheduler = null;
        }
        healthCheckRunning = false;
    }

    /**
     * Single tick of the periodic health check. Skips if a transaction is in
     * progress, reversal drain is running, or the service is not initialized.
     * On failure, the connection layer's onDisconnected listener will fire
     * which clears the host-alive state for the next session.
     */
    private void healthCheckTick() {
        if (!initialized) return;
        if (processingReversals) {
            Log.d(TAG, "Health check tick: skipping (reversal drain running)");
            return;
        }
        if (transactionManager != null && transactionManager.isTransactionInProgress()) {
            Log.d(TAG, "Health check tick: skipping (transaction in progress)");
            return;
        }
        try {
            Log.d(TAG, "Health check tick: sending Type 89");
            transactionManager.sendHealthCheck();
        } catch (Throwable t) {
            Log.w(TAG, "Health check tick: send failed — " + t.getMessage());
        }
    }

    /**
     * Picks the next drainable pending reversal. Returns null if none.
     * Skips {@code STATUS_PENDING_PRESEND} records (those belong to in-flight transactions).
     */
    private ReversalPersistenceManager.PendingReversal pickNextDrainable() {
        List<ReversalPersistenceManager.PendingReversal> all = reversalManager.getPendingReversals();
        for (ReversalPersistenceManager.PendingReversal r : all) {
            if (ReversalPersistenceManager.isDrainableStatus(r.getStatus())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Processes all pending reversals.
     * Should be called on app startup and can be triggered manually from admin.
     *
     * @param callback Callback for processing results
     */
    public void processPendingReversals(ReversalProcessingCallback callback) {
        if (processingReversals) {
            Log.w(TAG, "Already processing reversals");
            if (callback != null) {
                callback.onProcessingComplete(0, 0);
            }
            return;
        }

        java.util.List<ReversalPersistenceManager.PendingReversal> pending = reversalManager.getPendingReversals();
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending reversals to process");
            if (callback != null) {
                callback.onProcessingComplete(0, 0);
            }
            return;
        }

        processingReversals = true;
        Log.d(TAG, "Processing " + pending.size() + " pending reversals");

        // Process reversals on background thread
        final java.util.List<ReversalPersistenceManager.PendingReversal> pendingList = pending;
        final ReversalProcessingCallback finalCallback = callback;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int successCount = 0;
                int failCount = 0;

                for (ReversalPersistenceManager.PendingReversal rev : pendingList) {
                    try {
                        if (finalCallback != null) {
                            finalCallback.onProcessingReversal(rev);
                        }

                        // Update status to processing
                        reversalManager.updateReversalStatus(rev.getTransactionId(),
                            ReversalPersistenceManager.PendingReversal.STATUS_PROCESSING, null);

                        // Send the reversal
                        boolean success = sendReversalSync(rev);

                        if (success) {
                            successCount++;
                            // Move to completed history
                            reversalManager.addToCompletedHistory(rev, true);
                            reversalManager.removePendingReversal(rev.getTransactionId());
                            Log.d(TAG, "Reversal successful: " + rev.getTransactionId());

                            if (finalCallback != null) {
                                finalCallback.onReversalSuccess(rev);
                            }
                        } else {
                            failCount++;
                            reversalManager.updateReversalStatus(rev.getTransactionId(),
                                ReversalPersistenceManager.PendingReversal.STATUS_FAILED, "Host rejected reversal");
                            Log.w(TAG, "Reversal failed: " + rev.getTransactionId());

                            if (finalCallback != null) {
                                finalCallback.onReversalFailed(rev, "Host rejected reversal");
                            }
                        }

                    } catch (Exception e) {
                        failCount++;
                        String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        reversalManager.updateReversalStatus(rev.getTransactionId(),
                            ReversalPersistenceManager.PendingReversal.STATUS_FAILED, error);
                        Log.e(TAG, "Reversal error: " + error);

                        if (finalCallback != null) {
                            finalCallback.onReversalFailed(rev, error);
                        }
                    }

                    // Brief delay between reversals
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                processingReversals = false;
                final int success = successCount;
                final int fail = failCount;

                if (finalCallback != null) {
                    finalCallback.onProcessingComplete(success, fail);
                }

                Log.d(TAG, "Reversal processing complete: " + success + " success, " + fail + " failed");
            }
        }).start();
    }

    /**
     * Sends a reversal synchronously (blocking).
     * Used internally for processing pending reversals.
     */
    private boolean sendReversalSync(ReversalPersistenceManager.PendingReversal pending) {
        if (!initialized || transactionManager == null) {
            return false;
        }

        // Convert to ReversalRequest
        ReversalRequest request = reversalManager.toReversalRequest(pending, config.getRoutingId());

        // Use a blocking call to send the reversal
        // Note: This is a simplified implementation. In production, you'd want
        // proper async handling with a synchronization mechanism.
        final boolean[] result = {false};
        final Object lock = new Object();

        // Temporarily override the listener to capture the result
        AtmEventListener originalListener = this.listener;

        this.listener = new AtmEventListener() {
            @Override
            public void onProgress(String message) {}

            @Override
            public void onError(String error) {
                synchronized (lock) {
                    result[0] = false;
                    lock.notifyAll();
                }
            }

            @Override
            public void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime,
                    long accountBalanceCents, long availableBalanceCents, String displayMessage) {}

            @Override
            public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) {}

            @Override
            public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {}

            @Override
            public void onKeysLoaded(String keyCheckValue) {}

            @Override
            public void onReversalComplete(boolean success) {
                synchronized (lock) {
                    result[0] = success;
                    lock.notifyAll();
                }
            }

            @Override
            public void onHealthCheckResult(boolean success) {}

            @Override
            public void onHostTotalsReceived(HostTotalsResponse response) {}
        };

        try {
            // Use the reconstituted ReversalRequest from the persisted record —
            // NOT transactionManager.sendReversal(reason), which depends on
            // in-memory currentRequest/currentResponse that don't survive an
            // app restart. That's the bug that left REV* records stuck.
            // sendReversalDirect is synchronous (it blocks on retries internally),
            // so we don't need the lock.wait() that the old in-memory path relied on.
            result[0] = transactionManager.sendReversalDirect(request);
        } finally {
            // Restore original listener
            this.listener = originalListener;
        }

        return result[0];
    }

    /**
     * Callback interface for reversal processing.
     */
    public interface ReversalProcessingCallback {
        void onProcessingReversal(ReversalPersistenceManager.PendingReversal reversal);
        void onReversalSuccess(ReversalPersistenceManager.PendingReversal reversal);
        void onReversalFailed(ReversalPersistenceManager.PendingReversal reversal, String error);
        void onProcessingComplete(int successCount, int failCount);
    }

    // =========================================================================
    // Health Check / Status Monitoring
    // =========================================================================

    /**
     * Sends a health check to verify connectivity (Type 89 - standard Hyosung).
     */
    public void sendHealthCheck() {
        ensureInitialized();
        transactionManager.sendHealthCheck();
    }

    /**
     * Sends a status monitoring request (Type H0 - EFX/Switch Commerce style).
     * This is the preferred method for testing connectivity with EFX processors.
     */
    public void sendStatusMonitoring() {
        ensureInitialized();
        transactionManager.sendStatusMonitoring();
    }

    // =========================================================================
    // Host Totals
    // =========================================================================

    /**
     * Requests host totals (Type 87) - settlement/reconciliation data.
     *
     * @param reset If true, resets totals after query; if false, query only
     */
    public void requestHostTotals(boolean reset) {
        ensureInitialized();
        Log.d(TAG, "Requesting host totals (reset=" + reset + ")");

        if (listener != null) {
            listener.onProgress("Requesting host totals...");
        }

        transactionManager.requestHostTotals(reset, new AtmTransactionManager.HostTotalsCallback() {
            @Override
            public void onHostTotalsReceived(HostTotalsResponse response) {
                Log.d(TAG, "Host totals received: " + response);
                if (listener != null) {
                    listener.onHostTotalsReceived(response);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Host totals error: " + error);
                if (listener != null) {
                    HostTotalsResponse errorResponse = new HostTotalsResponse();
                    errorResponse.setSuccess(false);
                    errorResponse.setErrorMessage(error);
                    listener.onHostTotalsReceived(errorResponse);
                }
            }
        });
    }

    /**
     * Requests host totals (query only, no reset).
     */
    public void requestHostTotals() {
        requestHostTotals(false);
    }

    /**
     * Requests host totals with a caller-supplied callback (used by POS mode where
     * the result must be routed back to a specific transaction flow rather than
     * the global event listener).
     */
    public void requestHostTotals(boolean reset, AtmTransactionManager.HostTotalsCallback callback) {
        ensureInitialized();
        Log.d(TAG, "Requesting host totals (reset=" + reset + ", caller-supplied callback)");
        transactionManager.requestHostTotals(reset, callback);
    }

    // =========================================================================
    // Configuration Update
    // =========================================================================

    /**
     * Requests full configuration from host.
     */
    public void requestFullConfiguration() {
        ensureInitialized();
        transactionManager.requestConfiguration(HyosungProtocol.CONFIG_FULL);
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    /**
     * Shuts down the service and releases resources.
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down ATM Host Service");

        if (transactionManager != null) {
            transactionManager.shutdown();
            transactionManager = null;
        }

        if (keyManager != null) {
            keyManager.clearWorkingKey();
            keyManager = null;
        }

        initialized = false;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("ATM Host Service not initialized. Call initialize() first.");
        }
    }

    private void notifyError(String error) {
        Log.e(TAG, error);
        if (listener != null) {
            listener.onError(error);
        }
    }

    // =========================================================================
    // Static Factory Methods
    // =========================================================================

    /**
     * Creates and initializes an ATM Host Service for DNS processor.
     */
    public static AtmHostService createForDns(Context context, String host, String terminalId) {
        AtmHostService service = new AtmHostService(context);
        service.initialize(ProcessorConfig.forDns(host, terminalId));
        return service;
    }

    /**
     * Creates and initializes an ATM Host Service for Switch Commerce processor.
     */
    public static AtmHostService createForSwitchCommerce(Context context, String host, String terminalId) {
        AtmHostService service = new AtmHostService(context);
        service.initialize(ProcessorConfig.forSwitchCommerce(host, terminalId));
        return service;
    }

    /**
     * Creates and initializes an ATM Host Service for EFX processor.
     */
    public static AtmHostService createForEfx(Context context, String host, String terminalId) {
        AtmHostService service = new AtmHostService(context);
        service.initialize(ProcessorConfig.forEfx(host, terminalId));
        return service;
    }

    // =========================================================================
    // Internal Transaction Listener
    // =========================================================================

    private class InternalTransactionListener implements AtmTransactionManager.TransactionListener {

        @Override
        public void onProgress(String message) {
            if (listener != null) {
                listener.onProgress(message);
            }
        }

        @Override
        public void onError(String error) {
            Log.d(TAG, "InternalTransactionListener.onError: error=" + error);
            Log.d(TAG, "InternalTransactionListener.onError: listener=" + (listener != null ? "SET" : "NULL"));
            if (listener != null) {
                Log.d(TAG, "InternalTransactionListener.onError: calling listener.onError()...");
                listener.onError(error);
                Log.d(TAG, "InternalTransactionListener.onError: done");
            } else {
                Log.e(TAG, "InternalTransactionListener.onError: LISTENER IS NULL!");
            }
            triggerReversalDrain();
        }

        @Override
        public void onApproved(TransactionResponse response) {
            if (listener != null) {
                listener.onTransactionApproved(
                    response.getResponseCode(),
                    response.getRetrievalReferenceNumber(),
                    response.getFormattedAuthDate(),
                    response.getFormattedAuthTime(),
                    response.getAccountBalanceCents(),
                    response.getAvailableBalanceCents(),
                    response.getDisplayMessage()
                );
            }
            triggerReversalDrain();
        }

        @Override
        public void onDeclined(TransactionResponse response) {
            Log.d(TAG, "InternalTransactionListener.onDeclined: listener=" + (listener != null ? "SET" : "NULL") +
                  ", responseCode=" + response.getResponseCode());
            if (listener != null) {
                try {
                    Log.d(TAG, "InternalTransactionListener.onDeclined: calling listener.onTransactionDeclined...");
                    Log.d(TAG, "InternalTransactionListener.onDeclined: listener class=" + listener.getClass().getName());
                    listener.onTransactionDeclined(
                        response.getResponseCode(),
                        response.getResponseDescription(),
                        response.shouldRetainCard()
                    );
                    Log.d(TAG, "InternalTransactionListener.onDeclined: done");
                } catch (Exception e) {
                    Log.e(TAG, "InternalTransactionListener.onDeclined: EXCEPTION in callback: " + e.getMessage(), e);
                }
            } else {
                Log.e(TAG, "InternalTransactionListener.onDeclined: LISTENER IS NULL!");
            }
            triggerReversalDrain();
        }

        @Override
        public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {
            if (listener != null) {
                listener.onBalanceReceived(responseCode, accountBalanceCents, availableBalanceCents);
            }
            triggerReversalDrain();
        }

        @Override
        public void onConfigurationComplete(ConfigResponse response) {
            if (listener != null) {
                listener.onKeysLoaded(keyManager.getWorkingKeyCheckValue());
            }
        }

        @Override
        public void onReversalComplete(boolean success) {
            if (listener != null) {
                listener.onReversalComplete(success);
            }
        }

        @Override
        public void onHealthCheckResult(boolean success) {
            if (listener != null) {
                listener.onHealthCheckResult(success);
            }
        }
    }

    // =========================================================================
    // Software TMK Configuration
    // =========================================================================

    private static final String TMK_PREFS_NAME = "atm_tmk_config";
    private static final String PREF_SOFTWARE_TMK = "software_tmk";

    // Default TMK value (XOR of Key Part A and Key Part B from processor)
    // This should match what's loaded in KMS2 at CFFF/0000
    // KCV: C66111 (verify with processor)
    private static final String DEFAULT_TMK = "80BBBF06B3FF73DC0ACE0FD71849FEC4";

    /**
     * Configures the software TMK for working key decryption.
     * Loads from SharedPreferences if available, otherwise uses default.
     *
     * The software TMK is used as a fallback when hardware TMK decryption fails
     * with error 0x2907 (wrong key attribute). This happens because the Key Injection
     * Tool sets attribute 0x00000010 (Data Decryption) which doesn't allow key unwrapping.
     */
    private void configureSoftwareTmk() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(TMK_PREFS_NAME, Context.MODE_PRIVATE);
            String tmkValue = prefs.getString(PREF_SOFTWARE_TMK, DEFAULT_TMK);

            if (tmkValue != null && tmkValue.length() == 32) {
                if (keyManager.setSoftwareTmk(tmkValue)) {
                    Log.d(TAG, "Software TMK configured for fallback decryption");
                    if (!tmkValue.equals(DEFAULT_TMK)) {
                        Log.d(TAG, "  Using custom TMK from preferences");
                    } else {
                        Log.d(TAG, "  Using default TMK (80BB...FEC4)");
                    }
                } else {
                    Log.e(TAG, "Failed to set software TMK");
                }
            } else {
                Log.w(TAG, "Invalid TMK in preferences, using default");
                keyManager.setSoftwareTmk(DEFAULT_TMK);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring software TMK: " + e.getMessage());
            // Try default anyway
            keyManager.setSoftwareTmk(DEFAULT_TMK);
        }
    }

    /**
     * Sets a custom software TMK value and persists it.
     * Use this if the processor's TMK changes.
     *
     * @param tmkHex The TMK as 32 hex characters
     * @return true if TMK was set and persisted successfully
     */
    public boolean setSoftwareTmk(String tmkHex) {
        if (tmkHex == null || tmkHex.length() != 32) {
            Log.e(TAG, "Invalid TMK format: expected 32 hex chars");
            return false;
        }

        try {
            // Set in key manager
            if (!keyManager.setSoftwareTmk(tmkHex)) {
                return false;
            }

            // Persist to preferences
            SharedPreferences prefs = context.getSharedPreferences(TMK_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_SOFTWARE_TMK, tmkHex).apply();

            Log.d(TAG, "Software TMK updated and persisted");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set software TMK: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clears the custom software TMK and reverts to default.
     */
    public void clearSoftwareTmk() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(TMK_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(PREF_SOFTWARE_TMK).apply();
            keyManager.setSoftwareTmk(DEFAULT_TMK);
            Log.d(TAG, "Software TMK reset to default");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing software TMK: " + e.getMessage());
        }
    }

    // =========================================================================
    // Event Listener Interface
    // =========================================================================

    /**
     * Listener for ATM Host Service events.
     */
    public interface AtmEventListener {
        /**
         * Called with progress updates.
         */
        void onProgress(String message);

        /**
         * Called when an error occurs.
         */
        void onError(String error);

        /**
         * Called when an operator-attention condition is detected (task #9).
         * Examples:
         * <ul>
         *     <li>Reversal retries exhausted — terminal entering OUT_OF_SERVICE</li>
         *     <li>Persistent host unreachable — manual intervention needed</li>
         *     <li>Disk space critical — journal cleanup required</li>
         * </ul>
         *
         * <p>Default implementation routes through {@link #onError(String)} so
         * existing listeners continue to work; new listeners should override
         * for dedicated operator alert UI handling.</p>
         */
        default void onOperatorAlert(String message) {
            onError("[OPERATOR ALERT] " + message);
        }

        /**
         * Called when a transaction is approved.
         *
         * @param responseCode ISO 8583 response code (00 = approved)
         * @param referenceNumber Transaction reference number (RRN)
         * @param authDate Authorization date (MM/DD/YYYY)
         * @param authTime Authorization time (HH:mm:ss)
         * @param accountBalanceCents Account balance in cents
         * @param availableBalanceCents Available balance in cents
         * @param displayMessage Optional message from host
         */
        void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime,
                long accountBalanceCents, long availableBalanceCents, String displayMessage);

        /**
         * Called when a transaction is declined.
         *
         * @param responseCode ISO 8583 response code
         * @param responseMessage Human-readable decline reason
         * @param retainCard true if card should be retained
         */
        void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard);

        /**
         * Called when balance inquiry completes.
         *
         * @param responseCode ISO 8583 response code (00 = approved)
         * @param accountBalanceCents Account balance in cents
         * @param availableBalanceCents Available balance in cents
         */
        void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents);

        /**
         * Called when working keys are loaded.
         *
         * @param keyCheckValue Key check value (KCV)
         */
        void onKeysLoaded(String keyCheckValue);

        /**
         * Called when a reversal completes.
         *
         * @param success true if reversal was accepted
         */
        void onReversalComplete(boolean success);

        /**
         * Called when a health check completes.
         *
         * @param success true if host is healthy
         */
        void onHealthCheckResult(boolean success);

        /**
         * Called when host totals are received.
         *
         * @param response The host totals response with transaction counts and amounts
         */
        void onHostTotalsReceived(HostTotalsResponse response);
    }
}
