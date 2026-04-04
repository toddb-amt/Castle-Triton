package castech.emvtxn.atm.host;

/**
 * Hyosung STD1 Message Builder (Packer)
 *
 * Builds properly formatted Hyosung protocol messages from request objects.
 * Supports both Standard (STX/ETX) and VISA (2-byte length) framing.
 */
public class HyosungMessageBuilder {

    private static final String TAG = "HyosungMessageBuilder";
    private final HyosungProtocol.FramingType framingType;

    /**
     * Safe logging that doesn't throw in unit tests.
     */
    private static void log(String message) {
        try {
            android.util.Log.d(TAG, message);
        } catch (RuntimeException e) {
            // Ignore - running in unit test environment
        }
    }

    /**
     * Creates a message builder with specified framing type.
     *
     * @param framingType The framing type to use (STANDARD or VISA_LENGTH_PREFIX)
     */
    public HyosungMessageBuilder(HyosungProtocol.FramingType framingType) {
        this.framingType = framingType;
    }

    /**
     * Creates a message builder with Standard framing.
     */
    public HyosungMessageBuilder() {
        this(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Gets the framing type used by this builder.
     */
    public HyosungProtocol.FramingType getFramingType() {
        return framingType;
    }

    // =========================================================================
    // Transaction Request (Type 85)
    // =========================================================================

    /**
     * Builds a Transaction Request message.
     *
     * @param request The TransactionRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildTransactionRequest(TransactionRequest request) {
        String[] fields = new String[14];

        fields[0] = request.getInfoHeader();                    // Field 0: Info Header
        fields[1] = request.getTerminalId();                    // Field 1: Terminal ID
        fields[2] = HyosungProtocol.MSG_TYPE_TRANSACTION;       // Field 2: Transaction Code
        fields[3] = request.getTransactionType();               // Field 3: Transaction Type
        fields[4] = request.getSequenceNumberString();          // Field 4: Sequence Number
        fields[5] = "";                                         // Field 5: Reserved

        // Field 6: Track 2 Data
        // CRITICAL: For EMV (chip/contactless), use CLEAR Track 2 - security comes from ARQC
        //           For MSR (swipe), use ENCRYPTED Track 2 - no ARQC protection
        String track2ForMessage;
        String ksnForField7 = "";
        boolean hasEmvData = request.getEmvData() != null && !request.getEmvData().isEmpty();

        if (hasEmvData) {
            // EMV transaction (chip/contactless): Use CLEAR Track 2
            // The ARQC in EMV data provides cryptographic protection
            track2ForMessage = nullToEmpty(request.getTrack2Data());
            // CRITICAL: Use PIN KSN (not Track2 KSN) for DUKPT PIN decryption
            // DUKPT counter increments for each operation - PIN is encrypted first,
            // so PIN KSN differs from Track2 KSN (e.g., PIN=00A7, Track2=00A8)
            String pinKsn = request.getPinKsn();
            if (pinKsn != null && !pinKsn.isEmpty()) {
                ksnForField7 = pinKsn;
                log("TRACK2 (EMV): Using PIN KSN for Field 7: " + ksnForField7);
            } else {
                // Fallback to Track2 KSN if PIN KSN not available
                ksnForField7 = nullToEmpty(request.getTrack2Ksn());
                log("TRACK2 (EMV): WARNING - No PIN KSN, falling back to Track2 KSN: " + ksnForField7);
            }
            log("TRACK2 (EMV): Using CLEAR track 2 - ARQC provides security");
            log("  Field 6 (clear): [" + track2ForMessage + "]");
            log("  Field 7 (KSN for PIN): " + ksnForField7);
        } else if (request.hasEncryptedTrack2()) {
            // MSR transaction (swipe): Use ENCRYPTED Track 2
            // No ARQC, so track data must be protected with DUKPT
            track2ForMessage = "e" + request.getEncryptedTrack2();
            ksnForField7 = request.getTrack2Ksn();
            log("TRACK2 (MSR): Using DUKPT encrypted track 2");
            log("  Field 6 (encrypted data): " + track2ForMessage.substring(0, Math.min(40, track2ForMessage.length())) + "...");
            log("  Field 7 (KSN): " + ksnForField7);
        } else {
            // Fallback: Use clear track 2 if available
            track2ForMessage = nullToEmpty(request.getTrack2Data());
            log("TRACK2 (fallback): [" + track2ForMessage + "]");
        }
        fields[6] = track2ForMessage;                           // Field 6: Track 2 Data

        fields[7] = ksnForField7;                               // Field 7: KSN (for DUKPT PIN decryption)
        fields[8] = nullToEmpty(request.getPinBlock());         // Field 8: PIN Block
        fields[9] = String.valueOf(request.getAmountCents());   // Field 9: Amount
        fields[10] = String.valueOf(request.getSurchargeCents()); // Field 10: Surcharge
        fields[11] = request.getDispenseFlag();                 // Field 11: Dispense Flag
        fields[12] = nullToEmpty(request.getStatusMonitoring()); // Field 12: Status Monitoring
        // Field 13: EMV Data - must have "ud" prefix per Hyosung spec
        String emvData = nullToEmpty(request.getEmvData());
        String emvField = emvData.isEmpty() ? "" : "ud" + emvData;
        log("DEBUG EMV: Raw EMV data length=" + emvData.length() +
            ", first 60 chars: [" + (emvData.length() > 60 ? emvData.substring(0, 60) + "..." : emvData) + "]");
        log("DEBUG EMV: Field 13 will be (first 80 chars): [" +
            (emvField.length() > 80 ? emvField.substring(0, 80) + "..." : emvField) + "]");
        fields[13] = emvField;

        return frameMessage(fields);
    }

    // =========================================================================
    // Reversal Request (Type 86)
    // =========================================================================

    /**
     * Builds a Reversal Request message.
     *
     * @param request The ReversalRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildReversalRequest(ReversalRequest request) {
        String[] fields = new String[11];

        fields[0] = request.getInfoHeader();                    // Field 0: Info Header
        fields[1] = request.getTerminalId();                    // Field 1: Terminal ID
        fields[2] = HyosungProtocol.MSG_TYPE_REVERSAL;          // Field 2: Transaction Code
        fields[3] = nullToEmpty(request.getOriginalAuthData()); // Field 3: Original Auth Data
        fields[4] = request.getOriginalSequenceNumberString();  // Field 4: Original Sequence
        fields[5] = nullToEmpty(request.getTrack2Data());       // Field 5: Track 2 Data
        fields[6] = "";                                         // Field 6: Reserved
        fields[7] = nullToEmpty(request.getPinBlock());         // Field 7: PIN Block
        fields[8] = String.valueOf(request.getOriginalAmountCents()); // Field 8: Original Amount
        fields[9] = String.valueOf(request.getOriginalSurchargeCents()); // Field 9: Original Surcharge
        fields[10] = nullToEmpty(request.getReversalReason());  // Field 10: Reversal Reason

        return frameMessage(fields);
    }

    // =========================================================================
    // Configuration Request (Type 88)
    // =========================================================================

    /**
     * Builds a Configuration Request message.
     *
     * @param request The ConfigRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildConfigRequest(ConfigRequest request) {
        String[] fields = new String[4];

        fields[0] = request.getInfoHeader();                    // Field 0: Info Header
        fields[1] = request.getTerminalId();                    // Field 1: Terminal ID
        fields[2] = HyosungProtocol.MSG_TYPE_CONFIG;            // Field 2: Transaction Code
        fields[3] = request.getConfigType();                    // Field 3: Config Type

        return frameMessage(fields);
    }

    // =========================================================================
    // Health Check / Status Monitoring Request
    // =========================================================================

    /**
     * Builds a Health Check Request message (Type 89 - standard Hyosung).
     *
     * @param request The HealthCheckRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildHealthCheckRequest(HealthCheckRequest request) {
        String[] fields = new String[3];

        fields[0] = request.getInfoHeader();                    // Field 0: Info Header
        fields[1] = request.getTerminalId();                    // Field 1: Terminal ID
        fields[2] = HyosungProtocol.MSG_TYPE_HEALTH_CHECK;      // Field 2: Transaction Code

        return frameMessage(fields);
    }

    /**
     * Builds a Status Monitoring Request message (Type H0 - EFX/Switch Commerce style).
     * This format includes date/time and terminal status data.
     *
     * Format: H0.000000|TerminalID|H0|DateTime|||||||StatusData
     *
     * @param terminalId Terminal ID
     * @param infoHeader Information header (can use default)
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildStatusMonitoringRequest(String terminalId, String infoHeader) {
        String[] fields = new String[12];

        // Field 0: Info Header
        fields[0] = infoHeader != null ? infoHeader :
                    HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);

        // Field 1: Terminal ID
        fields[1] = terminalId;

        // Field 2: Transaction Code (H0 for status monitoring)
        fields[2] = HyosungProtocol.MSG_TYPE_STATUS_MONITORING;

        // Field 3: Date/Time (MMDDYYHHMMSS)
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMddyyHHmmss");
        fields[3] = sdf.format(new java.util.Date());

        // Fields 4-10: Empty (reserved)
        fields[4] = "";
        fields[5] = "";
        fields[6] = "";
        fields[7] = "";
        fields[8] = "";
        fields[9] = "";
        fields[10] = "";

        // Field 11: Status Monitoring Data
        // Use EmvTagEnhancer.buildStatusMonitoring() for consistent format with Field 12
        fields[11] = EmvTagEnhancer.buildStatusMonitoring();

        return frameMessage(fields);
    }

    // =========================================================================
    // Host Totals Request (Type 87)
    // =========================================================================

    /**
     * Builds a Host Totals Request message.
     *
     * @param terminalId Terminal ID
     * @param infoHeader Information header (can use default)
     * @param resetFlag "0" = query only, "1" = reset after query
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildHostTotalsRequest(String terminalId, String infoHeader, String resetFlag) {
        String[] fields = new String[4];

        fields[0] = infoHeader != null ? infoHeader :
                    HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);
        fields[1] = terminalId;
        fields[2] = HyosungProtocol.MSG_TYPE_HOST_TOTALS;
        fields[3] = resetFlag != null ? resetFlag : HyosungProtocol.TOTALS_QUERY;

        return frameMessage(fields);
    }

    // =========================================================================
    // ACK/NAK/EOT Messages
    // =========================================================================

    /**
     * Builds an ACK (Acknowledge) message.
     *
     * @return Single ACK byte
     */
    public byte[] buildAck() {
        return new byte[] { HyosungProtocol.ACK };
    }

    /**
     * Builds a NAK (Negative Acknowledge) message.
     *
     * @return Single NAK byte
     */
    public byte[] buildNak() {
        return new byte[] { HyosungProtocol.NAK };
    }

    /**
     * Builds an EOT (End of Transmission) message.
     *
     * @return Single EOT byte
     */
    public byte[] buildEot() {
        return new byte[] { HyosungProtocol.EOT };
    }

    // =========================================================================
    // Generic Message Building
    // =========================================================================

    /**
     * Builds a generic message from field array.
     *
     * @param fields Array of field values
     * @return Framed message bytes
     */
    public byte[] buildMessage(String[] fields) {
        return frameMessage(fields);
    }

    /**
     * Builds a raw message (content only, no framing).
     *
     * @param fields Array of field values
     * @return Content bytes joined with FS separators
     */
    public byte[] buildContent(String[] fields) {
        return MessageFraming.joinFields(fields);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Frames the message content using the configured framing type.
     */
    private byte[] frameMessage(String[] fields) {
        byte[] content = MessageFraming.joinFields(fields);
        return MessageFraming.frame(content, framingType);
    }

    /**
     * Converts null to empty string.
     */
    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    // =========================================================================
    // Static Factory Methods
    // =========================================================================

    /**
     * Creates a builder for DNS processor (Standard framing).
     */
    public static HyosungMessageBuilder forDns() {
        return new HyosungMessageBuilder(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Creates a builder for FIS processor (Standard framing).
     */
    public static HyosungMessageBuilder forFis() {
        return new HyosungMessageBuilder(HyosungProtocol.FramingType.STANDARD);
    }

    /**
     * Creates a builder for Switch Commerce processor (VISA framing).
     */
    public static HyosungMessageBuilder forSwitchCommerce() {
        return new HyosungMessageBuilder(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    /**
     * Creates a builder for EFX processor (VISA framing).
     */
    public static HyosungMessageBuilder forEfx() {
        return new HyosungMessageBuilder(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    /**
     * Creates a builder for Cardtronics processor (VISA framing).
     */
    public static HyosungMessageBuilder forCardtronics() {
        return new HyosungMessageBuilder(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
    }

    // =========================================================================
    // Debug/Utility Methods
    // =========================================================================

    /**
     * Converts message bytes to hex string for debugging.
     *
     * @param message The message bytes
     * @return Hex string representation
     */
    public static String toHexString(byte[] message) {
        if (message == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(message.length * 3);
        for (byte b : message) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Converts message bytes to readable string for debugging.
     * Shows control characters as names.
     *
     * @param message The message bytes
     * @return Readable string representation
     */
    public static String toReadableString(byte[] message) {
        if (message == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : message) {
            switch (b) {
                case HyosungProtocol.STX:
                    sb.append("<STX>");
                    break;
                case HyosungProtocol.ETX:
                    sb.append("<ETX>");
                    break;
                case HyosungProtocol.FS:
                    sb.append("<FS>");
                    break;
                case HyosungProtocol.ACK:
                    sb.append("<ACK>");
                    break;
                case HyosungProtocol.NAK:
                    sb.append("<NAK>");
                    break;
                case HyosungProtocol.EOT:
                    sb.append("<EOT>");
                    break;
                case HyosungProtocol.ENQ:
                    sb.append("<ENQ>");
                    break;
                default:
                    if (b >= 0x20 && b < 0x7F) {
                        sb.append((char) b);
                    } else {
                        sb.append(String.format("<%02X>", b & 0xFF));
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
