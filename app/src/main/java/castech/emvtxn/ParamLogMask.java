package castech.emvtxn;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Masks every secret CasHUB parameter before provider content is logged (security review
 * 2026-10-10, parser differential). The merge in {@code CasHubParams} reads parameters with
 * org.json; masking used to run separate regexes over the raw text, and anywhere the two parsers
 * disagreed — a unicode-escaped key, an escaped quote in a value, a bare number — the secret
 * reached the log. Masking now goes through the SAME parser: parse, replace the secret keys,
 * re-serialise. The line masks remain only for text org.json rejects (which the merge skips too).
 */
public final class ParamLogMask {

    /** Every parameter whose value is a credential. One list, so a new secret cannot be forgotten in one place. */
    public static final Set<String> SECRET_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            castech.emvtxn.pos.PosParams.KEY_ACCESS_KEY,
            castech.emvtxn.net.ApnParams.KEY_PASSWORD,
            castech.emvtxn.reporting.ReportingParams.KEY_ACCESS_KEY,
            castech.emvtxn.admin.AdminPinParams.KEY_ADMIN,
            castech.emvtxn.admin.AdminPinParams.KEY_SUPER)));

    private static final String MASK = "[masked]";

    private ParamLogMask() {}

    public static String mask(String content) {
        if (content == null) return "[null]";
        String t = content.trim();
        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                for (String k : SECRET_KEYS) if (o.has(k)) o.put(k, MASK);
                return o.toString();
            } catch (JSONException notJson) {
                // fall through: the merge would have skipped this content as well
            }
        }
        String s = content;
        for (String k : SECRET_KEYS) {
            s = s.replaceAll("(?m)^(\\s*" + k + "\\s*=\\s*)[^\\n]*", "$1" + MASK);
            s = s.replaceAll("(\"" + k + "\"\\s*:\\s*)(\"[^\"]*\"|[^,}\\s\\]]+)", "$1\"" + MASK + "\"");
        }
        return s;
    }
}
