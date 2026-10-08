package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import castech.emvtxn.AmountBreakdown;

/** T5: from 6.2.14 a register sale rounds to the step like a walk-up custom amount. */
public class RegisterAmountsTest {

    @Test
    public void aRegisterSaleBelowTheStep_roundsUp_andCarriesTheDifferenceAsCashBack() {
        AmountBreakdown a = RegisterAmounts.of(12_50, 20_00, true, 3.50, 0.0);
        assertEquals(12_50, a.sale);
        assertEquals(0, a.tip);
        assertEquals(20_00, a.withdrawal);
        assertEquals(7_50, a.cashBack);
        assertEquals(3_50, a.fee);
        assertEquals(23_50, a.total);
    }

    @Test
    public void aRegisterSaleOnTheStep_isUnchanged() {
        AmountBreakdown a = RegisterAmounts.of(40_00, 20_00, true, 3.50, 0.0);
        assertEquals(40_00, a.withdrawal);
        assertEquals(0, a.cashBack);
        assertEquals(43_50, a.total);
    }

    @Test
    public void aRegisterSaleBelowTheMinimum_roundsUpToTheMinimum() {
        // spec section 7: $4.00 with a $10 step → $10.00, cash back $6.00
        AmountBreakdown a = RegisterAmounts.of(4_00, 10_00, true, 3.50, 0.0);
        assertEquals(10_00, a.withdrawal);
        assertEquals(6_00, a.cashBack);
    }

    @Test
    public void roundingThatCrossesTheMaximum_isReportedNotCharged() {
        // Review 6.2.14 Important #2: min $30, max $500, register sale $490 → rounds to $510, above the cap.
        // T6: the maximum applies to the withdrawal. The register gets an error, the customer is not charged.
        AmountBreakdown a = RegisterAmounts.of(490_00, 30_00, true, 3.50, 0.0);
        assertEquals(510_00, a.withdrawal);
        String why = RegisterAmounts.overMaximum(a, 500_00, 30_00);
        assertTrue(why, why != null && why.contains("$510.00") && why.contains("$500.00") && why.contains("$30.00"));
    }

    @Test
    public void aSaleWithinTheMaximum_hasNoObjection() {
        assertNull(RegisterAmounts.overMaximum(RegisterAmounts.of(480_00, 30_00, true, 3.50, 0.0), 500_00, 30_00));
        assertNull(RegisterAmounts.overMaximum(RegisterAmounts.of(500_00, 20_00, true, 3.50, 0.0), 500_00, 20_00));   // exactly at the cap
    }

    @Test
    public void aRegisterAmountAlreadyAboveTheMaximum_isAlsoReported() {
        // The register path never enforced max_amount; T6 now covers it on the rounded withdrawal.
        String why = RegisterAmounts.overMaximum(RegisterAmounts.of(600_00, 20_00, true, 3.50, 0.0), 500_00, 20_00);
        assertTrue(why, why != null && why.contains("$600.00"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aZeroSale_isRefused() {
        RegisterAmounts.of(0, 20_00, true, 3.50, 0.0);
    }
}
