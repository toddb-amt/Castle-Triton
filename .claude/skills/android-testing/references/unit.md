# Unit Testing Reference

## Contents
- Test File Location
- JUnit 4 Patterns
- Assertion Patterns
- Protocol Testing
- Configuration Testing
- Common Errors

## Test File Location

Unit tests go in `app/src/test/java/castech/emvtxn/`:

```
app/src/test/java/castech/emvtxn/
├── CAPKDataTest.java           # CAPK validation
├── ConfigComparisonTest.java   # Config comparison
├── ExampleUnitTest.java        # Template
└── atm/host/
    ├── HyosungProtocolTest.java  # Protocol tests
    └── EmvTagEnhancerTest.java   # EMV tag tests
```

## JUnit 4 Patterns

### Basic Test Structure

```java
package castech.emvtxn.atm.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class HyosungProtocolTest {

    @Test
    public void testLrcCalculation() {
        byte[] data = new byte[] { 0x48, 0x30, 0x2E };
        byte lrc = LrcCalculator.calculate(data);
        
        byte expected = 0;
        for (byte b : data) expected ^= b;
        assertEquals(expected, lrc);
    }
}
```

### Expected Exceptions

```java
@Test(expected = MessageFraming.FramingException.class)
public void testUnframeInvalidLrc() throws MessageFraming.FramingException {
    byte[] framed = MessageFraming.frameStandard("content");
    framed[framed.length - 1] = (byte)(framed[framed.length - 1] + 1); // Corrupt LRC
    MessageFraming.unframeStandard(framed);  // Should throw
}
```

## Assertion Patterns

### Byte Array Comparisons

```java
// GOOD - Check specific bytes
assertEquals(HyosungProtocol.STX, message[0]);
assertEquals(HyosungProtocol.ETX, message[message.length - 2]);

// GOOD - String arrays
assertArrayEquals(expectedFields, actualFields);
```

### Boolean Conditions with Messages

```java
// GOOD - Descriptive failure message
assertTrue("TVR must be preserved for ARQC validation",
           result.contains("95058080108000"));

assertFalse("TVR byte 3 should NOT be changed to 0x04",
            result.contains("95058080048000"));
```

### Position Ordering

```java
// GOOD - Verify tag order in EMV data
int pos9F02 = result.indexOf("9F0206");
int pos9F26 = result.indexOf("9F2608");
assertTrue("9F02 should come before 9F26", pos9F02 < pos9F26);
```

## Protocol Testing

### Round-Trip Validation

```java
@Test
public void testRoundTripTransactionMessage() throws Exception {
    HyosungMessageBuilder builder = new HyosungMessageBuilder();
    HyosungMessageParser parser = new HyosungMessageParser();

    TransactionRequest request = TransactionRequest.createCashWithdrawal(
        "GH001003",
        ";4430410000008318=26052011030018610000?",
        "89E02BDF2751ECA7",
        50000, 100,
        HyosungProtocol.ACCT_CHECKING
    );
    request.setSequenceNumber(4);

    byte[] message = builder.buildTransactionRequest(request);
    String[] fields = parser.parseFields(message);

    assertEquals("H0.000000", fields[0]);
    assertEquals("GH001003", fields[1]);
    assertEquals("85", fields[2]);
}
```

### Framing Type Variants

```java
@Test
public void testRoundTripWithVisaFraming() throws Exception {
    HyosungMessageBuilder builder = new HyosungMessageBuilder(
        HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    HyosungMessageParser parser = new HyosungMessageParser(
        HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);

    // ... test with VISA framing
}
```

## Configuration Testing

### Document Differences Pattern

```java
@Test
public void testSecureVersionDifference() {
    // Document expected vs actual for debugging
    assertNotEquals("secureInfo.version differs",
        QUICKCHIP_SECURE_VERSION, OUR_SECURE_VERSION);
    
    System.out.println("secureInfo.version: QuickChip=" + 
        QUICKCHIP_SECURE_VERSION + ", Ours=" + OUR_SECURE_VERSION);
}
```

### Informational Test

```java
@Test
public void printConfigurationSummary() {
    System.out.println("========================================");
    System.out.println("CONFIGURATION COMPARISON SUMMARY");
    // ... diagnostic output ...
    assertTrue(true);  // Always pass - informational only
}
```

## WARNING: Android Framework Calls

**The Problem:**

```java
// BAD - Uses Android Log in unit test
@Test
public void testSomething() {
    Log.d(TAG, "Testing...");  // FAILS - android.util.Log not available
}
```

**Why This Breaks:** Unit tests run on JVM, not Android. `android.*` classes are stubs that return null/0 by default (see `unitTests.returnDefaultValues = true` in build.gradle).

**The Fix:**

```java
// GOOD - Use System.out for diagnostics
@Test
public void testSomething() {
    System.out.println("Testing...");
}
```

## Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `NullPointerException` on Android class | Unit test calling Android SDK | Move to androidTest or mock |
| `java.lang.NoClassDefFoundError: CTOS/*` | Castle SDK requires terminal | Use androidTest for SDK tests |
| Test passes locally, fails CI | Gradle daemon caching | `./gradlew clean test` |