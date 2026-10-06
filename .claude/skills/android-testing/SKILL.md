---
name: android-testing
description: |
  Writes unit tests with JUnit 4 and implements EMV/protocol test cases for Castle S1F4 PRO terminal applications.
  Use when: writing new tests, debugging EMV flows, validating protocol implementations, or troubleshooting SDK integration issues
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Android Testing Skill

Unit testing for Castle S1F4 PRO Cashless ATM application using JUnit 4. Tests focus on protocol validation (Hyosung STD1), EMV tag processing, and configuration verification. Unit tests run on JVM without Android framework dependencies; instrumented tests require terminal hardware.

## Quick Start

### Run All Unit Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests "castech.emvtxn.atm.host.HyosungProtocolTest"
./gradlew test --tests "*EmvTagEnhancer*"
```

### Run With Detailed Output

```bash
./gradlew test --info
```

## Test Structure

| Directory | Purpose |
|-----------|---------|
| `app/src/test/java/` | Unit tests (JVM, no Android) |
| `app/src/androidTest/java/` | Instrumented tests (require device) |
| `app/src/main/java/.../test/` | Diagnostic utilities (run on terminal) |

## Key Test Classes

| Class | Tests |
|-------|-------|
| `HyosungProtocolTest` | LRC calculation, message framing, builder/parser |
| `EmvTagEnhancerTest` | TVR preservation, tag ordering, CVM override |
| `CAPKDataTest` | CAPK key validation, modulus/hash lengths |
| `ConfigComparisonTest` | SDK configuration comparison |

## Test Pattern

```java
@Test
public void testFeatureName() {
    // Arrange
    HyosungMessageBuilder builder = new HyosungMessageBuilder();
    TransactionRequest request = TransactionRequest.createCashWithdrawal(...);
    
    // Act
    byte[] message = builder.buildTransactionRequest(request);
    
    // Assert
    assertNotNull(message);
    assertEquals(HyosungProtocol.STX, message[0]);
}
```

## See Also

- [unit](references/unit.md) - JUnit 4 patterns and assertions
- [integration](references/integration.md) - SDK integration testing
- [mocking](references/mocking.md) - Mocking Castle SDK classes
- [fixtures](references/fixtures.md) - Test data and helpers

## Related Skills

For build configuration, see the **gradle** skill. For EMV-specific testing patterns, see the **emv** skill. For protocol message formats, see the **hyosung-protocol** skill.