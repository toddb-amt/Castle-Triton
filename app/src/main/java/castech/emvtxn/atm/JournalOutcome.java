package castech.emvtxn.atm;

/** Which journal row a finished transaction becomes. Pure; mapping pinned by JournalOutcomeTest. */
public final class JournalOutcome {
    public final String type;    // WITHDRAWAL | BALANCE_INQUIRY
    public final String result;  // APPROVED | DECLINED | CANCELLED
    public static final JournalOutcome CANCELLED = new JournalOutcome("WITHDRAWAL", "CANCELLED");
    JournalOutcome(String type, String result) { this.type = type; this.result = result; }

    /** A cancel with no host answer, keeping the transaction's type (review #16). */
    public static JournalOutcome cancelled(boolean balanceInquiry) {
        return new JournalOutcome(balanceInquiry ? "BALANCE_INQUIRY" : "WITHDRAWAL", "CANCELLED");
    }

    public static JournalOutcome of(boolean balanceInquiry, boolean hostApproved, String responseCode, String message) {
        String type = balanceInquiry ? "BALANCE_INQUIRY" : "WITHDRAWAL";
        if (hostApproved) return new JournalOutcome(type, "APPROVED");
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.US);
        String rc = responseCode == null ? "" : responseCode.trim();
        if (m.contains("cancel")) return new JournalOutcome(type, "CANCELLED");
        // No host answer at all (PIN-pad cancel, MSR give-up): a cancel, not a decline (review #4).
        if (rc.isEmpty() && m.trim().isEmpty()) return new JournalOutcome(type, "CANCELLED");
        return new JournalOutcome(type, "DECLINED");
    }

    /** Android-side convenience: read the finished transaction's state from GlobalPara. */
    public static JournalOutcome fromGlobalPara() {
        return of(castech.emvtxn.GlobalPara.atmBalanceInquiryMode, castech.emvtxn.GlobalPara.atmHostCallSuccess,
                  castech.emvtxn.GlobalPara.atmResponseCode, castech.emvtxn.GlobalPara.atmResponseMessage);
    }
}
