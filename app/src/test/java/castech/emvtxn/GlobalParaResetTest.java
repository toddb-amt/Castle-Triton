package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * TXN-01 (6.2.12). {@code resetATMHostResponse()} is called at the start of every
 * transaction, one line after {@code atmTransactionInProgress} is set — and it cleared
 * that flag, so the flag was false for the whole transaction and every guard that read
 * it (duplicate start, POS "customer transaction in progress", printer and report
 * guards) was asleep during the card and PIN phase.
 */
public class GlobalParaResetTest {

    @After
    public void tearDown() {
        GlobalPara.atmTransactionInProgress = false;
    }

    @Test
    public void resetATMHostResponse_leavesATransactionInProgress_inProgress() {
        GlobalPara.atmTransactionInProgress = true;

        GlobalPara.resetATMHostResponse();

        assertTrue("a host-response reset must not end the transaction", GlobalPara.atmTransactionInProgress);
    }

    @Test
    public void resetATMHostResponse_stillClearsTheHostResult() {
        GlobalPara.atmHostCallSuccess = true;
        GlobalPara.atmHostCallInProgress = true;
        GlobalPara.atmTransactionComplete = true;
        GlobalPara.atmResponseCode = "51";
        GlobalPara.atmResponseMessage = "INSUFFICIENT FUNDS";
        GlobalPara.atmEncryptedPinBlock = "0123456789ABCDEF";

        GlobalPara.resetATMHostResponse();

        assertFalse(GlobalPara.atmHostCallSuccess);
        assertFalse(GlobalPara.atmHostCallInProgress);
        assertFalse(GlobalPara.atmTransactionComplete);
        assertEquals("", GlobalPara.atmResponseCode);
        assertEquals("", GlobalPara.atmResponseMessage);
        assertEquals("", GlobalPara.atmEncryptedPinBlock);
    }

    // ---- TIP-01 (6.2.14): one writer for the amount mirrors ---------------------------------

    @Test
    public void applyAmounts_writesEveryMirrorFromTheOneBreakdown() {
        AmountBreakdown a = AmountBreakdown.of(10_00, 1_00, 20_00, true, true, 3.50, 0.0);
        GlobalPara.applyAmounts(a);
        assertSame(a, GlobalPara.atmAmounts);
        assertEquals("20.00", GlobalPara.atmSelectedAmount);
        assertEquals("3.50", GlobalPara.atmFee);
        assertEquals("23.50", GlobalPara.atmTotal);
        assertEquals("2350", GlobalPara.strAmount);
    }

    @Test
    public void clearAmounts_leavesNoStaleBreakdown() {
        GlobalPara.applyAmounts(AmountBreakdown.of(10_00, 1_00, 20_00, true, true, 3.50, 0.0));
        GlobalPara.clearAmounts();
        assertEquals(0, GlobalPara.atmAmounts.sale);
        assertEquals(0, GlobalPara.atmAmounts.tip);
        assertEquals("0.00", GlobalPara.atmSelectedAmount);
        assertEquals("0.00", GlobalPara.atmFee);
        assertEquals("0.00", GlobalPara.atmTotal);
        assertEquals("0", GlobalPara.strAmount);
    }
}
