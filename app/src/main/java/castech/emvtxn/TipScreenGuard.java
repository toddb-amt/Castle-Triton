package castech.emvtxn;

/**
 * One decision per showing of the tip screen, and only while it is shown (TIP-01, review 6.2.14 #1).
 *
 * <p>The ViewPager resumes every attached page, so the tip fragment's {@code onResume} can run while
 * the Admin page is current; leaving the page by any route other than a choice (navigation away, the
 * "no sale" belt path) must close the window, or a stale 30 s timer could later start a card read on
 * an old amount — the LIFE-02 class. Pure Java so the rule is unit-tested; the fragment owns one.
 */
public final class TipScreenGuard {

    private boolean open;

    /** The page just became current with a fresh quote. */
    public void onShown() { open = true; }

    /** The page was left without a decision (or by one). Nothing may act until the next onShown(). */
    public void onHidden() { open = false; }

    public boolean isOpen() { return open; }

    /** Claims the one decision. False when the window is closed: the caller must do nothing. */
    public boolean decide() {
        if (!open) return false;
        open = false;
        return true;
    }
}
