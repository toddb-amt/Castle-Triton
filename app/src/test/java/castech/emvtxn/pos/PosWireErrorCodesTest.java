package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Wire contract for the error codes the proxy maps (docs/CASTLE_POS_INTEGRATION_SPEC.md §7).
 * Proxy team, 2026-10-09: a clean business refusal must not share a code with a malformed exchange,
 * because `invalid_request` maps to a retry-able terminal-error class on their side.
 */
public class PosWireErrorCodesTest {

    @Test
    public void overMaximum_hasItsOwnCode_distinctFromInvalidRequest() {
        assertEquals("amount_exceeds_maximum", PosWire.ERR_AMOUNT_EXCEEDS_MAXIMUM);
        assertNotEquals(PosWire.ERR_INVALID_REQUEST, PosWire.ERR_AMOUNT_EXCEEDS_MAXIMUM);
    }
}
