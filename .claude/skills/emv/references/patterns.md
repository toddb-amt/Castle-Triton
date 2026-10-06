# EMV Patterns Reference

## Contents
- SDK Initialization Pattern
- EMV Callback Implementation
- Tag Reading and Writing
- CVM (Cardholder Verification) Processing
- Anti-Patterns

## SDK Initialization Pattern

Initialize EMV kernels in `MainActivity.onCreate()`:

```java
// Contact chip EMV
emv = new CtEMV(this);
emv.setGlobalEventListener(new MyEMVEvent(this, emv));

// Contactless NFC
emvcl = new CtEMVCL(this);
emvcl.setCLEventListener(emvclEventListener);

// Magnetic stripe
msr = new CtEMVMSR(this);
```

**DO:** Initialize in background thread (`threadTxn`) to avoid ANR.

```java
// GOOD - Background initialization
threadTxn = new Thread(new Runnable() {
    @Override
    public void run() {
        emv = new CtEMV(MainActivity.this);
        // ... more init
        GlobalPara.isInitThreadFinish = true;
    }
});
threadTxn.start();
```

**DON'T:** Initialize on UI thread - will cause ANR on production terminals.

## EMV Callback Implementation

### Transaction Data Callback

```java
@Override
public int onTxnDataGet(EMVTxnData txnData) {
    txnData.version = 3;  // MUST be 3
    
    // Amount in cents, 6-byte BCD
    String strAmt = Converter.amtPadding(amountCents);
    byte[] amount = Converter.hexString2ByteArray(strAmt);
    System.arraycopy(amount, 0, txnData.amount, 0, 6);
    
    // Date/time in YYMMDD/HHMMSS format
    System.arraycopy(strDate.getBytes(), 0, txnData.txnDate, 0, 6);
    System.arraycopy(strTime.getBytes(), 0, txnData.txnTime, 0, 6);
    
    txnData.posEntryMode = 0x00;
    txnData.txnType = 0x00;  // 0x00 = Purchase
    
    return 0;
}
```

### Application Selection Callback

```java
@Override
public int onAppListEx(EMVAppListExData appListExData) {
    // Build list UI from appListExData.appInfo[]
    for (int i = 0; i < appListExData.appNum; i++) {
        String appLabel = new String(appListExData.appInfo[i].appLabel);
    }
    
    // Wait for user selection
    do {
        MyUtility.sleep(500);
    } while (!GlobalPara.appListOK);
    
    // Return selected index (0 to appNum-1)
    appListExData.appSelectedIndex = GlobalPara.appSelectedIndex;
    return 0;
}
```

### WARNING: Blocking Callbacks

**The Problem:**

```java
// BAD - Callback returns immediately without user input
@Override
public int onAppListEx(EMVAppListExData appListExData) {
    appListExData.appSelectedIndex = 0;  // Just pick first
    return 0;
}
```

**Why This Breaks:**
1. User never sees app selection when card has multiple AIDs
2. May select wrong AID (credit vs debit)
3. Certification testing will fail

**The Fix:**

```java
// GOOD - Wait for user selection
do {
    MyUtility.sleep(500);
} while (!GlobalPara.appListOK);
appListExData.appSelectedIndex = GlobalPara.appSelectedIndex;
```

## Tag Reading and Writing

### Reading Tags from EMV Kernel

```java
// Method 1: TlvData struct
TlvData tag9F26 = new TlvData();
tag9F26.tag = 0x9F26;  // Application Cryptogram
tag9F26.len = 8;
tag9F26.value = new byte[8];
int result = emv.dataGet(tag9F26);

// Method 2: Using TLVUtility
TLVUtility_CT tlvUtil = new TLVUtility_CT();
tlvUtil.TLVDataParse(emvDataBytes, emvDataLen);
TlvData cryptogram = new TlvData();
cryptogram.tag = 0x9F26;
cryptogram.value = new byte[8];
tlvUtil.TLVDataGet(cryptogram);
```

### Critical EMV Tags for ATM

| Tag | Name | Required | Notes |
|-----|------|----------|-------|
| 9F26 | Application Cryptogram | Yes | ARQC for online auth |
| 9F27 | Cryptogram Information Data | Yes | Indicates ARQC/AAC/TC |
| 9F10 | Issuer Application Data | Yes | Processor-specific |
| 9F36 | Application Transaction Counter | Yes | Prevents replay |
| 95 | TVR | Yes | Terminal Verification Results |
| 9F34 | CVM Results | Yes | Shows PIN verified |
| 57 | Track 2 Equivalent | Yes | Card data for auth |
| 5A | PAN | Required | For PIN block creation |

### WARNING: Missing Cryptogram Tags

**The Problem:**

```java
// SDK returns 0x1003 during txnPerform()
// Tags 9F26, 9F27, 9F36, 9F10 are all empty
```

**Why This Breaks:**
1. Error 0x1003 = CVM failed during transaction
2. GENERATE AC command never executes
3. Card can't produce cryptogram without valid CVM

**Root Cause:** Key Injection Tool sets attribute `0x00000010 (DECRYPT)` instead of `0x00000001 (PIN)`.

**Current Workaround:**

```java
// Collect PIN AFTER transaction using No-CVM cryptogram
GlobalPara.atmPinCollectedPostTransaction = true;
// Use DUKPT encryption directly for PIN block
```

## CVM (Cardholder Verification) Processing

### Terminal Capabilities Configuration

```xml
<!-- emv_config.xml -->
<Item name="TERMINAL CAPABILITIES" tag="9F33" attribute="hex">E0F1C8</Item>
```

| Byte | Value | Meaning |
|------|-------|---------|
| 1 | E0 | Card data input: manual, magnetic, chip |
| 2 | F1 | CVM: signature, PIN, online PIN |
| 3 | C8 | Security: SDA, DDA, CDA |

### CVM Results Interpretation

```java
// Tag 9F34 - CVM Results
// Byte 1: CVM performed
// Byte 2: Condition code
// Byte 3: Result

// Example: 420000
// 42 = Online PIN performed
// 00 = Always (no condition)
// 00 = Success
```

### TVR Byte 3 (CVM Status)

```java
byte[] tvr = getTvrFromKernel();  // Tag 95
byte cvmByte = tvr[2];  // Byte 3 (0-indexed)

// Check bits
if ((cvmByte & 0x80) != 0) {
    // CVM processing failed
}
if ((cvmByte & 0x04) != 0) {
    // Online PIN entered
}
```

### WARNING: Wrong TVR CVM Bits

**The Problem:**

```java
// TVR byte 3 shows 0x80 (CVM failed) instead of 0x04 (PIN entered)
// Even though PIN was entered and encrypted
```

**Why This Breaks:**
1. Issuer sees CVM failed in authorization request
2. Transaction will be declined by processor
3. EMV certification will fail

**The Fix:** Ensure `onGetPINNotify` returns 0 and key location has correct PIN attribute.

## Anti-Patterns

### WARNING: Calling EMV SDK on UI Thread

**The Problem:**

```java
// BAD - Called from onClick handler (UI thread)
public void onClick(View v) {
    int result = emv.txnPerform();  // BLOCKS UI
}
```

**Why This Breaks:**
1. EMV operations can take 2-5 seconds
2. Android ANR timeout is 5 seconds
3. App will freeze and crash on real terminal

**The Fix:**

```java
// GOOD - Use dedicated transaction thread
threadTxn = new Thread(new Runnable() {
    @Override
    public void run() {
        int result = emv.txnPerform();
        runOnUiThread(() -> handleResult(result));
    }
});
threadTxn.start();
```

### WARNING: Not Resetting State Between Transactions

**The Problem:**

```java
// BAD - State from previous transaction leaks
public void startNewTransaction() {
    // atmTrack2Data still has old card data
    navigateToPage(d_PAGE_TRANSACTION);
}
```

**Why This Breaks:**
1. PAN mismatch with PIN block
2. Wrong cryptogram sent to processor
3. Transaction declined or security alert

**The Fix:**

```java
// GOOD - Reset all state
public void startNewTransaction() {
    GlobalPara.resetATMTransactionState();  // Clears everything
    navigateToPage(d_PAGE_TRANSACTION);
}
```

## See Also

- See the **dukpt** skill for PIN encryption key management
- See the **castle-sdk** skill for hardware initialization patterns