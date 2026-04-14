package castech.emvtxn.atm.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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

    // State
    private boolean initialized;
    private boolean processingReversals;
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
        this.processingReversals = false;
        this.reversalManager = new ReversalPersistenceManager(context);
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
                castech.emvtxn.GlobalPara.atmDukptEnabled = false;
                castech.emvtxn.GlobalPara.atmDukptKeySet = 0x0000CFFF;
                castech.emvtxn.GlobalPara.atmDukptKeyIndex = 0x00000000;
                castech.emvtxn.GlobalPara.onlinePinKeySet = 0x0000CFFF;
                castech.emvtxn.GlobalPara.onlinePinKeyIndex = 0x00000000;
                Log.d(TAG, "Triton mode: PIN key set to CFFF/0000 (TMK)");
            } else {
                processorConfig.setProtocolType(ProcessorConfig.ProtocolType.HYOSUNG_STD1);
                // Hyosung uses DUKPT — key at C000/0000
                castech.emvtxn.GlobalPara.atmDukptEnabled = true;
                castech.emvtxn.GlobalPara.atmDukptKeySet = 0x0000C000;
                castech.emvtxn.GlobalPara.atmDukptKeyIndex = 0x00000000;
                castech.emvtxn.GlobalPara.onlinePinKeySet = 0x0000C000;
                castech.emvtxn.GlobalPara.onlinePinKeyIndex = 0x00000000;
                Log.d(TAG, "Hyosung mode: PIN key set to C000/0000 (DUKPT)");
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

            // No software TMK — all key operations use hardware TMK at CFFF/0000
            // TMK must be injected via Key Injection Tool / KeyBRIDGE with correct attribute

            // Initialize transaction manager
            transactionManager = new AtmTransactionManager(config, keyManager);
            transactionManager.setTransactionListener(new InternalTransactionListener());
            transactionManager.setReversalManager(reversalManager);

            // Note: H0 heartbeat is sent before each transaction, not on a schedule
            // The scheduled heartbeat can be started manually after key download if needed:
            // transactionManager.startHeartbeat();

            initialized = true;
            Log.d(TAG, "ATM Host Service initialized successfully");
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

        // Check if renewal is needed (Master/Session mode only)
        if (!keyManager.needsRenewal()) {
            Log.d(TAG, "Startup key check: Key is valid - " + keyManager.getKeyStatus());
            if (callback != null) {
                callback.onRenewalSuccess(keyManager.getKeyStatus());
            }
            return;
        }

        Log.d(TAG, "Startup key check: Renewal needed - " + keyManager.getKeyStatus());

        // Perform async key download
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
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

        // DUKPT mode doesn't use downloadable working keys
        if (castech.emvtxn.GlobalPara.atmDukptEnabled) {
            Log.d(TAG, "DUKPT mode — working key download not needed (hardware key at C000/0000)");
            if (listener != null) {
                listener.onKeysLoaded("DUKPT - hardware key");
            }
            return;
        }

        Log.d(TAG, "Requesting new working key...");

        // Clear the current key first
        if (keyManager != null) {
            keyManager.clearWorkingKey();
            Log.d(TAG, "Current working key cleared");
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

        // Auto-download keys if not loaded
        // Skip for DUKPT — PIN encryption is handled by hardware, no working key needed
        boolean isDukpt = castech.emvtxn.GlobalPara.atmDukptEnabled;
        if (!isDukpt && !hasWorkingKeys()) {
            Log.d(TAG, "Master/Session mode - working keys not loaded, auto-downloading...");
            try {
                transactionManager.downloadKeysSync();
                if (!hasWorkingKeys()) {
                    Log.e(TAG, "Failed to download working keys");
                    notifyError("Failed to download working keys");
                    return;
                }
                Log.d(TAG, "Working keys downloaded successfully");
            } catch (Exception e) {
                Log.e(TAG, "Key download failed: " + e.getMessage());
                notifyError("Key download failed: " + e.getMessage());
                return;
            }
        } else if (isDukpt) {
            Log.d(TAG, "DUKPT mode - no working key download needed");
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

        // Auto-download keys if not loaded
        // Skip for DUKPT — PIN encryption is handled by hardware, no working key needed
        boolean isDukptBI = castech.emvtxn.GlobalPara.atmDukptEnabled;
        if (!isDukptBI && !hasWorkingKeys()) {
            Log.d(TAG, "Master/Session mode - working keys not loaded, auto-downloading...");
            try {
                transactionManager.downloadKeysSync();
                if (!hasWorkingKeys()) {
                    Log.e(TAG, "Failed to download working keys");
                    notifyError("Failed to download working keys");
                    return;
                }
                Log.d(TAG, "Working keys downloaded successfully");
            } catch (Exception e) {
                Log.e(TAG, "Key download failed: " + e.getMessage());
                notifyError("Key download failed: " + e.getMessage());
                return;
            }
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
            transactionManager.sendReversal(pending.getReasonCode());

            // Wait for result with timeout
            synchronized (lock) {
                lock.wait(30000); // 30 second timeout
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result[0] = false;
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
        }

        @Override
        public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {
            if (listener != null) {
                listener.onBalanceReceived(responseCode, accountBalanceCents, availableBalanceCents);
            }
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
