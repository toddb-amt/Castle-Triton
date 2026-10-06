---
name: hyosung-protocol
description: |
  Implements Hyosung STD1 message framing, TLV encoding, and ATM processor communication.
  Use when: building/parsing ATM messages, integrating with processors (DNS, EFX, SwitchCommerce), handling LRC checksums, or managing TLS socket connections.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Hyosung Protocol Skill

Hyosung STD1 is the standard ATM message format used by Nationwide processors. This codebase implements complete message building, parsing, framing, and processor communication for the Castle S1F4 PRO terminal.

## Quick Start

### Build a Transaction Request

```java
// Create cash withdrawal request
TransactionRequest request = TransactionRequest.createCashWithdrawal(
    "GH001003",                              // Terminal ID
    ";4430410000008318=26052011030018610000?", // Track 2
    "89E02BDF2751ECA7",                      // Encrypted PIN block
    50000,                                   // Amount in cents ($500)
    100,                                     // Surcharge in cents ($1)
    HyosungProtocol.ACCT_CHECKING            // Account type "CA"
);
request.setSequenceNumber(4);
request.setEmvData("9F02060000000500009F260812345678...");

// Build framed message
HyosungMessageBuilder builder = HyosungMessageBuilder.forDns();
byte[] message = builder.buildTransactionRequest(request);
```

### Parse a Response

```java
HyosungMessageParser parser = HyosungMessageParser.forDns();
TransactionResponse response = parser.parseTransactionResponse(framedMessage);

if (response.isApproved()) {
    long balance = response.getAccountBalanceCents();
    String authCode = response.getRetrievalReferenceNumber();
}
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `HyosungProtocol` | Constants: control chars, message types, response codes |
| `HyosungMessageBuilder` | Packs request objects into framed byte arrays |
| `HyosungMessageParser` | Unpacks framed messages into response objects |
| `MessageFraming` | STX/ETX and VISA length-prefix framing |
| `LrcCalculator` | XOR checksum calculation |
| `AtmHostConnection` | TLS socket management, handshake (ACK/EOT) |
| `ProcessorConfig` | Pre-configured profiles for DNS, EFX, etc. |
| `EmvTagEnhancer` | Reorders EMV TLV tags to match processor expectations |

## Framing Types

```java
// Standard (STX/ETX with LRC) - DNS, FIS, CDS
HyosungProtocol.FramingType.STANDARD

// VISA with length header + STX/ETX/LRC - Cardtronics, Elan
HyosungProtocol.FramingType.VISA_LENGTH_PREFIX

// VISA with length header only, NO STX/ETX - Switch Commerce, EFX
HyosungProtocol.FramingType.VISA_NO_STX_ETX
```

## Message Types

| Code | Constant | Purpose |
|------|----------|---------|
| 85 | `MSG_TYPE_TRANSACTION` | Cash withdrawal, balance inquiry |
| 86 | `MSG_TYPE_REVERSAL` | Undo failed transactions |
| 87 | `MSG_TYPE_HOST_TOTALS` | Settlement/reconciliation |
| 88 | `MSG_TYPE_CONFIG` | Key download, surcharge config |
| 89 | `MSG_TYPE_HEALTH_CHECK` | Keep-alive ping |

## See Also

- [patterns](references/patterns.md) - Message building patterns, LRC handling
- [workflows](references/workflows.md) - Transaction flow, testing, debugging

## Related Skills

For EMV tag handling and chip card processing, see the **emv** skill. For DUKPT key management and PIN encryption, see the **dukpt** skill. For TLS socket configuration, see the **tls-networking** skill.