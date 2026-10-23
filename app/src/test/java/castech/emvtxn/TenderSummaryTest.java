package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;

/**
 * TNDR-01 (6.2.20): the merchant block on the approval screen for an unintegrated store. The
 * clerk types the TENDER into the POS: the original sale plus the change the customer gets back,
 * never the tip, so the POS computes exactly that change and the tip stays with the staff.
 * Both worked examples are the partner's (email of 2026-10-21 and the Ingenico screens).
 */
public class TenderSummaryTest {

    private static AmountBreakdown walkUp(long sale, long tip) {
        return AmountBreakdown.of(sale, tip, 10_00, true, true, 3.50, 0.0);     // $10 step, $3.50 fee
    }

    @Test
    public void theEmailExample_fiftyThreeFiftyWithAFiveDollarTip() {
        TenderSummary s = TenderSummary.of(walkUp(53_50, 5_00));
        assertEquals(53_50, s.sale);
        assertEquals(5_00, s.tip);
        assertEquals(58_50, s.saleAndTip);
        assertEquals(60_00, s.withdrawal);
        assertEquals(3_50, s.fee);
        assertEquals(63_50, s.total);
        assertEquals(55_00, s.tender);          // 53.50 + 1.50 change, the tip excluded
        assertEquals(1_50, s.changeDue);
        assertTrue(s.hasTip());
    }

    @Test
    public void theIngenicoScreen_fortyFourFiftyFiveWithATwoTwentyFiveTip() {
        TenderSummary s = TenderSummary.of(walkUp(44_55, 2_25));
        assertEquals(46_80, s.saleAndTip);
        assertEquals(50_00, s.withdrawal);
        assertEquals(53_50, s.total);
        assertEquals(47_75, s.tender);
        assertEquals(3_20, s.changeDue);
    }

    @Test
    public void noTip_anExactAmountStillHasChange_andTheTenderIsTheWithdrawal() {
        TenderSummary s = TenderSummary.of(walkUp(53_50, 0));
        assertEquals(60_00, s.withdrawal);
        assertEquals(6_50, s.changeDue);
        assertEquals(60_00, s.tender);
        assertFalse(s.hasTip());
    }

    @Test
    public void applies_onlyToAnApprovedWalkUp_thatHasATipOrChange() {
        AmountBreakdown tipped = walkUp(53_50, 5_00);
        assertTrue(TenderSummary.applies(tipped, true, false));
        assertFalse(TenderSummary.applies(tipped, false, false));                 // declined: nothing to tender
        assertFalse(TenderSummary.applies(tipped, true, true));                   // register sale: the POS already knows
        assertTrue(TenderSummary.applies(walkUp(53_50, 0), true, false));         // exact amount, change only
        AmountBreakdown preset = AmountBreakdown.of(60_00, 0, 10_00, false, true, 3.50, 0.0);
        assertFalse(TenderSummary.applies(preset, true, false));                  // a plain $60 withdrawal: nothing new to say
        assertFalse(TenderSummary.applies(AmountBreakdown.balanceInquiry(), true, false));
        assertFalse(TenderSummary.applies(null, true, false));
    }

    // ---- from a journal row (the Reprint picker) ----

    private static TransactionLog row(long sale, long tip, long cashBack, String result, String clerk) {
        TransactionLog l = new TransactionLog();
        l.setTransactionType(TransactionLog.TYPE_WITHDRAWAL);
        l.setResult(result);
        l.setSaleCents(sale); l.setTipCents(tip); l.setCashBackCents(cashBack);
        l.setAmountCents(sale + tip + cashBack); l.setFeeCents(3_50); l.setTotalCents(sale + tip + cashBack + 3_50);
        l.setClerkId(clerk);
        return l;
    }

    @Test
    public void fromRow_rebuildsTheTenderFromTheJournal() {
        TenderSummary s = TenderSummary.fromRow(row(53_50, 5_00, 1_50, TransactionLog.RESULT_APPROVED, null));
        assertNotNull(s);
        assertEquals(55_00, s.tender);
        assertEquals(1_50, s.changeDue);
        assertEquals(60_00, s.withdrawal);
    }

    @Test
    public void fromRow_isNullWhenTheBlockWouldNotHaveShown() {
        assertNull(TenderSummary.fromRow(row(53_50, 5_00, 1_50, TransactionLog.RESULT_DECLINED, null)));
        assertNull(TenderSummary.fromRow(row(53_50, 5_00, 1_50, TransactionLog.RESULT_APPROVED, "12")));   // register
        assertNull(TenderSummary.fromRow(row(60_00, 0, 0, TransactionLog.RESULT_APPROVED, null)));          // plain preset
        assertNull(TenderSummary.fromRow(row(0, 0, 0, TransactionLog.RESULT_APPROVED, null)));              // pre-6.2.13 row
        assertNull(TenderSummary.fromRow(null));
    }
}
