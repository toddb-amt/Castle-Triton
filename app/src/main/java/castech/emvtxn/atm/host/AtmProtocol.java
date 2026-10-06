package castech.emvtxn.atm.host;

/**
 * Protocol abstraction interface for ATM host communication.
 * Implementations: TritonProtocolImpl (Triton Standard) and HyosungProtocolImpl (Hyosung STD1).
 *
 * This interface decouples the transaction flow from the specific message format,
 * allowing the app to switch between protocols via ProcessorConfig.
 */
public interface AtmProtocol {

    // =========================================================================
    // Message Building
    // =========================================================================

    /**
     * Builds a transaction request message (withdrawal, balance inquiry, etc.)
     */
    byte[] buildTransactionRequest(TransactionRequest request);

    /**
     * Builds a reversal request message.
     */
    byte[] buildReversalRequest(ReversalRequest request);

    /**
     * Builds a host totals / batch close request message.
     */
    byte[] buildHostTotalsRequest(String terminalId, int sequenceNumber, boolean includeTerminalTotals,
                                   int withdrawalCount, int inquiryCount, int transferCount, long settlementCents);

    /**
     * Builds a configuration download request message.
     */
    byte[] buildConfigDownloadRequest(String terminalId);

    /**
     * Builds a health check / keep-alive message.
     */
    byte[] buildHealthCheckRequest(String terminalId);

    // =========================================================================
    // Message Parsing
    // =========================================================================

    /**
     * Parses a transaction response message.
     */
    TransactionResponse parseTransactionResponse(byte[] message);

    /**
     * Parses a reversal response message.
     */
    ReversalResponse parseReversalResponse(byte[] message);

    /**
     * Parses a host totals response message.
     */
    HostTotalsResponse parseHostTotalsResponse(byte[] message);

    /**
     * Parses a configuration download response message.
     */
    ConfigResponse parseConfigResponse(byte[] message);

    /**
     * Parses a health check response message.
     */
    HealthCheckResponse parseHealthCheckResponse(byte[] message);

    // =========================================================================
    // Protocol Control
    // =========================================================================

    /**
     * Builds an ACK byte.
     */
    byte[] buildAck();

    /**
     * Builds a NAK byte.
     */
    byte[] buildNak();

    /**
     * Builds an ENQ byte (Triton requires ENQ before request; Hyosung does not).
     */
    byte[] buildEnq();

    /**
     * Returns true if this protocol requires an ENQ/ACK handshake before sending requests.
     */
    boolean requiresEnqHandshake();

    /**
     * Calculates LRC checksum for the given data.
     */
    byte calculateLrc(byte[] data, int offset, int length);

    /**
     * Returns the protocol name for logging.
     */
    String getProtocolName();
}
