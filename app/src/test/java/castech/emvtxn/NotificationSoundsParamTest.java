package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * SND-01 (6.2.19): the {@code notification_sounds} CasHUB parameter. Castles' CasHUB agent posts its
 * service notifications on normal-priority channels, so Android plays the default notification chime
 * every time the agent restarts them, which it does after every update (customer complaint
 * 2026-10-20). The app silences the terminal's notification volume on every start and parameter
 * apply; the parameter turns the sounds back on fleet-wide without a release. These tests pin the
 * parsing, the default, and the "only when it differs" volume rule.
 */
public class NotificationSoundsParamTest {

    /** The S1F4 PRO's notification volume scale: 0..7, shipped at 5. */
    private static final int MAX = 7;

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void absent_isNotPresent_andMeansSilenced() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map("host_address", "1.2.3.4"));
        assertFalse(p.isPresent());
        assertFalse(p.allowed);
        assertNull(p.problem);
    }

    @Test
    public void silenced_dropsAnAudibleVolumeToZero() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map());
        assertEquals(0, p.changeFrom(5, MAX));
        assertEquals(0, p.changeFrom(1, MAX));
    }

    @Test
    public void silenced_leavesAnAlreadySilentTerminalAlone() {
        assertEquals(NotificationSoundsParam.NO_CHANGE, NotificationSoundsParam.parse(map()).changeFrom(0, MAX));
    }

    @Test
    public void falseAndZero_meanSilenced() {
        for (String v : new String[] {"false", "0", " FALSE "}) {
            NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, v));
            assertTrue(v, p.isPresent());
            assertFalse(v, p.allowed);
            assertNull(v, p.problem);
        }
    }

    @Test
    public void trueAndOne_allowSounds() {
        for (String v : new String[] {"true", "1", " True "}) {
            NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, v));
            assertTrue(v, p.isPresent());
            assertTrue(v, p.allowed);
            assertNull(v, p.problem);
        }
    }

    @Test
    public void allowed_liftsASilencedTerminalToTheFactoryLevel() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "true"));
        assertEquals(5, p.changeFrom(0, 7));      // where the S1F4 PRO ships
        assertEquals(11, p.changeFrom(0, 15));    // the same point on a wider scale
        assertEquals(1, p.changeFrom(0, 1));      // never zero
    }

    @Test
    public void allowed_leavesAnAudibleTerminalAlone() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "true"));
        assertEquals(NotificationSoundsParam.NO_CHANGE, p.changeFrom(3, MAX));
        assertEquals(NotificationSoundsParam.NO_CHANGE, p.changeFrom(MAX, MAX));
    }

    @Test
    public void aNonBoolean_isAProblem_andFallsBackToSilenced() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "yes"));
        assertTrue(p.isPresent());
        assertFalse(p.allowed);
        assertTrue(p.problem, p.problem.contains("yes"));
        assertTrue(p.problem, p.problem.contains(NotificationSoundsParam.KEY));
        assertEquals(0, p.changeFrom(5, MAX));
    }

    @Test
    public void blank_isAProblem_andFallsBackToSilenced() {
        NotificationSoundsParam p = NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "  "));
        assertTrue(p.isPresent());
        assertFalse(p.allowed);
        assertTrue(p.problem, p.problem.contains("blank"));
    }

    @Test
    public void describe_saysWhatTheTerminalWillDo() {
        assertEquals("notification_sounds=false (default: silenced)", NotificationSoundsParam.parse(map()).describe());
        assertEquals("notification_sounds=true", NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "1")).describe());
        assertEquals("notification_sounds=false", NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "0")).describe());
        assertTrue(NotificationSoundsParam.parse(map(NotificationSoundsParam.KEY, "yes")).describe().startsWith("notification_sounds=["));
    }
}
