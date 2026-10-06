# EMV Workflows Reference

## Contents
- Contact Chip Transaction Flow
- Contactless Transaction Flow
- PIN Entry and Encryption Flow
- Online Authorization Flow
- Error Recovery

## Contact Chip Transaction Flow

### Complete Transaction Sequence

```
1. Card Insertion Detected
   └─> sc.getStatus() & 0x01 == 0x01

2. onTxnDataGet Callback
   └─> Set amount, date, time, transaction type

3. onAppListEx Callback (if multiple AIDs)
   └─> User selects application (debit vs credit)

4. Card Data Read
   └─> Tags 57, 5A, 9F27 available

5. onGetPINNotify Callback
   └─> SDK requests PIN entry
   └─> Return key location in getPinPara

6. eventOnlinePinBlockGet Callback
   └─> Collect PIN from user
   └─> Create Format 0 PIN block
   └─> Encrypt with working key

7. GENERATE AC (internal)
   └─> Card produces cryptogram (9F26, 9F27, 9F36, 9F10)

8. txnPerform Returns 0x0304
   └─> Go online required

9. Send to Host
   └─> AtmHostService.processTransaction()

10. txnCompletion
    └─> Pass issuer auth data (tag 91) to card
```

### Transaction Workflow Checklist

Copy this checklist and track progress:
- [ ] Step 1: Reset transaction state with `GlobalPara.resetATMTransactionState()`
- [ ] Step 2: Navigate to transaction page
- [ ] Step 3: Wait for card detection
- [ ] Step 4: SDK triggers callbacks automatically
- [ ] Step 5: Verify cryptogram tags exist after txnPerform
- [ ] Step 6: Send to host via AtmHostService
- [ ] Step 7: Call txnCompletion with host response
- [ ] Step 8: Navigate to receipt page

### Transaction Start Code

```java
public void startChipTransaction() {
    // Reset state
    GlobalPara.resetATMTransactionState();
    
    // Run on transaction thread
    threadTxn = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                // Wait for card
                while ((sc.getStatus() & 0x01) != 0x01) {
                    MyUtility.sleep(100);
                }
                
                // Start EMV transaction
                int result = emv.txnPerform();
                
                if (result == CtEMV.d_EMVAPLIB_ERR_GO_ONLINE) {
                    // Proceed with online authorization
                    processOnlineAuth();
                } else {
                    handleError(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed: " + e.getMessage());
            }
        }
    });
    threadTxn.start();
}
```

## Contactless Transaction Flow

### Contactless Detection

```java
// Configure contactless reader
EMVCLRcDataEx rcData = new EMVCLRcDataEx();
rcData.timeout = 30;  // 30 second timeout

// Poll for contactless tap
int result = emvcl.performTransactionEx(rcData);

switch (result) {
    case CtEMVCL.d_CL_OK:
        // Tap successful, process transaction
        break;
    case CtEMVCL.d_CL_PENDING:
        // Still waiting for tap
        continue;
    case CtEMVCL.d_CL_TIMEOUT:
        // No card tapped
        break;
}
```

### LED Indicator Control

```java
// ClessLed.java - Control 4 contactless LEDs
ClessLed clLED = new ClessLed(imageView1, imageView2, imageView3, imageView4);

// During card detection
clLED.setDetecting();

// On success
clLED.setSuccess();

// On failure
clLED.setFailure();
```

## PIN Entry and Encryption Flow

### PIN Collection Workflow

```
1. onGetPINNotify Called
   └─> type=0: Online PIN, type=1: Offline PIN

2. Set Key Location
   └─> getPinPara.onlinePINCipherKeySet = 0xC000
   └─> getPinPara.onlinePINCipherKeyIndex = 0x0000

3. eventOnlinePinBlockGet Called
   └─> Switch to PIN pad page

4. Read PAN from Kernel
   └─> Tag 57 or Tag 5A

5. User Enters PIN
   └─> Software PIN pad collects digits

6. Create Format 0 PIN Block
   └─> PIN XOR PAN

7. Encrypt PIN Block
   └─> Use CastleKeyManager

8. Return to Kernel
   └─> Set onlinePinData.pin
   └─> Return 0
```

### PIN Block Creation

```java
// Format 0 (ISO 9564-1) PIN Block
public static String createFormat0PinBlock(String pin, String pan) {
    // PIN block: 0 | length | PIN | FFFF padding
    StringBuilder pinPart = new StringBuilder();
    pinPart.append("0");
    pinPart.append(pin.length());
    pinPart.append(pin);
    while (pinPart.length() < 16) {
        pinPart.append("F");
    }
    
    // PAN block: 0000 | rightmost 12 PAN digits (excl check digit)
    String panRight12 = pan.substring(pan.length() - 13, pan.length() - 1);
    String panBlock = "0000" + panRight12;
    
    // XOR
    byte[] pinBytes = hexStringToBytes(pinPart.toString());
    byte[] panBytes = hexStringToBytes(panBlock);
    byte[] result = new byte[8];
    for (int i = 0; i < 8; i++) {
        result[i] = (byte) (pinBytes[i] ^ panBytes[i]);
    }
    
    return bytesToHex(result);
}
```

### WARNING: SDK PIN Error 0x1003

**The Problem:**

```
txnPerform() returns 0x00001003
Cryptogram tags (9F26, 9F27, 9F36, 9F10) missing
TVR shows 0x80 (CVM Failed)
```

**Why This Happens:**
1. Key Injection Tool sets DECRYPT attribute (0x00000010)
2. SDK internal PIN requires PIN attribute (0x00000001)
3. KMS2 rejects key for PIN operations

**Current Workaround:**

```java
// Collect PIN AFTER Generate AC using software encryption
if (GlobalPara.atmPinCollectedPostTransaction) {
    // PIN was collected after cryptogram generation
    // Cryptogram is No-CVM, but PIN block is valid
}
```

### Validation Loop for PIN

```
1. Collect PIN via software pad
2. Validate: PIN length >= 4
3. If validation fails, show error and repeat step 1
4. Only proceed when PIN is valid
```

## Online Authorization Flow

### Sending to Host

```java
// After txnPerform returns 0x0304 (Go Online)
public void processOnlineAuth() {
    // Collect EMV data
    String emvData = collectEmvTags();
    String track2 = GlobalPara.atmTrack2Data;
    String pinBlock = GlobalPara.atmEncryptedPinBlock;
    
    // Build and send request
    AtmHostService hostService = new AtmHostService(context);
    TransactionResponse response = hostService.processTransaction(
        track2, pinBlock, emvData, amountCents);
    
    if (response.isApproved()) {
        // Complete transaction with issuer data
        completeTransaction(response);
    } else {
        handleDecline(response);
    }
}
```

### Transaction Completion

```java
public void completeTransaction(TransactionResponse response) {
    // Set issuer auth data (tag 91)
    if (response.getIssuerAuthData() != null) {
        TlvData tag91 = new TlvData();
        tag91.tag = 0x91;
        tag91.value = response.getIssuerAuthData();
        tag91.len = tag91.value.length;
        emv.dataSet(tag91);
    }
    
    // Complete EMV transaction
    EMVOnlineResponseData onlineResponse = new EMVOnlineResponseData();
    onlineResponse.authCode = response.getAuthCode().getBytes();
    onlineResponse.authorizationRC = 0x00;  // Approved
    
    int result = emv.txnCompletion(onlineResponse);
    
    // Navigate to receipt
    navigateToPage(GlobalDef.d_PAGE_RECEIPT);
}
```

## Error Recovery

### Transaction Error Codes

| Code | Meaning | Recovery |
|------|---------|----------|
| 0x0001 | Success | Proceed |
| 0x0304 | Go Online | Send to host |
| 0x0305 | Declined Offline | Show decline message |
| 0x1003 | CVM Failed | Check key attribute |
| 0x2907 | Key Attribute Error | Use software encryption |

### Reversal Handling

```java
// Save reversal data before host call
ReversalPersistenceManager reversalMgr = new ReversalPersistenceManager(context);
reversalMgr.saveReversalData(request);

try {
    response = hostService.sendTransaction(request);
    reversalMgr.clearReversalData();  // Success - no reversal needed
} catch (Exception e) {
    // Keep reversal data for retry
    GlobalPara.atmNeedsReversal = true;
}
```

### Error Recovery Checklist

Copy this checklist for debugging transaction failures:
- [ ] Check key exists at configured location (`CastleKeyManager.checkKeyExists()`)
- [ ] Verify key attribute supports PIN (`0x00000001` not `0x00000010`)
- [ ] Dump TVR tag 95 to see CVM status
- [ ] Check cryptogram tags (9F26, 9F27) exist after txnPerform
- [ ] Verify PAN is available for PIN block (Tag 57 or 5A)
- [ ] Check host response code for decline reason

## See Also

- See the **hyosung-protocol** skill for message building
- See the **dukpt** skill for key management details
- See the **castle-sdk** skill for terminal-specific patterns