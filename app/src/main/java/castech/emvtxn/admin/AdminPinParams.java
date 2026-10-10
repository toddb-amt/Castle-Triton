package castech.emvtxn.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Admin PIN parameters CasHUB can push (SEC-03, 6.2.15), parsed out of the merged parameter
 * map that {@code CasHubParams} builds. Pure Java so the rules are unit-tested.
 * <ul>
 *   <li>{@code admin_pin} — the merchant's staff PIN (merchant or terminal level); 6–8 digits.</li>
 *   <li>{@code super_admin_pin} — TFI's PIN (top level); 6–8 digits.</li>
 * </ul>
 * Invalid values are a problem and leave the stored hash alone. Absent keys are not managed by
 * CasHUB: the stored hash stands. The values are credentials: never logged ({@link #maskForLog}),
 * never in the host-config payload or the KMS backup.
 */
public final class AdminPinParams {

    public static final String KEY_ADMIN = "admin_pin";
    public static final String KEY_SUPER = "super_admin_pin";
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(KEY_ADMIN, KEY_SUPER)));

    /** Validated PIN, or null when absent or unusable. */
    public final String adminPin;
    public final String superPin;
    /** Why a present key was ignored. Never contains a value. */
    public final List<String> problems;
    private final boolean present;

    private AdminPinParams(String adminPin, String superPin, List<String> problems, boolean present) {
        this.adminPin = adminPin; this.superPin = superPin; this.problems = problems; this.present = present;
    }

    public static AdminPinParams parse(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        boolean present = false;
        String admin = null, sup = null;
        if (params != null) {
            if (params.containsKey(KEY_ADMIN)) { present = true; admin = valid(KEY_ADMIN, params.get(KEY_ADMIN), problems); }
            if (params.containsKey(KEY_SUPER)) { present = true; sup = valid(KEY_SUPER, params.get(KEY_SUPER), problems); }
        }
        return new AdminPinParams(admin, sup, Collections.unmodifiableList(problems), present);
    }

    private static String valid(String key, String raw, List<String> problems) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() < 6 || v.length() > 8 || !v.matches("[0-9]+")) {
            problems.add(key + ": must be 6 to 8 digits — ignored");
            return null;
        }
        return v;
    }

    public boolean isPresent() { return present; }

    public String describe() {
        return "admin pins: admin=" + (adminPin != null ? "[set]" : "[unchanged]")
                + " super=" + (superPin != null ? "[set]" : "[unchanged]")
                + (problems.isEmpty() ? "" : " problems=" + problems);
    }

    /** Masks both PINs in raw parameter content (JSON or key=value) so provider rows can be logged. */
    public static String maskForLog(String content) {
        if (content == null) return "[null]";
        String s = content;
        for (String k : KEYS) {
            s = s.replaceAll("(\"" + k + "\"\\s*:\\s*\")[^\"]*(\")", "$1[masked]$2");
            s = s.replaceAll("(?m)^(" + k + "=)[^\\n]*", "$1[masked]");
        }
        return s;
    }
}
