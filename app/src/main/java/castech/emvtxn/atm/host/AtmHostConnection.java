package castech.emvtxn.atm.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * ATM Host Connection
 *
 * Manages TCP/TLS socket connections to ATM processors.
 * Supports both Triton Standard (ENQ/ACK → Request → Response → ACK → EOT)
 * and Hyosung STD1 (Request → Response → ACK → EOT) handshake protocols.
 *
 * Protocol selection is determined by ProcessorConfig.getProtocolType().
 */
public class AtmHostConnection {

    private static final String TAG = "AtmHostConnection";

    private final ProcessorConfig config;
    private final AtmProtocol protocol;
    private final HyosungMessageBuilder builder;
    private final HyosungMessageParser parser;

    private Socket socket;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;
    private volatile boolean connected;

    // Single-thread executor for socket close operations (W7 fix)
    private final java.util.concurrent.ExecutorService socketCloseExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Connection listener for status updates
    private ConnectionListener listener;

    /**
     * Creates a new connection for the specified processor.
     *
     * @param config Processor configuration
     */
    public AtmHostConnection(ProcessorConfig config) {
        this.config = config;
        this.protocol = config.createProtocol();
        this.builder = config.createMessageBuilder();
        this.parser = config.createMessageParser();
        this.connected = false;
    }

    /**
     * Sets the connection listener.
     */
    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    /**
     * Connects to the host processor.
     *
     * @throws ConnectionException if connection fails
     */
    public synchronized void connect() throws ConnectionException {
        if (connected) {
            log("Already connected");
            return;
        }

        try {
            log("Connecting to " + config.getHost() + ":" + config.getPort() +
                " (TLS=" + config.isUseTls() + ", version=" + config.getTlsVersion() + ")");

            if (config.isUseTls()) {
                log("Starting TLS connection...");
                connectTls();
            } else {
                log("Starting plain TCP connection...");
                connectPlain();
            }

            connected = true;
            notifyConnected();
            log("Connected successfully to " + config.getHost() + ":" + config.getPort());

        } catch (javax.net.ssl.SSLHandshakeException e) {
            log("SSL Handshake failed: " + e.getMessage());
            disconnect();
            throw new ConnectionException("TLS handshake failed (try disabling TLS?): " + e.getMessage(), e);
        } catch (javax.net.ssl.SSLException e) {
            log("SSL error: " + e.getMessage());
            disconnect();
            throw new ConnectionException("TLS error: " + e.getMessage(), e);
        } catch (java.net.SocketTimeoutException e) {
            log("Connection timeout: " + e.getMessage());
            disconnect();
            throw new ConnectionException("Connection timeout after " + config.getConnectionTimeout() + "ms", e);
        } catch (Exception e) {
            log("Connection failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            disconnect();
            throw new ConnectionException("Failed to connect: " + e.getMessage(), e);
        }
    }

    /**
     * Establishes a plain TCP connection.
     */
    private void connectPlain() throws IOException {
        socket = new Socket();
        socket.connect(
            new InetSocketAddress(config.getHost(), config.getPort()),
            config.getConnectionTimeout()
        );
        socket.setSoTimeout(config.getResponseTimeout());
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
    }

    /**
     * Establishes a TLS connection.
     * Uses system default trust manager (validates server certificates) in production.
     * Set config.setDevMode(true) for development with self-signed certs.
     */
    private void connectTls() throws IOException, NoSuchAlgorithmException, KeyManagementException {
        SSLSocketFactory factory;

        if (config.isDevMode()) {
            // Development only — trust all certs for testing with self-signed servers
            log("TLS: DEV MODE — certificate validation DISABLED");
            SSLContext sslContext = SSLContext.getInstance(config.getTlsVersion());
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };
            sslContext.init(null, trustAllCerts, new SecureRandom());
            factory = sslContext.getSocketFactory();
        } else {
            // Production — use system default trust manager (validates certificates)
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        // Create and connect socket
        socket = new Socket();
        socket.connect(
            new InetSocketAddress(config.getHost(), config.getPort()),
            config.getConnectionTimeout()
        );

        // Wrap in SSL
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(
            socket,
            config.getHost(),
            config.getPort(),
            true
        );

        // Enforce TLS 1.2 minimum
        sslSocket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        sslSocket.startHandshake();

        socket = sslSocket;
        socket.setSoTimeout(config.getResponseTimeout());
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
    }

    /**
     * Disconnects from the host.
     * Socket close is done on a background thread to avoid NetworkOnMainThreadException.
     */
    public synchronized void disconnect() {
        final java.net.Socket socketToClose = socket;
        socket = null;
        inputStream = null;
        outputStream = null;
        connected = false;

        // Close socket via executor to avoid NetworkOnMainThreadException
        // Uses single-thread executor instead of spawning new threads (W7 fix)
        if (socketToClose != null) {
            socketCloseExecutor.submit(() -> {
                try {
                    socketToClose.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            });
        }

        notifyDisconnected();
        log("Disconnected");
    }

    /**
     * Checks if connected.
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Returns the protocol implementation for this connection.
     */
    public AtmProtocol getProtocol() {
        return protocol;
    }

    /**
     * Returns the processor configuration.
     */
    public ProcessorConfig getConfig() {
        return config;
    }

    // =========================================================================
    // Transaction Methods
    // =========================================================================

    /**
     * Sends a transaction request and receives the response.
     * Implements full handshake: Request → Response → ACK → EOT
     *
     * @param request The transaction request
     * @return The transaction response
     * @throws ConnectionException if communication fails
     * @throws HyosungMessageParser.ParseException if response parsing fails
     */
    public TransactionResponse sendTransaction(TransactionRequest request)
            throws ConnectionException, HyosungMessageParser.ParseException {

        ensureConnected();

        byte[] requestMessage = builder.buildTransactionRequest(request);
        byte[] responseMessage = sendAndReceive(requestMessage);

        TransactionResponse response = parser.parseTransactionResponse(responseMessage);

        // Complete handshake
        completeHandshake();

        return response;
    }

    /**
     * Sends a reversal request and receives the response.
     *
     * @param request The reversal request
     * @return The reversal response
     * @throws ConnectionException if communication fails
     * @throws HyosungMessageParser.ParseException if response parsing fails
     */
    public ReversalResponse sendReversal(ReversalRequest request)
            throws ConnectionException, HyosungMessageParser.ParseException {

        ensureConnected();

        byte[] requestMessage = builder.buildReversalRequest(request);
        byte[] responseMessage = sendAndReceive(requestMessage);

        ReversalResponse response = parser.parseReversalResponse(responseMessage);

        // Complete handshake
        completeHandshake();

        return response;
    }

    /**
     * Sends a configuration request and receives the response.
     *
     * @param request The configuration request
     * @return The configuration response
     * @throws ConnectionException if communication fails
     * @throws HyosungMessageParser.ParseException if response parsing fails
     */
    public ConfigResponse sendConfigRequest(ConfigRequest request)
            throws ConnectionException, HyosungMessageParser.ParseException {

        ensureConnected();

        byte[] requestMessage = builder.buildConfigRequest(request);
        byte[] responseMessage = sendAndReceive(requestMessage);

        ConfigResponse response = parser.parseConfigResponse(responseMessage);

        // Complete handshake
        completeHandshake();

        return response;
    }

    /**
     * Sends a health check request (Type 89 - standard Hyosung).
     *
     * @param request The health check request
     * @return The health check response
     * @throws ConnectionException if communication fails
     * @throws HyosungMessageParser.ParseException if response parsing fails
     */
    public HealthCheckResponse sendHealthCheck(HealthCheckRequest request)
            throws ConnectionException, HyosungMessageParser.ParseException {

        ensureConnected();

        byte[] requestMessage = builder.buildHealthCheckRequest(request);
        byte[] responseMessage = sendAndReceive(requestMessage);

        HealthCheckResponse response = parser.parseHealthCheckResponse(responseMessage);

        // Complete handshake
        completeHandshake();

        return response;
    }

    /**
     * Sends a status monitoring request (Type H0 - EFX/Switch Commerce style).
     * This is the preferred method for testing connectivity with EFX processors.
     *
     * @return true if the host acknowledged the status monitoring message
     * @throws ConnectionException if communication fails
     */
    public boolean sendStatusMonitoring() throws ConnectionException {
        ensureConnected();

        byte[] requestMessage = builder.buildStatusMonitoringRequest(
            config.getTerminalId(),
            config.getInfoHeader()
        );

        log("Sending Status Monitoring (H0) message...");

        try {
            byte[] responseMessage = sendAndReceive(requestMessage);

            // For status monitoring, we just need to get a response back
            // The response format is: H0.000000|TerminalID|H0|
            if (responseMessage != null && responseMessage.length > 0) {
                log("Status Monitoring response received: " + responseMessage.length + " bytes");
                completeHandshake();
                return true;
            }
            return false;
        } catch (Exception e) {
            log("Status Monitoring failed: " + e.getMessage());
            throw new ConnectionException("Status Monitoring failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a Host Totals request (Type 87).
     *
     * @param reset If true, reset totals after query; if false, query only
     * @return The host totals response
     * @throws ConnectionException if communication fails
     * @throws HyosungMessageParser.ParseException if response parsing fails
     */
    public HostTotalsResponse sendHostTotalsRequest(boolean reset)
            throws ConnectionException, HyosungMessageParser.ParseException {

        ensureConnected();

        String resetFlag = reset ? HyosungProtocol.TOTALS_RESET : HyosungProtocol.TOTALS_QUERY;
        byte[] requestMessage = builder.buildHostTotalsRequest(
            config.getTerminalId(),
            config.getInfoHeader(),
            resetFlag
        );

        log("Sending Host Totals request (reset=" + reset + ")...");

        byte[] responseMessage = sendAndReceive(requestMessage);

        HostTotalsResponse response = parser.parseHostTotalsResponse(responseMessage);

        // Complete handshake
        completeHandshake();

        return response;
    }

    // =========================================================================
    // Low-Level Communication
    // =========================================================================

    /**
     * Performs the Triton ENQ/ACK handshake before sending a request.
     * Triton Standard requires: Terminal sends ENQ → Host responds ACK → Then send message.
     *
     * @throws ConnectionException if handshake fails
     */
    private void performEnqHandshake() throws ConnectionException {
        if (protocol == null || !protocol.requiresEnqHandshake()) {
            return; // Hyosung doesn't need ENQ
        }

        try {
            // Capture local references (W6 fix — prevent race with disconnect)
            OutputStream out = outputStream;
            InputStream in = inputStream;
            if (out == null || in == null) {
                throw new ConnectionException("Connection not established - streams are null");
            }

            log("ENQ handshake: sending ENQ...");
            out.write(protocol.buildEnq());
            out.flush();

            // Wait for ACK with timeout
            int savedTimeout = socket.getSoTimeout();
            socket.setSoTimeout(config.getAckTimeout());
            try {
                int response = in.read();
                if (response == 0x06) { // ACK
                    log("ENQ handshake: received ACK");
                } else if (response == 0x15) { // NAK
                    throw new ConnectionException("ENQ handshake: host sent NAK — busy or error");
                } else if (response == -1) {
                    throw new ConnectionException("ENQ handshake: connection closed by host");
                } else {
                    throw new ConnectionException("ENQ handshake: unexpected response 0x" +
                            String.format("%02X", response));
                }
            } finally {
                socket.setSoTimeout(savedTimeout);
            }
        } catch (IOException e) {
            throw new ConnectionException("ENQ handshake failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a message and waits for response.
     * For Triton protocol, performs ENQ/ACK handshake before sending.
     *
     * @param message The message to send
     * @return The response message
     * @throws ConnectionException if communication fails
     */
    private byte[] sendAndReceive(byte[] message) throws ConnectionException {
        try {
            // Triton: ENQ/ACK handshake before sending request
            performEnqHandshake();

            // Capture local references to prevent race condition (W6 fix)
            OutputStream out = outputStream;
            InputStream in = inputStream;
            if (out == null || in == null) {
                throw new ConnectionException("Connection not established - streams are null");
            }

            // Log outgoing message
            logMessage("TX", message);

            // Send the message
            out.write(message);
            out.flush();

            // Read response
            byte[] response = readResponse();

            // Log incoming message
            logMessage("RX", response);

            return response;

        } catch (NullPointerException e) {
            disconnect();
            throw new ConnectionException("Connection lost - streams became null", e);
        } catch (SocketTimeoutException e) {
            throw new ConnectionException("Response timeout", e);
        } catch (IOException e) {
            disconnect();
            throw new ConnectionException("Communication error: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a response from the socket.
     * Handles both Standard and VISA framing.
     */
    private byte[] readResponse() throws IOException, ConnectionException {
        // Verify stream is available
        if (inputStream == null) {
            throw new ConnectionException("Input stream is null - connection lost");
        }

        // Check for single-byte control messages first
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            throw new ConnectionException("Connection closed by host");
        }

        byte first = (byte) firstByte;

        // Single-byte control messages
        if (first == HyosungProtocol.ACK || first == HyosungProtocol.NAK ||
            first == HyosungProtocol.EOT || first == HyosungProtocol.ENQ) {
            return new byte[] { first };
        }

        switch (config.getFramingType()) {
            case VISA_LENGTH_PREFIX:
                return readVisaFramedMessage(first);
            case VISA_NO_STX_ETX:
                return readVisaNoStxEtxMessage(first);
            case STANDARD:
            default:
                return readStandardFramedMessage(first);
        }
    }

    /**
     * Reads a VISA framed message WITHOUT STX/ETX/LRC.
     * Format: [2-byte length][content]
     */
    private byte[] readVisaNoStxEtxMessage(byte firstByte) throws IOException, ConnectionException {
        if (inputStream == null) {
            throw new ConnectionException("Connection lost during read");
        }
        // First two bytes are length header (big-endian)
        int secondByte = inputStream.read();
        if (secondByte == -1) {
            throw new ConnectionException("Incomplete length header");
        }

        int messageLength = ((firstByte & 0xFF) << 8) | (secondByte & 0xFF);
        log("VISA_NO_STX_ETX: reading " + messageLength + " bytes after length header");

        if (messageLength <= 0 || messageLength > 8192) {
            throw new ConnectionException("Invalid message length: " + messageLength);
        }

        // Read the full message (length header + content)
        byte[] message = new byte[2 + messageLength];
        message[0] = firstByte;
        message[1] = (byte) secondByte;

        int totalRead = 0;
        while (totalRead < messageLength) {
            if (inputStream == null) {
                throw new ConnectionException("Connection lost during read");
            }
            int read = inputStream.read(message, 2 + totalRead, messageLength - totalRead);
            if (read == -1) {
                throw new ConnectionException("Unexpected end of stream");
            }
            totalRead += read;
        }

        log("VISA_NO_STX_ETX: received complete message: " + (2 + messageLength) + " bytes");
        return message;
    }

    /**
     * Reads a Standard framed message (STX...ETX LRC).
     */
    private byte[] readStandardFramedMessage(byte firstByte) throws IOException, ConnectionException {
        if (inputStream == null) {
            throw new ConnectionException("Connection lost during read");
        }
        // First byte should be STX
        if (firstByte != HyosungProtocol.STX) {
            throw new ConnectionException("Expected STX, got: " + String.format("0x%02X", firstByte));
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write(firstByte); // STX

        boolean foundEtx = false;
        int maxBytes = 8192; // Safety limit
        int bytesRead = 0;

        while (!foundEtx && bytesRead < maxBytes) {
            if (inputStream == null) {
                throw new ConnectionException("Connection lost during read");
            }
            int b = inputStream.read();
            if (b == -1) {
                throw new ConnectionException("Unexpected end of stream");
            }
            baos.write(b);
            bytesRead++;

            if ((byte) b == HyosungProtocol.ETX) {
                foundEtx = true;
                // Read LRC
                if (inputStream == null) {
                    throw new ConnectionException("Connection lost during LRC read");
                }
                int lrc = inputStream.read();
                if (lrc == -1) {
                    throw new ConnectionException("Missing LRC");
                }
                baos.write(lrc);
            }
        }

        if (!foundEtx) {
            throw new ConnectionException("ETX not found in message");
        }

        return baos.toByteArray();
    }

    /**
     * Reads a VISA framed message (length header + STX...ETX LRC).
     */
    private byte[] readVisaFramedMessage(byte firstByte) throws IOException, ConnectionException {
        if (inputStream == null) {
            throw new ConnectionException("Connection lost during read");
        }
        // First two bytes are length header (big-endian)
        int secondByte = inputStream.read();
        if (secondByte == -1) {
            throw new ConnectionException("Incomplete length header");
        }

        int messageLength = ((firstByte & 0xFF) << 8) | (secondByte & 0xFF);

        if (messageLength <= 0 || messageLength > 8192) {
            throw new ConnectionException("Invalid message length: " + messageLength);
        }

        // Read the full message
        byte[] message = new byte[2 + messageLength];
        message[0] = firstByte;
        message[1] = (byte) secondByte;

        int totalRead = 0;
        while (totalRead < messageLength) {
            if (inputStream == null) {
                throw new ConnectionException("Connection lost during read");
            }
            int read = inputStream.read(message, 2 + totalRead, messageLength - totalRead);
            if (read == -1) {
                throw new ConnectionException("Unexpected end of stream");
            }
            totalRead += read;
        }

        return message;
    }

    /**
     * Completes the handshake by sending ACK and waiting for EOT.
     */
    private void completeHandshake() throws ConnectionException {
        try {
            // Capture local references (W6 fix)
            OutputStream out = outputStream;
            if (out == null) {
                throw new ConnectionException("Output stream null during handshake");
            }

            // Send ACK — use protocol if available, fallback to builder
            byte[] ack = (protocol != null) ? protocol.buildAck() : builder.buildAck();
            out.write(ack);
            out.flush();
            log("Sent ACK");

            // Wait for EOT
            socket.setSoTimeout(config.getEotTimeout());
            byte[] eotResponse = readResponse();

            if (!parser.isEot(eotResponse)) {
                log("Warning: Expected EOT, got other response");
            } else {
                log("Received EOT - handshake complete");
            }

            // Restore normal timeout
            socket.setSoTimeout(config.getResponseTimeout());

        } catch (SocketTimeoutException e) {
            // EOT timeout is not critical
            log("EOT timeout (non-critical)");
        } catch (IOException e) {
            // W9 fix: propagate ACK send failure as ConnectionException
            throw new ConnectionException("Handshake completion error: " + e.getMessage(), e);
        }
    }

    /**
     * Sends NAK to request retransmission.
     */
    public void sendNak() throws ConnectionException {
        try {
            outputStream.write(builder.buildNak());
            outputStream.flush();
            log("Sent NAK");
        } catch (IOException e) {
            throw new ConnectionException("Failed to send NAK", e);
        }
    }

    /**
     * Ensures the connection is active.
     */
    private void ensureConnected() throws ConnectionException {
        if (!isConnected()) {
            connect();
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private void log(String message) {
        android.util.Log.d(TAG, "[" + config.getName() + "] " + message);
    }

    private void logMessage(String direction, byte[] message) {
        if (message == null) return;

        // Only log message length and type — NEVER log full message content
        // Full messages contain Track 2 (Field 6) and PIN blocks (Field 8)
        log(direction + " [" + message.length + " bytes]");
    }

    // =========================================================================
    // Listener Notifications
    // =========================================================================

    private void notifyConnected() {
        if (listener != null) {
            listener.onConnected(config.getName());
        }
    }

    private void notifyDisconnected() {
        if (listener != null) {
            listener.onDisconnected(config.getName());
        }
    }

    // =========================================================================
    // Inner Classes
    // =========================================================================

    /**
     * Connection state listener.
     */
    public interface ConnectionListener {
        void onConnected(String processorName);
        void onDisconnected(String processorName);
        void onError(String processorName, String error);
    }

    /**
     * Exception for connection errors.
     */
    public static class ConnectionException extends Exception {
        public ConnectionException(String message) {
            super(message);
        }

        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
