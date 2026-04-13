# Mocking Reference

## Contents
- Mocking Limitations
- Test Doubles Pattern
- Mockito Setup (Not Used)
- Protocol Mocking
- Response Simulation

## Mocking Limitations

This project does **not** use Mockito or other mocking frameworks. The Castle SDK classes cannot be effectively mocked because:

1. SDK classes are final or have native dependencies
2. SDK behaviors depend on terminal hardware state
3. Callbacks require actual EMV chip interaction

**Alternative approaches:**
- Unit test pure logic (LRC, framing, parsing)
- Create test doubles for request/response objects
- Use diagnostic tests on actual terminal

## Test Doubles Pattern

### Mock Response Objects

```java
@Test
public void testParseTransactionResponse() throws Exception {
    HyosungMessageParser parser = new HyosungMessageParser();

    // Build a mock response manually
    String[] fields = new String[] {
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
    byte[] content = MessageFraming.joinFields(fields);
    byte[] framed = MessageFraming.frameStandard(content);

    TransactionResponse response = parser.parseTransactionResponse(framed);

    assertEquals("GH001003", response.getTerminalId());
    assertTrue(response.isApproved());
}
```

### Simulating Processor Responses

```java
@Test
public void testParseConfigResponseTr31() throws Exception {
    HyosungMessageParser parser = new HyosungMessageParser();

    // Simulate TR-31 key block response from processor
    String tr31Block = "B0080P0TE00E000094B420079CC80BA3461F86FE26EFC4A3B8E4FA4C5F5341176EED7B727B8A248E";
    String[] fields = new String[] {
        "H0.000000",
        "GH001003",
        "88",              // Config response
        "",
        "",
        tr31Block,         // TR-31 in Field 5
        "100",
        "",
        ""
    };
    byte[] content = MessageFraming.joinFields(fields);
    byte[] framed = MessageFraming.frameStandard(content);

    ConfigResponse response = parser.parseConfigResponse(framed);

    assertTrue(response.isTr31Format());
    assertEquals("B", response.getTr31Version());
    assertEquals("P0", response.getTr31KeyUsage());
    assertTrue(response.isPinEncryptionKey());
}
```

## Protocol Mocking

### Control Message Simulation

```java
@Test
public void testControlMessageDetection() {
    HyosungMessageParser parser = new HyosungMessageParser();

    // Simulate single-byte control responses
    assertTrue(parser.isAck(new byte[] { HyosungProtocol.ACK }));
    assertTrue(parser.isNak(new byte[] { HyosungProtocol.NAK }));
    assertTrue(parser.isEot(new byte[] { HyosungProtocol.EOT }));

    assertFalse(parser.isAck(new byte[] { HyosungProtocol.NAK }));
    assertFalse(parser.isControlMessage(new byte[] { 0x41, 0x42 }));
}
```

### Framing Builder as Mock Source

```java
@Test
public void testUnframeStandard() throws MessageFraming.FramingException {
    // Use builder to create valid framed data for parser tests
    String original = "H0.000000";
    byte[] framed = MessageFraming.frameStandard(original);
    
    // Parser can now be tested with known-good input
    byte[] content = MessageFraming.unframeStandard(framed);
    assertEquals(original, new String(content));
}
```

## WARNING: Don't Mock What You Don't Own

**The Problem:**

```java
// BAD - Attempting to mock Castle SDK
@Mock CtEMV mockEmv;

@Test
public void testEmvFlow() {
    when(mockEmv.txnPerform()).thenReturn(0);  // Doesn't work
}
```

**Why This Breaks:**
1. Castle SDK classes may be final
2. Native library calls bypass mock
3. Callback registration fails
4. State machine behavior can't be simulated

**The Fix:**

Test the layers you control:

```java
// GOOD - Test your protocol layer
@Test
public void testBuildTransactionRequest() {
    HyosungMessageBuilder builder = new HyosungMessageBuilder();
    TransactionRequest request = TransactionRequest.createCashWithdrawal(...);
    
    byte[] message = builder.buildTransactionRequest(request);
    
    // Verify message structure
    assertEquals(HyosungProtocol.STX, message[0]);
}
```

## Hex Helper for Test Data

```java
// In CAPKDataTest - reusable pattern
private byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
}

@Test
public void testCAPK09_ProductionKey_ValidLength() {
    String modulus = "9D912248DE0A4E39C1A7DDE3F6D2588992C1A4095...";
    byte[] modulusBytes = hexStringToByteArray(modulus);
    assertEquals(248, modulusBytes.length);  // 1984-bit key
}
```