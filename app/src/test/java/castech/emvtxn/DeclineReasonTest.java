package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * RCPT-03 (6.2.20): the decline text the screen, the slip, the journal and the reprint share. Bench
 * 2026-10-22: a 76 came with a whitespace-only host message, so the slip printed "Reason:" followed
 * by nothing. The host's message counts only when it says something; otherwise the code's own
 * description; 76 reads "PIN key sync" (HOST-15), not "Invalid account".
 */
public class DeclineReasonTest {

    @Test
    public void aBlankHostMessage_fallsBackToTheCodeDescription() {
        assertEquals("PIN key sync", DeclineReason.text(" ", "76"));
        assertEquals("PIN key sync", DeclineReason.text("", "76"));
        assertEquals("PIN key sync", DeclineReason.text(null, "76"));
        assertEquals("Insufficient funds", DeclineReason.text("   ", "51"));
    }

    @Test
    public void aHostMessageThatSaysSomething_isUsedTrimmed() {
        assertEquals("Insufficient funds in the account", DeclineReason.text("  Insufficient funds in the account ", "51"));
        assertEquals("Host timeout", DeclineReason.text("Host timeout", null));
    }

    @Test
    public void anUnknownCode_isNamedAsACode() {
        assertEquals("Declined (code 99)", DeclineReason.text(" ", "99"));
        assertEquals("Declined (code 99)", DeclineReason.describe("99"));
    }

    @Test
    public void nothingAtAll_isNull() {
        assertNull(DeclineReason.text(null, null));
        assertNull(DeclineReason.text(" ", ""));
        assertNull(DeclineReason.describe(null));
        assertNull(DeclineReason.describe("  "));
    }

    @Test
    public void theTable_keepsTheCommonCodes_inSentenceCase() {
        assertEquals("Approved", DeclineReason.describe("00"));
        assertEquals("Do not honor", DeclineReason.describe("05"));
        assertEquals("Incorrect PIN", DeclineReason.describe("55"));
        assertEquals("PIN tries exceeded", DeclineReason.describe("75"));
        assertEquals("PIN key sync", DeclineReason.describe(" 76 "));
        assertEquals("Issuer unavailable", DeclineReason.describe("91"));
    }
}
