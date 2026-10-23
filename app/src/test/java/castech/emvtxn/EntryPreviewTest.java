package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * AMT-04 (6.2.20): the line under the amount field. With tips off it shows what the typed amount
 * becomes, live, so the rounding is visible before Continue. With tips on nothing is shown: the
 * withdrawal is only known after the tip screen (the owner's rule, 2026-10-22).
 */
public class EntryPreviewTest {

    private static String preview(long cents, boolean tipsOn, boolean preset) {
        return EntryPreview.text(cents, 10_00, tipsOn, preset, true, 3.50, 0.0);
    }

    @Test
    public void tipsOff_showsTheRoundedWithdrawalFeeAndTotal() {
        assertEquals("Withdrawal $60.00  ·  Fee $3.50  ·  Total $63.50", preview(53_50, false, false));
        assertEquals("Withdrawal $20.00  ·  Fee $3.50  ·  Total $23.50", preview(20_00, false, false));
    }

    @Test
    public void aPreset_isNotRounded() {
        assertEquals("Withdrawal $60.00  ·  Fee $3.50  ·  Total $63.50",
                EntryPreview.text(60_00, 25_00, false, true, true, 3.50, 0.0));
        assertEquals("Withdrawal $75.00  ·  Fee $3.50  ·  Total $78.50",
                EntryPreview.text(60_00, 25_00, false, false, true, 3.50, 0.0));
    }

    @Test
    public void percentageFee_isOnTheRoundedWithdrawal() {
        assertEquals("Withdrawal $60.00  ·  Fee $6.00  ·  Total $66.00",
                EntryPreview.text(53_50, 10_00, false, false, false, 0.0, 10.0));
    }

    @Test
    public void tipsOn_orNothingTyped_showsNothing() {
        assertNull(preview(53_50, true, false));
        assertNull(preview(0, false, false));
        assertNull(preview(0, true, false));
    }

    @Test
    public void aBadFeeConfiguration_showsNothingRatherThanThrowing() {
        assertNull(EntryPreview.text(53_50, 10_00, false, false, true, -3.50, 0.0));
    }
}
