package castech.emvtxn.reporting;

/** Which journal rows the MyView push sends (RPT-02, decision R3). Pure. */
public final class PushEligibility {
    public static final int PUSH_NOT_APPLICABLE = 0;
    public static final int PUSH_PENDING = 1;
    public static final int PUSH_SENT = 2;
    public static final int PUSH_PARKED = 3;

    private PushEligibility() {}

    /**
     * True for rows the host answered and for terminal-side declines where a card was presented
     * (both are journaled DECLINED), and for every reversal row. A cancel — Cancel pressed, no
     * card, PIN pad abandoned — is journaled CANCELLED and is never sent.
     */
    public static boolean isSendable(String transactionType, String result) {
        if (transactionType == null || result == null) return false;
        if ("REVERSAL".equals(transactionType)) return true;
        if (!"WITHDRAWAL".equals(transactionType) && !"BALANCE_INQUIRY".equals(transactionType)) return false;
        return "APPROVED".equals(result) || "DECLINED".equals(result);
    }

    /** The state a freshly written row gets. */
    public static int initialState(String transactionType, String result, boolean reportingConfigured) {
        return reportingConfigured && isSendable(transactionType, result) ? PUSH_PENDING : PUSH_NOT_APPLICABLE;
    }
}
