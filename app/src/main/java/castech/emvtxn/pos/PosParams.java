package castech.emvtxn.pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The POS-mode settings CasHUB can push centrally, parsed out of the merged
 * parameter map that {@code CasHubParams} builds from the agent's content provider.
 *
 * <p>Pure Java on purpose (no Android imports) so the rules are unit-tested:
 * <ul>
 *   <li>{@code pos_enabled} — {@code true|false|1|0}; anything else is a problem and
 *       leaves the current value alone.</li>
 *   <li>{@code pos_proxy_url} — must be {@code wss://} or {@code ws://}; trimmed, trailing
 *       slashes removed. Anything else is a problem and leaves the current value alone.</li>
 *   <li>{@code pos_terminal_access_key} — trimmed; blank is a problem and leaves the current
 *       value alone. This is a bearer credential: it is never logged (see {@link #maskForLog}).</li>
 * </ul>
 * A key that is absent from the map is simply not managed by CasHUB — the local
 * value stands, matching how the host settings behave.
 */
public final class PosParams {

    public static final String KEY_ENABLED    = "pos_enabled";
    public static final String KEY_PROXY_URL  = "pos_proxy_url";
    public static final String KEY_ACCESS_KEY = "pos_terminal_access_key";

    /** The keys this class consumes; CasHubParams keeps them out of the host-config path. */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(KEY_ENABLED, KEY_PROXY_URL, KEY_ACCESS_KEY)));

    /** Parsed value, or null when the key is absent or its value is unusable. */
    public final Boolean enabled;
    public final String proxyUrl;
    public final String accessKey;
    /** Human-readable reasons a present key was ignored. Never contains the access key. */
    public final List<String> problems;

    private final boolean anyKeyPresent;

    private PosParams(Boolean enabled, String proxyUrl, String accessKey,
                      List<String> problems, boolean anyKeyPresent) {
        this.enabled = enabled;
        this.proxyUrl = proxyUrl;
        this.accessKey = accessKey;
        this.problems = problems;
        this.anyKeyPresent = anyKeyPresent;
    }

    public static PosParams parse(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        boolean present = false;
        Boolean enabled = null;
        String url = null;
        String key = null;

        if (params != null) {
            if (params.containsKey(KEY_ENABLED)) {
                present = true;
                String raw = params.get(KEY_ENABLED);
                enabled = parseBoolean(raw);
                if (enabled == null) {
                    problems.add(KEY_ENABLED + ": expected true/false/1/0, got \"" + raw + "\" — ignored");
                }
            }
            if (params.containsKey(KEY_PROXY_URL)) {
                present = true;
                String raw = trim(params.get(KEY_PROXY_URL));
                if (isValidProxyUrl(raw)) {
                    url = normalizeUrl(raw);
                } else {
                    problems.add(KEY_PROXY_URL + ": must start with wss:// or ws://"
                            + (raw.isEmpty() ? " (blank)" : ", got \"" + raw + "\"") + " — ignored");
                }
            }
            if (params.containsKey(KEY_ACCESS_KEY)) {
                present = true;
                String raw = trim(params.get(KEY_ACCESS_KEY));
                if (!raw.isEmpty()) {
                    key = raw;
                } else {
                    problems.add(KEY_ACCESS_KEY + ": blank — ignored");
                }
            }
        }
        return new PosParams(enabled, url, key, Collections.unmodifiableList(problems), present);
    }

    /** True when none of the three keys appeared in the map. */
    public boolean isEmpty() {
        return !anyKeyPresent;
    }

    public static boolean isValidProxyUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        if (u.startsWith("wss://")) return u.length() > "wss://".length();
        if (u.startsWith("ws://"))  return u.length() > "ws://".length();
        return false;
    }

    /** What applying these values would change relative to the current configuration. */
    public Diff diffAgainst(boolean currentEnabled, String currentUrl, String currentKey) {
        boolean enabledChanged = enabled != null && enabled != currentEnabled;
        boolean urlChanged = proxyUrl != null && !proxyUrl.equals(normalizeUrl(trim(currentUrl)));
        boolean keyChanged = accessKey != null && !accessKey.equals(currentKey);
        return new Diff(enabledChanged, urlChanged || keyChanged);
    }

    /**
     * Writes every present, valid value into {@code cfg} and reports what changed.
     * Absent or invalid keys leave the stored value untouched.
     */
    public Diff applyTo(PosConfig cfg) {
        Diff d = diffAgainst(cfg.isEnabled(), cfg.getProxyBaseUrl(), cfg.getTerminalAccessKey());
        if (proxyUrl != null)  cfg.setProxyBaseUrl(proxyUrl);
        if (accessKey != null) cfg.setTerminalAccessKey(accessKey);
        if (enabled != null)   cfg.setEnabled(enabled);
        return d;
    }

    public static final class Diff {
        public final boolean enabledChanged;
        /** Proxy URL or access key changed — the client must re-register. */
        public final boolean credentialsChanged;

        Diff(boolean enabledChanged, boolean credentialsChanged) {
            this.enabledChanged = enabledChanged;
            this.credentialsChanged = credentialsChanged;
        }

        public boolean any() {
            return enabledChanged || credentialsChanged;
        }
    }

    /**
     * Masks the access key wherever it appears in raw parameter content — JSON
     * ({@code "pos_terminal_access_key":"…"}) or key=value lines — so diagnostic
     * dumps of the provider rows can be logged safely.
     */
    public static String maskForLog(String content) {
        if (content == null) return "[null]";
        String s = content.replaceAll("(\"" + KEY_ACCESS_KEY + "\"\\s*:\\s*\")[^\"]*(\")", "$1[masked]$2");
        s = s.replaceAll("(?m)^(" + KEY_ACCESS_KEY + "=)[^\\n]*", "$1[masked]");
        return s;
    }

    // ---- helpers -------------------------------------------------------------------

    private static Boolean parseBoolean(String raw) {
        String v = trim(raw).toLowerCase(Locale.US);
        if (v.equals("true") || v.equals("1"))  return Boolean.TRUE;
        if (v.equals("false") || v.equals("0")) return Boolean.FALSE;
        return null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeUrl(String s) {
        String u = trim(s);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
