package castech.emvtxn.pos;

/**
 * Wire-protocol constants shared across all POS module classes. Field names
 * here ARE the contract between this terminal app and the proxy
 * (tfi-proxy/backend-v2). Renaming any of these is a breaking change.
 *
 * <p>Documented in CASTLE_POS_INTEGRATION_SPEC.md (generated in Phase 8).
 */
public final class PosWire {

    private PosWire() {}

    // ---- URL paths (appended to PosConfig.proxyBaseUrl) -----------------------

    /** WebSocket path for registration — terminal dials, sends credentials, gets JWT. */
    public static final String PATH_REGISTER = "/castle/v1/register";

    /** WebSocket path for persistent connection — terminal dials with Authorization: Bearer JWT. */
    public static final String PATH_CONNECT = "/castle/v1/connect";

    // ---- Resource types (the value of resource.type) --------------------------

    public static final String RES_REGISTER = "register";

    // Transaction commands (proxy → terminal)
    public static final String RES_SALE             = "sale";
    public static final String RES_BALANCE_INQUIRY  = "balance_inquiry";
    public static final String RES_REVERSAL         = "reversal";
    public static final String RES_SETTLEMENT       = "settlement";
    public static final String RES_INFO             = "info";
    public static final String RES_REVERSAL_STATUS  = "reversal_status";

    // Unsupported commands — dispatcher returns "not_supported" for these
    public static final String RES_REFUND              = "refund";
    public static final String RES_VOID                = "void";
    public static final String RES_PREAUTH             = "preauth";
    public static final String RES_PREAUTH_COMPLETION  = "preauth_completion";
    public static final String RES_REPRINT             = "reprint";

    // Terminal-initiated events (terminal → proxy)
    public static final String EVT_STATE_CHANGE = "state_change";
    public static final String EVT_PROGRESS     = "progress";
    public static final String EVT_HEARTBEAT    = "heartbeat";

    // ---- Registration request fields ------------------------------------------

    public static final String REG_TYPE                 = "type";              // "register"
    public static final String REG_TSN                  = "tsn";               // terminal serial
    public static final String REG_TERMINAL_ACCESS_KEY  = "terminal_access_key";
    public static final String REG_CAPABILITIES         = "capabilities";      // JSONArray of supported resource types
    public static final String REG_APP_VERSION          = "app_version";
    public static final String REG_DEVICE_MODEL         = "device_model";

    // ---- Registration response fields -----------------------------------------

    public static final String REG_STATUS                  = "status";          // "approved" | "denied"
    public static final String REG_JWT                     = "jwt";
    public static final String REG_JWT_EXPIRES_AT          = "jwt_expires_at";  // millis-since-epoch
    public static final String REG_CONNECTION_URL          = "connection_url";  // optional override; else baseUrl + PATH_CONNECT
    public static final String REG_HEARTBEAT_INTERVAL_SEC  = "heartbeat_interval_sec";

    // ---- Transaction request fields (proxy → terminal) ------------------------

    public static final String TXN_AMOUNT        = "amount";         // integer cents
    public static final String TXN_SURCHARGE     = "surcharge";      // integer cents (optional)
    public static final String TXN_ACCOUNT_TYPE  = "account_type";   // "checking" | "savings" | "credit"
    public static final String TXN_TENDER_TYPE   = "tender_type";    // "debit" | "credit"
    public static final String TXN_INVOICE_NO    = "invoice_no";     // optional
    public static final String TXN_CLERK_ID      = "clerk_id";       // optional
    public static final String TXN_REFERENCE_NUMBER = "reference_number"; // for reversals

    // ---- Transaction response fields (terminal → proxy) -----------------------

    public static final String RSP_STATUS                  = "status";       // "approved" | "declined" | "error"
    public static final String RSP_RESPONSE_CODE           = "response_code";
    public static final String RSP_REFERENCE_NUMBER        = "reference_number";
    public static final String RSP_AUTH_CODE               = "auth_code";
    public static final String RSP_AUTH_DATE               = "auth_date";
    public static final String RSP_AUTH_TIME               = "auth_time";
    public static final String RSP_ACCOUNT_BALANCE_CENTS   = "account_balance_cents";
    public static final String RSP_AVAILABLE_BALANCE_CENTS = "available_balance_cents";
    public static final String RSP_DISPLAY_MESSAGE         = "display_message";
    public static final String RSP_RETAIN_CARD             = "retain_card";

    // ---- Standard error codes (resource error.code) ---------------------------

    public static final String ERR_NOT_SUPPORTED     = "not_supported";
    public static final String ERR_TERMINAL_BUSY     = "terminal_busy";
    public static final String ERR_HOST_UNREACHABLE  = "host_unreachable";
    public static final String ERR_KEY_NOT_LOADED    = "key_not_loaded";
    public static final String ERR_TIMEOUT           = "timeout";
    public static final String ERR_CARD_READ_FAILED  = "card_read_failed";
    public static final String ERR_PIN_ENTRY_FAILED  = "pin_entry_failed";
    public static final String ERR_REVERSAL_PENDING  = "reversal_pending";
    public static final String ERR_INVALID_REQUEST   = "invalid_request";
    public static final String ERR_INTERNAL          = "internal_error";

    // Registration-specific error codes
    public static final String ERR_UNKNOWN_TERMINAL  = "unknown_terminal";
    public static final String ERR_INVALID_CREDENTIALS = "invalid_credentials";
    public static final String ERR_CONNECTION_FAILED = "connection_failed";
    public static final String ERR_MALFORMED_RESPONSE = "malformed_response";
    public static final String ERR_INVALID_RESPONSE   = "invalid_response";

    // ---- Defaults -------------------------------------------------------------

    /** Default heartbeat interval if proxy doesn't supply one in the registration response. */
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SEC = 30;

    /** Registration request must complete within this many millis or it is failed. */
    public static final long REGISTRATION_TIMEOUT_MILLIS = 15_000L;
}
