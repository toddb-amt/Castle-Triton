package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * POS-13 (6.2.12). What the register is told when a POS-driven transaction finishes on
 * the terminal without a host result.
 *
 * <p>Before this, only host callbacks and Cancel answered the register. A transaction
 * that ended locally left the POS slot armed until the 300 s watchdog — seen at a POS
 * site 2026-10-05 as "the terminal took exactly 300 seconds to answer".
 */
public class PosLocalEndingTest {

    private static PosLocalEnding decide(String code, String message) {
        return PosLocalEnding.decide(false, false, code, message, null);
    }

    // ---- the terminal's own reason is passed on ---------------------------------

    @Test
    public void terminalDecline_carriesTheCodeAndReasonTheReceiptShows() {
        PosLocalEnding e = decide("MSR_NA", "Swipe not supported");
        assertNotNull(e);
        assertEquals("MSR_NA", e.responseCode);
        assertEquals("Swipe not supported", e.message);
    }

    @Test
    public void cardReadError_carriesTheSdkCode() {
        PosLocalEnding e = decide("A0000002", "Contactless read error - 0xA0000002");
        assertEquals("A0000002", e.responseCode);
        assertEquals("Contactless read error - 0xA0000002", e.message);
    }

    @Test
    public void reasonWithoutACode_getsTheGenericTerminalCode() {
        PosLocalEnding e = decide("", "Transaction timeout");
        assertEquals("terminal_declined", e.responseCode);
        assertEquals("Transaction timeout", e.message);
    }

    @Test
    public void codeWithoutAReason_getsAGenericReason() {
        PosLocalEnding e = decide("MSR_FAIL", "  ");
        assertEquals("MSR_FAIL", e.responseCode);
        assertEquals("declined at terminal", e.message);
    }

    // ---- nothing recorded at all = the customer backed out ----------------------
    // Same rule the journal uses (JournalOutcome): no code and no reason is a cancel,
    // e.g. the PIN pad was dismissed. The register hears what Cancel already sends.

    @Test
    public void nothingRecorded_isReportedAsACancel() {
        PosLocalEnding e = decide("", "");
        assertEquals("user_cancelled", e.responseCode);
        assertEquals("cancelled at terminal", e.message);
    }

    @Test
    public void nothingRecorded_nullsIncluded_isReportedAsACancel() {
        PosLocalEnding e = decide(null, null);
        assertEquals("user_cancelled", e.responseCode);
        assertEquals("cancelled at terminal", e.message);
    }

    // ---- when the terminal must NOT speak ---------------------------------------

    @Test
    public void hostCallStillInFlight_saysNothing_theRealResultMustBeAbleToLand() {
        // The transaction thread can give up (130 s) before the host layer does (up to
        // 150 s). Answering now would empty the slot and a late approval would be lost.
        assertNull(PosLocalEnding.decide(true, false, "", "Transaction timeout", null));
    }

    @Test
    public void hostApproved_saysNothing_anApprovalIsNeverReportedAsADecline() {
        assertNull(PosLocalEnding.decide(false, true, "00", "APPROVED", null));
    }

    // ---- the transaction thread itself failed -----------------------------------

    @Test
    public void threadFailure_isReportedAsATerminalError() {
        PosLocalEnding e = PosLocalEnding.decide(false, false, "", "", "NullPointerException");
        assertEquals("terminal_error", e.responseCode);
        assertEquals("terminal error: NullPointerException", e.message);
    }

    @Test
    public void threadFailure_stillSaysNothingWhileTheHostCallIsInFlight() {
        assertNull(PosLocalEnding.decide(true, false, "", "", "NullPointerException"));
    }
}
