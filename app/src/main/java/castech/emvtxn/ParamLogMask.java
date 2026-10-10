package castech.emvtxn;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the CasHUB provider dump may log (security review 2026-10-10, parser differential):
 * NEVER a value. Key names, value lengths and a [secret] marker, read through the same org.json
 * parser the merge uses; content that parser rejects is logged as its length only. There is no
 * masking step left to disagree with the merge.
 */
public final class ParamLogMask {

    /** Every parameter whose value is a credential. One list, so a new secret cannot be forgotten in one place. */
    public static final Set<String> SECRET_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            castech.emvtxn.pos.PosParams.KEY_ACCESS_KEY,
            castech.emvtxn.net.ApnParams.KEY_PASSWORD,
            castech.emvtxn.reporting.ReportingParams.KEY_ACCESS_KEY,
            castech.emvtxn.admin.AdminPinParams.KEY_ADMIN,
            castech.emvtxn.admin.AdminPinParams.KEY_SUPER)));

    private ParamLogMask() {}

    public static String summarize(String content) {
        if (content == null) return "[null]";
        String t = content.trim();
        if (t.isEmpty()) return "<empty>";
        try {
            JSONObject o = new JSONObject(t);
            StringBuilder b = new StringBuilder("keys: ");
            boolean first = true;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!first) b.append(", ");
                first = false;
                b.append(k);
                if (SECRET_KEYS.contains(k)) b.append("[secret]");
                else b.append('(').append(String.valueOf(o.opt(k)).length()).append(')');
            }
            return b.toString();
        } catch (JSONException notJson) {
            return "<non-JSON " + content.length() + " chars>";
        }
    }
}
