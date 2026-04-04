package castech.emvtxn.atm.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import CTOS.CtKMS2Key;
import CTOS.CtKMS2TR31;
import CTOS.CtKMS2CKBB;
import CTOS.CtKMS2MKSK;
import CTOS.CtKMS2FixedKey;
import CTOS.CtKMS2Exception;
import CTOS.CtKMS2System;
import CTOS.CtKMS2SymmetryKey;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Castle Key Manager
 *
 * Manages PIN encryption keys for ATM transactions.
 * Integrates with Castle KMS2 for secure key storage and PIN block generation.
 *
 * Key Types:
 * - TMK (Terminal Master Key): Used to decrypt working keys from host
 * - TWK (Terminal Working Key): Used to encrypt PIN blocks
 *
 * This class abstracts the Castle KMS2 API and provides a cleaner interface
 * for the ATM host integration.
 */
public class CastleKeyManager {

    private static final String TAG = "CastleKeyManager";

    // Key slot identifiers (matching GlobalPara) - C000/0000 where DUKPT is injected
    public static final int DEFAULT_ONLINE_PIN_KEY_SET = 0x0000C000;
    public static final int DEFAULT_ONLINE_PIN_KEY_INDEX = 0x00000000;

    // MKSK (Master Key / Session Key) location - FutureX slot 10 maps here
    // This is an alternative to FixedKey that has proper MKSK attribute for encryption
    public static final int MKSK_KEY_SET = 0xC000;
    public static final int MKSK_KEY_INDEX = 0x0010;

    // KBPK (Key Block Protection Key) location - used to unwrap TR-31 keys
    // Must be pre-loaded via Key Injection Tool before downloading working keys
    // Default location: CFFF/0000 (as per Key Injection Tool manual)
    public static final int DEFAULT_TMK_KEY_SET = 0x0000CFFF;
    public static final int DEFAULT_TMK_KEY_INDEX = 0x00000000;

    // Key persistence settings
    private static final String PREFS_NAME = "atm_key_storage";
    private static final String PREF_KEY_DATA = "working_key";
    private static final String PREF_KEY_TIMESTAMP = "key_timestamp";
    private static final String PREF_KEY_KCV = "key_kcv";
    private static final long KEY_EXPIRY_MS = 4 * 60 * 60 * 1000; // 4 hours in milliseconds
    private static final long KEY_RENEWAL_THRESHOLD_MS = 30 * 60 * 1000; // 30 minutes - renew proactively

    // Key states
    private boolean initialized;
    private boolean workingKeyLoaded;
    private String currentWorkingKeyCheckValue;

    // Encrypted working key (for MKSK approach)
    // Instead of storing decrypted key, we store encrypted and use MKSK to decrypt on-the-fly
    private byte[] encryptedWorkingKey;

    // Clear working key for software-based encryption (fallback when KMS2 fails)
    // NOTE: Less secure than hardware - use only when KMS2 key attributes block hardware crypto
    private byte[] softwareWorkingKey;
    private boolean useSoftwareEncryption = false;

    // Software TMK for decrypting working keys when KMS2 TMK has wrong attribute
    // The TMK is XOR of Key Part A and Key Part B from processor
    // This enables software decryption when hardware fails with error 0x2907
    private byte[] softwareTmk = null;

    // Configuration
    private int pinKeySet;
    private int pinKeyIndex;
    private int tmkKeySet;
    private int tmkKeyIndex;

    // Context for Castle SDK
    private Context context;

    // Listener for key events
    private KeyEventListener listener;

    /**
     * Creates a new CastleKeyManager.
     *
     * @param context Android context
     */
    public CastleKeyManager(Context context) {
        this.context = context;
        this.pinKeySet = DEFAULT_ONLINE_PIN_KEY_SET;
        this.pinKeyIndex = DEFAULT_ONLINE_PIN_KEY_INDEX;
        this.tmkKeySet = DEFAULT_TMK_KEY_SET;
        this.tmkKeyIndex = DEFAULT_TMK_KEY_INDEX;
        this.initialized = false;
        this.workingKeyLoaded = false;
    }

    /**
     * Sets the TMK (Terminal Master Key) location.
     * The TMK is used to decrypt working keys received from the host.
     */
    public void setTmkSlot(int keySet, int keyIndex) {
        this.tmkKeySet = keySet;
        this.tmkKeyIndex = keyIndex;
    }

    /**
     * Sets the software TMK value for software-based decryption.
     * Use this when the KMS2 hardware TMK has wrong attribute (0x00000010)
     * and fails with error 0x2907 during key decryption.
     *
     * The TMK should be the XOR of Key Part A and Key Part B from the processor.
     *
     * @param tmkHex The TMK as 32 hex characters (16 bytes for double-length 3DES)
     * @return true if TMK was set successfully
     */
    public boolean setSoftwareTmk(String tmkHex) {
        if (tmkHex == null || tmkHex.length() != 32) {
            Log.e(TAG, "Invalid software TMK format: expected 32 hex chars, got " +
                      (tmkHex != null ? tmkHex.length() : "null"));
            return false;
        }

        try {
            this.softwareTmk = hexStringToBytes(tmkHex);
            Log.d(TAG, "Software TMK set: " + maskKey(tmkHex));

            // Calculate and log KCV for verification
            String kcv = calculateSoftwareKcv(softwareTmk);
            Log.d(TAG, "Software TMK KCV: " + kcv);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set software TMK: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if software TMK is available for fallback decryption.
     */
    public boolean hasSoftwareTmk() {
        return softwareTmk != null && softwareTmk.length == 16;
    }

    /**
     * Sets custom key slot identifiers.
     */
    public void setKeySlot(int keySet, int keyIndex) {
        this.pinKeySet = keySet;
        this.pinKeyIndex = keyIndex;
    }

    /**
     * Sets the key event listener.
     */
    public void setKeyEventListener(KeyEventListener listener) {
        this.listener = listener;
    }

    /**
     * Initializes the key manager.
     * Must be called before any key operations.
     * Attempts to load persisted key if available and not expired.
     *
     * @return true if initialization successful
     */
    public boolean initialize() {
        try {
            Log.d(TAG, "Initializing CastleKeyManager...");

            // Initialize KMS2 system
            try {
                CtKMS2System kmsSystem = new CtKMS2System();
                kmsSystem.init();
                Log.d(TAG, "KMS2 system initialized");
            } catch (CtKMS2Exception e) {
                Log.w(TAG, "KMS2 init: " + String.format("0x%08X", e.getError()));
            }

            // Check configured key locations
            boolean tmkExists = checkKeyExists(tmkKeySet, tmkKeyIndex);
            boolean pinKeyExists = checkKeyExists(pinKeySet, pinKeyIndex);

            Log.d(TAG, "TMK at " + String.format("0x%04X/0x%04X", tmkKeySet, tmkKeyIndex) + ": " + tmkExists);
            Log.d(TAG, "PIN key at " + String.format("0x%04X/0x%04X", pinKeySet, pinKeyIndex) + ": " + pinKeyExists);

            // Check MKSK key from Castle MVP (alternative approach)
            boolean mkskExists = checkKeyExists(MKSK_KEY_SET, MKSK_KEY_INDEX);
            Log.d(TAG, "MKSK at " + String.format("0x%04X/0x%04X", MKSK_KEY_SET, MKSK_KEY_INDEX) + ": " + mkskExists);

            if (mkskExists) {
                // Test MKSK encryption (Castle MVP approach)
                testMkskKeyAtC001();
            }

            if (pinKeyExists) {
                workingKeyLoaded = true;
            }

            // Try to load persisted software key (4-hour persistence)
            if (!workingKeyLoaded) {
                if (loadKeyFromPrefs()) {
                    Log.d(TAG, "Restored working key from persistence");
                    notifyKeyLoaded();
                }
            }

            initialized = true;
            Log.d(TAG, "CastleKeyManager initialized, workingKeyLoaded=" + workingKeyLoaded);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks if a key exists at the specified location.
     */
    public boolean checkKeyExists(int keySet, int keyIndex) {
        try {
            CtKMS2Key key = new CtKMS2Key();
            key.selectKey(keySet, keyIndex);
            // If we get here, the key exists
            Log.d(TAG, "Key found at " + String.format("%04X/%04X", keySet, keyIndex) +
                      " Type=" + String.format("0x%02X", key.getKeyType()) +
                      " Attr=" + String.format("0x%08X", key.getKeyAttribute()));
            return true;
        } catch (CtKMS2Exception e) {
            Log.d(TAG, "No key at " + String.format("%04X/%04X", keySet, keyIndex) +
                      " Error=" + String.format("0x%08X", e.getError()));
            return false;
        }
    }

    /**
     * Gets key info for debugging.
     */
    public String getKeyInfo(int keySet, int keyIndex) {
        try {
            CtKMS2Key key = new CtKMS2Key();
            key.selectKey(keySet, keyIndex);
            return String.format("KeySet=%04X Index=%04X Type=0x%02X Attr=0x%08X Len=%d",
                    key.getKeySet(), key.getKeyIndex(), key.getKeyType(),
                    key.getKeyAttribute(), key.getKeyLength());
        } catch (CtKMS2Exception e) {
            return "Key not found: " + String.format("0x%08X", e.getError());
        }
    }

    /**
     * Loads a working key received from the host.
     *
     * @param configResponse The configuration response containing the key
     * @return true if key was loaded successfully
     */
    public boolean loadWorkingKey(ConfigResponse configResponse) {
        if (!initialized) {
            Log.e(TAG, "Key manager not initialized");
            return false;
        }

        // Log what we received
        Log.d(TAG, "loadWorkingKey: Received config response");
        Log.d(TAG, "  Terminal ID: " + configResponse.getTerminalId());
        Log.d(TAG, "  Surcharge: " + configResponse.getSurchargeCents() + " cents");
        Log.d(TAG, "  Key Part 1: " + maskKey(configResponse.getWorkingKeyPart1()));
        Log.d(TAG, "  Key Part 2: " + maskKey(configResponse.getWorkingKeyPart2()));
        Log.d(TAG, "  Is TR-31: " + configResponse.isTr31Format());

        if (configResponse.isTr31Format()) {
            Log.d(TAG, "  TR-31 Version: " + configResponse.getTr31Version());
            Log.d(TAG, "  TR-31 Key Usage: " + configResponse.getTr31KeyUsage());
            Log.d(TAG, "  TR-31 Algorithm: " + configResponse.getTr31Algorithm());
        }

        try {
            if (configResponse.isTr31Format()) {
                return loadTr31Key(configResponse.getTr31KeyBlock());
            } else {
                return loadStandardKey(configResponse.getCombinedWorkingKey());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load working key: " + e.getMessage(), e);
            notifyKeyError("Failed to load working key: " + e.getMessage());
            return false;
        }
    }

    /**
     * Masks a key for logging (shows first 4 and last 4 chars only).
     */
    private String maskKey(String key) {
        if (key == null) return "null";
        if (key.length() <= 8) return key;
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Loads a standard format working key (two 16-hex parts).
     *
     * Traditional ATM Key Exchange (Hyosung STD1):
     * 1. TMK (Terminal Master Key) is pre-loaded at terminal
     * 2. Processor sends Field 5 (Key Part A) and Field 8 (Key Part B)
     * 3. Each part is encrypted under the TMK
     * 4. Terminal decrypts each part with TMK
     * 5. Decrypted parts are concatenated to form the Working Key
     *
     * @param combinedKey The 32-hex character key (encrypted or clear)
     * @return true if key was loaded successfully
     */
    private boolean loadStandardKey(String combinedKey) {
        if (combinedKey == null || combinedKey.length() != 32) {
            Log.e(TAG, "Invalid standard key format: " +
                      (combinedKey == null ? "null" : "length=" + combinedKey.length()));
            return false;
        }

        Log.d(TAG, "=== Standard ATM Key Exchange ===");
        Log.d(TAG, "  Received key: " + maskKey(combinedKey));
        Log.d(TAG, "  TMK location: " + String.format("0x%04X/0x%04X", tmkKeySet, tmkKeyIndex));

        // Split into two parts (as received from processor)
        String keyPartA = combinedKey.substring(0, 16);
        String keyPartB = combinedKey.substring(16, 32);
        Log.d(TAG, "  Key Part A: " + maskKey(keyPartA));
        Log.d(TAG, "  Key Part B: " + maskKey(keyPartB));

        // Try to decrypt using TMK (traditional ATM approach)
        // Method 1: Hardware TMK decryption via KMS2
        if (checkKeyExists(tmkKeySet, tmkKeyIndex)) {
            Log.d(TAG, "  TMK exists - attempting HARDWARE key decryption");

            String decryptedKey = decryptKeyPartsWithTmk(keyPartA, keyPartB);
            if (decryptedKey != null) {
                Log.d(TAG, "  Hardware TMK decryption SUCCESS");
                this.softwareWorkingKey = hexStringToBytes(decryptedKey);
                this.useSoftwareEncryption = true;
                this.workingKeyLoaded = true;
                this.currentWorkingKeyCheckValue = "HW-TMK";

                String kcv = calculateSoftwareKcv(softwareWorkingKey);
                Log.d(TAG, "  Decrypted key KCV: " + kcv);

                saveKeyToPrefs();
                notifyKeyLoaded();
                return true;
            } else {
                Log.w(TAG, "  Hardware TMK decryption failed (likely 0x2907 - wrong key attribute)");
            }
        } else {
            Log.d(TAG, "  No hardware TMK found at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));
        }

        // Method 2: Software TMK decryption (fallback when hardware fails due to attribute)
        if (hasSoftwareTmk()) {
            Log.d(TAG, "  Attempting SOFTWARE TMK decryption...");

            String decryptedKey = decryptKeyPartsWithSoftwareTmk(keyPartA, keyPartB);
            if (decryptedKey != null) {
                Log.d(TAG, "  Software TMK decryption SUCCESS");
                this.softwareWorkingKey = hexStringToBytes(decryptedKey);
                this.useSoftwareEncryption = true;
                this.workingKeyLoaded = true;
                this.currentWorkingKeyCheckValue = "SW-TMK";

                String kcv = calculateSoftwareKcv(softwareWorkingKey);
                Log.d(TAG, "  Decrypted working key KCV: " + kcv);

                saveKeyToPrefs();
                notifyKeyLoaded();
                return true;
            } else {
                Log.e(TAG, "  Software TMK decryption failed");
            }
        } else {
            Log.w(TAG, "  No software TMK configured - call setSoftwareTmk() first");
        }

        // Fallback: Use key as-is (ONLY if processor sends CLEAR keys, which is rare)
        Log.w(TAG, "=== WARNING: Using key as CLEAR (no TMK decryption) ===");
        Log.w(TAG, "  This is likely WRONG if processor encrypts working keys under TMK!");
        Log.w(TAG, "  Configure software TMK with setSoftwareTmk() if keys are encrypted.");
        this.softwareWorkingKey = hexStringToBytes(combinedKey);
        this.useSoftwareEncryption = true;
        this.workingKeyLoaded = true;
        this.currentWorkingKeyCheckValue = "CLEAR";

        String kcv = calculateSoftwareKcv(softwareWorkingKey);
        Log.d(TAG, "  Clear key KCV: " + kcv);

        saveKeyToPrefs();
        notifyKeyLoaded();
        return true;
    }

    /**
     * Decrypts two key parts using the SOFTWARE TMK (fallback when KMS2 fails).
     * Uses Java 3DES decryption with the known TMK value.
     *
     * @param keyPartA First 16 hex chars (encrypted Key Part A)
     * @param keyPartB Second 16 hex chars (encrypted Key Part B)
     * @return Decrypted 32 hex char working key, or null on failure
     */
    private String decryptKeyPartsWithSoftwareTmk(String keyPartA, String keyPartB) {
        if (softwareTmk == null || softwareTmk.length != 16) {
            Log.e(TAG, "Software TMK not set or invalid length");
            return null;
        }

        try {
            Log.d(TAG, "=== Software TMK Decryption ===");
            Log.d(TAG, "  TMK KCV: " + calculateSoftwareKcv(softwareTmk));

            // Decrypt Part A (8 bytes)
            byte[] partABytes = hexStringToBytes(keyPartA);
            byte[] decryptedA = software3desDecrypt(softwareTmk, partABytes);

            if (decryptedA == null || decryptedA.length < 8) {
                Log.e(TAG, "  Part A decryption failed");
                return null;
            }
            Log.d(TAG, "  Part A decrypted: " + maskKey(bytesToHexString(decryptedA)));

            // Decrypt Part B (8 bytes)
            byte[] partBBytes = hexStringToBytes(keyPartB);
            byte[] decryptedB = software3desDecrypt(softwareTmk, partBBytes);

            if (decryptedB == null || decryptedB.length < 8) {
                Log.e(TAG, "  Part B decryption failed");
                return null;
            }
            Log.d(TAG, "  Part B decrypted: " + maskKey(bytesToHexString(decryptedB)));

            // Combine decrypted parts to form working key
            String combined = bytesToHexString(decryptedA) + bytesToHexString(decryptedB);
            Log.d(TAG, "  Combined working key: " + maskKey(combined));

            return combined;

        } catch (Exception e) {
            Log.e(TAG, "Software TMK decryption error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Performs 3DES ECB decryption in software.
     */
    private byte[] software3desDecrypt(byte[] key, byte[] data) {
        try {
            // Ensure key is 24 bytes for 3DES (if 16 bytes, expand to 24 by repeating first 8)
            byte[] key24;
            if (key.length == 16) {
                key24 = new byte[24];
                System.arraycopy(key, 0, key24, 0, 16);
                System.arraycopy(key, 0, key24, 16, 8);
            } else if (key.length == 24) {
                key24 = key;
            } else {
                Log.e(TAG, "Invalid key length for 3DES: " + key.length);
                return null;
            }

            SecretKeySpec keySpec = new SecretKeySpec(key24, "DESede");
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(data);

        } catch (Exception e) {
            Log.e(TAG, "Software 3DES decryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decrypts two key parts using the TMK (Terminal Master Key).
     * This is the traditional ATM key exchange method.
     *
     * Each 8-byte part is decrypted separately under TMK, then concatenated.
     *
     * @param keyPartA First 16 hex chars (encrypted Key Part A)
     * @param keyPartB Second 16 hex chars (encrypted Key Part B)
     * @return Decrypted 32 hex char working key, or null on failure
     */
    private String decryptKeyPartsWithTmk(String keyPartA, String keyPartB) {
        try {
            Log.d(TAG, "=== Decrypting Key Parts with TMK ===");

            CtKMS2FixedKey tmk = new CtKMS2FixedKey();
            tmk.selectKey(tmkKeySet, tmkKeyIndex);
            tmk.setCipherMethod((byte) 0x00);  // ECB mode

            // Decrypt Part A
            byte[] partABytes = hexStringToBytes(keyPartA);
            tmk.setInputData(partABytes, 0, partABytes.length);
            tmk.dataDecrypt();
            byte[] decryptedA = tmk.getOutpuData();

            if (decryptedA == null || decryptedA.length < 8) {
                Log.e(TAG, "  Part A decryption failed");
                return null;
            }
            Log.d(TAG, "  Part A decrypted: " + maskKey(bytesToHexString(decryptedA)));

            // Decrypt Part B
            byte[] partBBytes = hexStringToBytes(keyPartB);
            tmk.setInputData(partBBytes, 0, partBBytes.length);
            tmk.dataDecrypt();
            byte[] decryptedB = tmk.getOutpuData();

            if (decryptedB == null || decryptedB.length < 8) {
                Log.e(TAG, "  Part B decryption failed");
                return null;
            }
            Log.d(TAG, "  Part B decrypted: " + maskKey(bytesToHexString(decryptedB)));

            // Combine decrypted parts
            String combined = bytesToHexString(decryptedA) + bytesToHexString(decryptedB);
            Log.d(TAG, "  Combined working key: " + maskKey(combined));

            return combined;

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "TMK decryption failed: " + String.format("0x%08X", e.getError()));
            return null;
        } catch (Exception e) {
            Log.e(TAG, "TMK decryption error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tests if the encrypted working key can be used with MKSK.
     * Does a simple data encryption operation to verify the key works.
     */
    private boolean testMkskKey() {
        if (encryptedWorkingKey == null || encryptedWorkingKey.length != 16) {
            Log.e(TAG, "No encrypted working key available for MKSK test");
            return false;
        }

        try {
            Log.d(TAG, "=== Testing MKSK Key ===");

            CtKMS2MKSK mksk = new CtKMS2MKSK();

            // Select TMK as master key
            mksk.selectKey(tmkKeySet, tmkKeyIndex);
            Log.d(TAG, "  Selected TMK at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));

            // Set the encrypted working key as session key
            mksk.setSK(encryptedWorkingKey, 0, encryptedWorkingKey.length);
            Log.d(TAG, "  Set encrypted session key (" + encryptedWorkingKey.length + " bytes)");

            // Set cipher method (ECB = 0x00)
            mksk.setCipherMethod((byte) 0x00);

            // Try to encrypt 8 bytes of zeros as a test
            byte[] testData = new byte[8];
            mksk.setInputData(testData, 0, testData.length);

            // Do encryption
            mksk.dataEncrypt();
            byte[] result = mksk.getOutpuData();

            if (result != null && result.length > 0) {
                Log.d(TAG, "  MKSK test encryption SUCCESS");
                Log.d(TAG, "  Test output: " + bytesToHexString(result));
                return true;
            } else {
                Log.w(TAG, "  MKSK test encryption returned empty result");
                return false;
            }

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "MKSK test failed: " + String.format("0x%08X", e.getError()));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "MKSK test error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to load the encrypted key using CKBB format.
     */
    private boolean loadCkbbKey(String encryptedKey) {
        try {
            Log.d(TAG, "=== Trying CKBB Key Loading ===");

            byte[] keyBytes = hexStringToBytes(encryptedKey);
            Log.d(TAG, "  Key bytes length: " + keyBytes.length);

            CtKMS2CKBB ckbb = new CtKMS2CKBB();

            // Select the TMK (cipher key) for decryption
            ckbb.selectKey(tmkKeySet, tmkKeyIndex);
            Log.d(TAG, "  Selected TMK at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));

            // Set destination for unwrapped key
            ckbb.setKeyLocation(pinKeySet, pinKeyIndex);
            Log.d(TAG, "  Target: " + String.format("%04X/%04X", pinKeySet, pinKeyIndex));

            // Set the CKBB key block (trying raw encrypted bytes)
            ckbb.setCKBBKeyBlock(keyBytes, 0, keyBytes.length);
            Log.d(TAG, "  Set CKBB key block (" + keyBytes.length + " bytes)");

            // Write the key
            ckbb.writeKey();
            Log.d(TAG, "  CKBB writeKey completed!");

            // Verify
            if (checkKeyExists(pinKeySet, pinKeyIndex)) {
                workingKeyLoaded = true;
                currentWorkingKeyCheckValue = "CKBB";
                Log.d(TAG, "=== CKBB Key Loading SUCCESS ===");
                notifyKeyLoaded();
                return true;
            } else {
                Log.w(TAG, "  CKBB writeKey succeeded but key not found at target");
                return false;
            }

        } catch (CtKMS2Exception e) {
            Log.w(TAG, "CKBB key loading failed: " + String.format("0x%08X", e.getError()));
            return false;
        } catch (Exception e) {
            Log.w(TAG, "CKBB key loading error: " + e.getMessage());
            return false;
        }
    }


    /**
     * Scans common key locations to help with debugging.
     * Returns the first found key location, or null if none found.
     */
    private int[] scanForKeys() {
        int[][] commonLocations = getCommonKeyLocations();

        int[] firstFound = null;
        for (int[] loc : commonLocations) {
            if (checkKeyExists(loc[0], loc[1])) {
                Log.d(TAG, "  Key found at " + String.format("0x%04X/0x%04X", loc[0], loc[1]) +
                          " - " + getKeyInfo(loc[0], loc[1]));
                if (firstFound == null) {
                    firstFound = loc;
                }
            }
        }
        return firstFound;
    }

    /**
     * Gets list of common key locations to scan.
     */
    private int[][] getCommonKeyLocations() {
        return new int[][] {
            // DUKPT Geobridge slots - DUKPT(1A), DUKPT(1B), etc.
            {0xC001, 0x001A},  // DUKPT(1A) - CastlesHost sample
            {0xC001, 0x001B},  // DUKPT(1B)
            {0xC001, 0x001C},  // DUKPT(1C)
            {0xC001, 0x0001},  // DUKPT(1)
            {0xC001, 0x0002},  // DUKPT(2)
            {0x0001, 0x001A},  // Alternative DUKPT(1A)
            {0x0001, 0x000A},  // DUKPT slot A
            {0x0001, 0x0001},  // DUKPT slot 1

            // KEK/KBPK locations
            {0xC000, 0x0000},  // KEK for Geobridge
            {0xCFFF, 0x0000},  // KBPK (Key Block Protection Key)

            // Common PIN key locations
            {0xC001, 0x00A1},  // Default online PIN key
            {0xC002, 0x00A1},  // Alternative PIN key
            {0xC000, 0x0001},  // Legacy TMK location
            {0xC000, 0x0002},  // Alternative TMK

            // Test/development locations
            {0x0000, 0x0001},  // Zero keyset
            {0x0000, 0x0002},  // Zero keyset 2
            {0x0000, 0x001A},  // Zero keyset DUKPT(1A)
            {0x0010, 0x0001},  // Alternative keyset
            {0x0010, 0x001A},  // Alternative keyset DUKPT
            {0x00F1, 0x0022},  // Sample code location

            // More DUKPT variations
            {0xC001, 0x0010},  // DUKPT index 16
            {0xC001, 0x0011},  // DUKPT index 17
            {0xC001, 0x000A},  // DUKPT index 10 (A decimal)
            {0xC001, 0x000B},  // DUKPT index 11
        };
    }

    /**
     * Performs a full key scan and returns a formatted report.
     * Use this for diagnostics to see all keys on the terminal.
     */
    public String getKeyDiagnosticReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== KEY DIAGNOSTIC REPORT ===\n\n");

        int[][] locations = getCommonKeyLocations();
        int foundCount = 0;

        for (int[] loc : locations) {
            boolean exists = checkKeyExists(loc[0], loc[1]);
            if (exists) {
                foundCount++;
                String info = getKeyInfo(loc[0], loc[1]);
                report.append(String.format("FOUND: 0x%04X/0x%04X\n", loc[0], loc[1]));
                report.append("  " + info + "\n\n");
            }
        }

        if (foundCount == 0) {
            report.append("NO KEYS FOUND!\n\n");
            report.append("The terminal has no keys at common locations.\n");
            report.append("Key injection via Key Injection Facility is required.\n");
        } else {
            report.append("Total keys found: " + foundCount + "\n");
        }

        report.append("\n=== END REPORT ===");
        return report.toString();
    }

    /**
     * Attempts to find and use any available PIN key on the terminal.
     * This is useful for test environments where a key might be pre-loaded
     * at a non-standard location.
     *
     * @return true if a usable key was found and configured
     */
    public boolean findAndUseAvailableKey() {
        Log.d(TAG, "Searching for any available PIN key...");

        int[] foundKey = scanForKeys();
        if (foundKey != null) {
            Log.d(TAG, "Found key at " + String.format("0x%04X/0x%04X", foundKey[0], foundKey[1]));
            Log.d(TAG, "Configuring to use this key for PIN encryption");

            // Update the key location
            this.pinKeySet = foundKey[0];
            this.pinKeyIndex = foundKey[1];
            this.workingKeyLoaded = true;
            this.currentWorkingKeyCheckValue = "FOUND";

            notifyKeyLoaded();
            return true;
        }

        Log.e(TAG, "No keys found on terminal. Key injection required.");
        return false;
    }

    /**
     * Gets the currently configured PIN key location.
     */
    public String getPinKeyLocation() {
        return String.format("0x%04X/0x%04X", pinKeySet, pinKeyIndex);
    }

    /**
     * Loads a TR-31 format working key.
     *
     * @param tr31Block The complete TR-31 key block
     * @return true if successful
     */
    private boolean loadTr31Key(String tr31Block) {
        if (tr31Block == null || tr31Block.length() < 20) {
            Log.e(TAG, "Invalid TR-31 key block: " + (tr31Block == null ? "null" : "length=" + tr31Block.length()));
            return false;
        }

        Log.d(TAG, "Loading TR-31 format working key");
        Log.d(TAG, "  TR-31 block length: " + tr31Block.length());
        Log.d(TAG, "  TR-31 block: " + maskKey(tr31Block));

        // First check if KBPK exists at configured location
        Log.d(TAG, "  Checking for KBPK at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));
        if (!checkKeyExists(tmkKeySet, tmkKeyIndex)) {
            Log.e(TAG, "KBPK not found at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex) +
                      " - cannot unwrap TR-31 key. KBPK must be injected first via Key Injection Tool.");
            notifyKeyError("KBPK not loaded at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex) +
                          ". Please inject Key Block Protection Key first.");
            return false;
        }

        try {
            // Use Castle KMS2 TR-31 to load the key
            CtKMS2TR31 tr31 = new CtKMS2TR31();

            // Select the KBPK (Key Block Protection Key) for TR-31 unwrapping
            tr31.selectKey(tmkKeySet, tmkKeyIndex);
            Log.d(TAG, "  Selected KBPK at " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));

            // Set the destination location for the unwrapped key
            tr31.setKeyLocation(pinKeySet, pinKeyIndex);
            Log.d(TAG, "  Target location: " + String.format("%04X/%04X", pinKeySet, pinKeyIndex));

            // Set the TR-31 key block
            byte[] keyBlockBytes = tr31Block.getBytes("ISO-8859-1");
            tr31.setTR31KeyBlock(keyBlockBytes, 0, keyBlockBytes.length);
            Log.d(TAG, "  Set TR-31 key block (" + keyBlockBytes.length + " bytes)");

            // Write the key (decrypts using TMK and stores at target location)
            tr31.writeKey();
            Log.d(TAG, "  TR-31 key written successfully");

            // Verify the key was written
            if (checkKeyExists(pinKeySet, pinKeyIndex)) {
                workingKeyLoaded = true;
                currentWorkingKeyCheckValue = "TR31";
                Log.d(TAG, "TR-31 working key loaded and verified");
                notifyKeyLoaded();
                return true;
            } else {
                Log.e(TAG, "Key write succeeded but key not found at target location");
                return false;
            }

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "TR-31 key loading failed: " + String.format("0x%08X", e.getError()));
            e.showStatus();
            notifyKeyError("TR-31 key load failed: " + String.format("0x%08X", e.getError()));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "TR-31 key loading failed: " + e.getMessage(), e);
            notifyKeyError("TR-31 key load failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Encrypts a PIN block using the current working key.
     * Uses MKSK approach: TMK decrypts the session key, which then encrypts the PIN block.
     *
     * @param clearPinBlock The clear PIN block (16 hex chars from PinBlockFormatter)
     * @return The encrypted PIN block (16 hex chars), or null on error
     */
    public String encryptPinBlock(String clearPinBlock) {
        if (!workingKeyLoaded) {
            Log.e(TAG, "No working key loaded");
            return null;
        }

        if (clearPinBlock == null || clearPinBlock.length() != 16) {
            Log.e(TAG, "Invalid PIN block format");
            return null;
        }

        try {
            Log.d(TAG, "Encrypting PIN block...");

            // PRIMARY: Use software encryption if enabled (bypasses KMS2 attribute issues)
            if (useSoftwareEncryption && softwareWorkingKey != null) {
                Log.d(TAG, "  Using SOFTWARE encryption (KMS2 bypassed)");
                return encryptPinBlockWithSoftware(clearPinBlock);
            }

            // FALLBACK 1: Check if we have a pre-loaded key at target location (from Key Injection Tool)
            if (checkKeyExists(pinKeySet, pinKeyIndex)) {
                Log.d(TAG, "  Trying pre-loaded FixedKey...");
                String result = encryptPinBlockWithFixedKey(clearPinBlock);
                if (result != null) return result;
                Log.w(TAG, "  FixedKey failed, trying next method...");
            }

            // FALLBACK 2: Check if we're using MKSK approach (encrypted session key from server)
            if (encryptedWorkingKey != null && encryptedWorkingKey.length == 16) {
                Log.d(TAG, "  Trying MKSK with server key...");
                String result = encryptPinBlockWithMksk(clearPinBlock);
                if (result != null) return result;
                Log.w(TAG, "  MKSK failed");
            }

            Log.e(TAG, "No valid key available for PIN encryption");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "PIN block encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Encrypts PIN block using MKSK (Master Key / Session Key).
     * The TMK decrypts the session key, which is then used to encrypt the PIN block.
     */
    private String encryptPinBlockWithMksk(String clearPinBlock) {
        try {
            Log.d(TAG, "=== MKSK PIN Block Encryption ===");

            CtKMS2MKSK mksk = new CtKMS2MKSK();

            // Select TMK as master key
            mksk.selectKey(tmkKeySet, tmkKeyIndex);
            Log.d(TAG, "  Master key: " + String.format("%04X/%04X", tmkKeySet, tmkKeyIndex));

            // Set the encrypted working key as session key
            mksk.setSK(encryptedWorkingKey, 0, encryptedWorkingKey.length);
            Log.d(TAG, "  Session key set");

            // Set cipher method (ECB = 0x00)
            mksk.setCipherMethod((byte) 0x00);

            // Set the clear PIN block as input
            byte[] pinBlockBytes = hexStringToBytes(clearPinBlock);
            mksk.setInputData(pinBlockBytes, 0, pinBlockBytes.length);
            Log.d(TAG, "  Input PIN block: " + maskKey(clearPinBlock));

            // Encrypt
            mksk.dataEncrypt();
            byte[] encryptedBlock = mksk.getOutpuData();

            if (encryptedBlock != null && encryptedBlock.length >= 8) {
                String result = bytesToHexString(encryptedBlock);
                Log.d(TAG, "  Encrypted PIN block: " + result);
                return result;
            } else {
                Log.e(TAG, "MKSK encryption returned invalid result");
                return null;
            }

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "MKSK PIN encryption failed: " + String.format("0x%08X", e.getError()));
            return null;
        }
    }

    /**
     * Encrypts PIN block using FixedKey (pre-loaded key at target location).
     */
    private String encryptPinBlockWithFixedKey(String clearPinBlock) {
        try {
            Log.d(TAG, "=== FixedKey PIN Block Encryption ===");
            Log.d(TAG, "  Using key at: " + String.format("%04X/%04X", pinKeySet, pinKeyIndex));

            CtKMS2FixedKey fixedKey = new CtKMS2FixedKey();

            // Select the pre-loaded key
            fixedKey.selectKey(pinKeySet, pinKeyIndex);
            Log.d(TAG, "  Key selected");

            // Set cipher method (ECB = 0x00)
            fixedKey.setCipherMethod((byte) 0x00);

            // Set the clear PIN block as input
            byte[] pinBlockBytes = hexStringToBytes(clearPinBlock);
            fixedKey.setInputData(pinBlockBytes, 0, pinBlockBytes.length);
            Log.d(TAG, "  Input PIN block set (" + pinBlockBytes.length + " bytes)");

            // Encrypt
            fixedKey.dataEncrypt();
            byte[] encryptedBlock = fixedKey.getOutpuData();

            if (encryptedBlock != null && encryptedBlock.length >= 8) {
                String result = bytesToHexString(encryptedBlock);
                Log.d(TAG, "  Encrypted PIN block: " + result);
                return result;
            } else {
                Log.e(TAG, "FixedKey encryption returned invalid result");
                return null;
            }

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "FixedKey PIN encryption failed: " + String.format("0x%08X", e.getError()));
            return null;
        } catch (Exception e) {
            Log.e(TAG, "FixedKey PIN encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generates an encrypted PIN block for a transaction.
     * This is a convenience method that creates the clear PIN block and encrypts it.
     *
     * @param pin The clear PIN (4-6 digits)
     * @param pan The PAN (for Format 0 PIN block)
     * @return The encrypted PIN block (16 hex chars), or null on error
     */
    public String generateEncryptedPinBlock(String pin, String pan) {
        try {
            // Create clear PIN block using Format 0
            String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(pin, pan);

            // Encrypt it
            return encryptPinBlock(clearPinBlock);

        } catch (Exception e) {
            Log.e(TAG, "Failed to generate encrypted PIN block: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if a working key is currently loaded.
     */
    public boolean isWorkingKeyLoaded() {
        return workingKeyLoaded;
    }

    /**
     * Gets the current working key check value (KCV).
     */
    public String getWorkingKeyCheckValue() {
        return currentWorkingKeyCheckValue;
    }

    /**
     * Clears the current working key (memory and persistence).
     */
    public void clearWorkingKey() {
        workingKeyLoaded = false;
        currentWorkingKeyCheckValue = null;
        encryptedWorkingKey = null;
        softwareWorkingKey = null;
        useSoftwareEncryption = false;
        clearPersistedKey();
        Log.d(TAG, "Working key cleared (memory and persistence)");
    }

    /**
     * Sets a clear working key for software-based encryption.
     * Use this when KMS2 hardware encryption is blocked by key attribute issues.
     *
     * @param clearKeyHex The clear working key as 32 hex characters (16 bytes)
     * @return true if key was set successfully
     */
    public boolean setSoftwareWorkingKey(String clearKeyHex) {
        if (clearKeyHex == null || clearKeyHex.length() != 32) {
            Log.e(TAG, "Invalid clear key format: expected 32 hex chars");
            return false;
        }

        Log.d(TAG, "=== Setting Software Working Key ===");
        Log.d(TAG, "  Key: " + maskKey(clearKeyHex));

        this.softwareWorkingKey = hexStringToBytes(clearKeyHex);
        this.useSoftwareEncryption = true;
        this.workingKeyLoaded = true;
        this.currentWorkingKeyCheckValue = "SOFTWARE";

        // Calculate and log KCV
        String kcv = calculateSoftwareKcv(softwareWorkingKey);
        Log.d(TAG, "  KCV: " + kcv);
        Log.d(TAG, "  Software encryption ENABLED");

        // Persist key for 4 hours
        saveKeyToPrefs();

        notifyKeyLoaded();
        return true;
    }

    /**
     * Calculates KCV (Key Check Value) using software 3DES.
     */
    private String calculateSoftwareKcv(byte[] key) {
        try {
            byte[] zeros = new byte[8];
            byte[] encrypted = software3desEncrypt(key, zeros);
            if (encrypted != null && encrypted.length >= 3) {
                return bytesToHexString(new byte[]{encrypted[0], encrypted[1], encrypted[2]});
            }
        } catch (Exception e) {
            Log.e(TAG, "KCV calculation failed: " + e.getMessage());
        }
        return "??????";
    }

    /**
     * Performs 3DES ECB encryption in software.
     */
    private byte[] software3desEncrypt(byte[] key, byte[] data) {
        try {
            // Ensure key is 24 bytes for 3DES (if 16 bytes, expand to 24 by repeating first 8)
            byte[] key24;
            if (key.length == 16) {
                key24 = new byte[24];
                System.arraycopy(key, 0, key24, 0, 16);
                System.arraycopy(key, 0, key24, 16, 8);
            } else if (key.length == 24) {
                key24 = key;
            } else {
                Log.e(TAG, "Invalid key length for 3DES: " + key.length);
                return null;
            }

            SecretKeySpec keySpec = new SecretKeySpec(key24, "DESede");
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);

        } catch (Exception e) {
            Log.e(TAG, "Software 3DES encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Encrypts PIN block using software 3DES (fallback when KMS2 fails).
     */
    private String encryptPinBlockWithSoftware(String clearPinBlock) {
        if (softwareWorkingKey == null) {
            Log.e(TAG, "No software working key set");
            return null;
        }

        try {
            Log.d(TAG, "=== Software PIN Block Encryption ===");

            byte[] pinBlockBytes = hexStringToBytes(clearPinBlock);
            Log.d(TAG, "  Clear PIN block: " + maskKey(clearPinBlock));

            byte[] encrypted = software3desEncrypt(softwareWorkingKey, pinBlockBytes);

            if (encrypted != null) {
                String result = bytesToHexString(encrypted);
                Log.d(TAG, "  Encrypted PIN block: " + result);
                return result;
            } else {
                Log.e(TAG, "Software encryption returned null");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Software PIN encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the encrypted working key for diagnostics.
     * Returns masked version for security.
     */
    public String getEncryptedWorkingKeyInfo() {
        if (encryptedWorkingKey == null) {
            return "null";
        }
        String hex = bytesToHexString(encryptedWorkingKey);
        return maskKey(hex) + " (" + encryptedWorkingKey.length + " bytes)";
    }

    /**
     * Calculates a simple KCV for the key (first 6 hex of encrypting zeros).
     * In production, this would use actual 3DES encryption.
     */
    private String calculateKcv(String key) {
        // Simplified KCV - in production would encrypt 8 zeros
        if (key != null && key.length() >= 6) {
            return key.substring(0, 6);
        }
        return "000000";
    }

    // =========================================================================
    // Notification Methods
    // =========================================================================

    private void notifyKeyLoaded() {
        if (listener != null) {
            listener.onKeyLoaded(currentWorkingKeyCheckValue);
        }
    }

    private void notifyKeyError(String error) {
        if (listener != null) {
            listener.onKeyError(error);
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // =========================================================================
    // Key Persistence (4-hour expiry)
    // =========================================================================

    /**
     * Saves the working key to SharedPreferences with timestamp.
     * Key will be valid for 4 hours.
     */
    private void saveKeyToPrefs() {
        if (softwareWorkingKey == null || !useSoftwareEncryption) {
            Log.d(TAG, "No software key to persist");
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Encode key as Base64 for storage
            String keyBase64 = Base64.encodeToString(softwareWorkingKey, Base64.NO_WRAP);

            editor.putString(PREF_KEY_DATA, keyBase64);
            editor.putLong(PREF_KEY_TIMESTAMP, System.currentTimeMillis());
            editor.putString(PREF_KEY_KCV, currentWorkingKeyCheckValue);
            editor.apply();

            Log.d(TAG, "Working key persisted (valid for 4 hours)");
            Log.d(TAG, "  Key saved at: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));

        } catch (Exception e) {
            Log.e(TAG, "Failed to persist key: " + e.getMessage());
        }
    }

    /**
     * Loads the working key from SharedPreferences if not expired.
     *
     * @return true if a valid key was loaded
     */
    private boolean loadKeyFromPrefs() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            String keyBase64 = prefs.getString(PREF_KEY_DATA, null);
            long timestamp = prefs.getLong(PREF_KEY_TIMESTAMP, 0);
            String kcv = prefs.getString(PREF_KEY_KCV, null);

            if (keyBase64 == null || timestamp == 0) {
                Log.d(TAG, "No persisted key found");
                return false;
            }

            // Check if key has expired
            long elapsed = System.currentTimeMillis() - timestamp;
            if (elapsed > KEY_EXPIRY_MS) {
                Log.d(TAG, "Persisted key expired (" + (elapsed / 60000) + " minutes old)");
                clearPersistedKey();
                return false;
            }

            // Key is still valid - restore it
            byte[] keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP);
            if (keyBytes == null || keyBytes.length != 16) {
                Log.e(TAG, "Invalid persisted key data");
                clearPersistedKey();
                return false;
            }

            this.softwareWorkingKey = keyBytes;
            this.useSoftwareEncryption = true;
            this.workingKeyLoaded = true;
            this.currentWorkingKeyCheckValue = kcv != null ? kcv : "SOFTWARE";

            long remainingMinutes = (KEY_EXPIRY_MS - elapsed) / 60000;
            Log.d(TAG, "=== Loaded persisted key ===");
            Log.d(TAG, "  Key age: " + (elapsed / 60000) + " minutes");
            Log.d(TAG, "  Valid for: " + remainingMinutes + " more minutes");
            Log.d(TAG, "  KCV: " + currentWorkingKeyCheckValue);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to load persisted key: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clears the persisted key from SharedPreferences.
     */
    private void clearPersistedKey() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(PREF_KEY_DATA);
            editor.remove(PREF_KEY_TIMESTAMP);
            editor.remove(PREF_KEY_KCV);
            editor.apply();
            Log.d(TAG, "Persisted key cleared");
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear persisted key: " + e.getMessage());
        }
    }

    /**
     * Gets the remaining time (in minutes) before the persisted key expires.
     *
     * @return minutes remaining, or -1 if no key or expired
     */
    public long getKeyExpiryMinutes() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(PREF_KEY_TIMESTAMP, 0);
            if (timestamp == 0) return -1;

            long elapsed = System.currentTimeMillis() - timestamp;
            if (elapsed > KEY_EXPIRY_MS) return -1;

            return (KEY_EXPIRY_MS - elapsed) / 60000;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Checks if the working key needs renewal.
     * Returns true if:
     * - No key is loaded
     * - Key is expired
     * - Key will expire within 30 minutes (proactive renewal)
     *
     * @return true if key renewal is recommended
     */
    public boolean needsRenewal() {
        if (!workingKeyLoaded) {
            Log.d(TAG, "needsRenewal: No key loaded - renewal needed");
            return true;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(PREF_KEY_TIMESTAMP, 0);
            if (timestamp == 0) {
                Log.d(TAG, "needsRenewal: No timestamp - renewal needed");
                return true;
            }

            long elapsed = System.currentTimeMillis() - timestamp;
            long remaining = KEY_EXPIRY_MS - elapsed;

            if (remaining <= 0) {
                Log.d(TAG, "needsRenewal: Key expired - renewal needed");
                return true;
            }

            if (remaining <= KEY_RENEWAL_THRESHOLD_MS) {
                Log.d(TAG, "needsRenewal: Key expires in " + (remaining / 60000) +
                          " min (threshold: " + (KEY_RENEWAL_THRESHOLD_MS / 60000) + " min) - proactive renewal");
                return true;
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "needsRenewal: Error checking - " + e.getMessage());
            return true; // Err on side of renewal
        }
    }

    /**
     * Checks if the key is completely missing or expired (not just approaching expiry).
     * Use this for mandatory renewal on startup.
     *
     * @return true if key is missing or expired
     */
    public boolean isKeyMissingOrExpired() {
        if (!workingKeyLoaded) {
            return true;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(PREF_KEY_TIMESTAMP, 0);
            if (timestamp == 0) {
                return true;
            }

            long elapsed = System.currentTimeMillis() - timestamp;
            return elapsed > KEY_EXPIRY_MS;

        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Gets a human-readable key status for display/logging.
     *
     * @return Status string describing current key state
     */
    public String getKeyStatus() {
        if (!workingKeyLoaded) {
            return "No key loaded";
        }

        long remainingMin = getKeyExpiryMinutes();
        if (remainingMin < 0) {
            return "Key expired";
        } else if (remainingMin <= 30) {
            return "Key expires soon (" + remainingMin + " min)";
        } else {
            long hours = remainingMin / 60;
            long mins = remainingMin % 60;
            return "Key valid (" + hours + "h " + mins + "m remaining)";
        }
    }

    // =========================================================================
    // Castle MVP MKSK Support (C001/0001)
    // =========================================================================

    /**
     * Tests the MKSK key at C000/0010 (FutureX slot 10).
     * This uses CtKMS2MKSK which has proper MKSK attribute for encryption.
     *
     * @return Diagnostic result string
     */
    public String testMkskKeyAtC001() {
        StringBuilder result = new StringBuilder();
        result.append("=== MKSK Test (C000/0010 - FutureX slot 10) ===\n");

        try {
            // Check if key exists
            boolean exists = checkKeyExists(MKSK_KEY_SET, MKSK_KEY_INDEX);
            result.append("Key exists: ").append(exists).append("\n");

            if (!exists) {
                result.append("ERROR: No key at C001/0001\n");
                result.append("Castle support needs to inject a Master Session key here.\n");
                return result.toString();
            }

            // Get key info
            String keyInfo = getKeyInfo(MKSK_KEY_SET, MKSK_KEY_INDEX);
            result.append("Key info: ").append(keyInfo).append("\n");

            // Try MKSK encryption with test data (per Castle MVP)
            CtKMS2MKSK mksk = new CtKMS2MKSK();
            mksk.selectKey(MKSK_KEY_SET, MKSK_KEY_INDEX);

            byte[] testData = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38}; // "12345678"
            byte[] initVector = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            byte[] sessionKey = new byte[24]; // Triple-length session key buffer

            mksk.setCipherMethod(CtKMS2SymmetryKey.DATA_ENCRYPT_METHOD_CBC);
            mksk.setICV(initVector);
            mksk.setInputData(testData, 0, testData.length);
            mksk.setSK(sessionKey);

            byte[] encrypted = mksk.dataEncrypt();
            result.append("Encryption SUCCESS!\n");
            result.append("Encrypted: ").append(bytesToHexString(encrypted)).append("\n");

            // Try decryption
            CtKMS2MKSK mkskDec = new CtKMS2MKSK();
            mkskDec.selectKey(MKSK_KEY_SET, MKSK_KEY_INDEX);
            mkskDec.setCipherMethod(CtKMS2SymmetryKey.DATA_ENCRYPT_METHOD_CBC);
            mkskDec.setICV(initVector);
            mkskDec.setInputData(encrypted, 0, encrypted.length);
            mkskDec.setSK(sessionKey);

            byte[] decrypted = mkskDec.dataDecrypt();
            result.append("Decryption SUCCESS!\n");
            result.append("Decrypted: ").append(bytesToHexString(decrypted)).append("\n");

            result.append("\n*** MKSK KEY AT C001/0001 WORKING ***\n");

        } catch (CtKMS2Exception e) {
            result.append("MKSK ERROR: ").append(String.format("0x%08X", e.getError())).append("\n");
            result.append("Message: ").append(e.getMessage()).append("\n");
        } catch (Exception e) {
            result.append("ERROR: ").append(e.getMessage()).append("\n");
        }

        Log.d(TAG, result.toString());
        return result.toString();
    }

    /**
     * Encrypts a PIN block using MKSK at C001/0001 (Castle MVP approach).
     *
     * @param clearPinBlock The clear PIN block (16 hex chars / 8 bytes)
     * @return Encrypted PIN block (16 hex chars), or null on error
     */
    public String encryptPinWithMkskAtC001(String clearPinBlock) {
        if (clearPinBlock == null || clearPinBlock.length() != 16) {
            Log.e(TAG, "Invalid PIN block for MKSK C001: " +
                      (clearPinBlock == null ? "null" : "length=" + clearPinBlock.length()));
            return null;
        }

        try {
            Log.d(TAG, "=== MKSK C001/0001 PIN Encryption ===");

            CtKMS2MKSK mksk = new CtKMS2MKSK();
            mksk.selectKey(MKSK_KEY_SET, MKSK_KEY_INDEX);

            byte[] pinData = hexStringToBytes(clearPinBlock);
            byte[] initVector = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            byte[] sessionKey = new byte[24];

            // Use ECB for PIN block (standard for ANSI X9.8)
            mksk.setCipherMethod(CtKMS2SymmetryKey.DATA_ENCRYPT_METHOD_ECB);
            mksk.setICV(initVector);
            mksk.setInputData(pinData, 0, pinData.length);
            mksk.setSK(sessionKey);

            byte[] encrypted = mksk.dataEncrypt();
            String result = bytesToHexString(encrypted);

            Log.d(TAG, "  MKSK C001 PIN encryption SUCCESS: " + result);
            return result;

        } catch (CtKMS2Exception e) {
            Log.e(TAG, "MKSK C001 PIN error: " + String.format("0x%08X", e.getError()));
            return null;
        } catch (Exception e) {
            Log.e(TAG, "MKSK C001 PIN error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if MKSK key at C001/0001 is available.
     */
    public boolean isMkskAtC001Available() {
        try {
            CtKMS2MKSK mksk = new CtKMS2MKSK();
            mksk.selectKey(MKSK_KEY_SET, MKSK_KEY_INDEX);
            return true;
        } catch (CtKMS2Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Listener Interface
    // =========================================================================

    /**
     * Listener for key management events.
     */
    public interface KeyEventListener {
        void onKeyLoaded(String kcv);
        void onKeyError(String error);
    }
}
