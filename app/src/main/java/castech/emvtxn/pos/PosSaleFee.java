package castech.emvtxn.pos;

import castech.emvtxn.Money;

/**
 * Decision D7 (2026-09-25): the surcharge is the TERMINAL's business. A POS sale carries the
 * cash amount only; the fee comes from the terminal's fee configuration, exactly as for a
 * walk-up withdrawal, and the chip amount is amount + fee like a walk-up. A {@code surcharge}
 * the register sends is advisory — never applied — and the reply reports what was charged.
 */
public final class PosSaleFee {

    public final long amountCents;
    public final long feeCents;
    public final long totalCents;
    /** What the register sent (0 when absent). Informational only. */
    public final long posSurchargeCents;

    private PosSaleFee(long amountCents, long feeCents, long posSurchargeCents) {
        this.amountCents = amountCents;
        this.feeCents = feeCents;
        this.totalCents = amountCents + feeCents;
        this.posSurchargeCents = posSurchargeCents;
    }

    public static PosSaleFee resolve(long amountCents, long posSurchargeCents,
                                     boolean useFlatFee, double flatFeeDollars, double percent) {
        long fee = Money.feeCents(amountCents, useFlatFee, flatFeeDollars, percent);
        return new PosSaleFee(amountCents, fee, posSurchargeCents);
    }

    /** True when the register sent a surcharge that is not what the terminal charges (worth a log line). */
    public boolean posSurchargeDiffers() {
        return posSurchargeCents > 0 && posSurchargeCents != feeCents;
    }

    /** The chip amount (9F02) as the cents string GlobalPara.strAmount expects: the total. */
    public String chipAmountCents() {
        return Long.toString(totalCents);
    }
}
