package castech.emvtxn.atm.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reversal Persistence Manager
 *
 * Manages the persistence and processing of pending reversals.
 * Reversals must survive app restarts and be retried until successfully processed.
 *
 * Storage: SharedPreferences (JSON format)
 * Key features:
 * - Store pending reversals when transaction fails after approval
 * - Load and process pending reversals on app startup
 * - Manual reversal processing from admin screen
 * - Reversal status tracking and display
 */
public class ReversalPersistenceManager {

    private static final String TAG = "ReversalPersistMgr";
    private static final String PREFS_NAME = "atm_reversals";
    private static final String KEY_PENDING_REVERSALS = "pending_reversals";
    private static final String KEY_COMPLETED_REVERSALS = "completed_reversals";
    private static final int MAX_COMPLETED_HISTORY = 50;

    private final Context context;
    private final SharedPreferences prefs;
    private final ReversalJournal journal;
    private ReversalListener listener;

    /**
     * Creates a new ReversalPersistenceManager.
     *
     * @param context Android context
     */
    public ReversalPersistenceManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.journal = new ReversalJournal(this.context);
    }

    /**
     * Returns the append-only journal (tasks #6 + #7). Used for audit,
     * diagnostics, and post-mortem recovery verification.
     */
    public ReversalJournal getJournal() {
        return journal;
    }

    /**
     * Sets the listener for reversal events.
     */
    public void setReversalListener(ReversalListener listener) {
        this.listener = listener;
    }

    // =========================================================================
    // Pending Reversal Management
    // =========================================================================

    /**
     * Stores a pending reversal.
     *
     * @param reversal The reversal data to store
     */
    public void storePendingReversal(PendingReversal reversal) {
        List<PendingReversal> pending = getPendingReversals();
        pending.add(reversal);
        savePendingReversals(pending);
        Log.d(TAG, "Stored pending reversal: " + reversal.getTransactionId());
    }

    /**
     * Creates and stores a pending reversal from transaction data.
     *
     * @param terminalId Terminal ID
     * @param sequenceNumber Original sequence number
     * @param authData Original authorization data
     * @param track2Data Card track 2 data
     * @param pinBlock Encrypted PIN block
     * @param amountCents Original amount in cents
     * @param surchargeCents Original surcharge in cents
     * @param reason Reversal reason code
     * @return The created PendingReversal
     */
    public PendingReversal createAndStorePendingReversal(
            String terminalId, int sequenceNumber, String authData,
            String track2Data, String pinBlock,
            long amountCents, long surchargeCents, String reason) {

        PendingReversal reversal = new PendingReversal();
        reversal.setTransactionId(generateTransactionId());
        reversal.setTerminalId(terminalId);
        reversal.setSequenceNumber(sequenceNumber);
        reversal.setAuthData(authData != null ? authData : "");
        // PCI: Do NOT store Track 2 or PIN block in SharedPreferences
        // Reversal messages use sequence number + auth data to identify the original transaction
        reversal.setTrack2Data("");
        reversal.setPinBlock("");
        reversal.setAmountCents(amountCents);
        reversal.setSurchargeCents(surchargeCents);
        reversal.setReasonCode(reason);
        reversal.setCreatedTime(System.currentTimeMillis());
        reversal.setAttemptCount(0);
        reversal.setStatus(PendingReversal.STATUS_PENDING);

        storePendingReversal(reversal);
        return reversal;
    }

    /**
     * Creates and stores a {@code PENDING_PRESEND} reversal record BEFORE the
     * transaction request is sent to the host. Used to ensure a reversal exists
     * on disk even if the terminal crashes or loses power between the socket
     * write and the response handler.
     *
     * <p>Lifecycle:
     * <ul>
     *     <li>On successful approval → call {@link #removePendingReversal(String)}
     *         with the returned transaction ID.</li>
     *     <li>On send failure / timeout → call {@link #promoteToPending(String, String)}
     *         to convert the record from PRESEND to PENDING, making it eligible
     *         for reversal recovery.</li>
     *     <li>On terminal restart with a PRESEND record still present → the
     *         record is processed as a timeout reversal (we cannot know if the
     *         host received the request).</li>
     * </ul>
     *
     * <p>Mirrors the pre-send persistence pattern observed in deployed Hyosung
     * BlueVerse ATM software.
     *
     * @return the created {@code PendingReversal}; the caller must retain its
     *         {@link PendingReversal#getTransactionId() transactionId} to later
     *         remove or promote the record.
     */
    public PendingReversal createPreSendReversal(
            String terminalId, int sequenceNumber,
            long amountCents, long surchargeCents) {

        PendingReversal reversal = new PendingReversal();
        reversal.setTransactionId(generateTransactionId());
        reversal.setTerminalId(terminalId);
        reversal.setSequenceNumber(sequenceNumber);
        reversal.setAuthData("");
        // PCI: never persist Track 2 or PIN block
        reversal.setTrack2Data("");
        reversal.setPinBlock("");
        reversal.setAmountCents(amountCents);
        reversal.setSurchargeCents(surchargeCents);
        reversal.setReasonCode("");
        reversal.setCreatedTime(System.currentTimeMillis());
        reversal.setAttemptCount(0);
        reversal.setStatus(PendingReversal.STATUS_PENDING_PRESEND);

        // Per-destination upload tracking (task #5): processor is always
        // a required destination; RMS/audit get wired in when those integrations
        // are added.
        reversal.setUploadStatusFor(PendingReversal.DEST_PROCESSOR,
                PendingReversal.UPLOAD_PENDING);

        storePendingReversal(reversal);
        journal.appendEvent("create_presend", reversal, null);
        return reversal;
    }

    /**
     * Promotes a {@code PENDING_PRESEND} record to one of the recovery-eligible
     * pending states (default: {@link PendingReversal#STATUS_PENDING}). The
     * record then becomes eligible for processing by the reversal recovery loop.
     *
     * @param transactionId the ID of the pre-send record
     * @param reason        reversal reason code (see {@link HyosungProtocol})
     */
    public void promoteToPending(String transactionId, String reason) {
        promoteToStatus(transactionId, reason, PendingReversal.STATUS_PENDING);
    }

    /**
     * Promotes a record to a specific pending state. Used by callers that know
     * whether the failure is host-down (RECONNECT states) vs simple reversal-needed.
     *
     * @param transactionId  the ID of the record to promote
     * @param reason         reversal reason code
     * @param targetStatus   one of STATUS_PENDING, STATUS_PENDING_RECONNECT_AND_EXIT,
     *                       STATUS_PENDING_RECONNECT_AND_REVERSE
     */
    public void promoteToStatus(String transactionId, String reason, String targetStatus) {
        List<PendingReversal> all = getPendingReversals();
        for (PendingReversal r : all) {
            if (transactionId.equals(r.getTransactionId())) {
                r.setStatus(targetStatus);
                r.setReasonCode(reason != null ? reason : "");
                savePendingReversals(all);
                journal.appendEvent("promote", r,
                        "target=" + targetStatus + " reason=" + reason);
                Log.d(TAG, "Promoted pre-send reversal to " + targetStatus + ": "
                        + transactionId + " reason=" + reason);
                return;
            }
        }
        Log.w(TAG, "promoteToStatus: no record with id " + transactionId);
    }

    /**
     * Returns true if the given status is one of the recovery-eligible states
     * (any STATUS_PENDING* variant or STATUS_FAILED for retry).
     */
    public static boolean isDrainableStatus(String status) {
        return PendingReversal.STATUS_PENDING.equals(status)
                || PendingReversal.STATUS_PENDING_RECONNECT_AND_EXIT.equals(status)
                || PendingReversal.STATUS_PENDING_RECONNECT_AND_REVERSE.equals(status)
                || PendingReversal.STATUS_FAILED.equals(status);
    }

    /**
     * Gets all pending reversals.
     *
     * @return List of pending reversals
     */
    public List<PendingReversal> getPendingReversals() {
        List<PendingReversal> reversals = new ArrayList<>();
        String json = prefs.getString(KEY_PENDING_REVERSALS, "[]");

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                reversals.add(PendingReversal.fromJson(obj));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading pending reversals: " + e.getMessage());
        }

        return reversals;
    }

    /**
     * Gets the count of pending reversals.
     */
    public int getPendingReversalCount() {
        return getPendingReversals().size();
    }

    /**
     * Checks if there are pending reversals.
     */
    public boolean hasPendingReversals() {
        return getPendingReversalCount() > 0;
    }

    /**
     * Saves the pending reversals list.
     */
    private void savePendingReversals(List<PendingReversal> reversals) {
        JSONArray array = new JSONArray();
        for (PendingReversal rev : reversals) {
            array.put(rev.toJson());
        }
        prefs.edit().putString(KEY_PENDING_REVERSALS, array.toString()).apply();
    }

    /**
     * Removes a pending reversal (after successful processing).
     *
     * @param transactionId The transaction ID to remove
     */
    public void removePendingReversal(String transactionId) {
        List<PendingReversal> pending = getPendingReversals();
        PendingReversal removed = null;
        // Manual removal to avoid Java 8 lambdas (Castle terminal compatibility)
        java.util.Iterator<PendingReversal> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingReversal r = iterator.next();
            if (r.getTransactionId().equals(transactionId)) {
                removed = r;
                iterator.remove();
            }
        }
        savePendingReversals(pending);
        if (removed != null) {
            journal.appendEvent("remove", removed, null);
        }
        Log.d(TAG, "Removed pending reversal: " + transactionId);
    }

    /**
     * Updates a pending reversal's attempt count and status.
     *
     * @param transactionId The transaction ID
     * @param newStatus New status
     * @param errorMessage Error message (if failed)
     */
    public void updateReversalStatus(String transactionId, String newStatus, String errorMessage) {
        List<PendingReversal> pending = getPendingReversals();
        for (PendingReversal rev : pending) {
            if (rev.getTransactionId().equals(transactionId)) {
                rev.setStatus(newStatus);
                rev.setAttemptCount(rev.getAttemptCount() + 1);
                rev.setLastAttemptTime(System.currentTimeMillis());
                if (errorMessage != null) {
                    rev.setLastError(errorMessage);
                }
                break;
            }
        }
        savePendingReversals(pending);
    }

    /**
     * Clears all pending reversals (use with caution!).
     */
    public void clearAllPendingReversals() {
        prefs.edit().putString(KEY_PENDING_REVERSALS, "[]").apply();
        Log.w(TAG, "Cleared all pending reversals");
    }

    // =========================================================================
    // Completed Reversal History
    // =========================================================================

    /**
     * Adds a completed reversal to history.
     *
     * @param reversal The completed reversal
     * @param success Whether it was successful
     */
    public void addToCompletedHistory(PendingReversal reversal, boolean success) {
        List<CompletedReversal> history = getCompletedHistory();

        CompletedReversal completed = new CompletedReversal();
        completed.setTransactionId(reversal.getTransactionId());
        completed.setAmountCents(reversal.getAmountCents());
        completed.setCreatedTime(reversal.getCreatedTime());
        completed.setCompletedTime(System.currentTimeMillis());
        completed.setSuccess(success);
        completed.setAttempts(reversal.getAttemptCount());

        history.add(0, completed); // Add to beginning

        // Trim history
        while (history.size() > MAX_COMPLETED_HISTORY) {
            history.remove(history.size() - 1);
        }

        saveCompletedHistory(history);
    }

    /**
     * Gets completed reversal history.
     */
    public List<CompletedReversal> getCompletedHistory() {
        List<CompletedReversal> history = new ArrayList<>();
        String json = prefs.getString(KEY_COMPLETED_REVERSALS, "[]");

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                history.add(CompletedReversal.fromJson(obj));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading completed history: " + e.getMessage());
        }

        return history;
    }

    /**
     * Saves completed reversal history.
     */
    private void saveCompletedHistory(List<CompletedReversal> history) {
        JSONArray array = new JSONArray();
        for (CompletedReversal rev : history) {
            array.put(rev.toJson());
        }
        prefs.edit().putString(KEY_COMPLETED_REVERSALS, array.toString()).apply();
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Generates a unique transaction ID for the reversal.
     */
    private String generateTransactionId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        return "REV" + sdf.format(new Date()) + String.format("%04d", (int)(Math.random() * 10000));
    }

    /**
     * Converts a PendingReversal to a ReversalRequest for sending.
     */
    /**
     * Builds a {@link ReversalRequest} from a persisted {@link PendingReversal}.
     *
     * <p><b>Timeout vs Financial reversal distinction (task #4):</b></p>
     * <ul>
     *     <li>If {@code pending.getAuthData()} is empty, this is a <b>Timeout
     *         Reversal</b> — host never responded, no authorization data
     *         available, reversal carries only original request fields.</li>
     *     <li>If {@code pending.getAuthData()} is non-empty, this is a
     *         <b>Financial Reversal</b> — host responded with an approval, but
     *         dispense/cancel/other post-approval failure occurred. Reversal
     *         carries the approval auth data so the processor can match it.</li>
     * </ul>
     *
     * <p>The distinction is encoded by which write site populated authData:</p>
     * <ul>
     *     <li>{@link #createPreSendReversal} writes empty authData (pre-send,
     *         possibly will become timeout)</li>
     *     <li>{@link AtmTransactionManager#promoteOrCreatePendingReversal}
     *         updates authData from response when response was received</li>
     *     <li>{@link #createAndStorePendingReversal} (legacy post-failure path)
     *         takes authData as a parameter</li>
     * </ul>
     */
    public ReversalRequest toReversalRequest(PendingReversal pending, String routingId) {
        ReversalRequest request = new ReversalRequest();
        request.setRoutingId(routingId);
        request.setTerminalId(pending.getTerminalId());
        request.setOriginalSequenceNumber(pending.getSequenceNumber());
        request.setOriginalAuthData(pending.getAuthData() != null ? pending.getAuthData() : "");
        request.setTrack2Data(pending.getTrack2Data());
        request.setPinBlock(pending.getPinBlock());
        request.setOriginalAmountCents(pending.getAmountCents());
        request.setOriginalSurchargeCents(pending.getSurchargeCents());
        request.setReversalReason(pending.getReasonCode());
        return request;
    }

    // =========================================================================
    // Listener Interface
    // =========================================================================

    /**
     * Listener for reversal processing events.
     */
    public interface ReversalListener {
        void onReversalProcessing(PendingReversal reversal);
        void onReversalSuccess(PendingReversal reversal);
        void onReversalFailed(PendingReversal reversal, String error);
        void onAllReversalsProcessed(int successCount, int failCount);
    }

    // =========================================================================
    // Data Classes
    // =========================================================================

    /**
     * Represents a pending reversal stored for later processing.
     */
    public static class PendingReversal {
        /**
         * Reversal record written BEFORE the transaction request is sent.
         * On clean approval the record is removed. On send failure or timeout
         * it is promoted to one of the recovery states below.
         */
        public static final String STATUS_PENDING_PRESEND = "pending_presend";

        /**
         * Reversal needs to be sent to host. Host connection assumed to be alive.
         * Mirrors BlueVerse {@code MemGet(3, 0x3ec) == 1} — recovery loop should
         * dispatch this reversal immediately.
         */
        public static final String STATUS_PENDING = "pending";

        /**
         * Reversal needs reconnect then exit (no immediate send required).
         * Mirrors BlueVerse {@code MemGet(3, 0x3ec) == 2}. Drain loop should
         * verify host connectivity; on reconnect success, mark complete and exit
         * (the next session-Open will handle any follow-up). Used when the
         * reversal record was promoted but the failure mode suggests the host
         * may have processed the original transaction successfully.
         */
        public static final String STATUS_PENDING_RECONNECT_AND_EXIT = "pending_reconnect_exit";

        /**
         * Reversal needs reconnect then dispatch.
         * Mirrors BlueVerse {@code MemGet(3, 0x3ec) == 3}. Drain loop should
         * reconnect first, then dispatch the reversal. Used when connection was
         * lost during send and a reversal is required.
         */
        public static final String STATUS_PENDING_RECONNECT_AND_REVERSE = "pending_reconnect_reverse";

        /** Reversal is currently being sent to host. */
        public static final String STATUS_PROCESSING = "processing";

        /** Reversal failed after all retries. */
        public static final String STATUS_FAILED = "failed";

        // ---- Per-destination upload tracking (task #5, UP_TYPE pattern) ----
        // Mirrors BlueVerse's UP_TYPE enum + SetUploadedIndex / GetUploadLastIndex
        // (see HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md §3). A single
        // reversal record may need to propagate to multiple downstream systems
        // (processor, RMS, audit log, etc.); upload state is tracked per
        // destination independently.

        /** Upload destination: the processor host (reversal acceptance). */
        public static final String DEST_PROCESSOR = "processor";

        /** Upload destination: remote management server (audit/monitoring). */
        public static final String DEST_RMS = "rms";

        /** Upload destination: local audit log (always required). */
        public static final String DEST_AUDIT = "audit";

        /** Upload not yet attempted for this destination. */
        public static final String UPLOAD_PENDING = "pending";

        /** Upload in progress for this destination. */
        public static final String UPLOAD_IN_PROGRESS = "in_progress";

        /** Upload completed successfully for this destination. */
        public static final String UPLOAD_COMPLETED = "completed";

        /** Upload failed (will retry). */
        public static final String UPLOAD_FAILED = "failed";

        /** Upload not applicable for this destination (not configured). */
        public static final String UPLOAD_NA = "na";

        private String transactionId;
        private String terminalId;
        private int sequenceNumber;
        private String authData;
        private String track2Data;
        private String pinBlock;
        private long amountCents;
        private long surchargeCents;
        private String reasonCode;
        private long createdTime;
        private long lastAttemptTime;
        private int attemptCount;
        private String status;
        private String lastError;

        /**
         * Per-destination upload state map (task #5). Key = destination
         * identifier (DEST_PROCESSOR, DEST_RMS, DEST_AUDIT). Value = upload
         * status (UPLOAD_PENDING / UPLOAD_IN_PROGRESS / UPLOAD_COMPLETED /
         * UPLOAD_FAILED / UPLOAD_NA). Initialized to empty; populated as
         * upload destinations are configured and progressed.
         */
        private java.util.Map<String, String> uploadStatus =
                new java.util.HashMap<>();

        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getTerminalId() { return terminalId; }
        public void setTerminalId(String terminalId) { this.terminalId = terminalId; }

        public int getSequenceNumber() { return sequenceNumber; }
        public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }

        public String getAuthData() { return authData; }
        public void setAuthData(String authData) { this.authData = authData; }

        public String getTrack2Data() { return track2Data; }
        public void setTrack2Data(String track2Data) { this.track2Data = track2Data; }

        public String getPinBlock() { return pinBlock; }
        public void setPinBlock(String pinBlock) { this.pinBlock = pinBlock; }

        public long getAmountCents() { return amountCents; }
        public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

        public long getSurchargeCents() { return surchargeCents; }
        public void setSurchargeCents(long surchargeCents) { this.surchargeCents = surchargeCents; }

        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

        public long getCreatedTime() { return createdTime; }
        public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

        public long getLastAttemptTime() { return lastAttemptTime; }
        public void setLastAttemptTime(long lastAttemptTime) { this.lastAttemptTime = lastAttemptTime; }

        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }

        // ---- Per-destination upload tracking (task #5) ----

        /**
         * Returns the upload status for a specific destination, or
         * {@link #UPLOAD_PENDING} if not yet set.
         */
        public String getUploadStatusFor(String destination) {
            if (uploadStatus == null) return UPLOAD_PENDING;
            String s = uploadStatus.get(destination);
            return s != null ? s : UPLOAD_PENDING;
        }

        /**
         * Sets the upload status for a specific destination.
         */
        public void setUploadStatusFor(String destination, String status) {
            if (uploadStatus == null) {
                uploadStatus = new java.util.HashMap<>();
            }
            uploadStatus.put(destination, status);
        }

        /**
         * Returns the full upload status map (live reference). Used by
         * persistence layer for serialization.
         */
        public java.util.Map<String, String> getUploadStatusMap() {
            if (uploadStatus == null) uploadStatus = new java.util.HashMap<>();
            return uploadStatus;
        }

        public void setUploadStatusMap(java.util.Map<String, String> map) {
            this.uploadStatus = map != null ? map : new java.util.HashMap<>();
        }

        /**
         * Returns true if all configured destinations have COMPLETED uploads.
         * Destinations marked UPLOAD_NA are treated as completed.
         */
        public boolean allUploadsComplete() {
            if (uploadStatus == null || uploadStatus.isEmpty()) return false;
            for (String s : uploadStatus.values()) {
                if (!UPLOAD_COMPLETED.equals(s) && !UPLOAD_NA.equals(s)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Gets formatted amount as dollars.
         */
        public String getFormattedAmount() {
            return String.format(Locale.US, "$%.2f", amountCents / 100.0);
        }

        /**
         * Gets formatted creation time.
         */
        public String getFormattedCreatedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
            return sdf.format(new Date(createdTime));
        }

        /**
         * Converts to JSON for storage.
         */
        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("transactionId", transactionId);
                obj.put("terminalId", terminalId);
                obj.put("sequenceNumber", sequenceNumber);
                obj.put("authData", authData);
                obj.put("track2Data", track2Data);
                obj.put("pinBlock", pinBlock);
                obj.put("amountCents", amountCents);
                obj.put("surchargeCents", surchargeCents);
                obj.put("reasonCode", reasonCode);
                obj.put("createdTime", createdTime);
                obj.put("lastAttemptTime", lastAttemptTime);
                obj.put("attemptCount", attemptCount);
                obj.put("status", status);
                obj.put("lastError", lastError);

                // Per-destination upload tracking (task #5)
                if (uploadStatus != null && !uploadStatus.isEmpty()) {
                    JSONObject up = new JSONObject();
                    for (java.util.Map.Entry<String, String> e : uploadStatus.entrySet()) {
                        up.put(e.getKey(), e.getValue());
                    }
                    obj.put("uploadStatus", up);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error converting to JSON: " + e.getMessage());
            }
            return obj;
        }

        /**
         * Creates from JSON.
         */
        public static PendingReversal fromJson(JSONObject obj) throws JSONException {
            PendingReversal rev = new PendingReversal();
            rev.transactionId = obj.optString("transactionId", "");
            rev.terminalId = obj.optString("terminalId", "");
            rev.sequenceNumber = obj.optInt("sequenceNumber", 0);
            rev.authData = obj.optString("authData", "");
            rev.track2Data = obj.optString("track2Data", "");
            rev.pinBlock = obj.optString("pinBlock", "");
            rev.amountCents = obj.optLong("amountCents", 0);
            rev.surchargeCents = obj.optLong("surchargeCents", 0);
            rev.reasonCode = obj.optString("reasonCode", "");
            rev.createdTime = obj.optLong("createdTime", 0);
            rev.lastAttemptTime = obj.optLong("lastAttemptTime", 0);
            rev.attemptCount = obj.optInt("attemptCount", 0);
            rev.status = obj.optString("status", STATUS_PENDING);
            rev.lastError = obj.optString("lastError", "");

            // Per-destination upload tracking (task #5)
            JSONObject up = obj.optJSONObject("uploadStatus");
            if (up != null) {
                java.util.Iterator<String> keys = up.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    rev.uploadStatus.put(k, up.optString(k, UPLOAD_PENDING));
                }
            }
            return rev;
        }

        @Override
        public String toString() {
            return "PendingReversal{" +
                   "txnId='" + transactionId + '\'' +
                   ", amount=" + getFormattedAmount() +
                   ", attempts=" + attemptCount +
                   ", status='" + status + '\'' +
                   '}';
        }
    }

    /**
     * Represents a completed reversal in history.
     */
    public static class CompletedReversal {
        private String transactionId;
        private long amountCents;
        private long createdTime;
        private long completedTime;
        private boolean success;
        private int attempts;

        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public long getAmountCents() { return amountCents; }
        public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

        public long getCreatedTime() { return createdTime; }
        public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

        public long getCompletedTime() { return completedTime; }
        public void setCompletedTime(long completedTime) { this.completedTime = completedTime; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }

        public String getFormattedAmount() {
            return String.format(Locale.US, "$%.2f", amountCents / 100.0);
        }

        public String getFormattedCompletedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
            return sdf.format(new Date(completedTime));
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("transactionId", transactionId);
                obj.put("amountCents", amountCents);
                obj.put("createdTime", createdTime);
                obj.put("completedTime", completedTime);
                obj.put("success", success);
                obj.put("attempts", attempts);
            } catch (JSONException e) {
                Log.e(TAG, "Error converting to JSON: " + e.getMessage());
            }
            return obj;
        }

        public static CompletedReversal fromJson(JSONObject obj) throws JSONException {
            CompletedReversal rev = new CompletedReversal();
            rev.transactionId = obj.optString("transactionId", "");
            rev.amountCents = obj.optLong("amountCents", 0);
            rev.createdTime = obj.optLong("createdTime", 0);
            rev.completedTime = obj.optLong("completedTime", 0);
            rev.success = obj.optBoolean("success", false);
            rev.attempts = obj.optInt("attempts", 0);
            return rev;
        }
    }
}
