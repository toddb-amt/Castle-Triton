package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Decision D7 (2026-09-25): the surcharge is the TERMINAL's business. A POS sale carries the
 * cash amount; the fee comes from the terminal's fee configuration, exactly as for a walk-up
 * withdrawal. A {@code surcharge} the register sends is advisory only — never applied — and
 * the reply tells the register what was actually charged.
 */
public class PosSaleFeeTest {

    @Test
    public void terminalFlatFee_governs_whateverTheRegisterSent() {
        PosSaleFee none = PosSaleFee.resolve(1000L, 0L, true, 3.50, 0.0);
        assertEquals(350L, none.feeCents);
        assertEquals(1350L, none.totalCents);
        assertFalse(none.posSurchargeDiffers());

        PosSaleFee other = PosSaleFee.resolve(1000L, 500L, true, 3.50, 0.0);
        assertEquals(350L, other.feeCents);            // register said 500 — ignored
        assertEquals(1350L, other.totalCents);
        assertTrue(other.posSurchargeDiffers());

        PosSaleFee same = PosSaleFee.resolve(1000L, 350L, true, 3.50, 0.0);
        assertEquals(350L, same.feeCents);
        assertFalse(same.posSurchargeDiffers());
    }

    @Test
    public void percentageMode_usesTheTerminalsPercentage() {
        PosSaleFee f = PosSaleFee.resolve(6000L, 250L, false, 3.50, 2.99);
        assertEquals(179L, f.feeCents);                 // 2.99% of $60 = 1.794 → 1.79
        assertEquals(6179L, f.totalCents);
        assertTrue(f.posSurchargeDiffers());
    }

    @Test
    public void chipAmount_isAmountPlusFee_likeAWalkUp() {
        PosSaleFee f = PosSaleFee.resolve(2000L, 0L, true, 2.95, 0.0);
        assertEquals(295L, f.feeCents);                 // rounded, not 294
        assertEquals(2295L, f.totalCents);
        assertEquals("2295", f.chipAmountCents());
    }
}
