package castech.emvtxn.atm.report;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import castech.emvtxn.Money;
import castech.emvtxn.atm.TransactionLog;

/**
 * The on-screen Transaction History for the Admin card (HIST-01, 6.2.16): newest first, every
 * batch still on the terminal, grouped under a batch header. Pure Java, 32 columns like the
 * Detail Report so the same monospace dialog shows it; the layout is pinned by HistoryReportTest.
 * The journal holds only the last four card digits, and that is all this prints.
 */
public final class HistoryReport {

    public static final int WIDTH = 32;
    /** Newest rows the dialog shows; the header says when the journal holds more. */
    public static final int MAX_ROWS = 200;
    private static final String RULE = "================================";
    private static final String THIN = "--------------------------------";

    private HistoryReport() {}

    /** The Admin card line under the section header. */
    public static String statsLine(int totalRows, int currentBatchId) {
        String s = "Total Transactions: " + totalRows;
        if (currentBatchId > 0) s += "   (open batch " + String.format(Locale.US, "%03d", currentBatchId) + ")";
        return s;
    }

    /**
     * @param newestFirst journal rows ordered newest first (TransactionLogManager.getRecentTransactions)
     * @param totalRows   rows in the whole journal, so the header can say when the list is capped
     */
    public static String render(String terminalId, long nowMillis, int currentBatchId, int totalRows,
                                List<TransactionLog> newestFirst) {
        SimpleDateFormat full = new SimpleDateFormat("MM/dd/yy HH:mm", Locale.US);
        SimpleDateFormat shortF = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder();
        line(b, center("TRANSACTION HISTORY"));
        line(b, RULE);
        line(b, pair("TID " + cut(nz(terminalId), 8), full.format(new Date(nowMillis))));
        line(b, "Transactions on terminal: " + totalRows);
        if (newestFirst.size() < totalRows) line(b, "Showing newest " + newestFirst.size());
        line(b, THIN);

        if (newestFirst.isEmpty()) {
            line(b, "  (no transactions)");
        } else {
            boolean first = true;
            int lastBatch = Integer.MIN_VALUE;
            for (TransactionLog l : newestFirst) {
                if (l.getBatchId() != lastBatch) {
                    if (!first) line(b, THIN);
                    line(b, batchHeader(l.getBatchId(), currentBatchId));
                    lastBatch = l.getBatchId();
                    first = false;
                }
                row(b, l, shortF);
            }
        }
        line(b, RULE);
        line(b, "Terminal record - processor");
        line(b, "totals govern");
        return b.toString();
    }

    private static String batchHeader(int batchId, int currentBatchId) {
        if (batchId <= 0) return "BATCH ---";          // journaled before 6.2.11 had batches
        String h = "BATCH " + String.format(Locale.US, "%03d", batchId);
        return batchId == currentBatchId ? h + " (OPEN)" : h;
    }

    private static void row(StringBuilder b, TransactionLog l, SimpleDateFormat shortF) {
        String type = nz(l.getTransactionType());
        line(b, shortF.format(new Date(l.getTimestamp())) + "  " + typeCode(type) + "  " + cardCell(l.getCardLastFour())
                + "  " + entry(l.getEntryMode()));
        String left = TransactionLog.TYPE_BALANCE_INQUIRY.equals(type)
                ? " balance inquiry"
                : " " + money(l.getAmountCents()) + " fee " + money(l.getFeeCents());
        // "DECLINED 76" sits beside the money; a terminal-made code (MSR_NA, APP_SEL) that would not
        // fit goes on its own line rather than being clipped.
        String result = resultLabel(l);
        String code = null;
        if (left.length() + 2 + result.length() > WIDTH && result.startsWith(TransactionLog.RESULT_DECLINED + " ")) {
            code = result.substring(TransactionLog.RESULT_DECLINED.length() + 1);
            result = TransactionLog.RESULT_DECLINED;
        }
        line(b, pair(left, result));
        if (code != null) line(b, " code " + code);
        if (TransactionLog.RESULT_APPROVED.equals(l.getResult())) {
            line(b, " #" + String.format(Locale.US, "%05d", l.getSequenceNumber()) + "  auth "
                    + (notBlank(l.getAuthCode()) ? cut(l.getAuthCode(), 6) : "--"));
        }
        if (notBlank(l.getClerkId()) || notBlank(l.getInvoiceNo())) {     // register sale
            line(b, " clerk " + nz(l.getClerkId()) + "  inv " + nz(l.getInvoiceNo()));
        }
    }

    private static String resultLabel(TransactionLog l) {
        if (l.isReversed() && TransactionLog.TYPE_WITHDRAWAL.equals(l.getTransactionType())) return "REVERSED";
        String r = nz(l.getResult());
        if (r.isEmpty()) return "?";
        if (TransactionLog.RESULT_DECLINED.equals(r) && notBlank(l.getResponseCode())) return r + " " + l.getResponseCode().trim();
        return r;
    }

    private static String typeCode(String type) {
        if (TransactionLog.TYPE_WITHDRAWAL.equals(type)) return "WD";
        if (TransactionLog.TYPE_BALANCE_INQUIRY.equals(type)) return "BI";
        if (TransactionLog.TYPE_REVERSAL.equals(type)) return "RV";
        return type.isEmpty() ? "??" : String.format(Locale.US, "%-2s", cut(type, 2));
    }

    /** The eight-column card cell: "****1234", or "no card " when the row never got card data. */
    private static String cardCell(String last4) {
        String s = nz(last4).trim();
        if (s.isEmpty()) return "no card ";
        // never more than four digits, whatever the row holds
        return "****" + (s.length() <= 4 ? String.format(Locale.US, "%4s", s) : s.substring(s.length() - 4));
    }

    private static String entry(String em) {
        if (TransactionLog.ENTRY_CONTACT.equals(em)) return "C";
        if (TransactionLog.ENTRY_CONTACTLESS.equals(em)) return "T";
        if (TransactionLog.ENTRY_MSR.equals(em)) return "S";
        return "?";
    }

    private static String money(long cents) { return "$" + Money.dollars(cents); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String cut(String s, int n) { s = nz(s); return s.length() <= n ? s : s.substring(0, n); }
    private static String center(String s) { int pad = Math.max(0, (WIDTH - s.length()) / 2); return String.format(Locale.US, "%" + (pad + s.length()) + "s", s); }
    /** left and right text on one 32-column line, at least two spaces apart. */
    private static String pair(String left, String rightText) {
        int gap = WIDTH - left.length() - rightText.length();
        if (gap < 2) { left = cut(left, Math.max(0, WIDTH - rightText.length() - 2)); gap = WIDTH - left.length() - rightText.length(); }
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < gap; i++) sb.append(' ');
        return sb.append(rightText).toString();
    }
    /** Every emitted line is clipped to the width. */
    private static void line(StringBuilder b, String s) { b.append(cut(s, WIDTH)).append('\n'); }
}
