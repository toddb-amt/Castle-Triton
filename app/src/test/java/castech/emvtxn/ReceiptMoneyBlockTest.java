package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * RCPT-01 (6.2.16, owner decision 2026-10-12): the customer receipt holds only the ATM transaction.
 * Tip, sale and cash back never print, whatever the breakdown holds; the block is always
 * Withdrawal Amount / Service Fee / rule / Total Charged, 32-column printer.
 * (6.2.14 printed a Sale / Tip / Cash Back split when a tip or rounding was involved.)
 */
public class ReceiptMoneyBlockTest {

    private static AmountBreakdown plain()    { return AmountBreakdown.of(20_00, 0L, 20_00, false, true, 3.50, 0.0); }
    private static AmountBreakdown rounded()  { return AmountBreakdown.of(12_50, 0L, 20_00, true,  true, 3.50, 0.0); }
    private static AmountBreakdown tipped()   { return AmountBreakdown.of(10_00, 1_00, 20_00, true, true, 3.50, 0.0); }

    private static final String ATM_BLOCK =
            "Withdrawal Amount: $20.00\n" +
            "Service Fee:       $3.50\n" +
            "--------------------------------\n" +
            "Total Charged:     $23.50\n" +
            "\n";

    @Test
    public void noTipNoCashBack_isExactlyTodaysReceipt() {
        assertEquals(ATM_BLOCK, ReceiptMoneyBlock.printed(plain()));
    }

    @Test
    public void aTip_printsOnlyTheAtmTransaction() {
        assertEquals(ATM_BLOCK, ReceiptMoneyBlock.printed(tipped()));
    }

    @Test
    public void roundingOnly_printsOnlyTheAtmTransaction() {
        assertEquals(ATM_BLOCK, ReceiptMoneyBlock.printed(rounded()));
    }

    @Test
    public void printedBlock_neverMentionsSaleTipOrCashBack() {
        for (AmountBreakdown a : new AmountBreakdown[] { plain(), rounded(), tipped() }) {
            String out = ReceiptMoneyBlock.printed(a);
            assertFalse(out, out.contains("Sale"));
            assertFalse(out, out.contains("Tip"));
            assertFalse(out, out.contains("Cash Back"));
        }
    }

    @Test
    public void lines_areAlwaysTheFourAtmLines_forTheScreen() {
        for (AmountBreakdown a : new AmountBreakdown[] { plain(), rounded(), tipped() }) {
            List<ReceiptMoneyBlock.Line> l = ReceiptMoneyBlock.lines(a);
            assertEquals(4, l.size());
            assertEquals("Withdrawal Amount:", l.get(0).label); assertEquals(20_00, l.get(0).cents);
            assertEquals("Service Fee:", l.get(1).label);       assertEquals(3_50, l.get(1).cents);
            assertTrue(l.get(2).rule);
            assertEquals("Total Charged:", l.get(3).label);     assertEquals(23_50, l.get(3).cents);
        }
    }

    @Test
    public void everyPrintedLine_fitsThirtyTwoColumns() {
        for (String line : ReceiptMoneyBlock.printed(AmountBreakdown.of(499_00, 1_00, 20_00, true, true, 3.50, 0.0)).split("\n")) {
            assertEquals(line, true, line.length() <= 32);
        }
    }

    /** RCPT-02 (6.2.19): the duplicate receipt rebuilds the block from journal cents, same text. */
    @Test
    public void centsOverload_printsTheSameBlockAsTheBreakdown() {
        assertEquals(ReceiptMoneyBlock.printed(plain()), ReceiptMoneyBlock.printed(20_00, 3_50, 23_50));
        assertEquals(ATM_BLOCK, ReceiptMoneyBlock.printed(20_00, 3_50, 23_50));
    }
}
