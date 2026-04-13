# DUKPT Workflows Reference

## Contents
- Complete Key Injection Process
- PIN Encryption Flow
- Key Troubleshooting
- Server Key Exchange

---

## Complete Key Injection Process

### Prerequisites Checklist

Copy this checklist and track progress:
- [ ] Windows PC (or VM) with Key Injection Tool v2.01
- [ ] USB cable connected to terminal
- [ ] Terminal powered on
- [ ] Key_Injection and Key_Bridge apps on terminal
- [ ] Test keys ready (KEK, BDK, IPEK, KSN)

### Step-by-Step Injection

```
1. Factory Reset (if re-injecting)
   Terminal: SystemPanel → Password 00000000 → Factory Reset
   Wait for reboot

2. Inject KEK at C000/0000
   Terminal: Launch Key_Injection app → "Waiting for command"
   PC Tool:  Initial Key (KEK) tab
            Key Set: C000, Key Index: 0000
            Key Value: 0123456789ABCDEFFEDCBA9876543210
            → Click "Check Value" → Verify ends in D7B4
            → Click "Inject"
   Verify:  "KEK(KeySet: C000, KeyIndex: 0000) Loaded Successfully"

3. Generate TR31 Key Block
   PC Tool:  Generate Key Block tab
            KEK: 0123456789ABCDEFFEDCBA9876543210
            Working Key: 6AC292FAA1315B4D858AB3A3D7D5933A
            KSN: FFFF9876543210E00000
            Working Key Type: 3DES-DUKPT
            Key Designation: IPEK
            → Click "Generate"
   Copy:    TR31 block from log (starts with B0104...)

4. Inject DUKPT IPEK
   Terminal: Exit Key_Injection → Launch Key_Bridge → Geobridge SW
            Wait for "READY FOR KI"
   PC Tool:  Click Refresh → Select COM port
            → "Get Device Info Success"
            WK with Geobridge tab
            Key Index: DUKPT(1A)
            Paste TR31 block
            → Click "Inject"
   Verify:  "Key-1 Load_KEY Success Check Value: af8c07"

5. Load Config (Mac)
   Terminal: Enter Download Mode (Settings → System → Download Mode)
   Mac:     cp signed/config.CAP /tmp/ && cp signed/config.mci /tmp/
            cd CTOS_SDK_Installed/CAPTools/bin
            printf '/dev/tty.usbmodem*/\n/tmp/config.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
   Verify:  "ULDCAP_Download ret = 0000"

6. Install App (Mac)
   Terminal: Exit download, launch ADB Tool
   Mac:     adb install -r app-debug.apk
```

### Verification Commands

```bash
# After installation, check key via app logs:
adb logcat | grep -E "(CastleKeyManager|DUKPT|KMS2)"

# Expected output:
# Key found at C000/0000 Type=0x15 Attr=0x00000013
# DUKPT test encryption SUCCESS
```

---

## PIN Encryption Flow

### Transaction Sequence

```
1. Amount Selection
   └─> GlobalPara.atmSelectedAmount = "100.00"

2. Card Presented
   └─> MainActivity: Card detected (Contact/Contactless/MSR)
   └─> Track 2 captured: GlobalPara.atmTrack2Data

3. PIN Collection (Manual DUKPT approach)
   └─> Show PIN pad UI (CtEMVCusPINPadbyImg)
   └─> User enters PIN
   └─> Create Format 0 PIN block:
       PinBlockFormatter.createFormat0PinBlock(pin, pan)

4. PIN Encryption
   └─> CtKMS2Dukpt.selectKey(0xC000, 0x0000)
   └─> CtKMS2Dukpt.dataEncrypt()
   └─> GlobalPara.atmEncryptedPinBlock = encrypted
   └─> GlobalPara.atmDukptKsn = ksn

5. Host Authorization
   └─> HyosungMessageBuilder.buildTransactionRequest()
   └─> Field 8: Encrypted PIN block
   └─> Field 9: KSN (20 hex chars)

6. Completion
   └─> Display receipt
   └─> GlobalPara.resetATMTransactionState()
```

### Code Path

```java
// Fragment_page_transaction.java - After card read
@Override
public void onTransactionDataReady() {
    // Create clear PIN block
    String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(
        enteredPin,
        GlobalPara.atmClearPan
    );

    // Encrypt with DUKPT
    byte[] clearBytes = hexStringToBytes(clearPinBlock);
    CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
    dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
    dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);
    dukpt.setInputData(clearBytes, 0, clearBytes.length);
    dukpt.isUseCurrentKey(false);
    dukpt.dataEncrypt();

    GlobalPara.atmEncryptedPinBlock = bytesToHex(dukpt.getOutpuData());
    GlobalPara.atmDukptKsn = bytesToHex(dukpt.getKSN());
}
```

---

## Key Troubleshooting

### Error Resolution Matrix

| Error | Log Message | Cause | Resolution |
|-------|-------------|-------|------------|
| 0x1003 | FRD error 0x8602 | Key lacks PIN attribute | Use manual DUKPT, not SDK internal |
| 0x2901 | Key not found | Wrong key location | Verify keySet/keyIndex match injection |
| 0x2907 | Operation blocked | Wrong attribute for operation | Use software fallback |
| b004 | Unwrap failed | TR31/KEK mismatch | Re-inject KEK, regenerate TR31 |

### Diagnostic Workflow

```
1. Check key exists
   CastleKeyManager.checkKeyExists(0xC000, 0x0000)
   Expected: "Key found at C000/0000 Type=0x15 Attr=0x..."

2. If key not found, verify injection
   - Did Key_Bridge show "Load_KEY Success"?
   - Did you use DUKPT(1A) in Key Index dropdown?
   - Did you paste full TR31 block?

3. If key found but PIN fails
   - Check attribute value in log
   - If Attr=0x10 (DECRYPT only), use manual DUKPT
   - SDK internal PIN requires Attr=0x01 (PIN)

4. If manual DUKPT also fails
   - Try test encryption: dukpt.dataEncrypt() with zeros
   - Check KSN format: should be 20 hex chars
   - Verify key type: should be 0x15 (DUKPT)
```

### Iterate-Until-Pass Pattern

```
1. Attempt PIN encryption
2. Check: Did CtKMS2Dukpt.dataEncrypt() succeed?
3. If error 0x2901:
   - Log current key location
   - Try C001/001A (DUKPT(1A) slot)
   - Try C000/0000 (KEK slot)
   - Repeat step 2
4. If error 0x2907:
   - Enable software fallback: CastleKeyManager.setSoftwareWorkingKey()
   - Repeat step 2
5. Only proceed when encryption succeeds
```

---

## Server Key Exchange

### Hyosung STD1 Key Download (Type 88)

```
Host sends Configuration Response with working key:
Field 5: Key Part A (16 hex) - encrypted under TMK
Field 8: Key Part B (16 hex) - encrypted under TMK

Terminal decrypts both parts with TMK, concatenates for working key.
```

### Processing ConfigResponse

```java
// CastleKeyManager.loadWorkingKey()
public boolean loadWorkingKey(ConfigResponse configResponse) {
    if (configResponse.isTr31Format()) {
        // TR-31 format - BLOCKED by Key Injection Tool limitation
        return loadTr31Key(configResponse.getTr31KeyBlock());
    } else {
        // Standard format - two encrypted parts
        return loadStandardKey(configResponse.getCombinedWorkingKey());
    }
}

// Standard key loading with TMK decryption
private boolean loadStandardKey(String combinedKey) {
    String keyPartA = combinedKey.substring(0, 16);
    String keyPartB = combinedKey.substring(16, 32);

    // Attempt hardware TMK first
    if (checkKeyExists(tmkKeySet, tmkKeyIndex)) {
        String decrypted = decryptKeyPartsWithTmk(keyPartA, keyPartB);
        if (decrypted != null) {
            this.softwareWorkingKey = hexStringToBytes(decrypted);
            return true;
        }
    }

    // Fallback to software TMK
    if (hasSoftwareTmk()) {
        String decrypted = decryptKeyPartsWithSoftwareTmk(keyPartA, keyPartB);
        if (decrypted != null) {
            this.softwareWorkingKey = hexStringToBytes(decrypted);
            return true;
        }
    }

    return false;
}
```

### Setting Software TMK

```java
// When hardware TMK has wrong attribute, use software fallback
CastleKeyManager keyManager = new CastleKeyManager(context);
keyManager.initialize();

// TMK = XOR of Key Part A and Key Part B from processor initial config
String tmkFromProcessor = "0123456789ABCDEFFEDCBA9876543210";
keyManager.setSoftwareTmk(tmkFromProcessor);

// Now loadWorkingKey() will use software decryption as fallback
keyManager.loadWorkingKey(configResponse);
```

### Key Persistence (4-Hour Cache)

```java
// CastleKeyManager stores decrypted working key in SharedPreferences
private static final long KEY_EXPIRY_MS = 4 * 60 * 60 * 1000;  // 4 hours

private void saveKeyToPrefs() {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    prefs.edit()
        .putString(PREF_KEY_DATA, Base64.encodeToString(softwareWorkingKey, Base64.DEFAULT))
        .putLong(PREF_KEY_TIMESTAMP, System.currentTimeMillis())
        .apply();
}

private boolean loadKeyFromPrefs() {
    long timestamp = prefs.getLong(PREF_KEY_TIMESTAMP, 0);
    if (System.currentTimeMillis() - timestamp > KEY_EXPIRY_MS) {
        return false;  // Key expired
    }
    // Load and decode key
    return true;
}