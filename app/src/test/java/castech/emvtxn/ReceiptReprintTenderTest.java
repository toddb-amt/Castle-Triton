package castech.emvtxn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;
import castech.emvtxn.atm.report.ReceiptReprint;

/** TNDR-01: the Reprint Receipt picker is where a clerk looks the tender up after the screen has cleared. */
public class ReceiptReprintTenderTest {

    private static TransactionLog row(long sale, long tip, long cashBack, String result, String rc, String clerk) {
        TransactionLog l = new TransactionLog();
        l.setTransactionType(TransactionLog.TYPE_WITHDRAWAL);
        l.setResult(result); l.setResponseCode(rc);
        l.setSaleCents(sale); l.setTipCents(tip); l.setCashBackCents(cashBack);
        l.setAmountCents(sale + tip + cashBack); l.setFeeCents(3_50); l.setTotalCents(sale + tip + cashBack + 3_50);
        l.setClerkId(clerk); l.setCardLastFour("1111"); l.setTimestamp(1760000000000L);
        return l;
    }

    @Test
    public void lineTwo_carriesTenderAndChange_forAWalkUpWithATipOrChange() {
        assertEquals("APPROVED · Tender $55.00 · Change $1.50",
                ReceiptReprint.pickerLine2(row(53_50, 5_00, 1_50, "APPROVED", "00", null)));
        assertEquals("APPROVED · Tender $60.00 · Change $6.50",
                ReceiptReprint.pickerLine2(row(53_50, 0, 6_50, "APPROVED", "00", null)));
    }

    @Test
    public void lineTwo_isJustTheOutcome_otherwise() {
        assertEquals("APPROVED", ReceiptReprint.pickerLine2(row(60_00, 0, 0, "APPROVED", "00", null)));       // plain preset
        assertEquals("APPROVED", ReceiptReprint.pickerLine2(row(53_50, 5_00, 1_50, "APPROVED", "00", "12")));  // register sale
        assertEquals("DECLINED 51", ReceiptReprint.pickerLine2(row(53_50, 5_00, 1_50, "DECLINED", "51", null)));
    }
}
