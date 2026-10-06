---
name: java
description: |
  Handles Java 8 syntax, EMV SDK integration, and thread management patterns.
  Use when: Writing or modifying Java code in the Cashless ATM application, handling threading, integrating with Castle CTOS SDK, or implementing Hyosung protocol communication.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Java Skill

Java 8 patterns for Castle S1F4 PRO Android payment terminal development. This codebase uses singleton state management via `GlobalPara`, dedicated background threads for EMV operations, and Castle CTOS SDK for hardware access.

## Quick Start

### Background Threading for EMV

```java
// ALWAYS run EMV operations on threadTxn, NEVER on UI thread
threadTxn = new Thread(new Runnable() {
    @Override
    public void run() {
        int ret = emv.txnPerform();  // Blocking EMV operation
        // Marshal result back to UI
        activity.runOnUiThread(() -> updateTransactionResult(ret));
    }
});
threadTxn.start();
```

### Global State Access

```java
// Read transaction state from singleton
String amount = GlobalPara.atmSelectedAmount;
String pan = GlobalPara.asciiPAN;

// Reset state before new transaction
GlobalPara.resetATMTransactionState();
```

### Lambda Syntax (Java 8)

```java
// Use lambdas for click handlers and runnables
button.setOnClickListener(v -> processCard());
getActivity().runOnUiThread(() -> showResult(message));
```

## Key Concepts

| Concept | Usage | Example |
|---------|-------|---------|
| Singleton state | `GlobalPara.fieldName` | `GlobalPara.atmSelectedAmount` |
| Background thread | EMV/network ops | `threadTxn`, `thGetOnlinPin` |
| UI marshalling | Update from background | `runOnUiThread(() -> ...)` |
| Volatile flags | Thread coordination | `volatile boolean txnAborted` |
| SDK callbacks | EMV event handling | `MyEMVEvent` inner class |

## Common Patterns

### Thread Cleanup

**When:** Stopping transaction, changing screens

```java
if (threadTxn != null && threadTxn.isAlive()) {
    txnAborted = true;
    threadTxn.interrupt();
    try {
        threadTxn.join(3000);
    } catch (InterruptedException e) {
        Log.w(TAG, "Thread cleanup interrupted");
    }
}
```

### Hex String Conversion

**When:** Working with EMV tags, PIN blocks, SDK byte arrays

```java
// Bytes to hex string
String hex = Converter.byteArray2HexString(data, data.length);

// Hex string to bytes
byte[] bytes = Converter.hexString2ByteArray("9F2608A1B2C3D4E5F6");
```

### Factory Methods for Requests

**When:** Creating protocol messages

```java
TransactionRequest req = TransactionRequest.createCashWithdrawal(
    terminalId, track2Data, pinBlock, amountCents, surchargeCents, "CA");
```

## See Also

- [patterns](references/patterns.md) - Threading, singleton, callbacks
- [types](references/types.md) - Constants, data classes, enums
- [modules](references/modules.md) - Package organization
- [errors](references/errors.md) - Exception handling, SDK errors

## Related Skills

- **android** skill for Activity/Fragment lifecycle
- **gradle** skill for build configuration
- **emv** skill for EMV tag handling
- **castle-sdk** skill for CTOS API details
- **hyosung-protocol** skill for message building
- **dukpt** skill for PIN encryption