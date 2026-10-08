package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * TZ-01 (6.2.13): the {@code time_zone} CasHUB parameter. A terminal out of the box sits on
 * GMT and nothing on the device ever changes that, so an evening sale is dated tomorrow on the
 * receipt, in the Detail Report and in the portal push. The parameter sets the real system zone
 * through Castle's system service; these tests pin the parsing and the "only when different" rule.
 */
public class TimeZoneParamTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void absent_isNotPresent_andNeverChangesAnything() {
        TimeZoneParam p = TimeZoneParam.parse(map("host_address", "1.2.3.4"));
        assertFalse(p.isPresent());
        assertNull(p.zoneId);
        assertNull(p.problem);
        assertNull(p.changeFrom("GMT"));
    }

    @Test
    public void aKnownZone_isAccepted() {
        TimeZoneParam p = TimeZoneParam.parse(map("time_zone", "America/New_York"));
        assertTrue(p.isPresent());
        assertEquals("America/New_York", p.zoneId);
        assertNull(p.problem);
    }

    @Test
    public void whitespaceAndCase_areForgiven_andCanonicalised() {
        assertEquals("America/Chicago", TimeZoneParam.parse(map("time_zone", "  america/chicago ")).zoneId);
        assertEquals("America/Los_Angeles", TimeZoneParam.parse(map("time_zone", "AMERICA/LOS_ANGELES")).zoneId);
    }

    @Test
    public void anUnknownZone_isAProblem_andSetsNothing() {
        TimeZoneParam p = TimeZoneParam.parse(map("time_zone", "America/Springfield"));
        assertTrue(p.isPresent());
        assertNull(p.zoneId);
        assertTrue(p.problem, p.problem.contains("America/Springfield"));
        assertNull(p.changeFrom("GMT"));
    }

    @Test
    public void blank_isAProblem_andSetsNothing() {
        TimeZoneParam p = TimeZoneParam.parse(map("time_zone", "   "));
        assertTrue(p.isPresent());
        assertNull(p.zoneId);
        assertTrue(p.problem, p.problem.contains("blank"));
    }

    @Test
    public void changeFrom_returnsTheZoneOnlyWhenItDiffers() {
        TimeZoneParam p = TimeZoneParam.parse(map("time_zone", "America/New_York"));
        assertEquals("America/New_York", p.changeFrom("GMT"));
        assertNull(p.changeFrom("America/New_York"));   // repeated parameter pushes are no-ops
    }

    @Test
    public void describe_namesTheZone() {
        assertEquals("time_zone=America/Denver", TimeZoneParam.parse(map("time_zone", "America/Denver")).describe());
        assertTrue(TimeZoneParam.parse(map("time_zone", "Mars/Olympus")).describe().contains("ignored"));
    }
}
