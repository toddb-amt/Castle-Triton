package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * ADM-10 (6.2.20): the IMEI line on the Admin Network card. The S1F4 PRO's modem reports the IMEI
 * without its check digit (14 digits, seen on the bench 2026-10-22); the label and every carrier form
 * use the 15-digit form, so the check digit (Luhn) is appended. A 15-digit value is shown as given, and
 * anything else as given too, never invented. Identifiers here are synthetic.
 */
public class ImeiTest {

    @Test
    public void fourteenDigits_getTheirLuhnCheckDigit() {
        assertEquals("490154203237518", Imei.display("49015420323751"));    // the textbook example
        assertEquals("012345678901237", Imei.display("01234567890123"));    // a second 14-digit form, leading zero kept
    }

    @Test
    public void fifteenDigits_areShownAsGiven() {
        assertEquals("490154203237518", Imei.display("490154203237518"));
    }

    @Test
    public void whitespaceAndSeparators_areIgnored() {
        assertEquals("490154203237518", Imei.display(" 49-015420-323751 "));
    }

    @Test
    public void anythingElse_isShownAsGiven_orNull() {
        assertEquals("ABC123", Imei.display("ABC123"));
        assertEquals("1234567890123456", Imei.display("1234567890123456"));   // 16 digits: not an IMEI, not touched
        assertNull(Imei.display(null));
        assertNull(Imei.display("   "));
    }

    @Test
    public void checkDigit_isLuhn() {
        assertEquals(8, Imei.checkDigit("49015420323751"));
        assertEquals(7, Imei.checkDigit("01234567890123"));
    }

    @Test
    public void masked_forLogs_keepsTheTypeAllocationCodeOnly() {
        assertEquals("49015420*******", Imei.masked("490154203237518"));
        assertEquals("(none)", Imei.masked(null));
    }
}
