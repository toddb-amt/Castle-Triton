package castech.emvtxn;

import java.util.Locale;

/**
 * The one place dollars become cents (and back). Always ROUNDS — {@code (int)(2.95 * 100)}
 * is 294 in IEEE double, and that exact truncation sent a $2.95 surcharge to the host as
 * $2.94 while the receipt said $2.95. The receipt strings and the wire/chip cents must
 * always be derived from the same integer.
 */
public final class Money {

    private Money() {}

    /** Dollars → cents, rounded half-up (never truncated). */
    public static long toCents(double dollars) {
        return Math.round(dollars * 100.0);
    }

    /**
     * The surcharge for a withdrawal, in cents, from the terminal's fee configuration:
     * flat fee, or a percentage of the amount (rounded to the cent).
     */
    public static long feeCents(long amountCents, boolean useFlatFee, double flatFeeDollars, double percent) {
        if (useFlatFee) return toCents(flatFeeDollars);
        return Math.round(amountCents * percent / 100.0);
    }

    /** Cents → "12.34" (US locale, two decimals) for GlobalPara strings and receipts. */
    public static String dollars(long cents) {
        return String.format(Locale.US, "%.2f", cents / 100.0);
    }
}
