package castech.emvtxn;

/**
 * AMT-04 (6.2.20): the line under the amount field. With tips off it shows what the typed amount
 * becomes, live, so the step rounding (AMT-01) is visible before Continue without a toast. With
 * tips on it shows nothing: the withdrawal is only known after the tip screen (owner's rule,
 * 2026-10-22), and the card screen shows it before the card goes in. Pinned by EntryPreviewTest.
 */
public final class EntryPreview {

    private EntryPreview() {}

    /** The text, or null for nothing to show. Never throws: a bad fee configuration shows nothing. */
    public static String text(long saleCents, long stepCents, boolean tipsEnabled, boolean preset,
                              boolean useFlatFee, double flatFeeDollars, double percentFee) {
        if (tipsEnabled || saleCents <= 0) return null;
        try {
            AmountBreakdown a = AmountBreakdown.of(saleCents, 0L, stepCents, !preset, useFlatFee, flatFeeDollars, percentFee);
            return "Withdrawal " + CentsEntry.format(a.withdrawal)
                    + "  ·  Fee " + CentsEntry.format(a.fee)
                    + "  ·  Total " + CentsEntry.format(a.total);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }
}
