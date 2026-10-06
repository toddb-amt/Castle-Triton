package castech.emvtxn.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The cellular APN as CasHUB parameters (6.2.10). Pure Java so the rules are unit-tested.
 *
 * <ul>
 *   <li>{@code apn} — the access point name (required for anything to happen); letters,
 *       digits, dots and dashes.</li>
 *   <li>{@code apn_name} — display name, defaults to the APN.</li>
 *   <li>{@code apn_user}, {@code apn_password} — optional credentials. The password is never
 *       logged (see {@link #maskForLog} / {@link #describe}).</li>
 *   <li>{@code apn_auth_type} — {@code none|pap|chap|both} or {@code 0..3}; default none.</li>
 *   <li>{@code apn_protocol} — {@code IP|IPV6|IPV4V6}; default IP.</li>
 * </ul>
 * Absent keys mean "not managed by CasHUB". A present-but-unusable value is reported in
 * {@link #problems} and either ignored (secondary keys fall back to their default) or, for
 * {@code apn} itself, makes the whole set non-actionable.
 */
public final class ApnParams {

    public static final String KEY_APN       = "apn";
    public static final String KEY_NAME      = "apn_name";
    public static final String KEY_USER      = "apn_user";
    public static final String KEY_PASSWORD  = "apn_password";
    public static final String KEY_AUTH_TYPE = "apn_auth_type";
    public static final String KEY_PROTOCOL  = "apn_protocol";

    /** The keys this class consumes; CasHubParams keeps them out of the host-config path. */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(KEY_APN, KEY_NAME, KEY_USER, KEY_PASSWORD, KEY_AUTH_TYPE, KEY_PROTOCOL)));

    public static final int AUTH_NONE = 0;
    public static final int AUTH_PAP  = 1;
    public static final int AUTH_CHAP = 2;
    public static final int AUTH_BOTH = 3;

    /** null when {@code apn} is absent or unusable. */
    public final String apn;
    public final String name;
    public final String user;
    public final String password;
    public final int authType;
    public final String protocol;
    /** Human-readable reasons a present key was ignored. Never contains the password. */
    public final List<String> problems;
    private final boolean anyKeyPresent;

    private ApnParams(String apn, String name, String user, String password, int authType,
                      String protocol, List<String> problems, boolean anyKeyPresent) {
        this.apn = apn;
        this.name = name;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.protocol = protocol;
        this.problems = problems;
        this.anyKeyPresent = anyKeyPresent;
    }

    public static ApnParams parse(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        boolean present = false;
        String apn = null, name = null, user = null, password = null, protocol = "IP";
        int auth = AUTH_NONE;
        if (params != null) {
            for (String k : KEYS) if (params.containsKey(k)) present = true;
            if (!present) {
                return new ApnParams(null, null, null, null, AUTH_NONE, "IP",
                        Collections.<String>emptyList(), false);
            }
            if (params.containsKey(KEY_APN)) {
                String raw = trim(params.get(KEY_APN));
                if (raw.isEmpty()) {
                    problems.add(KEY_APN + ": blank — nothing applied");
                } else if (!raw.matches("[A-Za-z0-9.\\-]+")) {
                    problems.add(KEY_APN + ": only letters, digits, '.' and '-' allowed, got \"" + raw + "\" — nothing applied");
                } else {
                    apn = raw;
                }
            } else {
                problems.add(KEY_APN + ": missing while other apn_* keys are present — nothing applied");
            }
            if (params.containsKey(KEY_NAME)) {
                String raw = trim(params.get(KEY_NAME));
                if (!raw.isEmpty()) name = raw;
            }
            if (params.containsKey(KEY_USER)) {
                String raw = trim(params.get(KEY_USER));
                if (!raw.isEmpty()) user = raw;
            }
            if (params.containsKey(KEY_PASSWORD)) {
                String raw = params.get(KEY_PASSWORD) == null ? "" : params.get(KEY_PASSWORD).trim();
                if (!raw.isEmpty()) password = raw;
            }
            if (params.containsKey(KEY_AUTH_TYPE)) {
                Integer a = parseAuth(params.get(KEY_AUTH_TYPE));
                if (a == null) {
                    problems.add(KEY_AUTH_TYPE + ": expected none/pap/chap/both or 0-3, got \""
                            + trim(params.get(KEY_AUTH_TYPE)) + "\" — using none");
                } else {
                    auth = a;
                }
            }
            if (params.containsKey(KEY_PROTOCOL)) {
                String raw = trim(params.get(KEY_PROTOCOL)).toUpperCase(Locale.US);
                if (raw.equals("IP") || raw.equals("IPV6") || raw.equals("IPV4V6")) {
                    protocol = raw;
                } else {
                    problems.add(KEY_PROTOCOL + ": expected IP/IPV6/IPV4V6, got \"" + raw + "\" — using IP");
                }
            }
        }
        if (apn != null && name == null) name = apn;
        return new ApnParams(apn, name, user, password, auth, protocol,
                Collections.unmodifiableList(problems), present);
    }

    /** True when none of the apn keys appeared. */
    public boolean isEmpty() {
        return !anyKeyPresent;
    }

    /** True when there is a usable {@code apn} to apply. */
    public boolean isActionable() {
        return apn != null;
    }

    /**
     * Whether the modem's current APN differs in a way that matters (the APN string,
     * credentials user, protocol, auth type). The display name is not compared.
     */
    public boolean needsChange(String currentApn, String currentUser, String currentProtocol, int currentAuthType) {
        if (apn == null) return false;
        if (currentApn == null || !apn.equalsIgnoreCase(currentApn.trim())) return true;
        String cu = currentUser == null ? "" : currentUser.trim();
        String mu = user == null ? "" : user;
        if (!mu.equals(cu)) return true;
        String cp = currentProtocol == null ? "IP" : currentProtocol.trim().toUpperCase(Locale.US);
        if (!protocol.equals(cp)) return true;
        return authType != currentAuthType;
    }

    /** Log-safe summary: never the password, never the user name itself. */
    public String describe() {
        return "apn=" + apn + " name=" + name + " protocol=" + protocol + " auth=" + authName(authType)
                + " user=" + (user == null ? "[none]" : "[set]")
                + " password=" + (password == null ? "[none]" : "[set]");
    }

    /** Masks {@code apn_password} in raw parameter content (JSON or key=value lines). */
    public static String maskForLog(String content) {
        if (content == null) return "[null]";
        String s = content.replaceAll("(\"" + KEY_PASSWORD + "\"\\s*:\\s*\")[^\"]*(\")", "$1[masked]$2");
        s = s.replaceAll("(?m)^(" + KEY_PASSWORD + "=)[^\\n]*", "$1[masked]");
        return s;
    }

    public static String authName(int authType) {
        switch (authType) {
            case AUTH_PAP:  return "pap";
            case AUTH_CHAP: return "chap";
            case AUTH_BOTH: return "both";
            default:        return "none";
        }
    }

    // ---- helpers -------------------------------------------------------------------

    private static Integer parseAuth(String raw) {
        String v = trim(raw).toLowerCase(Locale.US);
        switch (v) {
            case "none": case "0": return AUTH_NONE;
            case "pap":  case "1": return AUTH_PAP;
            case "chap": case "2": return AUTH_CHAP;
            case "both": case "papchap": case "pap/chap": case "3": return AUTH_BOTH;
            default: return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
