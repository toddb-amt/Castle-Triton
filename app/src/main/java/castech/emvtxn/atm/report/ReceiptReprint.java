package castech.emvtxn.atm.report;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import castech.emvtxn.ReceiptMoneyBlock;
import castech.emvtxn.atm.TransactionLog;

/**
 * RCPT-02 (6.2.19): the customer receipt rebuilt from its journal row, for Reprint Receipt on the
 * ellipsis menu and the Admin Transaction History card. Pure Java. The text follows
 * {@code Fragment_page_receipt.printReceiptContent} line for line (header, status, money block or
 * balances, card, date, ids, reversal block, footer) with a DUPLICATE marker and the time of the
 * reprint under the header, so support can tell a reprint from the slip the customer took away.
 * The journal keeps the last four card digits only, so the card line is "****1234" without the
 * brand the original may have shown. Dates use the device zone like the original receipt (TZ-01).
 * The picker shows two lines per row ({@link #pickerLine1}, {@link #pickerLine2}) so the list reads
 * row by row on the terminal's narrow dialog. Pinned by ReceiptReprintTest.
 */
public final class ReceiptReprint {

    public static final int WIDTH = 32;
    /** Rows the picker offers, newest first. */
    public static final int MAX_ROWS = 20;

    private static final String RULE = "================================";
    private static final String THIN = "--------------------------------";
    /** The receipt screen's date format, so the duplicate reads like the original. */
    private static final String DATE_FMT = "yyyy/MM/dd HH:mm:ss";
    private static final String SEP = "   ";

    private ReceiptReprint() {}

    /**
     * The rows a customer receipt can be reprinted for, newest first, capped at {@link #MAX_ROWS}:
     * transactions that ended with a slip. Cancelled rows are skipped (owner decision 2026-10-20: a
     * cancelled transaction never showed a receipt), as are reversal bookkeeping rows (written for
     * the MyView push, RPT-02); a reversed withdrawal is offered as the withdrawal itself and prints
     * the reversal block. Timeouts and errors stay in: their original slip read STATUS: DECLINED.
     */
    public static List<TransactionLog> printable(List<TransactionLog> newestFirst) {
        List<TransactionLog> out = new ArrayList<>(MAX_ROWS);
        for (TransactionLog l : newestFirst) {
            if (TransactionLog.TYPE_REVERSAL.equals(l.getTransactionType())) continue;
            if (TransactionLog.RESULT_CANCELLED.equals(l.getResult())) continue;
            out.add(l);
            if (out.size() == MAX_ROWS) break;
        }
        return out;
    }

    /**
     * Picker line one: {@code 10/20 14:12   WD   ****1234   $100.00}. A balance inquiry has no amount
     * cell ({@code 10/20 14:12   BI   ****1234}): the BI code already says what it is, and a longer
     * line wraps on the terminal's dialog.
     */
    public static String pickerLine1(TransactionLog l) {
        String type = nz(l.getTransactionType());
        String last4 = nz(l.getCardLastFour()).trim();
        String line = new SimpleDateFormat("MM/dd HH:mm", Locale.US).format(new Date(l.getTimestamp()))
                + SEP + HistoryReport.typeCode(type)
                + SEP + (last4.isEmpty() ? "no card" : maskedCard(last4));
        return TransactionLog.TYPE_BALANCE_INQUIRY.equals(type) ? line : line + SEP + currency(l.getAmountCents());
    }

    /** Picker line two: the outcome, {@code APPROVED}, {@code DECLINED 51} or {@code REVERSED}. */
    public static String pickerLine2(TransactionLog l) {
        return HistoryReport.resultLabel(l);
    }

    /** The duplicate receipt text, ready for one {@code printf}. */
    public static String render(TransactionLog l, long reprintedAtMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FMT, Locale.US);
        boolean balanceInquiry = TransactionLog.TYPE_BALANCE_INQUIRY.equals(l.getTransactionType());
        boolean approved = TransactionLog.RESULT_APPROVED.equals(l.getResult());
        StringBuilder r = new StringBuilder(640);

        r.append('\n');
        r.append(RULE).append('\n');
        r.append(balanceInquiry ? "      BALANCE INQUIRY           " : "         ATM RECEIPT            ").append('\n');
        r.append(RULE).append('\n');
        r.append("       *** DUPLICATE ***\n");
        r.append("Reprinted: ").append(sdf.format(new Date(reprintedAtMillis))).append('\n');
        r.append('\n');

        if (approved) {
            r.append("   STATUS: APPROVED\n");
        } else {
            r.append("   STATUS: DECLINED\n");
            if (notBlank(l.getErrorMessage())) {
                wrapped(r, "   Reason: ", l.getErrorMessage().trim());
            } else if (notBlank(l.getResponseCode())) {
                r.append("   Code: ").append(l.getResponseCode().trim()).append('\n');
            }
        }
        r.append('\n');

        if (balanceInquiry && approved) {
            r.append("Account Balance:   ").append(plain(l.getBalanceAccountCents())).append('\n');
            r.append("Available Balance: ").append(plain(l.getBalanceAvailableCents())).append('\n');
            r.append('\n');
        } else {
            // RCPT-01: always Withdrawal Amount / Service Fee / Total Charged, whatever else the row holds
            r.append(ReceiptMoneyBlock.printed(l.getAmountCents(), l.getFeeCents(), l.getTotalCents()));
        }

        r.append("Card: ").append(maskedCard(nz(l.getCardLastFour()).trim())).append('\n');
        r.append("Date/Time: ").append(sdf.format(new Date(l.getTimestamp()))).append('\n');
        r.append("TransID: ").append(nz(l.getTransactionId())).append('\n');
        if (notBlank(l.getAuthCode())) r.append("Auth Code: ").append(l.getAuthCode().trim()).append('\n');
        if (notBlank(l.getReferenceNumber())) r.append("Ref #: ").append(l.getReferenceNumber().trim()).append('\n');
        r.append('\n');

        if (l.isReversed()) {
            r.append(THIN).append('\n');
            r.append("           REVERSAL             \n");
            r.append(THIN).append('\n');
            r.append("Status: SENT TO PROCESSOR\n");
            r.append("Card NOT charged.\n");
            r.append('\n');
        }

        r.append(RULE).append('\n');
        r.append("     Thank you for using our    \n");
        r.append("           ATM Service          \n");
        r.append(RULE).append('\n');
        r.append("\n\n\n");
        return r.toString();
    }

    /** "****1234"; "****" when the row has no card digits. Never more than four digits. */
    private static String maskedCard(String last4) {
        if (last4.isEmpty()) return "****";
        return "****" + (last4.length() <= 4 ? last4 : last4.substring(last4.length() - 4));
    }

    /** The money block's format: {@code $1,000.00}. */
    private static String currency(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(cents / 100.0);
    }

    /** The receipt screen's balance format: {@code $1234.56}, no grouping. */
    private static String plain(long cents) {
        return new DecimalFormat("$0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(cents / 100.0);
    }

    /** A labelled line wrapped on words to the width; continuation lines are indented under the text. */
    private static void wrapped(StringBuilder r, String label, String text) {
        String indent = String.format(Locale.US, "%" + label.length() + "s", "");
        int room = WIDTH - label.length();
        StringBuilder line = new StringBuilder();
        String prefix = label;
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) continue;
            while (word.length() > room) {                  // a single word longer than the room
                if (line.length() > 0) { r.append(prefix).append(line).append('\n'); line.setLength(0); prefix = indent; }
                r.append(prefix).append(word, 0, room).append('\n');
                word = word.substring(room);
                prefix = indent;
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > room) {
                r.append(prefix).append(line).append('\n');
                line.setLength(0);
                prefix = indent;
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) r.append(prefix).append(line).append('\n');
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
