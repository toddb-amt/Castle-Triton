package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/** Spec section 6: unchanged when tip and cash back are 0; the split otherwise. 32-column printer. */
public class ReceiptMoneyBlockTest {

    private static AmountBreakdown plain()    { return AmountBreakdown.of(20_00, 0L, 20_00, false, true, 3.50, 0.0); }
    private static AmountBreakdown rounded()  { return AmountBreakdown.of(12_50, 0L, 20_00, true,  true, 3.50, 0.0); }
    private static AmountBreakdown tipped()   { return AmountBreakdown.of(10_00, 1_00, 20_00, true, true, 3.50, 0.0); }

    @Test
    public void noTipNoCashBack_isExactlyTodaysReceipt() {
        String expected =
                "Withdrawal Amount: $20.00\n" +
                "Service Fee:       $3.50\n" +
                "--------------------------------\n" +
                "Total Charged:     $23.50\n" +
                "\n";
        assertEquals(expected, ReceiptMoneyBlock.printed(plain()));
    }

    @Test
    public void aTip_showsTheFullSplit() {
        String expected =
                "Sale:              $10.00\n" +
                "Tip:               $1.00\n" +
                "Cash Back:         $9.00\n" +
                "Withdrawal:        $20.00\n" +
                "Service Fee:       $3.50\n" +
                "--------------------------------\n" +
                "Total Charged:     $23.50\n" +
                "\n";
        assertEquals(expected, ReceiptMoneyBlock.printed(tipped()));
    }

    @Test
    public void roundingOnly_showsNoTipLine() {
        String expected =
                "Sale:              $12.50\n" +
                "Cash Back:         $7.50\n" +
                "Withdrawal:        $20.00\n" +
                "Service Fee:       $3.50\n" +
                "--------------------------------\n" +
                "Total Charged:     $23.50\n" +
                "\n";
        assertEquals(expected, ReceiptMoneyBlock.printed(rounded()));
    }

    @Test
    public void lines_carryLabelsAndCents_forTheScreen() {
        List<ReceiptMoneyBlock.Line> l = ReceiptMoneyBlock.lines(tipped());
        assertEquals(7, l.size());
        assertEquals("Sale:", l.get(0).label);        assertEquals(10_00, l.get(0).cents);
        assertEquals("Tip:", l.get(1).label);         assertEquals(1_00, l.get(1).cents);
        assertEquals("Cash Back:", l.get(2).label);   assertEquals(9_00, l.get(2).cents);
        assertEquals("Withdrawal:", l.get(3).label);  assertEquals(20_00, l.get(3).cents);
        assertEquals("Service Fee:", l.get(4).label);
        assertEquals(true, l.get(5).rule);
        assertEquals("Total Charged:", l.get(6).label); assertEquals(23_50, l.get(6).cents);
        assertEquals(4, ReceiptMoneyBlock.lines(plain()).size());
    }

    @Test
    public void everyPrintedLine_fitsThirtyTwoColumns() {
        for (String line : ReceiptMoneyBlock.printed(AmountBreakdown.of(499_00, 1_00, 20_00, true, true, 3.50, 0.0)).split("\n")) {
            assertEquals(line, true, line.length() <= 32);
        }
    }
}
