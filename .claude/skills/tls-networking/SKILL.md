---
name: tls-networking
description: |
  Configures TLS/SSL connections, socket management, and processor endpoint communication for ATM host integration.
  Use when: establishing connections to ATM processors (DNS, SwitchCommerce, EFX), implementing socket timeouts, handling TLS certificate validation, or troubleshooting connection failures.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# TLS Networking Skill

Manages secure socket connections to ATM processor endpoints using Java's SSL/TLS APIs. This codebase uses standard Java `SSLSocket` for TLS connections rather than Android-specific networking libraries, ensuring compatibility with Castle S1F4 PRO terminal's Java 8 runtime.

## Quick Start

### Basic TLS Connection

```java
// From AtmHostConnection.java - Production pattern
SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
SSLSocket socket = (SSLSocket) factory.createSocket();
socket.setSoTimeout(30000); // 30 second read timeout
socket.connect(new InetSocketAddress(host, port), 15000); // 15 second connect timeout
```

### Connection with Protocol Enforcement

```java
// Force TLS 1.2 minimum - required by most processors
SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());
socket.startHandshake();
```

## Key Concepts

| Concept | Usage | Example |
|---------|-------|---------|
| Connection Timeout | Time to establish TCP connection | `socket.connect(addr, 15000)` |
| Read Timeout | Time waiting for response data | `socket.setSoTimeout(30000)` |
| TLS Handshake | Explicit handshake trigger | `socket.startHandshake()` |
| Protocol Version | Minimum TLS version enforcement | `setEnabledProtocols({"TLSv1.2"})` |

## Processor Endpoints

| Processor | Default Port | Framing | TLS Required |
|-----------|-------------|---------|--------------|
| DNS | 8002 | Standard STX/ETX | Yes |
| SwitchCommerce | 1440 | VISA (length header) | Yes |
| EFX | 9057 | VISA (length header) | Yes |

## Common Patterns

### Send/Receive with Framing

**When:** Communicating with ATM processors using Hyosung STD1 protocol

```java
// Send framed message
OutputStream out = socket.getOutputStream();
byte[] framedMessage = MessageFraming.applyFraming(message, processorType);
out.write(framedMessage);
out.flush();

// Receive response with timeout
InputStream in = socket.getInputStream();
byte[] response = MessageFraming.readFramedResponse(in, processorType);
```

### Connection Health Check

**When:** Verifying processor connectivity before transactions

```java
public boolean isConnectionHealthy() {
    if (socket == null || socket.isClosed()) return false;
    try {
        socket.setSoTimeout(5000);
        return socket.isConnected() && !socket.isInputShutdown();
    } catch (Exception e) {
        return false;
    }
}
```

## See Also

- [patterns](references/patterns.md) - Connection patterns, error handling, retry logic
- [workflows](references/workflows.md) - Connection lifecycle, testing procedures

## Related Skills

- See the **hyosung-protocol** skill for message framing and STD1 protocol details
- See the **java** skill for threading patterns used with socket I/O