package castech.emvtxn.atm.host;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for EmvTagEnhancer.
 * Verifies TVR preservation and tag ordering for ATM transactions.
 */
public class EmvTagEnhancerTest {

    // =========================================================================
    // TVR Preservation Tests (Critical for ARQC validation)
    // =========================================================================

    @Test
    public void testTvrPreservedInAtmMode() {
        // TVR is part of ARQC calculation - it MUST NOT be modified after card generates cryptogram
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        // Sample EMV data with TVR = 8080108000 (byte 3 = 0x10, PIN pad not present)
        // This is what the card used to generate the ARQC
        String inputEmv = "9F0206000000002000" +  // Amount: $20.00
                          "9F2608FDBBFF4A279AFFD4" +  // ARQC (cryptogram)
                          "9505" + "8080108000" +  // TVR: byte 3 = 0x10
                          "9F2701" + "80" +  // CID: ARQC
                          "9C0130";  // Transaction Type: Cash

        // ATM mode should preserve TVR (not modify it)
        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                true,   // isCashWithdrawal
                true);  // isAtmMode

        // TVR should be UNCHANGED - same as input
        assertTrue("TVR must be preserved for ARQC validation",
                   result.contains("95058080108000"));
    }

    @Test
    public void testTvrNotModifiedToOnlinePinEntered() {
        // Previously we were changing TVR byte 3 from 0x10 to 0x04
        // This broke ARQC validation because the card used original TVR
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        String inputEmv = "9505" + "8080108000" +  // Original TVR
                          "9F2608ABCDEF1234567890";

        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                true, true);

        // Should NOT contain modified TVR (0x04 in byte 3)
        assertFalse("TVR byte 3 should NOT be changed to 0x04",
                    result.contains("95058080048000"));

        // Should contain original TVR
        assertTrue("Original TVR must be preserved",
                   result.contains("95058080108000"));
    }

    // =========================================================================
    // CVM Override Tests (9F34 is safe to modify - not in ARQC)
    // =========================================================================

    @Test
    public void testCvmResultsOverriddenInAtmMode() {
        // 9F34 (CVM Results) is overridden for processor compatibility
        // Config uses No-CVM but processor expects Online PIN claims
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        String inputEmv = "9F3403" + "1F0002" +  // Original CVM: No CVM performed
                          "9F2608ABCDEF1234567890";

        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                true, true);

        // 9F34 should be overridden to 420000 (Online PIN verified)
        assertTrue("9F34 should be 420000 in ATM mode",
                   result.contains("9F3403420000"));
    }

    @Test
    public void testTerminalCapsOverriddenInAtmMode() {
        // 9F33 (Terminal Capabilities) is overridden for processor compatibility
        // Config uses E008C8 (No-CVM) but processor expects E040C8 (Online PIN)
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        String inputEmv = "9F3303" + "E008C8" +  // Original: No CVM Required
                          "9F2608ABCDEF1234567890";

        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                true, true);

        // 9F33 should be overridden to E040C8 (Online PIN)
        assertTrue("9F33 should be E040C8 in ATM mode",
                   result.contains("9F3303E040C8"));
    }

    // =========================================================================
    // Non-ATM Mode Tests
    // =========================================================================

    @Test
    public void testNonAtmModePreservesCvmResults() {
        // In non-ATM mode, 9F34 should NOT be overridden
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        String inputEmv = "9F3403" + "1F0002" +
                          "9F2608ABCDEF1234567890";

        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_PURCHASE,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                false,   // not cash withdrawal
                false);  // not ATM mode

        // 9F34 should be preserved as original
        assertTrue("9F34 should be preserved in non-ATM mode",
                   result.contains("9F34031F0002"));
    }

    // =========================================================================
    // Tag Ordering Tests
    // =========================================================================

    @Test
    public void testTagOrderMatchesGH001038() {
        // Tags should be ordered to match working terminal GH001038
        // Expected order: 9F02, 9F26, 95, 9F27, 9C, ...
        EmvTagEnhancer enhancer = new EmvTagEnhancer();

        // Input with tags in wrong order
        String inputEmv = "9C0130" +              // Transaction Type (should be 5th)
                          "9F0206000000002000" +  // Amount (should be 1st)
                          "95058080108000" +      // TVR (should be 3rd)
                          "9F2608FDBBFF4A279AFFD4" + // ARQC (should be 2nd)
                          "9F270180";             // CID (should be 4th)

        String result = enhancer.enhanceEmvData(inputEmv,
                EmvTagEnhancer.TXN_TYPE_CASH,
                EmvTagEnhancer.POS_ENTRY_CHIP,
                true, true);

        // Verify order: 9F02 should come before 9F26
        int pos9F02 = result.indexOf("9F0206");
        int pos9F26 = result.indexOf("9F2608");
        int pos95 = result.indexOf("9505");
        int pos9F27 = result.indexOf("9F2701");
        int pos9C = result.indexOf("9C01");

        assertTrue("9F02 should come before 9F26", pos9F02 < pos9F26);
        assertTrue("9F26 should come before 95", pos9F26 < pos95);
        assertTrue("95 should come before 9F27", pos95 < pos9F27);
        assertTrue("9F27 should come before 9C", pos9F27 < pos9C);
    }
}
