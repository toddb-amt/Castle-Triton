---
name: emv
description: |
  Integrates EMV chip card processing, contactless NFC, and magnetic stripe reading using Castle CTOS SDK on S1F4 PRO terminals.
  Use when: implementing card detection flows, configuring EMV callbacks, handling PIN entry, processing transaction results, or working with EMV tags.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# EMV Skill

Integrates Castle CTOS SDK for EMV chip, contactless (NFC), and magnetic stripe reading on S1F4 PRO payment terminals. This codebase implements a cashless ATM application with online-only authorization requiring PIN verification for every transaction.

## Quick Start

### SDK Initialization

```java
// MainActivity.java - Initialize EMV kernels on app start
emv = new CtEMV(this);
emv.setGlobalEventListener(emvEventListener);

emvcl = new CtEMVCL(this);
emvcl.setCLEventListener(emvclEventListener);

msr = new CtEMVMSR(this);
```

### Card Detection Loop

```java
// Poll all payment methods - first detection wins
do {
    // Priority 1: Contactless (best UX)
    if (emvcl.performTransactionEx(rcData) != PENDING) {
        entryMode = ENTRY_MODE_CL;
        break;
    }
    // Priority 2: MSR
    if (msr.readTracks() == SUCCESS) {
        entryMode = ENTRY_MODE_MSR;
        break;
    }
    // Priority 3: Contact chip
    if ((sc.getStatus() & 0x01) == 0x01) {
        entryMode = ENTRY_MODE_CT;
        break;
    }
} while (true);
```

## Key Concepts

| Concept | Purpose | Key Class/Tag |
|---------|---------|---------------|
| Terminal Capabilities (9F33) | Declares what terminal supports | `E0F1C8` = Online PIN |
| CVM Results (9F34) | Records PIN verification outcome | `420000` = PIN verified |
| TVR (Tag 95) | Terminal Verification Results | Byte 3: 0x04=PIN entered, 0x80=CVM failed |
| Application Cryptogram (9F26) | Card-generated auth data | Required for online authorization |
| DUKPT | Derived Unique Key Per Transaction | PIN encryption at `C000/0000` |

## Critical EMV Callbacks

### onGetPINNotify - PIN Request

```java
@Override
public int onGetPINNotify(byte type, int remainingCounter, EMVGetPINFuncPara getPinPara) {
    getPinPara.version = 1;
    getPinPara.timeout = 60;
    getPinPara.maxPINDigitLength = 8;
    getPinPara.minPINDigitLength = 4;
    
    // Set key location for PIN encryption
    getPinPara.onlinePINCipherKeySet = GlobalPara.onlinePinKeySet;    // 0xC000
    getPinPara.onlinePINCipherKeyIndex = GlobalPara.onlinePinKeyIndex; // 0x0000
    
    // type=0: Online PIN (external), type=1: Offline PIN (internal)
    getPinPara.isInternalPINPad = (type == 1) ? 1 : 0;
    
    return 0;  // MUST return 0, returning 1 causes error 0x1003
}
```

### eventOnlinePinBlockGet - PIN Collection

```java
public int eventOnlinePinBlockGet(EMVOnlinePinData onlinePinData) {
    // Create Format 0 PIN block
    String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(pin, clearPan);
    
    // Encrypt with working key
    String encryptedPinHex = keyManager.encryptPinBlock(clearPinBlock);
    
    onlinePinData.pin = Converter.hexString2ByteArray(encryptedPinHex);
    onlinePinData.pinLen = 8;
    onlinePinData.isOnlinePinRquired = true;
    
    return 0;  // Success - EMV kernel continues
}
```

## Common Patterns

### Reading EMV Tags

```java
TlvData tag57 = new TlvData();
tag57.tag = (short) 0x57;  // Track 2 Equivalent
tag57.len = 40;
tag57.value = new byte[40];
int result = emv.dataGet(tag57);

if (result == 0 && tag57.len > 0) {
    String track2Hex = Converter.byteArray2HexString(tag57.value, tag57.len);
    // Parse PAN from track2 (before 'D' separator)
}
```

### Transaction Result Handling

```java
@Override
public void onTxnResult(byte txnResult, boolean isSignatureRequired) {
    switch (txnResult) {
        case 0x01: transactionResult = 0x0002; break;  // Approved
        case 0x02: transactionResult = 0x0003; break;  // Declined
        case 0x03: transactionResult = 0x0004; break;  // Online required
    }
}
```

## See Also

- [patterns](references/patterns.md) - EMV callback patterns, tag handling, CVM processing
- [workflows](references/workflows.md) - Transaction flows, PIN handling, cryptogram generation

## Related Skills

- See the **castle-sdk** skill for SDK initialization and hardware integration
- See the **dukpt** skill for PIN encryption and key management
- See the **android-fragments** skill for UI fragment navigation during transactions
- See the **hyosung-protocol** skill for sending EMV data to ATM processors