package castech.emvtxn;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The money block of the customer receipt. Pure Java. RCPT-01 (6.2.16, owner decision 2026-10-12):
 * the receipt holds only the ATM transaction, so the block is always Withdrawal Amount / Service Fee /
 * rule / Total Charged, whatever the breakdown holds. Tip, sale and cash back stay in the journal,
 * the MyView push, the register reply and the Detail Report, but never print for the customer.
 * (6.2.14 printed a Sale / Tip / Cash Back split when a tip or rounding was involved.)
 * Labels are padded to 19 columns like today's receipt; the rule is 32 dashes.
 */
public final class ReceiptMoneyBlock {

    public static final class Line {
        public final String label;
        public final long cents;
        public final boolean rule;
        Line(String label, long cents, boolean rule) { this.label = label; this.cents = cents; this.rule = rule; }
    }

    private static final String RULE = "--------------------------------";

    private ReceiptMoneyBlock() {}

    public static List<Line> lines(AmountBreakdown a) {
        return lines(a.withdrawal, a.fee, a.total);
    }

    /** RCPT-02: the same block from a journal row's cents (withdrawal, fee, total). */
    public static List<Line> lines(long withdrawalCents, long feeCents, long totalCents) {
        List<Line> out = new ArrayList<>(4);
        out.add(new Line("Withdrawal Amount:", withdrawalCents, false));
        out.add(new Line("Service Fee:", feeCents, false));
        out.add(new Line("", 0, true));
        out.add(new Line("Total Charged:", totalCents, false));
        return out;
    }

    public static String printed(AmountBreakdown a) {
        return printed(a.withdrawal, a.fee, a.total);
    }

    /** RCPT-02: the printed block from a journal row's cents; identical text to the breakdown form. */
    public static String printed(long withdrawalCents, long feeCents, long totalCents) {
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        StringBuilder b = new StringBuilder();
        for (Line l : lines(withdrawalCents, feeCents, totalCents)) {
            if (l.rule) b.append(RULE).append('\n');
            else b.append(String.format(Locale.US, "%-19s", l.label)).append(money.format(l.cents / 100.0)).append('\n');
        }
        return b.append('\n').toString();
    }
}
