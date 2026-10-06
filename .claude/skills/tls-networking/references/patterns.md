# TLS Connection Patterns

## Contents
- Socket Configuration
- Error Handling Patterns
- Timeout Management
- Anti-Patterns

## Socket Configuration

### Production Socket Setup

```java
// AtmHostConnection.java - Complete setup pattern
public SSLSocket createSecureConnection(String host, int port) throws IOException {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, null, new SecureRandom());
    
    SSLSocketFactory factory = sslContext.getSocketFactory();
    SSLSocket socket = (SSLSocket) factory.createSocket();
    
    // CRITICAL: Set timeouts BEFORE connect
    socket.setSoTimeout(READ_TIMEOUT_MS);
    socket.setKeepAlive(true);
    socket.setTcpNoDelay(true);
    
    // Force modern TLS
    socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
    
    // Connect with separate timeout
    socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
    socket.startHandshake();
    
    return socket;
}
```

### Processor-Specific Configuration

```java
// ProcessorConfig.java - Per-processor settings
public static ProcessorConfig forType(String processorType) {
    switch (processorType) {
        case "DNS":
            return new ProcessorConfig(8002, FramingType.STANDARD, 30000, 15000);
        case "SWITCH_COMMERCE":
            return new ProcessorConfig(1440, FramingType.VISA, 45000, 20000);
        case "EFX":
            return new ProcessorConfig(9057, FramingType.VISA, 60000, 20000);
        default:
            throw new IllegalArgumentException("Unknown processor: " + processorType);
    }
}
```

## Error Handling Patterns

### Connection Exception Hierarchy

```java
// Handle specific failure modes
try {
    socket = createSecureConnection(host, port);
} catch (SSLHandshakeException e) {
    // Certificate validation failed - check processor cert
    Log.e(TAG, "TLS handshake failed: " + e.getMessage());
    throw new AtmConnectionException("Certificate error", e, true); // retriable=true
} catch (SocketTimeoutException e) {
    // Connection or read timeout
    Log.e(TAG, "Connection timeout to " + host + ":" + port);
    throw new AtmConnectionException("Timeout", e, true);
} catch (UnknownHostException e) {
    // DNS resolution failed - check network
    Log.e(TAG, "Cannot resolve host: " + host);
    throw new AtmConnectionException("DNS failure", e, false); // not retriable
} catch (IOException e) {
    // Generic network error
    Log.e(TAG, "Connection failed: " + e.getMessage());
    throw new AtmConnectionException("Network error", e, true);
}
```

### Retry Logic with Backoff

```java
// AtmHostConnection.java - Exponential backoff
private static final int MAX_RETRIES = 3;
private static final int BASE_DELAY_MS = 1000;

public TransactionResponse sendWithRetry(TransactionRequest request) {
    int attempts = 0;
    Exception lastException = null;
    
    while (attempts < MAX_RETRIES) {
        try {
            return sendTransaction(request);
        } catch (AtmConnectionException e) {
            if (!e.isRetriable()) throw e;
            lastException = e;
            attempts++;
            if (attempts < MAX_RETRIES) {
                int delay = BASE_DELAY_MS * (1 << (attempts - 1)); // 1s, 2s, 4s
                Thread.sleep(delay);
            }
        }
    }
    throw new AtmConnectionException("Max retries exceeded", lastException, false);
}
```

## Timeout Management

### Timeout Constants

```java
// GlobalPara.java - Centralized timeout config
public static final int ATM_CONNECT_TIMEOUT_MS = 15000;  // 15s to establish connection
public static final int ATM_READ_TIMEOUT_MS = 30000;     // 30s to receive response
public static final int ATM_HEALTH_CHECK_TIMEOUT_MS = 5000; // 5s for health checks
```

### Dynamic Timeout Adjustment

```java
// Increase timeout for specific operations
public void sendReversalWithExtendedTimeout(ReversalRequest request) {
    int originalTimeout = socket.getSoTimeout();
    try {
        socket.setSoTimeout(60000); // Reversals need more time
        sendMessage(HyosungMessageBuilder.buildReversal(request));
        readResponse();
    } finally {
        socket.setSoTimeout(originalTimeout);
    }
}
```

## Anti-Patterns

### WARNING: Missing Timeout Configuration

**The Problem:**

```java
// BAD - No timeouts set
SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
socket.getOutputStream().write(data); // Can block forever
```

**Why This Breaks:**
1. Thread blocks indefinitely if processor is unresponsive
2. Transaction hangs with no user feedback
3. Terminal becomes unresponsive

**The Fix:**

```java
// GOOD - Always set both timeouts
SSLSocket socket = (SSLSocket) factory.createSocket();
socket.setSoTimeout(30000);
socket.connect(new InetSocketAddress(host, port), 15000);
```

### WARNING: Ignoring Partial Reads

**The Problem:**

```java
// BAD - Assumes full message in one read
byte[] buffer = new byte[4096];
int len = inputStream.read(buffer);
return Arrays.copyOf(buffer, len);
```

**Why This Breaks:**
1. TCP can fragment data across multiple packets
2. Large EMV responses (2KB+) often arrive in chunks
3. Causes message parsing failures

**The Fix:**

```java
// GOOD - Read until complete frame received
public byte[] readFramedMessage(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
        buffer.write(b);
        if (isCompleteFrame(buffer.toByteArray())) {
            return buffer.toByteArray();
        }
    }
    throw new IOException("Connection closed before complete message");
}
```

### WARNING: Not Closing Sockets

**The Problem:**

```java
// BAD - Socket leak on exception
SSLSocket socket = createConnection();
sendMessage(socket, request);
// Exception here leaves socket open
return readResponse(socket);
```

**Why This Breaks:**
1. File descriptor exhaustion after ~1000 transactions
2. Terminal crashes with "Too many open files"
3. Memory leak from unreleased buffers

**The Fix:**

```java
// GOOD - try-with-resources or explicit finally
try (SSLSocket socket = createConnection()) {
    sendMessage(socket, request);
    return readResponse(socket);
} // Socket auto-closed