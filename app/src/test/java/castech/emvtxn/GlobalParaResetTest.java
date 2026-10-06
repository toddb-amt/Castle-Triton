package castech.emvtxn;

import static org.junit.Assert.assertEquals;
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
}
