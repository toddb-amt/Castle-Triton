package castech.emvtxn.atm.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;

/**
 * RCPT-02 (6.2.19): a customer receipt rebuilt from its journal row, printed from the Admin
 * Transaction History card. The text must read like the slip the customer got (same header, money
 * block, card, date, ids and footer as {@code Fragment_page_receipt}), marked DUPLICATE with the time
 * of the reprint, 32 columns. Identifiers here are synthetic.
 */
public class ReceiptReprintTest {

    private static final long T_TXN = 1760000000000L;        // fixed instant; formatted in the default zone
    private static final long T_NOW = T_TXN + 3_600_000L;    // reprinted an hour later

    private static String full(long ts) { return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(new Date(ts)); }
    private static String shortDate(long ts) { return new SimpleDateFormat("MM/dd HH:mm", Locale.US).format(new Date(ts)); }
    private static List<String> lines(String s) { return Arrays.asList(s.split("\n", -1)); }

    private static TransactionLog row(String type, String result, String last4, long amt, long fee, long total,
                                      String auth, String rc, String ref, String msg, boolean reversed) {
        TransactionLog l = new TransactionLog();
        l.setTransactionId("TXN1760000000000-42");
        l.setTimestamp(T_TXN); l.setBatchId(4); l.setSequenceNumber(42);
        l.setTransactionType(type); l.setResult(result);
        l.setCardLastFour(last4); l.setEntryMode("CONTACT");
        l.setAmountCents(amt); l.setFeeCents(fee); l.setTotalCents(total);
        l.setAuthCode(auth); l.setResponseCode(rc); l.setReferenceNumber(ref); l.setErrorMessage(msg);
        l.setReversed(reversed);
        return l;
    }
    private static TransactionLog approvedWd() {
        return row("WITHDRAWAL", "APPROVED", "1111", 100_00, 3_00, 103_00, "123456", "00", "000012345678", null, false);
    }
    private static TransactionLog declinedWd(String rc, String msg) {
        return row("WITHDRAWAL", "DECLINED", "2222", 1000_00, 30_00, 1030_00, "", rc, "", msg, false);
    }
    private static TransactionLog approvedBi() {
        TransactionLog l = row("BALANCE_INQUIRY", "APPROVED", "3333", 0, 0, 0, "654321", "00", "000087654321", null, false);
        l.setBalanceAccountCents(1234_56); l.setBalanceAvailableCents(1000_00);
        return l;
    }
    private static TransactionLog reversalRow() {
        return row("REVERSAL", "APPROVED", "1111", 100_00, 3_00, 103_00, "", "00", "", null, false);
    }

    // ---- the duplicate receipt ----

    @Test
    public void approvedWithdrawal_isTheOriginalReceiptMarkedDuplicate() {
        String expected =
                "\n" +
                "================================\n" +
                "         ATM RECEIPT            \n" +
                "================================\n" +
                "       *** DUPLICATE ***\n" +
                "Reprinted: " + full(T_NOW) + "\n" +
                "\n" +
                "   STATUS: APPROVED\n" +
                "\n" +
                "Withdrawal Amount: $100.00\n" +
                "Service Fee:       $3.00\n" +
                "--------------------------------\n" +
                "Total Charged:     $103.00\n" +
                "\n" +
                "Card: ****1111\n" +
                "Date/Time: " + full(T_TXN) + "\n" +
                "TransID: TXN1760000000000-42\n" +
                "Auth Code: 123456\n" +
                "Ref #: 000012345678\n" +
                "\n" +
                "================================\n" +
                "     Thank you for using our    \n" +
                "           ATM Service          \n" +
                "================================\n" +
                "\n\n\n";
        assertEquals(expected, ReceiptReprint.render(approvedWd(), T_NOW));
    }

    @Test
    public void everyLine_fitsThirtyTwoColumns() {
        List<TransactionLog> rows = Arrays.asList(approvedWd(),
                declinedWd("51", "Insufficient funds in the account for this withdrawal amount"),
                approvedBi(), row("WITHDRAWAL", "APPROVED", "4444", 100_00, 3_00, 103_00, "123456", "00", "", null, true));
        for (TransactionLog r : rows) {
            for (String l : lines(ReceiptReprint.render(r, T_NOW))) {
                assertTrue("line too long: [" + l + "] " + l.length(), l.length() <= ReceiptReprint.WIDTH);
            }
        }
    }

    @Test
    public void declined_printsTheReasonTheJournalKept() {
        String out = ReceiptReprint.render(declinedWd("51", "Insufficient funds"), T_NOW);
        assertTrue(out, out.contains("\n   STATUS: DECLINED\n   Reason: Insufficient funds\n\n"));
        assertFalse(out, out.contains("Auth Code"));
        assertFalse(out, out.contains("Ref #"));
        // the money block still prints, as on the original declined slip
        assertTrue(out, out.contains("Withdrawal Amount: $1,000.00\n"));
    }

    @Test
    public void declined_withoutAReason_printsTheResponseCode() {
        String out = ReceiptReprint.render(declinedWd("51", null), T_NOW);
        assertTrue(out, out.contains("\n   STATUS: DECLINED\n   Code: 51\n\n"));
    }

    @Test
    public void declined_withNeither_printsJustTheStatus() {
        String out = ReceiptReprint.render(declinedWd("", ""), T_NOW);
        assertTrue(out, out.contains("\n   STATUS: DECLINED\n\n"));
        assertFalse(out, out.contains("Reason:"));
        assertFalse(out, out.contains("Code:"));
    }

    @Test
    public void balanceInquiry_printsTheBalancesNotTheMoneyBlock() {
        String out = ReceiptReprint.render(approvedBi(), T_NOW);
        assertTrue(out, out.contains("================================\n      BALANCE INQUIRY           \n================================\n"));
        assertTrue(out, out.contains("\n   STATUS: APPROVED\n\nAccount Balance:   $1234.56\nAvailable Balance: $1000.00\n\nCard: ****3333\n"));
        assertFalse(out, out.contains("Withdrawal Amount"));
        assertFalse(out, out.contains("Total Charged"));
    }

    @Test
    public void reversedWithdrawal_printsTheReversalBlock() {
        TransactionLog l = approvedWd();
        l.setReversed(true);
        String out = ReceiptReprint.render(l, T_NOW);
        assertTrue(out, out.contains(
                "Ref #: 000012345678\n\n" +
                "--------------------------------\n" +
                "           REVERSAL             \n" +
                "--------------------------------\n" +
                "Status: SENT TO PROCESSOR\n" +
                "Card NOT charged.\n" +
                "\n" +
                "================================\n     Thank you for using our    \n"));
        assertFalse(ReceiptReprint.render(approvedWd(), T_NOW).contains("REVERSAL"));
    }

    @Test
    public void noCardOnTheRow_printsFourStars() {
        TransactionLog l = approvedWd();
        l.setCardLastFour("");
        assertTrue(ReceiptReprint.render(l, T_NOW).contains("\nCard: ****\n"));
        l.setCardLastFour(null);
        assertTrue(ReceiptReprint.render(l, T_NOW).contains("\nCard: ****\n"));
    }

    @Test
    public void authAndReference_printOnlyWhenPresent() {
        TransactionLog l = approvedWd();
        l.setAuthCode(""); l.setReferenceNumber(null);
        String out = ReceiptReprint.render(l, T_NOW);
        assertTrue(out, out.contains("TransID: TXN1760000000000-42\n\n================================\n"));
    }

    // ---- the picker: two lines per row, so the list reads row by row ----

    @Test
    public void pickerLineOne_approvedWithdrawal() {
        assertEquals(shortDate(T_TXN) + "   WD   ****1111   $100.00", ReceiptReprint.pickerLine1(approvedWd()));
    }

    @Test
    public void pickerLineOne_declinedWithdrawal_groupsThousands() {
        assertEquals(shortDate(T_TXN) + "   WD   ****2222   $1,000.00", ReceiptReprint.pickerLine1(declinedWd("51", null)));
    }

    @Test
    public void pickerLineOne_balanceInquiry_hasNoAmountCell() {
        // "BI" already says what it is; an amount cell would only make the row wrap on the terminal
        assertEquals(shortDate(T_TXN) + "   BI   ****3333", ReceiptReprint.pickerLine1(approvedBi()));
    }

    @Test
    public void pickerLineOne_noCard() {
        TransactionLog l = approvedWd();
        l.setCardLastFour(null);
        assertEquals(shortDate(T_TXN) + "   WD   no card   $100.00", ReceiptReprint.pickerLine1(l));
    }

    @Test
    public void pickerLineTwo_isTheOutcome() {
        assertEquals("APPROVED", ReceiptReprint.pickerLine2(approvedWd()));
        assertEquals("DECLINED 51", ReceiptReprint.pickerLine2(declinedWd("51", "Insufficient funds")));
        TransactionLog l = approvedWd();
        l.setReversed(true);
        assertEquals("REVERSED", ReceiptReprint.pickerLine2(l));
    }

    @Test
    public void printable_skipsReversalRows_keepsOrder_capsAtMaxRows() {
        List<TransactionLog> newestFirst = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            TransactionLog l = approvedWd();
            l.setTimestamp(T_TXN - i * 60_000L);
            l.setTransactionId("TXN-" + i);
            newestFirst.add(l);
            if (i % 3 == 0) {
                TransactionLog rv = reversalRow();
                rv.setTimestamp(T_TXN - i * 60_000L + 1);
                newestFirst.add(rv);
            }
        }
        List<TransactionLog> out = ReceiptReprint.printable(newestFirst);
        assertEquals(ReceiptReprint.MAX_ROWS, out.size());
        assertEquals("TXN-0", out.get(0).getTransactionId());
        assertEquals("TXN-19", out.get(19).getTransactionId());
        for (TransactionLog l : out) assertFalse(TransactionLog.TYPE_REVERSAL.equals(l.getTransactionType()));
    }

    @Test
    public void printable_skipsCancelledRows_keepsEverythingThatGotASlip() {
        TransactionLog cancelled = row("WITHDRAWAL", "CANCELLED", "", 0, 0, 0, "", "", "", null, false);
        TransactionLog timeout = row("WITHDRAWAL", "TIMEOUT", "5555", 20_00, 3_00, 23_00, "", "", "", "Host timeout", false);
        List<TransactionLog> out = ReceiptReprint.printable(
                Arrays.asList(cancelled, declinedWd("51", null), timeout, approvedWd()));
        assertEquals(3, out.size());
        assertEquals("DECLINED", out.get(0).getResult());
        assertEquals("TIMEOUT", out.get(1).getResult());     // its original slip read STATUS: DECLINED
        assertEquals("APPROVED", out.get(2).getResult());
    }

    @Test
    public void printable_emptyJournal_isEmpty() {
        assertTrue(ReceiptReprint.printable(new ArrayList<TransactionLog>()).isEmpty());
    }
}
