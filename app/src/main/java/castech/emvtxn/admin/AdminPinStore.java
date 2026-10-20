package castech.emvtxn.admin;

import android.content.Context;
import android.content.SharedPreferences;

import castech.emvtxn.BuildConfig;

/**
 * Where the terminal keeps its Admin PIN hashes (SEC-03, 6.2.15): app-private preferences holding a
 * per-terminal salt (made once) and the two hashes — never a PIN. Own file, so the hashes never sit
 * beside the host configuration (backed up to KMS-II).
 *
 * <p>SEC-05 (6.2.18): the Super Admin tier also has a built-in PIN, hashed into the build
 * ({@link BuildConfig#BUILT_IN_SUPER_PIN_HASH} under {@link BuildConfig#BUILT_IN_PIN_SALT}). It is in
 * force until CasHUB pushes {@code super_admin_pin}; a pushed PIN replaces it. The merchant tier has
 * no built-in PIN.
 */
public final class AdminPinStore {

    private static final String PREFS = "admin_pins";
    private static final String K_SALT = "salt";
    private static final String K_ADMIN = "admin_hash";
    private static final String K_SUPER = "super_hash";

    private final SharedPreferences prefs;

    public AdminPinStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private synchronized String salt() {
        String s = prefs.getString(K_SALT, "");
        if (s.isEmpty()) {
            s = AdminPins.newSaltHex();
            prefs.edit().putString(K_SALT, s).commit();
        }
        return s;
    }

    /** Hashes and stores; the clear PIN is not kept. */
    public void setAdminPin(String pin) { prefs.edit().putString(K_ADMIN, AdminPins.hash(pin, salt())).commit(); }
    public void setSuperPin(String pin) { prefs.edit().putString(K_SUPER, AdminPins.hash(pin, salt())).commit(); }

    /** Where the Super Admin PIN in force comes from. */
    public enum SuperSource { NONE, BUILT_IN, CASHUB }

    /** True when CasHUB has pushed {@code admin_pin} (the merchant tier has no built-in PIN). */
    public boolean isAdminConfigured() { return !prefs.getString(K_ADMIN, "").isEmpty(); }
    /** True when CasHUB has pushed {@code super_admin_pin}. */
    public boolean isSuperPushed() { return !prefs.getString(K_SUPER, "").isEmpty(); }
    /** True when this build was made with admin-pins.properties present (release builds always are). */
    public static boolean hasBuiltInSuper() {
        return BuildConfig.BUILT_IN_SUPER_PIN_HASH != null && !BuildConfig.BUILT_IN_SUPER_PIN_HASH.isEmpty();
    }
    public SuperSource superSource() {
        if (isSuperPushed()) return SuperSource.CASHUB;
        return hasBuiltInSuper() ? SuperSource.BUILT_IN : SuperSource.NONE;
    }
    /** True when some Super Admin PIN (pushed or built-in) is in force. */
    public boolean isSuperConfigured() { return superSource() != SuperSource.NONE; }

    /** {@link AdminPins#TIER_SUPER}, {@link AdminPins#TIER_NORMAL} or {@link AdminPins#TIER_NONE}. */
    public int check(String enteredPin) {
        return AdminPins.tier(enteredPin, prefs.getString(K_ADMIN, null), prefs.getString(K_SUPER, null), salt(),
                BuildConfig.BUILT_IN_SUPER_PIN_HASH, BuildConfig.BUILT_IN_PIN_SALT);
    }
}
