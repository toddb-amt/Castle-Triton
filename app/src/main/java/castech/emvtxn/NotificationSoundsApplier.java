package castech.emvtxn;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * Sets the terminal's notification volume for SND-01 (6.2.19): zero unless the
 * {@code notification_sounds} parameter says otherwise ({@link NotificationSoundsParam}). Runs on
 * every CasHUB parameter apply, which includes app start, so a terminal is silenced the first time
 * this build runs and stays so across reboots: the volume is a system setting, not ours.
 *
 * <p>On this hardware the notification stream is the ring stream, so Android drops the ringer to
 * vibrate when it reaches zero and would turn a notification's chime into a short vibration if the
 * terminal can vibrate at all (its vibrator reports no effects; unverified). The app's own tones are
 * on the media stream and keep playing. Android refuses the change only when it would toggle Do Not
 * Disturb (a terminal reporting no vibrator); that is logged and shown on the Admin network card,
 * never thrown.
 */
public final class NotificationSoundsApplier {

    private static final String TAG = "NotificationSounds";

    private NotificationSoundsApplier() {}

    /** Last attempt, for the Admin card: "" when none this process. */
    private static volatile String lastResult = "";

    public static String lastResult() { return lastResult; }

    /** For the Admin card: {@code silenced (0/7)} or {@code on (5/7)}. */
    public static String currentSummary(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "unknown";
            int cur = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            return (cur == 0 ? "silenced" : "on") + " (" + cur + "/" + max + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Applies the parameter's wanted level; a no-op when the terminal is already there. Never throws. */
    public static void apply(Context ctx, NotificationSoundsParam p) {
        if (ctx == null || p == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                lastResult = "failed: no audio service";
                Log.e(TAG, "SND-01: no audio service; notification volume not changed");
                return;
            }
            int cur = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            int want = p.changeFrom(cur, max);
            if (want == NotificationSoundsParam.NO_CHANGE) {
                Log.w(TAG, "Applied CasHUB " + p.describe() + ": notification volume " + cur + "/" + max + " (unchanged)");
                return;
            }
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, want, 0);
            int now = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            lastResult = now == want ? "applied " + now + "/" + max : "failed: volume is " + now + "/" + max + " after asking for " + want;
            Log.w(TAG, "SND-01: notification volume " + cur + " → " + now + " of " + max + " (" + p.describe() + ")");
        } catch (SecurityException e) {
            // Android N+: a change that would toggle Do Not Disturb needs notification-policy access
            lastResult = "failed: " + e.getMessage();
            Log.e(TAG, "SND-01: notification volume not changed: " + e.getMessage());
        } catch (Throwable t) {
            lastResult = "failed: " + t.getClass().getSimpleName();
            Log.e(TAG, "SND-01: notification volume not changed: " + t);
        }
    }
}
