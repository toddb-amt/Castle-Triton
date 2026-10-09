package castech.emvtxn;

import java.util.Map;
import java.util.TimeZone;

/**
 * The {@code time_zone} CasHUB parameter (TZ-01, 6.2.13), parsed out of the merged parameter map
 * that {@code CasHubParams} builds. Pure Java so the rules are unit-tested.
 *
 * <p>A terminal out of the box sits on GMT: Android's zone is a stored setting that nothing on the
 * device ever writes (automatic zone is off, and would need a cellular time signal or a location
 * fix anyway). On GMT an evening sale is dated tomorrow on the receipt, in the Detail Report's
 * batches and in the portal push's BusinessDate. The parameter sets the REAL system zone through
 * Castle's system service ({@link TimeZoneApplier}); every clock in the app follows.
 *
 * <p>Value: an IANA zone name such as {@code America/New_York}; whitespace and letter case are
 * forgiven and the canonical name is used. Unknown or blank → a problem, nothing changes. Absent →
 * the terminal is left as it is.
 */
public final class TimeZoneParam {

    public static final String KEY = "time_zone";

    /** The canonical zone id, or null when absent or unusable. */
    public final String zoneId;
    /** Why a present value was ignored, or null. */
    public final String problem;
    private final boolean present;

    private TimeZoneParam(String zoneId, String problem, boolean present) {
        this.zoneId = zoneId;
        this.problem = problem;
        this.present = present;
    }

    public static TimeZoneParam parse(Map<String, String> params) {
        if (params == null || !params.containsKey(KEY)) return new TimeZoneParam(null, null, false);
        String raw = params.get(KEY) == null ? "" : params.get(KEY).trim();
        if (raw.isEmpty()) return new TimeZoneParam(null, KEY + ": blank — ignored", true);
        String canonical = canonicalise(raw);
        if (canonical == null) {
            return new TimeZoneParam(null, KEY + ": unknown zone \"" + raw + "\" — ignored (use an IANA name such as America/New_York)", true);
        }
        return new TimeZoneParam(canonical, null, true);
    }

    /** The exact id from the platform's list, matched ignoring case; null when not a known zone. */
    private static String canonicalise(String raw) {
        for (String id : TimeZone.getAvailableIDs()) {
            if (id.equalsIgnoreCase(raw)) return id;
        }
        return null;
    }

    /** True when the key appeared in the map at all. */
    public boolean isPresent() { return present; }

    /**
     * The zone to set, or null when nothing should be done: absent, unusable, or already the
     * terminal's zone (so repeated parameter pushes are no-ops).
     */
    public String changeFrom(String currentZoneId) {
        if (zoneId == null) return null;
        return zoneId.equals(currentZoneId) ? null : zoneId;
    }

    public String describe() {
        return KEY + "=" + (zoneId != null ? zoneId : "[" + problem + "]");
    }
}
