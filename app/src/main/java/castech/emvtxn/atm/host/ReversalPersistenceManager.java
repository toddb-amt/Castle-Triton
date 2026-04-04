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
    private ReversalListener listener;

    /**
     * Creates a new ReversalPersistenceManager.
     *
     * @param context Android context
     */
    public ReversalPersistenceManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
        // Manual removal to avoid Java 8 lambdas (Castle terminal compatibility)
        java.util.Iterator<PendingReversal> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingReversal r = iterator.next();
            if (r.getTransactionId().equals(transactionId)) {
                iterator.remove();
            }
        }
        savePendingReversals(pending);
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
    public ReversalRequest toReversalRequest(PendingReversal pending, String routingId) {
        ReversalRequest request = new ReversalRequest();
        request.setRoutingId(routingId);
        request.setTerminalId(pending.getTerminalId());
        request.setOriginalSequenceNumber(pending.getSequenceNumber());
        request.setOriginalAuthData(pending.getAuthData());
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
        public static final String STATUS_PENDING = "pending";
        public static final String STATUS_PROCESSING = "processing";
        public static final String STATUS_FAILED = "failed";

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
