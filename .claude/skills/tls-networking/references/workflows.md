# TLS Connection Workflows

## Contents
- Connection Lifecycle
- Testing Connectivity
- Debugging Connection Issues
- Production Checklist

## Connection Lifecycle

### Full Transaction Connection Flow

```
1. Check cached connection health
   ↓ (unhealthy or null)
2. Create new SSLSocket
   ↓
3. Configure timeouts and TLS version
   ↓
4. Connect to processor endpoint
   ↓
5. Perform TLS handshake
   ↓
6. Send Hyosung STD1 message
   ↓
7. Read framed response
   ↓
8. Parse response (see hyosung-protocol skill)
   ↓
9. Close or cache connection
```

### Connection Pooling Pattern

```java
// AtmHostConnection.java - Simple connection reuse
private SSLSocket cachedSocket = null;
private long lastUsedTimestamp = 0;
private static final long MAX_IDLE_MS = 30000;

public synchronized SSLSocket getConnection() throws IOException {
    long now = System.currentTimeMillis();
    
    if (cachedSocket != null && !cachedSocket.isClosed() 
        && (now - lastUsedTimestamp) < MAX_IDLE_MS) {
        // Reuse existing connection
        return cachedSocket;
    }
    
    // Close stale connection
    closeQuietly(cachedSocket);
    
    // Create fresh connection
    cachedSocket = createSecureConnection(host, port);
    lastUsedTimestamp = now;
    return cachedSocket;
}

public synchronized void releaseConnection() {
    lastUsedTimestamp = System.currentTimeMillis();
    // Don't close - keep for reuse
}
```

## Testing Connectivity

### Pre-Transaction Health Check

```java
// AtmHostService.java - Call before each transaction
public boolean verifyProcessorConnectivity() {
    try {
        SSLSocket socket = connection.getConnection();
        
        // Send health check (Type 89)
        byte[] healthCheck = HyosungMessageBuilder.buildHealthCheck(terminalId);
        socket.getOutputStream().write(healthCheck);
        socket.getOutputStream().flush();
        
        // Wait for ACK with short timeout
        socket.setSoTimeout(5000);
        int response = socket.getInputStream().read();
        
        return response == 0x06; // ACK
    } catch (Exception e) {
        Log.w(TAG, "Health check failed: " + e.getMessage());
        return false;
    }
}
```

### Connection Test Workflow

Copy this checklist and track progress:
- [ ] Step 1: Verify network connectivity (ping processor host)
- [ ] Step 2: Check DNS resolution
- [ ] Step 3: Test TCP connection on processor port
- [ ] Step 4: Verify TLS handshake completes
- [ ] Step 5: Send health check message
- [ ] Step 6: Verify ACK response received

```java
// Diagnostic connection test
public void runConnectionDiagnostics(String host, int port) {
    Log.i(TAG, "=== Connection Diagnostics ===");
    
    // Step 1: DNS resolution
    try {
        InetAddress addr = InetAddress.getByName(host);
        Log.i(TAG, "DNS OK: " + host + " -> " + addr.getHostAddress());
    } catch (UnknownHostException e) {
        Log.e(TAG, "DNS FAILED: Cannot resolve " + host);
        return;
    }
    
    // Step 2: TCP connect
    Socket tcpSocket = new Socket();
    try {
        tcpSocket.connect(new InetSocketAddress(host, port), 10000);
        Log.i(TAG, "TCP OK: Connected to " + host + ":" + port);
    } catch (IOException e) {
        Log.e(TAG, "TCP FAILED: " + e.getMessage());
        return;
    } finally {
        closeQuietly(tcpSocket);
    }
    
    // Step 3: TLS handshake
    try {
        SSLSocket sslSocket = createSecureConnection(host, port);
        Log.i(TAG, "TLS OK: Protocol=" + sslSocket.getSession().getProtocol() 
            + ", Cipher=" + sslSocket.getSession().getCipherSuite());
        closeQuietly(sslSocket);
    } catch (Exception e) {
        Log.e(TAG, "TLS FAILED: " + e.getMessage());
    }
}
```

## Debugging Connection Issues

### Common Failure Modes

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| `SocketTimeoutException` on connect | Firewall blocking port | Check network rules |
| `SSLHandshakeException` | TLS version mismatch | Force TLSv1.2 |
| `UnknownHostException` | DNS not configured | Use IP address directly |
| Connection succeeds but no response | Wrong framing type | Check VISA vs Standard |
| Intermittent timeouts | Processor overloaded | Increase read timeout |

### TLS Debug Logging

```java
// Enable for troubleshooting certificate issues
// Add to app startup (MainActivity.onCreate)
if (BuildConfig.DEBUG) {
    System.setProperty("javax.net.debug", "ssl,handshake");
}
```

### Network State Logging

```java
// Log connection state before each transaction
public void logNetworkState() {
    ConnectivityManager cm = (ConnectivityManager) 
        context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
    
    Log.i(TAG, "Network: " + (activeNetwork != null ? 
        activeNetwork.getTypeName() + " " + 
        (activeNetwork.isConnected() ? "CONNECTED" : "DISCONNECTED") :
        "NONE"));
}
```

## Production Checklist

### Before Going Live

Copy this checklist and track progress:
- [ ] TLS 1.2+ enforced (no TLS 1.0/1.1)
- [ ] Connect timeout set (15s recommended)
- [ ] Read timeout set (30s recommended)
- [ ] Retry logic with exponential backoff
- [ ] Socket closure in finally blocks
- [ ] Health check before transactions
- [ ] Processor-specific framing configured
- [ ] Certificate pinning (if required by processor)
- [ ] Network permission in AndroidManifest.xml

### Manifest Permissions

```xml
<!-- AndroidManifest.xml - Required for socket operations -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### Feedback Loop for Connection Issues

1. Attempt connection
2. Validate: Check `socket.isConnected()` and `!socket.isClosed()`
3. If validation fails, log diagnostics and retry with backoff
4. Only proceed when connection is verified healthy
5. If 3 retries fail, abort transaction and notify user

```java
// Validation loop pattern
for (int attempt = 1; attempt <= 3; attempt++) {
    SSLSocket socket = createSecureConnection(host, port);
    if (socket.isConnected() && !socket.isClosed()) {
        Log.i(TAG, "Connection established on attempt " + attempt);
        return socket;
    }
    Log.w(TAG, "Connection validation failed, attempt " + attempt);
    closeQuietly(socket);
    Thread.sleep(1000 * attempt);
}
throw new AtmConnectionException("Could not establish valid connection");