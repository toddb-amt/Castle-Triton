package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** T3/T4/T6/T7 of the tips spec, as arithmetic. Flat fee $3.50 unless a test says otherwise. */
public class TipQuoteTest {

    private static final long STEP = 20_00, MAX = 500_00;

    private static AmountBreakdown noTip(long saleCents, boolean roundToStep) {
        return AmountBreakdown.of(saleCents, 0L, STEP, roundToStep, true, 3.50, 0.0);
    }
    private static TipQuote quote(long saleCents) {
        return TipQuote.of(noTip(saleCents, true), STEP, MAX, true, 3.50, 0.0);
    }

    @Test
    public void theAgreedExample_tenDollarSale_tenPercent() {
        // sale $10.00, 10% tip $1.00, withdrawal $20.00, cash back $9.00, fee $3.50, card $23.50
        TipQuote.Option ten = quote(10_00).options.get(0);
        assertEquals(10, ten.percent);
        assertEquals(1_00, ten.tipCents);
        assertEquals(10_00, ten.amounts.sale);
        assertEquals(1_00, ten.amounts.tip);
        assertEquals(20_00, ten.amounts.withdrawal);
        assertEquals(9_00, ten.amounts.cashBack);
        assertEquals(3_50, ten.amounts.fee);
        assertEquals(23_50, ten.amounts.total);
        assertTrue(ten.enabled);
    }

    @Test
    public void percentages_areHalfUpAtTheCent() {
        assertEquals(1_23, TipQuote.percentOf(12_33, 10));   // 123.3  → 123
        assertEquals(1_85, TipQuote.percentOf(12_33, 15));   // 184.95 → 185
        assertEquals(2_47, TipQuote.percentOf(12_33, 20));   // 246.6  → 247
        assertEquals(0,    TipQuote.percentOf(4, 10));       // 0.4 → 0
        assertEquals(1,    TipQuote.percentOf(5, 10));       // 0.5 → 1
    }

    @Test
    public void threeOptions_inOrder_withTheNoTipBreakdownKeptAsGiven() {
        AmountBreakdown given = noTip(10_00, true);
        TipQuote q = TipQuote.of(given, STEP, MAX, true, 3.50, 0.0);
        assertEquals(3, q.options.size());
        assertEquals(10, q.options.get(0).percent);
        assertEquals(15, q.options.get(1).percent);
        assertEquals(20, q.options.get(2).percent);
        assertEquals(given, q.noTip);                          // No Tip = exactly what the caller built
        assertEquals(500_00, q.maxWithdrawalCents);
    }

    @Test
    public void aPresetWithNoTip_staysExact_butATipRounds() {
        // 6.2.13 review C1: a preset is never rounded on its own; a tip changes the amount, so it rounds
        AmountBreakdown preset = AmountBreakdown.of(60_00, 0L, 25_00, false, true, 3.50, 0.0);
        TipQuote q = TipQuote.of(preset, 25_00, MAX, true, 3.50, 0.0);
        assertEquals(60_00, q.noTip.withdrawal);
        assertEquals(75_00, q.options.get(0).amounts.withdrawal);   // 60 + 6 = 66 → 75
        assertEquals(9_00, q.options.get(0).amounts.cashBack);
    }

    @Test
    public void optionsOverTheLimit_areDisabled_withTheLimitNamed() {
        TipQuote q = quote(480_00);                            // 10% = $48 → $528 → $540 > $500
        for (TipQuote.Option o : q.options) {
            assertFalse(o.enabled);
            assertEquals("Over $500.00 limit", o.reasonIfDisabled);
        }
        assertEquals(20_00, q.customLimitCents);               // 480 + 20 = 500 exactly
        assertFalse(q.skipScreen);                             // a custom tip is still possible
    }

    @Test
    public void saleAtTheMaximum_skipsTheScreen() {
        TipQuote q = quote(500_00);
        assertTrue(q.skipScreen);
        assertEquals(0, q.customLimitCents);
        for (TipQuote.Option o : q.options) assertFalse(o.enabled);
    }

    @Test
    public void customLimit_isTheLargestTipThatKeepsTheWithdrawalWithinTheMaximum() {
        assertEquals(490_00, quote(10_00).customLimitCents);       // 10 + 490 = 500
        assertEquals(1_00, quote(499_00).customLimitCents);        // 499 + 1 = 500
        // max not a multiple of the step: the largest reachable withdrawal is $480
        TipQuote odd = TipQuote.of(noTip(10_00, true), STEP, 490_00, true, 3.50, 0.0);
        assertEquals(470_00, odd.customLimitCents);
    }

    @Test
    public void customTip_atTheLimitIsAllowed_oneCentOverIsRefused() {
        TipQuote q = quote(480_00);
        TipQuote.Custom ok = q.custom(20_00);
        assertNull(ok.failure);
        assertEquals(500_00, ok.amounts.withdrawal);
        TipQuote.Custom over = q.custom(20_01);
        assertEquals(TipQuote.OVER_LIMIT, over.failure);
        assertEquals(20_00, over.maxTipCents);
        assertNull(over.amounts);
    }

    @Test
    public void customTip_belowOneCent_isTooSmall() {
        assertEquals(TipQuote.TOO_SMALL, quote(10_00).custom(0).failure);
        assertEquals(TipQuote.TOO_SMALL, quote(10_00).custom(-5).failure);
    }

    @Test
    public void customTip_largerThanTheSale_isFlaggedForConfirmation() {
        TipQuote.Custom big = quote(10_00).custom(25_00);
        assertNull(big.failure);
        assertTrue(big.exceedsSale);
        assertEquals(40_00, big.amounts.withdrawal);          // 10 + 25 = 35 → 40
        assertFalse(quote(10_00).custom(10_00).exceedsSale);  // equal is not "larger"
    }

    @Test
    public void percentageFee_isRecomputedOnTheRoundedWithdrawal() {
        // Review Focus 1: fee 10% of the withdrawal; the tip moves the withdrawal up a step
        AmountBreakdown base = AmountBreakdown.of(19_00, 0L, STEP, true, false, 0.0, 10.0);
        assertEquals(20_00, base.withdrawal);
        assertEquals(2_00, base.fee);
        TipQuote q = TipQuote.of(base, STEP, MAX, false, 0.0, 10.0);
        TipQuote.Option ten = q.options.get(0);                // tip $1.90 → $20.90 → $40.00
        assertEquals(40_00, ten.amounts.withdrawal);
        assertEquals(4_00, ten.amounts.fee);
        assertEquals(44_00, ten.amounts.total);
    }

    @Test
    public void maxBelowStep_skipsTheScreen_andNothingThrows() {
        // Review Focus 3: max $15 with a $20 step → no reachable withdrawal; min 0 → no rounding
        TipQuote q = TipQuote.of(noTip(10_00, true), STEP, 15_00, true, 3.50, 0.0);
        assertTrue(q.skipScreen);
        assertEquals(0, q.customLimitCents);
        TipQuote noStep = TipQuote.of(AmountBreakdown.of(10_00, 0L, 0, true, true, 3.50, 0.0), 0, MAX, true, 3.50, 0.0);
        assertEquals(11_00, noStep.options.get(0).amounts.withdrawal);   // no step: sale + tip exactly
        assertEquals(490_00, noStep.customLimitCents);
    }

    @Test
    public void offer_isTrueOnlyWhenEnabled_notABalanceInquiry_andSomethingCanBeChosen() {
        AmountBreakdown ten = noTip(10_00, true);
        assertTrue(TipQuote.offer(true, false, ten, STEP, MAX, true, 3.50, 0.0));
        assertFalse(TipQuote.offer(false, false, ten, STEP, MAX, true, 3.50, 0.0));          // T1 off
        assertFalse(TipQuote.offer(true, true, AmountBreakdown.balanceInquiry(), STEP, MAX, true, 3.50, 0.0)); // never on BI
        assertFalse(TipQuote.offer(true, false, noTip(500_00, true), STEP, MAX, true, 3.50, 0.0));  // at the max
    }

    @Test
    public void aZeroSale_isNeverOffered_andOfReturnsAnAllDisabledQuote() {
        assertFalse(TipQuote.offer(true, false, AmountBreakdown.balanceInquiry(), STEP, MAX, true, 3.50, 0.0));
        TipQuote q = TipQuote.of(AmountBreakdown.balanceInquiry(), STEP, MAX, true, 3.50, 0.0);
        assertTrue(q.skipScreen);
        assertNotNull(q.options);
    }
}
