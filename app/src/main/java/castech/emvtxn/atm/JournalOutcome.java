package castech.emvtxn.atm;

/** Which journal row a finished transaction becomes. Pure; mapping pinned by JournalOutcomeTest. */
public final class JournalOutcome {
    public final String type;    // WITHDRAWAL | BALANCE_INQUIRY
    public final String result;  // APPROVED | DECLINED | CANCELLED
    public static final JournalOutcome CANCELLED = new JournalOutcome("WITHDRAWAL", "CANCELLED");
    JournalOutcome(String type, String result) { this.type = type; this.result = result; }

    public static JournalOutcome of(boolean balanceInquiry, boolean hostApproved, String responseCode, String message) {
        String type = balanceInquiry ? "BALANCE_INQUIRY" : "WITHDRAWAL";
        if (hostApproved) return new JournalOutcome(type, "APPROVED");
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.US);
        if (m.contains("cancel")) return new JournalOutcome(type, "CANCELLED");
        return new JournalOutcome(type, "DECLINED");
    }

    /** Android-side convenience: read the finished transaction's state from GlobalPara. */
    public static JournalOutcome fromGlobalPara() {
        return of(castech.emvtxn.GlobalPara.atmBalanceInquiryMode, castech.emvtxn.GlobalPara.atmHostCallSuccess,
                  castech.emvtxn.GlobalPara.atmResponseCode, castech.emvtxn.GlobalPara.atmResponseMessage);
    }
}
