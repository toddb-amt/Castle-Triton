package castech.emvtxn.atm.host.triton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import castech.emvtxn.atm.host.TransactionRequest;
import castech.emvtxn.atm.host.ReversalRequest;

/**
 * Triton Standard Message Builder (TSCD 5.22)
 *
 * Builds properly formatted Triton protocol request messages for ATM
 * terminal communication with host processors. Uses STX/ETX framing
 * with FS (0x1C) field separators and LRC checksum.
 *
 * <p>Message structure (Transaction Request):
 * <pre>
 * STX | [CommID(8) | FS | TermIdType(2) | FS | SWVersion(2) | FS |
 *        EncryptionMode(1) | FS | InfoHeader(7) | FS] |
 *        TerminalID(15) | FS | TxnCode(2) | FS | SeqNum(4) | FS |
 *        Track2(var) | FS | Amount1(8) | FS | Amount2(8) | FS |
 *        PINBlock(16) | FS | Misc1 | FS | Misc2 | FS |
 *        StatusMonitoring | FS | MiscX... | ETX | LRC
 * </pre>
 *
 * <p>The CommID through InfoHeader fields are optional as a group and
 * can be enabled/disabled via the {@code includeOptionalHeader} flag.
 *
 * <p>Miscellaneous fields use FID (Field ID) codes to identify their
 * content. Single-character FIDs are written directly after FS.
 * Multi-character FIDs (e.g., "ud" for EMV data) follow the same pattern.
 */
public class TritonMessageBuilder {

    private static final String TAG = "TritonMessageBuilder";

    private final String communicationsId;
    private final String terminalIdType;
    private final String softwareVersion;
    private final char encryptionMode;
    private final boolean includeOptionalHeader;

    /**
     * Safe logging that does not throw in unit tests.
     */
    private static void log(String message) {
        try {
            android.util.Log.d(TAG, message);
        } catch (RuntimeException e) {
            // Ignore - running in unit test environment
        }
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a Triton message builder.
     *
     * @param communicationsId    8-character communications identifier (null to skip)
     * @param terminalIdType      2-character terminal identifier type (null if not used)
     * @param softwareVersion     2-character software version (null if not used)
     * @param encryptionMode      Encryption mode: '0'=DES, '2'=3DES
     * @param includeOptionalHeader Whether to include CommID/TermIdType/SWVer/EncMode/InfoHeader group
     */
    public TritonMessageBuilder(String communicationsId, String terminalIdType,
                                String softwareVersion, char encryptionMode,
                                boolean includeOptionalHeader) {
        this.communicationsId = communicationsId;
        this.terminalIdType = terminalIdType;
        this.softwareVersion = softwareVersion;
        this.encryptionMode = encryptionMode;
        this.includeOptionalHeader = includeOptionalHeader;
    }

    /**
     * Creates a Triton message builder with no optional header fields.
     * Uses 3DES encryption mode by default.
     */
    public TritonMessageBuilder() {
        this(null, null, null, TritonProtocol.ENCRYPTION_TRIPLE_DES, false);
    }

    /**
     * Creates a Triton message builder with the optional header group enabled.
     *
     * @param communicationsId 8-character communications identifier
     * @param encryptionMode   Encryption mode character
     */
    public TritonMessageBuilder(String communicationsId, char encryptionMode) {
        this(communicationsId, null, null, encryptionMode, true);
    }

    // =========================================================================
    // Transaction Request
    // =========================================================================

    /**
     * Builds a Transaction Request message for withdrawal or balance inquiry.
     *
     * <p>Maps the request's transaction type and account type to the
     * appropriate Triton transaction code via
     * {@link TritonProtocol#getTransactionCode(String, String)}.
     *
     * <p>Track 2 data is sent excluding start/end sentinels and LRC.
     * Amount fields are 8-digit zero-padded values in cents.
     * PIN block is 16 hex characters (ANSI X9.8).
     *
     * <p>Miscellaneous fields are appended as FID + data:
     * <ul>
     *   <li>FID 'S' - DUKPT KSN (if available)</li>
     *   <li>FID "ud" - EMV tagged data (if available)</li>
     * </ul>
     *
     * @param request The TransactionRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildTransactionRequest(TransactionRequest request) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Optional header group
            if (includeOptionalHeader) {
                writeOptionalHeader(baos, request.getInfoHeader());
            }

            // Terminal ID (15 chars, space-padded right)
            String terminalId = padRight(nullToEmpty(request.getTerminalId()),
                    TritonProtocol.TERMINAL_ID_LENGTH);
            baos.write(terminalId.getBytes());

            // Transaction Code (2 chars)
            String txnCode = TritonProtocol.getTransactionCode(
                    getTritonTransactionType(request), getTritonAccountType(request));
            addField(baos, txnCode);

            // Sequence Number (4 digits, zero-padded)
            addField(baos, request.getSequenceNumberString());

            // Track 2 Data (variable length, no sentinels)
            String track2 = stripSentinels(nullToEmpty(request.getTrack2Data()));
            addField(baos, track2);

            // Amount 1 (8 digits, zero-padded, cents)
            addField(baos, TritonProtocol.zeroPad(request.getAmountCents(),
                    TritonProtocol.AMOUNT_LENGTH));

            // Amount 2 / Surcharge (8 digits, zero-padded, cents)
            addField(baos, TritonProtocol.zeroPad(request.getSurchargeCents(),
                    TritonProtocol.AMOUNT_LENGTH));

            // PIN Block (16 hex chars)
            String pinBlock = nullToEmpty(request.getPinBlock());
            if (pinBlock.length() < TritonProtocol.PIN_BLOCK_LENGTH) {
                pinBlock = TritonProtocol.zeroPad(pinBlock, TritonProtocol.PIN_BLOCK_LENGTH);
            }
            addField(baos, pinBlock);

            // Status Monitoring (miscellaneous field, no FID prefix)
            addField(baos, nullToEmpty(request.getStatusMonitoring()));

            // Miscellaneous FID fields

            // DUKPT KSN (FID 'S') - if available
            String pinKsn = request.getPinKsn();
            if (pinKsn != null && !pinKsn.isEmpty()) {
                addMiscField(baos, TritonProtocol.FID_DUKPT_KSN, pinKsn);
                log("Added DUKPT PIN KSN (FID S): " + pinKsn);
            } else {
                String track2Ksn = request.getTrack2Ksn();
                if (track2Ksn != null && !track2Ksn.isEmpty()) {
                    addMiscField(baos, TritonProtocol.FID_DUKPT_KSN, track2Ksn);
                    log("Added DUKPT Track2 KSN (FID S): " + track2Ksn);
                }
            }

            // EMV tagged data (FID "ud") - if available
            String emvData = request.getEmvData();
            if (emvData != null && !emvData.isEmpty()) {
                addMiscField(baos, TritonProtocol.FID_EMV_TAGGED_DATA, emvData);
                log("Added EMV data (FID ud): length=" + emvData.length());
            }

        } catch (IOException e) {
            log("Error building transaction request: " + e.getMessage());
            return new byte[0];
        }

        return frameMessage(baos.toByteArray());
    }

    // =========================================================================
    // Reversal Request
    // =========================================================================

    /**
     * Builds a Reversal Request message (transaction code "29").
     *
     * <p>The reversal includes the original transaction's sequence number,
     * track 2 data, requested amount (Amount 1), surcharge (Amount 2),
     * and dispensed amount (Amount 3). A reversal reason code is sent
     * as FID 'n'.
     *
     * @param request           The ReversalRequest object
     * @param dispensedAmountCents Amount actually dispensed (cents) for Amount 3
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildReversalRequest(ReversalRequest request, long dispensedAmountCents) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Optional header group
            if (includeOptionalHeader) {
                writeOptionalHeader(baos, request.getInfoHeader());
            }

            // Terminal ID (15 chars, space-padded right)
            String terminalId = padRight(nullToEmpty(request.getTerminalId()),
                    TritonProtocol.TERMINAL_ID_LENGTH);
            baos.write(terminalId.getBytes());

            // Transaction Code "29" (Reversal)
            addField(baos, TritonProtocol.TXN_REVERSAL);

            // Sequence Number of original transaction (4 digits, zero-padded)
            addField(baos, request.getOriginalSequenceNumberString());

            // Track 2 Data (variable length, no sentinels)
            String track2 = stripSentinels(nullToEmpty(request.getTrack2Data()));
            addField(baos, track2);

            // Amount 1: Original requested amount (8 digits, zero-padded, cents)
            addField(baos, TritonProtocol.zeroPad(request.getOriginalAmountCents(),
                    TritonProtocol.AMOUNT_LENGTH));

            // Amount 2: Original surcharge (8 digits, zero-padded, cents)
            addField(baos, TritonProtocol.zeroPad(request.getOriginalSurchargeCents(),
                    TritonProtocol.AMOUNT_LENGTH));

            // Amount 3: Dispensed amount (8 digits, zero-padded, cents)
            addField(baos, TritonProtocol.zeroPad(dispensedAmountCents,
                    TritonProtocol.AMOUNT_LENGTH));

            // PIN Block (16 hex chars)
            String pinBlock = nullToEmpty(request.getPinBlock());
            if (pinBlock.length() < TritonProtocol.PIN_BLOCK_LENGTH) {
                pinBlock = TritonProtocol.zeroPad(pinBlock, TritonProtocol.PIN_BLOCK_LENGTH);
            }
            addField(baos, pinBlock);

            // Status Monitoring
            addField(baos, "");

            // Reversal Reason Code (FID 'n')
            String reason = request.getReversalReason();
            if (reason != null && !reason.isEmpty()) {
                addMiscField(baos, TritonProtocol.FID_REVERSAL_REASON, reason);
                log("Added reversal reason (FID n): " + reason);
            }

        } catch (IOException e) {
            log("Error building reversal request: " + e.getMessage());
            return new byte[0];
        }

        return frameMessage(baos.toByteArray());
    }

    /**
     * Builds a Reversal Request message with dispensed amount defaulting to zero.
     *
     * @param request The ReversalRequest object
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildReversalRequest(ReversalRequest request) {
        return buildReversalRequest(request, 0);
    }

    // =========================================================================
    // Host Totals Request
    // =========================================================================

    /**
     * Builds a Host Totals Request message (transaction code "50" or "51").
     *
     * <p>Code "50" queries host totals without resetting them.
     * Code "51" queries and resets host totals.
     *
     * <p>Optional terminal totals can be appended when the terminal
     * maintains its own counters for reconciliation:
     * <ul>
     *   <li>Withdrawal count (4 digits)</li>
     *   <li>Inquiry count (4 digits)</li>
     *   <li>Transfer count (4 digits)</li>
     *   <li>Settlement total (12 digits, cents)</li>
     * </ul>
     *
     * @param terminalId      Terminal identifier
     * @param resetAfterQuery true to use code "51" (query and reset), false for "50" (query only)
     * @param statusMonitoring Terminal status monitoring data (may be null)
     * @param withdrawalCount  Terminal withdrawal count (negative to omit totals)
     * @param inquiryCount     Terminal inquiry count
     * @param transferCount    Terminal transfer count
     * @param settlementCents  Terminal settlement total in cents
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildHostTotalsRequest(String terminalId, boolean resetAfterQuery,
                                         String statusMonitoring,
                                         int withdrawalCount, int inquiryCount,
                                         int transferCount, long settlementCents) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Optional header group (uses default info header)
            if (includeOptionalHeader) {
                writeOptionalHeader(baos, null);
            }

            // Terminal ID (15 chars, space-padded right)
            baos.write(padRight(nullToEmpty(terminalId),
                    TritonProtocol.TERMINAL_ID_LENGTH).getBytes());

            // Transaction Code ("50" or "51")
            String txnCode = resetAfterQuery
                    ? TritonProtocol.TXN_HOST_TOTALS_RESET
                    : TritonProtocol.TXN_HOST_TOTALS;
            addField(baos, txnCode);

            // Status Monitoring
            addField(baos, nullToEmpty(statusMonitoring));

            // Terminal Totals (optional - included if withdrawalCount >= 0)
            if (withdrawalCount >= 0) {
                // Withdrawal count (4 digits)
                addField(baos, TritonProtocol.zeroPad(withdrawalCount,
                        TritonProtocol.SEQUENCE_NUMBER_LENGTH));

                // Inquiry count (4 digits)
                addField(baos, TritonProtocol.zeroPad(inquiryCount,
                        TritonProtocol.SEQUENCE_NUMBER_LENGTH));

                // Transfer count (4 digits)
                addField(baos, TritonProtocol.zeroPad(transferCount,
                        TritonProtocol.SEQUENCE_NUMBER_LENGTH));

                // Settlement total (12 digits, cents)
                addField(baos, TritonProtocol.zeroPad(settlementCents, 12));
            }

        } catch (IOException e) {
            log("Error building host totals request: " + e.getMessage());
            return new byte[0];
        }

        return frameMessage(baos.toByteArray());
    }

    /**
     * Builds a Host Totals Request without terminal totals.
     *
     * @param terminalId       Terminal identifier
     * @param resetAfterQuery  true for code "51", false for "50"
     * @param statusMonitoring Terminal status monitoring data (may be null)
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildHostTotalsRequest(String terminalId, boolean resetAfterQuery,
                                         String statusMonitoring) {
        return buildHostTotalsRequest(terminalId, resetAfterQuery, statusMonitoring,
                -1, 0, 0, 0);
    }

    /**
     * Builds a Host Totals query-only request (code "50") with no terminal totals.
     *
     * @param terminalId Terminal identifier
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildHostTotalsRequest(String terminalId) {
        return buildHostTotalsRequest(terminalId, false, null);
    }

    // =========================================================================
    // Configuration Download Request
    // =========================================================================

    /**
     * Builds a Configuration Download Request (transaction code "60").
     *
     * <p>Sent at terminal startup or on operator request to download
     * configuration parameters (keys, surcharge amounts, messages, etc.)
     * from the host processor.
     *
     * @param terminalId Terminal identifier
     * @return Framed message bytes ready for transmission
     */
    public byte[] buildConfigDownloadRequest(String terminalId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Optional header group (uses default info header)
            if (includeOptionalHeader) {
                writeOptionalHeader(baos, null);
            }

            // Terminal ID (15 chars, space-padded right)
            baos.write(padRight(nullToEmpty(terminalId),
                    TritonProtocol.TERMINAL_ID_LENGTH).getBytes());

            // Transaction Code "60" (Config Download)
            addField(baos, TritonProtocol.TXN_CONFIG_DOWNLOAD);

            // Status Monitoring (empty for config request)
            addField(baos, "");

        } catch (IOException e) {
            log("Error building config download request: " + e.getMessage());
            return new byte[0];
        }

        return frameMessage(baos.toByteArray());
    }

    // =========================================================================
    // Single-Byte Control Messages
    // =========================================================================

    /**
     * Builds an ACK (Acknowledge) message.
     * Sent to confirm successful receipt of a host message.
     *
     * @return Single ACK byte array
     */
    public byte[] buildAck() {
        return new byte[] { TritonProtocol.ACK };
    }

    /**
     * Builds a NAK (Negative Acknowledge) message.
     * Sent when an LRC check fails or the message is malformed.
     *
     * @return Single NAK byte array
     */
    public byte[] buildNak() {
        return new byte[] { TritonProtocol.NAK };
    }

    /**
     * Builds an ENQ (Enquiry) message.
     * Sent to initiate communication with the host.
     *
     * @return Single ENQ byte array
     */
    public byte[] buildEnq() {
        return new byte[] { TritonProtocol.ENQ };
    }

    // =========================================================================
    // LRC Calculation
    // =========================================================================

    /**
     * Calculates the Longitudinal Redundancy Check (LRC) for a range of bytes.
     *
     * <p>The LRC is the XOR of all bytes from {@code start} to {@code end}
     * inclusive. Per the Triton protocol, the LRC covers all bytes from
     * the first byte after STX through ETX (inclusive), exclusive of STX
     * itself.
     *
     * @param data  The byte array
     * @param start Start index (inclusive)
     * @param end   End index (inclusive)
     * @return The calculated LRC byte
     */
    public static byte calculateLrc(byte[] data, int start, int end) {
        if (data == null || start < 0 || end < start || end >= data.length) {
            return 0;
        }

        byte lrc = 0;
        for (int i = start; i <= end; i++) {
            lrc ^= data[i];
        }
        return lrc;
    }

    // =========================================================================
    // Field Helpers
    // =========================================================================

    /**
     * Adds a field separator (FS) followed by the field value to the output stream.
     *
     * @param baos  The output stream to write to
     * @param value The field value to append
     * @throws IOException if writing fails
     */
    public static void addField(ByteArrayOutputStream baos, String value) throws IOException {
        baos.write(TritonProtocol.FS);
        if (value != null) {
            baos.write(value.getBytes());
        }
    }

    /**
     * Adds a miscellaneous field with a single-character FID.
     *
     * <p>Format: FS + FID character + data
     *
     * @param baos The output stream to write to
     * @param fid  The single-character Field ID
     * @param data The field data
     * @throws IOException if writing fails
     */
    public static void addMiscField(ByteArrayOutputStream baos, char fid, String data)
            throws IOException {
        baos.write(TritonProtocol.FS);
        baos.write((byte) fid);
        if (data != null) {
            baos.write(data.getBytes());
        }
    }

    /**
     * Adds a miscellaneous field with a multi-character FID (e.g., "ud", "ua").
     *
     * <p>Format: FS + FID string + data
     *
     * @param baos The output stream to write to
     * @param fid  The multi-character Field ID string
     * @param data The field data
     * @throws IOException if writing fails
     */
    public static void addMiscField(ByteArrayOutputStream baos, String fid, String data)
            throws IOException {
        baos.write(TritonProtocol.FS);
        if (fid != null) {
            baos.write(fid.getBytes());
        }
        if (data != null) {
            baos.write(data.getBytes());
        }
    }

    // =========================================================================
    // Message Framing
    // =========================================================================

    /**
     * Wraps a payload with STX, ETX, and LRC to produce a complete framed message.
     *
     * <p>Output format: STX + payload + ETX + LRC
     * <br>LRC is calculated over all bytes from the first byte after STX
     * through ETX inclusive (i.e., payload bytes + ETX).
     *
     * @param payload The unframed message content bytes
     * @return The fully framed message as a byte array
     */
    public static byte[] frameMessage(byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }

        // STX(1) + payload + ETX(1) + LRC(1)
        byte[] framed = new byte[payload.length + 3];

        // STX
        framed[0] = TritonProtocol.STX;

        // Payload
        System.arraycopy(payload, 0, framed, 1, payload.length);

        // ETX
        framed[framed.length - 2] = TritonProtocol.ETX;

        // LRC: XOR of bytes from index 1 (after STX) through the ETX byte (inclusive)
        framed[framed.length - 1] = calculateLrc(framed, 1, framed.length - 2);

        return framed;
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    /**
     * Writes the optional header group to the output stream.
     *
     * <p>The optional header contains:
     * <ol>
     *   <li>Communications ID (8 chars)</li>
     *   <li>FS + Terminal ID Type (2 chars)</li>
     *   <li>FS + Software Version (2 chars)</li>
     *   <li>FS + Encryption Mode (1 char)</li>
     *   <li>FS + Information Header (7 chars)</li>
     *   <li>FS (trailing, before Terminal ID)</li>
     * </ol>
     *
     * @param baos       The output stream to write to
     * @param infoHeader The information header value (null for default "H0.0000")
     * @throws IOException if writing fails
     */
    private void writeOptionalHeader(ByteArrayOutputStream baos, String infoHeader)
            throws IOException {
        // Communications ID (8 chars)
        String commId = communicationsId != null
                ? padRight(communicationsId, 8)
                : TritonProtocol.DEFAULT_COMM_ID;
        baos.write(commId.getBytes());

        // Terminal ID Type (2 chars)
        String tidType = terminalIdType != null ? terminalIdType : "  ";
        addField(baos, padRight(tidType, 2));

        // Software Version (2 chars)
        String swVer = softwareVersion != null ? softwareVersion : "  ";
        addField(baos, padRight(swVer, 2));

        // Encryption Mode (1 char)
        addField(baos, String.valueOf(encryptionMode));

        // Information Header (7 chars)
        String header = infoHeader != null ? infoHeader : "H0.0000";
        addField(baos, padRight(header, 7));

        // Trailing FS before Terminal ID
        baos.write(TritonProtocol.FS);
    }

    /**
     * Strips start sentinel (;), end sentinel (?), and trailing LRC
     * character from Track 2 data.
     *
     * <p>Track 2 format: {@code ;PAN=YYMM...?L}
     * <br>Output: {@code PAN=YYMM...}
     *
     * @param track2 The raw Track 2 data string
     * @return Track 2 data without sentinels and LRC
     */
    static String stripSentinels(String track2) {
        if (track2 == null || track2.isEmpty()) {
            return "";
        }

        String result = track2;

        // Remove start sentinel
        if (result.startsWith(";")) {
            result = result.substring(1);
        }

        // Remove end sentinel and any trailing LRC character
        int endIdx = result.indexOf('?');
        if (endIdx >= 0) {
            result = result.substring(0, endIdx);
        }

        return result;
    }

    /**
     * Maps the TransactionRequest's Hyosung-style transaction type to a
     * Triton-compatible transaction type string.
     *
     * <p>The TransactionRequest uses Hyosung operation codes (CW, BI, TR).
     * This method extracts the operation portion for Triton code lookup.
     *
     * @param request The TransactionRequest
     * @return A transaction type string suitable for
     *         {@link TritonProtocol#getTransactionCode(String, String)}
     */
    private String getTritonTransactionType(TransactionRequest request) {
        // Prefer new operationType field if available
        String opType = request.getOperationType();
        if (opType != null && !opType.isEmpty()) {
            return opType;
        }

        // Fallback: parse from Hyosung-style transactionType (CWCACA, BISASA, etc.)
        String txnType = request.getTransactionType();
        if (txnType == null || txnType.isEmpty()) {
            return "WITHDRAWAL";
        }

        String operation = txnType.length() >= 2 ? txnType.substring(0, 2) : txnType;

        switch (operation.toUpperCase()) {
            case "CW":
                return "CW";
            case "BI":
                return "BI";
            case "TR":
                return "TR";
            default:
                return "CW";
        }
    }

    /**
     * Extracts the account type from the TransactionRequest's Hyosung-style
     * transaction type.
     *
     * <p>Hyosung format: {@code CWCACA} (operation + source account + dest account)
     * <br>This extracts the source account portion (characters 2-3).
     *
     * @param request The TransactionRequest
     * @return Account type string (e.g., "CA", "SA", "CR") or null
     */
    private String getTritonAccountType(TransactionRequest request) {
        // Prefer new accountType field if available
        String acctType = request.getAccountType();
        if (acctType != null && !acctType.isEmpty()) {
            return acctType;
        }

        // Fallback: parse from Hyosung-style transactionType (CWCACA, BISASA, etc.)
        String txnType = request.getTransactionType();
        if (txnType != null && txnType.length() >= 4) {
            String acctCode = txnType.substring(2, 4);
            switch (acctCode.toUpperCase()) {
                case "CA":
                    return "CHECKING";
                case "SA":
                    return "SAVINGS";
                case "CR":
                    return "CREDIT";
                default:
                    return null;
            }
        }
        return null;
    }

    /**
     * Converts null to empty string.
     */
    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /**
     * Right-pads a string with spaces to the specified length.
     * If the string is already at or beyond the target length, it is truncated.
     *
     * @param value  The string to pad
     * @param length The desired length
     * @return The padded (or truncated) string
     */
    private static String padRight(String value, int length) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = value.length(); i < length; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // =========================================================================
    // Debug / Utility Methods
    // =========================================================================

    /**
     * Converts message bytes to a hex string for debugging.
     *
     * @param message The message bytes
     * @return Space-separated hex string representation
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
     * Converts message bytes to a human-readable string for debugging.
     * Control characters are shown as named tokens (e.g., {@code <STX>}).
     *
     * @param message The message bytes
     * @return Readable string with control character names
     */
    public static String toReadableString(byte[] message) {
        if (message == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : message) {
            switch (b) {
                case TritonProtocol.STX:
                    sb.append("<STX>");
                    break;
                case TritonProtocol.ETX:
                    sb.append("<ETX>");
                    break;
                case TritonProtocol.FS:
                    sb.append("<FS>");
                    break;
                case TritonProtocol.ACK:
                    sb.append("<ACK>");
                    break;
                case TritonProtocol.NAK:
                    sb.append("<NAK>");
                    break;
                case TritonProtocol.ENQ:
                    sb.append("<ENQ>");
                    break;
                case TritonProtocol.EOT:
                    sb.append("<EOT>");
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

    // =========================================================================
    // Getters
    // =========================================================================

    /** Returns the communications ID configured for this builder. */
    public String getCommunicationsId() {
        return communicationsId;
    }

    /** Returns the terminal ID type configured for this builder. */
    public String getTerminalIdType() {
        return terminalIdType;
    }

    /** Returns the software version configured for this builder. */
    public String getSoftwareVersion() {
        return softwareVersion;
    }

    /** Returns the encryption mode character. */
    public char getEncryptionMode() {
        return encryptionMode;
    }

    /** Returns whether the optional header group is included. */
    public boolean isIncludeOptionalHeader() {
        return includeOptionalHeader;
    }
}
