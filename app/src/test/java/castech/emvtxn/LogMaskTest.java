package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Card data and PIN blocks must never reach logcat readable (PCI DSS: no
 * retention of sensitive authentication data after authorisation — logs are
 * retention). These vectors pin the masking contract for every helper.
 */
public class LogMaskTest {

    private static final String PAN = "4111111111111111";
    private static final String TRACK2_ASCII = ";" + PAN + "=2905201104001927?";       // 35 chars
    private static final String TRACK2_HEX = PAN + "D2905201104001927F";               // 17 bytes
    private static final String PIN_BLOCK = "B1D99A4E151864FE";
    private static final String ARQC = "7C9FA6D9C2CFAC41";

    // ---- pan ------------------------------------------------------------------

    @Test
    public void pan_showsLastFourOnly() {
        assertEquals("****1111", LogMask.pan(PAN));
        assertEquals("****1111", LogMask.pan("4111 1111 1111 1111"));   // non-digits ignored
        assertEquals("****", LogMask.pan("1234"));
        assertEquals("[null]", LogMask.pan(null));
        assertEquals("[empty]", LogMask.pan("  "));
    }

    // ---- track2 ---------------------------------------------------------------

    @Test
    public void track2_neverEchoesPanOrDiscretionaryData() {
        String ascii = LogMask.track2(TRACK2_ASCII);
        assertEquals("[Track 2: 35 chars, PAN ****1111]", ascii);
        String hex = LogMask.track2(TRACK2_HEX);
        assertEquals("[Track 2: 34 chars, PAN ****1111]", hex);
        assertFalse(ascii.contains("2905"));
        assertFalse(hex.contains("2905"));
        assertEquals("[Track 2: null]", LogMask.track2(null));
        assertEquals("[Track 2: empty]", LogMask.track2(""));
    }

    // ---- pinBlock / len -------------------------------------------------------

    @Test
    public void pinBlock_isByteCountOnly() {
        assertEquals("[8 bytes]", LogMask.pinBlock(PIN_BLOCK));
        assertEquals("[null]", LogMask.pinBlock(null));
        assertEquals("[empty]", LogMask.pinBlock(""));
        assertEquals("[16 chars]", LogMask.len(PIN_BLOCK));
    }

    // ---- tlv ------------------------------------------------------------------

    @Test
    public void tlv_redactsPanAndTrack2ButKeepsEveryOtherTag() {
        String in = "9F0902008C" + "5A08" + PAN + "5711" + TRACK2_HEX
                  + "9F2608" + ARQC + "9505" + "8080048000";
        String out = LogMask.tlv(in);
        assertEquals("9F0902008C5A08[8 bytes redacted]5711[17 bytes redacted]9F2608" + ARQC + "95058080048000", out);
        assertFalse(out.contains(PAN));
        assertTrue(out.contains(ARQC));
    }

    @Test
    public void tlv_walksConstructedTemplates() {
        String in = "7015" + "5A08" + PAN + "9F2608" + ARQC;
        assertEquals("70155A08[8 bytes redacted]9F2608" + ARQC, LogMask.tlv(in));
    }

    @Test
    public void tlv_handlesLongFormLengthAndCardholderName() {
        // 5F20 (cardholder name), length 0x81 0x05, "HELLO"
        assertEquals("5F208105[5 bytes redacted]", LogMask.tlv("5F20810548454C4C4F"));
    }

    @Test
    public void tlv_truncatedOrGarbageNeverLeaksRaw() {
        assertEquals("5A08[trunc 2 of 8 bytes]", LogMask.tlv("5A084430"));
        assertEquals("[2 chars, unparsed]", LogMask.tlv("ZZ"));
        assertEquals("009C0131", LogMask.tlv("009C0131"));  // leading padding byte passes through
        assertEquals("[null]", LogMask.tlv(null));
        assertEquals("[empty]", LogMask.tlv(""));
    }

    @Test
    public void tlv_isCaseInsensitiveOnInput() {
        assertEquals("5A08[8 bytes redacted]", LogMask.tlv("5a08" + PAN));
    }

    // ---- std1 -----------------------------------------------------------------

    private static byte[] std1(String... fields) {
        return String.join("", fields).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void std1_masksTrackPinAndEmvFieldsOnly() {
        String out = LogMask.std1(std1(
                "H0.000000", "MS008628", "85", "CWCACA", "0003", "",
                TRACK2_ASCII, "", PIN_BLOCK, "1000", "350", "1", "",
                "ud9F0902008C5A08" + PAN + "9F2608" + ARQC));

        assertTrue(out, out.startsWith("["));
        assertTrue(out, out.contains("MS008628<FS>85<FS>CWCACA<FS>0003"));
        assertTrue(out, out.contains("[Track 2: 35 chars, PAN ****1111]"));
        assertTrue(out, out.contains("<FS>[8 bytes]<FS>1000<FS>350<FS>1<FS>"));
        assertTrue(out, out.contains("ud9F0902008C5A08[8 bytes redacted]9F2608" + ARQC));
        assertFalse(out, out.contains(PAN));
        assertFalse(out, out.contains(PIN_BLOCK));
    }

    @Test
    public void std1_leavesNumericFieldsAndRrnReadable() {
        String out = LogMask.std1(std1("86", "778900000003", "1000", "0912345678901234"));
        assertTrue(out, out.contains("86<FS>778900000003<FS>1000<FS>0912345678901234"));
    }

    @Test
    public void std1_encryptedTrackAndControlBytes() {
        byte[] msg = ("" + "e" + "00112233445566778899AABBCCDDEEFF00112233" + "" + "00A7").getBytes(StandardCharsets.US_ASCII);
        String out = LogMask.std1(msg);
        assertTrue(out, out.contains("\\x02e[20 bytes encrypted]<FS>00A7"));

        // Trailing ETX + LRC share the last field with the EMV data
        byte[] tail = ("ud5A08" + PAN).getBytes(StandardCharsets.US_ASCII);
        byte[] msg2 = new byte[tail.length + 2];
        System.arraycopy(tail, 0, msg2, 0, tail.length);
        msg2[tail.length] = 0x03;
        msg2[tail.length + 1] = 0x5A;   // LRC — printable 'Z', stays as-is
        String out2 = LogMask.std1(msg2);
        assertTrue(out2, out2.contains("ud5A08[8 bytes redacted]\\x03Z"));
        assertFalse(out2, out2.contains(PAN));

        assertEquals("[null]", LogMask.std1(null));
    }
}
