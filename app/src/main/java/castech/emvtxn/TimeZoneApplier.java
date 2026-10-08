package castech.emvtxn;

import android.util.Log;

import java.util.Date;
import java.util.TimeZone;

/**
 * Applies a CasHUB-pushed {@code time_zone} through Castle's system service
 * ({@code CTOS.CtSystem.setTimeZone}), which runs with system rights — a normal app cannot change
 * the zone on Android itself. TZ-01, 6.2.13.
 *
 * <p>Only called when the requested zone differs from the terminal's ({@link TimeZoneParam#changeFrom}).
 * The clock is never touched. The outcome is kept for the Admin network card and the log; the
 * live zone shown there is the truth, this is only the story of the last attempt.
 */
public final class TimeZoneApplier {

    private static final String TAG = "TimeZoneApplier";

    private TimeZoneApplier() {}

    /** Last attempt, for the Admin card: "" when none this process. */
    private static volatile String lastResult = "";

    public static String lastResult() { return lastResult; }

    /** Current zone as the Admin card shows it: id plus its short name right now. */
    public static String currentZoneSummary() {
        TimeZone tz = TimeZone.getDefault();
        return tz.getID() + " (" + tz.getDisplayName(tz.inDaylightTime(new Date()), TimeZone.SHORT) + ")";
    }

    public static void applyIfChangedAsync(final TimeZoneParam p) {
        if (p == null) return;
        final String want = p.changeFrom(TimeZone.getDefault().getID());
        if (want == null) return;
        Thread t = new Thread(() -> applyIfChanged(want), "TimeZoneApply");
        t.setDaemon(true);
        t.start();
    }

    /** Blocking; call off the main thread. */
    static void applyIfChanged(String want) {
        String was = TimeZone.getDefault().getID();
        try {
            CTOS.CtSystem sys = new CTOS.CtSystem();
            if (!sys.isReady()) sys.init();
            int rc = sys.setTimeZone(want);
            if (rc == 0) {
                lastResult = "applied " + want;
                Log.w(TAG, "Applied CasHUB time_zone=" + want + " (was " + was + ")");
            } else {
                lastResult = "failed: rc=" + rc + " for " + want;
                Log.e(TAG, "CasHUB time_zone=" + want + " not applied: CtSystem.setTimeZone rc=" + rc);
            }
        } catch (Throwable t) {
            lastResult = "failed: " + t.getClass().getSimpleName() + " for " + want;
            Log.e(TAG, "CasHUB time_zone=" + want + " not applied: " + t);
        }
    }
}
