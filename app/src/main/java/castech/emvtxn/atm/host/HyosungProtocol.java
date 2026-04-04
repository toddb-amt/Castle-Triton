package castech.emvtxn.atm.host;

/**
 * Hyosung STD1 Protocol Constants
 *
 * Defines control characters, message types, transaction types, and response codes
 * for the Hyosung/Nautilus ATM message protocol.
 */
public final class HyosungProtocol {

    private HyosungProtocol() {
        // Utility class - prevent instantiation
    }

    // =========================================================================
    // Control Characters
    // =========================================================================

    /** Start of Text - marks beginning of message */
    public static final byte STX = 0x02;

    /** End of Text - marks end of message */
    public static final byte ETX = 0x03;

    /** Field Separator - delimits fields */
    public static final byte FS = 0x1C;

    /** Acknowledge - positive response */
    public static final byte ACK = 0x06;

    /** Negative Acknowledge - error response */
    public static final byte NAK = 0x15;

    /** End of Transmission - session complete */
    public static final byte EOT = 0x04;

    /** Enquiry - connection test (optional) */
    public static final byte ENQ = 0x05;

    // =========================================================================
    // Message Types (Transaction Codes)
    // =========================================================================

    /** Financial transaction (cash withdrawal, balance inquiry, transfer) */
    public static final String MSG_TYPE_TRANSACTION = "85";

    /** Transaction reversal */
    public static final String MSG_TYPE_REVERSAL = "86";

    /** Host totals / settlement */
    public static final String MSG_TYPE_HOST_TOTALS = "87";

    /** Configuration / key download */
    public static final String MSG_TYPE_CONFIG = "88";

    /** Health check (standard Hyosung) */
    public static final String MSG_TYPE_HEALTH_CHECK = "89";

    /** Status Monitoring (EFX/Switch Commerce style - uses H0 as transaction code) */
    public static final String MSG_TYPE_STATUS_MONITORING = "H0";

    // =========================================================================
    // Information Header
    // =========================================================================

    /** Standard information header prefix */
    public static final String INFO_HEADER_PREFIX = "H0.";

    /** Default routing ID */
    public static final String DEFAULT_ROUTING_ID = "000000";

    // =========================================================================
    // Operation Codes (first 2 chars of transaction type)
    // =========================================================================

    /** Cash Withdrawal operation */
    public static final String OP_CASH_WITHDRAWAL = "CW";

    /** Balance Inquiry operation */
    public static final String OP_BALANCE_INQUIRY = "BI";

    /** Transfer operation */
    public static final String OP_TRANSFER = "TR";

    /** Non-Cash Withdrawal operation */
    public static final String OP_NON_CASH_WITHDRAWAL = "NW";

    /** Deposit operation */
    public static final String OP_DEPOSIT = "DP";

    // =========================================================================
    // Account Type Codes (used in positions 3-4 and 5-6 of transaction type)
    // =========================================================================

    /** Checking Account */
    public static final String ACCT_CHECKING = "CA";

    /** Savings Account */
    public static final String ACCT_SAVINGS = "SA";

    /** Credit Card */
    public static final String ACCT_CREDIT = "CR";

    /** Money Market */
    public static final String ACCT_MONEY_MARKET = "MM";

    /** Line of Credit */
    public static final String ACCT_LINE_OF_CREDIT = "LN";

    // =========================================================================
    // Common Transaction Types (Operation + Source + Destination)
    // =========================================================================

    /** Cash Withdrawal from Checking */
    public static final String TXN_CW_CHECKING = "CWCACA";

    /** Cash Withdrawal from Savings */
    public static final String TXN_CW_SAVINGS = "CWSASA";

    /** Cash Withdrawal from Credit */
    public static final String TXN_CW_CREDIT = "CWCRCA";

    /** Balance Inquiry - Checking */
    public static final String TXN_BI_CHECKING = "BICACA";

    /** Balance Inquiry - Savings */
    public static final String TXN_BI_SAVINGS = "BISASA";

    /** Balance Inquiry - Credit */
    public static final String TXN_BI_CREDIT = "BICRCA";

    /** Transfer Checking to Savings */
    public static final String TXN_TR_CHECK_TO_SAVE = "TRCASA";

    /** Transfer Savings to Checking */
    public static final String TXN_TR_SAVE_TO_CHECK = "TRSACA";

    // =========================================================================
    // Reversal Transaction Types
    // =========================================================================

    /** Full Reversal - Cash Withdrawal */
    public static final String TXN_REV_FULL_CW = "CWFRRV";

    /** Partial Reversal - Cash Withdrawal */
    public static final String TXN_REV_PARTIAL_CW = "CWPRRV";

    /** Full Reversal - Non-Cash */
    public static final String TXN_REV_FULL_NW = "NWFRRV";

    /** Partial Reversal - Non-Cash */
    public static final String TXN_REV_PARTIAL_NW = "NWPRRV";

    // =========================================================================
    // Response Codes
    // =========================================================================

    // --- Approved ---
    /** Approved */
    public static final String RESP_APPROVED = "00";

    /** Partial Approval */
    public static final String RESP_PARTIAL_APPROVAL = "10";

    // --- Refer to Issuer ---
    /** Refer to Card Issuer */
    public static final String RESP_REFER_ISSUER = "01";

    /** Refer to Issuer (Special) */
    public static final String RESP_REFER_ISSUER_SPECIAL = "02";

    // --- Card Issues ---
    /** Pick Up Card */
    public static final String RESP_PICK_UP_CARD = "04";

    /** Do Not Honor */
    public static final String RESP_DO_NOT_HONOR = "05";

    /** Pick Up Card (Fraud) */
    public static final String RESP_PICK_UP_FRAUD = "07";

    /** Invalid Card Number */
    public static final String RESP_INVALID_CARD = "14";

    /** Expired Card - Pick Up */
    public static final String RESP_EXPIRED_PICKUP = "33";

    /** Suspected Fraud */
    public static final String RESP_SUSPECTED_FRAUD = "34";

    /** Lost Card */
    public static final String RESP_LOST_CARD = "41";

    /** Stolen Card */
    public static final String RESP_STOLEN_CARD = "43";

    /** Expired Card */
    public static final String RESP_EXPIRED_CARD = "54";

    /** Transaction Not Permitted */
    public static final String RESP_TXN_NOT_PERMITTED = "57";

    /** Restricted Card */
    public static final String RESP_RESTRICTED_CARD = "62";

    // --- Account Issues ---
    /** Insufficient Funds */
    public static final String RESP_INSUFFICIENT_FUNDS = "51";

    /** No Checking Account */
    public static final String RESP_NO_CHECKING = "52";

    /** No Savings Account */
    public static final String RESP_NO_SAVINGS = "53";

    // --- PIN/Security ---
    /** Incorrect PIN */
    public static final String RESP_INCORRECT_PIN = "55";

    /** PIN Tries Exceeded */
    public static final String RESP_PIN_TRIES_EXCEEDED = "75";

    /** Key Sync Error */
    public static final String RESP_KEY_SYNC_ERROR = "76";

    // --- Limits ---
    /** Exceeds Amount Limit */
    public static final String RESP_EXCEEDS_AMOUNT = "61";

    /** Exceeds Frequency Limit */
    public static final String RESP_EXCEEDS_FREQUENCY = "65";

    // --- System/Network ---
    /** Invalid Transaction */
    public static final String RESP_INVALID_TXN = "12";

    /** Invalid Amount */
    public static final String RESP_INVALID_AMOUNT = "13";

    /** Format Error */
    public static final String RESP_FORMAT_ERROR = "30";

    /** Issuer Unavailable */
    public static final String RESP_ISSUER_UNAVAILABLE = "91";

    /** System Malfunction */
    public static final String RESP_SYSTEM_MALFUNCTION = "96";

    // =========================================================================
    // Reversal Reason Codes
    // =========================================================================

    /** Timeout/no response */
    public static final String REV_REASON_TIMEOUT = "";

    /** Customer cancelled */
    public static final String REV_REASON_CUSTOMER_CANCEL = "01";

    /** Dispense failure */
    public static final String REV_REASON_DISPENSE_FAIL = "02";

    /** Partial dispense */
    public static final String REV_REASON_PARTIAL_DISPENSE = "03";

    /** Card retained */
    public static final String REV_REASON_CARD_RETAINED = "04";

    /** Host error */
    public static final String REV_REASON_HOST_ERROR = "05";

    // =========================================================================
    // Configuration Types
    // =========================================================================

    /** Full configuration download */
    public static final String CONFIG_FULL = "1";

    /** Partial configuration update */
    public static final String CONFIG_PARTIAL = "2";

    /** Key download only */
    public static final String CONFIG_KEY_ONLY = "3";

    /** Surcharge update */
    public static final String CONFIG_SURCHARGE = "4";

    /** Extended configuration */
    public static final String CONFIG_EXTENDED = "5";

    // =========================================================================
    // Host Totals Flags
    // =========================================================================

    /** Query totals only */
    public static final String TOTALS_QUERY = "0";

    /** Reset after query */
    public static final String TOTALS_RESET = "1";

    // =========================================================================
    // Dispense Flags
    // =========================================================================

    /** Do not dispense cash */
    public static final String DISPENSE_NO = "0";

    /** Dispense cash */
    public static final String DISPENSE_YES = "1";

    // =========================================================================
    // Framing Types
    // =========================================================================

    public enum FramingType {
        /** Standard STX/ETX framing with LRC (DNS, FIS, CDS, etc.) */
        STANDARD,

        /** VISA framing with 2-byte length header + STX/ETX/LRC */
        VISA_LENGTH_PREFIX,

        /** VISA framing with 2-byte length header only, NO STX/ETX/LRC (Switch Commerce style) */
        VISA_NO_STX_ETX
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if a response code indicates approval
     * @param responseCode The 2-digit response code
     * @return true if approved or partially approved
     */
    public static boolean isApproved(String responseCode) {
        return RESP_APPROVED.equals(responseCode) || RESP_PARTIAL_APPROVAL.equals(responseCode);
    }

    /**
     * Checks if a response code indicates the card should be retained
     * @param responseCode The 2-digit response code
     * @return true if card should be retained
     */
    public static boolean shouldRetainCard(String responseCode) {
        return RESP_PICK_UP_CARD.equals(responseCode) ||
               RESP_PICK_UP_FRAUD.equals(responseCode) ||
               RESP_EXPIRED_PICKUP.equals(responseCode) ||
               RESP_SUSPECTED_FRAUD.equals(responseCode) ||
               RESP_LOST_CARD.equals(responseCode) ||
               RESP_STOLEN_CARD.equals(responseCode) ||
               RESP_PIN_TRIES_EXCEEDED.equals(responseCode);
    }

    /**
     * Checks if a response code indicates a key sync is required
     * @param responseCode The 2-digit response code
     * @return true if key download should be requested
     */
    public static boolean requiresKeySync(String responseCode) {
        return RESP_KEY_SYNC_ERROR.equals(responseCode);
    }

    /**
     * Builds a standard information header
     * @param routingId The routing ID (4-6 characters, e.g., "SC101", "000000")
     * @return The complete information header (e.g., "H0.000000", "H0.SC101")
     */
    public static String buildInfoHeader(String routingId) {
        // Accept routing IDs with 4-6 characters
        if (routingId == null || routingId.length() < 4 || routingId.length() > 6) {
            routingId = DEFAULT_ROUTING_ID;
        }
        return INFO_HEADER_PREFIX + routingId;
    }

    /**
     * Builds a transaction type code
     * @param operation The operation code (CW, BI, TR, etc.)
     * @param sourceAccount The source account type (CA, SA, CR, etc.)
     * @param destAccount The destination account type (CA, SA, CR, etc.)
     * @return The 6-character transaction type code
     */
    public static String buildTransactionType(String operation, String sourceAccount, String destAccount) {
        return operation + sourceAccount + destAccount;
    }

    /**
     * Gets the human-readable description for a response code
     * @param responseCode The 2-digit response code
     * @return Description of the response
     */
    public static String getResponseDescription(String responseCode) {
        if (responseCode == null) return "Unknown";
        switch (responseCode) {
            case RESP_APPROVED: return "Approved";
            case RESP_PARTIAL_APPROVAL: return "Partial Approval";
            case RESP_REFER_ISSUER: return "Refer to Card Issuer";
            case RESP_REFER_ISSUER_SPECIAL: return "Refer to Issuer (Special)";
            case RESP_PICK_UP_CARD: return "Pick Up Card";
            case RESP_DO_NOT_HONOR: return "Do Not Honor";
            case RESP_PICK_UP_FRAUD: return "Pick Up Card (Fraud)";
            case RESP_INVALID_CARD: return "Invalid Card Number";
            case RESP_EXPIRED_PICKUP: return "Expired Card - Pick Up";
            case RESP_SUSPECTED_FRAUD: return "Suspected Fraud";
            case RESP_LOST_CARD: return "Lost Card";
            case RESP_STOLEN_CARD: return "Stolen Card";
            case RESP_INSUFFICIENT_FUNDS: return "Insufficient Funds";
            case RESP_NO_CHECKING: return "No Checking Account";
            case RESP_NO_SAVINGS: return "No Savings Account";
            case RESP_EXPIRED_CARD: return "Expired Card";
            case RESP_INCORRECT_PIN: return "Incorrect PIN";
            case RESP_TXN_NOT_PERMITTED: return "Transaction Not Permitted";
            case RESP_EXCEEDS_AMOUNT: return "Exceeds Amount Limit";
            case RESP_RESTRICTED_CARD: return "Restricted Card";
            case RESP_EXCEEDS_FREQUENCY: return "Exceeds Frequency Limit";
            case RESP_PIN_TRIES_EXCEEDED: return "PIN Tries Exceeded";
            case RESP_KEY_SYNC_ERROR: return "Key Sync Error";
            case RESP_INVALID_TXN: return "Invalid Transaction";
            case RESP_INVALID_AMOUNT: return "Invalid Amount";
            case RESP_FORMAT_ERROR: return "Format Error";
            case RESP_ISSUER_UNAVAILABLE: return "Issuer Unavailable";
            case RESP_SYSTEM_MALFUNCTION: return "System Malfunction";
            default: return "Unknown (" + responseCode + ")";
        }
    }
}
