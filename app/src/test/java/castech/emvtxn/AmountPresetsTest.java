package castech.emvtxn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The preset withdrawal buttons (6.2.8): $10, $20, $40, $60, $100, $200 — ascending, no
 * $500. A preset outside the configured limits is not offered (greyed) instead of
 * producing an "Amount too low/high" dialog when tapped.
 */
public class AmountPresetsTest {

    @Test
    public void presets_areTenToTwoHundredAscending() {
        assertArrayEquals(new long[] {10_00L, 20_00L, 40_00L, 60_00L, 100_00L, 200_00L},
                AmountPresets.PRESET_CENTS);
        for (int i = 1; i < AmountPresets.PRESET_CENTS.length; i++) {
            assertTrue("ascending", AmountPresets.PRESET_CENTS[i] > AmountPresets.PRESET_CENTS[i - 1]);
        }
    }

    @Test
    public void presetBelowTheMinimum_isNotOffered() {
        assertFalse(AmountPresets.isOffered(10_00L, 20_00L, 500_00L));
        assertTrue(AmountPresets.isOffered(20_00L, 20_00L, 500_00L));
    }

    @Test
    public void presetAboveTheMaximum_isNotOffered() {
        assertFalse(AmountPresets.isOffered(200_00L, 10_00L, 100_00L));
        assertTrue(AmountPresets.isOffered(100_00L, 10_00L, 100_00L));
    }

    @Test
    public void presetWithinTheLimits_isOffered() {
        assertTrue(AmountPresets.isOffered(10_00L, 10_00L, 500_00L));
        assertTrue(AmountPresets.isOffered(200_00L, 10_00L, 500_00L));
    }

    @Test
    public void nonPositiveLimits_doNotHideAnything() {
        assertTrue(AmountPresets.isOffered(10_00L, 0L, 0L));
        assertTrue(AmountPresets.isOffered(200_00L, -1L, -1L));
    }
}
