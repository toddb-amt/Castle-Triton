# Terminal reporting push to MyView — design (6.2.13)

**Status:** approved in design review 2026-10-07 (brainstorm with the product owner); ready for an
implementation plan.
**Release:** 6.2.13. Second half of the same design, tips, is `2026-10-07-tips-design.md` (6.2.14).
**Contract:** `docs/MYVIEW-TERMINAL-PUSH-API.md` (copy of the portal team's document, verified against
production traffic 2026-10-07).

## 1. Why

The portal (MyView) has two feeds for a terminal's transactions: a 60-second poll of the processor-side
host log, and a per-transaction push from the terminal. **Only the push carries tip and cash back.**
The host record has no field for either. The Ingenico fleet pushes today; the Castle terminals do not,
so nothing a Castle terminal does can ever show a tip or a cash-back amount in the portal. Tips (6.2.14)
therefore need this feed first. The feed is useful on its own: declines, balance inquiries and reversals
appear in the portal with the terminal's own text, and the cash back that walk-up rounding already
produces today becomes visible.

## 2. Decisions taken in review

| # | Decision |
|---|---|
| R1 | **The journal is the outbox.** No second queue. The journal already records every finished transaction at one completion point; push bookkeeping is columns on those rows. Rows persist, so the queue survives reboots and outages. |
| R2 | **Identity:** `TermID` and `HostTermID` = the CasHUB `terminal_id` parameter (`MS00…`); `tsn` = the Castle hardware serial (the same value the POS client registers with, `CtSystem.getFactorySNEx()`); `tenantAccessKey` = new CasHUB parameter `reporting_access_key`. |
| R3 | **What is sent:** every transaction the host answered (approved and declined withdrawals/sales, balance inquiries, our accepted reversals) **plus terminal-side declines where a card was presented** (swipe refused, read error, no usable track). **Not sent:** cancels — Cancel pressed, no card presented, PIN pad abandoned — and terminal faults before a card. The line is "was a card presented," not "did the host answer." |
| R4 | **`TerminalSequenceNum` is our terminal sequence, STD1 Field 4, exactly as generated today.** The sequence mechanism is not touched (not persisted, not re-assigned; `SEQ-01` stays a note). The push sends the row's recorded sequence: the Field 4 number when a request was built (so a timed-out request keeps its real number and the portal can merge a late host row), and `0` when none ever was (a terminal-side decline). The portal's duplicate key also includes date and time to the second and the terminal runs one transaction at a time, so zeros cannot collide. |
| R5 | **One amount model.** `AmountBreakdown` (sale, tip, withdrawal, cashBack, fee, total) is introduced in this release and wired in with tip = 0 and register-sale rounding **off**, so every charge, chip amount, receipt and host message is identical to 6.2.12. 6.2.14 changes the two inputs only. |
| R6 | Reporting is **off until a key is configured**; nothing is queued as pending and no network call is made. Turning it on later sends from that point forward, not retroactively. Batch catch-up (`/api/v1/live-transactions/batch`) is deliberately left out until a real case appears. |
| R7 | No customer-facing banner for a push backlog. Operator visibility is one Admin line plus log lines. |

## 3. Parameters (CasHUB)

Read by the existing `CasHubParams` path (boot and `PARAMETER_UPDATED`), absent keys leave current
values alone, invalid values are logged and ignored.

| Key | Values | Default | Effect |
|---|---|---|---|
| `reporting_access_key` | string, or the literal `off` | none | Tenant key. Without it reporting is off (see R6). The value `off` (any case) clears the stored key and switches reporting off — the only off-switch; a blank value is ignored like any other invalid value (review I6). Never logged, never in the host-config payload or the KMS backup. Merchant level is acceptable (it is per tenant). |
| `reporting_url` | `https://` URL | the production ingestion URL: `https://d16f8tt74onlvr.cloudfront.net/transactions/addTransaction` (portal team, 2026-10-08; the contract's Lambda URL is retired) | Override for a test portal. Not displayed on the terminal. |

A `ReportingParams` class mirrors `PosParams` / `ApnParams`: `KEYS`, `parse(Map)`, problems list; the
two keys are excluded from the host-config payload like the POS and APN keys.

## 4. The amount model — `AmountBreakdown`

All fields whole cents, immutable, built by one pure function.

| Field | Meaning |
|---|---|
| `sale` | what the customer asked for: entered or preset amount on a walk-up, the register's amount on a sale; 0 for a balance inquiry |
| `tip` | the customer's tip; **always 0 in 6.2.13** |
| `withdrawal` | `roundUp(sale + tip, stepCents)` when rounding applies, else `sale + tip`; what the host is asked for (STD1 amount field) |
| `cashBack` | `withdrawal − sale − tip` |
| `fee` | the terminal's fee on the withdrawal (`Money.feeCents`, flat or percentage, unchanged) |
| `total` | `withdrawal + fee`; the chip amount (9F02) and the card charge |

`AmountBreakdown.of(saleCents, tipCents, stepCents, roundToStep, feeConfig)`:

- walk-up **custom entry**: `roundToStep = true` (today's `AmountRounding.roundUpToStep` behaviour,
  applied to the entered amount); walk-up **preset**: `roundToStep = false` — a preset is charged
  exactly as its button says even when `min_amount` does not divide it ($60 with a $25 minimum stays
  $60), as since 6.2.8 (review C1 corrected the earlier "presets are already multiples" assumption);
- register sale: `roundToStep = false` in 6.2.13 (today's behaviour: exact cents, no cash back);
- balance inquiry: all zero.

Invariant, tested: `sale + tip + cashBack + fee == total` and `withdrawal + fee == total`.

**Wiring.** The breakdown is computed where the amounts are decided today — the amount screen's
Continue handler and `AtmHostServiceGateway.startCardDrivenTransaction` — and stored once
(`GlobalPara.atmAmounts`). The four legacy strings (`atmSelectedAmount`, `atmFee`, `atmTotal`,
`strAmount`) are **written from the breakdown as mirrors** for this release so untouched readers keep
working; new code reads the breakdown. Consumers switched in 6.2.13: the journal (new columns), the push.
Consumers switched in 6.2.14: receipt, Detail Report, POS reply. The host request already receives
amount and surcharge as separate numbers; they are the breakdown's `withdrawal` and `fee`.

## 5. Journal schema v3

`DATABASE_VERSION = 3`; data-preserving `onUpgrade` as v2 (ALTER TABLE … ADD COLUMN each, tolerant of
a column that already exists).

| New column | Type | Meaning |
|---|---|---|
| `sale_cents` | INTEGER DEFAULT 0 | breakdown `sale`; **migration backfill:** `UPDATE … SET sale_cents = amount_cents` for existing rows (no cash back known for them) |
| `cash_back_cents` | INTEGER DEFAULT 0 | breakdown `cashBack` |
| `flow_id` | TEXT | UUID generated when the row is written; the same value on every push retry |
| `push_state` | INTEGER DEFAULT 0 | 0 `NOT_APPLICABLE`, 1 `PENDING`, 2 `SENT`, 3 `PARKED` |
| `push_attempts` | INTEGER DEFAULT 0 | |
| `push_last_error` | TEXT | last failure reason, truncated to 200 chars, never the key |
| `push_sent_at` | INTEGER DEFAULT 0 | epoch millis of the accepting 200 |
| `push_message` | TEXT | the portal's `message` on success (`ingested` / `merged` / `already exists` / `updated with RRN`) |

Existing `amount_cents` keeps meaning **withdrawal** (host amount), `fee_cents` the fee, `tip_cents` the
tip (0 until 6.2.14), `total_cents` = `amount_cents + fee_cents`. Existing rows in v2 therefore read
correctly as sale = withdrawal, cash back = 0.

**Reversal rows.** When `TransactionJournal.markReversed` is called for an accepted reversal, in
addition to setting the original's `reversed` flag it **inserts a row** with `transaction_type =
"REVERSAL"`, `result = "APPROVED"`, the original's `sequence_number`, amounts, card last four, account
type, batch id, and a fresh `flow_id`; `push_state = PENDING` when a key is configured. The Detail
Report and `BatchMath` ignore `REVERSAL` rows (they already select by type); the "Reversed" count keeps
coming from the flag on the original.

**`push_state` at write time:** `PENDING` if a key is configured **and** the row is sendable (R3),
else `NOT_APPLICABLE`. Sendable: `result` in {APPROVED, DECLINED} for WITHDRAWAL and BALANCE_INQUIRY
rows, and all REVERSAL rows. `CANCELLED` rows are never sendable. (A terminal-side decline with a card
is journaled as DECLINED today — e.g. `MSR_NA`, a reader error code, `NO_TRACK2` — so this rule
matches R3; a cancel is journaled as CANCELLED.)

**Protection:** `clearClosedBatches()` and the keep-`BatchMath.KEEP_BATCHES` pruning skip any batch
that contains a row with `push_state = PENDING`. Nothing unsent is deleted.

## 6. The payload — `PushPayload`

Pure: `PushPayload.of(TransactionLog row, Identity id, Clock/TimeZone tz, String appVersion) → JSONObject`.
Tested byte-for-byte against the contract's example (with synthetic identifiers).

| Portal field | Source |
|---|---|
| `flow_id` | row `flow_id` |
| `tenantAccessKey` | parameter (also sent as `X-API-Key` header) |
| `tsn` (top level and in `transactionJSON`) | hardware serial |
| `TermID`, `HostTermID` | `terminal_id` parameter |
| `TerminalSequenceNum` | row `sequence_number`: the Field 4 number when a request was built, else 0 |
| `TransDateTimeUTC` | row timestamp formatted **in device local time** `yyyy-MM-dd HH:mm:ss` |
| `TimeZone`, `TimeZoneDST` | `TimeZone.getDefault()`: standard-time short name (e.g. `EST`, from `getDisplayName(false, SHORT, US)`) and `inDaylightTime(ts) ? 1 : 0` |
| `BusinessDate` | local calendar date of the row, `MMddyyyy` |
| `RequestedAmt` | `sale_cents` |
| `TipAmount` | `tip_cents` |
| `CashBackAmount` | `cash_back_cents` |
| `SurchargeAmt` | `fee_cents` |
| `TotalAmt` | `amount_cents + fee_cents` (withdrawal + fee; equals sale + tip + cash back + fee) |
| `TransType` | `WTH` for WITHDRAWAL, `INQ` for BALANCE_INQUIRY, `RWT` for REVERSAL |
| `Approved` | `1` when `result == APPROVED`, else `0` |
| `ResponseDescription` | when `response_code` is a two-digit host code: the host's text (`error_message`, or `Transaction approved` on an approval); otherwise the ending was the terminal's: `"Declined at terminal: <error_message>"` (e.g. `Swipe not supported`, `Transaction timeout`, a reader error) |
| `RRN` | `reference_number`, empty string when none |
| `CardLast4` | `card_last_four` (already last four only) |
| `SourceAccount` | `CA` checking, `SA` savings, `CC` credit, from `account_type` |
| `Host` | processor name (`EFX`, …) |
| `Software` | `"Castle S1FP-TFI " + versionName` |

Balance inquiries send all five money fields as 0. Declines send the attempted amounts; the portal
stores total 0 itself. No full PAN, no PIN, no key in any log line.

## 7. The sender — `ReportingPusher`

One background single-thread executor, never the transaction thread, never the SDK.

**Triggers:** (a) after each journal row is written with `PENDING`; (b) `onNetworkAvailable` (the
existing default-network callback in MainActivity that already pokes the host service); (c) app start,
after the host service initialises; (d) a 5-minute periodic sweep. Triggers coalesce: a run in progress
absorbs new triggers into "run again when done".

**A run:** while a key is configured, select `PENDING` rows oldest first (by timestamp, then id),
excluding rows whose backoff has not elapsed, and for each: build the payload, `POST` with the OkHttp
client (connect 10 s, read/write 30 s, `Content-Type: application/json`, `X-API-Key`), then:

| Response | Action |
|---|---|
| HTTP 200 | `SENT`, store `push_message`, continue |
| HTTP 401 | stop the run; set reporting status `KEY_REJECTED` (Admin line, one log line); **no attempt is counted and no backoff is set on the row** — a rejected key is never the row's fault, so the fixed key's first run sends it first (review I1); retry on the next parameter change or the next 5-minute sweep |
| any other failure: timeout, no network, HTTP 500 or any other status | `push_attempts++`, `push_last_error` (the contract's `error` text when present, truncated); backoff 5 s, 30 s, 2 min, then 5 min cap; stop the run (the next trigger or sweep resumes); rows stay `PENDING` indefinitely |

**Parking.** A row is set `PARKED` only when it has failed **10 or more times AND a row written after it has since been `SENT`** — proof that the portal is reachable and this payload is the problem (our bug, or a value the portal rejects). While the portal is down every row fails and nothing is parked. To make that proof possible, a run that hits a row on its 10th or later failure skips it and tries the next row; if that one is accepted, the skipped row is parked and logged at WARN with the portal's reason.

Retries resend the identical body (same `flow_id`); the portal treats it as idempotent.

**Status for the operator** (`ReportingStatus`, persisted in prefs for the Admin line): `NOT_CONFIGURED`,
`OK` (pending count, last sent time), `RETRYING` (pending count, last error class), `KEY_REJECTED`,
plus a parked count when > 0. Rendered on the Admin screen next to the Network card
(`Fragment_page_admin_atm.renderNetworkCard` sibling): `Reporting: not configured` /
`configured · 0 pending · last sent 14:32` / `3 pending · retrying (timeout)` / `key rejected` /
`… · 1 parked`. Super admin gets a `Retry now` action that triggers a run; nothing else is editable.

**Logging:** one line per push outcome — `Reporting: seq 12 WTH → sent (merged)`,
`Reporting: seq 13 WTH → retry in 30s (timeout)` — never the key, never card data.

## 8. Threading and failure modes

- The transaction thread only ever **writes** the journal row (as today) and signals the pusher; the
  pusher reads rows and talks HTTP on its own thread. SQLite access through the existing
  `TransactionLogManager` (synchronized methods).
- A crash or kill mid-request leaves the row `PENDING`; the next start resends; the portal de-duplicates.
- Clock: `TransDateTimeUTC` uses the device clock at the time the row was written, not at send time.
- No key → the pusher never constructs a client. Key switched off later (`reporting_access_key=off`) →
  pending rows stay pending (status `NOT_CONFIGURED`, count shown); Clear History keeps refusing while
  they exist, so switch back on to drain, or wait for the pruning guard to be lifted by a new build.
- Any exception inside a drain run (an unparsable URL, a SQLite error) is caught, reported as
  `RETRYING` with the exception name, and never kills the periodic sweep (review I2). `reporting_url`
  is validated with the HTTP client's own parser, not a prefix check.
- A negative `flat_fee`, `percentage_fee`, `min_amount` or `max_amount` from CasHUB is rejected at the
  configuration boundary (previous value kept, logged); if a breakdown still cannot be built, the walk-up
  shows "Configuration error" and a register sale is answered `internal_error` — never a crash (review I4).
- The hardware serial for `tsn` is read from the CTOS SDK once, where the pusher is started, never on
  the pusher's thread (review I5).
- Parked rows are visible (count) and kept; a later build that fixes the payload bug can re-queue them
  by resetting `push_state` (an Admin "Re-queue parked" action is **not** in this release).

## 9. Testing

Unit (JVM, TDD):
- `AmountBreakdownTest`: the review's examples ($10 sale / $1 tip / step $10 → withdrawal $20, cash back
  $9, fee $3.50, total $23.50; the contract's $219.25 + $2.00 → $230.00 / $8.75 / $233.50), rounding off
  for register sales, balance inquiry zeros, the invariants, equality with 6.2.12 arithmetic when tip is 0.
- `PushPayloadTest`: the contract's example reproduced field for field (synthetic ids), balance
  inquiry zeros, terminal-side decline (seq 0, description prefix, empty RRN), reversal `RWT`, local
  time and DST flag for a fixed zone, account mapping, no PAN anywhere in the output.
- `ReportingPusherTest` with MockWebServer: FIFO order; each response row of §7; backoff schedule;
  park after 10; identical body and `flow_id` on retry; nothing sent without a key; 401 stops the run;
  a trigger during a run causes exactly one more run.
- `ReportingParamsTest`: parsing, invalid URL ignored, key never in `describe()`.
- Journal: sendability rule, pruning skips batches with pending rows, reversal row insertion
  (pure parts; the SQLite migration is verified on the device).

Device (bench, EFX live, test terminal id, production portal):
1. Chip, tap and register sale approved → each pushed within seconds → MyView row shows the push
   (`merged` or `ingested`), amounts match the receipt.
2. A host decline → pushed with `Approved: 0` and the host text.
3. A swipe → `Declined at terminal: Swipe not supported`, seq 0, visible in MyView.
4. A reversal (force a host timeout after the request is sent, e.g. drop WiFi during the host wait on a withdrawal) → the original pushed as it ended, then the accepted reversal as `RWT`.
5. Cable out before a transaction, run two transactions, cable in → both drain in order.
6. Wrong key in CasHUB → `key rejected` on the Admin line; correct key pushed → queue drains.
7. Upgrade from a 6.2.12 device database → v3 migration keeps every row; old rows read sale = withdrawal.
8. Receipts and charges identical to 6.2.12 (same card, same amounts, compare prints).

## 10. Out of scope (this release)

Tips UI and register rounding (6.2.14). Batch catch-up endpoint. Admin re-queue of parked rows. Any
change to the STD1 sequence. Pushing cancels. Customer-facing status.

## 11. Files

New: `reporting/ReportingParams`, `reporting/ReportingStatus`, `reporting/PushPayload`,
`reporting/ReportingPusher`, `reporting/ReportingClient` (OkHttp wrapper, test seam), `AmountBreakdown`
(root package, next to `Money` / `AmountRounding`).
Changed: `CasHubParams` (route the two keys), `TransactionLogManager` (v3, sendability, pruning guard,
reversal row, pending queries), `TransactionLog` (new fields), `TransactionJournal` (write the breakdown
columns, `flow_id`, `push_state`; reversal row), `GlobalPara` (`atmAmounts` + mirrors),
`Fragment_page_amount_selection` and `AtmHostServiceGateway` (build the breakdown),
`MainActivity` (start the pusher, network trigger, app-start trigger), `Fragment_page_admin_atm`
(Reporting line + Retry now), `RELEASE-NOTES.md`, `docs/CODE-REVIEW-BACKLOG.md` (RPT-02 push,
AMT-03 breakdown). `docs/CASTLE_POS_INTEGRATION_SPEC.md` is unchanged in 6.2.13.
