package castech.emvtxn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * TIP-02 (6.2.20): the three suggested tips come from the CasHUB key {@code tip_presets}. Bare
 * numbers are percentages ({@code 10,15,20}); a dollar sign makes them amounts ({@code $1,$5,$10}).
 * Exactly three, all of one kind; anything else is refused with a reason and the previous value
 * stands (the parser throws, the config store catches and logs, like the other keys).
 */
public class TipPresetsTest {

    @Test
    public void theDefault_isTenFifteenTwentyPercent() {
        TipPresets d = TipPresets.DEFAULT;
        assertFalse(d.dollars);
        assertArrayEquals(new long[] {10, 15, 20}, d.values);
        assertEquals("10%", d.label(0));
        assertEquals("15%", d.label(1));
        assertEquals("20%", d.label(2));
        assertEquals("10%, 15%, 20%", d.describe());
        assertEquals("10,15,20", d.toParam());
        assertEquals(3, d.size());
    }

    @Test
    public void percentages_parse_withOrWithoutTheSign_andSpaces() {
        TipPresets a = TipPresets.parse("5,10,20");
        TipPresets b = TipPresets.parse(" 5% , 10%,20% ");
        for (TipPresets p : new TipPresets[] {a, b}) {
            assertFalse(p.dollars);
            assertArrayEquals(new long[] {5, 10, 20}, p.values);
        }
        assertEquals("5%, 10%, 20%", a.describe());
        assertEquals("5,10,20", b.toParam());
        TipPresets c = TipPresets.parse("15,18,20");
        assertEquals("15%, 18%, 20%", c.describe());
    }

    @Test
    public void dollarAmounts_parse_withCentsWhenGiven() {
        TipPresets p = TipPresets.parse("$1,$5,$10");
        assertTrue(p.dollars);
        assertArrayEquals(new long[] {1_00, 5_00, 10_00}, p.values);
        assertEquals("$1", p.label(0));
        assertEquals("$5", p.label(1));
        assertEquals("$10", p.label(2));
        assertEquals("$1, $5, $10", p.describe());
        assertEquals("$1,$5,$10", p.toParam());

        TipPresets odd = TipPresets.parse(" $2.50, $5.00 ,$7.25");
        assertArrayEquals(new long[] {2_50, 5_00, 7_25}, odd.values);
        assertEquals("$2.50", odd.label(0));
        assertEquals("$5", odd.label(1));
        assertEquals("$2.50,$5,$7.25", odd.toParam());
    }

    @Test
    public void tipFor_isPercentOfTheSale_orTheFixedAmount() {
        assertEquals(5_35, TipPresets.parse("10,15,20").tipFor(53_50, 0));    // 10% of $53.50, half-up at the cent
        assertEquals(8_03, TipPresets.parse("10,15,20").tipFor(53_50, 1));    // 15% = 802.5 → 803
        TipPresets d = TipPresets.parse("$1,$5,$10");
        assertEquals(5_00, d.tipFor(53_50, 1));
        assertEquals(5_00, d.tipFor(9_99, 1));                                 // the sale does not matter
    }

    @Test
    public void roundTrip_throughTheParamForm() {
        for (String s : new String[] {"10,15,20", "5,10,20", "$1,$5,$10", "$2.50,$5,$7.25"}) {
            assertEquals(s, TipPresets.parse(TipPresets.parse(s).toParam()).toParam());
        }
    }

    @Test
    public void refused_withAReason() {
        String[] bad = {
                "",                 // nothing
                "10,15",            // two
                "10,15,20,25",      // four
                "10,$5,20",         // mixed kinds
                "0,15,20",          // 0%
                "101,15,20",        // over 100%
                "$0,$5,$10",        // $0
                "$1000,$5,$10",     // over the $999.99 cap
                "abc,15,20",        // not a number
                "10;15;20",         // wrong separator
                "1.5,10,20",        // percentages are whole numbers
                "1,5,10$",          // the sign goes in front
                "$1.999,$5,$10",    // tenths of a cent
        };
        for (String s : bad) {
            try {
                TipPresets.parse(s);
                fail("accepted: [" + s + "]");
            } catch (IllegalArgumentException expected) {
                assertTrue(s, expected.getMessage() != null && !expected.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void null_isRefusedToo() {
        try { TipPresets.parse(null); fail("accepted null"); } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
