package castech.emvtxn.atm.host.triton;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import castech.emvtxn.atm.host.ConfigResponse;
import castech.emvtxn.atm.host.HealthCheckResponse;
import castech.emvtxn.atm.host.HostTotalsResponse;
import castech.emvtxn.atm.host.LrcCalculator;
import castech.emvtxn.atm.host.ReversalResponse;
import castech.emvtxn.atm.host.TransactionResponse;

/**
 * Triton Standard Message Parser (TSCD 5.22)
 *
 * Parses all incoming Triton Standard response messages from the host processor.
 * The Triton protocol uses STX/ETX framing with FS (0x1C) field separators
 * and LRC checksum validation.
 *
 * Supported message types:
 *   - Transaction Response (codes 00-49): Withdrawal, balance inquiry, transfer results
 *   - Config Download Response (code 60): PIN working keys, surcharge, settings
 *   - Host Totals Response (codes 50/51): Settlement and count data
 *   - Reversal Response (code 29): Reversal acknowledgement
 *   - Health Check Response: Keep-alive acknowledgement
 *
 * Miscellaneous fields use single-character Field ID (FID) codes to identify
 * variable data. See {@link TritonProtocol} for FID constant definitions.
 */
public class TritonMessageParser {

    private static final String TAG = "TritonMessageParser";

    /**
     * Creates a new TritonMessageParser.
     */
    public TritonMessageParser() {
    }

    // =========================================================================
    // Transaction Response Parsing
    // =========================================================================

    /**
     * Parses a Transaction Response message from the host.
     *
     * Expected field layout (after stripping STX/ETX/LRC):
     *   [InfoHeader(12)] FS [MultiBlock(1)] FS [TerminalID(15)] FS
     *   [TxnCode(2)] FS [SeqNum(4)] FS [ResponseCode(3)] FS
     *   [AuthNumber(8)] FS [TxnDate(6)] FS [TxnTime(6)] FS
     *   [BusinessDate(6)] FS [Amount1(8)] FS [Amount2(8)] FS
     *   [Misc1] FS [Misc2] FS ... [MiscN] FS
     *
     * The info header and its trailing FS are optional; they are only
     * present when the original request included them.
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed TransactionResponse
     * @throws ParseException if the message is malformed or LRC fails
     */
    public TransactionResponse parseTransactionResponse(byte[] message) throws ParseException {
        if (!verifyLrc(message)) {
            throw new ParseException("LRC verification failed for transaction response");
        }

        byte[] payload = stripFraming(message);
        String[] fields = splitFields(payload);

        // Determine if info header is present by examining field count and content.
        // If the header is present, the first field is 12 chars (space-filled)
        // and we have an extra field offset.
        int offset = detectInfoHeaderOffset(fields);

        // Minimum fields: [TerminalID][TxnCode][SeqNum][ResponseCode] = 4 fields after offset
        int minFields = offset + 4;
        if (fields.length < minFields) {
            throw new ParseException("Insufficient fields for transaction response: "
                    + fields.length + " (expected at least " + minFields + ")");
        }

        TransactionResponse response = new TransactionResponse();

        // Info header (optional)
        if (offset > 0) {
            response.setInfoHeader(fields[0]);
        }

        // When info header is present, field[1] is multi-block indicator (skip it)
        // and the remaining fields shift by the offset.
        // When no info header, field[0] is multi-block indicator (skip it) or
        // directly the terminal ID depending on whether multi-block is present.
        //
        // Per the spec: InfoHeader(opt) FS MultiBlock FS TerminalID FS ...
        // With header: fields[0]=header, fields[1]=multiBlock, fields[2]=terminalId, ...
        // Without header: fields[0]=multiBlock, fields[1]=terminalId, ...
        //
        // We use the offset to track the info header, and always skip multi-block.
        int idx = offset; // Points to multi-block indicator field
        idx++; // Skip multi-block indicator, now points to terminal ID

        response.setTerminalId(getFieldTrimmed(fields, idx++));

        // Transaction code (2 chars)
        String txnCode = getField(fields, idx++);

        // Sequence number (4 numeric)
        response.setSequenceNumber(parseIntSafe(getField(fields, idx++)));

        // Response code (3 numeric)
        response.setResponseCode(getField(fields, idx++));

        // Authorization number (8 numeric, right-justified zero-padded)
        if (idx < fields.length) {
            String authNum = getField(fields, idx++);
            // Store as authorization data for compatibility with TransactionResponse
            response.setAuthorizationData(authNum);
        }

        // Transaction date (6 numeric MMDDYY)
        String txnDate = "";
        if (idx < fields.length) {
            txnDate = getField(fields, idx++);
        }

        // Transaction time (6 numeric HHMMSS)
        String txnTime = "";
        if (idx < fields.length) {
            txnTime = getField(fields, idx++);
        }

        // Build authorization data in the format TransactionResponse expects:
        // MMDDYYYYHHmmss + auth number (padded)
        // Triton date is MMDDYY; TransactionResponse expects MMDDYYYY
        if (txnDate.length() == 6 && txnTime.length() == 6) {
            String year4 = "20" + txnDate.substring(4, 6);
            String fullAuthData = txnDate.substring(0, 4) + year4 + txnTime;
            if (response.getAuthorizationData() != null
                    && !response.getAuthorizationData().isEmpty()) {
                fullAuthData += response.getAuthorizationData();
            }
            response.setAuthorizationData(fullAuthData);
        }

        // Business date (6 numeric MMDDYY)
        String businessDate = "";
        if (idx < fields.length) {
            businessDate = getField(fields, idx++);
        }

        // Amount 1 (8 numeric: balance/dispense amount in cents)
        if (idx < fields.length) {
            response.setAccountBalanceCents(parseLongSafe(getField(fields, idx++)));
        }

        // Amount 2 (8 numeric: actual surcharge in cents)
        if (idx < fields.length) {
            response.setSurchargeCents(parseLongSafe(getField(fields, idx++)));
        }

        // Build settlement data from business date for compatibility
        // TransactionResponse settlement format: AAAAAASSMMDDYYYY
        if (businessDate.length() == 6) {
            String settlementYear = "20" + businessDate.substring(4, 6);
            String settlementData = "000000" + "00"
                    + businessDate.substring(0, 4) + settlementYear;
            response.setSettlementData(settlementData);
        }

        // Parse miscellaneous fields (FID code + data)
        if (idx < fields.length) {
            Map<String, String> miscFields = parseMiscFields(fields, idx);

            // FID 'p' - receipt text
            if (miscFields.containsKey(String.valueOf(TritonProtocol.FID_RECEIPT_TEXT))) {
                response.setDisplayMessage(
                        miscFields.get(String.valueOf(TritonProtocol.FID_RECEIPT_TEXT)));
            }

            // FID 'b' - available balance
            if (miscFields.containsKey(String.valueOf(TritonProtocol.FID_AVAILABLE_BALANCE))) {
                response.setAvailableBalanceCents(parseLongSafe(
                        miscFields.get(String.valueOf(TritonProtocol.FID_AVAILABLE_BALANCE))));
            }

            // FID 'k' - issuer fee
            if (miscFields.containsKey(String.valueOf(TritonProtocol.FID_ISSUER_FEE))) {
                // Issuer fee is informational; store in display message if no receipt text
                String issuerFee = miscFields.get(
                        String.valueOf(TritonProtocol.FID_ISSUER_FEE));
                if (response.getDisplayMessage() == null
                        || response.getDisplayMessage().isEmpty()) {
                    response.setDisplayMessage("Issuer Fee: " + issuerFee);
                }
            }

            // FID 'ud' - EMV tagged data
            if (miscFields.containsKey(TritonProtocol.FID_EMV_TAGGED_DATA)) {
                response.setEmvResponseData(
                        miscFields.get(TritonProtocol.FID_EMV_TAGGED_DATA));
            }

            // FID 'uh' - EMV untagged data
            if (miscFields.containsKey(TritonProtocol.FID_EMV_UNTAGGED_DATA)) {
                if (response.getEmvResponseData() == null
                        || response.getEmvResponseData().isEmpty()) {
                    response.setEmvResponseData(
                            miscFields.get(TritonProtocol.FID_EMV_UNTAGGED_DATA));
                }
            }
        }

        return response;
    }

    // =========================================================================
    // Config Download Response Parsing
    // =========================================================================

    /**
     * Parses a Configuration Download Response message from the host.
     *
     * Expected field layout:
     *   [InfoHeader(12, opt)] FS [TerminalID(15)] FS [TxnCode("60")] FS
     *   [FID '~' + PINKey1(16hex)] FS [FID '!' + Surcharge(8)] FS
     *   [Misc fields...] FS
     *
     * PIN Working Key 1 is identified by FID '~' (tilde).
     * Surcharge amount is identified by FID '!' (exclamation).
     * Additional keys and config values appear as FID-coded miscellaneous fields.
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed ConfigResponse
     * @throws ParseException if the message is malformed or LRC fails
     */
    public ConfigResponse parseConfigResponse(byte[] message) throws ParseException {
        if (!verifyLrc(message)) {
            throw new ParseException("LRC verification failed for config response");
        }

        byte[] payload = stripFraming(message);
        String[] fields = splitFields(payload);

        int offset = detectInfoHeaderOffset(fields);

        // Minimum fields: [TerminalID][TxnCode] = 2 after offset
        int minFields = offset + 2;
        if (fields.length < minFields) {
            throw new ParseException("Insufficient fields for config response: "
                    + fields.length + " (expected at least " + minFields + ")");
        }

        ConfigResponse response = new ConfigResponse();

        // Info header (optional)
        if (offset > 0) {
            response.setInfoHeader(fields[0]);
        }

        int idx = offset;
        response.setTerminalId(getFieldTrimmed(fields, idx++));

        // Transaction code (should be "60")
        String txnCode = getField(fields, idx++);

        // Remaining fields are FID-coded
        // Parse all remaining fields as FID data
        Map<String, String> fidData = parseMiscFields(fields, idx);

        // FID '~' - PIN Working Key 1 (16 hex chars)
        String pinKey1Fid = String.valueOf(TritonProtocol.FID_PIN_KEY_1);
        if (fidData.containsKey(pinKey1Fid)) {
            response.setWorkingKeyPart1(fidData.get(pinKey1Fid));
        }

        // FID '{' - PIN Working Key 2 (Triple-DES K2, 16 hex chars)
        String pinKey2Fid = String.valueOf(TritonProtocol.FID_PIN_KEY_2);
        if (fidData.containsKey(pinKey2Fid)) {
            response.setWorkingKeyPart2(fidData.get(pinKey2Fid));
        }

        // FID '!' - Surcharge amount (8 numeric, cents)
        String surchargeFid = String.valueOf(TritonProtocol.FID_SURCHARGE_AMOUNT);
        if (fidData.containsKey(surchargeFid)) {
            response.setSurchargeCents(parseLongSafe(fidData.get(surchargeFid)));
        }

        // FID 'w' - Max withdrawal amount (8 numeric, cents)
        // Stored for reference; ConfigResponse doesn't have a dedicated field
        // so we note it via the info header or caller retrieves from getMiscFields()

        // FID 'h' - Heartbeat interval (seconds)
        // FID 't' - Time synchronization
        // FID 'c' - Day close time
        // FID 'd' - Programmable messages
        // These are returned via the miscellaneous fields map for caller retrieval.

        // Store all FID data so callers can access processor-specific config
        // via the extended getter below.
        this.lastConfigFidData = fidData;

        return response;
    }

    /**
     * Returns the FID data map from the most recently parsed config response.
     * Callers can use this to retrieve processor-specific configuration values
     * such as max withdrawal (FID 'w'), heartbeat interval (FID 'h'),
     * time sync (FID 't'), day close (FID 'c'), and programmable messages (FID 'd').
     *
     * @return Map of FID code to value, or null if no config has been parsed
     */
    public Map<String, String> getLastConfigFidData() {
        return lastConfigFidData;
    }

    /**
     * Gets a specific FID value from the last parsed config response.
     *
     * @param fidCode The FID code character (e.g., 'w' for max withdrawal)
     * @return The FID value, or null if not present
     */
    public String getConfigFidValue(char fidCode) {
        if (lastConfigFidData == null) {
            return null;
        }
        return lastConfigFidData.get(String.valueOf(fidCode));
    }

    /**
     * Gets the max withdrawal amount from the last parsed config response.
     *
     * @return Max withdrawal in cents, or 0 if not present
     */
    public long getConfigMaxWithdrawal() {
        String value = getConfigFidValue(TritonProtocol.FID_MAX_WITHDRAWAL);
        return value != null ? parseLongSafe(value) : 0;
    }

    /**
     * Gets the heartbeat interval from the last parsed config response.
     *
     * @return Heartbeat interval in seconds, or 0 if not present
     */
    public int getConfigHeartbeatInterval() {
        String value = getConfigFidValue(TritonProtocol.FID_HEARTBEAT_INTERVAL);
        return value != null ? parseIntSafe(value) : 0;
    }

    /**
     * Gets the time sync value from the last parsed config response.
     *
     * @return Time sync string, or null if not present
     */
    public String getConfigTimeSync() {
        return getConfigFidValue(TritonProtocol.FID_TIME_SYNC);
    }

    /**
     * Gets the day close time from the last parsed config response.
     *
     * @return Day close time string, or null if not present
     */
    public String getConfigDayCloseTime() {
        return getConfigFidValue(TritonProtocol.FID_DAY_CLOSE_TIME);
    }

    /**
     * Gets the programmable messages from the last parsed config response.
     *
     * @return Programmable message data, or null if not present
     */
    public String getConfigProgrammableMessages() {
        return getConfigFidValue(TritonProtocol.FID_PROGRAMMABLE_MSG);
    }

    // =========================================================================
    // Host Totals Response Parsing
    // =========================================================================

    /**
     * Parses a Host Totals Response message from the host.
     *
     * Expected field layout:
     *   [InfoHeader(12, opt)] FS [TerminalID(15)] FS [TxnCode("50"/"51")] FS
     *   [BusinessDate(6)] FS [NoWithdrawals(4) + NoInquiries(4) + NoTransfers(4)
     *    + Settlement(8)] FS [Misc fields...] FS
     *
     * The counts and settlement are concatenated in a single field:
     *   4 digits withdrawals + 4 digits inquiries + 4 digits transfers + 8 digits settlement
     *   = 20 characters total
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed HostTotalsResponse
     * @throws ParseException if the message is malformed or LRC fails
     */
    public HostTotalsResponse parseHostTotalsResponse(byte[] message) throws ParseException {
        if (!verifyLrc(message)) {
            throw new ParseException("LRC verification failed for host totals response");
        }

        byte[] payload = stripFraming(message);
        String[] fields = splitFields(payload);

        int offset = detectInfoHeaderOffset(fields);

        // Minimum fields: [TerminalID][TxnCode][BusinessDate][CountsAndSettlement]
        int minFields = offset + 4;
        if (fields.length < minFields) {
            throw new ParseException("Insufficient fields for host totals response: "
                    + fields.length + " (expected at least " + minFields + ")");
        }

        HostTotalsResponse response = new HostTotalsResponse();

        int idx = offset;
        response.setTerminalId(getFieldTrimmed(fields, idx++));

        // Transaction code (50 or 51)
        String txnCode = getField(fields, idx++);

        // Business date (6 numeric MMDDYY)
        String businessDate = getField(fields, idx++);

        // Counts and settlement field
        // Format: WWWWIIIITTTTSSSSSSSS (4+4+4+8 = 20 chars)
        String countsField = getField(fields, idx++);

        if (countsField.length() >= 20) {
            int withdrawalCount = parseIntSafe(countsField.substring(0, 4));
            int inquiryCount = parseIntSafe(countsField.substring(4, 8));
            int transferCount = parseIntSafe(countsField.substring(8, 12));
            long settlementCents = parseLongSafe(countsField.substring(12, 20));

            // Build a 16-char transaction counts string for HostTotalsResponse:
            // Format expected by setTransactionCounts: CCCCTTTTBBBBNNNN
            // C=withdrawals, T=transfers, B=balance inquiries, N=non-cash
            String countString = TritonProtocol.zeroPad(withdrawalCount, 4)
                    + TritonProtocol.zeroPad(transferCount, 4)
                    + TritonProtocol.zeroPad(inquiryCount, 4)
                    + TritonProtocol.zeroPad(0, 4);  // Non-cash (not in Triton spec)
            response.setTransactionCounts(countString);

            response.setTotalCashDispensed(settlementCents);
        } else if (countsField.length() >= 12) {
            // Partial data: just counts without settlement
            int withdrawalCount = parseIntSafe(countsField.substring(0, 4));
            int inquiryCount = parseIntSafe(countsField.substring(4, 8));
            int transferCount = parseIntSafe(countsField.substring(8, 12));

            String countString = TritonProtocol.zeroPad(withdrawalCount, 4)
                    + TritonProtocol.zeroPad(transferCount, 4)
                    + TritonProtocol.zeroPad(inquiryCount, 4)
                    + TritonProtocol.zeroPad(0, 4);
            response.setTransactionCounts(countString);
        }

        // Parse miscellaneous fields for surcharge totals, etc.
        if (idx < fields.length) {
            Map<String, String> miscFields = parseMiscFields(fields, idx);

            // FID '!' - Surcharge total
            String surchargeFid = String.valueOf(TritonProtocol.FID_SURCHARGE_AMOUNT);
            if (miscFields.containsKey(surchargeFid)) {
                response.setTotalSurcharges(parseLongSafe(miscFields.get(surchargeFid)));
            }
        }

        response.setSuccess(true);
        return response;
    }

    // =========================================================================
    // Reversal Response Parsing
    // =========================================================================

    /**
     * Parses a Reversal Response message from the host.
     *
     * The reversal response has the same structure as a transaction response
     * but with transaction code "29".
     *
     * Expected field layout:
     *   [InfoHeader(12, opt)] FS [MultiBlock(1)] FS [TerminalID(15)] FS
     *   [TxnCode("29")] FS [SeqNum(4)] FS [ResponseCode(3)] FS
     *   [AuthNumber(8)] FS [TxnDate(6)] FS [TxnTime(6)] FS
     *   [BusinessDate(6)] FS [Amount1(8)] FS [Amount2(8)] FS
     *   [Misc fields...] FS
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed ReversalResponse
     * @throws ParseException if the message is malformed or LRC fails
     */
    public ReversalResponse parseReversalResponse(byte[] message) throws ParseException {
        if (!verifyLrc(message)) {
            throw new ParseException("LRC verification failed for reversal response");
        }

        byte[] payload = stripFraming(message);
        String[] fields = splitFields(payload);

        int offset = detectInfoHeaderOffset(fields);

        // Minimum: [MultiBlock][TerminalID][TxnCode][SeqNum][ResponseCode]
        int minFields = offset + 5;
        if (fields.length < minFields) {
            throw new ParseException("Insufficient fields for reversal response: "
                    + fields.length + " (expected at least " + minFields + ")");
        }

        ReversalResponse response = new ReversalResponse();

        // Info header (optional)
        if (offset > 0) {
            response.setInfoHeader(fields[0]);
        }

        int idx = offset;
        idx++; // Skip multi-block indicator

        response.setTerminalId(getFieldTrimmed(fields, idx++));

        // Transaction code (should be "29")
        String txnCode = getField(fields, idx++);

        // Sequence number (4 numeric)
        response.setSequenceNumber(parseIntSafe(getField(fields, idx++)));

        // Response code (3 numeric)
        response.setResponseCode(getField(fields, idx++));

        return response;
    }

    // =========================================================================
    // Health Check Response Parsing
    // =========================================================================

    /**
     * Parses a Health Check Response message from the host.
     *
     * The health check response is a minimal acknowledgement. In Triton protocol,
     * the host may simply respond with an ACK byte, or a minimal message
     * containing terminal ID and transaction code echoed back.
     *
     * Expected field layout (if full message):
     *   [InfoHeader(12, opt)] FS [TerminalID(15)] FS [TxnCode] FS [Status]
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed HealthCheckResponse
     * @throws ParseException if the message is malformed or LRC fails
     */
    public HealthCheckResponse parseHealthCheckResponse(byte[] message) throws ParseException {
        // A single ACK byte is a valid health check response
        if (isAck(message)) {
            HealthCheckResponse response = new HealthCheckResponse();
            response.setStatus("00");
            return response;
        }

        if (!verifyLrc(message)) {
            throw new ParseException("LRC verification failed for health check response");
        }

        byte[] payload = stripFraming(message);
        String[] fields = splitFields(payload);

        int offset = detectInfoHeaderOffset(fields);

        // Minimum: [TerminalID][TxnCode]
        int minFields = offset + 2;
        if (fields.length < minFields) {
            throw new ParseException("Insufficient fields for health check response: "
                    + fields.length + " (expected at least " + minFields + ")");
        }

        HealthCheckResponse response = new HealthCheckResponse();

        // Info header (optional)
        if (offset > 0) {
            response.setInfoHeader(fields[0]);
        }

        int idx = offset;
        response.setTerminalId(getFieldTrimmed(fields, idx++));

        // Transaction code
        String txnCode = getField(fields, idx++);

        // Status (if present)
        if (idx < fields.length) {
            response.setStatus(getField(fields, idx));
        } else {
            // No status field means implicit OK
            response.setStatus("00");
        }

        return response;
    }

    // =========================================================================
    // Auto-Detect and Parse
    // =========================================================================

    /**
     * Auto-detects the message type and parses accordingly.
     *
     * Determines the transaction code from the parsed fields and dispatches
     * to the appropriate parser method.
     *
     * @param message The complete framed message bytes (STX...ETX LRC)
     * @return Parsed response object (type depends on message)
     * @throws ParseException if the message cannot be parsed
     */
    public Object parseResponse(byte[] message) throws ParseException {
        // Check for single-byte control messages
        if (isAck(message)) {
            HealthCheckResponse response = new HealthCheckResponse();
            response.setStatus("00");
            return response;
        }
        if (isNak(message)) {
            throw new ParseException("Received NAK from host");
        }
        if (isEot(message)) {
            throw new ParseException("Received EOT from host (end of transmission)");
        }

        String txnCode = detectTransactionCode(message);
        if (txnCode == null) {
            throw new ParseException("Unable to determine transaction code from message");
        }

        switch (txnCode) {
            case TritonProtocol.TXN_CONFIG_DOWNLOAD:
                return parseConfigResponse(message);

            case TritonProtocol.TXN_HOST_TOTALS:
            case TritonProtocol.TXN_HOST_TOTALS_RESET:
                return parseHostTotalsResponse(message);

            case TritonProtocol.TXN_REVERSAL:
                return parseReversalResponse(message);

            default:
                // All other codes (00-49 except 29) are transaction responses
                return parseTransactionResponse(message);
        }
    }

    /**
     * Detects the transaction code from a framed message without full parsing.
     *
     * @param message The complete framed message bytes
     * @return The transaction code string, or null if not determinable
     */
    public String detectTransactionCode(byte[] message) {
        try {
            byte[] payload = stripFraming(message);
            String[] fields = splitFields(payload);
            int offset = detectInfoHeaderOffset(fields);

            // For transaction/reversal responses: InfoHeader(opt) + MultiBlock + TerminalID + TxnCode
            // For config/totals responses: InfoHeader(opt) + TerminalID + TxnCode
            // We need to check at least 2 positions after offset

            if (fields.length > offset + 2) {
                // Check if field at offset+2 looks like a 2-digit transaction code
                String candidate = fields[offset + 2];
                if (candidate.length() == 2 && isNumeric(candidate)) {
                    return candidate;
                }
            }

            // Try offset+1 (for config/totals format without multi-block)
            if (fields.length > offset + 1) {
                String candidate = fields[offset + 1];
                if (candidate.length() == 2 && isNumeric(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            // Can't determine type
        }
        return null;
    }

    // =========================================================================
    // Control Character Detection
    // =========================================================================

    /**
     * Checks if the message is a single ACK byte (0x06).
     *
     * @param message The received bytes
     * @return true if the message is a single ACK
     */
    public boolean isAck(byte[] message) {
        return message != null && message.length == 1 && message[0] == TritonProtocol.ACK;
    }

    /**
     * Checks if the message is a single NAK byte (0x15).
     *
     * @param message The received bytes
     * @return true if the message is a single NAK
     */
    public boolean isNak(byte[] message) {
        return message != null && message.length == 1 && message[0] == TritonProtocol.NAK;
    }

    /**
     * Checks if the message is a single EOT byte (0x04).
     *
     * @param message The received bytes
     * @return true if the message is a single EOT
     */
    public boolean isEot(byte[] message) {
        return message != null && message.length == 1 && message[0] == TritonProtocol.EOT;
    }

    /**
     * Checks if the message is a single-byte control character (ACK, NAK, EOT, ENQ).
     *
     * @param message The received bytes
     * @return true if the message is a single control character
     */
    public boolean isControlMessage(byte[] message) {
        if (message == null || message.length != 1) {
            return false;
        }
        byte b = message[0];
        return b == TritonProtocol.ACK || b == TritonProtocol.NAK
                || b == TritonProtocol.EOT || b == TritonProtocol.ENQ;
    }

    // =========================================================================
    // Framing and Checksum Helpers
    // =========================================================================

    /**
     * Strips STX/ETX/LRC framing from a message, returning the payload.
     *
     * Input format: [STX][payload][ETX][LRC]
     * Output: [payload] (content between STX and ETX)
     *
     * @param message The complete framed message bytes
     * @return The payload bytes without framing
     * @throws ParseException if the message structure is invalid
     */
    public byte[] stripFraming(byte[] message) throws ParseException {
        if (message == null || message.length < 4) {
            throw new ParseException("Message too short to strip framing: "
                    + (message == null ? 0 : message.length) + " bytes (minimum 4)");
        }

        // Verify STX at start
        if (message[0] != TritonProtocol.STX) {
            throw new ParseException(String.format(
                    "Expected STX (0x02) at start, found 0x%02X", message[0]));
        }

        // Verify ETX at penultimate position
        if (message[message.length - 2] != TritonProtocol.ETX) {
            throw new ParseException(String.format(
                    "Expected ETX (0x03) before LRC, found 0x%02X at position %d",
                    message[message.length - 2], message.length - 2));
        }

        // Extract content between STX and ETX
        int contentLength = message.length - 3; // Total - STX - ETX - LRC
        byte[] content = new byte[contentLength];
        System.arraycopy(message, 1, content, 0, contentLength);

        return content;
    }

    /**
     * Verifies the LRC checksum of a framed message.
     *
     * The LRC is calculated by XORing all bytes from after STX up to
     * and including ETX. The result must match the final byte (LRC).
     *
     * @param message The complete framed message bytes [STX][data][ETX][LRC]
     * @return true if the LRC is valid
     */
    public boolean verifyLrc(byte[] message) {
        if (message == null || message.length < 4) {
            return false;
        }

        // Must start with STX
        if (message[0] != TritonProtocol.STX) {
            return false;
        }

        // ETX should be at penultimate position
        if (message[message.length - 2] != TritonProtocol.ETX) {
            return false;
        }

        // LRC is last byte
        byte receivedLrc = message[message.length - 1];

        // Calculate LRC over bytes from after STX to ETX inclusive
        // That is positions 1 through (length - 2)
        byte calculatedLrc = LrcCalculator.calculate(message, 1, message.length - 2);

        return calculatedLrc == receivedLrc;
    }

    /**
     * Splits a payload byte array by FS (0x1C) separator into String fields.
     *
     * Each segment between FS bytes becomes a separate String element.
     * Leading and trailing FS bytes produce empty string entries.
     *
     * @param payload The payload bytes (without STX/ETX/LRC framing)
     * @return Array of field values as strings
     */
    public String[] splitFields(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new String[0];
        }

        List<String> fields = new ArrayList<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        for (byte b : payload) {
            if (b == TritonProtocol.FS) {
                fields.add(baos.toString());
                baos.reset();
            } else {
                baos.write(b);
            }
        }

        // Add the last field (after the last FS, or the entire content if no FS)
        fields.add(baos.toString());

        return fields.toArray(new String[0]);
    }

    // =========================================================================
    // Miscellaneous Field (FID) Parsing
    // =========================================================================

    /**
     * Parses miscellaneous fields starting from the given index.
     *
     * Each miscellaneous field starts with a Field ID (FID) code character
     * followed by the data value. Some FIDs are two characters long
     * (extended FIDs starting with 'u').
     *
     * @param fields The full array of fields from the message
     * @param startIndex The index of the first miscellaneous field
     * @return Map of FID code (as string) to data value
     */
    public Map<String, String> parseMiscFields(String[] fields, int startIndex) {
        Map<String, String> result = new HashMap<>();

        if (fields == null) {
            return result;
        }

        for (int i = startIndex; i < fields.length; i++) {
            String field = fields[i];
            if (field == null || field.isEmpty()) {
                continue;
            }

            String[] fidParts = parseFidValue(field);
            if (fidParts != null) {
                result.put(fidParts[0], fidParts[1]);
            }
        }

        return result;
    }

    /**
     * Parses a single miscellaneous field into its FID code and data value.
     *
     * Standard FID: single character code followed by data.
     *   Example: "p" + "RECEIPT TEXT" -> FID="p", value="RECEIPT TEXT"
     *
     * Extended FID: 'u' followed by a second character, then data.
     *   Example: "ud" + "9F2608..." -> FID="ud", value="9F2608..."
     *
     * @param miscField The raw miscellaneous field string (FID + data)
     * @return String array [fidCode, value], or null if the field is empty
     */
    public String[] parseFidValue(String miscField) {
        if (miscField == null || miscField.isEmpty()) {
            return null;
        }

        char firstChar = miscField.charAt(0);

        // Extended FID: starts with 'u' and has at least 2 characters for the FID code
        if (firstChar == 'u' && miscField.length() >= 2) {
            String fidCode = miscField.substring(0, 2);
            String value = miscField.length() > 2 ? miscField.substring(2) : "";
            return new String[]{fidCode, value};
        }

        // Standard FID: single character code
        String fidCode = String.valueOf(firstChar);
        String value = miscField.length() > 1 ? miscField.substring(1) : "";
        return new String[]{fidCode, value};
    }

    // =========================================================================
    // Info Header Detection
    // =========================================================================

    /**
     * Detects whether the first field is an Information Header.
     *
     * The info header is 12 characters, space-filled, and is only present
     * if the original request included one. When present, it adds an offset
     * of 1 to all subsequent field indices.
     *
     * Heuristic: If the first field is exactly 12 characters and contains
     * mostly non-numeric characters (spaces, dots, letters), treat it as
     * a header. Otherwise, the first field is the multi-block indicator
     * (1 digit) or terminal ID (15 chars).
     *
     * @param fields The split field array
     * @return 1 if info header is present (fields[0] is the header), 0 otherwise
     */
    private int detectInfoHeaderOffset(String[] fields) {
        if (fields == null || fields.length == 0) {
            return 0;
        }

        String first = fields[0];

        // Info header is exactly 12 characters
        if (first.length() == 12) {
            // Check if it looks like a header (contains dots or starts with letter/space)
            // Headers typically look like "H0.NNNNNN  " or "XXXXXX^^    "
            if (first.contains(".") || first.startsWith("H") || first.startsWith(" ")) {
                return 1;
            }
            // Also treat as header if it's all spaces
            if (first.trim().isEmpty()) {
                return 1;
            }
        }

        // Not a header
        return 0;
    }

    // =========================================================================
    // Private Utility Methods
    // =========================================================================

    /**
     * Safely retrieves a field from the array by index.
     *
     * @param fields The field array
     * @param index The index to retrieve
     * @return The field value, or empty string if index is out of bounds
     */
    private String getField(String[] fields, int index) {
        if (fields != null && index >= 0 && index < fields.length) {
            return fields[index];
        }
        return "";
    }

    /**
     * Safely retrieves a field from the array by index, with whitespace trimmed.
     *
     * @param fields The field array
     * @param index The index to retrieve
     * @return The trimmed field value, or empty string if index is out of bounds
     */
    private String getFieldTrimmed(String[] fields, int index) {
        String value = getField(fields, index);
        return value.trim();
    }

    /**
     * Safely parses a string to int, returning 0 on failure.
     *
     * @param value The string to parse
     * @return The parsed integer, or 0 if parsing fails
     */
    private int parseIntSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Safely parses a string to long, returning 0 on failure.
     *
     * @param value The string to parse
     * @return The parsed long, or 0 if parsing fails
     */
    private long parseLongSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Checks if a string contains only numeric digits.
     *
     * @param str The string to check
     * @return true if all characters are digits [0-9]
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Instance State
    // =========================================================================

    /**
     * Stores FID data from the most recently parsed config response.
     * Allows callers to access processor-specific configuration values
     * that don't map directly to ConfigResponse fields.
     */
    private Map<String, String> lastConfigFidData;

    // =========================================================================
    // Exception Class
    // =========================================================================

    /**
     * Exception thrown when Triton message parsing fails.
     *
     * Common causes:
     *   - LRC checksum mismatch
     *   - Insufficient fields for the expected message type
     *   - Missing or invalid STX/ETX framing
     *   - Unexpected transaction code
     */
    public static class ParseException extends Exception {

        /**
         * Creates a ParseException with a message.
         *
         * @param message The error description
         */
        public ParseException(String message) {
            super(message);
        }

        /**
         * Creates a ParseException with a message and cause.
         *
         * @param message The error description
         * @param cause The underlying cause
         */
        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
