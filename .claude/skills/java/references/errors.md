# Java Error Handling Reference

## Contents
- Exception Handling Patterns
- Castle SDK Error Codes
- Custom Exception Classes
- Logging Patterns
- Recovery Strategies

---

## Exception Handling Patterns

### Specific Exception Catching

```java
// AtmHostConnection.java:71-102
try {
    socket = new Socket();
    socket.connect(socketAddress, config.getConnectionTimeout());
    
} catch (javax.net.ssl.SSLHandshakeException e) {
    Log.e(TAG, "TLS handshake failed");
    throw new ConnectionException("TLS handshake failed: " + e.getMessage(), e);
    
} catch (javax.net.ssl.SSLException e) {
    Log.e(TAG, "TLS error");
    throw new ConnectionException("TLS error: " + e.getMessage(), e);
    
} catch (java.net.SocketTimeoutException e) {
    Log.e(TAG, "Connection timeout after " + config.getConnectionTimeout() + "ms");
    throw new ConnectionException("Connection timeout", e);
    
} catch (Exception e) {
    Log.e(TAG, "Connection failed: " + e.getMessage());
    throw new ConnectionException("Failed to connect: " + e.getMessage(), e);
}
```

**DO: Catch specific exceptions first, general last**

**DON'T: Catch all with bare Exception**

```java
// BAD - Hides the actual problem
try {
    doNetworkOperation();
} catch (Exception e) {
    Log.e(TAG, "Error");  // Which error? Why?
}
```

### Exception Wrapping

```java
// Wrap low-level exception with context
public class ConnectionException extends Exception {
    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Usage
throw new ConnectionException(
    "Failed to connect to " + host + ":" + port, 
    originalException);
```

---

## Castle SDK Error Codes

SDK methods return int error codes. Common pattern:

```java
// CastleKeyManager.java:188
try {
    CtKMS2System kmsSystem = new CtKMS2System();
    int ret = kmsSystem.init();
    if (ret != 0) {
        Log.e(TAG, "KMS2 init failed: " + String.format("0x%08X", ret));
    }
} catch (CtKMS2Exception e) {
    Log.e(TAG, "KMS2 exception: " + String.format("0x%08X", e.getError()));
}
```

### Common Error Codes

| Code | Hex | Meaning |
|------|-----|---------|
| 0 | 0x00000000 | Success |
| 4099 | 0x00001003 | Key attribute mismatch |
| 10503 | 0x00002907 | KMS2 key not found |
| 10505 | 0x00002909 | TR-31 unwrap failed |

### BLOCKING Issue: 0x1003

```java
// This error occurs during txnPerform() when PIN key has wrong attribute
int ret = emv.txnPerform();
if (ret == 0x1003) {
    // Key Injection Tool set DECRYPT (0x10) but SDK needs PIN (0x01)
    Log.e(TAG, "PIN key attribute error - contact Castle support");
}
```

---

## Logging Patterns

### TAG Convention

```java
public class AtmHostService {
    private static final String TAG = "AtmHostService";
    
    public void processTransaction(TransactionRequest req) {
        Log.d(TAG, "processTransaction() - amount: " + req.getAmountCents());
    }
}
```

### Debug Logging with Hex Values

```java
// Debugger utility class
public static void addHex(String tag, String title, byte[] array, int len) {
    Log.d(tag, title + Converter.byteArray2HexString(array, len));
}

// Usage
Debugger.addHex(TAG, "EMV Data: ", emvData, emvData.length);
// Output: "EmvTagEnhancer EMV Data: 9F2608A1B2C3D4E5F6..."
```

### Error Code Formatting

```java
// Always format SDK errors as hex
Log.e(TAG, "Error: " + String.format("0x%08X", errorCode));
// Output: "Error: 0x00001003"
```

---

## Recovery Strategies

### Retry with Backoff

```java
// AtmHostService.java
public void downloadKeys() throws Exception {
    int maxRetries = config.getMaxRetries();
    int retryDelayMs = config.getRetryDelayMs();
    
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            transactionManager.downloadKeys();
            return;  // Success
        } catch (ConnectionException e) {
            Log.w(TAG, "Key download attempt " + attempt + " failed");
            if (attempt == maxRetries) {
                throw e;
            }
            Thread.sleep(retryDelayMs * attempt);  // Exponential backoff
        }
    }
}
```

### Reversal Persistence

```java
// ReversalPersistenceManager.java - Store failed transactions
public void saveReversal(ReversalRequest reversal) {
    SQLiteDatabase db = getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put("terminal_id", reversal.getTerminalId());
    values.put("sequence_number", reversal.getSequenceNumber());
    values.put("amount_cents", reversal.getAmountCents());
    values.put("timestamp", System.currentTimeMillis());
    db.insert("reversals", null, values);
}

// On next startup, retry pending reversals
public void processPendingReversals(AtmHostService service) {
    List<ReversalRequest> pending = getPendingReversals();
    for (ReversalRequest r : pending) {
        try {
            service.sendReversal(r);
            markReversalComplete(r.getId());
        } catch (Exception e) {
            Log.w(TAG, "Reversal still pending: " + r.getId());
        }
    }
}
```

---

## WARNING: Silent Error Swallowing

**The Problem:**

```java
// BAD - Error hidden, transaction may be in unknown state
try {
    hostService.processTransaction(request);
} catch (Exception e) {
    // Do nothing
}
GlobalPara.atmTransactionComplete = true;  // Wrong!
```

**Why This Breaks:**
1. Transaction may have partially completed
2. User sees success but money not dispensed
3. No audit trail for debugging

**The Fix:**

```java
try {
    hostService.processTransaction(request);
    GlobalPara.atmTransactionComplete = true;
} catch (ConnectionException e) {
    Log.e(TAG, "Transaction failed: " + e.getMessage(), e);
    GlobalPara.atmResponseCode = "CE";  // Connection error
    GlobalPara.atmResponseMessage = "Unable to connect to processor";
    saveReversalIfNeeded(request);
}
```

---

## Thread Exception Handling

### WARNING: Unhandled Thread Exceptions

**The Problem:**

```java
// BAD - Exception crashes thread silently
new Thread(() -> {
    processCard();  // May throw
}).start();
```

**The Fix:**

```java
new Thread(() -> {
    try {
        processCard();
    } catch (Exception e) {
        Log.e(TAG, "Card processing error", e);
        activity.runOnUiThread(() -> showError(e.getMessage()));
    }
}).start();
```

Or use uncaught exception handler:

```java
Thread t = new Thread(runnable);
t.setUncaughtExceptionHandler((thread, e) -> {
    Log.e(TAG, "Uncaught in " + thread.getName(), e);
});
t.start();
```