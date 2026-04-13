# Castle SDK Workflows Reference

## Contents
- Transaction Flow
- Build and Deployment
- Key Injection Process
- Debugging EMV Issues

---

## Transaction Flow

### Complete EMV Transaction Sequence

```
1. Card Polling
   └─> Simultaneous check: Contact, Contactless, MSR
       └─> First detection wins

2. Card Detected
   └─> onTxnDataGet callback (set amount, date, time)
   └─> onAppListEx callback (if multiple apps on card)

3. PIN Required
   └─> onGetPINNotify callback (set key location)
   └─> eventOnlinePinBlockGet callback (collect and encrypt PIN)

4. Generate AC
   └─> Card produces cryptogram (9F26, 9F27, 9F36, 9F10)
   └─> onTxnResult callback (approved/declined/go-online)

5. Online Authorization
   └─> Send to processor via AtmHostService
   └─> Receive auth code, balance, response code

6. Completion
   └─> Display receipt
   └─> Print if requested
```

### Card Detection Loop

```java
private void startCardDetection() {
    GlobalPara.resetATMTransactionState();
    
    while (true) {
        // Priority 1: Contactless (best UX)
        if (emvcl.performTransactionEx() != PENDING) {
            GlobalPara.atmEntryMode = ENTRY_MODE_CONTACTLESS;
            break;
        }
        
        // Priority 2: MSR (swipe)
        if (msr.readTracks() == SUCCESS) {
            GlobalPara.atmEntryMode = ENTRY_MODE_MSR;
            break;
        }
        
        // Priority 3: Contact (chip)
        if ((sc.getStatus() & 0x01) == 0x01) {
            GlobalPara.atmEntryMode = ENTRY_MODE_CONTACT;
            break;
        }
        
        Thread.sleep(100);
    }
}
```

---

## Build and Deployment

### Build Checklist

Copy this checklist and track progress:
- [ ] Verify Gradle 8.5 (NOT 9.0) in `gradle-wrapper.properties`
- [ ] Enable `multiDexEnabled true` in `app/build.gradle`
- [ ] Set Java 8 compatibility
- [ ] Add multidex dependency
- [ ] Run `./gradlew clean build`

### Build Commands

```bash
# Navigate to project
cd "Android SDK/Sample Code/Emvtxn-S1F4"

# Clean build
./gradlew clean build

# Debug APK only
./gradlew assembleDebug

# Run unit tests
./gradlew test --tests "*HyosungProtocol*"
```

### Deployment to Terminal (NOT ADB)

**WARNING:** Castle terminals do NOT use ADB. Use CAPGen + Loader via serial port.

```bash
# Step 1: Build APK
./gradlew clean assembleDebug

# Step 2: Package into CAP file
cd /path/to/CTOS_SDK_Installed/CAPTools/bin
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/path/to/app/build/outputs/apk/debug" app-debug.apk 1 0

# Step 3: Copy files (Loader can't handle spaces in paths!)
cp .../output/debug.CAP /tmp/
cp .../output/debug.mci /tmp/

# Step 4: Find serial port
ls /dev/tty.usbmodem*

# Step 5: Upload via Loader
printf '/dev/tty.usbmodem144201\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

### Deployment Validation

1. Terminal shows "Download Mode"
2. Loader outputs "Upload successful"
3. Terminal auto-restarts
4. App appears in launcher

If Loader times out, retry 2-3 times. If app won't load, delete existing app on terminal first.

---

## Key Injection Process

### Key Injection Checklist

Copy this checklist and track progress:
- [ ] Connect terminal via USB serial
- [ ] Open Key Injection Tool v2.01
- [ ] Select serial port (e.g., `/dev/tty.usbmodem144201`)
- [ ] Inject TMK/KEK at `CFFF/0000`
- [ ] Inject DUKPT at `C000/0000`
- [ ] Verify KCV matches expected value
- [ ] Test PIN encryption in app

### Key Locations

| Location | Purpose | Attribute |
|----------|---------|-----------|
| `C000/0000` | DUKPT for PIN | Should be 0x01 (PIN) |
| `CFFF/0000` | TMK/KEK | Should be 0x20 (KBPK) |
| `C001/00A1` | Working key | Variable |

### Known Issue: Wrong Key Attribute

Key Injection Tool v2.01 sets attribute `0x00000010` (DECRYPT) but SDK requires `0x00000001` (PIN) for internal PIN flow.

**Symptoms:**
- `txnPerform()` returns `0x1003`
- No cryptogram generated (missing 9F26, 9F27)
- TVR shows `0x80` (CVM Failed)

**Workaround:** Use manual PIN collection + external DUKPT encryption (bypass SDK internal PIN).

---

## Debugging EMV Issues

### Enable SDK Debug Logging

```java
// In MainActivity.onCreate()
emv.setDebugLevel(CtEMV.DEBUG_LEVEL_VERBOSE);
emvcl.setDebugLevel(CtEMVCL.DEBUG_LEVEL_VERBOSE);
```

### Capture Full Logcat

```bash
adb logcat -v time | grep -E "(CtEMV|CtKMS2|TAG)" > emv_debug.log
```

### Key Diagnostic Report

```java
CastleKeyManager keyMgr = new CastleKeyManager(context);
keyMgr.initialize();
String report = keyMgr.getKeyDiagnosticReport();
Log.d(TAG, report);
```

### EMV Tag Extraction

```java
// After successful card read
byte[] arqc = getEmvTag(0x9F26);  // Application Cryptogram
byte[] tvr = getEmvTag(0x95);     // Terminal Verification Results
byte[] cvmResults = getEmvTag(0x9F34);  // CVM Results

Log.d(TAG, "ARQC: " + bytesToHexString(arqc));
Log.d(TAG, "TVR: " + bytesToHexString(tvr));
Log.d(TAG, "CVM: " + bytesToHexString(cvmResults));
```

### TVR Bit Analysis

| Byte | Bit | Meaning |
|------|-----|---------|
| 3 | 0x04 | Online PIN entered |
| 3 | 0x80 | CVM processing failed |
| 4 | 0x08 | Transaction declined |

```java
// Check if CVM failed
if ((tvr[2] & 0x80) != 0) {
    Log.e(TAG, "CVM FAILED - check PIN key attribute");
}
```

### Debug Iteration Pattern

1. Make code changes
2. Build: `./gradlew assembleDebug`
3. Deploy: CAPGen → Loader
4. Test on terminal
5. Check logcat for errors
6. If error persists, capture full logcat and analyze
7. Repeat until issue resolved