package castech.emvtxn.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The MyView reporting settings CasHUB can push (RPT-02, 6.2.13), parsed out of the merged
 * parameter map that {@code CasHubParams} builds. Pure Java so the rules are unit-tested.
 * <ul>
 *   <li>{@code reporting_access_key} — the tenant key TFI issues; trimmed; blank is a problem and
 *       leaves the stored key alone. The literal value {@code off} (any case) clears the stored key
 *       and switches reporting off — the only off-switch (review I6). A bearer credential: never
 *       logged ({@link #maskForLog}).</li>
 *   <li>{@code reporting_url} — override of the production ingestion URL; must be {@code https://};
 *       trailing slashes removed. Invalid is a problem and leaves the stored URL alone.</li>
 * </ul>
 * An absent key is not managed by CasHUB: the stored value stands.
 */
public final class ReportingParams {

    public static final String KEY_ACCESS_KEY = "reporting_access_key";
    public static final String KEY_URL        = "reporting_url";

    /** The keys this class consumes; CasHubParams keeps them out of the host-config payload. */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(KEY_ACCESS_KEY, KEY_URL)));

    /** The value that switches reporting off. Keys are long random strings, so this cannot collide. */
    public static final String OFF = "off";

    /** Parsed value, or null when the key is absent or unusable. */
    public final String accessKey;
    public final String url;
    /** True when {@code reporting_access_key} was the {@link #OFF} sentinel: clear the stored key. */
    public final boolean clearKey;
    /** Why a present key was ignored. Never contains the key. */
    public final List<String> problems;
    private final boolean anyKeyPresent;

    private ReportingParams(String accessKey, String url, boolean clearKey, List<String> problems, boolean anyKeyPresent) {
        this.accessKey = accessKey;
        this.url = url;
        this.clearKey = clearKey;
        this.problems = problems;
        this.anyKeyPresent = anyKeyPresent;
    }

    public static ReportingParams parse(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        boolean present = false;
        String key = null;
        String url = null;
        boolean clear = false;
        if (params != null) {
            if (params.containsKey(KEY_ACCESS_KEY)) {
                present = true;
                String raw = trim(params.get(KEY_ACCESS_KEY));
                if (raw.equalsIgnoreCase(OFF)) clear = true;
                else if (!raw.isEmpty()) key = raw;
                else problems.add(KEY_ACCESS_KEY + ": blank — ignored (use \"off\" to switch reporting off)");
            }
            if (params.containsKey(KEY_URL)) {
                present = true;
                String raw = trim(params.get(KEY_URL));
                while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
                // https and parseable by the client that will use it (a typo that passes a prefix
                // check but not OkHttp would otherwise throw inside the pusher — review I2)
                okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(raw);
                if (parsed != null && "https".equals(parsed.scheme())) url = raw;
                else problems.add(KEY_URL + ": must be a valid https:// URL" + (raw.isEmpty() ? " (blank)" : ", got \"" + raw + "\"") + " — ignored");
            }
        }
        return new ReportingParams(key, url, clear, Collections.unmodifiableList(problems), present);
    }

    /** True when neither key appeared in the map. */
    public boolean isEmpty() { return !anyKeyPresent; }

    /** Loggable summary: the URL in clear, the key only as [set] / [unchanged]. */
    public String describe() {
        return "reporting: key=" + (clearKey ? "[off]" : accessKey != null ? "[set]" : "[unchanged]")
                + " url=" + (url != null ? url : "[unchanged]")
                + (problems.isEmpty() ? "" : " problems=" + problems);
    }

    /** Masks the key in raw parameter content (JSON or key=value) so provider rows can be logged. */
    public static String maskForLog(String content) {
        if (content == null) return "[null]";
        String s = content.replaceAll("(\"" + KEY_ACCESS_KEY + "\"\\s*:\\s*\")[^\"]*(\")", "$1[masked]$2");
        return s.replaceAll("(?m)^(" + KEY_ACCESS_KEY + "=)[^\\n]*", "$1[masked]");
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
