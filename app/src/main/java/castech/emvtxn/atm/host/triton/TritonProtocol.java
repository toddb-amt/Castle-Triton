package castech.emvtxn.atm.host.triton;

/**
 * Triton Standard Protocol Constants (TSCD 5.22)
 *
 * Defines control characters, transaction codes, FID codes,
 * authorization codes, and message structure constants.
 */
public final class TritonProtocol {

    private TritonProtocol() {} // Constants only

    // =========================================================================
    // Control Characters
    // =========================================================================

    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte EOT = 0x04;
    public static final byte ENQ = 0x05;
    public static final byte ACK = 0x06;
    public static final byte NAK = 0x15;
    public static final byte FS  = 0x1C;  // Field Separator

    // =========================================================================
    // Transaction Codes (Terminal → Host)
    // =========================================================================

    // Withdrawal (Triton STD 3 codes — MUX isTritonCashWithdrawal: 11,12,15,61,62,65)
    public static final String TXN_WITHDRAWAL_CHECKING   = "11";
    public static final String TXN_PIN_CHANGE            = "01";
    public static final String TXN_WITHDRAWAL_SAVINGS    = "12";
    public static final String TXN_WITHDRAWAL_CREDIT     = "15";

    // Balance Inquiry (Triton STD 3 codes — MUX isTritonBalanceInquiry: 31,32,35,81,82,85)
    public static final String TXN_BALANCE_CHECKING      = "31";
    public static final String TXN_BALANCE_SAVINGS       = "32";
    public static final String TXN_BALANCE_CREDIT        = "35";

    // Transfer (Triton STD 3 codes — MUX isTritonTransfer: 21,22,25,71,72,75)
    public static final String TXN_TRANSFER_CHK_TO_SAV   = "21";
    public static final String TXN_TRANSFER_SAV_TO_CHK   = "22";

    // Reversal
    public static final String TXN_REVERSAL              = "29";

    // Host Totals
    public static final String TXN_HOST_TOTALS           = "50";
    public static final String TXN_HOST_TOTALS_RESET     = "51";

    // Configuration Download
    public static final String TXN_CONFIG_DOWNLOAD       = "60";

    // Extended
    public static final String TXN_QUASICASH_CREDIT_ADV  = "43";
    public static final String TXN_PREPAID               = "44";
    public static final String TXN_FUNDS_SEND_CHECKING   = "46";
    public static final String TXN_FUNDS_SEND_SAVINGS    = "47";
    public static final String TXN_FUNDS_RECEIVE         = "48";
    public static final String TXN_CHECK_CASHING         = "49";
    public static final String TXN_EXTENDED              = "99";

    // =========================================================================
    // Authorization / Response Codes (Host → Terminal)
    // =========================================================================

    public static final String AUTH_APPROVED             = "000";
    public static final String AUTH_DECLINED             = "100";
    public static final String AUTH_INVALID_CARD         = "101";
    public static final String AUTH_PICKUP_CARD          = "104";
    public static final String AUTH_CALL_ISSUER          = "107";
    public static final String AUTH_INSUFFICIENT_FUNDS   = "116";
    public static final String AUTH_EXCEEDED_LIMIT       = "121";
    public static final String AUTH_INVALID_PIN          = "117";
    public static final String AUTH_PIN_TRIES_EXCEEDED   = "118";
    public static final String AUTH_WRONG_ACCOUNT        = "125";
    public static final String AUTH_SYSTEM_ERROR         = "200";
    public static final String AUTH_DUPLICATE_TXN        = "201";
    public static final String AUTH_UNABLE_TO_PROCESS    = "207";
    public static final String AUTH_INVALID_AMOUNT       = "208";
    public static final String AUTH_PIN_CHANGE_FAILED    = "222";
    public static final String AUTH_CRC_ERROR            = "027";
    public static final String AUTH_SECOND_INVALID_PIN   = "035";

    // =========================================================================
    // Miscellaneous Field ID Codes (FIDs)
    // =========================================================================

    // Standard FIDs (single character)
    public static final char FID_AVAILABLE_BALANCE    = 'b';  // Available balance
    public static final char FID_DAY_CLOSE_TIME       = 'c';  // Automatic day close time
    public static final char FID_PROGRAMMABLE_MSG     = 'd';  // Download programmable messages
    public static final char FID_EXTENDED_AMOUNT      = 'e';  // Extended length amount (12 digits)
    public static final char FID_TRACK3_DATA          = 'g';  // Track 3 data
    public static final char FID_HEARTBEAT_INTERVAL   = 'h';  // Heartbeat interval (seconds)
    public static final char FID_TRACK3_PAN           = 'i';  // PAN from Track 3
    public static final char FID_ISSUER_FEE           = 'k';  // Issuer fee/credit
    public static final char FID_MAC_KEY_LEFT         = 'm';  // MAC working key left block
    public static final char FID_REVERSAL_REASON      = 'n';  // Reversal reason code
    public static final char FID_QUASICASH_TOTAL      = 'o';  // Quasicash CREDIT total amounts
    public static final char FID_RECEIPT_TEXT          = 'p';  // Receipt text for printing
    public static final char FID_TIME_SYNC            = 't';  // Time synchronization
    public static final char FID_MAX_WITHDRAWAL       = 'w';  // Maximum withdraw amount

    // Extended FIDs (two character, starting with 'u')
    public static final String FID_GENERAL_PURPOSE    = "u";   // General purpose FID group
    public static final String FID_DATE_SYNC          = "ua";  // Date synchronization
    public static final String FID_CRC_16             = "ub";  // ANSI 16-bit CRC
    public static final String FID_EMV_TAGGED_DATA    = "ud";  // EMV tagged data block (TLV)
    public static final String FID_DATA_KEY_LEFT      = "ue";  // Data working key left block
    public static final String FID_DATA_KEY_RIGHT     = "uf";  // Data working key right block
    public static final String FID_HOST_TRACE_NUM     = "ug";  // Host transaction trace number
    public static final String FID_EMV_UNTAGGED_DATA  = "uh";  // EMV untagged data block
    public static final String FID_MOTORIZED_READER   = "ui";  // Motorized card reader commands

    // FID for encrypted working keys
    public static final char FID_PIN_KEY_1            = '~';   // PIN Working Key 1
    public static final char FID_PIN_KEY_2            = '{';   // PIN Working Key 2 (Triple-DES K2)
    public static final char FID_SURCHARGE_AMOUNT     = '!';   // Maximum surcharge amount
    public static final char FID_MAC_KEY_RIGHT        = 'j';   // MAC working key right block
    public static final char FID_ACCOUNT_NUMBER       = '&';   // Account number return
    public static final char FID_12DIGIT_SEQ          = '#';   // 12-digit sequence number
    public static final char FID_PIN_CHANGE_BLOCK     = '@';   // PIN change PIN block

    // DUKPT FID
    public static final char FID_DUKPT_KSN            = 'S';   // DUKPT Key Serial Number

    // Value Added Service FIDs
    public static final char FID_VAS_ALT_TERMINAL_ID  = 'A';   // Alternate terminal ID for VAS (uppercase)

    // Encryption Mode Flag values
    public static final char ENCRYPTION_SINGLE_DES    = '0';
    public static final char ENCRYPTION_RESERVED      = '1';
    public static final char ENCRYPTION_TRIPLE_DES    = '2';

    // Terminal Identifier values
    public static final String TERMINAL_TYPE_TRITON   = "t";   // Triton terminal
    public static final String TERMINAL_TYPE_OTHER_D  = "d";   // Other type 'd'
    public static final String TERMINAL_TYPE_OTHER_S  = "s";   // Other type 's'

    // =========================================================================
    // Reversal Reason Codes (FID 'n')
    // =========================================================================

    public static final String REVERSAL_TIMEOUT       = "01";  // Customer did not take cash
    public static final String REVERSAL_PARTIAL        = "02";  // Partial dispense
    public static final String REVERSAL_COMM_ERROR     = "03";  // Communication error
    public static final String REVERSAL_HARDWARE       = "04";  // Hardware error
    public static final String REVERSAL_MAC_ERROR      = "05";  // MAC error
    public static final String REVERSAL_VAS_ERROR      = "06";  // VAS Challenge/Response error

    // =========================================================================
    // Message Limits
    // =========================================================================

    public static final int MAX_MESSAGE_LENGTH         = 512;   // Standard
    public static final int MAX_MESSAGE_LENGTH_LARGE   = 2000;  // Large message buffer support
    public static final int TERMINAL_ID_LENGTH         = 15;
    public static final int AMOUNT_LENGTH              = 8;
    public static final int SEQUENCE_NUMBER_LENGTH     = 4;
    public static final int RESPONSE_CODE_LENGTH       = 3;
    public static final int AUTH_NUMBER_LENGTH          = 8;
    public static final int DATE_LENGTH                = 6;     // MMDDYY
    public static final int TIME_LENGTH                = 6;     // HHMMSS
    public static final int PIN_BLOCK_LENGTH           = 16;

    // =========================================================================
    // Default Communications Identifier
    // =========================================================================

    public static final String DEFAULT_COMM_ID = "XXXXXX^^";  // 8 chars, X=processor ID, ^=space

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Returns true if the response code indicates approval.
     */
    public static boolean isApproved(String responseCode) {
        return AUTH_APPROVED.equals(responseCode);
    }

    /**
     * Maps our internal transaction type to Triton transaction code.
     */
    public static String getTransactionCode(String transactionType, String accountType) {
        if (transactionType == null) return TXN_WITHDRAWAL_CHECKING;

        switch (transactionType.toUpperCase()) {
            case "WITHDRAWAL":
            case "CW":
                if ("SA".equals(accountType) || "SAVINGS".equalsIgnoreCase(accountType)) {
                    return TXN_WITHDRAWAL_SAVINGS;
                } else if ("CC".equals(accountType) || "CREDIT".equalsIgnoreCase(accountType)) {
                    return TXN_WITHDRAWAL_CREDIT;
                }
                return TXN_WITHDRAWAL_CHECKING;

            case "BALANCE":
            case "BALANCE_INQUIRY":
            case "BI":
                if ("SA".equals(accountType) || "SAVINGS".equalsIgnoreCase(accountType)) {
                    return TXN_BALANCE_SAVINGS;
                } else if ("CC".equals(accountType) || "CREDIT".equalsIgnoreCase(accountType)) {
                    return TXN_BALANCE_CREDIT;
                }
                return TXN_BALANCE_CHECKING;

            case "TRANSFER":
            case "TR":
                return TXN_TRANSFER_CHK_TO_SAV;

            case "REVERSAL":
                return TXN_REVERSAL;

            default:
                return TXN_WITHDRAWAL_CHECKING;
        }
    }

    /**
     * Left-pads a string with spaces to the specified length.
     */
    public static String padLeft(String value, int length) {
        if (value == null) value = "";
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(' ');
        }
        sb.append(value);
        return sb.toString();
    }

    /**
     * Left-pads a numeric string with zeros to the specified length.
     */
    public static String zeroPad(long value, int length) {
        return String.format("%0" + length + "d", value);
    }

    /**
     * Left-pads a string with zeros to the specified length.
     */
    public static String zeroPad(String value, int length) {
        if (value == null) value = "0";
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - value.length(); i++) {
            sb.append('0');
        }
        sb.append(value);
        return sb.toString();
    }
}
