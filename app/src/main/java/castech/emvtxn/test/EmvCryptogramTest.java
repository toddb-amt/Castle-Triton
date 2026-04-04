package castech.emvtxn.test;

import android.content.Context;
import android.util.Log;

import CTOS.CtEMV;
import CTOS.CtKMS2Dukpt;
import CTOS.CtKMS2FixedKey;
import CTOS.CtKMS2MKSK;
import CTOS.CtKMS2SymmetryKey;
import CTOS.CtKMS2Exception;
import CTOS.emv.EMVSecureDataInfo;

/**
 * Diagnostic test for EMV cryptogram generation issues.
 *
 * Problem: GH111001 is missing cryptogram tags (9F26, 9F27, 9F10, 9F36)
 * because the EMV flow aborts at CVM with error 0x00001003.
 */
public class EmvCryptogramTest {
    private static final String TAG = "EmvCryptogramTest";

    // Key locations to check
    private static final int KEY_SET_C001 = 0xC001;
    private static final int KEY_SET_C000 = 0xC000;    // KEK location
    private static final int KEY_SET_CFFF = 0xCFFF;    // User's key location (TMK/KEK)
    private static final int KEY_INDEX_00A1 = 0x00A1;  // Default SDK expects this
    private static final int KEY_INDEX_001A = 0x001A;  // DUKPT key location
    private static final int KEY_INDEX_0001 = 0x0001;  // MKSK key location (Castle MVP)
    private static final int KEY_INDEX_0000 = 0x0000;  // KEK at C000/0000 or CFFF/0000
    private static final int KEY_INDEX_0010 = 0x0010;  // FutureX slot 10 might map here

    private Context context;
    private StringBuilder results;

    public EmvCryptogramTest(Context context) {
        this.context = context;
        this.results = new StringBuilder();
    }

    public String runAllTests() {
        results.setLength(0);
        log("=== EMV Cryptogram Diagnostic Test ===");
        log("Testing why cryptogram tags are missing...\n");

        testDukptKey();
        testFixedKey();
        testMkskKey();  // Check C001/0001 - Castle MVP MKSK location
        testKekKey();   // Check C000/0000 where processor key should be
        testEmvSecureDataConfig();
        showAnalysis();

        log("\n=== Test Complete ===");
        return results.toString();
    }

    private boolean dukptKeyOk = false;
    private boolean fixedKeyOk = false;
    private boolean kekKeyOk = false;
    private boolean mkskKeyOk = false;
    private String dukptKsn = null;

    private void testDukptKey() {
        log("--- Test 1: DUKPT Key at C001/001A ---");

        CtKMS2Dukpt dukptKey = new CtKMS2Dukpt();
        try {
            dukptKey.selectKey(KEY_SET_C001, KEY_INDEX_001A);
            log("✅ DUKPT Key EXISTS at C001/001A");

            // Get KSN to verify key is usable
            try {
                byte[] ksn = dukptKey.getKSN();
                if (ksn != null && ksn.length >= 10) {
                    dukptKeyOk = true;
                    dukptKsn = bytesToHex(ksn, ksn.length);
                    log("   ✅ Key is USABLE - KSN retrieved");
                    log("   KSN: " + dukptKsn);

                    // Extract counter from KSN
                    int counter = ((ksn[7] & 0x1F) << 16) | ((ksn[8] & 0xFF) << 8) | (ksn[9] & 0xFF);
                    log("   Transaction Counter: " + counter);
                    if (counter >= 1000000) {
                        log("   ⚠️ WARNING: Counter is high - key may be exhausted");
                    }
                } else {
                    log("   ⚠️ KSN is null or too short - key may be invalid");
                    log("   KSN length: " + (ksn == null ? "null" : ksn.length));
                }
            } catch (Exception e) {
                log("   ⚠️ Cannot get KSN: " + e.getMessage());
            }

            // Try to get key attributes
            try {
                int attr = dukptKey.getKeyAttribute();
                log("   Key Attribute: 0x" + String.format("%08X", attr));
            } catch (Exception e) {
                log("   Cannot get attribute: " + e.getMessage());
            }
        } catch (CtKMS2Exception e) {
            log("❌ DUKPT Key MISSING at C001/001A");
            log("   Error: 0x" + String.format("%08X", e.getError()));
        } catch (Exception e) {
            log("❌ Exception: " + e.getMessage());
        }
        log("");
    }

    private void testFixedKey() {
        log("--- Test 2: Fixed Key at C001/00A1 ---");

        CtKMS2FixedKey fixedKey = new CtKMS2FixedKey();
        try {
            fixedKey.selectKey(KEY_SET_C001, KEY_INDEX_00A1);
            fixedKeyOk = true;
            log("✅ Fixed Key EXISTS at C001/00A1");
            log("   This is the default SDK PIN key location");

            // Try to get key attributes
            try {
                int attr = fixedKey.getKeyAttribute();
                log("   Key Attribute: 0x" + String.format("%08X", attr));
                // Decode common attributes
                if ((attr & 0x00000001) != 0) log("   - Master Key");
                if ((attr & 0x00000004) != 0) log("   - PIN Encryption");
                if ((attr & 0x00000008) != 0) log("   - MAC Calculation");
                if ((attr & 0x00000010) != 0) log("   - Data Encryption");
            } catch (Exception e) {
                log("   Cannot get attribute: " + e.getMessage());
            }
        } catch (CtKMS2Exception e) {
            log("❌ Fixed Key MISSING at C001/00A1");
            log("   Error: 0x" + String.format("%08X", e.getError()));
            log("");
            log("   >>> THIS MAY BE THE ROOT CAUSE! <<<");
            log("   SDK expects a PIN encryption key at C001/00A1");
            log("   but no key exists there.");
        } catch (Exception e) {
            log("❌ Exception: " + e.getMessage());
        }
        log("");
    }

    private void testMkskKey() {
        log("--- Test 3: MKSK Key Scan (FutureX slot 10) ---");
        log("   Scanning possible MKSK locations...");

        // Try multiple possible locations for FutureX slot 10
        int[][] mkskLocations = {
            {0xC001, 0x0001},  // Castle MVP
            {0xC001, 0x0010},  // Slot 10 decimal
            {0xC001, 0x000A},  // Slot 10 as 0x0A
            {0x0010, 0x0001},  // KeySet 10
            {0x000A, 0x0001},  // KeySet 0A
            {0xCFFF, 0x0010},  // CFFF with slot 10
            {0xCFFF, 0x000A},  // CFFF with slot 0A
            {0x0010, 0x0010},  // 10/10
            {0x0010, 0x000A},  // 10/0A
            {0xC000, 0x0010},  // C000 with slot 10
            {0xC000, 0x000A},  // C000 with 0A
        };

        int foundKeySet = -1;
        int foundKeyIndex = -1;

        for (int[] loc : mkskLocations) {
            try {
                CtKMS2MKSK testMksk = new CtKMS2MKSK();
                testMksk.selectKey(loc[0], loc[1]);
                // Try a quick encryption test
                byte[] testData = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
                byte[] iv = new byte[8];
                byte[] sk = new byte[24];
                testMksk.setCipherMethod(CtKMS2SymmetryKey.DATA_ENCRYPT_METHOD_ECB);
                testMksk.setICV(iv);
                testMksk.setInputData(testData, 0, testData.length);
                testMksk.setSK(sk);
                byte[] result = testMksk.dataEncrypt();
                if (result != null && result.length >= 8) {
                    foundKeySet = loc[0];
                    foundKeyIndex = loc[1];
                    log(String.format("✅ MKSK WORKING at %04X/%04X!", loc[0], loc[1]));
                    log("   Encrypted: " + bytesToHex(result, result.length));
                    mkskKeyOk = true;
                    break;
                }
            } catch (CtKMS2Exception e) {
                log(String.format("   %04X/%04X: Error 0x%08X", loc[0], loc[1], e.getError()));
            }
        }

        if (foundKeySet < 0) {
            log("❌ No working MKSK key found at scanned locations");
            log("");
            return;
        }

        // Already tested encryption in scan - just log success
        log("");
        log("   >>> MKSK KEY CAN BE USED FOR PIN ENCRYPTION <<<");
        log(String.format("   Use KeySet=0x%04X, KeyIndex=0x%04X", foundKeySet, foundKeyIndex));
        log("");
    }

    private void testKekKey() {
        log("--- Test 4: KEK/Fixed Key at C000/0000 ---");
        log("   (Processor key should be injected here)");

        CtKMS2FixedKey kekKey = new CtKMS2FixedKey();
        try {
            kekKey.selectKey(KEY_SET_C000, KEY_INDEX_0000);
            kekKeyOk = true;
            log("✅ KEY EXISTS at C000/0000");

            // Try to get key attributes
            try {
                int attr = kekKey.getKeyAttribute();
                log("   Key Attribute: 0x" + String.format("%08X", attr));
                // Decode common attributes
                if ((attr & 0x00000001) != 0) log("   - Master Key");
                if ((attr & 0x00000004) != 0) log("   - PIN Encryption (GOOD for SDK PIN!)");
                if ((attr & 0x00000008) != 0) log("   - MAC Calculation");
                if ((attr & 0x00000010) != 0) log("   - Data Encryption/Decryption");
                if ((attr & 0x00000020) != 0) log("   - KBPK (Key Block Protection Key)");
            } catch (Exception e) {
                log("   Cannot get attribute: " + e.getMessage());
            }

            // Try to get key length
            try {
                int keyLen = kekKey.getKeyLength();
                log("   Key Length: " + keyLen + " bytes");
            } catch (Exception e) {
                log("   Cannot get key length: " + e.getMessage());
            }

            // Try to get key type
            try {
                byte keyType = kekKey.getKeyType();
                log("   Key Type: 0x" + String.format("%02X", keyType));
                if (keyType == 0x00) log("   - Type: DES");
                if (keyType == 0x01) log("   - Type: 3DES (2-key)");
                if (keyType == 0x02) log("   - Type: 3DES (3-key)");
            } catch (Exception e) {
                log("   Cannot get key type: " + e.getMessage());
            }

        } catch (CtKMS2Exception e) {
            log("❌ KEY MISSING at C000/0000");
            log("   Error: 0x" + String.format("%08X", e.getError()));
            log("");
            log("   Processor key has NOT been injected at C000/0000.");
            log("   Check Key Injection Tool process.");
        } catch (Exception e) {
            log("❌ Exception: " + e.getMessage());
        }
        log("");
    }

    private void testEmvSecureDataConfig() {
        log("--- Test 5: EMV Secure Data Configuration ---");

        try {
            CtEMV emv = new CtEMV();

            EMVSecureDataInfo secureInfo = new EMVSecureDataInfo();
            secureInfo.version = 2;
            secureInfo.keyType = (byte) 2;  // DUKPT
            secureInfo.cipherKeySet = KEY_SET_C001;
            secureInfo.cipherKeyIndex = KEY_INDEX_001A;
            secureInfo.cipherMethod = 0x01;  // CBC
            secureInfo.checksumType = 0;
            secureInfo.ICVLen = 8;
            secureInfo.ICV = new byte[8];
            secureInfo.paddingMethod = 1;

            int result = emv.secureDataEncryptInfoSet(secureInfo);

            if (result == 0) {
                log("✅ EMV secureDataEncryptInfoSet SUCCESS");
                log("   KeyType: DUKPT (2)");
                log("   KeySet/Index: C001/001A");
            } else {
                log("❌ EMV secureDataEncryptInfoSet FAILED");
                log("   Error: 0x" + String.format("%08X", result));
            }
        } catch (Exception e) {
            log("❌ Exception: " + e.getMessage());
        }
        log("");
    }

    private void showAnalysis() {
        log("--- Analysis & Recommendations ---");
        log("");

        // Check MKSK first - this is the Castle MVP solution
        if (mkskKeyOk) {
            log("✅ MKSK KEY AT C001/0001 EXISTS!");
            log("");
            log("Castle MVP approach is available.");
            log("Use CtKMS2MKSK class for PIN encryption instead of CtKMS2FixedKey.");
            log("");
            log("NEXT STEP: Integrate MKSK into eventOnlinePinBlockGet callback");
            log("Use CastleKeyManager.encryptPinWithMkskAtC001() method");
            return;
        }

        if (dukptKeyOk && fixedKeyOk) {
            log("✅ Both keys exist - SDK should work!");
            log("");
            log("If transactions still fail with 0x00001003:");
            log("1. Key at C001/00A1 may have wrong attribute (need PIN Encryption)");
            log("2. SDK may be checking a different key location");
            log("3. Run a transaction to see actual error flow");
            log("");
            log("NEXT STEP: Insert card and attempt transaction");
            log("Check logcat for CVM/PIN errors during txnPerform");
        } else if (!dukptKeyOk && !fixedKeyOk) {
            log("❌ NO KEYS FOUND - Terminal needs key injection!");
            log("");
            log("SOLUTION:");
            log("1. Ask Castle support to inject MKSK key at C001/0001");
            log("2. Or use Key Injection Tool for fixed keys");
        } else if (!fixedKeyOk) {
            log("⚠️ Fixed key at C001/00A1 is MISSING");
            log("");
            log("SDK expects PIN key at C001/00A1 but only DUKPT exists at 001A.");
            log("");
            log("SOLUTIONS:");
            log("A) Ask Castle support to inject MKSK at C001/0001 (recommended)");
            log("B) Inject fixed key at C001/00A1 with PIN attribute");
            log("C) Configure SDK to use DUKPT key at 001A");
        } else if (!dukptKeyOk) {
            log("⚠️ DUKPT key at C001/001A is INVALID");
            log("");
            log("DUKPT key exists but KSN cannot be retrieved.");
            log("Key may need to be re-injected.");
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        results.append(message).append("\n");
    }

    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
