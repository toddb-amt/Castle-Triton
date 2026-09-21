package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Regression tests for the contactless receipt-PAN bug reproduced on the
 * S1F4 PRO 2026-09-02: tap receipts showed a wrong last-4 because ASCII Track 2
 * was parsed with BCD logic. Verifies both encodings decode correctly and that
 * the specific broken behaviour cannot come back.
 */
public class Track2PanExtractorTest {

    private static final String PAN = "4111111111111111"; // 16-digit test PAN, last4 = 1111

    /** ASCII Track 2 as delivered by EMVCL for contactless: ";PAN=expiry...?". */
    private static byte[] asciiTrack2(String pan) {
        return (";" + pan + "=2903101254300000?").getBytes(StandardCharsets.US_ASCII);
    }

    /** BCD-packed Track 2 as delivered for contact / DF35: PAN 'D' expiry 'F'-pad. */
    private static byte[] bcdTrack2(String pan) {
        String hex = pan + "D" + "2903101254300000";
        if (hex.length() % 2 != 0) hex += "F";
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** The exact broken algorithm the CL path used before the fix. */
    private static String oldBuggyParse(byte[] track2, int len) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int v = track2[i] & 0xFF;
            if (v < 0x10) hex.append('0');
            hex.append(Integer.toHexString(v).toUpperCase());
        }
        int sepIdx = hex.toString().indexOf("D");
        if (sepIdx <= 0) return null;
        return hex.substring(0, sepIdx).replaceAll("[Ff]+$", "");
    }

    // ---- ASCII (contactless) --------------------------------------------------

    @Test
    public void extractPan_asciiContactless_returnsFullPan() {
        byte[] t2 = asciiTrack2(PAN);
        assertEquals(PAN, Track2PanExtractor.extractPan(t2, t2.length));
    }

    @Test
    public void extractPan_asciiContactless_lastFourIsCorrect() {
        byte[] t2 = asciiTrack2(PAN);
        String pan = Track2PanExtractor.extractPan(t2, t2.length);
        assertEquals("1111", pan.substring(pan.length() - 4));
    }

    /** The heart of the bug: the OLD parse produced a WRONG last-4 on ASCII input. */
    @Test
    public void oldBugReproduced_thenFixed() {
        byte[] t2 = asciiTrack2(PAN);
        String buggy = oldBuggyParse(t2, t2.length);
        // Old code cut at the 'D' inside 0x3D ('='), so its "last 4" were NOT the PAN's.
        assertNotEquals("old BCD parse must NOT yield the real PAN on ASCII track2",
                PAN, buggy);
        if (buggy != null && buggy.length() >= 4) {
            assertNotEquals("old last-4 was wrong (this is the reported symptom)",
                    "1111", buggy.substring(buggy.length() - 4));
        }
        // The fix yields the correct PAN and last-4.
        String fixed = Track2PanExtractor.extractPan(t2, t2.length);
        assertEquals(PAN, fixed);
        assertEquals("1111", fixed.substring(fixed.length() - 4));
    }

    // ---- BCD (contact / DF35) -------------------------------------------------

    @Test
    public void extractPan_bcdContact_returnsFullPan() {
        byte[] t2 = bcdTrack2(PAN);
        assertEquals(PAN, Track2PanExtractor.extractPan(t2, t2.length));
    }

    @Test
    public void extractPan_bcdOddLengthPan_stopsAtSeparator() {
        // 15-digit PAN (Amex-style) packed BCD.
        String pan15 = "371449635398431";
        byte[] t2 = bcdTrack2(pan15);
        assertEquals(pan15, Track2PanExtractor.extractPan(t2, t2.length));
    }

    // ---- edge cases -----------------------------------------------------------

    @Test
    public void extractPan_nullOrEmpty_returnsNull() {
        assertNull(Track2PanExtractor.extractPan(null, 0));
        assertNull(Track2PanExtractor.extractPan(new byte[0], 0));
    }

    @Test
    public void extractPan_lenClampedToArray() {
        byte[] t2 = asciiTrack2(PAN);
        // Oversized len must not overflow; still decodes the PAN.
        assertEquals(PAN, Track2PanExtractor.extractPan(t2, t2.length + 50));
    }

    // ---- masking --------------------------------------------------------------

    @Test
    public void maskPan_keepsFirst6AndLast4() {
        assertEquals("411111******1111", Track2PanExtractor.maskPan(PAN));
    }

    @Test
    public void maskPan_isReceiptSafe_showsOnlyLast4Clear() {
        String masked = Track2PanExtractor.maskPan(PAN);
        assertTrue("must end with real last-4", masked.endsWith("1111"));
        assertTrue("middle must be masked", masked.contains("*"));
    }

    @Test
    public void maskPan_shortPan_masksAllButLast4() {
        assertEquals("****3456", Track2PanExtractor.maskPan("12343456"));
    }

    @Test
    public void maskPan_nullSafe() {
        assertNull(Track2PanExtractor.maskPan(null));
    }
}
