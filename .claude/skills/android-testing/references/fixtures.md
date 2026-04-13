# Test Fixtures Reference

## Contents
- Test Data Constants
- Configuration Fixtures
- Message Fixtures
- EMV Test Data
- Helper Methods

## Test Data Constants

### Protocol Constants

```java
// From HyosungProtocol - use these in tests
public static final byte STX = 0x02;
public static final byte ETX = 0x03;
public static final byte FS  = 0x1C;
public static final byte ACK = 0x06;
public static final byte NAK = 0x15;
public static final byte EOT = 0x04;

// Account types
public static final String ACCT_CHECKING = "CA";
public static final String ACCT_SAVINGS  = "SA";
```

### Key Location Constants

```java
// From ConfigComparisonTest - document expected vs actual
static final int QUICKCHIP_CIPHER_KEY_SET = 0xC001;
static final int QUICKCHIP_CIPHER_KEY_INDEX = 0x001A;

static final int OUR_CIPHER_KEY_SET = 0xC000;
static final int OUR_CIPHER_KEY_INDEX = 0x0000;
```

## Configuration Fixtures

### QuickChip Reference Config

```java
// EMVSecureDataInfo settings (working reference)
static final int QUICKCHIP_SECURE_VERSION = 2;
static final int QUICKCHIP_KEY_TYPE = 2;  // TDES_DUKPT
static final int QUICKCHIP_CIPHER_METHOD = 1;  // CBC
static final int QUICKCHIP_CHECKSUM_TYPE = 1;  // SHA1
static final int QUICKCHIP_ICV_LEN = 16;

// PIN key settings
static final int QUICKCHIP_PIN_KEY_SET = 0xC001;
static final int QUICKCHIP_PIN_KEY_INDEX = 0x001A;
static final int QUICKCHIP_PIN_VERSION = 1;
```

## Message Fixtures

### Transaction Request Fixture

```java
TransactionRequest request = TransactionRequest.createCashWithdrawal(
    "GH001003",                                    // Terminal ID
    ";4430410000008318=26052011030018610000?",    // Track 2
    "89E02BDF2751ECA7",                           // PIN block
    50000,                                         // Amount: $500.00
    100,                                           // Surcharge: $1.00
    HyosungProtocol.ACCT_CHECKING                 // Account type
);
request.setSequenceNumber(4);
request.setStatusMonitoring("sXV1.05.00");
request.setEmvData("ud9F02000000005000");
```

### Transaction Response Fixture

```java
String[] responseFields = new String[] {
    "H0.000000",       // Field 0: Info Header
    "GH001003",        // Field 1: Terminal ID
    "85",              // Field 2: Transaction Code
    "0004",            // Field 3: Sequence Number
    "00",              // Field 4: Response Code (Approved)
    "11272025100813000000000001", // Field 5: Auth Data
    "00000100112720250", // Field 6: Settlement Data
    "50000",           // Field 7: Account Balance
    "50000",           // Field 8: Available Balance
    "100",             // Field 9: Surcharge
    "",                // Field 10: Display Message
    "",                // Field 11: Config Indicator
    ""                 // Field 12: EMV Response
};
```

### Config Response with TR-31

```java
String tr31Block = "B0080P0TE00E000094B420079CC80BA3461F86FE26EFC4A3B8E4FA4C5F5341176EED7B727B8A248E";
String[] configFields = new String[] {
    "H0.000000",
    "GH001003",
    "88",
    "", "",
    tr31Block,  // TR-31 key block
    "100",      // Surcharge
    "", ""
};
```

## EMV Test Data

### TVR Preservation Fixture

```java
// Sample EMV data with TVR = 8080108000 (byte 3 = 0x10, PIN pad not present)
String inputEmv = "9F0206000000002000" +     // Amount: $20.00
                  "9F2608FDBBFF4A279AFFD4" + // ARQC
                  "9505" + "8080108000" +    // TVR: byte 3 = 0x10
                  "9F2701" + "80" +          // CID: ARQC
                  "9C0130";                  // Transaction Type: Cash
```

### CAPK Test Data

```java
// VISA production key 09 (1984-bit = 248 bytes)
String capk09Modulus = "9D912248DE0A4E39C1A7DDE3F6D2588992C1A4095AFBD1824D1BA74847F2BC4926D2EFD904B4B54954CD189A54C5D1179654F8F9B0D2AB5F0357EB642FEDA95D3912C6576945FAB897E7062CAA44A4AA06B8FE6E3DBA18AF6AE3738E30429EE9BE03427C9D64F695FA8CAB4BFE376853EA34AD1D76BFCAD15908C077FFE6DC5521ECEF5D278A96E26F57359FFAEDA19434B937F1AD999DC5C41EB11935B44C18100E857F431A4A5A6BB65114F174C2D7B59FDF237D6BB1DD0916E644D709DED56481477C75D95CDD68254615F7740EC07F330AC5D67BCD75BF23D28A140826C026DBDE971A37CD3EF9B8DF644AC385010501EFC6509D7A41";

String capk09Hash = "1FF80A40173F52D7D27E0F26A146A1C8CCB29046";

// All VISA CAPKs use exponent 03
String capkExponent = "03";
```

## Helper Methods

### Hex String Conversion

```java
// Reuse across test classes
private byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
}
```

### Bytes to Hex (Diagnostic)

```java
// For diagnostic output
private String bytesToHex(byte[] bytes, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len && i < bytes.length; i++) {
        sb.append(String.format("%02X", bytes[i]));
    }
    return sb.toString();
}
```

### Debug Message Formatting

```java
// From HyosungMessageBuilder
public static String toHexString(byte[] data) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < data.length; i++) {
        if (i > 0) sb.append(" ");
        sb.append(String.format("%02X", data[i]));
    }
    return sb.toString();  // "02 48 30 03"
}

public static String toReadableString(byte[] data) {
    StringBuilder sb = new StringBuilder();
    for (byte b : data) {
        switch (b) {
            case STX: sb.append("<STX>"); break;
            case ETX: sb.append("<ETX>"); break;
            case FS:  sb.append("<FS>"); break;
            default:  sb.append((char) b);
        }
    }
    return sb.toString();  // "<STX>H0<FS>A<ETX>"
}
```

## WARNING: Hardcoded Test Data

**The Problem:**

```java
// BAD - Magic numbers without context
assertEquals(144, bytes.length);
assertEquals((byte)0x03, bytes[4]);
```

**Why This Breaks:** When tests fail, it's unclear what the numbers mean.

**The Fix:**

```java
// GOOD - Named constants with context
assertEquals("CAPK 05 modulus should be 144 bytes (1152-bit)", 
             144, modulusBytes.length);
assertEquals("Last byte should be 0x03 (VISA)", 
             (byte)0x03, ridBytes[4]);
```

## Test Data Validation Checklist

Copy this checklist when adding new fixtures:
- [ ] Track 2 format valid (starts with `;`, ends with `?`)
- [ ] PIN block is 16 hex chars
- [ ] Amount in cents, not dollars
- [ ] Sequence number 4 digits padded
- [ ] Terminal ID matches expected format
- [ ] EMV tags have correct length bytes