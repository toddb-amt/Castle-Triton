package castech.emvtxn.reporting;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted reporting settings and the pusher's last known status (RPT-02). Own prefs file so
 * the key never sits beside the host configuration (which is backed up to KMS-II).
 */
public final class ReportingConfig {

    /** Production ingestion endpoint (the portal team's CloudFront front door, 2026-10-08; the earlier Lambda URL is retired). */
    public static final String DEFAULT_URL =
            "https://d16f8tt74onlvr.cloudfront.net/transactions/addTransaction";

    private static final String PREFS_FILE = "reporting_config";
    private static final String K_KEY = "access_key";
    private static final String K_URL = "url";
    private static final String K_STATE = "status_state";
    private static final String K_PENDING = "status_pending";
    private static final String K_PARKED = "status_parked";
    private static final String K_LAST_SENT = "status_last_sent_at";
    private static final String K_LAST_ERROR = "status_last_error";

    private final SharedPreferences prefs;

    public ReportingConfig(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public String getAccessKey() { return prefs.getString(K_KEY, ""); }
    public void setAccessKey(String key) { prefs.edit().putString(K_KEY, key == null ? "" : key).apply(); }
    public String getUrl() { return prefs.getString(K_URL, DEFAULT_URL); }
    public void setUrl(String url) { prefs.edit().putString(K_URL, url == null || url.isEmpty() ? DEFAULT_URL : url).apply(); }
    /** Reporting is on only when a key exists. */
    public boolean isConfigured() { return !getAccessKey().isEmpty(); }

    public void setStatus(String state, int pending, int parked, long lastSentAt, String lastError) {
        prefs.edit().putString(K_STATE, state).putInt(K_PENDING, pending).putInt(K_PARKED, parked)
                .putLong(K_LAST_SENT, lastSentAt).putString(K_LAST_ERROR, lastError == null ? "" : lastError).apply();
    }
    public String getStatusState() { return prefs.getString(K_STATE, ReportingStatus.NOT_CONFIGURED); }
    public int getPending() { return prefs.getInt(K_PENDING, 0); }
    public int getParked() { return prefs.getInt(K_PARKED, 0); }
    public long getLastSentAt() { return prefs.getLong(K_LAST_SENT, 0L); }
    public String getLastError() { return prefs.getString(K_LAST_ERROR, ""); }
}
