package castech.emvtxn.atm.report;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import castech.emvtxn.Money;

/**
 * The Detail Report text for the 32-column receipt printer. Pure Java; the layout is
 * pinned by DetailReportTest. See docs/superpowers/specs/2026-10-01-detail-report-design.md.
 */
public final class DetailReport {

    public static final int WIDTH = 32;
    private static final String RULE = "================================";
    private static final String THIN = "--------------------------------";

    private DetailReport() {}

    public static final class Header {
        public final int batchId; public final long openedAtMillis; public final long printedAtMillis; public final String terminalId;
        public Header(int batchId, long openedAtMillis, long printedAtMillis, String terminalId) {
            this.batchId = batchId; this.openedAtMillis = openedAtMillis; this.printedAtMillis = printedAtMillis;
            this.terminalId = terminalId == null ? "" : terminalId;
        }
    }

    public static final class Summary {
        public int withdrawals, declined, cancelled, balanceInquiries, reversed;
        public long amountCents, feeCents, tipCents, cashBackCents;
        // per card type, for the optional second group
        public int creditWithdrawals; public long creditAmountCents, creditFeeCents, creditTipCents, creditCashBackCents;
    }

    public static Summary summarize(List<ReportRow> rows) {
        Summary s = new Summary();
        for (ReportRow r : rows) {
            // A withdrawal the processor reversed counts under Reversed whatever the terminal
            // recorded locally (approved, or declined/timed out before the answer) — review #2.
            if (r.reversed && "WITHDRAWAL".equals(r.type)) { s.reversed++; continue; }
            if ("BALANCE_INQUIRY".equals(r.type)) { s.balanceInquiries++; continue; }
            if (!"WITHDRAWAL".equals(r.type)) continue;
            if ("APPROVED".equals(r.result)) {
                s.withdrawals++; s.amountCents += r.amountCents; s.feeCents += r.feeCents; s.tipCents += r.tipCents; s.cashBackCents += r.cashBackCents;
                if (r.accountType == 30) { s.creditWithdrawals++; s.creditAmountCents += r.amountCents; s.creditFeeCents += r.feeCents; s.creditTipCents += r.tipCents; s.creditCashBackCents += r.cashBackCents; }
            } else if ("CANCELLED".equals(r.result)) {
                s.cancelled++;
            } else {
                s.declined++;
            }
        }
        return s;
    }

    public static String render(Header h, List<ReportRow> rows) {
        SimpleDateFormat full = new SimpleDateFormat("MM/dd/yy HH:mm:ss", Locale.US);
        SimpleDateFormat shortF = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder();
        line(b, center("DETAIL REPORT"));
        line(b, RULE);
        line(b, pair(full.format(new Date(h.printedAtMillis)), "Batch #: " + String.format(Locale.US, "%03d", h.batchId)));
        line(b, pair("TID " + cut(h.terminalId, 8), "Opened " + shortF.format(new Date(h.openedAtMillis))));
        line(b, THIN);

        List<ReportRow> approved = new ArrayList<>();
        for (ReportRow r : rows) if (r.isApprovedWithdrawal()) approved.add(r);
        if (approved.isEmpty()) {
            line(b, "  (no approved withdrawals)");
        } else {
            for (int i = 0; i < approved.size(); i++) {
                ReportRow r = approved.get(i);
                line(b, String.format(Locale.US, "%-8s  CWDR %s  %s    #%05d",
                        "****" + cut(nz(r.last4), 4), cardType(r.accountType), entry(r.entryMode), r.sequence));
                if (notBlank(r.clerkId) || notBlank(r.invoiceNo)) {
                    String one = String.format(Locale.US, " CLRK %-8s INV %s", nz(r.clerkId), nz(r.invoiceNo));
                    if (one.length() <= WIDTH) {
                        line(b, one);
                    } else {   // never clip a clerk/invoice — split onto two lines (review #6)
                        line(b, " CLRK " + cut(nz(r.clerkId), WIDTH - 6));
                        line(b, " INV " + cut(nz(r.invoiceNo), WIDTH - 5));
                    }
                }
                line(b, String.format(Locale.US, " AUTH %-6s   REF %s", notBlank(r.authCode) ? cut(r.authCode, 6) : "--", nz(r.rrn)));
                // TIP-01 (6.2.14): SALE is what the customer asked for; TOTAL = withdrawal + fee (the tip is
                // inside the withdrawal). Rows journaled before the sale column existed show the amount.
                line(b, " SALE " + right(money(r.saleCents > 0 ? r.saleCents : r.amountCents), 9) + "   FEE " + right(money(r.feeCents), 9));
                line(b, " TIP " + right(money(r.tipCents), 9) + "  TOTAL " + right(money(r.amountCents + r.feeCents), 8));
                if (i < approved.size() - 1) line(b, THIN);
            }
        }
        line(b, RULE);

        Summary s = summarize(rows);
        boolean allCredit = s.creditWithdrawals > 0 && s.creditWithdrawals == s.withdrawals;
        boolean twoGroups = s.creditWithdrawals > 0 && s.creditWithdrawals < s.withdrawals;
        if (allCredit) {
            group(b, "CREDIT", s.withdrawals, s.amountCents, s.feeCents, s.tipCents, s.cashBackCents);
        } else {
            group(b, "DEBIT", s.withdrawals - s.creditWithdrawals, s.amountCents - s.creditAmountCents,
                    s.feeCents - s.creditFeeCents, s.tipCents - s.creditTipCents, s.cashBackCents - s.creditCashBackCents);
            if (twoGroups) {
                line(b, THIN);
                group(b, "CREDIT", s.creditWithdrawals, s.creditAmountCents, s.creditFeeCents, s.creditTipCents, s.creditCashBackCents);
                line(b, THIN);
                line(b, "GRAND TOTALS");
                line(b, sum("Withdrawals", s.withdrawals, s.amountCents));
                line(b, sum(" Fees", -1, s.feeCents));
                line(b, sum(" Tips", -1, s.tipCents));
                line(b, sum(" Cash back", -1, s.cashBackCents));
                line(b, sum("Grand Total", s.withdrawals, s.amountCents + s.feeCents));
            }
        }
        line(b, THIN);
        // counts right-aligned at col 16 and col 32; 3-digit counts print in full (review #7)
        line(b, String.format(Locale.US, "%-11s%5d   %-9s%4d", "Declined", s.declined, "Cancelled", s.cancelled));
        line(b, String.format(Locale.US, "%-13s%3d   %-9s%4d", "Bal Inquiries", s.balanceInquiries, "Reversed", s.reversed));
        line(b, RULE);
        line(b, "Terminal record - processor");
        line(b, "totals govern");
        line(b, center("END OF REPORT"));
        b.append("\n\n");
        return b.toString();
    }

    private static void group(StringBuilder b, String name, int count, long amt, long fee, long tip, long cashBack) {
        line(b, "SUMMARY  (" + name + ")");
        line(b, sum("Withdrawals", count, amt));
        line(b, sum(" Fees", -1, fee));
        line(b, sum(" Tips", -1, tip));
        line(b, sum(" Cash back", -1, cashBack));
        line(b, sum("Total", count, amt + fee));     // the tip is inside the withdrawal (TIP-01)
    }

    /** label (12) + count (4, or blank) + amount right-aligned in 14 = 30 (spec layout). */
    private static String sum(String label, int count, long cents) {
        String c = count < 0 ? "    " : String.format(Locale.US, "%4d", count);
        return String.format(Locale.US, "%-12s", cut(label, 12)) + c + right(money(cents), 14);
    }

    private static String money(long cents) { return "$" + Money.dollars(cents); }
    private static String cardType(int acct) { return acct == 30 ? "CR" : "DB"; }
    private static String entry(int em) { return em == 1 ? "C" : em == 2 ? "T" : em == 3 ? "S" : "?"; }
    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String cut(String s, int n) { s = nz(s); return s.length() <= n ? s : s.substring(0, n); }
    private static String right(String s, int w) { return s.length() >= w ? s : String.format(Locale.US, "%" + w + "s", s); }
    private static String center(String s) { int pad = Math.max(0, (WIDTH - s.length()) / 2); return String.format(Locale.US, "%" + (pad + s.length()) + "s", s); }
    /** left and right text on one 32-column line, at least two spaces apart. */
    private static String pair(String left, String rightText) {
        int gap = WIDTH - left.length() - rightText.length();
        if (gap < 2) { left = cut(left, Math.max(0, WIDTH - rightText.length() - 2)); gap = WIDTH - left.length() - rightText.length(); }
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < gap; i++) sb.append(' ');
        return sb.append(rightText).toString();
    }
    /** Every emitted line is clipped to the printer width. */
    private static void line(StringBuilder b, String s) { b.append(cut(s, WIDTH)).append('\n'); }
}
