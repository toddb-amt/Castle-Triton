package castech.emvtxn.atm.host;

/**
 * Reversal progress travels through the generic {@code onProgress(String)} channel,
 * so the kind is carried in a prefix:
 * <ul>
 *   <li>{@code [DRAIN] body} — about the queue as a whole (starting, exhausted,
 *       complete). Feeds the service banner and the Admin screen. Never a receipt.</li>
 *   <li>{@code [REVERSAL:<transactionId>] body} — about one record. Feeds the receipt
 *       only when that record belongs to the transaction being receipted.</li>
 * </ul>
 * The pre-6.2.7 form {@code [REVERSAL] body} had no id and was applied to whatever
 * receipt printed next — which is how a field customer's receipt came to say
 * "Reversal in progress" about somebody else's transaction. It now parses as DRAIN.
 */
public final class ReversalProgress {

    public enum Kind { DRAIN, RECORD, OTHER }

    static final String DRAIN_PREFIX  = "[DRAIN] ";
    static final String RECORD_OPEN   = "[REVERSAL:";
    static final String LEGACY_PREFIX = "[REVERSAL] ";

    public final Kind kind;
    /** Non-null only for {@link Kind#RECORD}. */
    public final String transactionId;
    public final String body;

    private ReversalProgress(Kind kind, String transactionId, String body) {
        this.kind = kind;
        this.transactionId = transactionId;
        this.body = body;
    }

    public static String drain(String body) {
        return DRAIN_PREFIX + body;
    }

    public static String record(String transactionId, String body) {
        return RECORD_OPEN + transactionId + "] " + body;
    }

    public static ReversalProgress parse(String message) {
        if (message == null) return new ReversalProgress(Kind.OTHER, null, null);
        if (message.startsWith(DRAIN_PREFIX)) {
            return new ReversalProgress(Kind.DRAIN, null, message.substring(DRAIN_PREFIX.length()));
        }
        if (message.startsWith(LEGACY_PREFIX)) {
            return new ReversalProgress(Kind.DRAIN, null, message.substring(LEGACY_PREFIX.length()));
        }
        if (message.startsWith(RECORD_OPEN)) {
            int close = message.indexOf("] ", RECORD_OPEN.length());
            if (close > RECORD_OPEN.length()) {
                return new ReversalProgress(Kind.RECORD,
                        message.substring(RECORD_OPEN.length(), close),
                        message.substring(close + 2));
            }
        }
        return new ReversalProgress(Kind.OTHER, null, message);
    }

    /** True only for a record-level message about {@code currentTransactionId}. */
    public boolean appliesToReceipt(String currentTransactionId) {
        return kind == Kind.RECORD && currentTransactionId != null
                && currentTransactionId.equals(transactionId);
    }
}
