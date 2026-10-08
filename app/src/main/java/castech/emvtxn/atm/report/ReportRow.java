package castech.emvtxn.atm.report;

/** One journaled transaction, as the report needs it. Plain data, no Android. */
public final class ReportRow {
    public final int sequence;
    public final String last4;
    public final int entryMode;      // 1 contact, 2 contactless, 3 MSR, 0 unknown
    public final int accountType;    // 10 savings, 20 checking, 30 credit
    public final long amountCents;
    public final long feeCents;
    public final long tipCents;
    /** TIP-01 (6.2.14): what the customer asked for; 0 on rows journaled before 6.2.13's backfill. */
    public final long saleCents;
    /** TIP-01 (6.2.14): withdrawal − sale − tip, the rounding remainder the customer received. */
    public final long cashBackCents;
    public final String authCode;
    public final String rrn;
    public final String clerkId;     // null when not a register sale
    public final String invoiceNo;   // null when not a register sale
    public final String type;        // WITHDRAWAL | BALANCE_INQUIRY
    public final String result;      // APPROVED | DECLINED | CANCELLED
    public final boolean reversed;

    public ReportRow(int sequence, String last4, int entryMode, int accountType, long amountCents, long feeCents,
                     long tipCents, long saleCents, long cashBackCents, String authCode, String rrn, String clerkId, String invoiceNo,
                     String type, String result, boolean reversed) {
        this.sequence = sequence; this.last4 = last4; this.entryMode = entryMode; this.accountType = accountType;
        this.amountCents = amountCents; this.feeCents = feeCents; this.tipCents = tipCents;
        this.saleCents = saleCents; this.cashBackCents = cashBackCents;
        this.authCode = authCode; this.rrn = rrn; this.clerkId = clerkId; this.invoiceNo = invoiceNo;
        this.type = type; this.result = result; this.reversed = reversed;
    }

    public boolean isApprovedWithdrawal() {
        return "WITHDRAWAL".equals(type) && "APPROVED".equals(result) && !reversed;
    }
}
