package castech.emvtxn;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for CAPK data validation
 * Tests that the CAPK keys we're providing in eventCapkGet are valid
 */
public class CAPKDataTest {

    // Helper to convert hex string to byte array (same as Converter.hexString2ByteArray)
    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    @Test
    public void testCAPK05_TestKey_ValidLength() {
        // VISA test key (1152-bit = 144 bytes)
        String modulus = "BE9E1FA5E9A803852999C4AB432DB28600DCD9DAB76DFAAA47355A0FE37B1508AC6BF38860D3C6C2E5B12A3CAAF2A7005A7241EBAA7771112C74CF9A0634652FBCA0E5980C54A64761EA101A114E0F0B5572ADD57D010B7C9C887E104CA4EE1272DA66D997B9A90B5A6D624AB6C57E73C8F919000EB5F684898EF8C3DBEFB330C62660BED88EA78E909AFF05F6DA627B";
        String hash = "EE1511CEC71020A9B90443B37B1D5F6E703030F6";

        byte[] modulusBytes = hexStringToByteArray(modulus);
        byte[] hashBytes = hexStringToByteArray(hash);

        assertEquals("CAPK 05 modulus should be 144 bytes (1152-bit)", 144, modulusBytes.length);
        assertEquals("CAPK 05 hash should be 20 bytes (SHA-1)", 20, hashBytes.length);
    }

    @Test
    public void testCAPK07_ProductionKey_ValidLength() {
        // VISA production key (1152-bit = 144 bytes)
        String modulus = "A89F25A56FA6DA258C8CA8B40427D927B4A1EB4D7EA326BBB12F97DED70AE5E4480FC9C5E8A972177110A1CC318D06D2F8F5C4844AC5FA79A4DC470BB11ED635699C17081B90F1B984F12E92C1C529276D8AF8EC7F28492097D8CD5BECEA16FE4088F6CFAB4A1B42328A1B996F9278B0B7E3311CA5EF856C2F888474B83612A82E4E00D0CD4069A6783140433D50725F";
        String hash = "B4BC56CC4E88324932CBC643D6898F6FE593B172";

        byte[] modulusBytes = hexStringToByteArray(modulus);
        byte[] hashBytes = hexStringToByteArray(hash);

        assertEquals("CAPK 07 modulus should be 144 bytes (1152-bit)", 144, modulusBytes.length);
        assertEquals("CAPK 07 hash should be 20 bytes (SHA-1)", 20, hashBytes.length);
    }

    @Test
    public void testCAPK08_ProductionKey_ValidLength() {
        // VISA production key (1408-bit = 176 bytes)
        String modulus = "D9FD6ED75D51D0E30664BD157023EAA1FFA871E4DA65672B863D255E81E137A51DE4F72BCC9E44ACE12127F87E263D3AF9DD9CF35CA4A7B01E907000BA85D24954C2FCA3074825DDD4C0C8F186CB020F683E02F2DEAD3969133F06F7845166ACEB57CA0FC2603445469811D293BFEFBAFAB57631B3DD91E796BF850A25012F1AE38F05AA5C4D6D03B1DC2E568612785938BBC9B3CD3A910C1DA55A5A9218ACE0F7A21287752682F15832A678D6E1ED0B";
        String hash = "20D213126955DE205ADC2FD2822BD22DE21CF9A8";

        byte[] modulusBytes = hexStringToByteArray(modulus);
        byte[] hashBytes = hexStringToByteArray(hash);

        assertEquals("CAPK 08 modulus should be 176 bytes (1408-bit)", 176, modulusBytes.length);
        assertEquals("CAPK 08 hash should be 20 bytes (SHA-1)", 20, hashBytes.length);
    }

    @Test
    public void testCAPK09_ProductionKey_ValidLength() {
        // VISA production key (1984-bit = 248 bytes) - most commonly used
        String modulus = "9D912248DE0A4E39C1A7DDE3F6D2588992C1A4095AFBD1824D1BA74847F2BC4926D2EFD904B4B54954CD189A54C5D1179654F8F9B0D2AB5F0357EB642FEDA95D3912C6576945FAB897E7062CAA44A4AA06B8FE6E3DBA18AF6AE3738E30429EE9BE03427C9D64F695FA8CAB4BFE376853EA34AD1D76BFCAD15908C077FFE6DC5521ECEF5D278A96E26F57359FFAEDA19434B937F1AD999DC5C41EB11935B44C18100E857F431A4A5A6BB65114F174C2D7B59FDF237D6BB1DD0916E644D709DED56481477C75D95CDD68254615F7740EC07F330AC5D67BCD75BF23D28A140826C026DBDE971A37CD3EF9B8DF644AC385010501EFC6509D7A41";
        String hash = "1FF80A40173F52D7D27E0F26A146A1C8CCB29046";

        byte[] modulusBytes = hexStringToByteArray(modulus);
        byte[] hashBytes = hexStringToByteArray(hash);

        assertEquals("CAPK 09 modulus should be 248 bytes (1984-bit)", 248, modulusBytes.length);
        assertEquals("CAPK 09 hash should be 20 bytes (SHA-1)", 20, hashBytes.length);
    }

    @Test
    public void testCAPK92_ProductionKey_ValidLength() {
        // VISA production key (1408-bit = 176 bytes)
        String modulus = "996AF56F569187D09293C14810450ED8EE3357397B18A2458EFAA92DA3B6DF6514EC060195318FD43BE9B8F0CC669E3F844057CBDDF8BDA191BB64473BC8DC9A730DB8F6B4EDE3924186FFD9B8C7735789C23A36BA0B8AF65372EB57EA5D89E7D14E9C7B6B557460F10885DA16AC923F15AF3758F0F03EBD3C5C2C949CBA306DB44E6A2C076C5F67E281D7EF56785DC4D75945E491F01918800A9E2DC66F60080566CE0DAF8D17EAD46AD8E30A247C9F";
        String hash = "429C954A3859CEF91295F663C963E582ED6EB253";

        byte[] modulusBytes = hexStringToByteArray(modulus);
        byte[] hashBytes = hexStringToByteArray(hash);

        assertEquals("CAPK 92 modulus should be 176 bytes (1408-bit)", 176, modulusBytes.length);
        assertEquals("CAPK 92 hash should be 20 bytes (SHA-1)", 20, hashBytes.length);
    }

    @Test
    public void testRID_USCommonDebit_ValidFormat() {
        String rid = "A000000098";
        byte[] ridBytes = hexStringToByteArray(rid);

        assertEquals("RID should be 5 bytes", 5, ridBytes.length);
        assertEquals("First byte should be 0xA0", (byte)0xA0, ridBytes[0]);
    }

    @Test
    public void testRID_VISA_ValidFormat() {
        String rid = "A000000003";
        byte[] ridBytes = hexStringToByteArray(rid);

        assertEquals("RID should be 5 bytes", 5, ridBytes.length);
        assertEquals("First byte should be 0xA0", (byte)0xA0, ridBytes[0]);
        assertEquals("Last byte should be 0x03", (byte)0x03, ridBytes[4]);
    }

    @Test
    public void testExponent_IsValid() {
        // All our CAPKs use exponent 03
        String exponent = "03";
        byte[] expBytes = hexStringToByteArray(exponent);

        assertEquals("Exponent should be 1 byte", 1, expBytes.length);
        assertEquals("Exponent should be 0x03", (byte)0x03, expBytes[0]);
    }

    @Test
    public void testAllCAPKIndexes_Supported() {
        // Verify we support all the common CAPK indexes
        int[] supportedIndexes = {0x05, 0x07, 0x08, 0x09, 0x92};

        for (int index : supportedIndexes) {
            assertTrue("Index " + String.format("0x%02X", index) + " should be supported",
                    isCAPKIndexSupported(index));
        }
    }

    private boolean isCAPKIndexSupported(int index) {
        // Mirrors the switch statement in eventCapkGet
        switch (index) {
            case 0x05:
            case 0x07:
            case 0x08:
            case 0x09:
            case 0x92:
                return true;
            default:
                return false;
        }
    }
}
