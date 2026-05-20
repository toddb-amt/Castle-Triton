package castech.emvtxn.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Configuration for POS-mode proxy connection. Persisted in its own
 * SharedPreferences file (pos_config) to keep concerns isolated from the
 * customer-flow settings managed by {@link castech.emvtxn.AtmSettingsManager}.
 *
 * <p>Fields stored:
 * <ul>
 *   <li>{@code enabled} — POS mode on/off (when off, app behaves as customer-driven ATM)
 *   <li>{@code proxyBaseUrl} — wss:// or ws:// URL of the proxy host (e.g. {@code wss://proxy.myviewonline.com})
 *   <li>{@code terminalAccessKey} — credential issued by the proxy admin portal for this terminal
 *   <li>{@code jwt} + {@code jwtExpiresAtMillis} — connection-server JWT cache (cleared when expired)
 * </ul>
 */
public class PosConfig {

    private static final String TAG = "PosConfig";
    private static final String PREFS_FILE = "pos_config";

    private static final String K_ENABLED = "enabled";
    private static final String K_PROXY_BASE_URL = "proxy_base_url";
    private static final String K_TERMINAL_ACCESS_KEY = "terminal_access_key";
    private static final String K_JWT = "jwt";
    private static final String K_JWT_EXPIRES_AT = "jwt_expires_at";

    /** JWT is treated as expired this many millis before its real expiry — gives the renewal time to run. */
    public static final long JWT_RENEWAL_MARGIN_MILLIS = 60_000L;

    private final SharedPreferences prefs;

    public PosConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply();
        Log.d(TAG, "POS mode " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public String getProxyBaseUrl() {
        return prefs.getString(K_PROXY_BASE_URL, "");
    }

    public void setProxyBaseUrl(String url) {
        prefs.edit().putString(K_PROXY_BASE_URL, url == null ? "" : url.trim()).apply();
    }

    public String getTerminalAccessKey() {
        return prefs.getString(K_TERMINAL_ACCESS_KEY, "");
    }

    public void setTerminalAccessKey(String key) {
        prefs.edit().putString(K_TERMINAL_ACCESS_KEY, key == null ? "" : key.trim()).apply();
    }

    public String getJwt() {
        return prefs.getString(K_JWT, "");
    }

    public long getJwtExpiresAtMillis() {
        return prefs.getLong(K_JWT_EXPIRES_AT, 0L);
    }

    public void setJwt(String jwt, long expiresAtMillis) {
        prefs.edit()
                .putString(K_JWT, jwt == null ? "" : jwt)
                .putLong(K_JWT_EXPIRES_AT, expiresAtMillis)
                .apply();
    }

    public void clearJwt() {
        prefs.edit().remove(K_JWT).remove(K_JWT_EXPIRES_AT).apply();
    }

    /**
     * Returns true if the cached JWT is missing, empty, or within the renewal margin of expiry.
     * Callers should re-register when this returns true.
     */
    public boolean isJwtExpired() {
        String jwt = getJwt();
        if (jwt.isEmpty()) return true;
        long expiresAt = getJwtExpiresAtMillis();
        if (expiresAt <= 0L) return true;
        return System.currentTimeMillis() + JWT_RENEWAL_MARGIN_MILLIS >= expiresAt;
    }

    /** Returns true if {@link #getProxyBaseUrl()} and {@link #getTerminalAccessKey()} are both set. */
    public boolean hasCredentials() {
        return !getProxyBaseUrl().isEmpty() && !getTerminalAccessKey().isEmpty();
    }
}
