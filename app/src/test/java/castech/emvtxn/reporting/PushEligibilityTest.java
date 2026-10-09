package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * RPT-02 (6.2.13), design decision R3: send everything the host answered plus terminal-side
 * declines where a card was presented; never cancels. In journal terms: APPROVED and DECLINED
 * withdrawals and balance inquiries, and every reversal row. CANCELLED is never sent.
 */
public class PushEligibilityTest {

    @Test
    public void hostAnsweredRows_areSendable() {
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "APPROVED"));
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "DECLINED"));
        assertTrue(PushEligibility.isSendable("BALANCE_INQUIRY", "APPROVED"));
        assertTrue(PushEligibility.isSendable("BALANCE_INQUIRY", "DECLINED"));
    }

    @Test
    public void terminalSideDeclineWithACard_isJournaledAsDeclined_soItIsSendable() {
        // a swipe we refuse (MSR_NA), a reader error, NO_TRACK2 all land as DECLINED rows
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "DECLINED"));
    }

    @Test
    public void cancels_areNeverSendable() {
        assertFalse(PushEligibility.isSendable("WITHDRAWAL", "CANCELLED"));
        assertFalse(PushEligibility.isSendable("BALANCE_INQUIRY", "CANCELLED"));
    }

    @Test
    public void reversalRows_areAlwaysSendable() {
        assertTrue(PushEligibility.isSendable("REVERSAL", "APPROVED"));
    }

    @Test
    public void unknownTypesOrResults_areNotSendable() {
        assertFalse(PushEligibility.isSendable(null, "APPROVED"));
        assertFalse(PushEligibility.isSendable("WITHDRAWAL", null));
        assertFalse(PushEligibility.isSendable("SOMETHING", "APPROVED"));
    }

    @Test
    public void initialState_isPendingOnlyWhenConfiguredAndSendable() {
        assertEquals(PushEligibility.PUSH_PENDING, PushEligibility.initialState("WITHDRAWAL", "APPROVED", true));
        assertEquals(PushEligibility.PUSH_NOT_APPLICABLE, PushEligibility.initialState("WITHDRAWAL", "APPROVED", false));
        assertEquals(PushEligibility.PUSH_NOT_APPLICABLE, PushEligibility.initialState("WITHDRAWAL", "CANCELLED", true));
    }
}
