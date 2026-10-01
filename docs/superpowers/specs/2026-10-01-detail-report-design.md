# Detail Report — design (6.2.11)

**Status:** draft for review · **Owner:** TFI · **Date:** 2026-10-01

## Purpose

A printed **Detail Report** from the ellipsis menu (Admin · Host Totals · **Detail Report**)
listing every approved withdrawal in the current batch, one block each, followed by a
summary. Merchants reconcile the cash they handed out against it; support uses it to
answer "what did this terminal do since the last close". Modelled on the sample receipt
the user supplied (05/13/25, TID MP002153, Batch 002), adapted to our 32-column printer.

## Decisions taken in the design conversation

| # | Decision | Choice |
|---|----------|--------|
| 1 | Which transactions appear as detail lines | **Approved withdrawals only.** Declines, cancels, balance inquiries and reversals appear only as counts in the summary. |
| 2 | Batch boundary and number | **Terminal-owned.** The boundary is a successful Close Batch (Type 87 reset accepted). A local counter starts at 001 and increments on each close; it is a label for humans, not a processor identifier. The report prints the number and the batch's open time. |
| 3 | Fee columns | **One `FEE` column** (our surcharge = receipt "Service Fee" = Host Totals surcharge). No "cash back" column. |
| 4 | Tips | A `TIP` column and a `Tips` summary line exist from day one at $0.00 so the layout does not change when tips ship (next item). |
| 5 | Access | Same as Host Totals: ellipsis entry, confirm dialog, **no PIN**. |
| 6 | Paper | Print through the receipt printer; when the printer is out of paper or unavailable, show the same text in a scrollable dialog (the receipt screen already does this for receipts). |

## What exists and what is missing

- `atm/TransactionLog` + `atm/TransactionLogManager` (SQLite, `transactions` table) exist with
  the right columns — **but nothing writes to them** (the activity field is commented out).
  The Admin "Transaction History" section is a header and a Clear button over an empty table.
- Host Totals prints from a `StringBuilder` through `MainActivity.CTOS_Printer.printf` —
  the report reuses that path.
- `res/menu/menu_main.xml` has `action_admin` and `action_host_totals`.
- Per-transaction facts available at approval time: amount/fee/total cents (`Money`), card
  last four (`GlobalPara.asciiPAN`, masked), entry mode (CT/CL/MSR), account type
  (checking/savings/credit → card type DB/CR), host response code, auth code, RRN, sequence
  number (in `AtmTransactionManager`), POS `clerk_id` / `invoice_no` (parsed by
  `PosTransactionExecutor`, currently dropped).

## Architecture

Three small units, each testable on its own:

1. **Journal (write side)** — `atm/TransactionJournal` (thin service over the existing
   `TransactionLogManager`): one `record(...)` call at the single point where every host
   answer lands (`MainActivity`'s host listener: `onTransactionApproved`,
   `onTransactionDeclined`, `onBalanceReceived`, plus the cancel/error path that produces a
   declined receipt). Walk-up and POS both pass through that listener, so one hook covers
   both. Adds the missing columns via a schema upgrade: `sequence_number`, `account_type`,
   `clerk_id`, `invoice_no`, `tip_cents` (0 for now), `batch_id`, `reversed` (0/1).
2. **Batches** — new `batches` table: `id` (the counter), `opened_at`, `closed_at`,
   `withdrawal_count`, `withdrawal_cents`, `fee_cents`, `tip_cents` (snapshot at close).
   `BatchStore.current()` returns the open batch (creates batch 001 on first use);
   `BatchStore.close(totalsFromHost)` stamps `closed_at`, snapshots the summary and opens
   the next. Called from the Close Batch success path (`onHostTotalsReceived` with
   `response.isSuccess()` on the reset request).
3. **Report (pure)** — `atm/report/DetailReport`: takes the open batch, its approved
   withdrawals and the summary counts, returns the 32-column text. Pure Java, fixtures in
   unit tests (block layout, "--" auth, clerk/invoice line only when present, totals,
   zero-transaction batch, column alignment at $1,000.00+).

`MainActivity` glue: menu item → confirm dialog ("Print Detail Report — batch 003,
N withdrawals since 10/01 09:02") → build text on a worker thread → print, or show the
dialog when out of paper. "Batch #" also gets printed on the existing Close Batch receipt
so the two pieces of paper match.

## Report format (32 columns)

```
         DETAIL REPORT
================================
10/01/26 14:16:53  Batch #: 003
TID: MP001194  Opened 10/01 09:02
--------------------------------
****2803  CWDR DB  C    #00001
 AUTH 123456   REF 673100000012
 AMT     $5.00   FEE     $3.50
 TIP     $0.00   TOTAL   $8.50
--------------------------------
****1234  CWDR DB  T    #00002
 CLRK 7        INV POS-12345
 AUTH --       REF 673100000013
 AMT    $20.00   FEE     $3.50
 TIP     $0.00   TOTAL  $23.50
================================
SUMMARY  (DEBIT)
Withdrawals    4        $40.00
 Fees                   $14.00
 Tips                    $0.00
Total          4        $54.00
--------------------------------
Declined       2    Cancelled 1
Bal Inquiries  3    Reversed  0
================================
         END OF REPORT
```

- Line 1 of a block: card last four · `CWDR` · card type (`DB` for checking/savings, `CR`
  for credit) · entry mode (`C` chip, `T` tap, `S` swipe) · our sequence number.
- `CLRK` / `INV` line only when the sale came from the register with those fields.
- `AUTH --` when the host sent no auth code. `REF` is the host reference number (RRN).
- Amounts right-aligned in fixed columns; `Money.dollars` for every figure.
- If the batch has no approved withdrawals the detail section prints
  `  (no approved withdrawals)` and the summary shows zeros.
- A second summary group (`CREDIT`) prints only when a credit-account withdrawal exists.

## Data flow

```
host answer ──► MainActivity listener ──► TransactionJournal.record(ctx)
                                           │  (amount/fee/tip/total, last4, entry mode,
                                           │   acct type, seq, auth, RRN, rc, result,
                                           │   clerk/invoice if POS, batch_id = current)
Close Batch OK ─► BatchStore.close(totals) ─► next batch opened
Ellipsis ▸ Detail Report ─► DetailReport.render(batch, approved rows, counts) ─► print / dialog
Reversal accepted for seq N ─► journal row N marked reversed=1 (excluded from lines, counted)
```

## Edge cases

- **Approved then reversed.** Our reversals cover pre-response failures, but if a reversal
  for a sequence that is journaled as approved is accepted, the row is marked `reversed`
  and leaves the detail lines; the summary counts it under `Reversed`.
- **Power loss between host answer and journal write.** The journal write happens
  synchronously in the listener before the receipt is shown; a loss in that window loses
  the row (the host still has the transaction; Host Totals remains the processor's truth).
  Acceptable; noted on the report footer: "Terminal record — processor totals govern."
- **Close Batch fails.** No boundary is written; the report keeps accumulating; the
  existing error dialog stands.
- **Clock change.** Rows carry the batch id, not just a time, so a clock jump cannot move
  a transaction between batches.
- **Retention.** Keep the last 20 batches of rows (and their batch rows); older rows are
  deleted at each close. Admin "Clear History" (Super) clears rows of closed batches only,
  never the open one.
- **Reinstall from scratch** wipes the database like every other local record; an in-place
  update keeps it.

## Testing

- `DetailReportTest` (pure): fixtures for the layout above, alignment at large amounts,
  zero batch, clerk/invoice presence, auth `--`, credit group, reversed exclusion.
- `BatchStoreTest` / journal: tested against an in-memory SQLite is not available on the
  JVM here; the store logic that can be pure (next batch number, summary snapshot maths) is
  split out and unit-tested; the SQLite glue is exercised on the device.
- Device pass: walk-up withdrawal + POS sale + a decline + a BI → print the report → check
  every figure against the receipts; Close Batch → batch number increments on both papers;
  reprint after close shows an empty new batch.

## Out of scope

Tips (next item; the column is reserved), reprinting a previous batch (store supports it;
no menu entry yet), sending the report to MyView, the Admin "Transaction History" screen
itself (it stays as is).

## Delivery

Own release: **6.2.11**, PR #7 off `release/v6.2.10`, one commit per unit (journal, batches,
report, glue), release-notes entry, backlog ticket `RPT-01`.
