package castech.emvtxn.pos;

import castech.emvtxn.AmountBreakdown;
import castech.emvtxn.Money;

/**
 * The breakdown for a register-driven sale (TIP-01 / T5, 6.2.14): rounded UP to the step like a
 * walk-up custom amount, the difference carried as cash back. 6.2.13 kept register sales exact;
 * this is the one place that changed. The register's surcharge is advisory (D7): the terminal's
 * fee configuration governs.
 */
public final class RegisterAmounts {
    private RegisterAmounts() {}

    public static AmountBreakdown of(long amountCents, long stepCents,
                                     boolean useFlatFee, double flatFeeDollars, double percentFee) {
        return AmountBreakdown.of(amountCents, 0L, stepCents, true, useFlatFee, flatFeeDollars, percentFee);
    }

    /**
     * T6: the maximum applies to the withdrawal. Rounding can carry a register sale past
     * {@code max_amount} when the maximum is not a multiple of the step (min $30, max $500, sale $490 →
     * $510), and a register amount can simply be too large. Returns the reason to refuse, or null.
     */
    public static String overMaximum(AmountBreakdown a, long maxWithdrawalCents, long stepCents) {
        if (a.withdrawal <= maxWithdrawalCents) return null;
        String rounded = a.withdrawal != a.sale
                ? " after rounding $" + Money.dollars(a.sale) + " up to the $" + Money.dollars(stepCents) + " step"
                : "";
        return "withdrawal $" + Money.dollars(a.withdrawal) + " exceeds the terminal's maximum $"
                + Money.dollars(maxWithdrawalCents) + rounded;
    }
}
