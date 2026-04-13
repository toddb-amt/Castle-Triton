# Hyosung Protocol Workflows

## Contents
- Transaction Flow
- Testing Messages
- Debugging Protocol Issues
- Connection Management
- Reversal Handling

## Transaction Flow

Complete ATM transaction requires this handshake:

```
Terminal                    Processor
   │                           │
   │──── Request (Type 85) ───>│
   │                           │
   │<─── Response (Type 85) ───│
   │                           │
   │──── ACK (0x06) ──────────>│
   │                           │
   │<─── EOT (0x04) ───────────│
   │                           │
  [Session Complete]
```

### Implementation

```java
// AtmHostConnection handles full handshake
AtmHostConnection connection = new AtmHostConnection(config);
connection.connect();

try {
    TransactionResponse response = connection.sendTransaction(request);
    // sendTransaction internally: sends request, receives response, sends ACK, waits for EOT
    
    if (response.isApproved()) {
        dispense();
    }
} finally {
    connection.disconnect();
}
```

### Workflow Checklist

Copy this checklist for implementing a new transaction:

- [ ] Create ProcessorConfig with correct processor type
- [ ] Build TransactionRequest with all required fields
- [ ] Set sequence number (wraps 0001-9999)
- [ ] Connect to host (TLS handshake)
- [ ] Send request and receive response
- [ ] Send ACK after successful parse
- [ ] Wait for EOT (5 second timeout acceptable)
- [ ] Handle response code appropriately
- [ ] Queue reversal if dispense fails
- [ ] Disconnect socket

## Testing Messages

### Run Protocol Tests

```bash
# All protocol tests
./gradlew test --tests "*HyosungProtocol*"

# Specific test class
./gradlew test --tests "castech.emvtxn.atm.host.HyosungProtocolTest"

# With verbose output
./gradlew test --tests "*HyosungProtocol*" --info
```

### Manual Message Building for Debug

```java
// Build and dump message for inspection
HyosungMessageBuilder builder = new HyosungMessageBuilder();
byte[] message = builder.buildTransactionRequest(request);

// Human-readable format
String readable = HyosungMessageBuilder.toReadableString(message);
// Output: <STX>H0.000000<FS>GH001003<FS>85<FS>CWCACA<FS>0004<FS>...<ETX><LRC>

// Hex dump
String hex = HyosungMessageBuilder.toHexString(message);
// Output: 02 48 30 2E 30 30 30 30 30 30 1C 47 48...
```

### Validate Framing

```java
// Verify LRC before sending
boolean valid = LrcCalculator.verifyFramedMessage(message);
if (!valid) {
    throw new IllegalStateException("LRC validation failed");
}

// Parse and verify round-trip
String[] fields = parser.parseFields(message);
assertEquals("H0.000000", fields[0]);
assertEquals("85", fields[2]);
```

## Debugging Protocol Issues

### Response Code 12 (Invalid Transaction)

Usually indicates malformed message. Debug steps:

1. Dump EMV tags being sent:

```java
// EmvTagEnhancer logs all tags
Log.d(TAG, "=== EMV TAGS BEING SENT ===");
for (String tag : TAG_ORDER) {
    String value = tagMap.get(tag);
    if (value != null) {
        Log.d(TAG, "  " + tag + " = " + value);
    }
}
```

2. Compare against working terminal (GH001038)
3. Check tag order matches expected sequence
4. Verify mandatory tags present (9F02, 9F26, 9F27, 95, 9F10)

### Response Code 76 (Key Sync Error)

PIN decryption failed. Debug steps:

1. Verify KSN in Field 7 matches PIN encryption KSN
2. Check DUKPT counter hasn't rolled over
3. Request new key via Type 88 message

```java
ConfigRequest keyRequest = ConfigRequest.createKeyDownload("GH001003");
ConfigResponse keyResponse = connection.sendConfigRequest(keyRequest);

if (keyResponse.isTr31Format()) {
    // TR-31 key block
    String keyBlock = keyResponse.getTr31KeyBlock();
} else {
    // Standard two-part key
    String fullKey = keyResponse.getCombinedWorkingKey();
}
```

### Connection Timeouts

```java
// Adjust timeouts for slow networks
ProcessorConfig config = ProcessorConfig.forDns(host, terminalId);
config.setConnectionTimeout(60000);   // 60s connection
config.setResponseTimeout(90000);      // 90s response wait
config.setEotTimeout(10000);           // 10s EOT wait (non-critical)
```

### TLS Handshake Failures

```java
// Try disabling TLS for debugging (NOT for production)
config.setUseTls(false);

// Or try different TLS version
config.setTlsVersion("TLSv1.1");  // Default is TLSv1.2
```

## Connection Management

### Singleton Pattern for Connection

```java
// Avoid: Creating new connection per transaction
AtmHostConnection conn = new AtmHostConnection(config);  // New each time

// Better: Reuse connection for multiple transactions
private AtmHostConnection connection;

public synchronized AtmHostConnection getConnection() {
    if (connection == null || !connection.isConnected()) {
        connection = new AtmHostConnection(config);
        connection.connect();
    }
    return connection;
}
```

### Background Thread Requirement

Socket operations MUST run off UI thread (Android requirement).

```java
// In MainActivity or AtmHostService
new Thread(() -> {
    try {
        TransactionResponse response = connection.sendTransaction(request);
        runOnUiThread(() -> handleResponse(response));
    } catch (ConnectionException e) {
        runOnUiThread(() -> showError(e.getMessage()));
    }
}, "HostTransaction").start();
```

## Reversal Handling

When dispense fails after approval, queue a reversal:

### Create Reversal Request

```java
ReversalRequest reversal = ReversalRequest.createDispenseFailureReversal(
    originalRequest,    // The approved transaction
    originalResponse    // Contains auth data needed for reversal
);

// Reversal includes:
// - Original auth data (Field 3)
// - Original sequence number (Field 4)
// - Original track 2 (Field 5)
// - Original amounts (Fields 8, 9)
// - Reason code "02" = Dispense failure
```

### Persist Reversals for Retry

```java
// Save to disk in case of crash
ReversalPersistenceManager.save(reversal);

// On startup, retry pending reversals
List<ReversalRequest> pending = ReversalPersistenceManager.loadPending();
for (ReversalRequest rev : pending) {
    try {
        ReversalResponse resp = connection.sendReversal(rev);
        if ("00".equals(resp.getResponseCode())) {
            ReversalPersistenceManager.markComplete(rev);
        }
    } catch (Exception e) {
        // Will retry on next startup
    }
}
```

### Reversal Retry Loop

```java
// Retry until success or max attempts
int maxRetries = config.getMaxReversalRetries();  // Default: 3
int attempt = 0;

while (attempt < maxRetries) {
    try {
        ReversalResponse resp = connection.sendReversal(reversal);
        if ("00".equals(resp.getResponseCode())) {
            break;  // Success
        }
    } catch (ConnectionException e) {
        attempt++;
        Thread.sleep(config.getRetryDelayMs());  // Default: 1000ms
    }
}
```

## Host Totals (Settlement)

```java
// Query totals without reset
HostTotalsResponse totals = connection.sendHostTotalsRequest(false);

// Query and reset
HostTotalsResponse totals = connection.sendHostTotalsRequest(true);

// Parse counts: CCCCTTTTBBBBNNNN (16 chars)
String counts = totals.getTransactionCounts();
int cashWithdrawals = Integer.parseInt(counts.substring(0, 4));  // CCCC
int transfers = Integer.parseInt(counts.substring(4, 8));         // TTTT
int balanceInquiries = Integer.parseInt(counts.substring(8, 12)); // BBBB
int nonCash = Integer.parseInt(counts.substring(12, 16));         // NNNN

long totalDispensed = totals.getTotalCashDispensed();  // In cents
long totalSurcharges = totals.getTotalSurcharges();    // In cents