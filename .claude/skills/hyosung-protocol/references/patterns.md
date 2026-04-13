# Hyosung Protocol Patterns

## Contents
- Message Building Patterns
- LRC Checksum Handling
- Framing Selection
- Response Code Handling
- EMV Data Integration
- Common Anti-Patterns

## Message Building Patterns

### Factory Methods for Processor-Specific Builders

```java
// GOOD - Use factory methods for correct framing
HyosungMessageBuilder builder = HyosungMessageBuilder.forDns();        // Standard
HyosungMessageBuilder builder = HyosungMessageBuilder.forEfx();        // VISA no STX
HyosungMessageBuilder builder = HyosungMessageBuilder.forSwitchCommerce(); // VISA no STX
```

```java
// BAD - Hardcoding framing type without knowing processor requirements
HyosungMessageBuilder builder = new HyosungMessageBuilder();  // Defaults to STANDARD
```

### Processor Configuration via Factory

```java
// Create complete processor config with correct defaults
ProcessorConfig config = ProcessorConfig.forDns("dns.example.com", "GH001003");
// Port: 8002, Framing: STANDARD, TLS: true, HealthCheck: disabled

ProcessorConfig config = ProcessorConfig.forSwitchCommerce("sc.example.com", "GH001003");
// Port: 1440, Framing: VISA_NO_STX_ETX, RoutingId: SC101, Header: 123SC101
```

## LRC Checksum Handling

LRC (Longitudinal Redundancy Check) is XOR of all bytes from after STX through ETX inclusive.

```java
// Calculate LRC for message content
byte lrc = LrcCalculator.calculate(contentBytes);

// Verify received LRC
boolean valid = LrcCalculator.verify(dataWithoutLrc, receivedLrc);

// Verify complete framed message (including LRC)
boolean valid = LrcCalculator.verifyFramedMessage(framedMessage);
```

### WARNING: LRC Range Errors

**The Problem:**

```java
// BAD - Wrong range includes STX byte
byte lrc = LrcCalculator.calculate(framedMessage, 0, framedMessage.length - 1);
```

**Why This Breaks:**
1. LRC excludes STX (0x02) - starts at byte AFTER STX
2. LRC includes ETX (0x03)
3. Wrong calculation = processor rejects message with NAK

**The Fix:**

```java
// GOOD - LRC from after STX to ETX (indices 1 to length-2)
byte lrc = LrcCalculator.calculate(framedMessage, 1, framedMessage.length - 2);
```

## Framing Selection by Processor

| Processor | Framing | Port | Routing ID |
|-----------|---------|------|------------|
| DNS | STANDARD | 8002 | 000000 |
| FIS | STANDARD | 443 | 000000 |
| CDS | STANDARD | 6965 | CDHY |
| Switch Commerce | VISA_NO_STX_ETX | 1440 | SC101 |
| EFX | VISA_NO_STX_ETX | 9057 | 000000 |
| Cardtronics | VISA_LENGTH_PREFIX | 5550 | CTSTRA |
| Elan/Genpass | VISA_LENGTH_PREFIX | 5166 | 000000 |

### Standard Framing Structure

```
[STX][Field0][FS][Field1][FS]...[FieldN][ETX][LRC]
```

### VISA Framing with STX/ETX

```
[2-byte length][STX][Field0][FS][Field1][FS]...[FieldN][ETX][LRC]
```

### VISA Framing WITHOUT STX/ETX (Switch Commerce, EFX)

```
[2-byte length][Field0][FS][Field1][FS]...[FieldN]
```

## Response Code Handling

```java
// Check approval status
if (HyosungProtocol.isApproved(response.getResponseCode())) {
    // "00" (approved) or "10" (partial)
    dispenseCase();
}

// Check if card should be retained (fraud, stolen, etc.)
if (HyosungProtocol.shouldRetainCard(response.getResponseCode())) {
    // "04", "07", "33", "34", "41", "43", "75"
    retainCard();
}

// Check if key sync needed
if (HyosungProtocol.requiresKeySync(response.getResponseCode())) {
    // "76" - request new working key via Type 88
    requestKeyDownload();
}
```

### Common Response Codes

| Code | Meaning | Action |
|------|---------|--------|
| 00 | Approved | Complete transaction |
| 51 | Insufficient Funds | Decline, show message |
| 55 | Incorrect PIN | Allow retry (up to 3) |
| 75 | PIN Tries Exceeded | Retain card |
| 76 | Key Sync Error | Request config (Type 88) |
| 12 | Invalid Transaction | Check message format |

## EMV Data Integration

EMV data in Field 13 must be prefixed with "ud" per Hyosung spec.

```java
// In HyosungMessageBuilder.buildTransactionRequest()
String emvData = request.getEmvData();
String emvField = emvData.isEmpty() ? "" : "ud" + emvData;
fields[13] = emvField;
```

### Tag Ordering with EmvTagEnhancer

Processors expect tags in specific order. Use `EmvTagEnhancer` to reorder:

```java
EmvTagEnhancer enhancer = new EmvTagEnhancer();
String orderedEmv = enhancer.enhanceEmvData(
    rawEmvData,
    EmvTagEnhancer.TXN_TYPE_CASH,      // 0x01
    EmvTagEnhancer.POS_ENTRY_CHIP,     // "05"
    true,                               // isCashWithdrawal
    true                                // isAtmMode
);
request.setEmvData(orderedEmv);
```

## Common Anti-Patterns

### WARNING: Mixing Encrypted and Clear Track 2

**The Problem:**

```java
// BAD - Using encrypted track2 for EMV (chip) transactions
fields[6] = "e" + request.getEncryptedTrack2();  // Always encrypted
```

**Why This Breaks:**
1. EMV transactions have ARQC cryptogram for security
2. Double-encrypting wastes bytes and confuses processors
3. Processor can't validate ARQC if track2 is also encrypted

**The Fix:**

```java
// GOOD - EMV uses clear track2, MSR uses encrypted
if (hasEmvData) {
    track2ForMessage = request.getTrack2Data();  // Clear - ARQC provides security
} else if (request.hasEncryptedTrack2()) {
    track2ForMessage = "e" + request.getEncryptedTrack2();  // "e" prefix for encrypted
}
```

### WARNING: Wrong KSN for PIN Decryption

**The Problem:**

```java
// BAD - Using Track2 KSN when PIN KSN is different
fields[7] = request.getTrack2Ksn();  // DUKPT counter mismatch
```

**Why This Breaks:**
1. DUKPT counter increments for each operation
2. PIN encrypted first = KSN 00A7, Track2 encrypted second = KSN 00A8
3. Processor uses Field 7 KSN to derive PIN decryption key

**The Fix:**

```java
// GOOD - Use PIN-specific KSN
String pinKsn = request.getPinKsn();
if (pinKsn != null && !pinKsn.isEmpty()) {
    ksnForField7 = pinKsn;
} else {
    ksnForField7 = request.getTrack2Ksn();  // Fallback only
}
```

### WARNING: Ignoring NAK Responses

**The Problem:**

```java
// BAD - Not handling NAK, assuming all responses are data
TransactionResponse response = parser.parseTransactionResponse(message);
```

**Why This Breaks:**
1. NAK (0x15) is single-byte response indicating transmission error
2. Parsing NAK as transaction response throws exception
3. Protocol requires retransmission on NAK

**The Fix:**

```java
// GOOD - Check for control messages first
if (parser.isNak(message)) {
    retransmitLastMessage();
    return;
}
if (parser.isEot(message)) {
    closeSession();
    return;
}
TransactionResponse response = parser.parseTransactionResponse(message);