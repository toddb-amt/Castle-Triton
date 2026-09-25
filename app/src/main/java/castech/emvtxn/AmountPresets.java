package castech.emvtxn;

/**
 * The preset withdrawal buttons on the amount screen (6.2.8): $10, $20, $40, $60, $100,
 * $200 — ascending, six buttons. Single source of truth: the layout's six buttons are
 * labelled and wired from this list, in this order.
 */
public final class AmountPresets {

    private AmountPresets() {}

    /** Preset amounts in cents, ascending. */
    public static final long[] PRESET_CENTS = {10_00L, 20_00L, 40_00L, 60_00L, 100_00L, 200_00L};

    /**
     * Whether a preset should be offered under the configured limits. A preset outside
     * them is greyed out instead of producing an "Amount too low/high" dialog when tapped.
     * A non-positive limit means "no limit" for this check.
     */
    public static boolean isOffered(long presetCents, long minCents, long maxCents) {
        if (minCents > 0 && presetCents < minCents) return false;
        if (maxCents > 0 && presetCents > maxCents) return false;
        return true;
    }
}
