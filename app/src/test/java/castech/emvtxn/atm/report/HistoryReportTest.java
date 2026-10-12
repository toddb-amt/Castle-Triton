package castech.emvtxn.atm.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;

/** Pins the on-screen Transaction History text (HIST-01, 6.2.16). */
public class HistoryReportTest {

    private static final long T0 = 1760000000000L;   // fixed instant; dates are formatted in the default zone

    private static TransactionLog row(long ts, int batch, String type, String result, String last4, String entry,
                                      long amt, long fee, int seq, String auth, String rc, boolean reversed) {
        TransactionLog l = new TransactionLog();
        l.setTimestamp(ts); l.setBatchId(batch); l.setTransactionType(type); l.setResult(result);
        l.setCardLastFour(last4); l.setEntryMode(entry); l.setAmountCents(amt); l.setFeeCents(fee);
        l.setSequenceNumber(seq); l.setAuthCode(auth); l.setResponseCode(rc); l.setReversed(reversed);
        return l;
    }
    private static TransactionLog approvedWd(long ts, int batch) {
        return row(ts, batch, "WITHDRAWAL", "APPROVED", "1111", "CONTACT", 100_00, 3_00, 42, "123456", "00", false);
    }
    private static String shortDate(long ts) { return new SimpleDateFormat("MM/dd HH:mm", Locale.US).format(new Date(ts)); }
    private static List<String> lines(String s) { return Arrays.asList(s.split("\n", -1)); }
    private static String render(int totalRows, List<TransactionLog> newestFirst) {
        return HistoryReport.render("TEST0001", T0, 4, totalRows, newestFirst);
    }

    // ---- stats line on the Admin card ----

    @Test
    public void statsLine_zeroRows_isTheBareCount() {
        assertEquals("Total Transactions: 0", HistoryReport.statsLine(0, 0));
    }

    @Test
    public void statsLine_namesTheOpenBatch() {
        assertEquals("Total Transactions: 312   (open batch 004)", HistoryReport.statsLine(312, 4));
    }

    // ---- the dialog text ----

    @Test
    public void everyLine_fitsThirtyTwoColumns() {
        List<TransactionLog> rows = Arrays.asList(
                row(T0, 4, "WITHDRAWAL", "DECLINED", "2222", "CONTACTLESS", 1000_00, 30_00, 43, "", "51", false),
                approvedWd(T0 - 60_000, 4),
                row(T0 - 120_000, 3, "BALANCE_INQUIRY", "APPROVED", "3333", "MSR", 0, 0, 41, "", "00", false));
        for (String l : lines(render(3, rows))) {
            assertTrue("line too long: [" + l + "] " + l.length(), l.length() <= HistoryReport.WIDTH);
        }
    }

    @Test
    public void header_namesTerminalAndCount() {
        String out = render(1, Collections.singletonList(approvedWd(T0, 4)));
        assertTrue(out, out.startsWith("      TRANSACTION HISTORY\n================================\n"));
        assertTrue(out, out.contains("TID TEST0001"));
        assertTrue(out, out.contains("Transactions on terminal: 1\n"));
        assertFalse(out, out.contains("Showing newest"));
    }

    @Test
    public void capped_saysHowManyAreShown() {
        List<TransactionLog> rows = new ArrayList<>();
        for (int i = 0; i < HistoryReport.MAX_ROWS; i++) rows.add(approvedWd(T0 - i * 1000L, 4));
        String out = render(312, rows);
        assertTrue(out, out.contains("Transactions on terminal: 312\n"));
        assertTrue(out, out.contains("Showing newest 200\n"));
    }

    @Test
    public void empty_saysSo() {
        String out = render(0, Collections.<TransactionLog>emptyList());
        assertTrue(out, out.contains("Transactions on terminal: 0\n"));
        assertTrue(out, out.contains("  (no transactions)\n"));
    }

    @Test
    public void approvedWithdrawal_hasTheAgreedThreeLines() {
        String out = render(1, Collections.singletonList(approvedWd(T0, 4)));
        assertTrue(out, out.contains(shortDate(T0) + "  WD  ****1111  C\n"));
        assertTrue(out, out.contains(" $100.00 fee $3.00      APPROVED\n"));
        assertTrue(out, out.contains(" #00042  auth 123456\n"));
    }

    @Test
    public void declined_showsTheResponseCode_andNoAuthLine() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "WITHDRAWAL", "DECLINED", "2222", "CONTACTLESS", 40_00, 3_00, 43, "", "51", false)));
        assertTrue(out, out.contains(shortDate(T0) + "  WD  ****2222  T\n"));
        assertTrue(out, out.contains(" $40.00 fee $3.00    DECLINED 51\n"));
        assertFalse(out, out.contains("auth"));
    }

    @Test
    public void reversedWithdrawal_isMarkedReversed() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "WITHDRAWAL", "APPROVED", "1111", "CONTACT", 100_00, 3_00, 42, "123456", "00", true)));
        assertTrue(out, out.contains(" $100.00 fee $3.00      REVERSED\n"));
    }

    @Test
    public void balanceInquiry_hasNoMoneyLine() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "BALANCE_INQUIRY", "APPROVED", "3333", "MSR", 0, 0, 41, "", "00", false)));
        assertTrue(out, out.contains(shortDate(T0) + "  BI  ****3333  S\n"));
        assertTrue(out, out.contains(" balance inquiry        APPROVED\n"));
        assertFalse(out, out.contains("fee"));
    }

    @Test
    public void reversalRow_isTypeRV() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "REVERSAL", "APPROVED", "1111", "CONTACT", 100_00, 3_00, 42, "", "00", false)));
        assertTrue(out, out.contains(shortDate(T0) + "  RV  ****1111  C\n"));
    }

    @Test
    public void rows_groupedByBatch_openBatchFirst_legacyRowsLast() {
        List<TransactionLog> rows = Arrays.asList(
                approvedWd(T0, 4),
                approvedWd(T0 - 3600_000L, 3),
                approvedWd(T0 - 7200_000L, 0));
        String out = render(3, rows);
        int open = out.indexOf("BATCH 004 (OPEN)\n");
        int closed = out.indexOf("BATCH 003\n");
        int legacy = out.indexOf("BATCH ---\n");
        assertTrue(out, open >= 0 && closed > open && legacy > closed);
        // exactly one header per batch
        assertEquals(out, out.indexOf("BATCH 004"), out.lastIndexOf("BATCH 004"));
    }

    @Test
    public void missingCardDigits_neverShowMoreThanFour() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "WITHDRAWAL", "APPROVED", "4111111111111111", "CONTACT", 20_00, 3_00, 1, "A1", "00", false)));
        assertTrue(out, out.contains("****1111  C\n"));
        assertFalse(out, out.contains("4111111111111111"));
    }

    @Test
    public void registerSale_showsClerkAndInvoice_afterTheAuthLine() {
        TransactionLog l = approvedWd(T0, 4);
        l.setClerkId("7"); l.setInvoiceNo("POS-12345");
        String out = render(1, Collections.singletonList(l));
        assertTrue(out, out.contains(" #00042  auth 123456\n clerk 7  inv POS-12345\n"));
    }

    @Test
    public void walkUp_hasNoClerkLine() {
        String out = render(1, Collections.singletonList(approvedWd(T0, 4)));
        assertFalse(out, out.contains("clerk"));
    }

    @Test
    public void terminalMadeDeclineCode_tooLongForTheLine_goesOnACodeLine() {
        // MSR_NA (swipe with no host path) and APP_SEL are the terminal's own codes; "DECLINED MSR_NA"
        // does not fit beside the money, so the code gets its own line instead of being clipped.
        String out = render(1, Collections.singletonList(
                row(T0, 4, "WITHDRAWAL", "DECLINED", null, "MSR", 10_00, 3_50, 0, "", "MSR_NA", false)));
        assertTrue(out, out.contains(" $10.00 fee $3.50       DECLINED\n code MSR_NA\n"));
        assertFalse(out, out.contains("MSR_\n"));
    }

    @Test
    public void hostDeclineCode_thatFits_staysInline() {
        String out = render(1, Collections.singletonList(
                row(T0, 4, "BALANCE_INQUIRY", "DECLINED", "8318", "CONTACT", 0, 0, 0, "", "76", false)));
        assertTrue(out, out.contains(" balance inquiry     DECLINED 76\n"));
        assertFalse(out, out.contains(" code "));
    }

    @Test
    public void rowWithoutCardData_saysNoCard() {
        String out = render(2, Arrays.asList(
                row(T0, 4, "WITHDRAWAL", "CANCELLED", null, "UNKNOWN", 0, 0, 0, "", "", false),
                row(T0 - 60_000, 4, "BALANCE_INQUIRY", "DECLINED", "", "MSR", 0, 0, 0, "", "MSR_NA", false)));
        assertTrue(out, out.contains(shortDate(T0) + "  WD  no card   ?\n"));
        assertTrue(out, out.contains(shortDate(T0 - 60_000) + "  BI  no card   S\n"));
        assertFalse(out, out.contains("****----"));
    }

    @Test
    public void footer_saysProcessorTotalsGovern() {
        String out = render(0, Collections.<TransactionLog>emptyList());
        assertTrue(out, out.endsWith("Terminal record - processor\ntotals govern\n"));
    }
}
