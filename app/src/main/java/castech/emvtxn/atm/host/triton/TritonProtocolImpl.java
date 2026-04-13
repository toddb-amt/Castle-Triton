package castech.emvtxn.atm.host.triton;

import android.util.Log;

import castech.emvtxn.atm.host.AtmProtocol;
import castech.emvtxn.atm.host.ConfigResponse;
import castech.emvtxn.atm.host.HealthCheckResponse;
import castech.emvtxn.atm.host.HostTotalsResponse;
import castech.emvtxn.atm.host.ReversalRequest;
import castech.emvtxn.atm.host.ReversalResponse;
import castech.emvtxn.atm.host.TransactionRequest;
import castech.emvtxn.atm.host.TransactionResponse;

/**
 * Triton Standard protocol implementation.
 * Implements the AtmProtocol interface using Triton TSCD 5.22 message format.
 *
 * Key differences from Hyosung STD1:
 * - Single framing type (STX/ETX/LRC) — no VISA variants
 * - Requires ENQ/ACK handshake before each request
 * - Separate transaction codes per operation (00=checking W/D, 30=checking BI, etc.)
 * - EMV data carried in Miscellaneous fields via FID 'ud'
 * - Configuration download via transaction code "60"
 * - PIN working key in FID '~'
 */
public class TritonProtocolImpl implements AtmProtocol {

    private static final String TAG = "TritonProtocol";

    private final TritonMessageBuilder builder;
    private final TritonMessageParser parser;

    /**
     * Creates a Triton protocol implementation with default settings.
     * Optional header fields disabled by default.
     */
    public TritonProtocolImpl() {
        this.builder = new TritonMessageBuilder();
        this.parser = new TritonMessageParser();
    }

    /**
     * Creates a Triton protocol implementation with full configuration.
     *
     * @param communicationsId  8-char processor identifier (null to skip optional headers)
     * @param terminalIdType    2-char terminal type ("t" for Triton)
     * @param softwareVersion   2-char software version number
     * @param encryptionMode    '0'=DES, '2'=3DES
     * @param includeOptionalHeader Whether to include the optional header group
     */
    public TritonProtocolImpl(String communicationsId, String terminalIdType,
                               String softwareVersion, char encryptionMode,
                               boolean includeOptionalHeader) {
        this.builder = new TritonMessageBuilder(communicationsId, terminalIdType,
                softwareVersion, encryptionMode, includeOptionalHeader);
        this.parser = new TritonMessageParser();
    }

    // =========================================================================
    // Message Building
    // =========================================================================

    @Override
    public byte[] buildTransactionRequest(TransactionRequest request) {
        Log.d(TAG, "Building Triton transaction request: type=" + request.getTransactionType());
        return builder.buildTransactionRequest(request);
    }

    @Override
    public byte[] buildReversalRequest(ReversalRequest request) {
        Log.d(TAG, "Building Triton reversal request, seq=" + request.getOriginalSequenceNumber());
        return builder.buildReversalRequest(request);
    }

    @Override
    public byte[] buildHostTotalsRequest(String terminalId, int sequenceNumber,
                                          boolean includeTerminalTotals,
                                          int withdrawalCount, int inquiryCount,
                                          int transferCount, long settlementCents) {
        Log.d(TAG, "Building Triton host totals request");
        return builder.buildHostTotalsRequest(terminalId, includeTerminalTotals,
                null, withdrawalCount, inquiryCount, transferCount, settlementCents);
    }

    @Override
    public byte[] buildConfigDownloadRequest(String terminalId) {
        Log.d(TAG, "Building Triton config download request");
        return builder.buildConfigDownloadRequest(terminalId);
    }

    @Override
    public byte[] buildHealthCheckRequest(String terminalId) {
        // Triton doesn't have a dedicated health check message type.
        // Use host totals (code 50) as a connectivity check.
        Log.d(TAG, "Building Triton health check (via host totals)");
        return builder.buildHostTotalsRequest(terminalId);
    }

    // =========================================================================
    // Message Parsing
    // =========================================================================

    @Override
    public TransactionResponse parseTransactionResponse(byte[] message) {
        try {
            return parser.parseTransactionResponse(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing transaction response: " + e.getMessage());
            return null;
        }
    }

    @Override
    public ReversalResponse parseReversalResponse(byte[] message) {
        try {
            return parser.parseReversalResponse(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing reversal response: " + e.getMessage());
            return null;
        }
    }

    @Override
    public HostTotalsResponse parseHostTotalsResponse(byte[] message) {
        try {
            return parser.parseHostTotalsResponse(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing host totals response: " + e.getMessage());
            return null;
        }
    }

    @Override
    public ConfigResponse parseConfigResponse(byte[] message) {
        try {
            return parser.parseConfigResponse(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing config response: " + e.getMessage());
            return null;
        }
    }

    @Override
    public HealthCheckResponse parseHealthCheckResponse(byte[] message) {
        try {
            return parser.parseHealthCheckResponse(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing health check response: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Protocol Control
    // =========================================================================

    @Override
    public byte[] buildAck() {
        return new byte[] { TritonProtocol.ACK };
    }

    @Override
    public byte[] buildNak() {
        return new byte[] { TritonProtocol.NAK };
    }

    @Override
    public byte[] buildEnq() {
        return new byte[] { TritonProtocol.ENQ };
    }

    /**
     * Triton Standard normally requires ENQ/ACK handshake before every request.
     * Controlled by GlobalPara.atmTritonEnqEnabled — some MUX/processors skip ENQ.
     */
    @Override
    public boolean requiresEnqHandshake() {
        return castech.emvtxn.GlobalPara.atmTritonEnqEnabled;
    }

    @Override
    public byte calculateLrc(byte[] data, int offset, int length) {
        return builder.calculateLrc(data, offset, length);
    }

    @Override
    public String getProtocolName() {
        return "Triton Standard (TSCD 5.22)";
    }
}
