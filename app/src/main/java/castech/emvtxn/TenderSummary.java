package castech.emvtxn;

import castech.emvtxn.atm.TransactionLog;

/**
 * TNDR-01 (6.2.20): the merchant block for an unintegrated store, shown on the approval screen and
 * in the Reprint Receipt picker, never printed (owner decision 2026-10-22: the paper slip stays an
 * ATM receipt). The clerk types the TENDER into the POS: the original sale plus the change the
 * customer takes away, the tip excluded. The POS then expects the sale, sees the tender, and asks
 * for exactly that change; the tip stays with the staff instead of going back as change.
 *
 * <pre>
 *   saleAndTip = sale + tip          the partner's "Amount Due"
 *   changeDue  = withdrawal - saleAndTip   (the breakdown's cash back)
 *   tender     = sale + changeDue
 * </pre>
 * Worked example (partner email): sale $53.50, tip $5.00, withdrawal $60.00, fee $3.50, total
 * $63.50, change $1.50, tender $55.00. Pinned by TenderSummaryTest.
 */
public final class TenderSummary {

    public final long sale, tip, saleAndTip, withdrawal, fee, total, tender, changeDue;

    private TenderSummary(long sale, long tip, long withdrawal, long fee) {
        this.sale = sale;
        this.tip = tip;
        this.saleAndTip = sale + tip;
        this.withdrawal = withdrawal;
        this.fee = fee;
        this.total = withdrawal + fee;
        this.changeDue = withdrawal - sale - tip;
        this.tender = sale + changeDue;
    }

    public static TenderSummary of(AmountBreakdown a) {
        return new TenderSummary(a.sale, a.tip, a.withdrawal, a.fee);
    }

    public boolean hasTip() { return tip > 0; }

    /**
     * Whether the block is shown: an approved walk-up whose amount came with a tip or produced
     * change. A plain preset withdrawal has nothing to add, a declined transaction tenders nothing,
     * and a register sale already told the POS its breakdown.
     */
    public static boolean applies(AmountBreakdown a, boolean approved, boolean registerSale) {
        return approved && !registerSale && a != null && a.sale > 0 && (a.tip > 0 || a.cashBack > 0);
    }

    /** The same block rebuilt from a journal row, or null when it would not have shown. */
    public static TenderSummary fromRow(TransactionLog l) {
        if (l == null) return null;
        if (!TransactionLog.TYPE_WITHDRAWAL.equals(l.getTransactionType())) return null;
        if (!TransactionLog.RESULT_APPROVED.equals(l.getResult()) || l.isReversed()) return null;
        if (l.getClerkId() != null && !l.getClerkId().trim().isEmpty()) return null;     // register sale
        long sale = l.getSaleCents(), tip = l.getTipCents(), cashBack = l.getCashBackCents();
        if (sale <= 0 || (tip <= 0 && cashBack <= 0)) return null;                      // pre-6.2.13 rows have no sale
        return new TenderSummary(sale, tip, sale + tip + cashBack, l.getFeeCents());
    }
}
