package castech.emvtxn.atm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class JournalOutcomeTest {
    @Test public void approvedWithdrawal() {
        JournalOutcome o = JournalOutcome.of(false, true, "00", "APPROVED");
        assertEquals("WITHDRAWAL", o.type); assertEquals("APPROVED", o.result);
    }
    @Test public void declinedWithdrawal() {
        JournalOutcome o = JournalOutcome.of(false, false, "51", "INSUFFICIENT FUNDS");
        assertEquals("WITHDRAWAL", o.type); assertEquals("DECLINED", o.result);
    }
    @Test public void connectionErrorIsDeclined_notCancelled() {
        JournalOutcome o = JournalOutcome.of(false, false, "", "Connection error: timed out");
        assertEquals("DECLINED", o.result);
    }
    @Test public void cancelledMessage_isCancelled() {
        assertEquals("CANCELLED", JournalOutcome.of(false, false, "", "Transaction cancelled").result);
        assertEquals("CANCELLED", JournalOutcome.CANCELLED.result);
    }
    @Test public void balanceInquiry_keepsItsType() {
        assertEquals("BALANCE_INQUIRY", JournalOutcome.of(true, true, "00", "APPROVED").type);
        assertEquals("BALANCE_INQUIRY", JournalOutcome.of(true, false, "05", "DO NOT HONOR").type);
    }
}
