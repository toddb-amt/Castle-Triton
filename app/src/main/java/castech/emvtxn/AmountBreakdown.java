package castech.emvtxn;

/**
 * The six numbers of a transaction, computed once and read everywhere (AMT-03, 6.2.13).
 *
 * <pre>
 *   withdrawal = roundUp(sale + tip, step)   when roundToStep, else sale + tip   — what the host is asked for
 *   cashBack   = withdrawal - sale - tip                                          — the change the customer receives
 *   fee        = the terminal's fee on the withdrawal (flat or percentage)
 *   total      = withdrawal + fee                                                 — the chip amount and the card charge
 * </pre>
 * Hence sale + tip + cashBack + fee == total. Whole cents throughout. The fee is not deducted
 * from the cash back (design review 2026-10-07).
 *
 * <p>6.2.13 builds every breakdown with tip = 0; walk-ups round (as since 6.2.8), register
 * sales do not (as today). 6.2.14 changes those two inputs and nothing else.
 */
public final class AmountBreakdown {

    public final long sale;
    public final long tip;
    public final long withdrawal;
    public final long cashBack;
    public final long fee;
    public final long total;

    private AmountBreakdown(long sale, long tip, long withdrawal, long fee) {
        this.sale = sale;
        this.tip = tip;
        this.withdrawal = withdrawal;
        this.cashBack = withdrawal - sale - tip;
        this.fee = fee;
        this.total = withdrawal + fee;
    }

    /**
     * @param saleCents     what the customer asked for (> 0)
     * @param tipCents      the tip (>= 0)
     * @param stepCents     the withdrawal step (the configured minimum); 0 or less disables rounding
     * @param roundToStep   true for a walk-up; false for a register sale in 6.2.13
     * @param useFlatFee    the terminal's fee configuration, with {@code flatFeeDollars} / {@code percentFee}
     */
    public static AmountBreakdown of(long saleCents, long tipCents, long stepCents, boolean roundToStep,
                                     boolean useFlatFee, double flatFeeDollars, double percentFee) {
        if (saleCents <= 0) throw new IllegalArgumentException("sale must be positive, got " + saleCents);
        if (tipCents < 0) throw new IllegalArgumentException("tip must not be negative, got " + tipCents);
        long saleAndTip = saleCents + tipCents;
        long withdrawal = roundToStep ? AmountRounding.roundUpToStepCents(saleAndTip, stepCents) : saleAndTip;
        long fee = Money.feeCents(withdrawal, useFlatFee, flatFeeDollars, percentFee);
        if (fee < 0) throw new IllegalArgumentException("fee must not be negative, got " + fee);
        return new AmountBreakdown(saleCents, tipCents, withdrawal, fee);
    }

    /** A balance inquiry moves no money. */
    public static AmountBreakdown balanceInquiry() {
        return new AmountBreakdown(0, 0, 0, 0);
    }

    /** The chip amount (EMV 9F02) as the plain cents string {@code GlobalPara.strAmount} expects. */
    public String chipAmountCents() {
        return Long.toString(total);
    }

    @Override
    public String toString() {
        return "sale=" + sale + " tip=" + tip + " withdrawal=" + withdrawal + " cashBack=" + cashBack
                + " fee=" + fee + " total=" + total;
    }
}
