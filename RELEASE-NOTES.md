# Cashless ATM — Release Notes

Castle S1F4 PRO · package `castech.emvtxn` · production APK `S1FP-TFI-v<version>.apk` (mksk release flavor)

Newest release first. Every versioned PR adds an entry here **before** it is merged; the
entry is part of the PR. Readers: TFI engineering, operations/support, and partner teams
(Axium) who follow what changed and why — so entries say what an operator or a customer
will notice, not only what the code does. Ticket IDs (`HOST-04`, `ADM-05`, …) refer to
`docs/CODE-REVIEW-BACKLOG.md`, which carries the full failure scenario for each.

---

## How to write an entry (going forward)

1. **One entry per versioned PR**, added on the release branch as its last commit, so
   the notes ship with the code they describe. Version bump (`app/build.gradle`
   `versionName` / `versionCode`), release-notes entry, PR, merge, then tag `v<version>`
   on the merge commit (`git tag -a v6.2.6 -m "…" && git push origin v6.2.6`).
2. **Header line:** version · date · PR link · base version · `versionCode`.
3. **Sections, in this order, omitting any that are empty:**
   - **Highlights** — 2–4 sentences a non-engineer can act on.
   - **What operators and customers will notice** — visible behaviour changes, screen text,
     new prompts, anything support will get asked about.
   - **Fixes** — grouped by area (Host / Transaction / Admin / Security / POS / Other).
     One line each: the symptom first, the cause second, the ticket ID last.
   - **Security / PCI** — always call these out separately, even if also listed above.
   - **Known issues and deferred** — what is *not* in this release and where it is tracked.
   - **Verification** — what was tested, on what, and what was not.
   - **Upgrade notes** — install method (CasHUB plain-install push), config migration,
     anything that must be done on the terminal after the update.
4. **Write the symptom, not the diff.** "A single connect failure could take the terminal
   out of service" — not "moved `requestSentToHost` after `ensureConnected()`".
5. Never put card data, keys, PINs, credentials or customer identifiers in this file.

Template:

```markdown
## 6.2.X — YYYY-MM-DD · PR #N · base 6.2.(X-1) · versionCode NN

### Highlights
### What operators and customers will notice
### Fixes
**Host / network** · **Transaction** · **Admin** · **Security / PCI** · **POS** · **Other**
### Known issues and deferred
### Verification
### Upgrade notes
```

---

## 6.2.12 — 2026-10-05 · PR #8 · base 6.2.11 · versionCode 74

### Highlights

**Tapped cards now go to the processor.** Until this release a tap was never sent to the host.
The terminal read the card, took the PIN, and then declined the transaction itself with
"91 — Host service not available" — a host response code for a request the host never saw. Only
an inserted chip card actually reached the processor. **A register is answered at once when a
transaction ends on the terminal.** A POS-driven transaction that finished locally used to
leave the register waiting for the 300-second slot watchdog, and every POS command in between
was refused as busy. Both were found from one field report (2026-10-05): a POS balance
inquiry that "got a 91" and then took exactly 300 seconds to answer. It was a tap.

### What operators and customers will notice

- **Tap works like insert.** PIN, "Online Processing", the processor's answer, receipt. Walk-up
  and register-driven alike.
- **Swipe is still declined at the terminal** — there is no host path for swiped cards in this
  app — but it now says so: the screen and receipt read **SWIPE NOT SUPPORTED** (code `MSR_NA`)
  instead of "HOST SERVICE NOT AVAILABLE" / 91.
- **A 91 now means the processor said 91.** Before 6.2.12 a 91 on a receipt, in the journal or
  on the Detail Report's declined count could be the terminal's own decline of a tap or swipe.
  (One exception remains — see Known issues.)
- **At a POS site**, a transaction that ends on the terminal — swipe, unreadable card, card
  read error, PIN pad abandoned, a terminal fault — reaches the register within a few seconds
  as `declined` with a terminal code, and the terminal accepts the next POS command straight
  away. Nothing changes for approvals, host declines or Cancel.

### Fixes

**Transaction**

- A tapped card was declined at the terminal as "91 / Host service not available" and never
  sent to the processor. The contactless host send sat inside the branch for the sample app's
  *QuickChip* checkbox, which nothing ever ticks, so it could not run; taps fell through to
  the last branch of the chain. The routing is now an explicit, tested decision
  (`OnlineRoute`): tap → host, inserted chip → host, swipe → terminal decline. `TAP-01`
- The contactless send would have put a hex dump of the track in Field 6. The tap reader hands
  Track 2 over as text (`;PAN=EXPIRY…?` plus an LRC byte); that block treated it as BCD.
  `Track2PanExtractor.toHostTrack2` reads either encoding, drops the LRC, and falls back to
  Tag 57 when the reader gives no track. A tap with no usable Track 2 at all is declined at the
  terminal (`NO_TRACK2`, "Card not readable - insert card") rather than sent. `TAP-01`
- Track 2 from a tap is validated before it goes into the host message. Those bytes come from
  the card, and Field 6 sits in a field-separated message: only digits and one `=` within
  ISO 7813 lengths are accepted, so a crafted card cannot add fields to the request. (Raised by
  the security review of the first cut of this change, which copied the bytes.) `TAP-01`
- A swiped card reported a made-up host code. It now reports `MSR_NA` / "Swipe not supported".
  `TAP-01`

**POS**

- A POS transaction that ended on the terminal without a host result answered the register
  only when the 300-second watchdog fired (as `host_unreachable`), and the terminal refused
  POS commands as `terminal_busy` until then. Only the host callbacks and Cancel answered the
  register. The transaction thread now remembers the POS slot it was started for and answers
  it on every exit path if nothing else has: `declined`, with the terminal's own code and
  reason (the ones the receipt shows), `user_cancelled` when nothing was recorded, or
  `terminal_error` when the terminal itself failed. It stays silent while a request is still
  with the host and after an approval, so a late approval can still land. `POS-13`

### Known issues and deferred

- **Tap has not yet run against the processor.** See Verification. The first taps on the bench
  decide whether the contactless EMV data (Field 13) is accepted as built.
- **Swipe has no host path** (`MSR-01`). It needs the reader's encrypted track and a PIN-block
  decision with the MUX; not in this release.
- **PIN retry on a register-driven transaction** (`POS-14`, found during this work, **not
  fixed**): on a first "55 Incorrect PIN" the register is told `declined 55` immediately while
  the terminal re-prompts the PIN; if the retry is then approved, the register is never told.
  Money can move on a sale the register shows as declined. Walk-up is unaffected.
- **`atmTransactionInProgress` is cleared at the start of every transaction** (`TXN-01`, found
  during this work, not fixed): the flag is set and then reset on the next line, so the guards
  that read it do not protect the card and PIN phase. Read from code; not yet observed on a
  terminal. The live thread check still prevents two
  transaction threads.
- Two "91 / Host service not available" assignments remain for *host service not initialised*.
  They sit behind the readiness gate and are not expected to be reachable.
- The proxy team's two requests in `CASTLE-HOST-TIMEOUT-91-2026-10-05.md` (fail fast on connect
  failure; cap the host leg at 60 s) are **not** taken: the host leg was never started in the
  reported cases, and a 60 s cap would abandon live authorizations on the busy path.

### Verification

- Unit suite: **299** tests (37 new: Track 2 for the host ×16 including hostile input,
  routing ×6, slot-safe local answer ×5, register wording ×10). Each new test was watched failing first. The same three
  pre-existing failures remain (`TEST-01`).
- The defect itself is on record from the terminal: a tap on 2026-09-02 logged PIN accepted,
  kernel result "go online", and the terminal's own decline in the same millisecond, with no
  host connection.
- **Not yet run on a terminal.** Bench pass required before this ships:
  1. Tap, walk-up balance inquiry and withdrawal → processor answers; receipt last-4 correct.
  2. Tap from the register (balance inquiry and sale) → register receives the host result.
  3. Swipe from the register → `declined MSR_NA` within seconds; next POS command accepted.
  4. Abandon the PIN pad on a register transaction → `user_cancelled` within seconds.
  5. Inserted chip, walk-up and register → unchanged.
  6. A Mastercard tap as well as a Visa tap (the tag sets differ — `TAP-02`).

### Upgrade notes

Plain-install push from CasHUB. No parameter changes, no configuration migration.

---

## 6.2.11 — 2026-10-01 · PR #7 · base 6.2.10 · versionCode 73

### Highlights

**Detail Report.** Ellipsis ▸ **Detail Report** prints every approved withdrawal in the current
batch, one block each, followed by a summary — modelled on the sample receipt, laid out for our
32-column printer. Behind it the terminal now keeps a **transaction journal**: one row per
finished transaction (approved, declined, cancelled, balance inquiry), walk-up and POS alike.
The log table existed since the first builds but nothing ever wrote to it. Batches are
**terminal-owned**: batch 001 opens on first use, a successful Close Batch closes it and opens
the next, and the batch number now prints on the Close Batch and Host Totals receipts too.
Spec: `docs/superpowers/specs/2026-10-01-detail-report-design.md`.

### What operators and customers will notice

- **Ellipsis menu** has a third entry, Detail Report. Same gate as Host Totals: a confirm
  dialog that names the batch, the approved count and the total since the batch opened — no
  PIN. **Print**, **View** (on-screen, also used automatically when the printer is out of
  paper), or Cancel.
- **Detail lines** are approved withdrawals only: card last four, CWDR, card type (DB/CR), entry
  mode (C chip / T tap / S swipe), our sequence number; a clerk / invoice line for register
  sales that carry them; AUTH ("--" when the host sent none) and REF; AMT, FEE, TIP (always
  present, $0.00 until tips ship) and TOTAL.
- **Summary** by card type: withdrawals count and amount, fees, tips, total; then counts of
  declined, cancelled, balance inquiries and reversed. A withdrawal the processor **reversed**
  counts under Reversed whatever the terminal recorded for it (approved, or timed out before
  the answer), and leaves the money totals. A cancel with no host contact at all (PIN pad
  cancel, three bad swipes) counts under Cancelled. Footer: "Terminal record - processor
  totals govern".
- **Close Batch** and **Host Totals** receipts show `Batch #: 00N`.
- **Admin → Clear Transaction History** (Super) now does something: it deletes the rows of
  closed batches and never touches the open batch.
- **Customer disclaimer** (`DIS-01`): tapping Withdrawal or Balance Inquiry on the main menu
  first shows "DISCLAIMER — This transaction may incur additional fees from your bank. Contact
  your bank for further details." with an **OK** button and a plain **Cancel** link beneath it.
  OK continues into the usual flow, Cancel returns to the menu. Walk-up only; register-driven
  POS sales never pass through the menu. Wording lives in the string resources.
- Nothing changes on the wire or in CasHUB.

### Fixes / changes

**Reporting (`RPT-01`)**
- `atm/report/DetailReport` + `ReportRow` (pure, 11 tests written first): layout, amounts up
  to $99,999.99 within 32 columns (the TOTAL column shifts one character at $10,000+), empty
  batch, reversed exclusion, credit group, long clerk/invoice wrapped onto two lines,
  three-digit counts.
- `atm/TransactionJournal` records at the single completion point of the transaction thread
  (`JournalOutcome` mapping tested first); `GlobalPara.atmSequenceNumber/atmClerkId/atmInvoiceNo`
  carried from the request builder and the POS executor and cleared once consumed; an accepted
  reversal marks its row by the shared pre-send reversal id (never by the per-session sequence
  number, which restarts at 1 on every app start).
- Review pass (fresh reviewer, 2026-10-01): reversal identity keyed on the pre-send id; Clear
  History button made reachable; no-host-answer cancels classified as Cancelled; fresh paper
  check before printing the report; report refused while a transaction is running; clerk and
  invoice never clipped; three-digit counts; chronological order across restarts.
- `TransactionLogManager` schema v2 with a **data-preserving migration** (the old `onUpgrade`
  dropped the table), `batches` table, batch queries, retention of the last 20 batches at close
  (`BatchMath`, tested).

### Known issues and deferred

- Reprint of a previous batch (the store keeps 20; no menu entry yet). Sending the report to
  MyView. Tips (column reserved).
- `REV-02`, `POS-12`, R2, `HOST-14`, `LOG-01`, `TEST-01` — unchanged.

### Verification

- `DetailReportTest` (11), `BatchMathTest` (3), `JournalOutcomeTest` (7) — all RED before the
  code. Full suite 262 tests; the 3 pre-existing `TEST-01` failures only.
- Device (terminal …680, debug build of a64dfb9, 2026-10-02, operator-run): balance inquiry →
  journaled `BALANCE_INQUIRY/APPROVED seq=1 batch=1`; withdrawal $10 + $3.50 → journaled
  `WITHDRAWAL/APPROVED seq=2 batch=1` under its pre-send reversal id; Detail Report printed;
  Host Totals printed; Close Batch → "Batch 1 closed; batch 2 opened", close receipt printed.
  Disclaimer verified from the view hierarchy (OK → amount screen, Cancel → menu). Not yet
  exercised on hardware: POS sale clerk/invoice line, a decline and a cancel on the report,
  Clear History, a reversal moving its row to Reversed, the out-of-paper fallback.

### Upgrade notes

- Installs in place over 6.2.10; the journal database migrates in place. Batch 001 opens the
  first time the journal or the report is touched after the update.

## 6.2.10 — 2026-10-01 · PR #6 · base 6.2.9 · versionCode 72

### Highlights

**Cellular support, managed from CasHUB.** A terminal with a SIM already transacts over LTE
with no app change (verified 2026-10-01 on an AT&T SIM: key download, POS and host traffic
over cellular). What 6.2.10 adds is the operability around it: the **APN can be pushed as a
CasHUB parameter** through Castle's settings service, the Admin screen gets a **Network card**
showing what the terminal is actually using, and the status strip stops showing a frightening
0% when the OS reports no battery.

### What operators and customers will notice

- **CasHUB keys** `apn` (required), `apn_name`, `apn_user`, `apn_password`, `apn_auth_type`,
  `apn_protocol`. Same rules as every other key: absent means untouched, a bad value is logged
  and ignored, applied at boot and live on a push. Apply-and-report, no automatic rollback
  (decision D9): units are provisioned on WiFi and a SIM unit keeps WiFi alongside, so a wrong
  APN is always correctable from CasHUB.
- **Admin → Network** (read-only): transport in use (WiFi / Cellular / none, with "no
  internet" when unvalidated), SIM carrier, registered network, data state, signal bars, APN
  in use, the last CasHUB APN push and its outcome with time, and "Battery: NOT PRESENT"
  when the OS says so.
- **Status strip** shows "No batt" instead of 0% when the OS reports no battery present.
- **Admin → WiFi Configuration:** Connect WiFi and Refresh Status are stacked and the same
  size; a **WiFi on/off toggle** sits beside Connect. The toggle shows the radio's real state,
  switches it through Castle's settings service, and asks first when WiFi is the terminal's
  only connection (no cellular data), since turning it off takes the unit offline.

### Fixes / changes

**Admin (`ADM-08`)**
- WiFi section rearranged as above; the toggle snaps back and toasts if the settings service
  refuses; both tiers.

**Network (`NET-01`, `NET-02`, `NET-03`)**
- `net/ApnParams` (pure, tested first) parses and validates the keys; the password is masked
  in every diagnostic dump and never logged.
- `net/ApnApplier` reads the current APN from `CTOS.CtSettings`, skips when nothing material
  differs, otherwise writes the entry for the SIM's MCC/MNC (`setApn3`, falling back to
  `setApn`), selects it and enables mobile data; outcome persisted and logged as
  `APN apply applied|unchanged|failed: …`.
- `CasHubParams` routes the keys to the applier and keeps them out of the host-config
  payload and the KMS-II backup.

### Known issues and deferred

- The first APN on a cellular-only unit must still be set at staging (CasHUB cannot deliver
  the parameter to a terminal with no connectivity). Deployment note.
- Registration reject cause is not shown on the card (needs `READ_PHONE_STATE`, a runtime
  grant); the Data state line is the practical signal.

### Verification

- `ApnParamsTest` (9) — written before the class: defaults, full set, auth/protocol fallbacks,
  blank/illegal APN, other keys without `apn`, change detection, masking.
- Full suite 241 tests; the 3 pre-existing `TEST-01` failures only.
- Device: pending — on the AT&T unit: push `apn=BROADBAND` → log `APN apply unchanged`;
  push `apn_protocol=IPV4V6` → `setApn3 rc=…`, `APN apply applied`, LTE data still connected;
  Network card shows Cellular / AT&T / connected / APN in use; strip shows "No batt" while the
  pack is out; WiFi toggle off (confirm shown with the SIM out, no confirm with LTE up) and on
  again, Connect and Refresh the same width.

### Upgrade notes

- Installs in place over 6.2.9. WiFi-only terminals are unaffected.

## 6.2.9 — 2026-09-30 · PR #5 · base 6.2.8 · versionCode 71

### Highlights

**The terminal no longer edits, or names, its processor.** Host settings, fee configuration
and withdrawal limits are now managed only through CasHUB and shown read-only in Admin for
both tiers. The processor appears as a short code (E1, S1, D1, F1, C1), never by name, and
the host address is not shown at all — only the code, the port and the terminal ID. The
single host action left on the terminal is **Request New Working Key**, available to Admin
and Super Admin alike. Test Connection and Download Keys are gone (Download Keys performed
the same key download as Request New Working Key).

### What operators and customers will notice

- **Admin → Fee Configuration / Withdrawal Limits / Host Settings** are read-only cards
  labelled "Managed centrally via CasHUB". Nothing there can be typed or saved; a CasHUB push
  changes them (live, or at the next boot).
- **Host Settings** shows `Processor: E1 · Port 9020 · TLS` and `Terminal ID: MP001194`
  (full ID, since field techs quote it). The host address is never displayed.
- A terminal that CasHUB has never provisioned shows **"Awaiting configuration from CasHUB"**
  in red instead of an empty form, and Request New Working Key refuses with the same message.
- **Save Settings** now saves only the POS-mode section.
- Nothing changes for customers, for the CasHUB parameter values (they stay `EFX`,
  `SWITCH_COMMERCE`, …) or for the wire.

### Fixes / changes

**Admin screen (`ADM-06`, `ADM-07`)**
- Removed the on-terminal write path for host, fee and limit values (the Admin prefs
  `processor_index`, `host_address`, `fee_*`, `limit_*` keys are no longer written; boot
  reads `atm_host_settings` / `atm_settings` as before, then CasHUB overrides). `updateGlobalPara`
  and the spinner index conversions are gone, so the Admin screen can no longer overwrite a
  CasHUB value with a stale widget state.
- `ProcessorLabel.codeFor(processorType)` — display mapping; unknown → `--`.
- TLS is always on (there was a checkbox; every processor uses TLS).

### Known issues and deferred

- POS-mode section still editable on the terminal (also CasHUB-managed since 6.2.7) — lock
  it the same way if wanted.
- `REV-02`, `POS-12`, R2, `HOST-14`, `LOG-01`, `TEST-01` — unchanged.

### Verification

- `ProcessorLabelTest` (3) — written before the class: codes, case/whitespace tolerance,
  unknown → neutral. Full suite 232 tests; the 3 pre-existing `TEST-01` failures only.
- Device (terminal …680, debug build of 524a375, 2026-09-30): boot applied the CasHUB host
  and POS parameters, host service initialized, POS connected; Admin cards, processor code,
  no host address and Request New Working Key checked by the operator ("6.2.9 looks good").

### Upgrade notes

- Installs in place over 6.2.8. Terminals already provisioned from CasHUB need nothing. A
  terminal that was configured only by hand in Admin keeps working on its persisted values
  but can no longer be changed on the terminal — push its parameters from CasHUB.

## 6.2.8 — 2026-09-25 · PR #4 · base 6.2.7 · versionCode 70

### Highlights

**Amount screen changes.** The preset buttons are now **$10, $20, $40, $60, $100, $200**
($500 removed, $10 added, still ascending), and a preset outside the terminal's configured
limits is greyed out instead of failing when tapped. **Custom amounts round up to the next
multiple of the minimum.** The configured minimum is also the step: with a $10 minimum,
$12.50 becomes $20 and $5 becomes $10; $20 stays $20. Previously an entry under the minimum
was refused and anything in range went to the host exactly as typed, cents included. The
rounding was pulled out of 6.2.7 because that build had already shipped to terminals when
the rule was decided.

### What operators and customers will notice

- **Preset buttons:** $10 · $20 · $40 · $60 · $100 · $200. A site with a $20 minimum
  sees the $10 button greyed; a site with a $100 maximum sees $200 greyed. Presets are
  exact and never round.
- **Amount screen, custom entry:** the rounded amount is what the screen shows, with a
  brief "Rounded up to $20.00 (withdrawals in $10.00 steps)" note. The customer still
  presses Continue. The maximum still applies to the rounded amount.
- **POS-driven sales:** the register sends the cash amount; the terminal adds its own fee
  and tells the register the applied surcharge and total. Preset/rounding rules do not
  apply to register amounts.

### Fixes

**Money handling (`CENTS-01`, `FEE-01`, `FEE-02`, `BI-01`)** — found while reading the terminal log after the
amount-screen work; all three were in the shipped 6.2.7 and earlier.
- **Dollars-to-cents conversions rounded, not truncated.** `(int)(2.95 * 100)` is 294 in
  binary floating point, so a $2.95 fee reached the host as $2.94 while the receipt said
  $2.95; the same cast sat under the chip amount, the host amount and the surcharge. All of
  them now go through `Money.toCents` (rounds) and the receipt strings are derived from the
  same integers, so screen, receipt, chip and wire can no longer disagree by a cent.
- **The surcharge on the wire is the fee the receipt shows.** It was always the configured
  *flat* fee, even when the terminal is in percentage-fee mode and even for a POS sale whose
  surcharge came from the register. Now it is taken from the fee already shown to the
  customer, with the fee configuration as the fallback.
- **POS sales: the fee is the terminal's (decision D7).** A register no longer has to send a
  surcharge, and one it does send is never applied; the terminal's own fee configuration
  (flat or percentage, pushed via CasHUB) sets the fee exactly as for a walk-up, and the
  reply to the register now reports the surcharge actually applied plus `total_cents`.
  This also makes the chip amount identical for both paths (amount + fee), closing the
  EMV-01 question.
- **A balance inquiry's chip amount is always zero.** It was read from a hidden legacy field
  on the transaction page, which held 0 or $10.00 depending on when the screen had been
  pre-created, so the cryptogram amount for a balance inquiry varied between runs. The
  request to the host was always $0 and was approved either way.

**Amount entry (`AMT-01`, `AMT-02`)**
- `AmountRounding` (pure, cents arithmetic; the dollar overload converts through cents so
  binary-double noise cannot pick the wrong step) applied in the custom-amount dialog.
- `AmountPresets` is the single source of truth for the six preset buttons (labels, click
  amounts, order) and for whether a preset is offered under the current min/max; the
  screen re-applies the limits every time it is shown, so a CasHUB limit change takes
  effect without a restart.

### Known issues and deferred

- MyView alert and remote resolve for a pending reversal (`REV-02`), POS/SYS release switch
  (`POS-12`), host-layer robustness (R2), `HOST-14`, `LOG-01`, `TEST-01` — unchanged.

### Verification

- `AmountRoundingTest` (6) — written before the helper: below-minimum, between steps, exact
  multiples, non-whole-dollar minimum, no usable step, float-drift safety.
- `AmountPresetsTest` (5) — written before the class: the exact ascending list, below-min
  and above-max presets not offered, non-positive limits hide nothing.
- `MoneyTest` (5) — written before the class: rounding of awkward values ($2.95, $1.15,
  $4.35, 10 + 0.3), flat and percentage fees in cents, formatting, and receipt/wire agreement.
- `PosSaleFeeTest` (3) — written before the class: terminal fee governs whatever the register
  sent (flat and percentage), mismatch flagged for the log, chip amount = amount + fee.
- Full suite 229 tests; the 3 pre-existing `TEST-01` failures only.
- Device (terminal …680, 2026-09-25, debug build of 7c7b437): balance inquiry logs
  `strAmount=0` (was 0 or 1000 before); withdrawal via a custom amount that rounded up to
  $10 → chip 1350, host `amount=1000 surcharge=350`, receipt $10.00 / $3.50 / $13.50 —
  all one set of numbers; approved, pre-send reversal record cleared on approval; six
  preset buttons in two rows, all active at the $10 minimum. 2026-09-27 via the MyView
  proxy (CasHUB `pos_enabled=true` applied at boot): POS sale with no register surcharge →
  terminal fee applied (`surcharge(applied)=350`), host `amount=1000 surcharge=350`, approved,
  reply `surcharge=350`, receipt $10.00 / $3.50 / $13.50; POS balance inquiry approved with chip
  amount 0; batch close (host totals + reset) printed. Still to run: custom $501 → "Amount too
  high", a $20 minimum greying the $10 button, and the 6.2.7 reversal path.

### Upgrade notes

- Installs in place over 6.2.7 (same signing key). No settings change; the step is the
  existing `min_amount` parameter.

## 6.2.7 — 2026-09-24 · PR #3 · base 6.2.6 · versionCode 69

### Highlights

**A stuck reversal no longer anchors the terminal.** A field terminal running 6.2.6 sat for
weeks refusing every customer with "processing pending transactions" and printing
"REVERSAL IN PROGRESS" on strangers' receipts, because one reversal record the host kept
rejecting was retried on every customer, forever. 6.2.7 replaces that behaviour with a
policy: customers are held only while a reversal is actually being drained; once its
retries are exhausted the terminal trades again, shows a service-required banner, and keeps
retrying that record in the background (every 15 minutes, on network recovery, at boot).
Only a *pattern* — two failed records — takes the terminal out of service. The Admin screen
now shows each record with its attempts and the host's last answer, with per-record
**Retry now** and a Super-only **Resolve** that requires a reason and keeps the record in
history. "Clear All Pending" is gone.

**POS mode can now be configured centrally.** The three POS-mode settings — on/off, proxy
URL and terminal access key — are CasHUB parameters, entered in CasHUB by TFI operations
exactly like the terminal's host parameters, and a pushed change takes effect on the
terminal immediately (the POS connection restarts; no reboot). This closes the gap behind 6.2.6's
ADM-05: the POS flag is centrally owned instead of living only in an on-terminal checkbox.

### What operators and customers will notice

**Reversals**
- **Customers wait only while a drain is running** ("Please wait — processing pending
  transactions"). After the drain gives up on a record, the next customer transacts
  normally.
- **Bottom banner:** "SERVICE REQUIRED — a prior transaction is awaiting reversal.
  Transactions continue." while one failed record exists; "OUT OF SERVICE — pending
  reversals. Contact TFI." at two. It shares the banner with the out-of-paper notice.
- **Out of service is recoverable in the field:** a background retry that succeeds, an
  Admin **Retry now** that succeeds, or a Super-admin **Resolve** brings the failed count
  under two and the terminal returns to service on its own — no reboot, no reinstall.
- **Admin → Reversal Management** lists each pending record: time, sequence, amount,
  status, attempts, last attempt, RRN and the host's last response (for example
  `host responded 06 (…)` or `host unreachable (reconnect failed)`), plus the gate state.
  **Process Pending Reversals** now reports *why* a record failed, not just a count.
- **Resolve (Super admin only)** removes a record without sending it. A reason is
  required and is kept in the reversal history and journal with who resolved it. Use it
  only after the processor confirms the original was declined or already reversed.
- **Receipts** show the REVERSAL block only for the customer's own transaction. Progress
  for an older record never reaches another customer's receipt again.

**POS mode configuration**
- **CasHUB can turn POS mode on or off and set the proxy URL and access key** per
  terminal, entered manually in CasHUB like the host parameters. Absent keys leave the terminal's local value alone, exactly as the host
  settings behave; CasHUB wins for any key it carries, at every boot and on a live push.
- **A pushed change applies live.** The terminal's POS connection restarts on the new
  settings within seconds. If a register transaction is in flight at that moment, the
  restart waits for it (up to 60 s) so the register still gets its answer.
- The Admin screen's POS section shows the pushed values (it reads the same store).
- Nothing changes for walk-up-only terminals.

### Fixes

**Reversals (`REV-01`)**
- Gate decisions come from one place (`ReversalGatePolicy`): FAILED records no longer
  count as drainable for the customer gate, so a rejected record is not retried on every
  customer; the post-transaction drain attempts active records only.
- After exhaustion the terminal returns to READY (trade-and-alert) instead of a permanent
  `OUT_OF_SERVICE` that only a restart cleared. Safety stop at two FAILED records; lifted
  automatically when the count drops back under two.
- Background retry schedule for FAILED records (15 min, network recovery, boot, Admin),
  never on a customer's transaction; one drain at a time on the drain executor (the old
  Admin/boot path ran its own thread and could race the drain).
- The host's actual answer (response code / connection error) is stored on the record as
  its last error and shown in Admin; previously only "All 5 retries exhausted".
- A record left in PROCESSING by a crash or power cut mid-drain is drained at the next
  opportunity instead of being orphaned (it was neither drainable nor counted).
- Operator alerts no longer route through the transaction error path (which failed the
  current transaction and could answer the POS); they log and drive the banner.
- Per-record `Retry now` and `Resolve` (reason required, Super only) replace `Clear All
  Pending`; resolutions are journaled (`resolve` event) and visible in history.

**Configuration**
- `pos_enabled`, `pos_proxy_url`, `pos_terminal_access_key` are recognised CasHUB
  parameters (`CFG-01`). `pos_proxy_url` must be `wss://` or `ws://`; an invalid value is
  logged on the terminal and ignored rather than breaking the connection.
- The `cashub` CLI knows the new keys (no "unknown key" warning).

### Security / PCI

- The terminal access key is a bearer credential. It is kept out of the host-config path
  and the KMS-II backup, never written to the log, and masked in the parameter diagnostic
  dump. Treat CasHUB parameter manifests that contain it as secrets.

### Known issues and deferred

- POS/SYS manual release switch (`POS-12`) — on hold; will be 6.2.8 when resumed.
- MyView alert and remote resolve for a pending reversal (`REV-02`) — 6.2.8. Until then the
  terminal's banner and the Admin screen are the only signals.
- Host-layer robustness (backlog R2), `HOST-14`, `LOG-01`, `TEST-01` — unchanged from 6.2.6.

### Verification

- `ReversalGatePolicyTest` (10), `ReversalProgressTest` (4), `ReversalRecordHistoryTest`
  (3) — written before the code: gate outcomes incl. the safety stop and its precedence
  over a running drain, status classification (PROCESSING-after-crash), receipt scoping of
  progress messages, resolved-with-reason history entries.
- `PosParamsTest` — 10 JVM tests written before the parser: boolean/URL/key parsing,
  invalid values ignored with a reason, change detection, access-key masking.
- Full suite 210 tests; the 3 pre-existing `TEST-01` failures only.
- Device: pending — (1) reversal: pull WiFi during "Online Processing…" on a withdrawal,
  confirm the record promotes, the drain runs once on the next customer, the banner
  appears after exhaustion and the next customer transacts; retry from Admin after WiFi
  returns and confirm the banner clears; (2) CasHUB: push the three POS keys to terminal
  …680, confirm `Applied CasHUB POS config` and a fresh `POS connected` on the new URL
  without a reboot; then a register balance inquiry.

### Upgrade notes

- CasHUB plain-install push of `S1FP-TFI-v6.2.7.apk`. No configuration migration.
- To move a POS site under central control, push `pos_enabled`, `pos_proxy_url` and
  `pos_terminal_access_key` for that terminal once; from then on the terminal follows
  CasHUB. Sites not pushed keep their local settings.

---

## 6.2.6 — 2026-09-18 · [PR #2](https://github.com/toddb-amt/Castle-Triton/pull/2) · base 6.2.5 · versionCode 68

### Highlights

The go-live hardening release. A full pre-release code review of the host/network layer,
the transaction thread, the admin screen, key handling and the POS proxy client produced
46 findings; the 16 that affect money, availability or PCI are fixed here, each as its own
commit, plus four more found during the on-terminal verification pass. The recurring
"terminal ends up in a bad state after a network blip" behaviour had specific causes in the
host layer — all four are fixed. Card data no longer appears in logs. POS mode survives an
admin Save. Also carries the two-tier admin access and the toolbar WiFi/battery indicators
that were verified on device before the review.

### What operators and customers will notice

- **Admin has two PINs.** The Super Admin PIN unlocks everything. The Normal Admin PIN
  unlocks Reversal Management, Transaction History, WiFi Configuration, Diagnostics and
  *Request New Working Key*; the other sections stay visible but greyed out. The "Change
  Default PIN" prompt is gone — PINs are managed by TFI only.
- **Toolbar shows connectivity and power:** WiFi fan or cellular bars (whichever transport
  is active), battery level with percentage, and a bolt while charging.
- **Cancel during "Online Processing…" no longer drops to the menu.** Once the request is on
  its way to the processor the screen shows *"Please wait — finishing transaction…"* and
  then the real result. Cancel still works instantly before a card is presented.
- **Clear All Pending reversals is Super Admin only** — a Normal admin tapping it gets
  "Super Admin only".
- **A mistyped admin PIN after a previous lockout no longer re-locks the terminal
  immediately.** The counter resets when a lockout is served.
- **POS mode stays on.** *Request New Working Key*, *Test Connection* and *Download Keys*
  no longer rewrite the POS-mode setting; only the Save button does, and only for a Super
  Admin (the POS section is greyed for Normal).
- **Logs are safe to share with support.** PAN, Track 2 and PIN blocks are masked or
  reduced to byte counts everywhere.

### Fixes

**Host / network — why a network blip could leave the terminal in a bad state**
- A connect failure (host down, TCP timeout, TLS failure) created a reversal for a request
  the host never received; the reversal could never match, and after 15 failed attempts the
  terminal put itself **out of service**. Reversal is now armed only after the connection is
  up. `HOST-01`
- If the host closed the socket after responding *without* sending EOT (what a MUX restart
  looks like), an **approved withdrawal was reversed**. The post-response handshake can no
  longer fail the transaction under any exception. Two regression tests added. `HOST-02`
- The TLS handshake had no timeout; a peer that accepted the connection but never answered
  wedged the transaction thread permanently ("Transaction already in progress" until
  restart) and could pin the key-download lock for the life of the process. `HOST-03`
- A socket left open by the 6-minute health check was reused for the next customer's
  request although the host had already closed it — the request went into a dead pipe and
  produced a bogus reversal. Every transaction now opens a fresh connection and every host
  operation disconnects when done. `HOST-04`

**Transaction**
- Cancel during the host call left the SDK thread running under an idle screen; a second
  customer could start a second SDK thread (the CTOS-service crash class), or the
  "cancelled" request completed and moved money after Cancel. The terminal now stays busy
  until the thread exits, a request committed to the host runs to completion, and a cancel
  before send is honoured at the last safe point. `SDK-01`

**Admin**
- Clear All Pending could be re-enabled for a Normal admin by a status refresh after
  *Process Pending Reversals*; the action itself now checks the tier. `ADM-01`
- Failed-attempt counter never reset after a lockout → permanent one-strike lockout. `ADM-02`
- Greying forced hidden controls visible (both fee layouts at once; Clear buttons with
  nothing to clear). `ADM-03`
- Implicit saves persisted the POS-mode checkbox — a stray tap on the long Admin page
  followed by *Request New Working Key* silently turned a POS-site terminal into a walk-up
  ATM until someone noticed. `ADM-05` *(found during the device pass)*

**Security / PCI**
- The "masked" clear PIN block written to the log exposed the PIN length and first two PIN
  digits (ISO Format-0 nibbles 0–3 are not covered by the PAN mask). Encrypted PIN blocks,
  key parts and a known-plaintext ciphertext under the master key were also logged. All
  removed or reduced to KCV / byte counts. `SEC-01`
- Clear Track 2 (full PAN, expiry, service code) appeared in the transaction log on every
  transaction, in 25 lines plus EMV TLV dumps and the reversal-path message dumps. New
  `LogMask` redacts PAN / Track 2 / cardholder-name values inside TLV and STD1 messages
  while keeping the rest of each dump readable. 11-vector test. `SEC-02` *(found during
  the device pass)*

**POS (proxy) — required because go-live units are cashier-driven POS installs**
- Stale-socket close events could drive the reconnect state machine, opening a second
  socket beside a healthy one; with one binding per TSN that meant a `bind_conflict` and a
  5-minute park during network trouble. Superseded sockets are now ignored and only one
  socket is ever live. `POS-01`
- After any admin Save / Test Connection / Request New Key that rebuilt the host service,
  the POS stack stayed bound to the dead one — every register command answered
  `host_unreachable` until a reboot. The stack now restarts with the host service (~4 s). `POS-02`
- A register sale arriving while a customer was mid-transaction overwrote the customer's
  amount and could receive the customer's approval. Refused with `terminal_busy` while any
  transaction is live. `POS-03`
- A reversal with nothing to reverse left the register waiting until timeout and the next
  unrelated host error was reported against a dead flow. Answered immediately; one reversal
  at a time. `POS-04`
- A malformed connection URL from the proxy could crash the process (taking the walk-up
  fallback down with it); a thrown exception inside the reconnect or supervisor task killed
  the safety net permanently. Both paths are now guarded. `POS-05`
- The 180 s result watchdog was shorter than a legitimate slow transaction; when it fired
  first, the real approval was dropped (customer debited, register told "error", no
  reversal). Now 300 s. `POS-06`
- Every POS reply is now logged (`POS flow=… → approved / declined / error`) and a reply
  that could not be sent because the proxy socket was down is a warning instead of a
  silent drop. *(found during the device pass)*

### Known issues and deferred

- **POS/SYS release switch (6.2.7, before go-live).** In POS mode the customer screen is
  still usable alongside the register; a PIN-gated bottom-bar switch between POS-locked and
  walk-up mode is designed (`POS-12`, backlog section R1-SWITCH) and ships next.
- **Host-layer robustness (6.2.8, after go-live):** key work shares the transaction
  executor, timeout budget doesn't nest, one socket driven from several threads, hostname
  verification for TLS (needs processor cert SANs confirmed first) — backlog section R2.
- EFX answers every 6-minute Type 89 health check by closing the socket; harmless since
  `HOST-04`, but a wasted connection every 6 minutes. `HOST-14`
- At current log verbosity the terminal's default 256 KiB log buffer holds ~15 minutes.
  Support: run `adb logcat -G 16M` before reproducing anything. `LOG-01`
- Three unit tests fail at 6.2.5 and still do — stale expectations, not regressions. `TEST-01`
- Admin PINs are fixed in code by decision; rotating them requires a build. `D1`

### Verification

Terminal `0000195250201680` (EFX, TLS, MKSK), debug build, 2026-09-18:

- Approved walk-up withdrawal on a fresh socket (1.5 s round trip), receipt printed.
- Cancel 0.7 s after the request hit the wire: refused, stayed on page, approval shown.
- Zero raw PAN / Track 2 / PIN block in logcat across all transactions.
- Normal admin → Request New Working Key: key downloaded, POS-mode setting untouched.
- POS from the register: sale approved (`rrn 383900000005`, register answered 5 ms after
  the host); sale cancelled at the terminal → register answered `user_cancelled`; two
  host-service rebuilds (TID edit + revert) → POS stack restarted each time → register
  balance inquiry approved against the rebuilt service; WiFi off 3.5 min → back-off
  1/2/4/8/16/30 s, one socket at the end, no `bind_conflict`, reconnected 0.4 s after the
  network returned.
- Not staged: a register sale arriving mid customer flow (`POS-03` guard), reversal with
  nothing to reverse (`POS-04`), the watchdog (`POS-06`), a misbehaving host for
  `HOST-01/02/03` (HOST-02 has regression tests), Clear Pending with reversals pending
  (`ADM-01`). All code-verified.
- Unit suite: 183 tests; 3 pre-existing failures (`TEST-01`).

### Upgrade notes

- Install via CasHUB **plain-install** push of `S1FP-TFI-v6.2.6.apk` (not "Reboot Install",
  which wipes settings on every boot).
- No configuration migration. Existing host settings, POS settings and the persisted
  working key carry over; no key re-download is triggered by the update itself.
- After the update, confirm on each POS-site unit that Admin → POS section shows
  **Enable POS Mode** checked (see `ADM-05`); the update does not change it, but any unit
  that lost it earlier will still be off.
- Support: if you need a log from a unit, enlarge the buffer first (`adb logcat -G 16M`).

---

## 6.2.5 — 2026-09-03 · commit `10187bf` · base 6.2.4

### Highlights
POS client no longer goes silent after a proxy restart; contactless receipts print the
correct card number.

### Fixes
**POS** — After a routine proxy restart the client presented its expired token, was
rejected, and made no further attempt for 40+ minutes; a power-cycle was the only
recovery. Any rejection at reconnect now falls back to re-registration (with 15-minute
tokens an expired token at reconnect is the *normal* case), and a stall supervisor gives
every non-connected state a deadline and force-recovers when it passes.

**Transaction** — Tap (contactless) receipts printed a wrong last-4. The Track 2 buffer
arrives in different encodings by entry mode (contactless ASCII, contact BCD) and was
parsed with BCD logic; the receipt PAN is now sourced from Tag 5A and stored masked. The
PIN block was never affected.

### Upgrade notes
Plain-install push; no configuration change.

---

## 6.2.4 — 2026-08-30 · commit `8df16e2` · base 6.2.3

### Highlights
First fixes from live testing against the MyView POS proxy.

### Fixes
**POS**
- Every register sale/balance inquiry was rejected "not connected" while manual
  transactions worked: readiness required a live processor socket, but sockets are opened
  per transaction so at idle there never is one. Readiness no longer depends on a socket.
- Cancelling a POS-driven transaction sent no reply; the proxy hung 90 s. Cancel now
  answers `user_cancelled` immediately.
- The single POS transaction slot stayed armed after a cancel — every later command got
  `terminal_busy` until an app restart. The slot is freed on cancel and a watchdog
  guarantees it cannot wedge.
- The terminal registered with the proxy under the processor TID; it now registers under
  the device hardware serial. *Coordination note:* the proxy binds the identity on first
  registration; changing it needs a binding reset on the proxy side.

---

## 6.2.3 — 2026-08-28 · commit `456de12` · base 6.2.2

### Highlights
Terminals recover on their own after the nightly reboot.

### Fixes
**Host / keys** — On a normal midnight reboot the app started before WiFi associated, the
key download failed a few times within ~3 s and nothing retried, leaving "Terminal Not
Started, try again later" until someone intervened. Boot-time key acquisition now retries
with exponential back-off (15 s → 5 min cap), and the transaction screen kicks a download
if a customer arrives without a key.

---

## 6.2.2 — 2026-08-27 · commit `1376a0a` · base 6.2.1

### Highlights
MKSK made solid on high-latency hosts (EFX through a busy MUX, 50–80 s round trips). A
seven-bug campaign around key state.

### Fixes
**Host / keys**
- PIN encryption failed with a valid key on the device: key state was per-instance and the
  component that downloaded the key was not the one encrypting the PIN. Key state is now
  process-wide, and the encrypted session key is persisted so a restart is
  transaction-ready with no wire traffic.
- Every key request makes the host rotate the working key, so "re-download to be safe"
  produced a self-inflicted ~52 s rotation loop that looked like key rejection. A valid,
  unexpired key is now authoritative and never re-requested; downloads are coalesced at one
  choke point (static lock + 30 s window); service init is idempotent for an unchanged
  config.
- A just-loaded key computed as "expired" (timestamp not persisted) → endless re-request
  loop. Timestamp + KCV + session blob are persisted together.
- In-flight authorisations were abandoned on slow hosts: timeouts set to response 120 s,
  transaction latch 130 s, readiness gate 150 s (latch must outlast the socket layer).

---

## 6.2.1 — August 2026 · base 6.2.0

### Highlights
Central configuration via CasHUB parameters (`ParameterContentProvider`), so fleet
settings are pushed rather than typed on each terminal. Versioned APK filenames
(`S1FP-TFI-v<version>.apk`) for fleet distribution; production release signing.

*Earlier history is in git (`git log --oneline` on `mksk-cfff-key`) and the development
journal `docs/CASHLESS_ATM_DEVELOPMENT.md`.*
