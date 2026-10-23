package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AMT-04 (6.2.20): the amount is typed from the cents, the way the Ingenico does it. Each digit
 * shifts the figure left; no decimal point is ever typed. The partner's own example: 2, 1, 3, 5, 2
 * reads $0.02, $0.21, $2.13, $21.35, $213.52.
 */
public class CentsEntryTest {

    private static CentsEntry typed(int... digits) {
        CentsEntry e = new CentsEntry();
        for (int d : digits) e.digit(d);
        return e;
    }

    @Test
    public void startsEmpty_atZero() {
        CentsEntry e = new CentsEntry();
        assertTrue(e.isEmpty());
        assertEquals(0, e.cents());
        assertEquals("$0.00", e.text());
    }

    @Test
    public void thePartnersExample_digitsShiftInFromTheCents() {
        CentsEntry e = new CentsEntry();
        e.digit(2); assertEquals("$0.02", e.text());
        e.digit(1); assertEquals("$0.21", e.text());
        e.digit(3); assertEquals("$2.13", e.text());
        e.digit(5); assertEquals("$21.35", e.text());
        e.digit(2); assertEquals("$213.52", e.text());
        assertEquals(213_52, e.cents());
        assertFalse(e.isEmpty());
    }

    @Test
    public void thousands_areGrouped() {
        assertEquals("$1,000.00", typed(1, 0, 0, 0, 0, 0).text());
    }

    @Test
    public void leadingZeros_changeNothing() {
        CentsEntry e = new CentsEntry();
        assertFalse(e.digit(0));
        assertFalse(e.digit(0));
        assertTrue(e.isEmpty());
        assertTrue(e.digit(5));
        assertEquals("$0.05", e.text());
    }

    @Test
    public void backspace_dropsTheLastDigitTyped() {
        CentsEntry e = typed(1, 2, 3, 4);
        assertEquals("$12.34", e.text());
        e.backspace(); assertEquals("$1.23", e.text());
        e.backspace(); assertEquals("$0.12", e.text());
        e.backspace(); assertEquals("$0.01", e.text());
        e.backspace(); assertEquals("$0.00", e.text());
        e.backspace(); assertEquals("$0.00", e.text());     // nothing left: stays empty
        assertTrue(e.isEmpty());
    }

    @Test
    public void clear_emptiesIt() {
        CentsEntry e = typed(5, 3, 5, 0);
        e.clear();
        assertTrue(e.isEmpty());
        assertEquals("$0.00", e.text());
    }

    @Test
    public void aSeventhDigit_isIgnored() {
        CentsEntry e = typed(9, 9, 9, 9, 9, 9);
        assertEquals("$9,999.99", e.text());
        assertFalse(e.digit(1));
        assertEquals("$9,999.99", e.text());
        assertEquals(CentsEntry.MAX_CENTS, e.cents());
    }

    @Test
    public void aDigitOutsideZeroToNine_isIgnored() {
        CentsEntry e = typed(4);
        assertFalse(e.digit(10));
        assertFalse(e.digit(-1));
        assertEquals("$0.04", e.text());
    }

    @Test
    public void set_fillsAPreset() {
        CentsEntry e = new CentsEntry();
        e.set(20_00);
        assertEquals("$20.00", e.text());
        assertEquals(20_00, e.cents());
    }

    @Test
    public void typingAfterAPreset_startsANewAmount_notADigitOnTheEnd() {
        // A mis-tapped $20 chip followed by typing 53.50 must give $53.50, not $2,053.50
        CentsEntry e = new CentsEntry();
        e.set(20_00);
        e.digit(5); assertEquals("$0.05", e.text());
        e.digit(3); e.digit(5); e.digit(0);
        assertEquals("$53.50", e.text());
    }

    @Test
    public void backspaceAfterAPreset_editsThePresetFigure() {
        CentsEntry e = new CentsEntry();
        e.set(100_00);
        e.backspace();
        assertEquals("$10.00", e.text());
        e.digit(5);                                         // and typing now continues from it
        assertEquals("$100.05", e.text());
    }

    @Test
    public void set_isClampedToTheRange() {
        CentsEntry e = new CentsEntry();
        e.set(-5);
        assertEquals("$0.00", e.text());
        e.set(CentsEntry.MAX_CENTS + 1);
        assertEquals("$9,999.99", e.text());
    }
}
