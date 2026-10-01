package castech.emvtxn.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Applies a CasHUB-pushed APN through Castle's settings service ({@code CTOS.CtSettings}),
 * which runs with system rights — a normal app cannot write APNs on Android itself.
 *
 * <p>Apply-and-report (decision 2026-10-01): no automatic rollback. Units are provisioned on
 * WiFi and a SIM unit keeps WiFi alongside, so a wrong APN can always be corrected from
 * CasHUB. The outcome is persisted so the Admin network card and the log tell the story
 * after a reboot.</p>
 */
public final class ApnApplier {

    private static final String TAG = "ApnApplier";
    private static final String PREFS = "apn_config";
    /** Single-SIM terminal (persist.radio.multisim.config=ssss) — slot 0. */
    static final int SLOT = 0;

    private ApnApplier() {}

    /** Last outcome, for the Admin card. */
    public static final class Status {
        public final String desiredApn;
        public final String desiredName;
        public final String result;     // "applied" | "unchanged" | "failed: …" | "" (never attempted)
        public final long at;
        Status(String desiredApn, String desiredName, String result, long at) {
            this.desiredApn = desiredApn; this.desiredName = desiredName; this.result = result; this.at = at;
        }
        public boolean attempted() { return at > 0; }
    }

    public static void applyIfChangedAsync(final Context ctx, final ApnParams p) {
        Thread t = new Thread(() -> applyIfChanged(ctx.getApplicationContext(), p), "ApnApply");
        t.setDaemon(true);
        t.start();
    }

    /** Blocking; call off the main thread. */
    static void applyIfChanged(Context ctx, ApnParams p) {
        if (p == null || !p.isActionable()) return;
        String result;
        try {
            CTOS.CtSettings settings = new CTOS.CtSettings();

            // What the modem is using now
            String curApn = null, curUser = null; int curAuth = ApnParams.AUTH_NONE;
            try {
                CTOS.Apn cur = settings.getCurrentApn(SLOT);
                if (cur != null) { curApn = cur.getApn(); curUser = cur.getUser(); curAuth = cur.getAuthtype(); }
            } catch (Throwable t) {
                Log.w(TAG, "getCurrentApn failed (continuing): " + t.getMessage());
            }
            if (!p.needsChange(curApn, curUser, "IP", curAuth)) {
                result = "unchanged";
                Log.d(TAG, "APN already in place (" + curApn + ") — nothing to do");
            } else {
                // Carrier codes from the SIM (no permission needed)
                String mcc = "", mnc = "";
                TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
                String op = tm == null ? null : tm.getSimOperator();
                if (op == null || op.length() < 5) {
                    result = "failed: no SIM operator (SIM absent?)";
                    Log.w(TAG, "Cannot apply APN — " + result);
                    persist(ctx, p, result);
                    return;
                }
                mcc = op.substring(0, 3); mnc = op.substring(3);

                int rc;
                try {
                    CTOS.Apn2 a = new CTOS.Apn2();
                    a.setName(p.name); a.setApn(p.apn); a.setMcc(mcc); a.setMnc(mnc);
                    a.setUser(p.user == null ? "" : p.user);
                    a.setPassword(p.password == null ? "" : p.password);
                    a.setAuthtype(p.authType);
                    a.setType("default,supl");
                    a.setServer("");
                    a.setApn_protocol(p.protocol);
                    a.setApn_roaming_protocol(p.protocol);
                    rc = settings.setApn3(SLOT, a);
                    Log.d(TAG, "setApn3 rc=" + rc);
                } catch (Throwable t) {
                    Log.w(TAG, "setApn3 unavailable (" + t.getMessage() + ") — falling back to setApn");
                    CTOS.Apn a = new CTOS.Apn();
                    a.setName(p.name); a.setApn(p.apn); a.setMcc(mcc); a.setMnc(mnc);
                    a.setUser(p.user == null ? "" : p.user);
                    a.setPassword(p.password == null ? "" : p.password);
                    a.setAuthtype(p.authType);
                    a.setType("default,supl");
                    a.setServer("");
                    rc = settings.setApn(SLOT, a);
                    Log.d(TAG, "setApn rc=" + rc);
                }
                boolean selected = false;
                try { selected = settings.selectApn(SLOT, p.name); } catch (Throwable t) { Log.w(TAG, "selectApn failed: " + t.getMessage()); }
                boolean dataOn = false;
                try { dataOn = settings.setMobileDataEnabled(true); } catch (Throwable t) { Log.w(TAG, "setMobileDataEnabled failed: " + t.getMessage()); }
                result = (rc == 0 && selected) ? "applied"
                        : "failed: rc=" + rc + " selected=" + selected + " dataOn=" + dataOn;
                Log.w(TAG, "APN apply " + result + " — " + p.describe() + " mcc=" + mcc + " mnc=" + mnc);
            }
        } catch (Throwable t) {
            result = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "APN apply error", t);
        }
        persist(ctx, p, result);
    }

    private static void persist(Context ctx, ApnParams p, String result) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString("desired_apn", p.apn).putString("desired_name", p.name)
                    .putString("result", result).putLong("at", System.currentTimeMillis()).apply();
        } catch (Throwable ignore) {}
    }

    public static Status readStatus(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new Status(sp.getString("desired_apn", ""), sp.getString("desired_name", ""),
                    sp.getString("result", ""), sp.getLong("at", 0L));
        } catch (Throwable t) {
            return new Status("", "", "", 0L);
        }
    }

    /** "name (apn)" the modem currently uses, or a short reason. Blocking; call off the main thread. */
    public static String currentApnSummary() {
        try {
            CTOS.Apn cur = new CTOS.CtSettings().getCurrentApn(SLOT);
            if (cur == null || cur.getApn() == null || cur.getApn().isEmpty()) return "none selected";
            return cur.getName() + " (" + cur.getApn() + ")";
        } catch (Throwable t) {
            return "unavailable";
        }
    }
}
