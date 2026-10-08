package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;

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

    @Test(expected = IllegalArgumentException.class)
    public void aZeroSale_isRefused() {
        RegisterAmounts.of(0, 20_00, true, 3.50, 0.0);
    }
}
