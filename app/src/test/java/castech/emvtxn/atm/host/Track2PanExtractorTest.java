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

    // ---- Track 2 for the host (TAP-01, 6.2.12) ---------------------------------
    //
    // Field 6 of the STD1 request is ";PAN=EXPIRY...?" for every entry mode. A tap
    // delivers that text as ASCII bytes followed by an LRC byte (seen on the S1F4 PRO
    // 2026-09-02: the buffer ended 0x3F 0x3D); a chip read delivers it BCD-packed.
    // The contactless host block hex-dumped the ASCII buffer, which would have sent
    // ";3B34313131...?" to the processor had that block ever run.

    private static final String HOST_TRACK2 = ";4111111111111111=2903101254300000?";

    @Test
    public void toHostTrack2_asciiTap_isSentAsText_notHexDumped() {
        byte[] t2 = HOST_TRACK2.getBytes(StandardCharsets.US_ASCII);
        assertEquals(HOST_TRACK2, Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_asciiTap_dropsTheLrcByteAfterTheEndSentinel() {
        byte[] t2 = (HOST_TRACK2 + "=").getBytes(StandardCharsets.US_ASCII); // '=' here is the LRC
        assertEquals(HOST_TRACK2, Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_asciiWithoutSentinels_addsThem() {
        byte[] t2 = "4111111111111111=2903101254300000".getBytes(StandardCharsets.US_ASCII);
        assertEquals(HOST_TRACK2, Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_bcdPacked_turnsSeparatorIntoEqualsAndDropsPadding() {
        byte[] t2 = bcdTrack2(PAN); // 4111111111111111 D 2903101254300000 F
        assertEquals(HOST_TRACK2, Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_readsOnlyLenBytesOfTheSdkBuffer() {
        byte[] sdkBuffer = new byte[256];                       // the SDK hands over a fixed-size array
        byte[] t2 = HOST_TRACK2.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t2, 0, sdkBuffer, 0, t2.length);
        assertEquals(HOST_TRACK2, Track2PanExtractor.toHostTrack2(sdkBuffer, t2.length));
    }

    @Test
    public void toHostTrack2_maskedTrackKeepsItsMask_soTheCallerCanRefuseToSendIt() {
        byte[] t2 = ";411111******1111=2903?".getBytes(StandardCharsets.US_ASCII);
        assertEquals(";411111******1111=2903?", Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_withoutAFieldSeparator_isUnusable() {
        byte[] ascii = ";4111111111111111?".getBytes(StandardCharsets.US_ASCII);
        assertNull(Track2PanExtractor.toHostTrack2(ascii, ascii.length));
        byte[] bcd = { 0x41, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 };
        assertNull(Track2PanExtractor.toHostTrack2(bcd, bcd.length));
    }

    @Test
    public void toHostTrack2_nullOrEmpty_isUnusable() {
        assertNull(Track2PanExtractor.toHostTrack2(null, 0));
        assertNull(Track2PanExtractor.toHostTrack2(new byte[0], 0));
        assertNull(Track2PanExtractor.toHostTrack2(new byte[8], 0));
    }

    // ---- the card controls these bytes: only Track 2 may reach the host ----------
    //
    // Field 6 sits in a message whose fields are split on FS (0x1C). Track 2 from a
    // tap is whatever the card — or a card emulator — sent, so it is validated, not
    // copied: digits and exactly one '=' ('*' where the reader masked it), a PAN of
    // 12-19 characters, at most 37 characters in all (ISO 7813). Anything else is
    // unusable, and the transaction is not sent.

    @Test
    public void toHostTrack2_withAFieldSeparatorByte_isRejected_soACardCannotAddFields() {
        byte[] t2 = (";4111111111111111=2903" + (char) 0x1C + "99999?").getBytes(StandardCharsets.ISO_8859_1);
        assertNull(Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_withAnyControlOrHighByte_isRejected() {
        for (int b : new int[] { 0x00, 0x02, 0x03, 0x0A, 0x0D, 0x1C, 0x7F, 0x80, 0xFF }) {
            byte[] t2 = ";4111111111111111=29031012?".getBytes(StandardCharsets.US_ASCII);
            t2[10] = (byte) b;
            assertNull("byte 0x" + Integer.toHexString(b), Track2PanExtractor.toHostTrack2(t2, t2.length));
        }
    }

    @Test
    public void toHostTrack2_withLettersInTheTrack_isRejected() {
        byte[] ascii = ";41111x1111111111=2903?".getBytes(StandardCharsets.US_ASCII);
        assertNull(Track2PanExtractor.toHostTrack2(ascii, ascii.length));
        // BCD with a stray 'A' nibble inside the PAN
        byte[] bcd = { 0x41, 0x11, (byte) 0xA1, 0x11, 0x11, 0x11, 0x11, 0x11, (byte) 0xD2, (byte) 0x90, 0x31 };
        assertNull(Track2PanExtractor.toHostTrack2(bcd, bcd.length));
    }

    @Test
    public void toHostTrack2_withTwoSeparators_isRejected() {
        byte[] t2 = ";4111111111111111=29=03?".getBytes(StandardCharsets.US_ASCII);
        assertNull(Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_longerThanATrack2CanBe_isRejected() {
        byte[] t2 = (";4111111111111111=" + "290310125430000012345" + "?")   // 16 + 1 + 21 = 38
                .getBytes(StandardCharsets.US_ASCII);
        assertNull(Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_atTheLongestLegalLength_isAccepted() {
        String track = ";4111111111111111=" + "29031012543000001234" + "?";       // 16 + 1 + 20 = 37
        byte[] t2 = track.getBytes(StandardCharsets.US_ASCII);
        assertEquals(track, Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_withAPanShorterThanAnyCard_isRejected() {
        byte[] t2 = ";41111111111=2903?".getBytes(StandardCharsets.US_ASCII);     // 11 digits
        assertNull(Track2PanExtractor.toHostTrack2(t2, t2.length));
    }

    @Test
    public void toHostTrack2_carriesTheSamePanTheTerminalReadsForItself() {
        // No second opinion: the PAN inside Field 6 is the PAN extractPan returns.
        byte[] ascii = (HOST_TRACK2 + "=").getBytes(StandardCharsets.US_ASCII);
        byte[] bcd = bcdTrack2(PAN);
        for (byte[] t2 : new byte[][] { ascii, bcd }) {
            String host = Track2PanExtractor.toHostTrack2(t2, t2.length);
            assertEquals("4111111111111111", host.substring(1, host.indexOf('=')));
            assertEquals("4111111111111111", Track2PanExtractor.extractPan(t2, t2.length));
        }
    }
}
