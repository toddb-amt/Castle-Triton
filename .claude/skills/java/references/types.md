# Java Types Reference

## Contents
- Constants Classes
- Data Model Classes
- Type Conversion Utilities
- EMV Data Structures
- Protocol Types

---

## Constants Classes

### Navigation Constants

```java
// GlobalDef.java
public class GlobalDef {
    static final int d_PAGE_IDLE             = 0;
    static final int d_PAGE_MAIN_MENU        = 1;
    static final int d_PAGE_AMOUNT_SELECTION = 2;
    static final int d_PAGE_TRANSACTION      = 3;
    static final int d_PAGE_RECEIPT          = 4;
    static final int d_PAGE_SETTING          = 5;
    
    static final byte d_ENTRY_MODE_CT   = 0x01;  // Contact/chip
    static final byte d_ENTRY_MODE_MSR  = 0x02;  // Magnetic stripe
    static final byte d_ENTRY_MODE_CL   = 0x03;  // Contactless
}
```

### Account Type Constants

```java
// GlobalPara.java
public static final int ATM_ACCOUNT_DEFAULT  = 0;
public static final int ATM_ACCOUNT_SAVINGS  = 10;
public static final int ATM_ACCOUNT_CHECKING = 20;
public static final int ATM_ACCOUNT_CREDIT   = 30;

public static String getHyosungAccountType() {
    switch (atmAccountType) {
        case ATM_ACCOUNT_SAVINGS: return "SA";
        case ATM_ACCOUNT_CREDIT:  return "CR";
        default:                  return "CA";
    }
}
```

### Protocol Constants

```java
// HyosungProtocol.java
public final class HyosungProtocol {
    // Control characters
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte FS  = 0x1C;
    
    // Message types
    public static final String MSG_TYPE_TRANSACTION = "85";
    public static final String MSG_TYPE_REVERSAL    = "86";
    public static final String MSG_TYPE_HOST_TOTALS = "87";
    
    // Response codes
    public static final String RESP_APPROVED      = "00";
    public static final String RESP_REFER_ISSUER  = "01";
    public static final String RESP_PICK_UP_CARD  = "04";
    
    // Framing types
    public enum FramingType {
        STANDARD,        // STX...ETX LRC
        VISA_NO_STX_ETX  // Length header only
    }
}
```

---

## Data Model Classes

### TransactionRequest

```java
// TransactionRequest.java
public class TransactionRequest {
    private String infoHeader;
    private String terminalId;
    private String transactionType;
    private int sequenceNumber;
    private String track2Data;
    private String pinBlock;
    private long amountCents;
    private long surchargeCents;
    private boolean dispense;
    private String emvData;
    
    // Getters/setters...
    
    // Factory methods
    public static TransactionRequest createCashWithdrawal(...) { }
    public static TransactionRequest createBalanceInquiry(...) { }
}
```

### TransactionResponse

```java
// TransactionResponse.java
public class TransactionResponse {
    private String responseCode;
    private String authCode;
    private String responseText;
    private long availableBalance;
    private String emvResponseData;
    private byte[] mac;
    
    public boolean isApproved() {
        return "00".equals(responseCode);
    }
}
```

### ProcessorConfig

```java
// ProcessorConfig.java
public class ProcessorConfig {
    private String name;
    private String host;
    private int port;
    private HyosungProtocol.FramingType framingType;
    private int connectionTimeout = 30000;
    private int readTimeout = 60000;
    private boolean useTls = true;
    
    // Factory methods for common processors
    public static ProcessorConfig forDns(String host, String terminalId) {
        ProcessorConfig c = new ProcessorConfig();
        c.setPort(8002);
        c.setFramingType(FramingType.STANDARD);
        return c;
    }
    
    public static ProcessorConfig forSwitchCommerce(String host, String terminalId) {
        ProcessorConfig c = new ProcessorConfig();
        c.setPort(1440);
        c.setFramingType(FramingType.VISA_NO_STX_ETX);
        return c;
    }
}
```

---

## Type Conversion Utilities

### Hex String Conversion

```java
// Converter class in MainActivity.java
public static byte[] hexString2ByteArray(String hexString) {
    if (hexString == null || hexString.isEmpty()) return new byte[0];
    byte[] result = new byte[hexString.length() / 2];
    for (int i = 0; i < result.length; i++) {
        result[i] = (byte) Integer.parseInt(
            hexString.substring(2 * i, 2 * i + 2), 16);
    }
    return result;
}

public static String byteArray2HexString(byte[] array, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
        int val = array[i] & 0xFF;
        if (val < 0x10) sb.append("0");
        sb.append(Integer.toHexString(val).toUpperCase());
    }
    return sb.toString();
}
```

**DO: Mask with 0xFF for unsigned conversion**

```java
int unsignedValue = byteValue & 0xFF;  // 0-255
```

**DON'T: Forget mask - get negative values**

```java
// BAD - negative for bytes > 127
int val = array[i];  // -128 to 127
```

---

## EMV Data Structures

### TlvData

```java
// TlvData inner class in MainActivity.java
public class TlvData {
    public int tag;     // 1-3 byte tag as int
    public int length;  // Data length
    public byte[] value;
}
```

### TLV Parsing

```java
// Parse EMV TLV data
public int getTLV(byte[] buffer, int index, TlvData tlv) {
    int tagLen = 1;
    
    // Check for multi-byte tag
    if ((buffer[index] & 0x1F) == 0x1F) {
        if ((buffer[index + 1] & 0x80) == 0x80) {
            // 3-byte tag
            tlv.tag = ((buffer[index] & 0xFF) << 16) |
                      ((buffer[index + 1] & 0xFF) << 8) |
                      (buffer[index + 2] & 0xFF);
            tagLen = 3;
        } else {
            // 2-byte tag
            tlv.tag = ((buffer[index] & 0xFF) << 8) |
                      (buffer[index + 1] & 0xFF);
            tagLen = 2;
        }
    } else {
        tlv.tag = buffer[index] & 0xFF;
    }
    
    // Parse length and value...
    return bytesConsumed;
}
```

---

## WARNING: Numeric Type Issues

**The Problem:**

```java
// BAD - Integer overflow for amounts in cents
int amount = 999999999;  // $9,999,999.99 - close to int max
int surcharge = 300;
int total = amount + surcharge;  // May overflow
```

**The Fix:**

```java
// GOOD - Use long for monetary values
long amountCents = 999999999L;
long surchargeCents = 300L;
long totalCents = amountCents + surchargeCents;  // Safe
```

**When You Might Be Tempted:** Working with cents seems fine with int, but total amounts, batch totals, or calculations can overflow.