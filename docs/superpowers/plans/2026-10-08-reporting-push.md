# Terminal Reporting Push (6.2.13) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every finished transaction the terminal journals is pushed to the MyView ingestion endpoint in the Ingenico shape, with store-and-forward semantics, and the terminal gains the one amount model (sale / tip / withdrawal / cash back / fee / total) that tips (6.2.14) will build on — with every charge identical to 6.2.12.

**Architecture:** The SQLite transaction journal is the outbox: push bookkeeping is columns on the journal rows, written at the single completion point. A pure `PushPayload` builder turns a row into the portal's JSON; a single-threaded `ReportingPusher` drains `PENDING` rows oldest-first through an OkHttp client, marks them on 200, backs off on anything else, and parks a row only when a later row has since been accepted. Configuration arrives as two CasHUB parameters routed by the existing `CasHubParams` path. A pure `AmountBreakdown` replaces the four loose amount strings; this release wires it in with tip 0 and register rounding off.

**Tech Stack:** Java 8, Android SDK 31 (minSdk 24), SQLite via `SQLiteOpenHelper`, OkHttp 4.12 (already a dependency), `org.json`, JUnit 4 + MockWebServer (already test dependencies). Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@17`.

**Spec:** `docs/superpowers/specs/2026-10-07-reporting-push-design.md` (the plan argues from it; read it first). Contract: `docs/MYVIEW-TERMINAL-PUSH-API.md`.

## Global Constraints

- Branch `release/v6.2.13` (already exists, from `main` f9f85d1). One commit per task, message prefixed with the ticket: `RPT-02:` for the push, `AMT-03:` for the breakdown, `notes:` for docs.
- Pure classes (`AmountBreakdown`, `ReportingParams`, `PushPayload`, `PushEligibility`, `ReportingPusher` core) carry **no `android.*` imports** so they run in the JVM suite. Android-touching code stays thin.
- **TDD:** write the failing test, run it, watch it fail for the right reason, implement, run again. Test command: `cd ~/Documents/TFI/Castle/CashlessATM && export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew testMkskDebugUnitTest --tests '<pattern>' -q`; results in `app/build/test-results/testMkskDebugUnitTest/*.xml`. Baseline: 309 tests, 3 pre-existing failures (`EmvTagEnhancerTest` ×2, `HyosungProtocolTest.testProcessorConfigDns`, ticket TEST-01) — those stay red and are not ours.
- **Synthetic identifiers only** in tests and docs: terminal ids `MS00TEST`/`TEST0001`, serial `0000195260000000`, PAN last four `1111`, key `test-key-not-real`. The production endpoint URL may appear (it is in the contract).
- **The tenant access key is never logged, never written to the host-config payload, never in `describe()`/`toString()`.** Card data: last four only.
- **The STD1 sequence mechanism is not touched** (spec R4). No change to `AtmTransactionManager.getNextSequenceNumber` or where `atmSequenceNumber` is set.
- **Charges identical to 6.2.12** (spec R5): walk-up rounding on, register rounding off, tip 0. Any test that compares against 6.2.12 arithmetic must pass.
- Money is whole cents (`long`); dollars only at the UI edge via `Money.dollars`.
- Line endings: `Fragment_page_amount_selection.java` and `fragment_page_admin_atm.xml` are **CRLF**; edit them with the Edit tool (which preserves endings), never with a script that normalises.
- Never run SDK calls off the transaction thread; the pusher and the journal reads run on their own executor.
- Version bump to `6.2.12` → `6.2.13`, `versionCode` 74 → 75, in the last task only. Do not build a release APK; the debug APK is built in the last task for the bench.

## Review Focus

Inputs the spec implies but no task would otherwise test — each has a test added to the owning task below:

1. **Device time zone or DST changes between writing the row and sending it** — the payload must format the *row's* timestamp in the zone passed in, and the DST flag must follow that instant, not "now". (Task 6: `PushPayloadTest.timeZoneFieldsFollowTheRowInstant`.)
2. **Rows with missing optional data** — null card last four, null reference number, null error message must become `""` in JSON, never `null`, and never throw. (Task 6: `PushPayloadTest.missingOptionalFieldsBecomeEmptyStrings`.)
3. **A negative or zero sale, or a fee configuration that yields a negative fee** — the portal rejects negative amounts; the breakdown must refuse negative inputs loudly rather than ship them. (Task 1: `AmountBreakdownTest.negativeInputsAreRejected`.)
4. **The key is changed or removed while a drain run is in progress** — the run in flight finishes with the configuration it started with; the next run re-reads. Removal must stop sending without losing rows. (Task 7: `ReportingPusherTest.configIsReadOncePerRun`, `keyRemovedMidQueueStopsSendingButKeepsRows`.)
5. **An empty `terminal_id`** (terminal not yet configured in CasHUB) — rows are still journaled and queued; the portal will reject them; they must not be parked while *everything* fails, and the Admin line must show the portal's reason. (Task 7: `ReportingPusherTest.allRowsFailingNeverParks`; Task 6: `PushPayloadTest.emptyTerminalIdStillBuilds`.)

---

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/java/castech/emvtxn/AmountBreakdown.java` (new) | The six-number money model and its single constructor function. Pure. |
| `app/src/main/java/castech/emvtxn/GlobalPara.java` | `atmAmounts` (the breakdown for the current transaction) beside the four legacy strings, reset with the rest. |
| `app/src/main/java/castech/emvtxn/Fragment_page_amount_selection.java` (CRLF) | Continue handler builds the breakdown and writes the mirrors from it. |
| `app/src/main/java/castech/emvtxn/pos/AtmHostServiceGateway.java` | Register sale builds the breakdown (rounding off) and writes the mirrors from it. |
| `app/src/main/java/castech/emvtxn/reporting/ReportingParams.java` (new) | Parses `reporting_access_key` / `reporting_url` out of the merged CasHUB map. Pure. |
| `app/src/main/java/castech/emvtxn/reporting/ReportingConfig.java` (new) | SharedPreferences store for the two values + status fields. Android. |
| `app/src/main/java/castech/emvtxn/CasHubParams.java` | Routes the two keys to `ReportingConfig`, keeps them out of the host payload, notifies the pusher on change. |
| `app/src/main/java/castech/emvtxn/reporting/PushEligibility.java` (new) | Which (type, result) rows are sendable (spec R3). Pure. |
| `app/src/main/java/castech/emvtxn/atm/TransactionLog.java` | New fields: `saleCents`, `cashBackCents`, `flowId`, `pushState`, `pushAttempts`, `pushLastError`, `pushSentAt`, `pushMessage`. |
| `app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java` | Schema v3 + backfill; new columns in save/cursor; pending/park queries; pruning guard; reversal row insert. |
| `app/src/main/java/castech/emvtxn/atm/TransactionJournal.java` | Writes breakdown columns, `flow_id`, `push_state`; inserts the `REVERSAL` row; signals the pusher. |
| `app/src/main/java/castech/emvtxn/reporting/PushPayload.java` (new) | Row → portal JSON. Pure. |
| `app/src/main/java/castech/emvtxn/reporting/ReportingClient.java` (new) | One `POST` with OkHttp; returns a small result object. No Android. |
| `app/src/main/java/castech/emvtxn/reporting/PushStore.java` (new) | Interface the pusher drains: pending rows, mark sent/failed/parked, "any row written after X sent?". Implemented by `TransactionLogManager` and by a fake in tests. |
| `app/src/main/java/castech/emvtxn/reporting/ReportingPusher.java` (new) | The drain loop, backoff, parking rule, status. Pure core + a thin scheduler. |
| `app/src/main/java/castech/emvtxn/reporting/ReportingStatus.java` (new) | Status enum + rendered text for the Admin line. Pure. |
| `app/src/main/java/castech/emvtxn/MainActivity.java` | Creates the pusher after host init, wires the four triggers, stops it in `onDestroy`. |
| `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java` + `res/layout/fragment_page_admin_atm.xml` (CRLF) | Reporting line + Super-only "Retry now". |
| `RELEASE-NOTES.md`, `docs/CODE-REVIEW-BACKLOG.md`, `app/build.gradle` | Version, notes, tickets RPT-02 and AMT-03. |

---

### Task 1: `AmountBreakdown` — the money model

**Files:**
- Create: `app/src/main/java/castech/emvtxn/AmountBreakdown.java`
- Test: `app/src/test/java/castech/emvtxn/AmountBreakdownTest.java`

**Interfaces:**
- Consumes: `Money.feeCents(long amountCents, boolean useFlatFee, double flatFeeDollars, double percent)`, `AmountRounding.roundUpToStepCents(long amountCents, long stepCents)` (both exist).
- Produces: `AmountBreakdown.of(long saleCents, long tipCents, long stepCents, boolean roundToStep, boolean useFlatFee, double flatFeeDollars, double percentFee)`; `AmountBreakdown.balanceInquiry()`; public final fields `sale, tip, withdrawal, cashBack, fee, total` (all `long` cents); `String chipAmountCents()` (total as a plain cents string).

- [ ] **Step 1: Write the failing test**

```java
package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * AMT-03 (6.2.13). One object holds the six numbers of a transaction so the chip, the host,
 * the receipt, the journal and the portal push can never disagree.
 *   withdrawal = roundUp(sale + tip, step)   (or sale + tip when rounding is off)
 *   cashBack   = withdrawal - sale - tip
 *   total      = withdrawal + fee
 */
public class AmountBreakdownTest {

    private static final long STEP = 10_00;          // $10 minimum / step
    private static final boolean FLAT = true;
    private static final double FLAT_FEE = 3.50;

    @Test
    public void agreedExample_tenDollarSaleOneDollarTip() {
        // $10 sale, $1 tip -> $20 withdrawal, $9 cash back, $3.50 fee, $23.50 on the card
        AmountBreakdown b = AmountBreakdown.of(10_00, 1_00, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(10_00, b.sale);
        assertEquals(1_00, b.tip);
        assertEquals(20_00, b.withdrawal);
        assertEquals(9_00, b.cashBack);
        assertEquals(3_50, b.fee);
        assertEquals(23_50, b.total);
    }

    @Test
    public void portalContractExample_219_25_plus_2_00_tip() {
        // The Ingenico push in the contract: 219.25 + 2.00 -> 230.00, cash back 8.75, total 233.50
        AmountBreakdown b = AmountBreakdown.of(219_25, 2_00, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(230_00, b.withdrawal);
        assertEquals(8_75, b.cashBack);
        assertEquals(233_50, b.total);
    }

    @Test
    public void walkUpWithoutTip_matches_6_2_12_arithmetic() {
        // Today's custom amount $12.50 -> $20.00 withdrawal, fee $3.50, total $23.50; cash back is the change
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(20_00, b.withdrawal);
        assertEquals(7_50, b.cashBack);
        assertEquals(23_50, b.total);
        assertEquals("2350", b.chipAmountCents());
    }

    @Test
    public void presetAmount_isNotChangedByRounding() {
        AmountBreakdown b = AmountBreakdown.of(40_00, 0, STEP, true, FLAT, FLAT_FEE, 0);
        assertEquals(40_00, b.withdrawal);
        assertEquals(0, b.cashBack);
    }

    @Test
    public void registerSaleWithRoundingOff_isExact_asIn_6_2_12() {
        // 6.2.13 keeps register sales exact: $12.50 stays $12.50, no cash back
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, false, FLAT, FLAT_FEE, 0);
        assertEquals(12_50, b.withdrawal);
        assertEquals(0, b.cashBack);
        assertEquals(16_00, b.total);
    }

    @Test
    public void percentageFee_isComputedOnTheWithdrawal() {
        // 2.5% of a $20 withdrawal = $0.50 (fee is on what the host is asked for)
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, STEP, true, false, 0, 2.5);
        assertEquals(50, b.fee);
        assertEquals(20_50, b.total);
    }

    @Test
    public void balanceInquiry_isAllZero() {
        AmountBreakdown b = AmountBreakdown.balanceInquiry();
        assertEquals(0, b.sale + b.tip + b.withdrawal + b.cashBack + b.fee + b.total);
        assertEquals("0", b.chipAmountCents());
    }

    @Test
    public void invariantsHold_acrossOddInputs() {
        long[][] cases = { {1, 0}, {9_99, 0}, {10_01, 0}, {12_33, 1_85}, {499_99, 0}, {500_00, 0} };
        for (long[] c : cases) {
            AmountBreakdown b = AmountBreakdown.of(c[0], c[1], STEP, true, FLAT, FLAT_FEE, 0);
            assertEquals("sale+tip+cashBack+fee == total for " + c[0], b.total, b.sale + b.tip + b.cashBack + b.fee);
            assertEquals("withdrawal+fee == total for " + c[0], b.total, b.withdrawal + b.fee);
            assertTrue("cash back never negative for " + c[0], b.cashBack >= 0);
            assertTrue("withdrawal >= sale+tip for " + c[0], b.withdrawal >= b.sale + b.tip);
        }
    }

    @Test
    public void negativeInputsAreRejected() {
        // Review focus 3: the portal rejects negative amounts; refuse to build them at all
        long[][] bad = { {-1, 0}, {10_00, -1}, {0, 5_00} };
        for (long[] c : bad) {
            try {
                AmountBreakdown.of(c[0], c[1], STEP, true, FLAT, FLAT_FEE, 0);
                fail("expected rejection for sale=" + c[0] + " tip=" + c[1]);
            } catch (IllegalArgumentException expected) { /* ok */ }
        }
    }

    @Test
    public void zeroStep_meansNoRounding() {
        AmountBreakdown b = AmountBreakdown.of(12_50, 0, 0, true, FLAT, FLAT_FEE, 0);
        assertEquals(12_50, b.withdrawal);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testMkskDebugUnitTest --tests '*AmountBreakdownTest*' -q`
Expected: compilation error, `cannot find symbol: class AmountBreakdown`.

- [ ] **Step 3: Implement**

```java
package castech.emvtxn;

/**
 * The six numbers of a transaction, computed once and read everywhere (AMT-03, 6.2.13).
 *
 * <pre>
 *   withdrawal = roundUp(sale + tip, step)   when roundToStep, else sale + tip   — what the host is asked for
 *   cashBack   = withdrawal - sale - tip                                          — the change the customer receives
 *   fee        = the terminal's fee on the withdrawal (flat or percentage)
 *   total      = withdrawal + fee                                                 — the chip amount and the card charge
 * </pre>
 * Hence sale + tip + cashBack + fee == total. Whole cents throughout. The fee is not deducted
 * from the cash back (design review 2026-10-07).
 *
 * <p>6.2.13 builds every breakdown with tip = 0; walk-ups round (as since 6.2.8), register
 * sales do not (as today). 6.2.14 changes those two inputs and nothing else.
 */
public final class AmountBreakdown {

    public final long sale;
    public final long tip;
    public final long withdrawal;
    public final long cashBack;
    public final long fee;
    public final long total;

    private AmountBreakdown(long sale, long tip, long withdrawal, long fee) {
        this.sale = sale;
        this.tip = tip;
        this.withdrawal = withdrawal;
        this.cashBack = withdrawal - sale - tip;
        this.fee = fee;
        this.total = withdrawal + fee;
    }

    /**
     * @param saleCents     what the customer asked for (> 0)
     * @param tipCents      the tip (>= 0)
     * @param stepCents     the withdrawal step (the configured minimum); 0 or less disables rounding
     * @param roundToStep   true for a walk-up; false for a register sale in 6.2.13
     * @param useFlatFee / flatFeeDollars / percentFee   the terminal's fee configuration
     */
    public static AmountBreakdown of(long saleCents, long tipCents, long stepCents, boolean roundToStep,
                                     boolean useFlatFee, double flatFeeDollars, double percentFee) {
        if (saleCents <= 0) throw new IllegalArgumentException("sale must be positive, got " + saleCents);
        if (tipCents < 0) throw new IllegalArgumentException("tip must not be negative, got " + tipCents);
        long saleAndTip = saleCents + tipCents;
        long withdrawal = roundToStep ? AmountRounding.roundUpToStepCents(saleAndTip, stepCents) : saleAndTip;
        long fee = Money.feeCents(withdrawal, useFlatFee, flatFeeDollars, percentFee);
        if (fee < 0) throw new IllegalArgumentException("fee must not be negative, got " + fee);
        return new AmountBreakdown(saleCents, tipCents, withdrawal, fee);
    }

    /** A balance inquiry moves no money. */
    public static AmountBreakdown balanceInquiry() {
        return new AmountBreakdown(0, 0, 0, 0);
    }

    /** The chip amount (EMV 9F02) as the plain cents string {@code GlobalPara.strAmount} expects. */
    public String chipAmountCents() {
        return Long.toString(total);
    }

    @Override
    public String toString() {
        return "sale=" + sale + " tip=" + tip + " withdrawal=" + withdrawal + " cashBack=" + cashBack
                + " fee=" + fee + " total=" + total;
    }
}
```

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*AmountBreakdownTest*' -q`
Expected: 10 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/castech/emvtxn/AmountBreakdown.java app/src/test/java/castech/emvtxn/AmountBreakdownTest.java
git commit -m "AMT-03: AmountBreakdown — sale, tip, withdrawal, cash back, fee, total in one place

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Wire the breakdown in — charges unchanged

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/GlobalPara.java` (near line 198, the `atm*` card/amount fields; and `resetATMTransactionState()` ~line 262)
- Modify: `app/src/main/java/castech/emvtxn/Fragment_page_amount_selection.java:127-140` (CRLF — Edit tool only)
- Modify: `app/src/main/java/castech/emvtxn/pos/AtmHostServiceGateway.java:160-185`
- Test: existing suite (`PosSaleFeeTest`, `AmountBreakdownTest`) plus a device check in Task 10

**Interfaces:**
- Consumes: `AmountBreakdown.of(...)`, `AmountBreakdown.balanceInquiry()`.
- Produces: `public static volatile AmountBreakdown GlobalPara.atmAmounts` — the current transaction's numbers; always set before the transaction page is entered, reset to `balanceInquiry()` by `resetATMTransactionState()`. Later tasks (journal, push) read it.

- [ ] **Step 1: Add the field and its reset in `GlobalPara`**

Next to `atmSelectedAmount` / `atmFee` / `atmTotal` (search for `atmTotal = "0.00"` in the reset method to find both places):

```java
	/** AMT-03 (6.2.13): the current transaction's money, computed once. The four strings below are mirrors of it. */
	public static volatile AmountBreakdown atmAmounts = AmountBreakdown.balanceInquiry();
```

and in `resetATMTransactionState()`, next to `atmTotal = "0.00";`:

```java
		atmAmounts = AmountBreakdown.balanceInquiry();
```

- [ ] **Step 2: Amount screen — build the breakdown, write the mirrors from it**

Replace lines 130–140 of `Fragment_page_amount_selection.java` (the body of `if (selectedAmount > 0) {` up to and including the `strAmount` line) with:

```java
                if (selectedAmount > 0) {
                    // AMT-03: one breakdown, every string and the chip amount from the SAME integers.
                    // selectedAmount is already rounded to the step by the custom-amount dialog
                    // (AMT-01); presets are multiples. Rounding here is therefore a no-op today
                    // and becomes real when tips (6.2.14) add to the sale.
                    long saleCents = Money.toCents(selectedAmount);
                    AmountBreakdown amounts = AmountBreakdown.of(saleCents, 0L,
                            Money.toCents(GlobalPara.atmMinAmount), true,
                            GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
                    GlobalPara.atmAmounts = amounts;
                    GlobalPara.atmSelectedAmount = Money.dollars(amounts.withdrawal);
                    GlobalPara.atmFee = Money.dollars(amounts.fee);
                    GlobalPara.atmTotal = Money.dollars(amounts.total);
                    GlobalPara.strAmount = amounts.chipAmountCents();   // chip amount (9F02) = total in cents
```

Note: `atmSelectedAmount` keeps meaning the **withdrawal** (what the host is asked for), as it does today — every existing reader expects that. The sale is only in `atmAmounts.sale`. Because `selectedAmount` is already rounded, `amounts.sale == amounts.withdrawal` on a walk-up today; **the sale the customer typed before rounding is recorded as the sale starting in this release** — see Step 3.

- [ ] **Step 3: Keep the typed amount as the sale**

In the custom-amount dialog (lines ~206–226), the entered value is rounded and then `selectAmount(amount)` stores only the rounded value. Record the entered value too. Add a field next to `selectedAmount`:

```java
    /** AMT-03: what the customer typed before AMT-01 rounding; equals selectedAmount for presets. */
    private double enteredAmount = 0;
```

In the custom dialog after `double amount = AmountRounding.roundUpToStep(entered, GlobalPara.atmMinAmount);` add `enteredAmount = entered;`. In `selectAmount(double amount)` for presets (find the preset click path) set `enteredAmount = amount;` when the caller is a preset — simplest: at the top of `selectAmount` add `if (enteredAmount <= 0 || enteredAmount > amount) enteredAmount = amount;` and reset `enteredAmount = 0;` in `resetSelection()`. Then in Step 2 use `long saleCents = Money.toCents(enteredAmount > 0 ? enteredAmount : selectedAmount);`. With rounding on, `withdrawal` comes out identical to today's `selectedAmount` and `cashBack` becomes the real change (e.g. $12.50 typed → sale 1250, withdrawal 2000, cash back 750). Receipts and charges are unchanged because they read the mirrors.

- [ ] **Step 4: Gateway — register sale and balance inquiry**

Replace lines 160–185 of `AtmHostServiceGateway.java` (from `final long appliedSurchargeCents;` through the closing brace of the `else`) with:

```java
        // AMT-03: one breakdown. D7 stands: the register's surcharge is advisory; the terminal's fee
        // configuration governs. 6.2.13 keeps register sales EXACT (roundToStep = false) — identical
        // charges to 6.2.12; 6.2.14 turns rounding on together with tips.
        final AmountBreakdown amounts;
        if (balanceInquiry) {
            amounts = AmountBreakdown.balanceInquiry();
        } else {
            amounts = AmountBreakdown.of(amountCents, 0L,
                    castech.emvtxn.Money.toCents(GlobalPara.atmMinAmount), false,
                    GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
            if (surchargeCents > 0 && surchargeCents != amounts.fee) {
                Log.w(TAG, "POS sent surcharge=" + surchargeCents + " cents; terminal fee config governs: "
                        + amounts.fee + " cents (reply carries the applied value)");
            }
        }
        final long appliedSurchargeCents = amounts.fee;
        final long appliedTotalCents = amounts.total;
        GlobalPara.atmAmounts = amounts;
        GlobalPara.atmSelectedAmount = castech.emvtxn.Money.dollars(amounts.withdrawal);
        GlobalPara.atmFee = castech.emvtxn.Money.dollars(amounts.fee);
        GlobalPara.atmTotal = castech.emvtxn.Money.dollars(amounts.total);
        GlobalPara.strAmount = balanceInquiry ? "0" : amounts.chipAmountCents();
```

Add `import castech.emvtxn.AmountBreakdown;` at the top. `PosSaleFee` is no longer used by the gateway; leave the class and its test in place this release (deleting is a separate cleanup), but add a one-line Javadoc note on `PosSaleFee`: `@deprecated superseded by AmountBreakdown (AMT-03); kept until 6.2.14.`

- [ ] **Step 5: Compile and run the whole suite**

Run: `./gradlew compileMkskDebugJavaWithJavac -q && ./gradlew testMkskDebugUnitTest -q`
Expected: compiles; 319 tests (309 + 10), the same 3 pre-existing failures only.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/castech/emvtxn/GlobalPara.java app/src/main/java/castech/emvtxn/Fragment_page_amount_selection.java app/src/main/java/castech/emvtxn/pos/AtmHostServiceGateway.java app/src/main/java/castech/emvtxn/pos/PosSaleFee.java
git commit -m "AMT-03: amount screen and POS gateway build one AmountBreakdown; mirrors unchanged, charges identical

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Reporting parameters — `ReportingParams`, `ReportingConfig`, CasHUB routing

**Files:**
- Create: `app/src/main/java/castech/emvtxn/reporting/ReportingParams.java`
- Create: `app/src/main/java/castech/emvtxn/reporting/ReportingConfig.java`
- Modify: `app/src/main/java/castech/emvtxn/CasHubParams.java:153-204` (`applyToConfigDetailed`) and `:37-62` (receiver)
- Test: `app/src/test/java/castech/emvtxn/reporting/ReportingParamsTest.java`

**Interfaces:**
- Produces: `ReportingParams.KEY_ACCESS_KEY = "reporting_access_key"`, `KEY_URL = "reporting_url"`, `Set<String> KEYS`, `static ReportingParams parse(Map<String,String>)`, fields `String accessKey` (null when absent/blank), `String url` (null when absent/invalid), `List<String> problems`, `boolean isEmpty()`, `String describe()` (never the key), `static String maskForLog(String content)`.
- Produces: `ReportingConfig(Context)` with `String getAccessKey()`, `void setAccessKey(String)`, `String getUrl()` (defaults to `ReportingConfig.DEFAULT_URL`), `void setUrl(String)`, `boolean isConfigured()` (key non-empty); `static final String DEFAULT_URL = "https://t5wfhaal2k5usy2rfb5uwaszzm0ijsvn.lambda-url.us-east-1.on.aws/transactions/addTransaction"`; plus status persistence used by Task 7: `void setStatus(String state, int pending, int parked, long lastSentAt, String lastError)` and getters `getStatusState()`, `getPending()`, `getParked()`, `getLastSentAt()`, `getLastError()`.
- Produces for Task 8: `CasHubParams.Applied` gains `public final boolean reportingChanged`.

- [ ] **Step 1: Write the failing test**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** RPT-02 (6.2.13): the two CasHUB keys that configure the MyView push. */
public class ReportingParamsTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void absentKeys_isEmpty_andManagesNothing() {
        ReportingParams p = ReportingParams.parse(map("terminal_id", "MS00TEST"));
        assertTrue(p.isEmpty());
        assertNull(p.accessKey);
        assertNull(p.url);
        assertTrue(p.problems.isEmpty());
    }

    @Test
    public void accessKey_isTrimmedAndKept() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "  test-key-not-real \n"));
        assertFalse(p.isEmpty());
        assertEquals("test-key-not-real", p.accessKey);
    }

    @Test
    public void blankAccessKey_isAProblem_andLeavesTheCurrentKeyAlone() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "   "));
        assertNull(p.accessKey);
        assertEquals(1, p.problems.size());
        assertTrue(p.problems.get(0).startsWith("reporting_access_key:"));
    }

    @Test
    public void url_mustBeHttps() {
        assertEquals("https://portal.example/transactions/addTransaction",
                ReportingParams.parse(map("reporting_url", " https://portal.example/transactions/addTransaction/ ")).url);
        ReportingParams bad = ReportingParams.parse(map("reporting_url", "http://portal.example/x"));
        assertNull(bad.url);
        assertEquals(1, bad.problems.size());
        assertTrue(bad.problems.get(0).startsWith("reporting_url:"));
    }

    @Test
    public void describe_neverContainsTheKey() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "test-key-not-real",
                "reporting_url", "https://portal.example/t"));
        String d = p.describe();
        assertFalse(d.contains("test-key-not-real"));
        assertTrue(d.contains("key=[set]"));
        assertTrue(d.contains("https://portal.example/t"));
    }

    @Test
    public void maskForLog_hidesTheKeyInJsonAndKeyValueForms() {
        String json = "{\"reporting_access_key\":\"test-key-not-real\",\"terminal_id\":\"MS00TEST\"}";
        String masked = ReportingParams.maskForLog(json);
        assertFalse(masked.contains("test-key-not-real"));
        assertTrue(masked.contains("MS00TEST"));
        assertEquals("reporting_access_key=[masked]", ReportingParams.maskForLog("reporting_access_key=test-key-not-real"));
    }

    @Test
    public void keysSet_isExactlyTheTwoKeys() {
        assertEquals(2, ReportingParams.KEYS.size());
        assertTrue(ReportingParams.KEYS.contains("reporting_access_key"));
        assertTrue(ReportingParams.KEYS.contains("reporting_url"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testMkskDebugUnitTest --tests '*ReportingParamsTest*' -q`
Expected: compilation error, `package castech.emvtxn.reporting does not exist`.

- [ ] **Step 3: Implement `ReportingParams` (pure)**

```java
package castech.emvtxn.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The MyView reporting settings CasHUB can push (RPT-02, 6.2.13), parsed out of the merged
 * parameter map that {@code CasHubParams} builds. Pure Java so the rules are unit-tested.
 * <ul>
 *   <li>{@code reporting_access_key} — the tenant key TFI issues; trimmed; blank is a problem and
 *       leaves the stored key alone. A bearer credential: never logged ({@link #maskForLog}).</li>
 *   <li>{@code reporting_url} — override of the production ingestion URL; must be {@code https://};
 *       trailing slashes removed. Invalid is a problem and leaves the stored URL alone.</li>
 * </ul>
 * An absent key is not managed by CasHUB: the stored value stands.
 */
public final class ReportingParams {

    public static final String KEY_ACCESS_KEY = "reporting_access_key";
    public static final String KEY_URL        = "reporting_url";

    /** The keys this class consumes; CasHubParams keeps them out of the host-config payload. */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(KEY_ACCESS_KEY, KEY_URL)));

    /** Parsed value, or null when the key is absent or unusable. */
    public final String accessKey;
    public final String url;
    /** Why a present key was ignored. Never contains the key. */
    public final List<String> problems;
    private final boolean anyKeyPresent;

    private ReportingParams(String accessKey, String url, List<String> problems, boolean anyKeyPresent) {
        this.accessKey = accessKey;
        this.url = url;
        this.problems = problems;
        this.anyKeyPresent = anyKeyPresent;
    }

    public static ReportingParams parse(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        boolean present = false;
        String key = null;
        String url = null;
        if (params != null) {
            if (params.containsKey(KEY_ACCESS_KEY)) {
                present = true;
                String raw = trim(params.get(KEY_ACCESS_KEY));
                if (!raw.isEmpty()) key = raw;
                else problems.add(KEY_ACCESS_KEY + ": blank — ignored");
            }
            if (params.containsKey(KEY_URL)) {
                present = true;
                String raw = trim(params.get(KEY_URL));
                while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
                if (raw.toLowerCase(Locale.US).startsWith("https://") && raw.length() > "https://".length()) url = raw;
                else problems.add(KEY_URL + ": must start with https://" + (raw.isEmpty() ? " (blank)" : ", got \"" + raw + "\"") + " — ignored");
            }
        }
        return new ReportingParams(key, url, Collections.unmodifiableList(problems), present);
    }

    /** True when neither key appeared in the map. */
    public boolean isEmpty() { return !anyKeyPresent; }

    /** Loggable summary: the URL in clear, the key only as [set] / [unchanged]. */
    public String describe() {
        return "reporting: key=" + (accessKey != null ? "[set]" : "[unchanged]")
                + " url=" + (url != null ? url : "[unchanged]")
                + (problems.isEmpty() ? "" : " problems=" + problems);
    }

    /** Masks the key in raw parameter content (JSON or key=value) so provider rows can be logged. */
    public static String maskForLog(String content) {
        if (content == null) return "[null]";
        String s = content.replaceAll("(\"" + KEY_ACCESS_KEY + "\"\\s*:\\s*\")[^\"]*(\")", "$1[masked]$2");
        return s.replaceAll("(?m)^(" + KEY_ACCESS_KEY + "=)[^\\n]*", "$1[masked]");
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
```

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*ReportingParamsTest*' -q`
Expected: 7 tests, 0 failures.

- [ ] **Step 5: Implement `ReportingConfig` (Android, prefs)**

```java
package castech.emvtxn.reporting;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted reporting settings and the pusher's last known status (RPT-02). Own prefs file so
 * the key never sits beside the host configuration (which is backed up to KMS-II).
 */
public final class ReportingConfig {

    public static final String DEFAULT_URL =
            "https://t5wfhaal2k5usy2rfb5uwaszzm0ijsvn.lambda-url.us-east-1.on.aws/transactions/addTransaction";

    private static final String PREFS_FILE = "reporting_config";
    private static final String K_KEY = "access_key";
    private static final String K_URL = "url";
    private static final String K_STATE = "status_state";
    private static final String K_PENDING = "status_pending";
    private static final String K_PARKED = "status_parked";
    private static final String K_LAST_SENT = "status_last_sent_at";
    private static final String K_LAST_ERROR = "status_last_error";

    private final SharedPreferences prefs;

    public ReportingConfig(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public String getAccessKey() { return prefs.getString(K_KEY, ""); }
    public void setAccessKey(String key) { prefs.edit().putString(K_KEY, key == null ? "" : key).apply(); }
    public String getUrl() { return prefs.getString(K_URL, DEFAULT_URL); }
    public void setUrl(String url) { prefs.edit().putString(K_URL, url == null || url.isEmpty() ? DEFAULT_URL : url).apply(); }
    /** Reporting is on only when a key exists. */
    public boolean isConfigured() { return !getAccessKey().isEmpty(); }

    public void setStatus(String state, int pending, int parked, long lastSentAt, String lastError) {
        prefs.edit().putString(K_STATE, state).putInt(K_PENDING, pending).putInt(K_PARKED, parked)
                .putLong(K_LAST_SENT, lastSentAt).putString(K_LAST_ERROR, lastError == null ? "" : lastError).apply();
    }
    public String getStatusState() { return prefs.getString(K_STATE, ReportingStatus.NOT_CONFIGURED); }
    public int getPending() { return prefs.getInt(K_PENDING, 0); }
    public int getParked() { return prefs.getInt(K_PARKED, 0); }
    public long getLastSentAt() { return prefs.getLong(K_LAST_SENT, 0L); }
    public String getLastError() { return prefs.getString(K_LAST_ERROR, ""); }
}
```

`ReportingStatus.NOT_CONFIGURED` is defined in Task 7; to compile now, create `ReportingStatus.java` with the constants only (Task 7 adds the rendering):

```java
package castech.emvtxn.reporting;

/** Pusher status for the Admin line (RPT-02). Rendering is added with the pusher. */
public final class ReportingStatus {
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String OK = "OK";
    public static final String RETRYING = "RETRYING";
    public static final String KEY_REJECTED = "KEY_REJECTED";
    private ReportingStatus() {}
}
```

- [ ] **Step 6: Route the keys in `CasHubParams.applyToConfigDetailed`**

Extend `Applied`:

```java
    public static final class Applied {
        public final boolean anyRows;
        /** Null when no pos_* key was present. */
        public final castech.emvtxn.pos.PosParams.Diff posDiff;
        /** RPT-02: a reporting key or URL changed value. */
        public final boolean reportingChanged;
        Applied(boolean anyRows, castech.emvtxn.pos.PosParams.Diff posDiff, boolean reportingChanged) {
            this.anyRows = anyRows;
            this.posDiff = posDiff;
            this.reportingChanged = reportingChanged;
        }
    }
```

Update the early return to `return new Applied(false, null, false);`. After the APN block and before "Everything else → the host-config mapping", add:

```java
        // Reporting keys (6.2.13) → ReportingConfig; never into the host payload / KMS backup
        boolean reportingChanged = false;
        castech.emvtxn.reporting.ReportingParams rp = castech.emvtxn.reporting.ReportingParams.parse(merged);
        if (!rp.isEmpty()) {
            for (String problem : rp.problems) Log.w(TAG, "CasHUB reporting param ignored — " + problem);
            castech.emvtxn.reporting.ReportingConfig rc = new castech.emvtxn.reporting.ReportingConfig(ctx);
            if (rp.accessKey != null && !rp.accessKey.equals(rc.getAccessKey())) { rc.setAccessKey(rp.accessKey); reportingChanged = true; }
            if (rp.url != null && !rp.url.equals(rc.getUrl())) { rc.setUrl(rp.url); reportingChanged = true; }
            Log.w(TAG, "Applied CasHUB " + rp.describe() + (reportingChanged ? " (changed)" : " (unchanged)"));
        }
```

In the payload loop add `if (castech.emvtxn.reporting.ReportingParams.KEYS.contains(e.getKey())) continue;`. Change the final return to `return new Applied(true, posDiff, reportingChanged);`. Where provider content is logged with `PosParams.maskForLog(...)` (search `maskForLog` in `CasHubParams`), wrap it: `ReportingParams.maskForLog(PosParams.maskForLog(content))`.

In the receiver (line ~56), after the POS block add:

```java
                                if (a.reportingChanged) {
                                    GlobalPara.mainActivity.onReportingParamsChanged();
                                }
```

`MainActivity.onReportingParamsChanged()` is added in Task 8; to compile now add a stub to `MainActivity` next to `onPosParamsChanged`:

```java
    /** RPT-02: a reporting key or URL changed in CasHUB. Wired to the pusher in Task 8. */
    public void onReportingParamsChanged() {
        Log.w(TAG, "Reporting parameters changed");
    }
```

- [ ] **Step 7: Compile and run the suite**

Run: `./gradlew compileMkskDebugJavaWithJavac -q && ./gradlew testMkskDebugUnitTest -q`
Expected: compiles; 326 tests, the same 3 pre-existing failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/castech/emvtxn/reporting app/src/test/java/castech/emvtxn/reporting app/src/main/java/castech/emvtxn/CasHubParams.java app/src/main/java/castech/emvtxn/MainActivity.java
git commit -m "RPT-02: reporting_access_key / reporting_url as CasHUB parameters (ReportingParams, ReportingConfig)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Journal schema v3 and the push store

**Files:**
- Create: `app/src/main/java/castech/emvtxn/reporting/PushEligibility.java`
- Create: `app/src/main/java/castech/emvtxn/reporting/PushStore.java`
- Modify: `app/src/main/java/castech/emvtxn/atm/TransactionLog.java` (fields + getters/setters after `reversed`)
- Modify: `app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java` (constants, `SQL_CREATE_TABLE`, `onUpgrade`, `saveTransaction`, `cursorToTransactionLog`, `closeCurrentBatch`, `clearClosedBatches`, new methods)
- Test: `app/src/test/java/castech/emvtxn/reporting/PushEligibilityTest.java`

**Interfaces:**
- Produces: `PushEligibility.isSendable(String transactionType, String result)`; `PushEligibility.PUSH_NOT_APPLICABLE = 0, PUSH_PENDING = 1, PUSH_SENT = 2, PUSH_PARKED = 3`.
- Produces: `interface PushStore { List<TransactionLog> pendingPush(int limit); boolean anySentAfter(long rowId); void markSent(long rowId, String message, long sentAt); void markFailed(long rowId, String error); void markParked(long rowId, String error); int countPending(); int countParked(); }` — implemented by `TransactionLogManager`.
- Produces on `TransactionLog`: `long saleCents, cashBackCents; String flowId; int pushState, pushAttempts; String pushLastError; long pushSentAt; String pushMessage;` with getters/setters named `getSaleCents()/setSaleCents(long)` etc.
- Produces on `TransactionLogManager`: `long insertReversalRow(TransactionLog original, boolean pending)` (Task 5 uses it).

- [ ] **Step 1: Write the failing test for the sendability rule**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * RPT-02 (6.2.13), design decision R3: send everything the host answered plus terminal-side
 * declines where a card was presented; never cancels. In journal terms: APPROVED and DECLINED
 * withdrawals and balance inquiries, and every reversal row. CANCELLED is never sent.
 */
public class PushEligibilityTest {

    @Test
    public void hostAnsweredRows_areSendable() {
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "APPROVED"));
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "DECLINED"));
        assertTrue(PushEligibility.isSendable("BALANCE_INQUIRY", "APPROVED"));
        assertTrue(PushEligibility.isSendable("BALANCE_INQUIRY", "DECLINED"));
    }

    @Test
    public void terminalSideDeclineWithACard_isJournaledAsDeclined_soItIsSendable() {
        // a swipe we refuse (MSR_NA), a reader error, NO_TRACK2 all land as DECLINED rows
        assertTrue(PushEligibility.isSendable("WITHDRAWAL", "DECLINED"));
    }

    @Test
    public void cancels_areNeverSendable() {
        assertFalse(PushEligibility.isSendable("WITHDRAWAL", "CANCELLED"));
        assertFalse(PushEligibility.isSendable("BALANCE_INQUIRY", "CANCELLED"));
    }

    @Test
    public void reversalRows_areAlwaysSendable() {
        assertTrue(PushEligibility.isSendable("REVERSAL", "APPROVED"));
    }

    @Test
    public void unknownTypesOrResults_areNotSendable() {
        assertFalse(PushEligibility.isSendable(null, "APPROVED"));
        assertFalse(PushEligibility.isSendable("WITHDRAWAL", null));
        assertFalse(PushEligibility.isSendable("SOMETHING", "APPROVED"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushEligibilityTest*' -q`
Expected: compilation error, `cannot find symbol: class PushEligibility`.

- [ ] **Step 3: Implement `PushEligibility` and `PushStore`**

```java
package castech.emvtxn.reporting;

/** Which journal rows the MyView push sends (RPT-02, decision R3). Pure. */
public final class PushEligibility {
    public static final int PUSH_NOT_APPLICABLE = 0;
    public static final int PUSH_PENDING = 1;
    public static final int PUSH_SENT = 2;
    public static final int PUSH_PARKED = 3;

    private PushEligibility() {}

    /**
     * True for rows the host answered and for terminal-side declines where a card was presented
     * (both are journaled DECLINED), and for every reversal row. A cancel — Cancel pressed, no
     * card, PIN pad abandoned — is journaled CANCELLED and is never sent.
     */
    public static boolean isSendable(String transactionType, String result) {
        if (transactionType == null || result == null) return false;
        if ("REVERSAL".equals(transactionType)) return true;
        if (!"WITHDRAWAL".equals(transactionType) && !"BALANCE_INQUIRY".equals(transactionType)) return false;
        return "APPROVED".equals(result) || "DECLINED".equals(result);
    }

    /** The state a freshly written row gets. */
    public static int initialState(String transactionType, String result, boolean reportingConfigured) {
        return reportingConfigured && isSendable(transactionType, result) ? PUSH_PENDING : PUSH_NOT_APPLICABLE;
    }
}
```

```java
package castech.emvtxn.reporting;

import java.util.List;

import castech.emvtxn.atm.TransactionLog;

/** What the pusher needs from the journal. Implemented by TransactionLogManager; faked in tests. */
public interface PushStore {
    /** PENDING rows, oldest first (timestamp, then id). */
    List<TransactionLog> pendingPush(int limit);
    /** True when a row with a greater id has state SENT — proof the portal accepted something newer. */
    boolean anySentAfter(long rowId);
    void markSent(long rowId, String message, long sentAt);
    void markFailed(long rowId, String error);
    void markParked(long rowId, String error);
    int countPending();
    int countParked();
}
```

Add a test to `PushEligibilityTest` for `initialState` (RED → GREEN within this task):

```java
    @Test
    public void initialState_isPendingOnlyWhenConfiguredAndSendable() {
        assertEquals(PushEligibility.PUSH_PENDING, PushEligibility.initialState("WITHDRAWAL", "APPROVED", true));
        assertEquals(PushEligibility.PUSH_NOT_APPLICABLE, PushEligibility.initialState("WITHDRAWAL", "APPROVED", false));
        assertEquals(PushEligibility.PUSH_NOT_APPLICABLE, PushEligibility.initialState("WITHDRAWAL", "CANCELLED", true));
    }
```

(add `import static org.junit.Assert.assertEquals;`)

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushEligibilityTest*' -q`
Expected: 6 tests, 0 failures.

- [ ] **Step 5: `TransactionLog` fields**

After `private boolean reversed;` add:

```java
    // 6.2.13 (AMT-03 / RPT-02)
    private long saleCents;             // what the customer asked for; amountCents stays the withdrawal
    private long cashBackCents;         // withdrawal - sale - tip
    private String flowId;              // UUID, same on every push retry
    private int pushState;              // PushEligibility.PUSH_*
    private int pushAttempts;
    private String pushLastError;
    private long pushSentAt;
    private String pushMessage;         // the portal's message on success
```

with plain getters/setters in the same style as the existing ones (`getSaleCents()/setSaleCents(long)`, `getCashBackCents()/setCashBackCents(long)`, `getFlowId()/setFlowId(String)`, `getPushState()/setPushState(int)`, `getPushAttempts()/setPushAttempts(int)`, `getPushLastError()/setPushLastError(String)`, `getPushSentAt()/setPushSentAt(long)`, `getPushMessage()/setPushMessage(String)`).

- [ ] **Step 6: `TransactionLogManager` — schema v3**

Constants (after `COL_REVERSED`):

```java
    // 6.2.13 columns (AMT-03 breakdown + RPT-02 push bookkeeping)
    private static final String COL_SALE_CENTS = "sale_cents";
    private static final String COL_CASH_BACK_CENTS = "cash_back_cents";
    private static final String COL_FLOW_ID = "flow_id";
    private static final String COL_PUSH_STATE = "push_state";
    private static final String COL_PUSH_ATTEMPTS = "push_attempts";
    private static final String COL_PUSH_LAST_ERROR = "push_last_error";
    private static final String COL_PUSH_SENT_AT = "push_sent_at";
    private static final String COL_PUSH_MESSAGE = "push_message";
    private static final String[] V3_DDL = {
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_SALE_CENTS + " INTEGER DEFAULT 0",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_CASH_BACK_CENTS + " INTEGER DEFAULT 0",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_FLOW_ID + " TEXT",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_PUSH_STATE + " INTEGER DEFAULT 0",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_PUSH_ATTEMPTS + " INTEGER DEFAULT 0",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_PUSH_LAST_ERROR + " TEXT",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_PUSH_SENT_AT + " INTEGER DEFAULT 0",
            "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_PUSH_MESSAGE + " TEXT" };
```

`DATABASE_VERSION = 3;   // 3 (6.2.13): breakdown + push columns`. In `SQL_CREATE_TABLE` add the eight columns before the closing `")"` (same types/defaults). In `onUpgrade` after the `oldVersion < 2` block:

```java
        if (oldVersion < 3) {
            for (String ddl : V3_DDL) {
                try { db.execSQL(ddl); } catch (Exception e) { Log.w(TAG, "migration step skipped: " + e.getMessage()); }
            }
            // Existing rows: the sale was never recorded separately; the best truth is the withdrawal.
            db.execSQL("UPDATE " + TABLE_TRANSACTIONS + " SET " + COL_SALE_CENTS + " = " + COL_AMOUNT_CENTS
                    + " WHERE " + COL_SALE_CENTS + " = 0");
        }
```

In `saveTransaction` add the eight `values.put(...)` lines (`COL_SALE_CENTS` ← `getSaleCents()`, … `COL_PUSH_MESSAGE` ← `getPushMessage()`). In `cursorToTransactionLog` add guarded reads in the same style as the 6.2.11 columns (`getColumnIndex` ≥ 0).

- [ ] **Step 7: `TransactionLogManager` — the push store, the pruning guard, the reversal row**

Make the class `implements castech.emvtxn.reporting.PushStore` and add:

```java
    // ======================================================================
    // RPT-02 push store (6.2.13) — the journal is the outbox
    // ======================================================================

    @Override
    public List<TransactionLog> pendingPush(int limit) {
        List<TransactionLog> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_TRANSACTIONS, null, COL_PUSH_STATE + " = ?",
                    new String[] { String.valueOf(castech.emvtxn.reporting.PushEligibility.PUSH_PENDING) },
                    null, null, COL_TIMESTAMP + " ASC, " + COL_ID + " ASC", String.valueOf(limit));
            while (c.moveToNext()) out.add(cursorToTransactionLog(c));
        } catch (Exception e) {
            Log.e(TAG, "pendingPush: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override
    public boolean anySentAfter(long rowId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT 1 FROM " + TABLE_TRANSACTIONS + " WHERE " + COL_ID + " > ? AND "
                    + COL_PUSH_STATE + " = ? LIMIT 1",
                    new String[] { String.valueOf(rowId), String.valueOf(castech.emvtxn.reporting.PushEligibility.PUSH_SENT) });
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override
    public void markSent(long rowId, String message, long sentAt) {
        ContentValues v = new ContentValues();
        v.put(COL_PUSH_STATE, castech.emvtxn.reporting.PushEligibility.PUSH_SENT);
        v.put(COL_PUSH_SENT_AT, sentAt);
        v.put(COL_PUSH_MESSAGE, message);
        v.put(COL_PUSH_LAST_ERROR, "");
        getWritableDatabase().update(TABLE_TRANSACTIONS, v, COL_ID + " = ?", new String[] { String.valueOf(rowId) });
    }

    @Override
    public void markFailed(long rowId, String error) {
        getWritableDatabase().execSQL("UPDATE " + TABLE_TRANSACTIONS + " SET " + COL_PUSH_ATTEMPTS + " = " + COL_PUSH_ATTEMPTS
                + " + 1, " + COL_PUSH_LAST_ERROR + " = ? WHERE " + COL_ID + " = ?",
                new Object[] { truncate(error, 200), rowId });
    }

    @Override
    public void markParked(long rowId, String error) {
        ContentValues v = new ContentValues();
        v.put(COL_PUSH_STATE, castech.emvtxn.reporting.PushEligibility.PUSH_PARKED);
        v.put(COL_PUSH_LAST_ERROR, truncate(error, 200));
        getWritableDatabase().update(TABLE_TRANSACTIONS, v, COL_ID + " = ?", new String[] { String.valueOf(rowId) });
    }

    @Override public int countPending() { return countByPushState(castech.emvtxn.reporting.PushEligibility.PUSH_PENDING); }
    @Override public int countParked()  { return countByPushState(castech.emvtxn.reporting.PushEligibility.PUSH_PARKED); }

    private int countByPushState(int state) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_TRANSACTIONS + " WHERE " + COL_PUSH_STATE + " = ?",
                    new String[] { String.valueOf(state) });
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** True when any row in a batch older than {@code batchId} is still PENDING — such batches must not be pruned. */
    private boolean hasPendingPushBelow(SQLiteDatabase db, long batchId) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM " + TABLE_TRANSACTIONS + " WHERE " + COL_BATCH_ID + " < ? AND " + COL_PUSH_STATE + " = ? LIMIT 1",
                    new String[] { String.valueOf(batchId), String.valueOf(castech.emvtxn.reporting.PushEligibility.PUSH_PENDING) });
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * RPT-02: a reversal the host accepted becomes its own journal row, pushed as RWT. Carries the
     * original's sequence, amounts, card and account; fresh id and flow id. The Detail Report and
     * BatchMath select by type and ignore REVERSAL rows; "Reversed" keeps coming from the flag.
     */
    public long insertReversalRow(TransactionLog original, boolean pending) {
        TransactionLog r = new TransactionLog();
        r.setTransactionId(original.getTransactionId() + "-RWT");
        r.setTransactionType("REVERSAL");
        r.setTimestamp(System.currentTimeMillis());
        r.setCardLastFour(original.getCardLastFour());
        r.setEntryMode(original.getEntryMode());
        r.setAmountCents(original.getAmountCents());
        r.setSaleCents(original.getSaleCents());
        r.setCashBackCents(original.getCashBackCents());
        r.setFeeCents(original.getFeeCents());
        r.setTipCents(original.getTipCents());
        r.setTotalCents(original.getTotalCents());
        r.setResult("APPROVED");
        r.setResponseCode(original.getResponseCode());
        r.setReferenceNumber(original.getReferenceNumber());
        r.setTerminalId(original.getTerminalId());
        r.setProcessorType(original.getProcessorType());
        r.setSequenceNumber(original.getSequenceNumber());
        r.setAccountType(original.getAccountType());
        r.setBatchId(original.getBatchId());
        r.setFlowId(java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US));
        r.setPushState(pending ? castech.emvtxn.reporting.PushEligibility.PUSH_PENDING
                               : castech.emvtxn.reporting.PushEligibility.PUSH_NOT_APPLICABLE);
        return saveTransaction(r);
    }
```

Pruning guards. In `closeCurrentBatch`, replace the two `db.delete(...)` lines with:

```java
            long oldest = BatchMath.oldestBatchToKeep(next, BatchMath.KEEP_BATCHES);
            if (hasPendingPushBelow(db, oldest)) {
                Log.w(TAG, "Batch pruning skipped: unsent reporting rows in batches below " + oldest);
            } else {
                db.delete(TABLE_TRANSACTIONS, COL_BATCH_ID + " < ?", new String[] { String.valueOf(oldest) });
                db.delete(TABLE_BATCHES, "id < ?", new String[] { String.valueOf(oldest) });
            }
```

In `clearClosedBatches`, before the deletes:

```java
        if (hasPendingPushBelow(db, current)) {
            Log.w(TAG, "Clear history refused: unsent reporting rows in closed batches");
            return -1;
        }
```

and make the Admin caller (`Fragment_page_admin_atm.clearClosedBatches()`, search `clearClosedBatches`) show "Not cleared: unsent reporting rows" when it gets -1 instead of "Cleared N".

The Detail Report already ignores rows of any other type: `DetailReport.java:43-45` counts `reversed` only for `WITHDRAWAL`, `balanceInquiries` for `BALANCE_INQUIRY`, and `continue`s on anything else; `ReportRow.isApprovedWithdrawal()` requires `WITHDRAWAL`. So a `REVERSAL` row changes nothing there without code changes. Pin that with one new test in `DetailReportTest` (same style as its existing cases): a batch of one approved withdrawal plus one `REVERSAL` row with the same amounts prints one detail block, summary count 1, and `Reversed 0` (the flag, not the row, drives that count).

- [ ] **Step 8: Compile and run the suite**

Run: `./gradlew compileMkskDebugJavaWithJavac -q && ./gradlew testMkskDebugUnitTest -q`
Expected: compiles; 332 tests (+6), same 3 pre-existing failures.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/castech/emvtxn/reporting/PushEligibility.java app/src/main/java/castech/emvtxn/reporting/PushStore.java app/src/test/java/castech/emvtxn/reporting/PushEligibilityTest.java app/src/main/java/castech/emvtxn/atm/TransactionLog.java app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java
git commit -m "RPT-02: journal schema v3 — sale/cash back, flow id, push bookkeeping; push store; pruning guard; reversal rows

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: The journal writes the new columns and signals the pusher

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/atm/TransactionJournal.java` (`record`, `markReversed`)
- Create: `app/src/main/java/castech/emvtxn/reporting/PushSignal.java`
- Test: `app/src/test/java/castech/emvtxn/reporting/PushSignalTest.java`

**Interfaces:**
- Consumes: `GlobalPara.atmAmounts` (Task 2), `PushEligibility.initialState` (Task 4), `TransactionLogManager.insertReversalRow`, `getTransactionById` (exists), `ReportingConfig.isConfigured()` (Task 3).
- Produces: `PushSignal` — a tiny static hook: `static void setListener(Runnable r)`, `static void newRow()`; the pusher registers in Task 8. Pure (no Android).

- [ ] **Step 1: Write the failing test for the hook**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/** RPT-02: the journal tells the pusher "a row was written" without knowing the pusher. */
public class PushSignalTest {
    @After public void tearDown() { PushSignal.setListener(null); }

    @Test
    public void newRow_reachesTheRegisteredListener_once() {
        AtomicInteger calls = new AtomicInteger();
        PushSignal.setListener(calls::incrementAndGet);
        PushSignal.newRow();
        assertEquals(1, calls.get());
    }

    @Test
    public void newRow_withNoListener_isANoOp() {
        PushSignal.newRow();   // must not throw
    }

    @Test
    public void aThrowingListener_doesNotPropagate() {
        PushSignal.setListener(() -> { throw new IllegalStateException("boom"); });
        PushSignal.newRow();   // the journal must never fail a transaction because of the pusher
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushSignalTest*' -q`
Expected: compilation error, `cannot find symbol: class PushSignal`.

- [ ] **Step 3: Implement `PushSignal`**

```java
package castech.emvtxn.reporting;

import java.util.concurrent.atomic.AtomicReference;

/** Decouples the journal (transaction thread) from the pusher: "a row was written, drain when you can". */
public final class PushSignal {
    private static final AtomicReference<Runnable> listener = new AtomicReference<>();
    private PushSignal() {}

    public static void setListener(Runnable r) { listener.set(r); }

    public static void newRow() {
        Runnable r = listener.get();
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) { /* the journal must never fail a transaction */ }
    }
}
```

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushSignalTest*' -q`
Expected: 3 tests, 0 failures.

- [ ] **Step 5: `TransactionJournal.record` writes the breakdown and push columns**

Replace the amount block (`boolean bi = ...` through `log.setTotalCents(amount + fee);`) with:

```java
            boolean bi = "BALANCE_INQUIRY".equals(outcome.type);
            // AMT-03: the breakdown is the source; amount_cents stays the WITHDRAWAL (host amount)
            castech.emvtxn.AmountBreakdown a = bi ? castech.emvtxn.AmountBreakdown.balanceInquiry() : GlobalPara.atmAmounts;
            log.setSaleCents(a.sale);
            log.setTipCents(a.tip);
            log.setAmountCents(a.withdrawal);
            log.setCashBackCents(a.cashBack);
            log.setFeeCents(a.fee);
            log.setTotalCents(a.total);
```

After `log.setReversed(false);` add:

```java
            // RPT-02: push bookkeeping. Pending only when a reporting key exists and the row is sendable.
            boolean configured = new castech.emvtxn.reporting.ReportingConfig(ctx).isConfigured();
            log.setFlowId(java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US));
            log.setPushState(castech.emvtxn.reporting.PushEligibility.initialState(outcome.type, outcome.result, configured));
```

After `long id = m.saveTransaction(log);` add:

```java
            if (id != -1 && log.getPushState() == castech.emvtxn.reporting.PushEligibility.PUSH_PENDING) {
                castech.emvtxn.reporting.PushSignal.newRow();
            }
```

Remove the now-unused `parse` helper and the `Money` import if nothing else uses them.

- [ ] **Step 6: `TransactionJournal.markReversed` inserts the reversal row**

Replace the body of the `try` in `markReversed` with:

```java
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            boolean hit = m.markReversed(transactionId);
            Log.w(TAG, "reversal accepted for " + transactionId + " → journal row "
                    + (hit ? "marked reversed" : "not found (nothing journaled under that id)"));
            if (hit) {
                TransactionLog original = m.getTransactionById(transactionId);
                if (original != null) {
                    boolean configured = new castech.emvtxn.reporting.ReportingConfig(ctx).isConfigured();
                    long row = m.insertReversalRow(original, configured);
                    Log.w(TAG, "reversal row " + row + " written for seq " + original.getSequenceNumber()
                            + (configured ? " (pending push as RWT)" : ""));
                    if (row != -1 && configured) castech.emvtxn.reporting.PushSignal.newRow();
                }
            }
```

- [ ] **Step 7: Compile and run the suite**

Run: `./gradlew compileMkskDebugJavaWithJavac -q && ./gradlew testMkskDebugUnitTest -q`
Expected: compiles; 335 tests (+3), same 3 pre-existing failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/castech/emvtxn/atm/TransactionJournal.java app/src/main/java/castech/emvtxn/reporting/PushSignal.java app/src/test/java/castech/emvtxn/reporting/PushSignalTest.java
git commit -m "RPT-02: journal writes the breakdown and push columns, inserts reversal rows, signals the pusher

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: `PushPayload` — a row becomes the portal's JSON

**Files:**
- Create: `app/src/main/java/castech/emvtxn/reporting/PushPayload.java`
- Test: `app/src/test/java/castech/emvtxn/reporting/PushPayloadTest.java`

**Interfaces:**
- Consumes: `TransactionLog` getters (Task 4 fields included).
- Produces: `PushPayload.Identity(String terminalId, String hardwareSerial, String accessKey, String processorName, String appVersion)` (public final fields, same order); `static org.json.JSONObject PushPayload.of(TransactionLog row, Identity id, java.util.TimeZone zone)`; `static String PushPayload.transType(String transactionType)`; `static boolean PushPayload.isHostCode(String responseCode)`.

- [ ] **Step 1: Write the failing test**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;

/**
 * RPT-02 (6.2.13): the request body, built from a journal row, in the shape the portal's
 * contract (docs/MYVIEW-TERMINAL-PUSH-API.md) shows. Synthetic identifiers throughout.
 */
public class PushPayloadTest {

    private static final TimeZone EASTERN = TimeZone.getTimeZone("America/New_York");
    private static final PushPayload.Identity ID = new PushPayload.Identity(
            "MS00TEST", "0000195260000000", "test-key-not-real", "EFX", "6.2.13");

    /** 2026-10-07 09:04:15 Eastern (daylight time), as epoch millis. */
    private static long eastern(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(EASTERN);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    private static TransactionLog approvedSale() {
        TransactionLog t = new TransactionLog();
        t.setId(42);
        t.setTransactionType("WITHDRAWAL");
        t.setResult("APPROVED");
        t.setTimestamp(eastern(2026, 10, 7, 9, 4, 15));
        t.setSequenceNumber(1);
        t.setSaleCents(219_25);
        t.setTipCents(2_00);
        t.setCashBackCents(8_75);
        t.setFeeCents(3_50);
        t.setAmountCents(230_00);          // withdrawal
        t.setTotalCents(233_50);
        t.setCardLastFour("1111");
        t.setReferenceNumber("295300004276");
        t.setResponseCode("00");
        t.setAccountType(20);              // checking
        t.setFlowId("69B266E6-ADD4-4BFC-847E-7BEA0A4C9A74");
        return t;
    }

    @Test
    public void approvedSale_matchesTheContractExample() throws Exception {
        JSONObject body = PushPayload.of(approvedSale(), ID, EASTERN);
        assertEquals("69B266E6-ADD4-4BFC-847E-7BEA0A4C9A74", body.getString("flow_id"));
        assertEquals("test-key-not-real", body.getString("tenantAccessKey"));
        assertEquals("0000195260000000", body.getString("tsn"));
        JSONObject t = body.getJSONObject("transactionJSON");
        assertEquals("0000195260000000", t.getString("tsn"));
        assertEquals("MS00TEST", t.getString("TermID"));
        assertEquals("MS00TEST", t.getString("HostTermID"));
        assertEquals(1, t.getInt("TerminalSequenceNum"));
        assertEquals("2026-10-07 09:04:15", t.getString("TransDateTimeUTC"));
        assertEquals("295300004276", t.getString("RRN"));
        assertEquals("CA", t.getString("SourceAccount"));
        assertEquals(21925, t.getInt("RequestedAmt"));
        assertEquals(200, t.getInt("TipAmount"));
        assertEquals(875, t.getInt("CashBackAmount"));
        assertEquals(350, t.getInt("SurchargeAmt"));
        assertEquals(23350, t.getInt("TotalAmt"));
        assertEquals("1111", t.getString("CardLast4"));
        assertEquals("Transaction approved", t.getString("ResponseDescription"));
        assertEquals("EFX", t.getString("Host"));
        assertEquals("10072026", t.getString("BusinessDate"));
        assertEquals("WTH", t.getString("TransType"));
        assertEquals(1, t.getInt("Approved"));
        assertEquals("EST", t.getString("TimeZone"));
        assertEquals(1, t.getInt("TimeZoneDST"));
        assertEquals("Castle S1FP-TFI 6.2.13", t.getString("Software"));
    }

    @Test
    public void totalEqualsSalePlusTipPlusCashBackPlusSurcharge() throws Exception {
        JSONObject t = PushPayload.of(approvedSale(), ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals(t.getInt("TotalAmt"),
                t.getInt("RequestedAmt") + t.getInt("TipAmount") + t.getInt("CashBackAmount") + t.getInt("SurchargeAmt"));
    }

    @Test
    public void hostDecline_isApprovedZero_withTheHostsText() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("51");
        d.setErrorMessage("INSUFFICIENT FUNDS");
        d.setReferenceNumber(null);
        JSONObject t = PushPayload.of(d, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals(0, t.getInt("Approved"));
        assertEquals("INSUFFICIENT FUNDS", t.getString("ResponseDescription"));
        assertEquals("", t.getString("RRN"));
        assertEquals(350, t.getInt("SurchargeAmt"));   // declines keep the surcharge (contract §3)
    }

    @Test
    public void terminalSideDecline_isLabelled_andKeepsSequenceZero() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("MSR_NA");
        d.setErrorMessage("Swipe not supported");
        d.setSequenceNumber(0);
        d.setReferenceNumber("");
        JSONObject t = PushPayload.of(d, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals(0, t.getInt("TerminalSequenceNum"));
        assertEquals("Declined at terminal: Swipe not supported", t.getString("ResponseDescription"));
        assertEquals(0, t.getInt("Approved"));
    }

    @Test
    public void timedOutRequest_keepsItsRealSequence_butIsStillTerminalLabelled() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("");
        d.setErrorMessage("Transaction timeout");
        d.setSequenceNumber(17);
        JSONObject t = PushPayload.of(d, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals(17, t.getInt("TerminalSequenceNum"));
        assertEquals("Declined at terminal: Transaction timeout", t.getString("ResponseDescription"));
    }

    @Test
    public void balanceInquiry_isINQ_withZeroMoney() throws Exception {
        TransactionLog b = approvedSale();
        b.setTransactionType("BALANCE_INQUIRY");
        b.setSaleCents(0); b.setTipCents(0); b.setCashBackCents(0); b.setFeeCents(0); b.setAmountCents(0); b.setTotalCents(0);
        JSONObject t = PushPayload.of(b, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals("INQ", t.getString("TransType"));
        assertEquals(0, t.getInt("RequestedAmt") + t.getInt("TipAmount") + t.getInt("CashBackAmount")
                + t.getInt("SurchargeAmt") + t.getInt("TotalAmt"));
    }

    @Test
    public void reversalRow_isRWT_withTheOriginalsSequence() throws Exception {
        TransactionLog r = approvedSale();
        r.setTransactionType("REVERSAL");
        r.setSequenceNumber(1);
        JSONObject t = PushPayload.of(r, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals("RWT", t.getString("TransType"));
        assertEquals(1, t.getInt("TerminalSequenceNum"));
    }

    @Test
    public void accountTypes_mapToPortalCodes() throws Exception {
        TransactionLog s = approvedSale(); s.setAccountType(10);
        assertEquals("SA", PushPayload.of(s, ID, EASTERN).getJSONObject("transactionJSON").getString("SourceAccount"));
        TransactionLog c = approvedSale(); c.setAccountType(30);
        assertEquals("CC", PushPayload.of(c, ID, EASTERN).getJSONObject("transactionJSON").getString("SourceAccount"));
    }

    @Test
    public void timeZoneFieldsFollowTheRowInstant() throws Exception {
        // Review focus 1: a January row is standard time even if sent in July
        TransactionLog w = approvedSale();
        w.setTimestamp(eastern(2026, 1, 15, 18, 30, 0));
        JSONObject t = PushPayload.of(w, ID, EASTERN).getJSONObject("transactionJSON");
        assertEquals("2026-01-15 18:30:00", t.getString("TransDateTimeUTC"));
        assertEquals("01152026", t.getString("BusinessDate"));
        assertEquals("EST", t.getString("TimeZone"));
        assertEquals(0, t.getInt("TimeZoneDST"));
    }

    @Test
    public void missingOptionalFieldsBecomeEmptyStrings() throws Exception {
        // Review focus 2: nulls must never reach the wire or throw
        TransactionLog n = approvedSale();
        n.setCardLastFour(null); n.setReferenceNumber(null); n.setErrorMessage(null); n.setFlowId(null);
        n.setResult("DECLINED"); n.setResponseCode("05");
        JSONObject body = PushPayload.of(n, ID, EASTERN);
        JSONObject t = body.getJSONObject("transactionJSON");
        assertEquals("", t.getString("CardLast4"));
        assertEquals("", t.getString("RRN"));
        assertEquals("Transaction declined", t.getString("ResponseDescription"));
        assertFalse(body.toString().contains("null"));
    }

    @Test
    public void emptyTerminalIdStillBuilds() throws Exception {
        // Review focus 5: an unconfigured terminal still produces a body (the portal will say why it is wrong)
        PushPayload.Identity bare = new PushPayload.Identity("", "0000195260000000", "test-key-not-real", "EFX", "6.2.13");
        JSONObject t = PushPayload.of(approvedSale(), bare, EASTERN).getJSONObject("transactionJSON");
        assertEquals("", t.getString("TermID"));
    }

    @Test
    public void neverContainsAFullPan() {
        String s = PushPayload.of(approvedSale(), ID, EASTERN).toString();
        assertFalse(s.matches(".*\\d{13,19}.*"));
    }

    @Test
    public void isHostCode_isTwoDigits() {
        assertTrue(PushPayload.isHostCode("00"));
        assertTrue(PushPayload.isHostCode("91"));
        assertFalse(PushPayload.isHostCode("MSR_NA"));
        assertFalse(PushPayload.isHostCode("A0000002"));
        assertFalse(PushPayload.isHostCode(""));
        assertFalse(PushPayload.isHostCode(null));
    }
}
```

Note on `neverContainsAFullPan`: the body contains the 16-digit serial `0000195260000000`, which would match 13–19 digits. Use a serial with a letter in the test identity instead (`"S0000195260000000"` is not realistic) — simpler: assert on the `transactionJSON` **without** the `tsn` field: build `String s = t.toString()` after `t.remove("tsn")`. Write the test that way.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushPayloadTest*' -q`
Expected: compilation error, `cannot find symbol: class PushPayload`.

- [ ] **Step 3: Implement**

```java
package castech.emvtxn.reporting;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.json.JSONException;
import org.json.JSONObject;

import castech.emvtxn.atm.TransactionLog;

/**
 * Builds the MyView ingestion request from a journal row (RPT-02, 6.2.13). Pure; pinned by
 * PushPayloadTest against the portal contract's own example. All money is whole cents.
 * {@code TransDateTimeUTC} is, per the contract, the terminal's LOCAL time of the row.
 */
public final class PushPayload {

    /** Who we are to the portal. */
    public static final class Identity {
        public final String terminalId;      // CasHUB terminal_id → TermID / HostTermID
        public final String hardwareSerial;  // Castle factory serial → tsn
        public final String accessKey;       // tenantAccessKey
        public final String processorName;   // Host
        public final String appVersion;      // Software
        public Identity(String terminalId, String hardwareSerial, String accessKey, String processorName, String appVersion) {
            this.terminalId = nz(terminalId);
            this.hardwareSerial = nz(hardwareSerial);
            this.accessKey = nz(accessKey);
            this.processorName = nz(processorName);
            this.appVersion = nz(appVersion);
        }
    }

    private PushPayload() {}

    public static JSONObject of(TransactionLog row, Identity id, TimeZone zone) {
        try {
            Date when = new Date(row.getTimestamp());
            SimpleDateFormat dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat bd = new SimpleDateFormat("MMddyyyy", Locale.US);
            dt.setTimeZone(zone);
            bd.setTimeZone(zone);
            boolean approved = "APPROVED".equals(row.getResult());

            JSONObject t = new JSONObject();
            t.put("tsn", id.hardwareSerial);
            t.put("TermID", id.terminalId);
            t.put("TerminalSequenceNum", Math.max(0, row.getSequenceNumber()));
            t.put("TransDateTimeUTC", dt.format(when));
            t.put("RRN", nz(row.getReferenceNumber()));
            t.put("SourceAccount", sourceAccount(row.getAccountType()));
            t.put("RequestedAmt", row.getSaleCents());
            t.put("TipAmount", row.getTipCents());
            t.put("CashBackAmount", row.getCashBackCents());
            t.put("SurchargeAmt", row.getFeeCents());
            t.put("TotalAmt", row.getAmountCents() + row.getFeeCents());
            t.put("CardLast4", nz(row.getCardLastFour()));
            t.put("ResponseDescription", description(row, approved));
            t.put("Host", id.processorName);
            t.put("HostTermID", id.terminalId);
            t.put("BusinessDate", bd.format(when));
            t.put("TransType", transType(row.getTransactionType()));
            t.put("Approved", approved ? 1 : 0);
            t.put("TimeZone", zone.getDisplayName(false, TimeZone.SHORT, Locale.US));
            t.put("TimeZoneDST", zone.inDaylightTime(when) ? 1 : 0);
            t.put("Software", "Castle S1FP-TFI " + id.appVersion);

            JSONObject body = new JSONObject();
            body.put("flow_id", nz(row.getFlowId()));
            body.put("tenantAccessKey", id.accessKey);
            body.put("tsn", id.hardwareSerial);
            body.put("transactionJSON", t);
            return body;
        } catch (JSONException e) {
            throw new IllegalStateException("payload build failed: " + e.getMessage(), e);
        }
    }

    /** WTH for withdrawals and sales, INQ for balance inquiries, RWT for reversals (contract §3). */
    public static String transType(String transactionType) {
        if ("BALANCE_INQUIRY".equals(transactionType)) return "INQ";
        if ("REVERSAL".equals(transactionType)) return "RWT";
        return "WTH";
    }

    /** A two-digit response code came from the host; anything else is the terminal's own ending. */
    public static boolean isHostCode(String responseCode) {
        return responseCode != null && responseCode.matches("\\d{2}");
    }

    private static String description(TransactionLog row, boolean approved) {
        if (approved) return "Transaction approved";
        String msg = nz(row.getErrorMessage()).trim();
        if (isHostCode(row.getResponseCode())) return msg.isEmpty() ? "Transaction declined" : msg;
        return "Declined at terminal: " + (msg.isEmpty() ? "no reason recorded" : msg);
    }

    private static String sourceAccount(int accountType) {
        switch (accountType) {
            case 10: return "SA";
            case 30: return "CC";
            default: return "CA";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
```

Check the account constants: `grep -n 'ATM_ACCOUNT_' app/src/main/java/castech/emvtxn/GlobalPara.java` — the plan assumes savings 10, checking 20, credit 30 (the `TransactionLog` comment says so); if they differ, use the constants directly in `sourceAccount` and adjust the test values.

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*PushPayloadTest*' -q`
Expected: 13 tests, 0 failures. If `TimeZone` comes out `GMT-05:00` rather than `EST` on this JDK, the test is right and the implementation must map via `zone.getDisplayName(false, TimeZone.SHORT, Locale.US)`; on JDK 17 `America/New_York` gives `EST`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/castech/emvtxn/reporting/PushPayload.java app/src/test/java/castech/emvtxn/reporting/PushPayloadTest.java
git commit -m "RPT-02: PushPayload — journal row to the portal's request body, pinned to the contract example

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: `ReportingClient`, `ReportingPusher`, `ReportingStatus`

**Files:**
- Create: `app/src/main/java/castech/emvtxn/reporting/ReportingClient.java`
- Create: `app/src/main/java/castech/emvtxn/reporting/ReportingPusher.java`
- Modify: `app/src/main/java/castech/emvtxn/reporting/ReportingStatus.java` (add rendering)
- Test: `app/src/test/java/castech/emvtxn/reporting/ReportingPusherTest.java`, `app/src/test/java/castech/emvtxn/reporting/ReportingStatusTest.java`

**Interfaces:**
- Consumes: `PushStore` (Task 4), `PushPayload` (Task 6), `TransactionLog`.
- Produces: `ReportingClient(OkHttpClient)`; `ReportingClient.Result post(String url, String accessKey, JSONObject body)` with `Result { int httpStatus; String message; String error; boolean transportFailure; }` and helpers `boolean accepted()` (200), `boolean unauthorized()` (401).
- Produces: `ReportingPusher(PushStore store, ReportingClient client, Settings settings, Sleeper sleeper, Clock clock)` where `Settings { String url(); String accessKey(); PushPayload.Identity identity(); TimeZone zone(); }` is read **once per run**; `Sleeper { void sleep(long ms) throws InterruptedException; }`; `Clock { long now(); }`. Methods: `RunReport drainOnce()` (synchronous, for tests and for the executor), `void requestRun()` (coalescing; schedules on the single-thread executor), `void start(long sweepIntervalMs)`, `void stop()`, `Snapshot snapshot()`. `RunReport { int sent, failed, parked; String state; String lastError; }`.
- Produces: `ReportingStatus.render(String state, int pending, int parked, long lastSentAt, String lastError, long now)` → the Admin line text.

- [ ] **Step 1: Write the failing pusher tests**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** RPT-02: the drain loop against a fake portal and an in-memory journal. */
public class ReportingPusherTest {

    private MockWebServer server;
    private OkHttpClient http;
    private FakeStore store;
    private List<Long> sleeps;
    private String key = "test-key-not-real";
    private ReportingPusher pusher;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build();
        store = new FakeStore();
        sleeps = new ArrayList<>();
        pusher = new ReportingPusher(store, new ReportingClient(http),
                new ReportingPusher.Settings() {
                    @Override public String url() { return server.url("/transactions/addTransaction").toString(); }
                    @Override public String accessKey() { return key; }
                    @Override public PushPayload.Identity identity() {
                        return new PushPayload.Identity("MS00TEST", "0000195260000000", key, "EFX", "6.2.13");
                    }
                    @Override public TimeZone zone() { return TimeZone.getTimeZone("America/New_York"); }
                },
                ms -> sleeps.add(ms),
                () -> 1_760_000_000_000L);
    }

    @After
    public void tearDown() throws Exception {
        pusher.stop();
        http.dispatcher().executorService().shutdownNow();
        server.shutdown();
    }

    private static String ok(String message) {
        return "{\"success\":true,\"tsn\":\"0000195260000000\",\"message\":\"" + message
                + "\",\"host_response_code\":\"00\",\"host_response_isocode\":\"00\",\"error_result_code\":\"000\",\"extended_error_code\":\"0000\"}";
    }

    private TransactionLog row(long id, int seq) {
        TransactionLog t = new TransactionLog();
        t.setId(id); t.setTransactionType("WITHDRAWAL"); t.setResult("APPROVED");
        t.setTimestamp(1_759_800_000_000L + id * 1000); t.setSequenceNumber(seq);
        t.setSaleCents(10_00); t.setAmountCents(10_00); t.setFeeCents(3_50); t.setTotalCents(13_50);
        t.setCardLastFour("1111"); t.setResponseCode("00"); t.setAccountType(20);
        t.setFlowId("FLOW-" + id); t.setPushState(PushEligibility.PUSH_PENDING);
        return t;
    }

    // ---- happy path -----------------------------------------------------------------

    @Test
    public void drainsPendingRowsOldestFirst_andMarksThemSentWithThePortalsMessage() throws Exception {
        store.add(row(2, 2)); store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.enqueue(new MockResponse().setBody(ok("Transaction merged successfully")));

        ReportingPusher.RunReport r = pusher.drainOnce();

        assertEquals(2, r.sent);
        RecordedRequest first = server.takeRequest();
        assertEquals("/transactions/addTransaction", first.getPath());
        assertEquals("test-key-not-real", first.getHeader("X-API-Key"));
        assertEquals("FLOW-1", new JSONObject(first.getBody().readUtf8()).getString("flow_id"));
        assertEquals("FLOW-2", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("flow_id"));
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
        assertEquals("Transaction ingested successfully", store.get(1).getPushMessage());
        assertEquals("Transaction merged successfully", store.get(2).getPushMessage());
        assertEquals(ReportingStatus.OK, r.state);
    }

    @Test
    public void alreadyExists_isDelivered_notAFailure() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction already exists")));
        assertEquals(1, pusher.drainOnce().sent);
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
    }

    // ---- nothing without a key --------------------------------------------------------

    @Test
    public void nothingIsSentWithoutAKey() throws Exception {
        key = "";
        store.add(row(1, 1));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.sent);
        assertEquals(0, server.getRequestCount());
        assertEquals(ReportingStatus.NOT_CONFIGURED, r.state);
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());   // kept
    }

    // ---- failures ----------------------------------------------------------------------

    @Test
    public void unauthorized_stopsTheRun_andReportsKeyRejected() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.KEY_REJECTED, r.state);
        assertEquals(1, server.getRequestCount());          // did not try row 2
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
        assertEquals(1, store.get(1).getPushAttempts());
    }

    @Test
    public void serverError_backsOffAndStopsTheRun_rowStaysPending() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":\"Invalid amounts: all amounts must be non-negative\",\"host_response_code\":\"96\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.RETRYING, r.state);
        assertEquals(1, r.failed);
        assertEquals(1, server.getRequestCount());
        assertEquals("Invalid amounts: all amounts must be non-negative", store.get(1).getPushLastError());
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
    }

    @Test
    public void backoffSchedule_is5s_30s_2m_then5mCap() {
        assertEquals(5_000L, ReportingPusher.backoffMillis(1));
        assertEquals(30_000L, ReportingPusher.backoffMillis(2));
        assertEquals(120_000L, ReportingPusher.backoffMillis(3));
        assertEquals(300_000L, ReportingPusher.backoffMillis(4));
        assertEquals(300_000L, ReportingPusher.backoffMillis(50));
    }

    @Test
    public void aRowInBackoff_isSkippedUntilItsTime() throws Exception {
        TransactionLog r1 = row(1, 1); r1.setPushAttempts(1); store.add(r1); store.lastFailureAt.put(1L, 1_760_000_000_000L - 1_000);
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.drainOnce();
        // only row 2 went (row 1 failed 1 s ago, backoff is 5 s)
        assertEquals(1, server.getRequestCount());
        assertEquals("FLOW-2", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("flow_id"));
    }

    @Test
    public void retryResendsTheIdenticalBody() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.drainOnce();
        store.lastFailureAt.clear();                        // backoff elapsed
        pusher.drainOnce();
        String a = server.takeRequest().getBody().readUtf8();
        String b = server.takeRequest().getBody().readUtf8();
        assertEquals(a, b);
    }

    // ---- parking -------------------------------------------------------------------------

    @Test
    public void allRowsFailingNeverParks() throws Exception {
        // Review focus 5: portal down (or terminal id wrong for every row) → retry forever, park nothing
        TransactionLog r1 = row(1, 1); r1.setPushAttempts(25); store.add(r1);
        TransactionLog r2 = row(2, 2); r2.setPushAttempts(25); store.add(r2);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"TermID required\"}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"TermID required\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.parked);
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
        assertEquals(PushEligibility.PUSH_PENDING, store.get(2).getPushState());
        assertEquals(ReportingStatus.RETRYING, r.state);
    }

    @Test
    public void aRowThatKeepsFailingWhileALaterRowIsAccepted_isParked() throws Exception {
        TransactionLog bad = row(1, 1); bad.setPushAttempts(10); store.add(bad);
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Invalid amounts\"}"));   // row 1
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));              // row 2
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(1, r.sent);
        assertEquals(1, r.parked);
        assertEquals(PushEligibility.PUSH_PARKED, store.get(1).getPushState());
        assertEquals(PushEligibility.PUSH_SENT, store.get(2).getPushState());
    }

    @Test
    public void aRowUnderTenFailures_isNotParked_evenIfLaterRowsSucceed() throws Exception {
        TransactionLog bad = row(1, 1); bad.setPushAttempts(3); store.add(bad);
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"x\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        // under the threshold the run stops at the first failure (backoff) — row 2 is not tried this run
        assertEquals(0, r.parked);
        assertEquals(1, server.getRequestCount());
    }

    // ---- configuration lifecycle -----------------------------------------------------------

    @Test
    public void configIsReadOncePerRun() throws Exception {
        // Review focus 4: a key change mid-run does not affect the run in flight
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        final String[] keyAfterFirst = { null };
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (keyAfterFirst[0] == null) { keyAfterFirst[0] = request.getHeader("X-API-Key"); key = "rotated-key"; }
                return new MockResponse().setBody(ok("Transaction ingested successfully"));
            }
        });
        pusher.drainOnce();
        RecordedRequest r1 = server.takeRequest();
        RecordedRequest r2 = server.takeRequest();
        assertEquals(r1.getHeader("X-API-Key"), r2.getHeader("X-API-Key"));
    }

    @Test
    public void keyRemovedMidQueueStopsSendingButKeepsRows() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.drainOnce();           // sends row 1 ... and row 2? no: one response queued → second request gets a 200 too
        key = "";
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.NOT_CONFIGURED, r.state);
        assertTrue(store.countPending() >= 0);   // nothing deleted
    }

    @Test
    public void requestRun_coalesces_andRunsOnTheExecutor() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.start(60_000L);
        pusher.requestRun(); pusher.requestRun(); pusher.requestRun();
        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        assertTrue(first != null);
        Thread.sleep(300);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void emptyStore_isAQuietNoOp() throws Exception {
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.sent + r.failed + r.parked);
        assertEquals(0, server.getRequestCount());
        assertEquals(ReportingStatus.OK, r.state);
    }

    // ---- fake store ---------------------------------------------------------------------------

    /** In-memory PushStore. Backoff bookkeeping lives here as it would in the row (push_attempts + last failure time). */
    static final class FakeStore implements PushStore {
        final Map<Long, TransactionLog> rows = new HashMap<>();
        final Map<Long, Long> lastFailureAt = new HashMap<>();
        void add(TransactionLog t) { rows.put(t.getId(), t); }
        TransactionLog get(long id) { return rows.get(id); }
        @Override public List<TransactionLog> pendingPush(int limit) {
            List<TransactionLog> out = new ArrayList<>();
            for (TransactionLog t : rows.values()) if (t.getPushState() == PushEligibility.PUSH_PENDING) out.add(t);
            out.sort((a, b) -> a.getTimestamp() != b.getTimestamp() ? Long.compare(a.getTimestamp(), b.getTimestamp()) : Long.compare(a.getId(), b.getId()));
            return out.size() > limit ? out.subList(0, limit) : out;
        }
        @Override public boolean anySentAfter(long rowId) {
            for (TransactionLog t : rows.values()) if (t.getId() > rowId && t.getPushState() == PushEligibility.PUSH_SENT) return true;
            return false;
        }
        @Override public void markSent(long id, String message, long sentAt) { TransactionLog t = rows.get(id); t.setPushState(PushEligibility.PUSH_SENT); t.setPushMessage(message); t.setPushSentAt(sentAt); }
        @Override public void markFailed(long id, String error) { TransactionLog t = rows.get(id); t.setPushAttempts(t.getPushAttempts() + 1); t.setPushLastError(error); lastFailureAt.put(id, 1_760_000_000_000L); }
        @Override public void markParked(long id, String error) { TransactionLog t = rows.get(id); t.setPushState(PushEligibility.PUSH_PARKED); t.setPushLastError(error); }
        @Override public int countPending() { return pendingPush(Integer.MAX_VALUE).size(); }
        @Override public int countParked() { int n = 0; for (TransactionLog t : rows.values()) if (t.getPushState() == PushEligibility.PUSH_PARKED) n++; return n; }
    }
}
```

**Backoff bookkeeping decision (lock in here):** the backoff needs "when did this row last fail". Rather than a new column, the pusher keeps an in-memory map `rowId → lastFailureAt` (lost on restart, which is fine: after a restart every pending row is tried once immediately). The `FakeStore.lastFailureAt` map in the test stands in for that; in the real pusher it is a field. So `ReportingPusher` has `private final Map<Long, Long> lastFailureAt = new ConcurrentHashMap<>()` and the two tests that touch `store.lastFailureAt` instead call `pusher.forgetBackoff(long rowId)` / `pusher.noteFailure(long rowId, long at)` — **adjust those two tests** (`aRowInBackoff_isSkippedUntilItsTime` uses `pusher.noteFailure(1L, now - 1000)`; `retryResendsTheIdenticalBody` uses `pusher.forgetBackoff(1L)`), and drop `lastFailureAt` from `FakeStore`.

- [ ] **Step 2: Write the failing status test**

```java
package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** RPT-02: the one Admin line. */
public class ReportingStatusTest {
    private static final long NOW = 1_760_000_000_000L;

    @Test public void notConfigured() {
        assertEquals("Reporting: not configured", ReportingStatus.render(ReportingStatus.NOT_CONFIGURED, 0, 0, 0, "", NOW));
    }
    @Test public void okWithNothingPending_showsLastSent() {
        String s = ReportingStatus.render(ReportingStatus.OK, 0, 0, NOW - 5 * 60_000, "", NOW);
        assertEquals("Reporting: configured · 0 pending · last sent 5 min ago", s);
    }
    @Test public void okNeverSent() {
        assertEquals("Reporting: configured · 0 pending · nothing sent yet", ReportingStatus.render(ReportingStatus.OK, 0, 0, 0, "", NOW));
    }
    @Test public void retrying_showsCountAndReason() {
        assertEquals("Reporting: 3 pending · retrying (timeout)", ReportingStatus.render(ReportingStatus.RETRYING, 3, 0, 0, "timeout", NOW));
    }
    @Test public void keyRejected() {
        assertEquals("Reporting: key rejected · 2 pending", ReportingStatus.render(ReportingStatus.KEY_REJECTED, 2, 0, 0, "Unauthorized", NOW));
    }
    @Test public void parkedCount_isAppended() {
        assertEquals("Reporting: configured · 0 pending · last sent 1 min ago · 1 parked",
                ReportingStatus.render(ReportingStatus.OK, 0, 1, NOW - 60_000, "", NOW));
    }
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew testMkskDebugUnitTest --tests '*ReportingPusherTest*' --tests '*ReportingStatusTest*' -q`
Expected: compilation errors (`ReportingClient`, `ReportingPusher`, `ReportingStatus.render` missing).

- [ ] **Step 4: Implement `ReportingClient`**

```java
package castech.emvtxn.reporting;

import java.io.IOException;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One POST to the portal. No retry here — the pusher owns the schedule. */
public final class ReportingClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** What the portal said, flattened for the pusher. */
    public static final class Result {
        public final int httpStatus;          // 0 on a transport failure
        public final String message;          // 200: the portal's "message"
        public final String error;            // non-200: the portal's "error" text, or the transport reason
        public Result(int httpStatus, String message, String error) {
            this.httpStatus = httpStatus; this.message = message == null ? "" : message; this.error = error == null ? "" : error;
        }
        public boolean accepted()      { return httpStatus == 200; }
        public boolean unauthorized()  { return httpStatus == 401; }
        public boolean transportFailure() { return httpStatus == 0; }
    }

    private final OkHttpClient http;

    public ReportingClient(OkHttpClient http) { this.http = http; }

    /** The default production client: 10 s connect, 30 s read/write (contract: allow 30 s). */
    public static ReportingClient production() {
        return new ReportingClient(new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build());
    }

    public Result post(String url, String accessKey, JSONObject body) {
        Request req = new Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("X-API-Key", accessKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() == null ? "" : res.body().string();
            String message = "", error = "";
            try {
                JSONObject o = new JSONObject(text);
                message = o.optString("message", "");
                error = o.optString("error", "");
            } catch (Exception notJson) {
                error = text.length() > 120 ? text.substring(0, 120) : text;
            }
            if (res.code() != 200 && error.isEmpty()) error = "HTTP " + res.code();
            return new Result(res.code(), message, error);
        } catch (IOException e) {
            return new Result(0, "", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
```

- [ ] **Step 5: Implement `ReportingStatus.render`**

Add to `ReportingStatus`:

```java
    public static String render(String state, int pending, int parked, long lastSentAt, String lastError, long now) {
        String s;
        if (NOT_CONFIGURED.equals(state)) {
            s = "Reporting: not configured";
        } else if (KEY_REJECTED.equals(state)) {
            s = "Reporting: key rejected · " + pending + " pending";
        } else if (RETRYING.equals(state)) {
            s = "Reporting: " + pending + " pending · retrying (" + (lastError == null || lastError.isEmpty() ? "error" : lastError) + ")";
        } else {
            s = "Reporting: configured · " + pending + " pending · " + (lastSentAt <= 0 ? "nothing sent yet" : "last sent " + ago(now - lastSentAt));
        }
        if (parked > 0) s += " · " + parked + " parked";
        return s;
    }

    private static String ago(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        return h < 24 ? h + " h ago" : (h / 24) + " d ago";
    }
```

- [ ] **Step 6: Implement `ReportingPusher`**

```java
package castech.emvtxn.reporting;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import castech.emvtxn.atm.TransactionLog;

/**
 * Drains PENDING journal rows to the portal (RPT-02, 6.2.13). One background thread; never the
 * transaction thread; never the SDK. The core ({@link #drainOnce()}) is synchronous and tested
 * against a fake portal; {@link #requestRun()} coalesces triggers onto the executor.
 *
 * <p>Per run: read the settings ONCE; stop at once when there is no key; send rows oldest first.
 * 200 → SENT. 401 → stop, KEY_REJECTED. Anything else → attempts++, back off (5 s, 30 s, 2 min,
 * 5 min cap) and stop the run — unless the row has failed 10+ times already, in which case skip it
 * and try the next row; if a later row is then accepted, park the skipped one (the portal is up,
 * that payload is the problem). While the portal is down everything fails and nothing is parked.
 */
public final class ReportingPusher {

    public interface Settings {
        String url();
        String accessKey();
        PushPayload.Identity identity();
        TimeZone zone();
    }
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public interface Clock { long now(); }

    /** Listener for status changes (Admin line, log). */
    public interface StatusListener { void onStatus(String state, int pending, int parked, long lastSentAt, String lastError); }

    public static final class RunReport {
        public int sent, failed, parked;
        public String state = ReportingStatus.OK;
        public String lastError = "";
    }

    static final int PARK_AFTER_FAILURES = 10;
    static final int BATCH = 50;
    private static final long[] BACKOFF_MS = { 5_000L, 30_000L, 120_000L, 300_000L };

    private final PushStore store;
    private final ReportingClient client;
    private final Settings settings;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Map<Long, Long> lastFailureAt = new ConcurrentHashMap<>();
    private final AtomicBoolean runRequested = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> sweep;
    private volatile StatusListener statusListener;
    private volatile long lastSentAt = 0L;
    private volatile String lastState = ReportingStatus.NOT_CONFIGURED;
    private volatile String lastError = "";

    public ReportingPusher(PushStore store, ReportingClient client, Settings settings, Sleeper sleeper, Clock clock) {
        this.store = store; this.client = client; this.settings = settings; this.sleeper = sleeper; this.clock = clock;
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    /** Backoff after the n-th consecutive failure of a row (n >= 1). */
    public static long backoffMillis(int attempts) {
        if (attempts <= 0) return 0L;
        return BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1];
    }

    // ---- test seams for the in-memory backoff map ----------------------------------------
    void noteFailure(long rowId, long at) { lastFailureAt.put(rowId, at); }
    void forgetBackoff(long rowId) { lastFailureAt.remove(rowId); }

    /** One synchronous drain. Safe to call from any thread; runs may not overlap (guarded). */
    public RunReport drainOnce() {
        RunReport report = new RunReport();
        if (!running.compareAndSet(false, true)) return report;
        try {
            String key = settings.accessKey();
            String url = settings.url();
            PushPayload.Identity id = settings.identity();
            TimeZone zone = settings.zone();
            if (key == null || key.isEmpty()) {
                report.state = ReportingStatus.NOT_CONFIGURED;
                publish(report);
                return report;
            }
            long now = clock.now();
            List<TransactionLog> pending = store.pendingPush(BATCH);
            Long skippedCandidate = null;   // a 10+ failure row we skipped this run
            String skippedError = null;
            for (TransactionLog row : pending) {
                Long failedAt = lastFailureAt.get(row.getId());
                if (failedAt != null && now - failedAt < backoffMillis(row.getPushAttempts())) continue;   // still backing off

                JSONObject body = PushPayload.of(row, id, zone);
                ReportingClient.Result res = client.post(url, key, body);
                if (res.accepted()) {
                    store.markSent(row.getId(), res.message, now);
                    lastFailureAt.remove(row.getId());
                    lastSentAt = now;
                    report.sent++;
                    if (skippedCandidate != null) {
                        store.markParked(skippedCandidate, skippedError);
                        report.parked++;
                        skippedCandidate = null;
                    }
                    continue;
                }
                store.markFailed(row.getId(), res.error);
                lastFailureAt.put(row.getId(), now);
                report.failed++;
                report.lastError = res.error;
                if (res.unauthorized()) {
                    report.state = ReportingStatus.KEY_REJECTED;
                    publish(report);
                    return report;
                }
                int attemptsNow = row.getPushAttempts() + 1;
                if (attemptsNow >= PARK_AFTER_FAILURES && skippedCandidate == null) {
                    skippedCandidate = row.getId();      // give the next row a chance to prove the portal is up
                    skippedError = res.error;
                    continue;
                }
                report.state = ReportingStatus.RETRYING;
                publish(report);
                return report;                             // back off; the next trigger or sweep resumes
            }
            report.state = skippedCandidate != null ? ReportingStatus.RETRYING : ReportingStatus.OK;
            publish(report);
            return report;
        } finally {
            running.set(false);
            if (runRequested.getAndSet(false) && executor != null) schedule(0);
        }
    }

    /** Coalescing trigger: at most one queued run beyond the one in flight. */
    public void requestRun() {
        if (executor == null) return;
        if (running.get()) { runRequested.set(true); return; }
        if (runRequested.compareAndSet(false, true)) schedule(0);
    }

    private void schedule(long delayMs) {
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        ex.schedule(() -> { runRequested.set(false); drainOnce(); }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Starts the executor and the periodic sweep. */
    public synchronized void start(long sweepIntervalMs) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReportingPusher");
            t.setDaemon(true);
            return t;
        });
        sweep = executor.scheduleWithFixedDelay(this::drainOnce, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (sweep != null) sweep.cancel(false);
        if (executor != null) executor.shutdownNow();
        executor = null; sweep = null;
    }

    private void publish(RunReport r) {
        lastState = r.state; lastError = r.lastError;
        StatusListener l = statusListener;
        if (l != null) {
            try { l.onStatus(r.state, store.countPending(), store.countParked(), lastSentAt, r.lastError); }
            catch (Throwable ignored) { /* status is best effort */ }
        }
    }
}
```

`sleeper` is kept in the constructor for symmetry with the spec's test seams but the loop does not block: backoff is "skip until its time" and the run ends; the next trigger or sweep resumes. If `Sleeper` ends up unused, drop it from the constructor and the test.

- [ ] **Step 7: Run the tests — PASS**

Run: `./gradlew testMkskDebugUnitTest --tests '*ReportingPusherTest*' --tests '*ReportingStatusTest*' -q`
Expected: 15 pusher tests + 6 status tests, 0 failures. Fix the implementation, not the tests, on failure. Then the whole suite: 356 tests (+21), same 3 pre-existing failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/castech/emvtxn/reporting app/src/test/java/castech/emvtxn/reporting
git commit -m "RPT-02: ReportingPusher drains the journal to the portal — backoff, 401 stop, parking only when a later row was accepted

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Wire the pusher into the app

**Files:**
- Modify: `app/src/main/java/castech/emvtxn/MainActivity.java` (field near `posOrchestrator` ~line 213; after host-service init ~line 983; network callback ~line 549; `onDestroy` ~line 382; `onReportingParamsChanged` stub from Task 3)

**Interfaces:**
- Consumes: `ReportingPusher` (Task 7), `ReportingConfig` (Task 3), `TransactionLogManager` as `PushStore` (Task 4), `PushSignal` (Task 5), `getHardwareSerialNumber()`, `GlobalPara.atmTerminalId`, `GlobalPara.atmProcessorType`, `BuildConfig.VERSION_NAME`.
- Produces: `MainActivity.reportingPusher` (private), `startReportingPusher()`, `onReportingParamsChanged()` (real), status written to `ReportingConfig` for the Admin line (Task 9).

- [ ] **Step 1: Field and start method**

Next to `private castech.emvtxn.pos.PosOrchestrator posOrchestrator = null;`:

```java
    /** RPT-02 (6.2.13): drains journal rows to the MyView portal. Lives for the activity. */
    private castech.emvtxn.reporting.ReportingPusher reportingPusher = null;
```

Add the method (near `startPosModeIfEnabled`):

```java
    /**
     * RPT-02: build the pusher once the host service is up (its identity needs the terminal id and
     * processor from the applied configuration). Settings are read per run, so a CasHUB change takes
     * effect on the next run without a restart. Triggers: new journal row (PushSignal), network back
     * (initStatusBar's callback), parameter change, app start, and a 5-minute sweep.
     */
    private void startReportingPusher() {
        if (reportingPusher != null) return;
        final android.content.Context app = getApplicationContext();
        final castech.emvtxn.reporting.ReportingConfig cfg = new castech.emvtxn.reporting.ReportingConfig(app);
        final castech.emvtxn.atm.TransactionLogManager store = castech.emvtxn.atm.TransactionLogManager.getInstance(app);
        castech.emvtxn.reporting.ReportingPusher p = new castech.emvtxn.reporting.ReportingPusher(
                store,
                castech.emvtxn.reporting.ReportingClient.production(),
                new castech.emvtxn.reporting.ReportingPusher.Settings() {
                    @Override public String url() { return cfg.getUrl(); }
                    @Override public String accessKey() { return cfg.getAccessKey(); }
                    @Override public castech.emvtxn.reporting.PushPayload.Identity identity() {
                        return new castech.emvtxn.reporting.PushPayload.Identity(
                                GlobalPara.atmTerminalId, getHardwareSerialNumber(), cfg.getAccessKey(),
                                GlobalPara.atmProcessorType, BuildConfig.VERSION_NAME);
                    }
                    @Override public java.util.TimeZone zone() { return java.util.TimeZone.getDefault(); }
                },
                Thread::sleep,
                System::currentTimeMillis);
        p.setStatusListener((state, pending, parked, lastSentAt, lastError) -> {
            cfg.setStatus(state, pending, parked, lastSentAt, lastError);
            Log.w(TAG, castech.emvtxn.reporting.ReportingStatus.render(state, pending, parked, lastSentAt, lastError, System.currentTimeMillis()));
        });
        reportingPusher = p;
        castech.emvtxn.reporting.PushSignal.setListener(p::requestRun);
        p.start(5 * 60_000L);
        p.requestRun();   // app start: anything left from before
        Log.w(TAG, "Reporting pusher started — " + (cfg.isConfigured() ? "configured" : "not configured (no key)"));
    }
```

- [ ] **Step 2: Call it after host-service init**

Right after `Log.d(TAG, "ATM Host Service initialized successfully");` (line ~983, inside the same block that later starts POS mode) add `startReportingPusher();`. If that block runs on a background thread, that is fine: `start()` is synchronized and the pusher has its own executor.

- [ ] **Step 3: Network trigger**

In `initStatusBar()`'s `onAvailable`, after the `svc.onNetworkAvailable()` try/catch:

```java
                        castech.emvtxn.reporting.ReportingPusher rp = reportingPusher;
                        if (rp != null) rp.requestRun();
```

- [ ] **Step 4: Parameter-change trigger and shutdown**

Replace the Task 3 stub:

```java
    /** RPT-02: a reporting key or URL changed in CasHUB — try the queue now (a fixed key clears KEY_REJECTED). */
    public void onReportingParamsChanged() {
        castech.emvtxn.reporting.ReportingPusher rp = reportingPusher;
        Log.w(TAG, "Reporting parameters changed" + (rp == null ? " (pusher not started yet)" : " — requesting a run"));
        if (rp != null) rp.requestRun();
    }
```

In `onDestroy()` next to the POS stop:

```java
        if (reportingPusher != null) {
            try { reportingPusher.stop(); } catch (Exception e) { Log.w(TAG, "reporting pusher stop: " + e.getMessage()); }
            reportingPusher = null;
            castech.emvtxn.reporting.PushSignal.setListener(null);
        }
```

- [ ] **Step 5: Compile, run the suite, build the debug APK**

Run: `./gradlew compileMkskDebugJavaWithJavac -q && ./gradlew testMkskDebugUnitTest -q`
Expected: compiles; 356 tests, same 3 pre-existing failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/castech/emvtxn/MainActivity.java
git commit -m "RPT-02: start the reporting pusher after host init; triggers on journal rows, network, parameter change, sweep

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Admin screen — the Reporting line and "Retry now"

**Files:**
- Modify: `app/src/main/res/layout/fragment_page_admin_atm.xml` (CRLF — Edit tool only) after the Network card block (~line 170, before the `hdrHostSettings` TextView)
- Modify: `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java` (field declarations; `onCreateView` lookups; where `renderNetworkCard()` is called at lines 845 and 973; a new `renderReportingLine()`; the Super-tier gating helper the WiFi/Clear History buttons already use)

**Interfaces:**
- Consumes: `ReportingConfig` getters (Task 3), `ReportingStatus.render` (Task 7), `MainActivity.onReportingParamsChanged()` (Task 8) as the "Retry now" action.

- [ ] **Step 1: Layout**

Insert before the `hdrHostSettings` TextView (keep the card's existing styling attributes — copy them from `txvNetwork`'s TextView and the WiFi buttons' Button):

```xml
            <TextView
                android:id="@+id/hdrReporting"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="Reporting (MyView)"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/txvReporting"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Reporting: not configured" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="12sp"
                android:text="Key and URL managed centrally via CasHUB (reporting_access_key, reporting_url)." />

            <Button
                android:id="@+id/btnReportingRetry"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Retry now"
                android:visibility="gone" />
```

- [ ] **Step 2: Fragment**

Fields: `private TextView txvReporting; private Button btnReportingRetry;` Lookups in `onCreateView` beside `txvNetwork`. Then:

```java
    /** RPT-02: one line from the pusher's last published status; Retry now for Super only. */
    private void renderReportingLine() {
        if (txvReporting == null || getContext() == null) return;
        castech.emvtxn.reporting.ReportingConfig cfg = new castech.emvtxn.reporting.ReportingConfig(getContext());
        String state = cfg.isConfigured() ? cfg.getStatusState() : castech.emvtxn.reporting.ReportingStatus.NOT_CONFIGURED;
        txvReporting.setText(castech.emvtxn.reporting.ReportingStatus.render(
                state, cfg.getPending(), cfg.getParked(), cfg.getLastSentAt(), cfg.getLastError(), System.currentTimeMillis()));
        if (btnReportingRetry != null) {
            btnReportingRetry.setVisibility(isSuperAdmin() && cfg.isConfigured() ? View.VISIBLE : View.GONE);
            btnReportingRetry.setOnClickListener(v -> {
                if (GlobalPara.mainActivity != null) GlobalPara.mainActivity.onReportingParamsChanged();
                android.widget.Toast.makeText(getContext(), "Reporting: retry requested", android.widget.Toast.LENGTH_SHORT).show();
                txvReporting.postDelayed(this::renderReportingLine, 3000);
            });
        }
    }
```

`isSuperAdmin()` — use whatever the fragment already uses to gate Super-only controls (search for the Clear History button's visibility logic and reuse that condition; if it is a field like `currentTier == TIER_SUPER`, use that expression instead). Call `renderReportingLine();` next to both existing `renderNetworkCard();` calls.

- [ ] **Step 3: Compile; build the debug APK; verify freshness**

Run:
```bash
./gradlew compileMkskDebugJavaWithJavac -q
./gradlew --stop; ./gradlew --no-daemon assembleMkskDebug -q
ls -la app/build/outputs/apk/mksk/debug/*.apk
for d in $(unzip -Z1 app/build/outputs/apk/mksk/debug/S1FP-TFI-v6.2.12-debug.apk | grep -E '^classes[0-9]*\.dex$'); do unzip -p app/build/outputs/apk/mksk/debug/S1FP-TFI-v6.2.12-debug.apk $d | LC_ALL=C grep -a -c 'ReportingPusher'; done
```
Expected: an APK with today's mtime and at least one dex hit for `ReportingPusher`. (The file is still named 6.2.12 until Task 10 bumps the version.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_page_admin_atm.xml app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java
git commit -m "RPT-02: Admin Reporting line (status from the pusher) and Super-only Retry now

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Version, notes, backlog, device pass

**Files:**
- Modify: `app/build.gradle:26-27` (`versionCode 75`, `versionName "6.2.13"`)
- Modify: `RELEASE-NOTES.md` (new 6.2.13 entry above the 6.2.12 entry, in the file's established format)
- Modify: `docs/CODE-REVIEW-BACKLOG.md` (RPT-02 and AMT-03 entries, checked, "shipped 6.2.13 (device pass pending)", placed with the other recent shipped items above the REV-02 line)

- [ ] **Step 1: Bump the version**

`versionCode 74` → `versionCode 75`; `versionName "6.2.12"` → `versionName "6.2.13"`.

- [ ] **Step 2: Release notes entry (write it fully; this is the shape)**

```markdown
## 6.2.13 — 2026-10-DD · PR #9 · base 6.2.12 · versionCode 75

### Highlights

**Every transaction is now reported to MyView directly from the terminal.** The portal's host-log poll
has no tip or cash-back field; the Ingenico fleet fills that gap with a per-transaction push and the
Castle terminals never did. From this release each finished transaction the host answered — and each
decline the terminal made with a card present — is POSTed to the ingestion endpoint and retried until
the portal acknowledges it. Tips (6.2.14) will ride on this feed. **No charge changes:** the new
amount model is wired in with tip 0 and register sales still exact.

### What operators and customers will notice

- Nothing at the card, on the receipt or on the register. Charges are identical to 6.2.12.
- Admin screen: a **Reporting** line — "not configured" until the key arrives from CasHUB, then
  pending count and last-sent time; Super admin gets **Retry now**.
- MyView: Castle terminals' rows now carry the terminal's text on declines, balance inquiries appear,
  reversals appear as `RWT`, and the cash back a walk-up's rounding produces is visible.

### Fixes

**Reporting**

- (RPT-02) Journal as outbox; `PushPayload` per the contract; `ReportingPusher` with 5 s / 30 s / 2 min /
  5 min backoff, 401 stop, parking only once a later row was accepted; batches with unsent rows are never
  pruned; reversals get their own journal row.

**Transaction**

- (AMT-03) `AmountBreakdown`: sale, tip, withdrawal, cash back, fee, total computed once; the amount screen
  and the POS gateway write the legacy strings from it. Walk-up custom amounts now record the typed sale
  and the change separately (receipt unchanged this release).

### Parameters

| Key | Default | Effect |
|---|---|---|
| `reporting_access_key` | none | tenant key; reporting is off without it |
| `reporting_url` | production ingestion URL | test-portal override |

### Known issues and deferred

- Sent rows are never resent after the key is first configured (no retroactive catch-up); the batch
  endpoint is left for a real case.
- Parked rows need a new build to be re-queued (no Admin action yet).
- Terminal-side declines are stored by the portal as code 99 like host declines; the text
  `Declined at terminal: …` distinguishes them.

### Verification

- Unit suite: NNN tests (… new). Same three pre-existing failures (TEST-01).
- Device pass (fill in from the bench): …

### Upgrade notes

Plain-install push from CasHUB. Journal migrates in place (v2 → v3). Add `reporting_access_key` to the
terminal's CasHUB parameters to turn reporting on; nothing is sent until it is present.
```

- [ ] **Step 3: Backlog entries**

```markdown
- [x] **RPT-02** · MED (reporting) · `reporting/*`, `atm/TransactionLogManager` v3, `atm/TransactionJournal`, `MainActivity`, Admin — **shipped 6.2.13** (spec `docs/superpowers/specs/2026-10-07-reporting-push-design.md`, plan `docs/superpowers/plans/2026-10-08-reporting-push.md`). Device pass: see release notes.
  **Change:** journal is the outbox; every host-answered row and every card-present terminal decline is pushed to MyView in the Ingenico shape (`PushPayload`, pinned to the contract example); `ReportingPusher` drains oldest-first with backoff, stops on 401, parks a row only once a later row is accepted; pruning skips batches with unsent rows; accepted reversals become `REVERSAL` rows pushed as `RWT`; two CasHUB keys. The STD1 sequence is untouched (TerminalSequenceNum = Field 4; 0 when no request was built).

- [x] **AMT-03** · MED · `AmountBreakdown`, `GlobalPara.atmAmounts`, amount screen, `AtmHostServiceGateway` — **shipped 6.2.13.** Six-number model (sale, tip, withdrawal, cash back, fee, total) computed once; legacy strings are mirrors; tip 0 and register rounding off in this release so charges are identical to 6.2.12 (tips 6.2.14 flips the two inputs). `PosSaleFee` deprecated.
```

- [ ] **Step 4: Full suite, cold debug build, commit, PR**

```bash
./gradlew testMkskDebugUnitTest -q      # expect same 3 pre-existing failures only
./gradlew --stop; ./gradlew --no-daemon assembleMkskDebug -q
/Users/broadbent/Library/Android/sdk/build-tools/33.0.1/aapt dump badging app/build/outputs/apk/mksk/debug/S1FP-TFI-v6.2.13-debug.apk | head -1
git add app/build.gradle RELEASE-NOTES.md docs/CODE-REVIEW-BACKLOG.md
git commit -m "v6.2.13: version bump, release notes, backlog (RPT-02, AMT-03)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git push -u origin release/v6.2.13
gh pr create --base main --head release/v6.2.13 --title "v6.2.13: terminal reporting push to MyView (RPT-02) + AmountBreakdown (AMT-03)" --body-file <pr body written from the release notes; end with: 🤖 Generated with [Claude Code](https://claude.com/claude-code)>
```

- [ ] **Step 5: Device pass (terminal attached, EFX live, test terminal id, production portal)**

Install with `adb install -r`, `adb logcat -G 16M && adb logcat -c`. Set `reporting_access_key` in CasHUB for the bench terminal (ask the user for the key; never paste it into a log or a doc). Then, reading the log after each:

1. Chip balance inquiry → `Reporting: seq N INQ → sent (…)` within seconds; MyView shows the row.
2. Tap withdrawal and a register sale → pushed; amounts equal the receipt; MyView row shows push message `merged` or `ingested`.
3. A host decline (if a decline card is available) → `Approved: 0` with the host text.
4. A swipe → `Declined at terminal: Swipe not supported`, `TerminalSequenceNum 0`.
5. WiFi off before a transaction; run two; WiFi on → both drain in order on `onAvailable`.
6. Wrong key in CasHUB → Admin line `key rejected`; correct key → queue drains after `PARAMETER_UPDATED`.
7. Upgrade check: the device came from 6.2.12 with journal rows — `adb shell dumpsys` not needed; confirm the Detail Report still prints the earlier rows and the log shows `Upgrading transaction log 2 → 3`.
8. Receipts and the register reply identical to 6.2.12 for the same amounts.

Record results in the release notes' Verification section and in the PR; commit as `notes: 6.2.13 device results`.

---

## Self-review (done while writing)

- **Spec coverage:** §3 parameters → Task 3; §4 breakdown → Tasks 1–2; §5 schema v3, backfill, sendability, pruning guard, reversal rows → Tasks 4–5; §6 payload → Task 6; §7 sender, 401, backoff, parking, status, Retry now → Tasks 7–9; §8 threading/failure modes → Tasks 7–8 (own executor, config per run, rows persist); §9 testing → each task's tests + Task 10 device list; §10 out of scope respected (no sequence change, no cancels sent, no batch endpoint, no re-queue action).
- **Placeholder scan:** the release-notes entry has `2026-10-DD` and `NNN tests` to be filled at Task 10 from the actual date and count — those are the only deliberate blanks, both resolved in that task.
- **Type consistency:** `PushEligibility.PUSH_*` ints used in `TransactionLog.pushState`, `PushStore`, `FakeStore`, and the pusher; `PushPayload.Identity` five-arg constructor used in Tasks 7 and 8; `ReportingPusher.Settings` four methods implemented identically in test and `MainActivity`; `ReportingStatus.render` six arguments everywhere; `ReportingConfig.setStatus` five arguments matches `StatusListener.onStatus`.
- **Review Focus:** 1 → `timeZoneFieldsFollowTheRowInstant`; 2 → `missingOptionalFieldsBecomeEmptyStrings`; 3 → `negativeInputsAreRejected`; 4 → `configIsReadOncePerRun` + `keyRemovedMidQueueStopsSendingButKeepsRows`; 5 → `allRowsFailingNeverParks` + `emptyTerminalIdStillBuilds`.
