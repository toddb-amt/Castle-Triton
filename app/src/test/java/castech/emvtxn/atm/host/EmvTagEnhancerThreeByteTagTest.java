package castech.emvtxn.atm.host;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

/**
 * TAP-02 (6.2.17): Field 13 must survive three-byte EMV tags.
 *
 * Field failure 2026-10-14 on a Mastercard tap: Kernel 2 (Mastercard) returns three-byte tags in
 * its data record, Castles' own DF8F4F first, then kernel-internal DF81xx objects, then 9F6E and
 * 9F42. The old parser only understood one- and two-byte tags, read "DF8F" as the tag and the third
 * tag byte 4F as a 79-byte length, swallowed the rest of the record into a fake value and cut the
 * last bytes of 9F6E. The processor's TLV parser then found a bare track 2 after 9F6E and rejected
 * the request (BAD TLV FORMAT, response 006). Kernel 3 (Visa) emits no three-byte tags, which is
 * why only Mastercard taps failed. Bench reproduction 2026-10-14 16:17 with a real Mastercard.
 *
 * Expected behaviour: tags of one to three bytes parse correctly; kernel-internal DF81xx and
 * Castles-proprietary DF8Fxx tags are dropped from Field 13; every other tag is passed through
 * intact and the result is valid BER-TLV.
 *
 * Synthetic data only: Mastercard test PAN 5413339000001596.
 */
public class EmvTagEnhancerThreeByteTagTest {

    private static final String STANDARD =
            "9F0206000000002000" + "9F2608FDBBFF4A279AFFD4" + "95058080108000" + "9F270180"
            + "9C0101" + "9A03261014" + "5F2403251231" + "9F360200A1" + "82021980" + "9F3704A1B2C3D4"
            + "9F10120110A04003220000000000000000000000FF" + "9F3303E0F8C8" + "9F3403420000"
            + "4F07A0000000041010" + "5F2A020840" + "9F1A020840" + "9F350114";

    private static final String KERNEL2_INTERNAL = "DF810F0100";              // three-byte tag
    private static final String THIRD_PARTY_DATA = "9F6E0708400000325710";    // Mastercard 9F6E
    private static final String TRACK2_VALUE = "5413339000001596D25122010000000000";
    private static final String TRACK2 = "5711" + TRACK2_VALUE;
    private static final String PAN = "5A085413339000001596";

    /** Shape of the real additional data from the bench Mastercard (84 bytes, DF8F4F first). */
    private static final String REAL_ADDITIONAL_DATA =
            "DF8F4F020004"
            + "DF81290830F0F000B0F0FF00"
            + "DF8116161B00000000656E000000000000000000000000000000"
            + "DF8115060000000000FF"
            + "9F42020840"
            + "DF810B0100"
            + "DF810E0100"
            + "DF810F0100"
            + "9F6E0708400000303000";

    @Test
    public void kernel2OrderInternalThenThirdPartyThenTrack() {
        assertCleanFieldThirteen("order A", STANDARD + KERNEL2_INTERNAL + THIRD_PARTY_DATA + TRACK2 + PAN);
    }

    @Test
    public void kernel2OrderThirdPartyThenInternalThenTrack() {
        assertCleanFieldThirteen("order B", STANDARD + THIRD_PARTY_DATA + KERNEL2_INTERNAL + TRACK2 + PAN);
    }

    @Test
    public void kernel2OrderTrackFirst() {
        assertCleanFieldThirteen("order C", STANDARD + TRACK2 + PAN + KERNEL2_INTERNAL + THIRD_PARTY_DATA);
    }

    @Test
    public void realBenchRecordShapeRoundTrips() {
        String decoded = assertCleanFieldThirteen("bench", STANDARD + TRACK2 + PAN + REAL_ADDITIONAL_DATA);
        assertTrue("9F6E must keep all 7 bytes\n" + decoded, decoded.contains("9F6E(7)=08400000303000"));
        assertTrue("9F42 must be passed through\n" + decoded, decoded.contains("9F42(2)=0840"));
    }

    @Test
    public void visaStyleRecordWithoutThreeByteTagsStillRoundTrips() {
        assertCleanFieldThirteen("visa", STANDARD + THIRD_PARTY_DATA + TRACK2 + PAN);
    }

    @Test
    public void threeByteTagThatIsNotKernelInternalIsKeptIntact() {
        String decoded = assertCleanFieldThirteen("generic", STANDARD + "DF9F04020101" + TRACK2 + PAN);
        assertTrue("DF9F04 must be passed through intact\n" + decoded, decoded.contains("DF9F04(2)=0101"));
    }

    /** Runs the enhancer in ATM contactless mode and returns the strict decode, one tag per line. */
    private static String assertCleanFieldThirteen(String label, String kernelData) {
        String out = new EmvTagEnhancer().enhanceEmvData(kernelData, EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CONTACTLESS, true, true);
        List<String> decoded;
        try {
            decoded = strictDecode(out);
        } catch (RuntimeException e) {
            fail(label + ": Field 13 is not valid TLV: " + e.getMessage() + "\nOUT=" + out);
            return null;
        }
        String joined = String.join("\n", decoded);
        for (String line : decoded) {
            assertFalse(label + " must drop kernel-internal tag " + line + "\n" + joined,
                    line.startsWith("DF81") || line.startsWith("DF8F"));
        }
        assertTrue(label + " lost 9F6E\n" + joined, joined.contains("9F6E(7)=") || !kernelData.contains("9F6E07"));
        assertTrue(label + " lost 57\n" + joined, joined.contains("57(17)=" + TRACK2_VALUE));
        assertTrue(label + " lost 5A\n" + joined, joined.contains("5A(8)=5413339000001596"));
        assertTrue(label + " lost the cryptogram\n" + joined, joined.contains("9F26(8)=FDBBFF4A279AFFD4"));
        return joined;
    }

    /** Strict BER-TLV walk: tags of 1 to 3 bytes, lengths in short form, 81 xx or 82 xxxx. */
    static List<String> strictDecode(String hex) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        while (pos < hex.length()) {
            int start = pos;
            int b = byteAt(hex, pos, out); pos += 2;
            StringBuilder tag = new StringBuilder(String.format("%02X", b));
            if ((b & 0x1F) == 0x1F) {
                do {
                    b = byteAt(hex, pos, out); pos += 2;
                    tag.append(String.format("%02X", b));
                } while ((b & 0x80) != 0);
            }
            int len = byteAt(hex, pos, out); pos += 2;
            if (len == 0x81) { len = byteAt(hex, pos, out); pos += 2; }
            else if (len == 0x82) { len = Integer.parseInt(hex.substring(pos, pos + 4), 16); pos += 4; }
            else if (len > 0x7F) throw new IllegalStateException(
                    "bad length byte " + String.format("%02X", len) + " for tag " + tag + " at hex offset " + start + " after " + out);
            if (pos + len * 2 > hex.length()) throw new IllegalStateException(
                    "tag " + tag + " length " + len + " overruns the data at hex offset " + start + " after " + out);
            out.add(tag + "(" + len + ")=" + hex.substring(pos, pos + len * 2));
            pos += len * 2;
        }
        return out;
    }

    private static int byteAt(String hex, int pos, List<String> soFar) {
        if (pos + 2 > hex.length()) throw new IllegalStateException("ran off the end at hex offset " + pos + " after " + soFar);
        return Integer.parseInt(hex.substring(pos, pos + 2), 16);
    }
}
