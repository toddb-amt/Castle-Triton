package castech.emvtxn.atm.host;

/**
 * Hyosung STD1 Message Parser (Unpacker)
 *
 * Parses incoming Hyosung protocol messages into response objects.
 * Supports both Standard (STX/ETX) and VISA (2-byte length) framing.
 */
public class HyosungMessageParser {

    private final HyosungProtocol.FramingType framingType;

    /**
     * Creates a message parser with specified framing type.
     *
     * @param framingType The framing type expected (STANDARD or VISA_LENGTH_PREFIX)
     */
    public HyosungMessageParser(HyosungProtocol.FramingType framingType) {
        this.framingType = framingType;
    }

    /**
     * Creates a message parser with Standard framing.
     */
    public HyosungMessageParser() {
        this(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Gets the framing type used by this parser.
     */
    public HyosungProtocol.FramingType getFramingType() {
        return framingType;
    }

    // =========================================================================
    // Generic Parsing
    // =========================================================================

    /**
     * Parses a raw framed message into field array.
     *
     * @param framedMessage The complete framed message bytes
     * @return Array of field values
     * @throws MessageFraming.FramingException if the message format is invalid
     */
    public String[] parseFields(byte[] framedMessage) throws MessageFraming.FramingException {
        byte[] content = MessageFraming.unframe(framedMessage, framingType);
        return MessageFraming.splitFields(content);
    }

    /**
     * Parses a raw framed message and auto-detects framing type.
     *
     * @param framedMessage The complete framed message bytes
     * @return Array of field values
     * @throws MessageFraming.FramingException if the message format is invalid
     */
    public String[] parseFieldsAuto(byte[] framedMessage) throws MessageFraming.FramingException {
        byte[] content = MessageFraming.unframeAuto(framedMessage);
        return MessageFraming.splitFields(content);
    }

    /**
     * Determines the message type from a framed message.
     *
     * @param framedMessage The complete framed message bytes
     * @return The message type code (85, 86, 87, 88, 89), or null if not determinable
     */
    public String getMessageType(byte[] framedMessage) {
        try {
            String[] fields = parseFields(framedMessage);
            if (fields.length >= 3) {
                return fields[2];
            }
        } catch (MessageFraming.FramingException e) {
            // Can't determine type
        }
        return null;
    }

    // =========================================================================
    // Transaction Response (Type 85)
    // =========================================================================

    /**
     * Parses a Transaction Response message.
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed TransactionResponse object
     * @throws ParseException if the message cannot be parsed
     */
    public TransactionResponse parseTransactionResponse(byte[] framedMessage) throws ParseException {
        try {
            String[] fields = parseFields(framedMessage);
            return parseTransactionResponseFromFields(fields);
        } catch (MessageFraming.FramingException e) {
            throw new ParseException("Failed to parse transaction response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a Transaction Response from field array.
     */
    public TransactionResponse parseTransactionResponseFromFields(String[] fields) throws ParseException {
        if (fields.length < 5) {
            throw new ParseException("Insufficient fields for transaction response: " + fields.length);
        }

        // Verify message type
        if (!HyosungProtocol.MSG_TYPE_TRANSACTION.equals(fields[2])) {
            throw new ParseException("Invalid message type for transaction response: " + fields[2]);
        }

        TransactionResponse response = new TransactionResponse();
        response.setInfoHeader(getField(fields, 0));
        response.setTerminalId(getField(fields, 1));
        response.setSequenceNumber(parseSequence(getField(fields, 3)));
        response.setResponseCode(getField(fields, 4));

        // Optional fields
        if (fields.length > 5) {
            response.setAuthorizationData(getField(fields, 5));
        }
        if (fields.length > 6) {
            response.setSettlementData(getField(fields, 6));
        }
        if (fields.length > 7) {
            response.setAccountBalanceCents(parseLong(getField(fields, 7)));
        }
        if (fields.length > 8) {
            response.setAvailableBalanceCents(parseLong(getField(fields, 8)));
        }
        if (fields.length > 9) {
            response.setSurchargeCents(parseLong(getField(fields, 9)));
        }
        if (fields.length > 10) {
            response.setDisplayMessage(getField(fields, 10));
        }
        if (fields.length > 11) {
            response.setConfigIndicator(getField(fields, 11));
        }
        if (fields.length > 12) {
            response.setEmvResponseData(getField(fields, 12));
        }

        return response;
    }

    // =========================================================================
    // Reversal Response (Type 86)
    // =========================================================================

    /**
     * Parses a Reversal Response message.
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed ReversalResponse object
     * @throws ParseException if the message cannot be parsed
     */
    public ReversalResponse parseReversalResponse(byte[] framedMessage) throws ParseException {
        try {
            String[] fields = parseFields(framedMessage);
            return parseReversalResponseFromFields(fields);
        } catch (MessageFraming.FramingException e) {
            throw new ParseException("Failed to parse reversal response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a Reversal Response from field array.
     */
    public ReversalResponse parseReversalResponseFromFields(String[] fields) throws ParseException {
        if (fields.length < 5) {
            throw new ParseException("Insufficient fields for reversal response: " + fields.length);
        }

        // Verify message type
        if (!HyosungProtocol.MSG_TYPE_REVERSAL.equals(fields[2])) {
            throw new ParseException("Invalid message type for reversal response: " + fields[2]);
        }

        ReversalResponse response = new ReversalResponse();
        response.setInfoHeader(getField(fields, 0));
        response.setTerminalId(getField(fields, 1));
        response.setSequenceNumber(parseSequence(getField(fields, 3)));
        response.setResponseCode(getField(fields, 4));

        return response;
    }

    // =========================================================================
    // Configuration Response (Type 88)
    // =========================================================================

    /**
     * Parses a Configuration Response message.
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed ConfigResponse object
     * @throws ParseException if the message cannot be parsed
     */
    public ConfigResponse parseConfigResponse(byte[] framedMessage) throws ParseException {
        try {
            String[] fields = parseFields(framedMessage);
            return parseConfigResponseFromFields(fields);
        } catch (MessageFraming.FramingException e) {
            throw new ParseException("Failed to parse config response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a Configuration Response from field array.
     */
    public ConfigResponse parseConfigResponseFromFields(String[] fields) throws ParseException {
        if (fields.length < 3) {
            throw new ParseException("Insufficient fields for config response: " + fields.length);
        }

        // Verify message type
        if (!HyosungProtocol.MSG_TYPE_CONFIG.equals(fields[2])) {
            throw new ParseException("Invalid message type for config response: " + fields[2]);
        }

        ConfigResponse response = new ConfigResponse();
        response.setInfoHeader(getField(fields, 0));
        response.setTerminalId(getField(fields, 1));

        // Field 3, 4 are reserved/empty
        // Field 5: Working Key Part 1 or TR-31 block
        if (fields.length > 5) {
            response.setWorkingKeyPart1(getField(fields, 5));
        }

        // Field 6: Surcharge
        if (fields.length > 6) {
            response.setSurchargeCents(parseLong(getField(fields, 6)));
        }

        // Field 7 is reserved/empty
        // Field 8: Working Key Part 2 (for standard format)
        if (fields.length > 8) {
            response.setWorkingKeyPart2(getField(fields, 8));
        }

        return response;
    }

    // =========================================================================
    // Host Totals Response (Type 87)
    // =========================================================================

    /**
     * Parses a Host Totals Response message.
     *
     * Response format:
     *   Field 0: Information Header
     *   Field 1: Terminal ID
     *   Field 2: Transaction Code (87)
     *   Field 3: Transaction Counts (16 chars: CCCCTTTTBBBBNNNN)
     *   Field 4: Total Cash Dispensed (cents)
     *   Field 5: Total Non-Cash (cents)
     *   Field 6: Total Surcharges (cents)
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed HostTotalsResponse object
     * @throws ParseException if the message cannot be parsed
     */
    public HostTotalsResponse parseHostTotalsResponse(byte[] framedMessage) throws ParseException {
        try {
            String[] fields = parseFields(framedMessage);
            return parseHostTotalsResponseFromFields(fields);
        } catch (MessageFraming.FramingException e) {
            throw new ParseException("Failed to parse host totals response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a Host Totals Response from field array.
     */
    public HostTotalsResponse parseHostTotalsResponseFromFields(String[] fields) throws ParseException {
        if (fields.length < 4) {
            throw new ParseException("Insufficient fields for host totals response: " + fields.length);
        }

        // Verify message type
        if (!HyosungProtocol.MSG_TYPE_HOST_TOTALS.equals(fields[2])) {
            throw new ParseException("Invalid message type for host totals response: " + fields[2]);
        }

        HostTotalsResponse response = new HostTotalsResponse();
        response.setTerminalId(getField(fields, 1));

        // Field 3: Transaction counts (16 chars: CCCCTTTTBBBBNNNN)
        response.setTransactionCounts(getField(fields, 3));

        // Field 4: Total Cash Dispensed (cents)
        if (fields.length > 4) {
            response.setTotalCashDispensed(parseLong(getField(fields, 4)));
        }

        // Field 5: Total Non-Cash (cents)
        if (fields.length > 5) {
            response.setTotalNonCash(parseLong(getField(fields, 5)));
        }

        // Field 6: Total Surcharges (cents)
        if (fields.length > 6) {
            response.setTotalSurcharges(parseLong(getField(fields, 6)));
        }

        response.setSuccess(true);
        return response;
    }

    // =========================================================================
    // Health Check Response (Type 89)
    // =========================================================================

    /**
     * Parses a Health Check Response message.
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed HealthCheckResponse object
     * @throws ParseException if the message cannot be parsed
     */
    public HealthCheckResponse parseHealthCheckResponse(byte[] framedMessage) throws ParseException {
        try {
            String[] fields = parseFields(framedMessage);
            return parseHealthCheckResponseFromFields(fields);
        } catch (MessageFraming.FramingException e) {
            throw new ParseException("Failed to parse health check response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a Health Check Response from field array.
     */
    public HealthCheckResponse parseHealthCheckResponseFromFields(String[] fields) throws ParseException {
        if (fields.length < 3) {
            throw new ParseException("Insufficient fields for health check response: " + fields.length);
        }

        // Verify message type
        if (!HyosungProtocol.MSG_TYPE_HEALTH_CHECK.equals(fields[2])) {
            throw new ParseException("Invalid message type for health check response: " + fields[2]);
        }

        HealthCheckResponse response = new HealthCheckResponse();
        response.setInfoHeader(getField(fields, 0));
        response.setTerminalId(getField(fields, 1));

        // Field 3: Status
        if (fields.length > 3) {
            response.setStatus(getField(fields, 3));
        }

        return response;
    }

    // =========================================================================
    // Auto-Detect and Parse
    // =========================================================================

    /**
     * Auto-detects message type and parses accordingly.
     *
     * @param framedMessage The complete framed message bytes
     * @return Parsed response object (type varies based on message)
     * @throws ParseException if the message cannot be parsed
     */
    public Object parseResponse(byte[] framedMessage) throws ParseException {
        String messageType = getMessageType(framedMessage);
        if (messageType == null) {
            throw new ParseException("Unable to determine message type");
        }

        switch (messageType) {
            case HyosungProtocol.MSG_TYPE_TRANSACTION:
                return parseTransactionResponse(framedMessage);
            case HyosungProtocol.MSG_TYPE_REVERSAL:
                return parseReversalResponse(framedMessage);
            case HyosungProtocol.MSG_TYPE_HOST_TOTALS:
                return parseHostTotalsResponse(framedMessage);
            case HyosungProtocol.MSG_TYPE_CONFIG:
                return parseConfigResponse(framedMessage);
            case HyosungProtocol.MSG_TYPE_HEALTH_CHECK:
                return parseHealthCheckResponse(framedMessage);
            default:
                throw new ParseException("Unknown message type: " + messageType);
        }
    }

    // =========================================================================
    // Control Character Detection
    // =========================================================================

    /**
     * Checks if the message is a single ACK byte.
     */
    public boolean isAck(byte[] message) {
        return message != null && message.length == 1 && message[0] == HyosungProtocol.ACK;
    }

    /**
     * Checks if the message is a single NAK byte.
     */
    public boolean isNak(byte[] message) {
        return message != null && message.length == 1 && message[0] == HyosungProtocol.NAK;
    }

    /**
     * Checks if the message is a single EOT byte.
     */
    public boolean isEot(byte[] message) {
        return message != null && message.length == 1 && message[0] == HyosungProtocol.EOT;
    }

    /**
     * Checks if the message is a control character (ACK, NAK, EOT, ENQ).
     */
    public boolean isControlMessage(byte[] message) {
        if (message == null || message.length != 1) {
            return false;
        }
        byte b = message[0];
        return b == HyosungProtocol.ACK || b == HyosungProtocol.NAK ||
               b == HyosungProtocol.EOT || b == HyosungProtocol.ENQ;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Safely gets a field value from the array.
     */
    private String getField(String[] fields, int index) {
        if (fields != null && index >= 0 && index < fields.length) {
            return fields[index];
        }
        return "";
    }

    /**
     * Parses a sequence number string to int.
     */
    private int parseSequence(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Parses a numeric string to long.
     */
    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =========================================================================
    // Static Factory Methods
    // =========================================================================

    /**
     * Creates a parser for DNS processor (Standard framing).
     */
    public static HyosungMessageParser forDns() {
        return new HyosungMessageParser(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Creates a parser for FIS processor (Standard framing).
     */
    public static HyosungMessageParser forFis() {
        return new HyosungMessageParser(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Creates a parser for Switch Commerce processor (VISA framing).
     */
    public static HyosungMessageParser forSwitchCommerce() {
        return new HyosungMessageParser(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    /**
     * Creates a parser for EFX processor (VISA framing).
     */
    public static HyosungMessageParser forEfx() {
        return new HyosungMessageParser(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    /**
     * Creates a parser for Cardtronics processor (VISA framing).
     */
    public static HyosungMessageParser forCardtronics() {
        return new HyosungMessageParser(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    // =========================================================================
    // Exception Class
    // =========================================================================

    /**
     * Exception thrown when message parsing fails.
     */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
