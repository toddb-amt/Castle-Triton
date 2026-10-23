package castech.emvtxn;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * AMT-04 (6.2.20): an amount typed from the cents up, the way the Ingenico terminals the
 * partner's merchants know do it. Each digit shifts the figure one place left and no decimal
 * point is ever typed: 2, 1, 3, 5, 2 reads $0.02, $0.21, $2.13, $21.35, $213.52. Pure Java,
 * pinned by CentsEntryTest; the amount screen owns one and renders {@link #text()}.
 *
 * <p>A preset chip calls {@link #set(long)}; the next digit then starts a fresh amount, so a
 * mis-tapped $20 followed by 5, 3, 5, 0 is $53.50 and not $2,053.50. Backspace edits whatever
 * is shown.
 */
public final class CentsEntry {

    /** Six digits, $9,999.99: far above any withdrawal maximum, it only bounds the display. */
    public static final int MAX_DIGITS = 6;
    public static final long MAX_CENTS = 999_999L;

    private long cents;
    private boolean replaceOnDigit;

    public long cents() { return cents; }

    public boolean isEmpty() { return cents == 0; }

    /** Appends a digit on the right. False when ignored: not 0-9, a leading zero, or already six digits. */
    public boolean digit(int d) {
        if (d < 0 || d > 9) return false;
        if (replaceOnDigit) { cents = 0; replaceOnDigit = false; }
        if (cents == 0 && d == 0) return false;
        long next = cents * 10 + d;
        if (next > MAX_CENTS) return false;
        cents = next;
        return true;
    }

    /** Drops the last digit typed; nothing happens on an empty field. */
    public void backspace() {
        replaceOnDigit = false;
        cents /= 10;
    }

    public void clear() {
        replaceOnDigit = false;
        cents = 0;
    }

    /** A preset: shown as is; typing afterwards starts over, backspace edits it. Clamped to the range. */
    public void set(long presetCents) {
        cents = Math.max(0, Math.min(MAX_CENTS, presetCents));
        replaceOnDigit = true;
    }

    /** "$213.52", thousands grouped, the picker's and the breakdown's currency format. */
    public String text() { return format(cents); }

    static String format(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(cents / 100.0);
    }
}
