package castech.emvtxn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The tip choices for one transaction (TIP-01, 6.2.14; spec T3/T4/T6/T7). Pure Java, no Android.
 *
 * <p>Built from the NO-TIP breakdown the caller already has — a preset's exact amount, a custom
 * entry already rounded, or a register sale — so "No Tip" keeps exactly what the customer saw.
 * Every tipped option is {@code AmountBreakdown.of(sale, tip, step, roundToStep = true, fee…)}:
 * the tip changed the amount, so it rounds to the step (6.2.13 review C1 only protects a preset
 * on its own). The maximum applies to the withdrawal (T6).
 */
public final class TipQuote {

    public static final int[] PERCENTS = {10, 15, 20};
    public static final String TOO_SMALL = "TOO_SMALL";
    public static final String OVER_LIMIT = "OVER_LIMIT";

    public static final class Option {
        public final int percent;
        public final long tipCents;
        public final AmountBreakdown amounts;
        public final boolean enabled;
        /** Shown beneath a disabled button; "" when enabled. */
        public final String reasonIfDisabled;
        Option(int percent, long tipCents, AmountBreakdown amounts, boolean enabled, String reason) {
            this.percent = percent; this.tipCents = tipCents; this.amounts = amounts;
            this.enabled = enabled; this.reasonIfDisabled = reason;
        }
    }

    /** Result of validating a custom tip. */
    public static final class Custom {
        /** The breakdown to use, or null when {@link #failure} is set. */
        public final AmountBreakdown amounts;
        /** null, {@link #TOO_SMALL} or {@link #OVER_LIMIT}. */
        public final String failure;
        /** With OVER_LIMIT: the largest tip allowed (0 when none). */
        public final long maxTipCents;
        /** T7: tip larger than the sale — ask once before continuing. */
        public final boolean exceedsSale;
        Custom(AmountBreakdown amounts, String failure, long maxTipCents, boolean exceedsSale) {
            this.amounts = amounts; this.failure = failure; this.maxTipCents = maxTipCents; this.exceedsSale = exceedsSale;
        }
    }

    public final AmountBreakdown noTip;
    public final List<Option> options;
    /** The largest tip for which the withdrawal stays within the maximum; 0 when none. */
    public final long customLimitCents;
    /** True when nothing but No Tip could be chosen: the screen is not shown (T6). */
    public final boolean skipScreen;
    public final long maxWithdrawalCents;

    private final long stepCents;
    private final boolean useFlatFee;
    private final double flatFeeDollars;
    private final double percentFee;

    private TipQuote(AmountBreakdown noTip, List<Option> options, long customLimitCents, long maxWithdrawalCents,
                     long stepCents, boolean useFlatFee, double flatFeeDollars, double percentFee) {
        this.noTip = noTip;
        this.options = Collections.unmodifiableList(options);
        this.customLimitCents = customLimitCents;
        this.maxWithdrawalCents = maxWithdrawalCents;
        this.stepCents = stepCents;
        this.useFlatFee = useFlatFee;
        this.flatFeeDollars = flatFeeDollars;
        this.percentFee = percentFee;
        boolean any = false;
        for (Option o : options) any |= o.enabled;
        this.skipScreen = !any && customLimitCents <= 0;
    }

    /** Percent of the sale, rounded half-up to the cent (T3). */
    public static long percentOf(long saleCents, int percent) {
        return (saleCents * percent + 50) / 100;
    }

    public static TipQuote of(AmountBreakdown noTip, long stepCents, long maxWithdrawalCents,
                              boolean useFlatFee, double flatFeeDollars, double percentFee) {
        long sale = noTip.sale;
        long limit = sale <= 0 ? 0 : Math.max(0, largestReachableWithdrawal(maxWithdrawalCents, stepCents) - sale);
        List<Option> opts = new ArrayList<>(PERCENTS.length);
        for (int pct : PERCENTS) {
            long tip = sale <= 0 ? 0 : percentOf(sale, pct);
            AmountBreakdown a = null;
            boolean enabled = false;
            if (sale > 0 && tip > 0) {
                a = AmountBreakdown.of(sale, tip, stepCents, true, useFlatFee, flatFeeDollars, percentFee);
                enabled = a.withdrawal <= maxWithdrawalCents;
            }
            opts.add(new Option(pct, tip, a, enabled, enabled ? "" : "Over $" + Money.dollars(maxWithdrawalCents) + " limit"));
        }
        return new TipQuote(noTip, opts, limit, maxWithdrawalCents, stepCents, useFlatFee, flatFeeDollars, percentFee);
    }

    /** The one decision the two flows make: show the tip screen or go straight to the card (T1, T2, T6). */
    public static boolean offer(boolean tipsEnabled, boolean balanceInquiry, AmountBreakdown noTip, long stepCents,
                                long maxWithdrawalCents, boolean useFlatFee, double flatFeeDollars, double percentFee) {
        if (!tipsEnabled || balanceInquiry || noTip == null || noTip.sale <= 0) return false;
        return !of(noTip, stepCents, maxWithdrawalCents, useFlatFee, flatFeeDollars, percentFee).skipScreen;
    }

    /** Validates a custom tip (T7): ≥ 1 cent, withdrawal within the maximum; flags tip > sale. */
    public Custom custom(long tipCents) {
        if (tipCents < 1) return new Custom(null, TOO_SMALL, customLimitCents, false);
        // The limit is exactly "the largest tip whose rounded withdrawal fits" — so anything above it
        // is OVER_LIMIT, decided BEFORE any addition: a huge value typed on the keypad must never
        // reach sale + tip, where it would wrap negative and pass the maximum check (security review).
        if (tipCents > customLimitCents) return new Custom(null, OVER_LIMIT, customLimitCents, false);
        AmountBreakdown a = AmountBreakdown.of(noTip.sale, tipCents, stepCents, true, useFlatFee, flatFeeDollars, percentFee);
        if (a.withdrawal > maxWithdrawalCents) return new Custom(null, OVER_LIMIT, customLimitCents, false);
        return new Custom(a, null, customLimitCents, tipCents > noTip.sale);
    }

    /** roundUp(x, step) ≤ max ⇔ x ≤ the largest multiple of step that is ≤ max (max itself when no step). */
    private static long largestReachableWithdrawal(long maxCents, long stepCents) {
        if (stepCents <= 0) return maxCents;
        return (maxCents / stepCents) * stepCents;
    }
}
