package castech.emvtxn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Review 6.2.14 Important #1: the tip screen's idle timer must never act once the screen has been
 * left by ANY route — not only a choice or Cancel, but navigation away, the "no sale" belt path, or
 * a pager resume while another page is current. One decision per showing, and only while shown.
 */
public class TipScreenGuardTest {

    @Test
    public void notShown_cannotDecide() {
        TipScreenGuard g = new TipScreenGuard();
        assertFalse(g.isOpen());
        assertFalse(g.decide());
    }

    @Test
    public void shown_decidesExactlyOnce() {
        TipScreenGuard g = new TipScreenGuard();
        g.onShown();
        assertTrue(g.isOpen());
        assertTrue(g.decide());          // the choice
        assertFalse(g.isOpen());
        assertFalse(g.decide());         // a late timer or a double tap
    }

    @Test
    public void hidden_closesTheWindow_soALaterTimerDoesNothing() {
        TipScreenGuard g = new TipScreenGuard();
        g.onShown();
        g.onHidden();                    // navigated away without a decision (Admin, belt path, …)
        assertFalse(g.isOpen());
        assertFalse(g.decide());         // the pager resumed us while Admin is current: no action
    }

    @Test
    public void shownAgain_opensAFreshWindow() {
        TipScreenGuard g = new TipScreenGuard();
        g.onShown(); g.decide();
        g.onShown();
        assertTrue(g.isOpen());
        assertTrue(g.decide());
    }
}
