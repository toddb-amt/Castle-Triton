# Detail Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A printed 32-column Detail Report (ellipsis ▸ Detail Report) listing every approved withdrawal in the current terminal-owned batch, with a summary, fed by a per-transaction journal that the app does not yet write.

**Architecture:** Three units. (1) `atm/report/DetailReport` — pure formatter from plain rows to text, fully unit-tested. (2) `atm/TransactionLogManager` schema v2 — the existing (unused) SQLite log gains the report columns and a `batches` table; `atm/TransactionJournal` records one row per finished transaction from `GlobalPara` at the single completion point in `MainActivity`'s transaction thread, and marks a row reversed when the drain clears a reversal. (3) Glue in `MainActivity`: menu item, confirm dialog, build-and-print (or dialog when out of paper), batch close hook, batch number on the Close Batch receipt.

**Tech Stack:** Java 8, Android SDK 31 (min 24), SQLite via `SQLiteOpenHelper`, JUnit 4 (JVM; `unitTests.returnDefaultValues = true`), Castle `CTOS_Printer.printf`. Build/test: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew -q testMkskDebugUnitTest --tests "<class>"`. Suite baseline: 241 tests, 3 pre-existing `TEST-01` failures (`EmvTagEnhancerTest` ×2, `HyosungProtocolTest.testProcessorConfigDns`).

**Spec:** `docs/superpowers/specs/2026-10-01-detail-report-design.md`

## Global Constraints

- Printer width **32 columns**; every rendered line ≤ 32 chars; `Money.dollars(cents)` for every figure (no commas).
- Detail lines: **approved withdrawals only**; declines / cancels / balance inquiries / reversed rows appear only as counts.
- Batch: terminal-owned counter starting at **001**, +1 per **successful** Close Batch (Type 87 reset accepted). Report = rows of the open batch.
- One fee column labelled **FEE**; **TIP** column and **Tips** summary line always present ($0.00 for now).
- Access like Host Totals: ellipsis entry + confirm dialog, **no PIN**.
- No PAN beyond last four anywhere (journal stores last four only; `LogMask` rules apply to logs).
- Branch `release/v6.2.11` (off `release/v6.2.10`), version **6.2.11 / versionCode 73**, PR #7, one commit per task, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` on every commit.
- Preserve CRLF where a file already uses it (`fragment_page_admin_atm.xml`, `Fragment_page_amount_selection.java` are CRLF; `MainActivity.java`, `GlobalPara.java`, the admin fragment and `atm/*` are LF). Edit with byte-preserving tools.

## Review Focus

1. **A batch with 0 approved withdrawals** (fresh terminal, or right after a close) must print a complete report with "(no approved withdrawals)" and zero totals, not crash or print only a header. → `DetailReportTest.emptyBatch_printsPlaceholderAndZeroTotals` (Task 1).
2. **Amounts ≥ $1,000.00** must not push a line past 32 columns or misalign the money columns. → `DetailReportTest.largeAmounts_stayWithin32Columns` (Task 1).
3. **A reversed approval** (approved, then a reversal for that sequence accepted) must leave the detail lines and the money totals, and show under `Reversed`. → `DetailReportTest.reversedApproval_isCountedNotListed` (Task 1) and `TransactionJournal.markReversed` (Task 4).
4. **Close Batch fails or errors** must not open a new batch; the next report still shows the same batch. → `TransactionLogManager.closeCurrentBatch` is only called from the `response.isSuccess()` branch (Task 5), and `BatchMathTest.nextBatchId_onlyAdvancesOnClose` (Task 2).
5. **Journal write must never break a transaction.** Any exception in `TransactionJournal.record` is caught and logged; the receipt path continues. → `TransactionJournal` wraps everything in `try/catch (Throwable)` (Task 4); verified on device by forcing a null manager.

---

### Task 1: Pure report formatter

**Files:**
- Create: `app/src/main/java/castech/emvtxn/atm/report/ReportRow.java`
- Create: `app/src/main/java/castech/emvtxn/atm/report/DetailReport.java`
- Test: `app/src/test/java/castech/emvtxn/atm/report/DetailReportTest.java`

**Interfaces:**
- Consumes: `castech.emvtxn.Money.dollars(long cents)` (exists).
- Produces:
  - `ReportRow` — plain final fields: `int sequence; String last4; int entryMode /*1 CT,2 CL,3 MSR*/; int accountType /*10 sav,20 chk,30 credit*/; long amountCents; long feeCents; long tipCents; String authCode; String rrn; String clerkId; String invoiceNo; String type /*WITHDRAWAL|BALANCE_INQUIRY*/; String result /*APPROVED|DECLINED|CANCELLED*/; boolean reversed;` with the constructor in that order.
  - `DetailReport.Header(int batchId, long openedAtMillis, long printedAtMillis, String terminalId)`.
  - `static String DetailReport.render(Header h, List<ReportRow> rows)` → the full text, `\n`-separated, every line ≤ 32 chars, ending with `"\n\n\n"`.
  - `static DetailReport.Summary DetailReport.summarize(List<ReportRow> rows)` with public final `int withdrawals, declined, cancelled, balanceInquiries, reversed; long amountCents, feeCents, tipCents;` (used by Task 5 to snapshot a batch at close).

- [ ] **Step 1: Write the failing tests**

```java
package castech.emvtxn.atm.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DetailReportTest {

    private static final long T0 = 1759330922000L;   // 2026-10-01 14:22:02 UTC-ish; exact text asserted via format below

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
        assertFalse(out, out.contains("CLRK"));                    // walk-up: no clerk line
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
        assertTrue(out, out.contains("Declined       2    Cancelled 1\n"));
        assertTrue(out, out.contains("Bal Inquiries  3    Reversed  0\n"));
        assertEquals(4, out.split("CWDR", -1).length - 1);   // exactly four detail blocks
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
        assertTrue(out, out.contains("Reversed  1\n"));
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew -q testMkskDebugUnitTest --tests "castech.emvtxn.atm.report.DetailReportTest" 2>&1 | grep -E "cannot find symbol|error:" | head -3`
Expected: compile errors `cannot find symbol ... ReportRow` / `DetailReport`.

- [ ] **Step 3: Implement**

`ReportRow.java`:
```java
package castech.emvtxn.atm.report;

/** One journaled transaction, as the report needs it. Plain data, no Android. */
public final class ReportRow {
    public final int sequence;
    public final String last4;
    public final int entryMode;      // 1 contact, 2 contactless, 3 MSR, 0 unknown
    public final int accountType;    // 10 savings, 20 checking, 30 credit
    public final long amountCents;
    public final long feeCents;
    public final long tipCents;
    public final String authCode;
    public final String rrn;
    public final String clerkId;     // null when not a register sale
    public final String invoiceNo;   // null when not a register sale
    public final String type;        // WITHDRAWAL | BALANCE_INQUIRY
    public final String result;      // APPROVED | DECLINED | CANCELLED
    public final boolean reversed;

    public ReportRow(int sequence, String last4, int entryMode, int accountType, long amountCents, long feeCents,
                     long tipCents, String authCode, String rrn, String clerkId, String invoiceNo,
                     String type, String result, boolean reversed) {
        this.sequence = sequence; this.last4 = last4; this.entryMode = entryMode; this.accountType = accountType;
        this.amountCents = amountCents; this.feeCents = feeCents; this.tipCents = tipCents;
        this.authCode = authCode; this.rrn = rrn; this.clerkId = clerkId; this.invoiceNo = invoiceNo;
        this.type = type; this.result = result; this.reversed = reversed;
    }

    public boolean isApprovedWithdrawal() {
        return "WITHDRAWAL".equals(type) && "APPROVED".equals(result) && !reversed;
    }
}
```

`DetailReport.java`:
```java
package castech.emvtxn.atm.report;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import castech.emvtxn.Money;

/**
 * The Detail Report text for the 32-column receipt printer. Pure Java; the layout is
 * pinned by DetailReportTest. See docs/superpowers/specs/2026-10-01-detail-report-design.md.
 */
public final class DetailReport {

    public static final int WIDTH = 32;
    private static final String RULE = "================================";
    private static final String THIN = "--------------------------------";

    private DetailReport() {}

    public static final class Header {
        public final int batchId; public final long openedAtMillis; public final long printedAtMillis; public final String terminalId;
        public Header(int batchId, long openedAtMillis, long printedAtMillis, String terminalId) {
            this.batchId = batchId; this.openedAtMillis = openedAtMillis; this.printedAtMillis = printedAtMillis;
            this.terminalId = terminalId == null ? "" : terminalId;
        }
    }

    public static final class Summary {
        public int withdrawals, declined, cancelled, balanceInquiries, reversed;
        public long amountCents, feeCents, tipCents;
        // per card type, for the optional second group
        public int creditWithdrawals; public long creditAmountCents, creditFeeCents, creditTipCents;
    }

    public static Summary summarize(List<ReportRow> rows) {
        Summary s = new Summary();
        for (ReportRow r : rows) {
            if (r.reversed && "WITHDRAWAL".equals(r.type) && "APPROVED".equals(r.result)) { s.reversed++; continue; }
            if ("BALANCE_INQUIRY".equals(r.type)) { s.balanceInquiries++; continue; }
            if (!"WITHDRAWAL".equals(r.type)) continue;
            if ("APPROVED".equals(r.result)) {
                s.withdrawals++; s.amountCents += r.amountCents; s.feeCents += r.feeCents; s.tipCents += r.tipCents;
                if (r.accountType == 30) { s.creditWithdrawals++; s.creditAmountCents += r.amountCents; s.creditFeeCents += r.feeCents; s.creditTipCents += r.tipCents; }
            } else if ("CANCELLED".equals(r.result)) {
                s.cancelled++;
            } else {
                s.declined++;
            }
        }
        return s;
    }

    public static String render(Header h, List<ReportRow> rows) {
        SimpleDateFormat full = new SimpleDateFormat("MM/dd/yy HH:mm:ss", Locale.US);
        SimpleDateFormat shortF = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder();
        line(b, center("DETAIL REPORT"));
        line(b, RULE);
        line(b, pair(full.format(new Date(h.printedAtMillis)), "Batch #: " + String.format(Locale.US, "%03d", h.batchId)));
        line(b, pair("TID " + cut(h.terminalId, 8), "Opened " + shortF.format(new Date(h.openedAtMillis))));
        line(b, THIN);

        List<ReportRow> approved = new ArrayList<>();
        for (ReportRow r : rows) if (r.isApprovedWithdrawal()) approved.add(r);
        if (approved.isEmpty()) {
            line(b, "  (no approved withdrawals)");
        } else {
            for (int i = 0; i < approved.size(); i++) {
                ReportRow r = approved.get(i);
                line(b, String.format(Locale.US, "%-8s  CWDR %s  %s    #%05d",
                        "****" + cut(nz(r.last4), 4), cardType(r.accountType), entry(r.entryMode), r.sequence));
                if (notBlank(r.clerkId) || notBlank(r.invoiceNo)) {
                    line(b, cut(String.format(Locale.US, " CLRK %-8s INV %s", nz(r.clerkId), nz(r.invoiceNo)), WIDTH));
                }
                line(b, cut(String.format(Locale.US, " AUTH %-6s   REF %s", notBlank(r.authCode) ? cut(r.authCode, 6) : "--", nz(r.rrn)), WIDTH));
                line(b, " AMT " + right(money(r.amountCents), 9) + "   FEE " + right(money(r.feeCents), 9));
                line(b, " TIP " + right(money(r.tipCents), 9) + "  TOTAL " + right(money(r.amountCents + r.feeCents + r.tipCents), 9));
                if (i < approved.size() - 1) line(b, THIN);
            }
        }
        line(b, RULE);

        Summary s = summarize(rows);
        boolean twoGroups = s.creditWithdrawals > 0 && s.creditWithdrawals < s.withdrawals;
        if (s.creditWithdrawals > 0 && s.creditWithdrawals == s.withdrawals) {
            group(b, "CREDIT", s.withdrawals, s.amountCents, s.feeCents, s.tipCents);
        } else {
            group(b, "DEBIT", s.withdrawals - s.creditWithdrawals, s.amountCents - s.creditAmountCents,
                    s.feeCents - s.creditFeeCents, s.tipCents - s.creditTipCents);
            if (twoGroups) {
                line(b, THIN);
                group(b, "CREDIT", s.creditWithdrawals, s.creditAmountCents, s.creditFeeCents, s.creditTipCents);
                line(b, THIN);
                line(b, "GRAND TOTALS");
                line(b, sum("Withdrawals", s.withdrawals, s.amountCents));
                line(b, sum(" Fees", -1, s.feeCents));
                line(b, sum(" Tips", -1, s.tipCents));
                line(b, sum("Grand Total", s.withdrawals, s.amountCents + s.feeCents + s.tipCents));
            }
        }
        line(b, THIN);
        line(b, String.format(Locale.US, "%-10s%4d    %-10s%d", "Declined", s.declined, "Cancelled", s.cancelled));
        line(b, String.format(Locale.US, "%-10s%4d    %-10s%d", "Bal Inquir", s.balanceInquiries, "Reversed ", s.reversed)
                .replace("Bal Inquir", "Bal Inquiries").replace("Bal Inquiries   ", "Bal Inquiries  "));
        line(b, RULE);
        line(b, "Terminal record - processor");
        line(b, "totals govern");
        line(b, center("END OF REPORT"));
        b.append("\n\n");
        return b.toString();
    }

    private static void group(StringBuilder b, String name, int count, long amt, long fee, long tip) {
        line(b, "SUMMARY  (" + name + ")");
        line(b, sum("Withdrawals", count, amt));
        line(b, sum(" Fees", -1, fee));
        line(b, sum(" Tips", -1, tip));
        line(b, sum("Total", count, amt + fee + tip));
    }

    /** label (12) + count (4, or blank) + amount right-aligned in 16 = 32. */
    private static String sum(String label, int count, long cents) {
        String c = count < 0 ? "    " : String.format(Locale.US, "%4d", count);
        return String.format(Locale.US, "%-12s", cut(label, 12)) + c + right(money(cents), 16);
    }

    private static String money(long cents) { return "$" + Money.dollars(cents); }
    private static String cardType(int acct) { return acct == 30 ? "CR" : "DB"; }
    private static String entry(int em) { return em == 1 ? "C" : em == 2 ? "T" : em == 3 ? "S" : "?"; }
    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String cut(String s, int n) { s = nz(s); return s.length() <= n ? s : s.substring(0, n); }
    private static String right(String s, int w) { return s.length() >= w ? s : String.format(Locale.US, "%" + w + "s", s); }
    private static String center(String s) { int pad = Math.max(0, (WIDTH - s.length()) / 2); return String.format(Locale.US, "%" + (pad + s.length()) + "s", s); }
    /** left and right text on one 32-column line, at least two spaces apart. */
    private static String pair(String left, String rightText) {
        int gap = WIDTH - left.length() - rightText.length();
        if (gap < 2) { left = cut(left, Math.max(0, WIDTH - rightText.length() - 2)); gap = WIDTH - left.length() - rightText.length(); }
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < gap; i++) sb.append(' ');
        return sb.append(rightText).toString();
    }
    private static void line(StringBuilder b, String s) { b.append(cut(s, WIDTH)).append('\n'); }
}
```

Note the "Bal Inquiries  3    Reversed  0" line: the test pins the exact text. Simplest correct implementation: replace the awkward `String.format(...).replace(...)` with a direct builder — `line(b, String.format(Locale.US, "%-13s%2d    %-9s%d", "Bal Inquiries", s.balanceInquiries, "Reversed", s.reversed));` which yields `Bal Inquiries  3    Reversed  0` (13 + 2 + 4 + 9 + 1 = 29). Use that form; delete the replace chain.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew -q testMkskDebugUnitTest --tests "castech.emvtxn.atm.report.DetailReportTest"` then the result XML (`app/build/test-results/testMkskDebugUnitTest/*DetailReportTest.xml`): tests=8 failures=0. If a width or alignment assertion fails, adjust the implementation, never the agreed layout.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/castech/emvtxn/atm/report/ app/src/test/java/castech/emvtxn/atm/report/DetailReportTest.java
git commit -m "RPT-01: DetailReport — pure 32-column formatter + summary (tests first)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Batch arithmetic (pure)

**Files:**
- Create: `app/src/main/java/castech/emvtxn/atm/BatchMath.java`
- Test: `app/src/test/java/castech/emvtxn/atm/BatchMathTest.java`

**Interfaces:**
- Produces: `static int BatchMath.nextBatchId(int current)` (1 → 2; `0`/negative → 1); `static int BatchMath.firstBatchId()` = 1; `static long BatchMath.oldestBatchToKeep(int currentBatchId, int keep)` → the lowest batch id whose rows survive pruning (`current - keep + 1`, min 1).

- [ ] **Step 1: Write the failing test**

```java
package castech.emvtxn.atm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BatchMathTest {
    @Test public void firstBatchIsOne() { assertEquals(1, BatchMath.firstBatchId()); }
    @Test public void nextBatchId_onlyAdvancesOnClose() {
        assertEquals(2, BatchMath.nextBatchId(1));
        assertEquals(4, BatchMath.nextBatchId(3));
        assertEquals(1, BatchMath.nextBatchId(0));     // nothing yet → 1
        assertEquals(1, BatchMath.nextBatchId(-5));
    }
    @Test public void pruneKeepsTheLastNBatches() {
        assertEquals(1, BatchMath.oldestBatchToKeep(5, 20));
        assertEquals(6, BatchMath.oldestBatchToKeep(25, 20));
        assertEquals(1, BatchMath.oldestBatchToKeep(20, 20));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `--tests "castech.emvtxn.atm.BatchMathTest"` → `cannot find symbol BatchMath`.

- [ ] **Step 3: Implement**

```java
package castech.emvtxn.atm;

/** Batch counter rules (terminal-owned, decision 2 of the spec). Pure. */
public final class BatchMath {
    private BatchMath() {}
    public static final int KEEP_BATCHES = 20;
    public static int firstBatchId() { return 1; }
    public static int nextBatchId(int current) { return current <= 0 ? 1 : current + 1; }
    public static long oldestBatchToKeep(int currentBatchId, int keep) { return Math.max(1, (long) currentBatchId - keep + 1); }
}
```

- [ ] **Step 4: Run to verify it passes** — 3 tests green.
- [ ] **Step 5: Commit** — `git add app/src/main/java/castech/emvtxn/atm/BatchMath.java app/src/test/java/castech/emvtxn/atm/BatchMathTest.java && git commit -m "RPT-01: BatchMath — counter and retention rules (tests first)" ` (with the Co-Authored-By trailer).

---

### Task 3: Schema v2 — report columns + batches table

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/atm/TransactionLog.java` (add fields + getters/setters)
- Modify: `app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java` (version 2, migration, batches, queries)

**Interfaces:**
- Consumes: `BatchMath` (Task 2); `DetailReport.Summary` (Task 1) for the close snapshot.
- Produces (all on `TransactionLogManager`, obtained via `TransactionLogManager.getInstance(Context)`):
  - `TransactionLog` new fields: `int sequenceNumber; int accountType; String clerkId; String invoiceNo; long tipCents; int batchId; boolean reversed;` with get/set.
  - `int currentBatchId()` — opens batch 1 on first call.
  - `long currentBatchOpenedAt()`.
  - `int closeCurrentBatch(DetailReport.Summary snapshot)` — stamps `closed_at` + snapshot on the open batch, inserts the next batch, prunes rows/batches older than `BatchMath.oldestBatchToKeep(next, KEEP_BATCHES)`, returns the **new** batch id.
  - `List<TransactionLog> getTransactionsForBatch(int batchId)` ordered by `sequence_number, timestamp`.
  - `boolean markReversed(int sequenceNumber, int batchId)`.
  - `int clearClosedBatches()` — deletes rows and batch records with `batch_id < currentBatchId()`.

- [ ] **Step 1: TransactionLog fields** — after `private String errorMessage;` add:

```java
    // ---- 6.2.11 report columns ----
    private int sequenceNumber;         // our STD1 sequence (REF# on the report)
    private int accountType;            // GlobalPara.ATM_ACCOUNT_* (10 sav, 20 chk, 30 credit)
    private String clerkId;             // register sale only
    private String invoiceNo;           // register sale only
    private long tipCents;              // reserved (0 until tips ship)
    private int batchId;                // terminal-owned batch
    private boolean reversed;           // approval later reversed → excluded from detail lines
```
and the matching getters/setters at the end of the getter block (`getSequenceNumber/setSequenceNumber`, `getAccountType/setAccountType`, `getClerkId/setClerkId`, `getInvoiceNo/setInvoiceNo`, `getTipCents/setTipCents`, `getBatchId/setBatchId`, `isReversed/setReversed`).

- [ ] **Step 2: Manager schema** — in `TransactionLogManager`: `DATABASE_VERSION = 2`; add column constants `COL_SEQUENCE = "sequence_number"`, `COL_ACCOUNT_TYPE = "account_type"`, `COL_CLERK_ID = "clerk_id"`, `COL_INVOICE_NO = "invoice_no"`, `COL_TIP_CENTS = "tip_cents"`, `COL_BATCH_ID = "batch_id"`, `COL_REVERSED = "reversed"`; append them to `SQL_CREATE_TABLE` (`INTEGER DEFAULT 0` for ints/longs, `TEXT` for strings); add

```java
    private static final String TABLE_BATCHES = "batches";
    private static final String SQL_CREATE_BATCHES =
            "CREATE TABLE IF NOT EXISTS " + TABLE_BATCHES + " (" +
            "id INTEGER PRIMARY KEY, opened_at INTEGER NOT NULL, closed_at INTEGER DEFAULT 0, " +
            "withdrawals INTEGER DEFAULT 0, amount_cents INTEGER DEFAULT 0, fee_cents INTEGER DEFAULT 0, tip_cents INTEGER DEFAULT 0)";
```
`onCreate` also runs `SQL_CREATE_BATCHES`. Replace the destructive `onUpgrade` with a migration:

```java
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading transaction log " + oldVersion + " → " + newVersion + " (data preserved)");
        if (oldVersion < 2) {
            for (String ddl : new String[] {
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_SEQUENCE + " INTEGER DEFAULT 0",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_ACCOUNT_TYPE + " INTEGER DEFAULT 20",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_CLERK_ID + " TEXT",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_INVOICE_NO + " TEXT",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_TIP_CENTS + " INTEGER DEFAULT 0",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_BATCH_ID + " INTEGER DEFAULT 1",
                    "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_REVERSED + " INTEGER DEFAULT 0" }) {
                try { db.execSQL(ddl); } catch (Exception e) { Log.w(TAG, "migration step skipped: " + e.getMessage()); }
            }
            db.execSQL(SQL_CREATE_BATCHES);
        }
    }
```
`saveTransaction` puts the seven new columns; `cursorToTransactionLog` reads them (guard with `getColumnIndex(...) >= 0`).

- [ ] **Step 3: Batch methods** — add to the manager:

```java
    public synchronized int currentBatchId() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT id, opened_at FROM " + TABLE_BATCHES + " WHERE closed_at = 0 ORDER BY id DESC LIMIT 1", null)) {
            if (c.moveToFirst()) return c.getInt(0);
        }
        ContentValues v = new ContentValues();
        v.put("id", BatchMath.firstBatchId());
        v.put("opened_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_BATCHES, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return BatchMath.firstBatchId();
    }

    public synchronized long currentBatchOpenedAt() {
        int id = currentBatchId();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT opened_at FROM " + TABLE_BATCHES + " WHERE id = ?", new String[] { String.valueOf(id) })) {
            return c.moveToFirst() ? c.getLong(0) : System.currentTimeMillis();
        }
    }

    /** Close the open batch with its summary snapshot; open the next; prune. Returns the NEW batch id. */
    public synchronized int closeCurrentBatch(castech.emvtxn.atm.report.DetailReport.Summary s) {
        int current = currentBatchId();
        int next = BatchMath.nextBatchId(current);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues close = new ContentValues();
            close.put("closed_at", System.currentTimeMillis());
            close.put("withdrawals", s.withdrawals); close.put("amount_cents", s.amountCents);
            close.put("fee_cents", s.feeCents); close.put("tip_cents", s.tipCents);
            db.update(TABLE_BATCHES, close, "id = ?", new String[] { String.valueOf(current) });
            ContentValues open = new ContentValues();
            open.put("id", next); open.put("opened_at", System.currentTimeMillis());
            db.insert(TABLE_BATCHES, null, open);
            long oldest = BatchMath.oldestBatchToKeep(next, BatchMath.KEEP_BATCHES);
            db.delete(TABLE_TRANSACTIONS, COL_BATCH_ID + " < ?", new String[] { String.valueOf(oldest) });
            db.delete(TABLE_BATCHES, "id < ?", new String[] { String.valueOf(oldest) });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.w(TAG, "Batch " + current + " closed; batch " + next + " opened");
        return next;
    }

    public List<TransactionLog> getTransactionsForBatch(int batchId) {
        List<TransactionLog> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE_TRANSACTIONS, null, COL_BATCH_ID + " = ?",
                new String[] { String.valueOf(batchId) }, null, null, COL_SEQUENCE + " ASC, " + COL_TIMESTAMP + " ASC")) {
            while (c.moveToNext()) out.add(cursorToTransactionLog(c));
        } catch (Exception e) { Log.e(TAG, "getTransactionsForBatch: " + e.getMessage()); }
        return out;
    }

    public boolean markReversed(int sequenceNumber, int batchId) {
        ContentValues v = new ContentValues(); v.put(COL_REVERSED, 1);
        int n = getWritableDatabase().update(TABLE_TRANSACTIONS, v,
                COL_SEQUENCE + " = ? AND " + COL_BATCH_ID + " = ? AND " + COL_RESULT + " = 'APPROVED'",
                new String[] { String.valueOf(sequenceNumber), String.valueOf(batchId) });
        return n > 0;
    }

    public synchronized int clearClosedBatches() {
        int current = currentBatchId();
        SQLiteDatabase db = getWritableDatabase();
        int n = db.delete(TABLE_TRANSACTIONS, COL_BATCH_ID + " < ?", new String[] { String.valueOf(current) });
        db.delete(TABLE_BATCHES, "id < ?", new String[] { String.valueOf(current) });
        return n;
    }
```
(`Cursor`, `ContentValues`, `ArrayList` imports as already used in the file.)

- [ ] **Step 4: Compile** — `./gradlew -q compileMkskDebugJavaWithJavac 2>&1 | grep -E "error:" -A1 | head` → nothing. (No JVM SQLite here; this task is exercised on the device in Task 8.)
- [ ] **Step 5: Commit** — `git add app/src/main/java/castech/emvtxn/atm/TransactionLog.java app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java && git commit -m "RPT-01: transaction log schema v2 — report columns, batches table, data-preserving migration"` (+ trailer).

---

### Task 4: Journal — write one row per finished transaction; mark reversals

**Files:**
- Create: `app/src/main/java/castech/emvtxn/atm/TransactionJournal.java`
- Create: `app/src/main/java/castech/emvtxn/atm/JournalOutcome.java` (pure) + Test: `app/src/test/java/castech/emvtxn/atm/JournalOutcomeTest.java`
- Modify: `app/src/main/java/castech/emvtxn/GlobalPara.java` — new fields `public static volatile int atmSequenceNumber = 0; public static volatile String atmClerkId = ""; public static volatile String atmInvoiceNo = "";` next to `atmCurrentReversalId` (line ~239) and reset them in `resetATMTransactionState()` right after `atmCurrentReversalId = "";` (line ~297).
- Modify: `app/src/main/java/castech/emvtxn/atm/host/AtmTransactionManager.java` — at both lines reading `castech.emvtxn.GlobalPara.atmCurrentReversalId = currentPreSendReversal.getTransactionId();` add the next line `castech.emvtxn.GlobalPara.atmSequenceNumber = request.getSequenceNumber();` (withdrawal and BI paths).
- Modify: `app/src/main/java/castech/emvtxn/pos/PosTransactionExecutor.java:71` — before `gateway.startSale(...)` add `castech.emvtxn.GlobalPara.atmClerkId = resource.optString(PosWire.TXN_CLERK_ID, ""); castech.emvtxn.GlobalPara.atmInvoiceNo = resource.optString(PosWire.TXN_INVOICE_NO, "");`
- Modify: `app/src/main/java/castech/emvtxn/MainActivity.java` — two hooks at the end of the transaction thread (lines ~4890-4915): in the cancel branch, before `PosTransactionObserver.notifyDeclined("user_cancelled", ...)`, add `castech.emvtxn.atm.TransactionJournal.record(getApplicationContext(), castech.emvtxn.atm.JournalOutcome.CANCELLED);` and in the receipt branch, right after `GlobalPara.atmTransactionComplete = true;`, add `castech.emvtxn.atm.TransactionJournal.record(getApplicationContext(), castech.emvtxn.atm.JournalOutcome.fromGlobalPara());`
- Modify: `app/src/main/java/castech/emvtxn/atm/host/AtmHostService.java` — in `attemptReversalWithBackoff`, in the `if (ok) {` block after `reversalManager.removePendingReversal(rev.getTransactionId());` add `castech.emvtxn.atm.TransactionJournal.markReversed(context, rev.getSequenceNumber());` (`context` is the service's stored Context field — confirm its name with `grep -n "private.*Context" AtmHostService.java`; if the field is `appContext`, use that).

**Interfaces:**
- Consumes: Task 3 manager methods; `GlobalPara.atmSelectedAmount/atmFee/atmTotal` (dollar strings), `atmEntryMode`, `atmAccountType`, `asciiPAN`, `atmAuthCode`, `atmReferenceNumber`, `atmResponseCode`, `atmResponseMessage`, `atmHostCallSuccess`, `atmBalanceInquiryMode`, `atmTerminalId`, `atmProcessorType`, new `atmSequenceNumber/atmClerkId/atmInvoiceNo`.
- Produces: `TransactionJournal.record(Context, JournalOutcome)` (never throws), `TransactionJournal.markReversed(Context, int sequence)`, `JournalOutcome { String type; String result; }` with `static JournalOutcome fromGlobalPara()` and constants `CANCELLED`.

- [ ] **Step 1: Failing test for the pure outcome mapping**

```java
package castech.emvtxn.atm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class JournalOutcomeTest {
    @Test public void approvedWithdrawal() {
        JournalOutcome o = JournalOutcome.of(false, true, "00", "APPROVED");
        assertEquals("WITHDRAWAL", o.type); assertEquals("APPROVED", o.result);
    }
    @Test public void declinedWithdrawal() {
        JournalOutcome o = JournalOutcome.of(false, false, "51", "INSUFFICIENT FUNDS");
        assertEquals("WITHDRAWAL", o.type); assertEquals("DECLINED", o.result);
    }
    @Test public void connectionErrorIsDeclined_notCancelled() {
        JournalOutcome o = JournalOutcome.of(false, false, "", "Connection error: timed out");
        assertEquals("DECLINED", o.result);
    }
    @Test public void cancelledMessage_isCancelled() {
        assertEquals("CANCELLED", JournalOutcome.of(false, false, "", "Transaction cancelled").result);
        assertEquals("CANCELLED", JournalOutcome.CANCELLED.result);
    }
    @Test public void balanceInquiry_keepsItsType() {
        assertEquals("BALANCE_INQUIRY", JournalOutcome.of(true, true, "00", "APPROVED").type);
        assertEquals("BALANCE_INQUIRY", JournalOutcome.of(true, false, "05", "DO NOT HONOR").type);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `--tests "castech.emvtxn.atm.JournalOutcomeTest"` → cannot find symbol.

- [ ] **Step 3: Implement `JournalOutcome` (pure)**

```java
package castech.emvtxn.atm;

/** Which journal row a finished transaction becomes. Pure; mapping pinned by JournalOutcomeTest. */
public final class JournalOutcome {
    public final String type;    // WITHDRAWAL | BALANCE_INQUIRY
    public final String result;  // APPROVED | DECLINED | CANCELLED
    public static final JournalOutcome CANCELLED = new JournalOutcome("WITHDRAWAL", "CANCELLED");
    JournalOutcome(String type, String result) { this.type = type; this.result = result; }

    public static JournalOutcome of(boolean balanceInquiry, boolean hostApproved, String responseCode, String message) {
        String type = balanceInquiry ? "BALANCE_INQUIRY" : "WITHDRAWAL";
        if (hostApproved) return new JournalOutcome(type, "APPROVED");
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.US);
        if (m.contains("cancel")) return new JournalOutcome(type, "CANCELLED");
        return new JournalOutcome(type, "DECLINED");
    }

    /** Android-side convenience: read the finished transaction's state from GlobalPara. */
    public static JournalOutcome fromGlobalPara() {
        return of(castech.emvtxn.GlobalPara.atmBalanceInquiryMode, castech.emvtxn.GlobalPara.atmHostCallSuccess,
                  castech.emvtxn.GlobalPara.atmResponseCode, castech.emvtxn.GlobalPara.atmResponseMessage);
    }
}
```

- [ ] **Step 4: Run to verify it passes** — 5 green.

- [ ] **Step 5: Implement `TransactionJournal` (Android glue)**

```java
package castech.emvtxn.atm;

import android.content.Context;
import android.util.Log;

import castech.emvtxn.GlobalPara;
import castech.emvtxn.Money;

/**
 * Writes one row per finished transaction into TransactionLogManager (6.2.11). Called at the
 * single completion point in MainActivity's transaction thread, which both walk-up and POS
 * flows pass through. Must never break a transaction: everything is caught and logged.
 */
public final class TransactionJournal {
    private static final String TAG = "TxnJournal";
    private TransactionJournal() {}

    public static void record(Context ctx, JournalOutcome outcome) {
        try {
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            TransactionLog log = new TransactionLog();
            log.setTransactionId("TXN" + System.currentTimeMillis() + "-" + GlobalPara.atmSequenceNumber);
            log.setTransactionType(outcome.type);
            log.setTimestamp(System.currentTimeMillis());
            log.setCardLastFour(lastFour(GlobalPara.asciiPAN));
            log.setEntryMode(GlobalPara.atmEntryMode == 1 ? "CONTACT" : GlobalPara.atmEntryMode == 2 ? "CONTACTLESS" : GlobalPara.atmEntryMode == 3 ? "MSR" : "UNKNOWN");
            long amount = "BALANCE_INQUIRY".equals(outcome.type) ? 0L : Money.toCents(parse(GlobalPara.atmSelectedAmount));
            long fee = "BALANCE_INQUIRY".equals(outcome.type) ? 0L : Money.toCents(parse(GlobalPara.atmFee));
            log.setAmountCents(amount); log.setFeeCents(fee); log.setTipCents(0L); log.setTotalCents(amount + fee);
            log.setResult(outcome.result);
            log.setResponseCode(nz(GlobalPara.atmResponseCode));
            log.setAuthCode(nz(GlobalPara.atmAuthCode));
            log.setReferenceNumber(nz(GlobalPara.atmReferenceNumber));
            log.setTerminalId(nz(GlobalPara.atmTerminalId));
            log.setProcessorType(nz(GlobalPara.atmProcessorType));
            log.setErrorMessage("APPROVED".equals(outcome.result) ? null : nz(GlobalPara.atmResponseMessage));
            log.setSequenceNumber(GlobalPara.atmSequenceNumber);
            log.setAccountType(GlobalPara.atmAccountType);
            log.setClerkId(blankToNull(GlobalPara.atmClerkId));
            log.setInvoiceNo(blankToNull(GlobalPara.atmInvoiceNo));
            log.setBatchId(m.currentBatchId());
            log.setReversed(false);
            long id = m.saveTransaction(log);
            Log.d(TAG, "journaled " + outcome.type + "/" + outcome.result + " seq=" + GlobalPara.atmSequenceNumber
                    + " batch=" + log.getBatchId() + " row=" + id);
        } catch (Throwable t) {
            Log.w(TAG, "journal write skipped: " + t.getMessage());
        }
    }

    public static void markReversed(Context ctx, int sequenceNumber) {
        try {
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            boolean hit = m.markReversed(sequenceNumber, m.currentBatchId());
            Log.w(TAG, "reversal accepted for seq " + sequenceNumber + " → journal row " + (hit ? "marked reversed" : "not found (pre-send failure, nothing approved)"));
        } catch (Throwable t) {
            Log.w(TAG, "markReversed skipped: " + t.getMessage());
        }
    }

    static String lastFour(String pan) {
        if (pan == null) return "";
        String digits = pan.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
    }
    private static double parse(String dollars) { try { return Double.parseDouble(dollars.trim()); } catch (Exception e) { return 0d; } }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
```

- [ ] **Step 6: Wire GlobalPara, AtmTransactionManager, PosTransactionExecutor, MainActivity, AtmHostService** as listed under **Files** (byte-preserving edits; MainActivity anchors: the comment `// Cancelled after card detection and the host did not approve —` block and `GlobalPara.atmTransactionComplete = true;`).

- [ ] **Step 7: Compile + full suite** — `./gradlew -q compileMkskDebugJavaWithJavac` clean; `./gradlew -q testMkskDebugUnitTest` → baseline 241 + 8 + 3 + 5 = 257 tests, failures = the 3 pre-existing only.

- [ ] **Step 8: Commit** — `git add app/src/main/java/castech/emvtxn/atm/TransactionJournal.java app/src/main/java/castech/emvtxn/atm/JournalOutcome.java app/src/test/java/castech/emvtxn/atm/JournalOutcomeTest.java app/src/main/java/castech/emvtxn/GlobalPara.java app/src/main/java/castech/emvtxn/atm/host/AtmTransactionManager.java app/src/main/java/castech/emvtxn/pos/PosTransactionExecutor.java app/src/main/java/castech/emvtxn/MainActivity.java app/src/main/java/castech/emvtxn/atm/host/AtmHostService.java && git commit -m "RPT-01: TransactionJournal — one row per finished transaction, reversal marking (outcome mapping tested first)"` (+ trailer).

---

### Task 5: Close Batch hook + batch number on the totals receipt

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/MainActivity.java` — `requestHostTotalsWithReset()` success branch (`if (response.isSuccess()) {` inside `onHostTotalsReceived`), and `printHostTotalsReceipt(...)`.

**Interfaces:**
- Consumes: `TransactionLogManager.getInstance(ctx).currentBatchId()`, `.getTransactionsForBatch(id)`, `.closeCurrentBatch(summary)`; `DetailReport.summarize(rows)` with rows mapped through a small helper `ReportRows.fromLogs(List<TransactionLog>)` (create in Task 5: `app/src/main/java/castech/emvtxn/atm/report/ReportRows.java`, a static mapper `TransactionLog → ReportRow`: entryMode string → 1/2/3, others 1:1).
- Produces: `MainActivity.closeLocalBatchAfterHostReset()` (private) called from the success branch **before** `printHostTotalsReceipt(response, true)`, returning the closed batch id so the receipt can print it; `printHostTotalsReceipt` gains a third parameter `int batchId` and prints `Batch #: 003` under the Date line (for a plain Host Totals, pass `currentBatchId()`).

- [ ] **Step 1: `ReportRows.fromLogs`**

```java
package castech.emvtxn.atm.report;

import java.util.ArrayList;
import java.util.List;

import castech.emvtxn.atm.TransactionLog;

public final class ReportRows {
    private ReportRows() {}
    public static List<ReportRow> fromLogs(List<TransactionLog> logs) {
        List<ReportRow> out = new ArrayList<>();
        for (TransactionLog l : logs) {
            int em = "CONTACT".equals(l.getEntryMode()) ? 1 : "CONTACTLESS".equals(l.getEntryMode()) ? 2 : "MSR".equals(l.getEntryMode()) ? 3 : 0;
            out.add(new ReportRow(l.getSequenceNumber(), l.getCardLastFour(), em, l.getAccountType(),
                    l.getAmountCents(), l.getFeeCents(), l.getTipCents(), l.getAuthCode(), l.getReferenceNumber(),
                    l.getClerkId(), l.getInvoiceNo(), l.getTransactionType(), l.getResult(), l.isReversed()));
        }
        return out;
    }
}
```

- [ ] **Step 2: Close hook in MainActivity**

Add the method:
```java
    /** Terminal-owned batch boundary (spec decision 2): called only after the processor accepted the reset. */
    private int closeLocalBatchAfterHostReset() {
        try {
            castech.emvtxn.atm.TransactionLogManager m = castech.emvtxn.atm.TransactionLogManager.getInstance(getApplicationContext());
            int closing = m.currentBatchId();
            castech.emvtxn.atm.report.DetailReport.Summary s = castech.emvtxn.atm.report.DetailReport.summarize(
                    castech.emvtxn.atm.report.ReportRows.fromLogs(m.getTransactionsForBatch(closing)));
            m.closeCurrentBatch(s);
            return closing;
        } catch (Throwable t) {
            Log.w(TAG, "local batch close skipped: " + t.getMessage());
            return 0;
        }
    }
```
In the success branch replace `printHostTotalsReceipt(response, true);` with
```java
                                    final int closedBatch = closeLocalBatchAfterHostReset();
                                    printHostTotalsReceipt(response, true, closedBatch);
```
and the plain Host Totals call site (`printHostTotalsReceipt(response, false)` inside `requestHostTotals()`) with `printHostTotalsReceipt(response, false, currentBatchIdSafe())` where
```java
    private int currentBatchIdSafe() {
        try { return castech.emvtxn.atm.TransactionLogManager.getInstance(getApplicationContext()).currentBatchId(); }
        catch (Throwable t) { return 0; }
    }
```
In `printHostTotalsReceipt`, change the signature to `(response, batchClosed, int batchId)` and after the `Date:` line add
```java
        if (batchId > 0) r.append("Batch #: ").append(String.format(java.util.Locale.US, "%03d", batchId)).append("\n");
```

- [ ] **Step 3: Compile; grep that no other caller of `printHostTotalsReceipt(` remains with two args** — `grep -n "printHostTotalsReceipt(" app/src/main/java/castech/emvtxn/MainActivity.java`.
- [ ] **Step 4: Commit** — `git add app/src/main/java/castech/emvtxn/atm/report/ReportRows.java app/src/main/java/castech/emvtxn/MainActivity.java && git commit -m "RPT-01: Close Batch opens the next local batch; Batch # printed on totals receipts"` (+ trailer).

---

### Task 6: Menu entry, confirm dialog, print or show

**Files:**
- Modify: `app/src/main/res/menu/menu_main.xml` — add after `action_host_totals`:
```xml
    <item
        android:id="@+id/action_detail_report"
        android:orderInCategory="300"
        android:title="Detail Report"
        app:showAsAction="never"/>
```
- Modify: `app/src/main/java/castech/emvtxn/MainActivity.java` — `onOptionsItemSelected`: `} else if (id == R.id.action_detail_report) { printDetailReport(); return true; }`; add `printDetailReport()`.

**Interfaces:**
- Consumes: Tasks 1, 3, 5.
- Produces: `MainActivity.printDetailReport()`.

- [ ] **Step 1: Implement**

```java
    /** Ellipsis ▸ Detail Report (6.2.11). Same gate as Host Totals: a confirm dialog, no PIN. */
    public void printDetailReport() {
        new Thread(() -> {
            final int batch; final long opened; final java.util.List<castech.emvtxn.atm.report.ReportRow> rows;
            try {
                castech.emvtxn.atm.TransactionLogManager m = castech.emvtxn.atm.TransactionLogManager.getInstance(getApplicationContext());
                batch = m.currentBatchId(); opened = m.currentBatchOpenedAt();
                rows = castech.emvtxn.atm.report.ReportRows.fromLogs(m.getTransactionsForBatch(batch));
            } catch (Throwable t) {
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this).setTitle("Detail Report")
                        .setMessage("Transaction journal unavailable: " + t.getMessage()).setPositiveButton("OK", null).show());
                return;
            }
            final castech.emvtxn.atm.report.DetailReport.Summary s = castech.emvtxn.atm.report.DetailReport.summarize(rows);
            final String text = castech.emvtxn.atm.report.DetailReport.render(
                    new castech.emvtxn.atm.report.DetailReport.Header(batch, opened, System.currentTimeMillis(), GlobalPara.atmTerminalId), rows);
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("Print Detail Report?")
                .setMessage("Batch " + String.format(java.util.Locale.US, "%03d", batch) + " — " + s.withdrawals
                        + " approved withdrawal(s), " + castech.emvtxn.Money.dollars(s.amountCents + s.feeCents + s.tipCents) + " since "
                        + new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(new java.util.Date(opened)))
                .setPositiveButton("Print", (d, w) -> printOrShowReport(text))
                .setNeutralButton("View", (d, w) -> showReportDialog(text))
                .setNegativeButton("Cancel", null)
                .show());
        }, "DetailReport").start();
    }

    private void printOrShowReport(final String text) {
        if (Printer == null || GlobalPara.atmPrinterOutOfPaper) { showReportDialog(text); return; }
        new Thread(() -> {
            try {
                Printer.printf(text);
                Log.d(TAG, "Detail report printed");
            } catch (Exception e) {
                Log.e(TAG, "Detail report print failed: " + e.getMessage());
                runOnUiThread(() -> showReportDialog(text));
            }
        }, "DetailReportPrint").start();
    }

    private void showReportDialog(String text) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setPadding(24, 16, 24, 16);
        tv.setText(text);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Detail Report").setView(sv).setPositiveButton("Close", null).show();
    }
```

- [ ] **Step 2: Compile.** - [ ] **Step 3: Commit** — `git add app/src/main/res/menu/menu_main.xml app/src/main/java/castech/emvtxn/MainActivity.java && git commit -m "RPT-01: ellipsis ▸ Detail Report — confirm, print, or view when out of paper"` (+ trailer).

---

### Task 7: Admin Clear History = closed batches only

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java` — bind `btnClearHistory` (layout id exists, no handler today) in `initializeViews` (`btnClearHistory = rootView.findViewById(R.id.btnClearHistory);` with a new field `private Button btnClearHistory;`) and in `setupListeners`: `if (btnClearHistory != null) btnClearHistory.setOnClickListener(v -> clearClosedBatches());`

- [ ] **Step 1: Implement**

```java
    /** Super only (greyed for Normal in applyAccessLevel). Clears journal rows of CLOSED batches; the open batch is never touched. */
    private void clearClosedBatches() {
        if (accessLevel != ACCESS_SUPER) { Toast.makeText(getContext(), "Super Admin only", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(getContext())
            .setTitle("Clear transaction history?")
            .setMessage("Deletes the journal rows of closed batches. The current (open) batch is kept so its Detail Report stays complete.")
            .setPositiveButton("Clear", (d, w) -> {
                try {
                    int n = castech.emvtxn.atm.TransactionLogManager.getInstance(getContext().getApplicationContext()).clearClosedBatches();
                    Toast.makeText(getContext(), n + " row(s) cleared", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(getContext(), "Clear failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
```
- [ ] **Step 2: Compile.** - [ ] **Step 3: Commit** — `git add app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java && git commit -m "RPT-01: Admin Clear History clears closed batches only (Super)"` (+ trailer).

---

### Task 8: Release bump, notes, backlog, PR, device pass

**Files:**
- Modify: `app/build.gradle` — `versionCode 73`, `versionName "6.2.11"`.
- Modify: `RELEASE-NOTES.md` — new `## 6.2.11 — <date> · PR #7 · base 6.2.10 · versionCode 73` entry above 6.2.10, same section shape as earlier entries: Highlights (Detail Report; journal now written; batch counter; Batch # on totals receipts), What operators notice (ellipsis entry, confirm, View when out of paper, Clear History semantics), Fixes (`RPT-01`), Known/deferred (previous-batch reprint, MyView export, tips column reserved), Verification (test counts: `DetailReportTest` 8, `BatchMathTest` 3, `JournalOutcomeTest` 5; suite total from the last run; device items below), Upgrade notes (DB migrates in place; batch 001 opens on first use after install).
- Modify: `docs/CODE-REVIEW-BACKLOG.md` — `- [x] **RPT-01** · MED · … — shipped 6.2.11` (one paragraph: journal wired at the completion point, schema v2, batches, report, Clear History) inserted above `REV-02`.

- [ ] **Step 1: Edit the three files; run the full suite; record the total in the notes.**
- [ ] **Step 2: Commit** — `git add app/build.gradle RELEASE-NOTES.md docs/CODE-REVIEW-BACKLOG.md && git commit -m "6.2.11: version bump (versionCode 73) + release notes + backlog (RPT-01)"` (+ trailer); `git push -u origin release/v6.2.11`.
- [ ] **Step 3: PR** — `gh pr create --base release/v6.2.10 --head release/v6.2.11 --title "v6.2.11: Detail Report + transaction journal (RPT-01)" --body-file <summary: spec link, design bullets, tests, device-pass checklist below, trailer "🤖 Generated with [Claude Code](https://claude.com/claude-code)">`.
- [ ] **Step 4: Device pass (debug build, terminal …680):** install `./gradlew assembleMkskDebug` via adb; run walk-up withdrawal (approved), POS sale with `clerk_id`/`invoice_no`, one decline (wrong PIN ×3 or insufficient funds card), one balance inquiry, one cancel at the card screen; ellipsis ▸ Detail Report ▸ View, then Print: every figure matches the receipts, cancel/decline/BI only in the counts, clerk line only on the POS block; Close Batch → totals receipt shows `Batch #: 001`, report now says batch 002 with "(no approved withdrawals)"; Admin Clear History (Super) removes batch 001 rows only; pull a reversal (WiFi off during Online Processing) → when the drain clears it, that row leaves the lines and `Reversed 1` appears. Record results in the PR checklist and the notes.

---

## Self-review notes

- Spec coverage: decisions 1–6 → Tasks 1 (lines/summary/fee/tip), 2+3+5 (batch), 6 (access/paper), 4 (journal, reversed), 7 (retention UI), 3 (retention at close), 8 (delivery). Footer text → Task 1 test. Batch # on Close Batch receipt → Task 5.
- Type consistency: `ReportRow` ctor order is used identically in Tasks 1, 5 (`ReportRows.fromLogs`); `DetailReport.Summary` fields used in Tasks 3, 5, 6; `TransactionLogManager` methods named identically in Tasks 3–7; `JournalOutcome.of/fromGlobalPara/CANCELLED` in Task 4 and the MainActivity hooks.
- Review Focus items 1–5 each have an owning test or a guarded code path named above.
