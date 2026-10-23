package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TIP-02 (6.2.20): the quote builds its three options from the configured presets. Flat fee $3.50. */
public class TipQuotePresetsTest {

    private static final long STEP = 10_00, MAX = 500_00;

    private static AmountBreakdown sale(long cents) {
        return AmountBreakdown.of(cents, 0L, STEP, true, true, 3.50, 0.0);
    }

    @Test
    public void dollarPresets_areFixedTips_withDollarLabels() {
        TipQuote q = TipQuote.of(sale(53_50), TipPresets.parse("$1,$5,$10"), STEP, MAX, true, 3.50, 0.0);
        TipQuote.Option one = q.options.get(0);
        assertEquals("$1", one.label);
        assertEquals(0, one.percent);
        assertEquals(1_00, one.tipCents);
        assertEquals(60_00, one.amounts.withdrawal);        // 54.50 → 60
        assertEquals(5_50, one.amounts.cashBack);
        assertTrue(one.enabled);
        TipQuote.Option ten = q.options.get(2);
        assertEquals("$10", ten.label);
        assertEquals(10_00, ten.tipCents);
        assertEquals(70_00, ten.amounts.withdrawal);        // 63.50 → 70
    }

    @Test
    public void customPercentages_labelAndComputeFromTheSale() {
        TipQuote q = TipQuote.of(sale(53_50), TipPresets.parse("5,10,20"), STEP, MAX, true, 3.50, 0.0);
        assertEquals("5%", q.options.get(0).label);
        assertEquals(5, q.options.get(0).percent);
        assertEquals(2_68, q.options.get(0).tipCents);      // 267.5 → 268
        assertEquals("20%", q.options.get(2).label);
        assertEquals(10_70, q.options.get(2).tipCents);
    }

    @Test
    public void aDollarPreset_thatPushesTheWithdrawalOverTheMaximum_isDisabled() {
        TipQuote q = TipQuote.of(sale(495_00), TipPresets.parse("$1,$5,$10"), STEP, MAX, true, 3.50, 0.0);
        assertTrue(q.options.get(0).enabled);               // 496 → 500
        assertTrue(q.options.get(1).enabled);               // 500 → 500
        assertFalse(q.options.get(2).enabled);              // 505 → 510 > 500
        assertEquals("Over $500 limit", q.options.get(2).reasonIfDisabled);
        assertFalse(q.skipScreen);
    }

    @Test
    public void theOldOverload_stillMeansTheDefaultPresets() {
        TipQuote q = TipQuote.of(sale(10_00), STEP, MAX, true, 3.50, 0.0);
        assertEquals("10%", q.options.get(0).label);
        assertEquals("15%", q.options.get(1).label);
        assertEquals("20%", q.options.get(2).label);
        assertEquals(10, q.options.get(0).percent);
    }

    @Test
    public void offer_usesTheConfiguredPresets() {
        // $1 on a $480 sale with a $20 step still reaches $500: the screen is offered
        assertTrue(TipQuote.offer(true, false, AmountBreakdown.of(480_00, 0L, 20_00, true, true, 3.50, 0.0),
                TipPresets.parse("$1,$5,$10"), 20_00, MAX, true, 3.50, 0.0));
        // at the maximum nothing can be chosen whatever the presets
        assertFalse(TipQuote.offer(true, false, sale(500_00), TipPresets.parse("$1,$5,$10"), STEP, MAX, true, 3.50, 0.0));
    }
}
