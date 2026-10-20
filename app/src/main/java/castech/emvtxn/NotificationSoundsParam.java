package castech.emvtxn;

import java.util.Locale;
import java.util.Map;

/**
 * The {@code notification_sounds} CasHUB parameter (SND-01, 6.2.19), parsed out of the merged
 * parameter map that {@code CasHubParams} builds. Pure Java so the rules are unit-tested.
 *
 * <p>Castles' CasHUB agent posts its service notifications ("Mqtt Service", "Cashub Service") on
 * normal-priority notification channels, so Android plays the default notification chime whenever
 * the agent restarts them, which it does after every update (customer complaint 2026-10-20). A
 * normal app cannot touch another app's channels, so the app silences the terminal's notification
 * volume instead ({@link NotificationSoundsApplier}). The app's own tones play on the media stream
 * and are not affected.
 *
 * <p>Value: {@code false}/{@code 0} silences the terminal, {@code true}/{@code 1} leaves its
 * notification sounds on. Absent, blank or anything else → silenced, the fleet default. A terminal
 * already at the wanted level is left alone, so repeated pushes are no-ops; turning sounds back on
 * lifts a silenced terminal to the factory level (5 of 7 on the S1F4 PRO).
 */
public final class NotificationSoundsParam {

    public static final String KEY = "notification_sounds";
    /** {@link #changeFrom} result: the volume is already where it should be. */
    public static final int NO_CHANGE = -1;

    /** True when notification sounds are to stay on. */
    public final boolean allowed;
    /** Why a present value was not understood (the default applies), or null. */
    public final String problem;
    private final boolean present;

    private NotificationSoundsParam(boolean allowed, String problem, boolean present) {
        this.allowed = allowed;
        this.problem = problem;
        this.present = present;
    }

    public static NotificationSoundsParam parse(Map<String, String> params) {
        if (params == null || !params.containsKey(KEY)) return new NotificationSoundsParam(false, null, false);
        String raw = params.get(KEY) == null ? "" : params.get(KEY).trim();
        if (raw.isEmpty()) return new NotificationSoundsParam(false, KEY + ": blank — silenced (the default)", true);
        String t = raw.toLowerCase(Locale.US);
        if (t.equals("true") || t.equals("1")) return new NotificationSoundsParam(true, null, true);
        if (t.equals("false") || t.equals("0")) return new NotificationSoundsParam(false, null, true);
        return new NotificationSoundsParam(false, KEY + ": \"" + raw + "\" is not true/false — silenced (the default)", true);
    }

    /** True when the key appeared in the map at all. */
    public boolean isPresent() { return present; }

    /**
     * The index to set on the notification stream, or {@link #NO_CHANGE}: silenced wants zero,
     * sounds-on wants the factory level but only lifts a terminal that is at zero.
     */
    public int changeFrom(int currentIndex, int maxIndex) {
        if (!allowed) return currentIndex > 0 ? 0 : NO_CHANGE;
        return currentIndex == 0 ? factoryLevel(maxIndex) : NO_CHANGE;
    }

    /** 5 of 7 scaled to the stream's range, never below 1: where the S1F4 PRO ships. */
    static int factoryLevel(int maxIndex) {
        return Math.max(1, (maxIndex * 5 + 3) / 7);
    }

    public String describe() {
        if (problem != null) return KEY + "=[" + problem + "]";
        if (!present) return KEY + "=false (default: silenced)";
        return KEY + "=" + allowed;
    }
}
