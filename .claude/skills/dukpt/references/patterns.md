# DUKPT Patterns Reference

## Contents
- PIN Block Creation
- DUKPT Key Selection
- Software Fallback Encryption
- Key Attribute Verification
- Anti-Patterns

---

## PIN Block Creation

### ISO 9564-1 Format 0 (With PAN)

```java
// PinBlockFormatter.java - Format 0 requires PAN
public static String createFormat0PinBlock(String pin, String pan) {
    validatePin(pin);
    validatePan(pan);

    // PIN component: 0 + length + PIN + F-padding
    // Example: PIN "1234" → "041234FFFFFFFFFF"
    String pinComponent = buildPinComponent(pin);

    // PAN component: 0000 + rightmost 12 digits (excluding check digit)
    // Example: PAN "4012345678909" → "0000401234567890"
    String panComponent = buildPanComponent(pan);

    // XOR the two 8-byte components
    byte[] pinBlock = new byte[8];
    for (int i = 0; i < 8; i++) {
        pinBlock[i] = (byte) (pinBytes[i] ^ panBytes[i]);
    }
    return bytesToHexString(pinBlock);
}
```

### ISO 9564-1 Format 1 (No PAN Required)

```java
// Use when terminal doesn't have clear PAN (PCI compliance)
// Server translates Format 1 → Format 0 using decrypted track data
public static String createFormat1PinBlock(String pin) {
    StringBuilder sb = new StringBuilder(16);
    sb.append('1');  // Format code
    sb.append(Integer.toHexString(pin.length()).toUpperCase());
    sb.append(pin);

    // Random padding (digits must NOT match PIN digits)
    while (sb.length() < 16) {
        char randChar = generateRandomHexNotInPin(pin);
        sb.append(randChar);
    }
    return sb.toString();
}
```

---

## DUKPT Key Selection

### Manual DUKPT Encryption (Working Pattern)

```java
// MainActivity.java - This approach WORKS with injected key
private DukptEncryptedData encryptPinWithDukpt(byte[] clearPinBlock) {
    try {
        CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
        dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
        dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);
        dukpt.setInputData(clearPinBlock, 0, clearPinBlock.length);
        dukpt.isUseCurrentKey(false);  // Derive new key, increment KSN
        dukpt.dataEncrypt();

        return new DukptEncryptedData(
            dukpt.getOutpuData(),  // Encrypted PIN block
            dukpt.getKSN()         // Current KSN for this transaction
        );
    } catch (CtKMS2Exception e) {
        Log.e(TAG, "DUKPT encryption failed: " + String.format("0x%08X", e.getError()));
        return null;
    }
}
```

### GlobalPara Key Configuration

```java
// GlobalPara.java - Key locations for PIN encryption
public static final int onlinePinKeySet = 0x0000C000;    // SDK internal PIN (BROKEN)
public static final int onlinePinKeyIndex = 0x00000000;

public static boolean atmDukptEnabled = true;
public static int atmDukptKeySet = 0x0000C000;    // Manual DUKPT (WORKS)
public static int atmDukptKeyIndex = 0x00000000;
public static String atmDukptKsn = "";            // Captured after encryption
```

---

## Software Fallback Encryption

### When Hardware KMS2 Fails

```java
// CastleKeyManager.java - Software 3DES when hardware has wrong attribute
private byte[] software3desDecrypt(byte[] key, byte[] data) {
    // Expand 16-byte key to 24-byte (K1-K2-K1)
    byte[] key24;
    if (key.length == 16) {
        key24 = new byte[24];
        System.arraycopy(key, 0, key24, 0, 16);
        System.arraycopy(key, 0, key24, 16, 8);
    } else {
        key24 = key;
    }

    SecretKeySpec keySpec = new SecretKeySpec(key24, "DESede");
    Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, keySpec);
    return cipher.doFinal(data);
}
```

### TMK Decryption Fallback Chain

```java
// CastleKeyManager.loadStandardKey() - Three-stage fallback
private boolean loadStandardKey(String combinedKey) {
    String keyPartA = combinedKey.substring(0, 16);
    String keyPartB = combinedKey.substring(16, 32);

    // Method 1: Hardware TMK via CtKMS2FixedKey
    if (checkKeyExists(tmkKeySet, tmkKeyIndex)) {
        String decrypted = decryptKeyPartsWithTmk(keyPartA, keyPartB);
        if (decrypted != null) return storeWorkingKey(decrypted, "HW-TMK");
    }

    // Method 2: Software TMK (fallback for attribute error 0x2907)
    if (hasSoftwareTmk()) {
        String decrypted = decryptKeyPartsWithSoftwareTmk(keyPartA, keyPartB);
        if (decrypted != null) return storeWorkingKey(decrypted, "SW-TMK");
    }

    // Method 3: Clear key (ONLY if processor sends unencrypted keys)
    Log.w(TAG, "WARNING: Using key as CLEAR - likely WRONG if encrypted!");
    return storeWorkingKey(combinedKey, "CLEAR");
}
```

---

## Key Attribute Verification

### Check Key Exists and Attributes

```java
// CastleKeyManager.java
public boolean checkKeyExists(int keySet, int keyIndex) {
    try {
        CtKMS2Key key = new CtKMS2Key();
        key.selectKey(keySet, keyIndex);
        Log.d(TAG, String.format("Key at %04X/%04X Type=0x%02X Attr=0x%08X",
            keySet, keyIndex, key.getKeyType(), key.getKeyAttribute()));
        return true;
    } catch (CtKMS2Exception e) {
        Log.d(TAG, String.format("No key at %04X/%04X Error=0x%08X",
            keySet, keyIndex, e.getError()));
        return false;
    }
}
```

### Key Attribute Values

| Attribute | Hex | Purpose |
|-----------|-----|---------|
| PIN | 0x00000001 | PIN encryption (SDK internal needs this) |
| MAC | 0x00000004 | MAC generation |
| DATA_ENC | 0x00000002 | Data encryption |
| DATA_DEC | 0x00000010 | Data decryption (Key Injection Tool sets this) |
| KBPK | 0x00000020 | Key Block Protection (for TR-31) |

---

## Anti-Patterns

### WARNING: Using SDK Internal PIN with DUKPT Key

**The Problem:**

```java
// BAD - SDK internal PIN flow fails with error 0x1003
@Override
public int onGetPINNotify(EMVGetPINPara getPinPara) {
    getPinPara.onlinePINCipherKeySet = 0xC000;    // Where DUKPT is injected
    getPinPara.onlinePINCipherKeyIndex = 0x0000;
    return 0;  // Tell SDK to use this key
    // RESULT: FRD layer error 0x8602/0x1003 - eventOnlinePinBlockGet NEVER called
}
```

**Why This Breaks:**
1. Key Injection Tool sets attribute 0x00000010 (DECRYPT), not 0x00000001 (PIN)
2. SDK's FRD (security) layer validates key attributes before PIN operations
3. FRD rejects key because it lacks PIN attribute
4. No cryptogram tags generated (9F26, 9F27, 9F36, 9F10)

**The Fix:**

```java
// GOOD - Manual DUKPT after SDK completes
@Override
public void eventOnlinePinBlockGet(String pinBlock) {
    // SDK may return null when internal PIN fails
    // Use manual DUKPT instead
    if (pinBlock == null || pinBlock.isEmpty()) {
        DukptEncryptedData result = encryptPinWithDukpt(clearPinBlock);
        GlobalPara.atmEncryptedPinBlock = bytesToHex(result.encryptedBlock);
        GlobalPara.atmDukptKsn = bytesToHex(result.ksn);
    }
}
```

### WARNING: Storing Clear Keys in SharedPreferences

**The Problem:**

```java
// BAD - Keys persisted in clear for 4 hours
private void saveKeyToPrefs() {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    prefs.edit()
        .putString(PREF_KEY_DATA, Base64.encodeToString(softwareWorkingKey, Base64.DEFAULT))
        .putLong(PREF_KEY_TIMESTAMP, System.currentTimeMillis())
        .apply();
}
```

**Why This Breaks:**
1. SharedPreferences stored in app data directory
2. Rooted devices can read this data
3. No server-side key rotation notification
4. PCI-DSS compliance violation in production

**The Fix:**
- Use Android Keystore for key storage
- Implement server push for key rotation
- Reduce persistence window or disable entirely

### WARNING: Hardcoded Test Keys in Production

**The Problem:**

```java
// BAD - Test keys visible in source code
public static final String TEST_KEK = "0123456789ABCDEFFEDCBA9876543210";
public static final String TEST_BDK = "0123456789ABCDEFFEDCBA9876543210";
```

**Why This Breaks:**
1. Test keys compromise real transactions if deployed
2. Source code audits flag this immediately
3. PCI-DSS requires production key ceremony

**The Fix:**
- Remove test keys before production build
- Use build flavors to separate debug/release keys
- Production keys injected via secure key ceremony only