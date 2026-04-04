package castech.emvtxn;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Configuration comparison test.
 * Compares our settings with Castle's working QuickChip sample.
 *
 * Key insight: QuickChip uses C001/001A for DUKPT, we use C000/0000.
 * This may explain the 0x1003 error during PIN operations.
 */
public class ConfigComparisonTest {

    // =========================================================================
    // QuickChip Sample Settings (WORKING)
    // From: DemoApp_QuickChip/app/src/main/java/com/castles/sample/emv/EmvInstance.java
    // =========================================================================

    // EMVSecureDataInfo settings
    static final int QUICKCHIP_SECURE_VERSION = 2;
    static final int QUICKCHIP_KEY_TYPE = 2;  // TDES_DUKPT
    static final int QUICKCHIP_CIPHER_KEY_SET = 0xC001;
    static final int QUICKCHIP_CIPHER_KEY_INDEX = 0x001A;
    static final int QUICKCHIP_CIPHER_METHOD = 1;  // CBC
    static final int QUICKCHIP_CHECKSUM_TYPE = 1;  // SHA1
    static final int QUICKCHIP_ICV_LEN = 16;

    // onGetPINNotify settings
    static final int QUICKCHIP_PIN_KEY_SET = 0xC001;
    static final int QUICKCHIP_PIN_KEY_INDEX = 0x001A;
    static final int QUICKCHIP_PIN_VERSION = 1;

    // =========================================================================
    // Our Current Settings
    // From: GlobalPara.java and MainActivity.java
    // =========================================================================

    // EMVSecureDataInfo settings (current)
    static final int OUR_SECURE_VERSION = 4;  // Changed from 2 to 4
    static final int OUR_KEY_TYPE = 2;  // TDES_DUKPT (same)
    static final int OUR_CIPHER_KEY_SET = 0xC000;  // DIFFERENT!
    static final int OUR_CIPHER_KEY_INDEX = 0x0000;  // DIFFERENT!
    static final int OUR_CIPHER_METHOD = 1;  // CBC (same)
    static final int OUR_CHECKSUM_TYPE = 0;  // DIFFERENT!
    static final int OUR_ICV_LEN = 8;  // DIFFERENT!

    // onGetPINNotify settings (current)
    static final int OUR_PIN_KEY_SET = 0xC000;  // DIFFERENT!
    static final int OUR_PIN_KEY_INDEX = 0x0000;  // DIFFERENT!
    static final int OUR_PIN_VERSION = 1;  // same

    // =========================================================================
    // Tests documenting the differences
    // =========================================================================

    @Test
    public void testSecureVersionDifference() {
        // QuickChip uses version=2, we use version=4
        // Castle support suggested version=4, but QuickChip sample uses version=2
        assertNotEquals("secureInfo.version differs",
            QUICKCHIP_SECURE_VERSION, OUR_SECURE_VERSION);

        System.out.println("secureInfo.version: QuickChip=" + QUICKCHIP_SECURE_VERSION +
            ", Ours=" + OUR_SECURE_VERSION + " <-- DIFFERENT");
    }

    @Test
    public void testKeyLocationDifference() {
        // QuickChip uses C001/001A, we use C000/0000
        // This is likely the cause of 0x1003 error!
        assertNotEquals("cipherKeySet differs",
            QUICKCHIP_CIPHER_KEY_SET, OUR_CIPHER_KEY_SET);
        assertNotEquals("cipherKeyIndex differs",
            QUICKCHIP_CIPHER_KEY_INDEX, OUR_CIPHER_KEY_INDEX);

        System.out.println("Key location: QuickChip=C001/001A, Ours=C000/0000 <-- DIFFERENT!");
        System.out.println("Our IPEK was injected at C000/0000");
        System.out.println("QuickChip expects key at C001/001A");
    }

    @Test
    public void testPinKeyLocationDifference() {
        // onGetPINNotify key location must match secureInfo key location
        assertNotEquals("PIN keySet differs",
            QUICKCHIP_PIN_KEY_SET, OUR_PIN_KEY_SET);
        assertNotEquals("PIN keyIndex differs",
            QUICKCHIP_PIN_KEY_INDEX, OUR_PIN_KEY_INDEX);

        System.out.println("PIN key: QuickChip=C001/001A, Ours=C000/0000 <-- DIFFERENT!");
    }

    @Test
    public void testChecksumTypeDifference() {
        // QuickChip uses SHA1, we use 0
        assertNotEquals("checksumType differs",
            QUICKCHIP_CHECKSUM_TYPE, OUR_CHECKSUM_TYPE);

        System.out.println("checksumType: QuickChip=SHA1(1), Ours=0 <-- DIFFERENT");
    }

    @Test
    public void testIcvLenDifference() {
        // QuickChip uses 16, we use 8
        assertNotEquals("ICVLen differs",
            QUICKCHIP_ICV_LEN, OUR_ICV_LEN);

        System.out.println("ICVLen: QuickChip=16, Ours=8 <-- DIFFERENT");
    }

    @Test
    public void testKeyTypeSame() {
        // Key type should be the same (DUKPT)
        assertEquals("keyType should be same (DUKPT)",
            QUICKCHIP_KEY_TYPE, OUR_KEY_TYPE);

        System.out.println("keyType: Both use DUKPT (2) <-- SAME");
    }

    @Test
    public void testCipherMethodSame() {
        // Cipher method should be the same (CBC)
        assertEquals("cipherMethod should be same (CBC)",
            QUICKCHIP_CIPHER_METHOD, OUR_CIPHER_METHOD);

        System.out.println("cipherMethod: Both use CBC (1) <-- SAME");
    }

    // =========================================================================
    // Summary test
    // =========================================================================

    @Test
    public void printConfigurationSummary() {
        System.out.println("\n========================================");
        System.out.println("CONFIGURATION COMPARISON SUMMARY");
        System.out.println("========================================");
        System.out.println("\nEMVSecureDataInfo:");
        System.out.println("  version:        QuickChip=" + QUICKCHIP_SECURE_VERSION + ", Ours=" + OUR_SECURE_VERSION);
        System.out.println("  keyType:        QuickChip=" + QUICKCHIP_KEY_TYPE + ", Ours=" + OUR_KEY_TYPE);
        System.out.println("  cipherKeySet:   QuickChip=0x" + Integer.toHexString(QUICKCHIP_CIPHER_KEY_SET) +
            ", Ours=0x" + Integer.toHexString(OUR_CIPHER_KEY_SET) + " <-- KEY DIFFERENCE!");
        System.out.println("  cipherKeyIndex: QuickChip=0x" + Integer.toHexString(QUICKCHIP_CIPHER_KEY_INDEX) +
            ", Ours=0x" + Integer.toHexString(OUR_CIPHER_KEY_INDEX) + " <-- KEY DIFFERENCE!");
        System.out.println("  checksumType:   QuickChip=" + QUICKCHIP_CHECKSUM_TYPE + ", Ours=" + OUR_CHECKSUM_TYPE);
        System.out.println("  ICVLen:         QuickChip=" + QUICKCHIP_ICV_LEN + ", Ours=" + OUR_ICV_LEN);

        System.out.println("\nonGetPINNotify:");
        System.out.println("  version:        QuickChip=" + QUICKCHIP_PIN_VERSION + ", Ours=" + OUR_PIN_VERSION);
        System.out.println("  keySet:         QuickChip=0x" + Integer.toHexString(QUICKCHIP_PIN_KEY_SET) +
            ", Ours=0x" + Integer.toHexString(OUR_PIN_KEY_SET) + " <-- KEY DIFFERENCE!");
        System.out.println("  keyIndex:       QuickChip=0x" + Integer.toHexString(QUICKCHIP_PIN_KEY_INDEX) +
            ", Ours=0x" + Integer.toHexString(OUR_PIN_KEY_INDEX) + " <-- KEY DIFFERENCE!");

        System.out.println("\n========================================");
        System.out.println("RECOMMENDATION:");
        System.out.println("========================================");
        System.out.println("1. Re-inject DUKPT IPEK at C001/001A (not C000/0000)");
        System.out.println("2. Change secureInfo.version from 4 to 2");
        System.out.println("3. Change checksumType to SHA1 (1)");
        System.out.println("4. Change ICVLen from 8 to 16");
        System.out.println("5. Update onGetPINNotify to use C001/001A");
        System.out.println("========================================\n");

        assertTrue(true);  // Always pass - this is informational
    }
}
