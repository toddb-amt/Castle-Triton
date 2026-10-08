package castech.emvtxn.pos;

import castech.emvtxn.AmountBreakdown;

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
}
