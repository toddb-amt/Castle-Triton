package castech.emvtxn.admin;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the terminal keeps its Admin PIN hashes (SEC-03, 6.2.15): app-private preferences holding a
 * per-terminal salt (made once) and the two hashes — never a PIN. Own file, so the hashes never sit
 * beside the host configuration (backed up to KMS-II).
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

    public boolean isAdminConfigured() { return !prefs.getString(K_ADMIN, "").isEmpty(); }
    public boolean isSuperConfigured() { return !prefs.getString(K_SUPER, "").isEmpty(); }

    /** {@link AdminPins#TIER_SUPER}, {@link AdminPins#TIER_NORMAL} or {@link AdminPins#TIER_NONE}. */
    public int check(String enteredPin) {
        return AdminPins.tier(enteredPin, prefs.getString(K_ADMIN, null), prefs.getString(K_SUPER, null), salt());
    }
}
