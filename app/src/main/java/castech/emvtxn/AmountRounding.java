package castech.emvtxn;

/**
 * Withdrawal-amount step rule: the configured minimum is also the step, and a custom
 * entry rounds UP to the next multiple of it (min $10: $12.50 → $20, $5 → $10, $20 → $20).
 *
 * <p>Pure, cents-based. The dollar overload converts through cents so binary-double
 * noise ($0.1 + $0.2) can never produce a wrong step.</p>
 */
public final class AmountRounding {

    private AmountRounding() {}

    /** @return {@code amountCents} rounded up to a multiple of {@code stepCents}; unchanged when either is not positive */
    public static long roundUpToStepCents(long amountCents, long stepCents) {
        if (stepCents <= 0 || amountCents <= 0) return amountCents;
        long steps = (amountCents + stepCents - 1) / stepCents;   // ceiling division
        return steps * stepCents;
    }

    /** Dollar convenience over {@link #roundUpToStepCents}. */
    public static double roundUpToStep(double amount, double step) {
        long amountCents = Math.round(amount * 100.0);
        long stepCents = Math.round(step * 100.0);
        return roundUpToStepCents(amountCents, stepCents) / 100.0;
    }
}
