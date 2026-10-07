# Tips — design (6.2.14)

**Status:** approved in design review 2026-10-07 (brainstorm with the product owner); ready for an
implementation plan **after 6.2.13 (reporting push) has shipped**, because tips are only visible in the
portal through that feed.
**Depends on:** `2026-10-07-reporting-push-design.md` — the `AmountBreakdown` model, journal v3 and the
push are assumed to exist.

## 1. Why

Customers tip at the terminal. The processor has no tip field, so the tip rides inside the amount the
card is charged and the portal learns the split from the terminal's push. The Detail Report has
carried a TIP column since 6.2.11 waiting for this.

## 2. Decisions taken in review

| # | Decision |
|---|---|
| T1 | **Switch:** CasHUB parameter `tips_enabled`, default **false**. While false the tip screen is not in the flow, in either mode, and every transaction carries tip 0. Takes effect on the next transaction. |
| T2 | **Both flows, terminal prompts.** Walk-up withdrawals and register-driven sales both show the tip screen at the terminal after the amount is known. Never on a balance inquiry. The register does not send a tip. |
| T3 | **Choices:** 10% · 15% · 20% · Custom amount · No Tip. Percentages are of the **sale** amount, rounded half-up to the cent. Fixed in this release (a `tip_presets` parameter is a later follow-up only if asked). |
| T4 | **Arithmetic:** `withdrawal = roundUp(sale + tip, step)`, `cashBack = withdrawal − sale − tip`, `total = withdrawal + fee`. Example agreed: sale $10.00, 10% tip $1.00, withdrawal $20.00, cash back $9.00, fee $3.50, card $23.50; portal gets 1000 / 100 / 900 / 350 / 2350. Cash back is the change the customer receives; the fee is **not** deducted from it. |
| T5 | **Register sales round too** (the Ingenico model, one rule everywhere). This changes what register customers are charged today: a $12.50 register sale becomes a $20.00 withdrawal with $7.50 cash back. |
| T6 | **The maximum applies to the withdrawal.** Choices that would push the withdrawal past `max_amount` are shown greyed with the limit beneath. A sale already at the maximum skips the tip screen. |
| T7 | **Custom tip** is a dollar amount on the existing keypad: minimum $0.01, maximum whatever keeps the withdrawal within the limit (shown when exceeded). A tip larger than the sale asks once, "Tip $25.00 on a $10.00 sale?", before continuing. |
| T8 | **Idle 30 seconds = No Tip** and on to the card prompt. **Cancel = cancel the transaction** (walk-up to the menu; register sale answered `user_cancelled`). |
| T9 | No extra confirmation after a choice: the card screen shows the total; the receipt shows the split. |

## 3. Parameter

| Key | Values | Default | Effect |
|---|---|---|---|
| `tips_enabled` | `true` / `false` / `1` / `0` | false | Enables the tip screen in both flows. Shown read-only on the Admin managed-configuration card as `Tips: on/off`. |

Parsed through a `TipParams` class in the style of `PosParams` (or folded into the host-config mapping
as a boolean, like `use_flat_fee`; the plan picks one — the behaviour is identical). Invalid values are
logged and ignored.

## 4. The tip screen — `Fragment_page_tip`

**Position in the flow.**
- Walk-up: amount screen → **tip** → transaction (card) page. The amount screen's Continue navigates to
  the tip page when `tips_enabled` and the sale is below the maximum; otherwise to the transaction page
  as today.
- Register sale: `AtmHostServiceGateway.startCardDrivenTransaction` navigates to the tip page under the
  same conditions instead of straight to the transaction page. The POS slot is armed exactly as today;
  the tip screen is part of the armed transaction (Cancel there answers `user_cancelled` through the
  existing cancel path; the 300 s watchdog covers it like any other phase).
- Balance inquiry: never.

**Layout** (same button style as the amount presets; 32-column receipt not involved):

```
             Add a tip?
            Sale  $10.00

  [ 10%  $1.00 ] [ 15%  $1.50 ] [ 20%  $2.00 ]
  [ Custom amount ]            [ No Tip ]

                Cancel
```

Each percentage button shows its dollar value. A greyed button shows `Over $500 limit` beneath it.
Tapping any enabled choice stores the tip and navigates to the transaction page immediately.

**Custom.** Reuses the custom-amount keypad component from the amount screen (dollars and cents, a
Confirm button). Validation from `TipQuote`: ≥ $0.01; withdrawal within the limit (message names the
maximum tip allowed); tip > sale → confirm dialog (T7). Back from the keypad returns to the five choices.

**Timer.** A 30 s `Handler` timer started in `onResume`, cancelled in `onPause`/`onDestroyView` and on
any choice, that selects No Tip. It must never fire into the card phase (lesson from `LIFE-02`).

**Cancel.** Calls the same cancel path the amount screen uses (walk-up: reset state, main menu; POS:
the armed slot is answered `user_cancelled`).

## 5. Logic — `TipQuote` (pure, tested)

```
TipQuote.for(saleCents, stepCents, maxWithdrawalCents, feeConfig)
  → options: [10%, 15%, 20%] each { tipCents, breakdown, enabled, reasonIfDisabled }
  → noTip: breakdown with tip 0
  → customLimitCents: the largest tip for which withdrawal ≤ max (0 when none)
  → skipScreen: true when no option other than No Tip is enabled
TipQuote.custom(saleCents, tipCents, …) → breakdown or a validation failure (TOO_SMALL, OVER_LIMIT(maxTip))
TipQuote.percentOf(saleCents, percent) → cents, half-up
```

All breakdowns come from `AmountBreakdown.of(sale, tip, step, roundToStep = true, feeConfig)` — in
6.2.14 `roundToStep` is true for register sales as well (T5).

## 6. Where the numbers go

| Consumer | 6.2.14 change |
|---|---|
| Chip amount (9F02) | `total` (already the breakdown's total since 6.2.13) |
| Host request | amount = `withdrawal`, surcharge = `fee` (unchanged plumbing) |
| Journal | `tip_cents` real; `sale_cents`, `cash_back_cents` already written |
| Push | `TipAmount`, `CashBackAmount` real (payload unchanged since 6.2.13) |
| Customer receipt | when tip and cash back are both 0: unchanged. Otherwise the money block is `Sale / Tip / Cash Back / Withdrawal / Service Fee / Total Charged` (a rounding-only walk-up shows Sale, Cash Back, Withdrawal, Fee, Total). Same on the on-screen receipt. |
| Detail Report | per transaction: ` SALE … FEE …` then ` TIP … TOTAL …` with TOTAL = withdrawal + fee (today's TOTAL adds tip on top of an amount that will already contain it). Summary keeps "Withdrawals" as the host amount and adds a "Cash back" line next to "Tips". Final layout checked against the owner's sample receipt before shipping. |
| Host Totals | untouched (host sees withdrawals and surcharges) |
| Register reply (`approved` resource) | adds `tip_cents` and `cash_back_cents` beside `surcharge` and `total_cents`; `amount` stays the register's sale. Additive; request format unchanged. `docs/CASTLE_POS_INTEGRATION_SPEC.md` updated and the proxy team told. |
| Admin managed-config card | `Tips: on/off` |

## 7. Edge cases

- `tips_enabled` flips while a transaction is in progress: the current transaction keeps the flow it
  started with; the next one follows the new value.
- Sale at the maximum: screen skipped, tip 0 (T6).
- Fee in percentage mode: the fee is computed on the withdrawal (as today), so a tip can raise the fee
  by a step; the receipt shows the actual fee.
- Register sale below the minimum (e.g. $4.00 with a $10 step): rounds up to the step like a walk-up
  custom amount; cash back carries the difference. (6.2.13 keeps register sales exact; this is the T5
  change.)
- Custom tip keypad timeout: the 30 s timer applies to the whole tip screen including the keypad; on
  expiry No Tip is selected.
- The 30 s timer and the register's 90 s window: tip ≤ 30 s + card + PIN fits the window the proxy
  team documented; the watchdog (300 s) is unaffected.

## 8. Testing

Unit (TDD): `TipQuoteTest` (the agreed example, percent rounding half-up at the cent on odd sales such
as $12.33, greying at the limit, custom limit, skipScreen, tip > sale flag, custom validation),
`AmountBreakdownTest` additions for register rounding on, receipt-block formatting (pure formatter),
Detail Report lines (`DetailReportTest` extensions), POS reply fields (`PosTransactionExecutorTest`).

Device: each of the five choices on a walk-up tap and a chip insert; the same on a register sale; a
greyed choice at the limit; custom above the sale (confirm dialog); custom over the limit (message);
30 s idle → No Tip → card; Cancel in both flows (register sees `user_cancelled`); receipt block and
Detail Report print; the register reply carries tip and cash back; MyView shows the split after the
push; `tips_enabled=false` removes the screen without restart.

## 9. Out of scope

Configurable percentages; tip adjust after authorization; tips on balance inquiries; a register-supplied
tip; any change to Host Totals or the STD1 sequence.

## 10. Files

New: `Fragment_page_tip` + `res/layout/fragment_page_tip.xml`, `TipQuote`, `TipParams` (or a key in the
host-config mapping), receipt money-block formatter (pure).
Changed: `GlobalDef` (page constant), `MainActivity` (pager page, navigation), `Fragment_page_amount_selection`
(navigate to tip), `AtmHostServiceGateway` (navigate to tip; register rounding on), `Fragment_page_receipt`,
`atm/report/DetailReport` + `ReportRow`, `pos/PosTransactionExecutor` + `PosTerminalGateway.TransactionResult`
+ `PosWire`, `Fragment_page_admin_atm` (Tips line), `CasHubParams` / `KmsConfigStore` (the key),
`docs/CASTLE_POS_INTEGRATION_SPEC.md`, `RELEASE-NOTES.md`, `docs/CODE-REVIEW-BACKLOG.md` (TIP-01).
