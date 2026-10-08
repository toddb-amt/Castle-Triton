package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * AMT-03 (6.2.13). One object holds the six numbers of a transaction so the chip, the host,
 * the receipt, the journal and the portal push can never disagree.
 *   withdrawal = roundUp(sale + tip, step)   (or sale + tip when rounding is off)
 *   cashBack   = withdrawal - sale - tip
 *   total      = withdrawal + fee
 */
public class AmountBreakdownTest {

    private static final long STEP = 10_00;          // $10 minimum / step
    private static final boolean FLAT = true;
    private static final double FLAT_FEE = 3.50;

    @Test
    public void agreedExample_tenDollarSaleOneDollarTip() {
        // $10 sale, $1 tip -> $20 withdrawal, $9 cash back, $3.50 fee, $23.50 on the card
        AmountBreakdown b = AmountBreakdown.of(10_00, 1_00, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(10_00, b.sale);
        assertEquals(1_00, b.tip);
        assertEquals(20_00, b.withdrawal);
        assertEquals(9_00, b.cashBack);
        assertEquals(3_50, b.fee);
        assertEquals(23_50, b.total);
    }

    @Test
    public void portalContractExample_219_25_plus_2_00_tip() {
        // The Ingenico push in the contract: 219.25 + 2.00 -> 230.00, cash back 8.75, total 233.50
        AmountBreakdown b = AmountBreakdown.of(219_25, 2_00, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(230_00, b.withdrawal);
        assertEquals(8_75, b.cashBack);
        assertEquals(233_50, b.total);
    }

    @Test
    public void walkUpWithoutTip_matches_6_2_12_arithmetic() {
        // Today's custom amount $12.50 -> $20.00 withdrawal, fee $3.50, total $23.50; cash back is the change
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(20_00, b.withdrawal);
        assertEquals(7_50, b.cashBack);
        assertEquals(23_50, b.total);
        assertEquals("2350", b.chipAmountCents());
    }

    @Test
    public void presetAmount_isNotChangedByRounding() {
        AmountBreakdown b = AmountBreakdown.of(40_00, 0, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(40_00, b.withdrawal);
        assertEquals(0, b.cashBack);
    }

    @Test
    public void registerSaleWithRoundingOff_isExact_asIn_6_2_12() {
        // 6.2.13 keeps register sales exact: $12.50 stays $12.50, no cash back
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, false, FLAT, FLAT_FEE, 0);
        assertEquals(12_50, b.withdrawal);
        assertEquals(0, b.cashBack);
        assertEquals(16_00, b.total);
    }

    @Test
    public void percentageFee_isComputedOnTheWithdrawal() {
        // 2.5% of a $20 withdrawal = $0.50 (fee is on what the host is asked for)
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, true, false, 0, 2.5);
        assertEquals(50, b.fee);
        assertEquals(20_50, b.total);
    }

    @Test
    public void balanceInquiry_isAllZero() {
        AmountBreakdown b = AmountBreakdown.balanceInquiry();
        assertEquals(0, b.sale + b.tip + b.withdrawal + b.cashBack + b.fee + b.total);
        assertEquals("0", b.chipAmountCents());
    }

    @Test
    public void invariantsHold_acrossOddInputs() {
        long[][] cases = { {1, 0}, {9_99, 0}, {10_01, 0}, {12_33, 1_85}, {499_99, 0}, {500_00, 0} };
        for (long[] c : cases) {
            AmountBreakdown b = AmountBreakdown.of(c[0], c[1], STEP, true, FLAT, FLAT_FEE, 0);
            assertEquals("sale+tip+cashBack+fee == total for " + c[0], b.total, b.sale + b.tip + b.cashBack + b.fee);
            assertEquals("withdrawal+fee == total for " + c[0], b.total, b.withdrawal + b.fee);
            assertTrue("cash back never negative for " + c[0], b.cashBack >= 0);
            assertTrue("withdrawal >= sale+tip for " + c[0], b.withdrawal >= b.sale + b.tip);
        }
    }

    @Test
    public void negativeInputsAreRejected() {
        // Review focus 3: the portal rejects negative amounts; refuse to build them at all
        long[][] bad = { {-1, 0}, {10_00, -1}, {0, 5_00} };
        for (long[] c : bad) {
            try {
                AmountBreakdown.of(c[0], c[1], STEP, true, FLAT, FLAT_FEE, 0);
                fail("expected rejection for sale=" + c[0] + " tip=" + c[1]);
            } catch (IllegalArgumentException expected) { /* ok */ }
        }
    }

    @Test
    public void presetWithANonDivisorStep_isExactWhenNotRounded() {
        // Review C1: the $60 preset with min_amount $25 is offered (range check only) and must charge $60.
        // The amount screen passes roundToStep=false for presets; only custom entries round.
        AmountBreakdown b = AmountBreakdown.of(60_00, 0, 25_00, false, FLAT, FLAT_FEE, 0);
        assertEquals(60_00, b.withdrawal);
        assertEquals(0, b.cashBack);
        assertEquals(63_50, b.total);
    }

    @Test
    public void roundingANonDivisorPreset_wouldChangeTheCharge() {
        // what C1 did: this is why presets must never be built with roundToStep=true
        AmountBreakdown b = AmountBreakdown.of(60_00, 0, 25_00, true, FLAT, FLAT_FEE, 0);
        assertEquals(75_00, b.withdrawal);
        assertEquals(15_00, b.cashBack);
    }

    @Test
    public void zeroStep_meansNoRounding() {
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, 0, true, FLAT, FLAT_FEE, 0);
        assertEquals(12_50, b.withdrawal);
    }
}
