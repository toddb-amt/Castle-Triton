# Castle SDK Patterns Reference

## Contents
- SDK Initialization
- EMV Callback Implementation
- Hardware Control
- Key Management
- Common Anti-Patterns

---

## SDK Initialization

### Lazy Hardware Detection

```java
// GOOD - Check for emulator before SDK init
private static boolean checkIsEmulator() {
    if (android.os.Build.MANUFACTURER.toLowerCase().contains("castle") ||
        android.os.Build.MODEL.toLowerCase().contains("s1f")) {
        return false;  // Castle hardware
    }
    return android.os.Build.HARDWARE.contains("ranchu") ||
           android.os.Build.FINGERPRINT.contains("generic/sdk");
}

// BAD - Initialize SDK unconditionally
@Override
protected void onCreate(Bundle savedInstanceState) {
    emv = new CtEMV();  // Crashes on emulator!
}
```

**Why This Matters:** SDK classes throw native exceptions on non-Castle hardware. Always guard with emulator detection.

### Singleton SDK References

```java
// GOOD - Static SDK instances, initialized once
private static CtEMV emv;
private static CtEMVCL emvcl;
private static boolean sdkInitialized = false;

public boolean isSdkAvailable() {
    return sdkInitialized && !isRunningOnEmulator;
}

// BAD - Creating new instances per transaction
public void startTransaction() {
    CtEMV emv = new CtEMV();  // Memory leak, SDK state corruption
}
```

---

## EMV Callback Implementation

### Transaction Data Callback

```java
@Override
public int onTxnDataGet(EMVTxnData txnData) {
    // CRITICAL: version must be 3
    txnData.version = 3;
    
    // Amount in BCD format (6 bytes)
    String amountBcd = Converter.amtPadding(amountCents);
    byte[] amount = Converter.hexString2ByteArray(amountBcd);
    System.arraycopy(amount, 0, txnData.amount, 0, 6);
    
    // Date/Time: YYMMDD / HHMMSS
    System.arraycopy(strDate.getBytes(), 0, txnData.txnDate, 0, 6);
    System.arraycopy(strTime.getBytes(), 0, txnData.txnTime, 0, 6);
    
    return 0;  // Return 0 = success
}
```

### PIN Collection Pattern

```java
@Override
public int eventOnlinePinBlockGet(EMVOnlinePinData onlinePinData) {
    // Show PIN pad, wait for input
    showPinPadScreen();
    while (!GlobalPara.pinBypassActionOK) {
        MyUtility.sleep(500);
    }
    
    // Create Format 0 PIN block
    String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(
        GlobalPara.enteredPin,
        GlobalPara.asciiPAN
    );
    
    // Encrypt with DUKPT
    CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
    dukpt.selectKey(GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex);
    dukpt.setPinControl(CtKMS2Dukpt.PIN_BLOCKTYPE_ANSI_X9_8_ISO_4, 0, 0);
    
    byte[] pinBlockBytes = hexStringToBytes(clearPinBlock);
    dukpt.setInputData(pinBlockBytes, 0, pinBlockBytes.length);
    dukpt.dataEncrypt();
    
    onlinePinData.encPin = dukpt.getOutpuData();
    onlinePinData.ksnLen = dukpt.getKSN().length;
    System.arraycopy(dukpt.getKSN(), 0, onlinePinData.ksn, 0, onlinePinData.ksnLen);
    
    return 0;
}
```

---

## Hardware Control

### LED Indicator States

```java
// LED bit mask: LED1=0x01, LED2=0x02, LED3=0x04, LED4=0x08
public void showCardReady() {
    clLED.setLED((byte)0x0F, (byte)0x08);  // LED4 on (green)
}

public void showCardSuccess() {
    clLED.setLED((byte)0x0F, (byte)0x02);  // LED2 on (green blink)
}

public void showError() {
    clLED.setLED((byte)0x0F, (byte)0x01);  // LED1 on (red)
}
```

### Audio Feedback

```java
// Audio uses raw WAV files from res/raw/
GlobalPara.audio.soundOK();     // Success beep
GlobalPara.audio.soundAlert();  // Error buzz
GlobalPara.audio.soundCancel(); // Cancel double-beep
```

### Printer Usage

```java
// WARNING: CTOS_Printer is MainActivity inner class
MainActivity.CTOS_Printer printer = activity.getPrinter();

// Special formatting codes
printer.printf("|<|CENTER THIS|>|\n");  // Center align
printer.printf("|>|RIGHT ALIGN|<|\n");  // Right align
printer.printf("||FONT||2||");          // Font size

// Print QR code
printer.printQRCode("https://example.com", 5);

// CRITICAL: Must call goprintf() to flush
printer.goprintf();
```

---

## Key Management

### Key Location Discovery

```java
// Scan for any available key
public boolean findAndUseAvailableKey() {
    int[][] locations = {
        {0xC000, 0x0000},  // Default DUKPT
        {0xC001, 0x001A},  // Geobridge DUKPT(1A)
        {0xCFFF, 0x0000},  // TMK/KBPK
    };
    
    for (int[] loc : locations) {
        if (checkKeyExists(loc[0], loc[1])) {
            this.pinKeySet = loc[0];
            this.pinKeyIndex = loc[1];
            return true;
        }
    }
    return false;
}

public boolean checkKeyExists(int keySet, int keyIndex) {
    try {
        CtKMS2Key key = new CtKMS2Key();
        key.selectKey(keySet, keyIndex);
        Log.d(TAG, "Key at " + String.format("%04X/%04X", keySet, keyIndex) +
                  " Attr=" + String.format("0x%08X", key.getKeyAttribute()));
        return true;
    } catch (CtKMS2Exception e) {
        return false;
    }
}
```

### Software Fallback Encryption

```java
// When KMS2 fails with 0x2907 (wrong attribute), use software 3DES
private byte[] software3desEncrypt(byte[] key, byte[] data) {
    byte[] key24 = new byte[24];
    System.arraycopy(key, 0, key24, 0, 16);
    System.arraycopy(key, 0, key24, 16, 8);  // Expand 16 to 24
    
    SecretKeySpec keySpec = new SecretKeySpec(key24, "DESede");
    Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, keySpec);
    return cipher.doFinal(data);
}
```

---

## Common Anti-Patterns

### WARNING: EMV Operations on UI Thread

**The Problem:**

```java
// BAD - Blocks UI, causes ANR
public void onClick(View v) {
    int ret = emv.txnPerform();  // 5-30 second operation!
}
```

**Why This Breaks:** EMV operations block for seconds during card communication. Android kills the app after 5 seconds of UI freeze (ANR).

**The Fix:**

```java
// GOOD - Background thread
new Thread(() -> {
    int ret = emv.txnPerform();
    runOnUiThread(() -> updateUI(ret));
}).start();
```

### WARNING: Missing State Reset

**The Problem:**

```java
// BAD - State persists between transactions
public void startNewTransaction() {
    showAmountSelection();  // Previous transaction data still in GlobalPara!
}
```

**The Fix:**

```java
// GOOD - Reset before every transaction
public void startNewTransaction() {
    GlobalPara.resetATMTransactionState();  // Clear all state
    showAmountSelection();
}
```

### WARNING: Ignoring CtKMS2Exception Error Codes

**The Problem:**

```java
// BAD - Silent failure
try {
    key.selectKey(keySet, keyIndex);
} catch (CtKMS2Exception e) {
    Log.e(TAG, "Key error");  // Useless log
}
```

**The Fix:**

```java
// GOOD - Actionable error handling
try {
    key.selectKey(keySet, keyIndex);
} catch (CtKMS2Exception e) {
    int error = e.getError();
    if (error == 0x2907) {
        Log.e(TAG, "Key not found - run Key Injection Tool");
    } else if (error == 0x2909) {
        Log.e(TAG, "TR-31 unwrap failed - check KBPK attribute");
    } else {
        Log.e(TAG, "KMS2 error: " + String.format("0x%08X", error));
    }
}