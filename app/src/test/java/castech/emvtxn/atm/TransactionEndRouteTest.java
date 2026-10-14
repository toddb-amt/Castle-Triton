package castech.emvtxn.atm;

import org.junit.Test;
import static org.junit.Assert.*;

import static castech.emvtxn.atm.TransactionEndRoute.Route.*;

/**
 * PIN-02 (6.2.17): where the transaction thread goes when it ends.
 *
 * Found on the bench 2026-10-14: Cancel on the PIN pad after a tap reset the transaction state
 * and returned to the menu, but the thread ending only knew about the on-screen Cancel, so it
 * still showed the receipt page, which rendered its layout placeholders ($0.00, card 1234, a
 * 2025 date) under a green check. Same path for chip, swipe and tap.
 */
public class TransactionEndRouteTest {

    @Test
    public void pinPadCancelWithoutHostApprovalReturnsToMenu() {
        assertEquals(MAIN_MENU, TransactionEndRoute.decide(true, false, true, true, false));
    }

    @Test
    public void pinPadCancelOnBalanceInquiryReturnsToMenu() {
        assertEquals(MAIN_MENU, TransactionEndRoute.decide(true, false, true, false, true));
    }

    @Test
    public void cancelAfterHostApprovalStillShowsReceiptBecauseMoneyMoved() {
        assertEquals(RECEIPT, TransactionEndRoute.decide(true, true, true, true, false));
    }

    @Test
    public void finishedAtmTransactionShowsReceipt() {
        assertEquals(RECEIPT, TransactionEndRoute.decide(false, false, true, true, false));
        assertEquals(RECEIPT, TransactionEndRoute.decide(false, true, true, true, false));
    }

    @Test
    public void balanceInquiryWithoutAtmModeFlagStillShowsReceipt() {
        assertEquals(RECEIPT, TransactionEndRoute.decide(false, false, false, false, true));
    }

    @Test
    public void sampleAppModeReEnablesButtons() {
        assertEquals(SAMPLE_BUTTONS, TransactionEndRoute.decide(false, false, false, false, false));
    }
}
