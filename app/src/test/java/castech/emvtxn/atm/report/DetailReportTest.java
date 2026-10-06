package castech.emvtxn.atm.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DetailReportTest {

    private static final long T0 = 1759330922000L;

    private static ReportRow wd(int seq, String last4, int em, int acct, long amt, long fee, String auth, String rrn,
                                String clerk, String inv) {
        return new ReportRow(seq, last4, em, acct, amt, fee, 0L, auth, rrn, clerk, inv, "WITHDRAWAL", "APPROVED", false);
    }
    private static ReportRow other(int seq, String type, String result) {
        return new ReportRow(seq, "0000", 1, 20, 0L, 0L, 0L, "", "", null, null, type, result, false);
    }
    private static DetailReport.Header header() {
        return new DetailReport.Header(3, T0 - 5 * 3600_000L, T0, "MP001194");
    }
    private static List<String> lines(String s) { return Arrays.asList(s.split("\n", -1)); }

    @Test
    public void everyLine_fitsThePrinter() {
        List<ReportRow> rows = Arrays.asList(
                wd(1, "2803", 1, 20, 5_00, 3_50, "123456", "673100000012", null, null),
                wd(2, "1234", 2, 20, 20_00, 3_50, "", "673100000013", "7", "POS-12345"));
        for (String l : lines(DetailReport.render(header(), rows))) {
            assertTrue("line too long: [" + l + "] " + l.length(), l.length() <= 32);
        }
    }

    @Test
    public void block_hasTheAgreedShape() {
        String out = DetailReport.render(header(), Arrays.asList(
                wd(1, "2803", 1, 20, 5_00, 3_50, "123456", "673100000012", null, null)));
        assertTrue(out, out.contains("         DETAIL REPORT\n"));
        assertTrue(out, out.contains("  Batch #: 003\n"));
        assertTrue(out, out.contains("TID MP001194"));
        assertTrue(out, out.contains("****2803  CWDR DB  C    #00001\n"));
        assertTrue(out, out.contains(" AUTH 123456   REF 673100000012\n"));
        assertTrue(out, out.contains(" AMT     $5.00   FEE     $3.50\n"));
        assertTrue(out, out.contains(" TIP     $0.00  TOTAL    $8.50\n"));
        assertFalse(out, out.contains("CLRK"));
        assertTrue(out, out.endsWith("         END OF REPORT\n\n\n"));
    }

    @Test
    public void posSale_showsClerkAndInvoice_andTapAndDashAuth() {
        String out = DetailReport.render(header(), Arrays.asList(
                wd(2, "1234", 2, 20, 20_00, 3_50, "", "673100000013", "7", "POS-12345")));
        assertTrue(out, out.contains("****1234  CWDR DB  T    #00002\n"));
        assertTrue(out, out.contains(" CLRK 7        INV POS-12345\n"));
        assertTrue(out, out.contains(" AUTH --       REF 673100000013\n"));
    }

    @Test
    public void summary_countsAndSumsOnlyApprovedWithdrawals() {
        List<ReportRow> rows = new ArrayList<>(Arrays.asList(
                wd(1, "2803", 1, 20, 5_00, 3_50, "1", "r1", null, null),
                wd(2, "2803", 1, 20, 5_00, 3_50, "1", "r2", null, null),
                wd(3, "2803", 1, 20, 10_00, 3_50, "1", "r3", null, null),
                wd(4, "2803", 1, 20, 20_00, 3_50, "1", "r4", null, null),
                other(5, "WITHDRAWAL", "DECLINED"), other(6, "WITHDRAWAL", "DECLINED"),
                other(7, "WITHDRAWAL", "CANCELLED"),
                other(8, "BALANCE_INQUIRY", "APPROVED"), other(9, "BALANCE_INQUIRY", "APPROVED"), other(10, "BALANCE_INQUIRY", "DECLINED")));
        DetailReport.Summary s = DetailReport.summarize(rows);
        assertEquals(4, s.withdrawals);
        assertEquals(40_00, s.amountCents);
        assertEquals(14_00, s.feeCents);
        assertEquals(0, s.tipCents);
        assertEquals(2, s.declined);
        assertEquals(1, s.cancelled);
        assertEquals(3, s.balanceInquiries);
        String out = DetailReport.render(header(), rows);
        assertTrue(out, out.contains("Withdrawals    4        $40.00\n"));
        assertTrue(out, out.contains(" Fees                   $14.00\n"));
        assertTrue(out, out.contains(" Tips                    $0.00\n"));
        assertTrue(out, out.contains("Total          4        $54.00\n"));
        assertTrue(out, out.contains("Declined       2   Cancelled   1\n"));
        assertTrue(out, out.contains("Bal Inquiries  3   Reversed    0\n"));
        assertEquals(4, out.split("CWDR", -1).length - 1);
    }

    @Test
    public void reversedApproval_isCountedNotListed() {
        ReportRow rev = new ReportRow(9, "4444", 1, 20, 50_00, 3_50, 0L, "A", "r9", null, null, "WITHDRAWAL", "APPROVED", true);
        List<ReportRow> rows = Arrays.asList(wd(1, "2803", 1, 20, 5_00, 3_50, "1", "r1", null, null), rev);
        DetailReport.Summary s = DetailReport.summarize(rows);
        assertEquals(1, s.withdrawals);
        assertEquals(5_00, s.amountCents);
        assertEquals(1, s.reversed);
        String out = DetailReport.render(header(), rows);
        assertFalse(out, out.contains("****4444"));
        assertTrue(out, out.contains("Reversed    1\n"));
    }

    /** Review #2: a withdrawal the processor reversed counts under Reversed whatever the terminal recorded. */
    @Test
    public void reversedDecline_countsUnderReversedNotDeclined() {
        ReportRow rev = new ReportRow(4, "4444", 1, 20, 50_00, 3_50, 0L, "", "", null, null, "WITHDRAWAL", "DECLINED", true);
        DetailReport.Summary s = DetailReport.summarize(Arrays.asList(rev));
        assertEquals(0, s.declined);
        assertEquals(1, s.reversed);
        assertEquals(0, s.withdrawals);
    }

    /** Review #6: a long clerk / invoice must never be clipped — it moves to two lines. */
    @Test
    public void longClerkOrInvoice_wrapsInsteadOfTruncating() {
        String out = DetailReport.render(header(), Arrays.asList(
                wd(1, "2803", 2, 20, 20_00, 3_50, "A1", "673100000013", "12345678", "INV-1234567890")));
        for (String l : lines(out)) assertTrue("[" + l + "]", l.length() <= 32);
        assertTrue(out, out.contains("INV-1234567890"));
        assertTrue(out, out.contains(" CLRK 12345678\n"));
        assertTrue(out, out.contains(" INV INV-1234567890\n"));
    }

    /** Review #7: three-digit counts print in full. */
    @Test
    public void threeDigitCounts_printInFull() {
        List<ReportRow> rows = new ArrayList<>();
        for (int i = 1; i <= 150; i++) rows.add(other(i, "WITHDRAWAL", "CANCELLED"));
        for (int i = 151; i <= 270; i++) rows.add(other(i, "WITHDRAWAL", "DECLINED"));
        for (int i = 271; i <= 380; i++) rows.add(other(i, "BALANCE_INQUIRY", "APPROVED"));
        String out = DetailReport.render(header(), rows);
        for (String l : lines(out)) assertTrue("[" + l + "]", l.length() <= 32);
        assertTrue(out, out.contains("Declined     120   Cancelled 150\n"));
        assertTrue(out, out.contains("Bal Inquiries110   Reversed    0\n"));
    }

    @Test
    public void emptyBatch_printsPlaceholderAndZeroTotals() {
        String out = DetailReport.render(header(), new ArrayList<ReportRow>());
        assertTrue(out, out.contains("  (no approved withdrawals)\n"));
        assertTrue(out, out.contains("Withdrawals    0         $0.00\n"));
        assertTrue(out, out.contains("Total          0         $0.00\n"));
        assertTrue(out, out.endsWith("         END OF REPORT\n\n\n"));
    }

    @Test
    public void largeAmounts_stayWithin32Columns() {
        String out = DetailReport.render(header(), Arrays.asList(
                wd(1, "2803", 3, 30, 12345_67, 99_99, "ABCDEF", "673100000012", "12345678", "INV-1234567890")));
        for (String l : lines(out)) assertTrue("[" + l + "]", l.length() <= 32);
        assertTrue(out, out.contains("CWDR CR  S"));
        assertTrue(out, out.contains(" AMT $12345.67   FEE    $99.99\n"));
        assertTrue(out, out.contains("SUMMARY  (CREDIT)\n"));
    }

    @Test
    public void footer_saysProcessorTotalsGovern() {
        String out = DetailReport.render(header(), new ArrayList<ReportRow>());
        assertTrue(out, out.contains("Terminal record - processor\ntotals govern\n"));
    }
}
