package castech.emvtxn;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The money block of the customer receipt (TIP-01, 6.2.14; spec section 6). Pure Java.
 * When tip and cash back are both 0 the block is byte-for-byte what 6.2.13 printed; otherwise
 * it shows the split. Labels are padded to 19 columns like today's receipt; the rule is 32 dashes.
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
        List<Line> out = new ArrayList<>(7);
        boolean split = a.tip > 0 || a.cashBack > 0;
        if (split) {
            out.add(new Line("Sale:", a.sale, false));
            if (a.tip > 0) out.add(new Line("Tip:", a.tip, false));
            out.add(new Line("Cash Back:", a.cashBack, false));
            out.add(new Line("Withdrawal:", a.withdrawal, false));
        } else {
            out.add(new Line("Withdrawal Amount:", a.withdrawal, false));
        }
        out.add(new Line("Service Fee:", a.fee, false));
        out.add(new Line("", 0, true));
        out.add(new Line("Total Charged:", a.total, false));
        return out;
    }

    public static String printed(AmountBreakdown a) {
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        StringBuilder b = new StringBuilder();
        for (Line l : lines(a)) {
            if (l.rule) b.append(RULE).append('\n');
            else b.append(String.format(Locale.US, "%-19s", l.label)).append(money.format(l.cents / 100.0)).append('\n');
        }
        return b.append('\n').toString();
    }
}
