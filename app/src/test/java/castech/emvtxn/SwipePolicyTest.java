package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * MSR-01 (6.2.14): whether a swipe may proceed. The service code on Track 2 says whether the card
 * has a chip (first digit 2 or 6); a chip card is swiped only after a failed chip read in the same
 * transaction (technical fallback). Synthetic PANs only.
 */
public class SwipePolicyTest {

    private static final String STRIPE_ONLY = ";4111111111111111=2903101254300000?";   // service code 101
    private static final String CHIP_CARD   = ";4111111111111111=2903201254300000?";   // service code 201
    private static final String CHIP_CARD_6 = ";5500000000000004=2812601000000000?";   // service code 601

    @Test
    public void serviceCode_isTheThreeDigitsAfterTheExpiry() {
        assertEquals("101", SwipePolicy.serviceCode(STRIPE_ONLY));
        assertEquals("201", SwipePolicy.serviceCode(CHIP_CARD));
        assertEquals("601", SwipePolicy.serviceCode(CHIP_CARD_6));
        assertNull(SwipePolicy.serviceCode(";4111111111111111=29?"));     // too short
        assertNull(SwipePolicy.serviceCode("4111111111111111"));          // no separator
        assertNull(SwipePolicy.serviceCode(null));
    }

    @Test
    public void hasChip_isServiceCodeTwoOrSix() {
        assertTrue(SwipePolicy.hasChip("201"));
        assertTrue(SwipePolicy.hasChip("601"));
        assertFalse(SwipePolicy.hasChip("101"));
        assertFalse(SwipePolicy.hasChip("501"));
        assertFalse(SwipePolicy.hasChip("701"));
        assertFalse(SwipePolicy.hasChip(null));
    }

    @Test
    public void aStripeOnlyCard_isAcceptedAtOnce() {
        assertEquals(SwipePolicy.Decision.ACCEPT, SwipePolicy.decide(STRIPE_ONLY, true, false));
    }

    @Test
    public void aChipCardSwipedCold_isAskedToInsert() {
        assertEquals(SwipePolicy.Decision.INSERT_CHIP, SwipePolicy.decide(CHIP_CARD, true, false));
        assertEquals(SwipePolicy.Decision.INSERT_CHIP, SwipePolicy.decide(CHIP_CARD_6, true, false));
    }

    @Test
    public void aChipCardAfterAFailedChipRead_isAccepted() {
        assertEquals(SwipePolicy.Decision.ACCEPT, SwipePolicy.decide(CHIP_CARD, true, true));
    }

    @Test
    public void swipesOff_refusesEverything() {
        assertEquals(SwipePolicy.Decision.NOT_ACCEPTED, SwipePolicy.decide(STRIPE_ONLY, false, false));
        assertEquals(SwipePolicy.Decision.NOT_ACCEPTED, SwipePolicy.decide(CHIP_CARD, false, true));
    }

    @Test
    public void anUnreadableTrack_isUnreadable() {
        assertEquals(SwipePolicy.Decision.UNREADABLE, SwipePolicy.decide(null, true, false));
        assertEquals(SwipePolicy.Decision.UNREADABLE, SwipePolicy.decide("", true, false));
    }

    @Test
    public void anUnparseableServiceCode_isTreatedAsNoChip() {
        // a short or odd stripe is still a card the processor can judge; refusing it helps nobody
        assertEquals(SwipePolicy.Decision.ACCEPT, SwipePolicy.decide(";4111111111111111=29?", true, false));
    }

    @Test
    public void pan_andMaskedPan_forReceiptAndDisplay() {
        assertEquals("4111111111111111", SwipePolicy.pan(STRIPE_ONLY));
        assertEquals("411111******1111", SwipePolicy.maskedPan("4111111111111111"));
        assertEquals("1111", SwipePolicy.lastFour("4111111111111111"));
        assertEquals("", SwipePolicy.maskedPan(null));
    }
}
