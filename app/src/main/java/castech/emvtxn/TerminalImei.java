package castech.emvtxn;

import android.content.Context;
import android.util.Log;

/**
 * ADM-10 (6.2.20): reads the terminal's IMEI through Castles' telephony service ({@code CTOS.CtTelephony},
 * which holds the privileged permission Android 10+ denies to apps). Never throws and never blocks the UI
 * thread by contract: callers run it on a worker thread (the Admin Network card is assembled on one).
 * The first successful read is cached for the life of the process; a unit without a modem, or a service
 * that is not there, reads as null and the card says "not available".
 */
public final class TerminalImei {

    private static final String TAG = "TerminalImei";
    private static volatile String cached;

    private TerminalImei() {}

    /** The raw IMEI as the service reports it (14 or 15 digits), or null. */
    public static String read(Context ctx) {
        String c = cached;
        if (c != null) return c;
        try {
            CTOS.CtTelephony t = new CTOS.CtTelephony(ctx.getApplicationContext());
            String imei = blankToNull(t.getImei());
            if (imei == null) imei = blankToNull(t.getImei(0));
            if (imei != null) {
                cached = imei;
                Log.d(TAG, "IMEI read: " + Imei.masked(Imei.display(imei)));
            } else {
                Log.w(TAG, "IMEI: the telephony service returned nothing");
            }
            return imei;
        } catch (Throwable t) {
            Log.w(TAG, "IMEI not available: " + t);
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
