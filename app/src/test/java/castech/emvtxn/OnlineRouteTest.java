package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * TAP-01 (6.2.12). Which online path a card takes once the kernel says "go online".
 *
 * <p>Until 6.2.12 only an inserted chip had a live path to the host. A tapped card fell
 * into the last branch of the chain and was declined at the terminal with a made-up
 * "91 / Host service not available" — the processor was never contacted (seen on the
 * S1F4 PRO 2026-09-02 and at a POS site 2026-10-05).
 *
 * <p>Entry modes are written as the literal values GlobalDef uses (contact 0x01,
 * swipe 0x02, tap 0x03) so a renumbering there cannot silently re-route cards.
 */
public class OnlineRouteTest {

    private static final byte CONTACT = 0x01;
    private static final byte SWIPE = 0x02;
    private static final byte TAP = 0x03;

    @Test
    public void tappedCard_isSentToTheHost() {
        assertEquals(OnlineRoute.CONTACTLESS_HOST, OnlineRoute.of(TAP, false));
    }

    @Test
    public void tappedCard_isSentToTheHost_whateverTheQuickChipBoxSays() {
        assertEquals(OnlineRoute.CONTACTLESS_HOST, OnlineRoute.of(TAP, true));
    }

    @Test
    public void insertedChip_isSentToTheHost() {
        assertEquals(OnlineRoute.CONTACT_HOST, OnlineRoute.of(CONTACT, false));
    }

    @Test
    public void insertedChipWithQuickChipTicked_keepsTheSampleAppRoute() {
        assertEquals(OnlineRoute.QUICK_CHIP_CONTACT, OnlineRoute.of(CONTACT, true));
    }

    @Test
    public void swipedCard_hasNoHostPath() {
        assertEquals(OnlineRoute.NO_HOST_PATH, OnlineRoute.of(SWIPE, false));          // 2-arg form: swipes off
    }

    // ---- MSR-01 (6.2.14): a swipe goes to the host when swipes are enabled ---------------

    @Test
    public void swipe_goesToTheHost_whenSwipesAreEnabled() {
        assertEquals(OnlineRoute.MSR_HOST, OnlineRoute.of(SWIPE, false, true));
        assertEquals(OnlineRoute.MSR_HOST, OnlineRoute.of(SWIPE, true, true));           // quick chip is a contact thing
    }

    @Test
    public void swipe_hasNoHostPath_whenSwipesAreOff() {
        assertEquals(OnlineRoute.NO_HOST_PATH, OnlineRoute.of(SWIPE, false, false));
    }

    @Test
    public void theSwipeSwitch_changesNothingForTapOrChip() {
        assertEquals(OnlineRoute.CONTACTLESS_HOST, OnlineRoute.of(TAP, false, false));
        assertEquals(OnlineRoute.CONTACT_HOST, OnlineRoute.of(CONTACT, false, false));
        assertEquals(OnlineRoute.QUICK_CHIP_CONTACT, OnlineRoute.of(CONTACT, true, false));
        assertEquals(OnlineRoute.NO_HOST_PATH, OnlineRoute.of(SWIPE, true));
    }

    @Test
    public void unknownEntryMode_hasNoHostPath() {
        assertEquals(OnlineRoute.NO_HOST_PATH, OnlineRoute.of((byte) 0x00, false));
    }
}
