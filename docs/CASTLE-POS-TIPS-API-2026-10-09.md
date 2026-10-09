# Castle terminals — tips: what changes on the wire (terminal 6.2.14)

**To:** proxy team · **From:** TFI terminal team · **Date:** 2026-10-09
**Status:** terminal build complete and under bench test; ships after the device pass. Nothing below is live on a merchant terminal yet.
**Scope:** the terminal ↔ proxy protocol (`docs/CASTLE_POS_INTEGRATION_SPEC.md`, §5.1.1 `sale`) and what the register-facing API needs to pass through. All changes are **additive**: no new commands, no change to any request, no renamed or removed field.

## 1. The feature, in one paragraph

A merchant can enable tips per terminal (CasHUB parameter `tips_enabled`, default off). When on, the terminal shows a tip screen **before the card prompt** on every sale, register-driven or walk-up: 10% · 15% · 20% · Custom amount · No Tip. The register does not send a tip and cannot. The tip rides inside the amount the card is charged, so the processor never sees it; the terminal reports the split to you and to MyView.

## 2. New fields in the `approved` sale response

Two integer-cents fields, **always present on an approved sale** (0 when there is no tip, or tips are off):

```json
{
  "type": "response",
  "flow_id": "8f1b2c3d-0000-4000-8000-000000000001",
  "resource": {
    "status": "approved",
    "response_code": "00",
    "reference_number": "578500000123",
    "auth_date": "2026/10/09",
    "auth_time": "10:15:02",
    "display_message": "APPROVED",
    "amount": 1250,
    "surcharge": 350,
    "total_cents": 2350,
    "tip_cents": 125,
    "cash_back_cents": 625
  }
}
```

| Field | Meaning |
|---|---|
| `amount` | the register's sale, **unchanged** (as today) |
| `surcharge` | the fee the terminal applied (as today, D7) |
| `tip_cents` | **new** — the tip the customer chose at the terminal |
| `cash_back_cents` | **new** — `withdrawal − amount − tip_cents`, the change the customer receives (see §3) |
| `total_cents` | what the card was charged = **withdrawal + surcharge**. Up to terminal 6.2.13 the withdrawal always equalled `amount`; from 6.2.14 it can be larger |

Identity: `total_cents = amount + tip_cents + cash_back_cents + surcharge`.

Declined and error responses are unchanged.

## 3. Rounding: register sales now round up to the terminal's step

From 6.2.14 the terminal rounds a register sale **up to its `min_amount` step**, exactly as a walk-up custom amount always did (processor withdrawals are in fixed steps). The remainder is reported as `cash_back_cents`. This applies whether or not tips are on.

| Register sends | Terminal step | Withdrawal | `cash_back_cents` | `surcharge` | `total_cents` |
|---|---|---|---|---|---|
| 1250 | $20 | 2000 | 750 | 350 | 2350 |
| 4000 | $20 | 4000 | 0 | 350 | 4350 |
| 1250 + 10% tip (125) | $20 | 2000 | 625 | 350 | 2350 |

Up to 6.2.13 the first row was charged exactly 1250 + 350. **Please tell register integrators**: the card is charged `total_cents`, not `amount + surcharge`, and the customer receives `cash_back_cents` in cash.

## 4. New refusal: sale over the terminal's maximum

The terminal's `max_amount` applies to the rounded withdrawal. A sale whose rounded withdrawal would exceed it — including a sale already above the maximum, which was never checked before — is answered immediately, nothing charged:

```json
{ "type": "response", "flow_id": "…", "error": { "code": "invalid_request",
  "message": "withdrawal $510.00 exceeds the terminal's maximum $500.00 after rounding $490.00 up to the $30.00 step" } }
```

Map it as you map other `invalid_request` errors; the message is suitable to show the cashier.

## 5. Timing

- Tip screen: up to **30 seconds** before the card prompt (30 s idle = No Tip, then the card prompt). Fits inside your 90-second budget: ≤ 30 s tip + card + PIN.
- Cancel on the tip screen answers `user_cancelled` within a second, as every terminal-side ending has since 6.2.12.
- A sale at or above the maximum, or with tips off, shows no tip screen: timing as today.

## 6. What the register-facing API should expose

Pass `tip_cents` and `cash_back_cents` through on the `/tsi/v1/payment` response (same names suggested), and document `total_cents` as the amount charged to the card. Registers that ignore the two new fields keep working, but their receipts will not show the split; MyView will, from the terminal's own push.

## 7. Test plan (same bench, same day)

1. Sale 1250 with tips off → `tip_cents: 0`, `cash_back_cents: 750`, `total_cents: 2350` (rounding only).
2. Sale 1250 with tips on, choose 10% → `tip_cents: 125`, `cash_back_cents: 625`, `total_cents: 2350`.
3. Sale 49000 with step $30 and max $500 → `invalid_request` with the message above.
4. Sale with tips on, Cancel on the tip screen → `user_cancelled` within a second.
5. Sale with tips on, no touch for 30 s → card prompt appears, result as (1).

Spec: `docs/CASTLE_POS_INTEGRATION_SPEC.md` §5.1.1 and §9 (versioning: additive fields are non-breaking). Questions to the terminal team.

---

## Proxy team response (2026-10-09)

Reviewed; the design works for us. Status on our side, one change request, and one question — raised now while the build is still on the bench.

**§2/§6 — done on our side.** The proxy forwards unrecognized resource fields on an approved sale to the register-facing response and the stored transaction record as-is, so `tip_cents`, `cash_back_cents`, and `total_cents` pass through with no proxy change. We have pinned this with a test emulating a 6.2.14 approved sale (including the §2 identity), so the pass-through is now a contract, not an accident. Older builds omitting the fields are handled (absent, not zeroed). We will send register integrators the §3 notice — that the card is charged `total_cents`, not `amount + surcharge`, with `cash_back_cents` returned in cash, and that absent fields mean a pre-6.2.14 terminal.

**§4 — change request: give the over-maximum refusal its own error code.** You send it as `error.code: "invalid_request"`. On our side `invalid_request` maps to a generic terminal-error class (HTTP 502) because it otherwise means a malformed exchange — a register seeing 502 may retry or raise a malfunction alert, which is wrong for a clean business refusal that charged nothing. We don't want to distinguish the two by parsing the message text. Request: use a distinct code, e.g. `error.code: "amount_exceeds_maximum"` (message unchanged — it's good). We'll map it to a 4xx "fix the amount, don't retry" response with your message shown to the cashier. One line on each side, but only cheap before this ships.

**§5 — question on the timing budget.** The tip screen adds up to 30 s before the card prompt, inside our 90-second window. But your 2026-08-30 watchdog note said the processor leg alone can legitimately run ~130 s on the busy-MUX deployment — already past our window before any tip screen. Two things to confirm: (a) the 30 s tip window is capped by the No-Tip default and nothing else can extend it; (b) tips don't lengthen the processor leg (the withdrawal is still a single host operation). If both hold, nothing changes for us; if the end-to-end worst case grows, tell us the realistic ceiling so we can decide whether the 90-second register budget needs revisiting fleet-wide.

**Bench test plan (§7):** we'll mirror it from the register side the same day — happy to run our five calls against your bench unit on an agreed window. Our test 3 note: we'll also assert nothing was recorded as charged on our side for the over-max refusal.

---

## Terminal team reply (2026-10-09)

**§4 — done.** The over-maximum refusal now has its own code, `error.code: "amount_exceeds_maximum"`, message unchanged. `invalid_request` keeps its old meaning (malformed exchange). Spec §7 lists the new code. It is in the bench build as of today; your test 3 will see it.

**§5 (a) — confirmed, with one caveat.** The tip screen is bounded by a single 30-second idle timer that selects No Tip; it covers the custom-amount keypad and the "tip larger than the sale?" confirmation as well (both are dismissed when it fires), and nothing on the screen restarts it. The caveat: the timer runs only while the screen is in the foreground. If the terminal's display sleeps while the tip screen is up, the timer pauses and restarts at 30 s on wake — so a sleeping terminal can hold a register sale on the tip screen longer than 30 s. That is bounded by the 300-second slot watchdog like every other phase, and we have it on our list to make the tip screen cancel the register sale instead if the slot has already been released. It needs a long screen-off mid-sale to occur at all.

**§5 (b) — confirmed.** Tips do not touch the processor leg. The withdrawal is still one host operation for the rounded amount, same message, same timeouts; the processor never sees the tip.

**The realistic ceiling, from the code as shipped (unchanged by tips except the first line):**

| Phase | Bound | Changed by 6.2.14? |
|---|---|---|
| Tip screen | ≤ 30 s (foreground), No Tip on expiry | **new** |
| Card prompt | no terminal-side limit; the 300 s slot watchdog is the only bound | no |
| PIN entry | 30 s to the first key, 60 s between keys (SDK) | no |
| Processor leg, EFX | 30 s connect + 120 s response (EFX answers key requests in 50–57 s, so the response timeout is EFX-specific) | no |
| Terminal-side endings (cancel, swipe, PIN failure) | answered within a second since 6.2.12 | no |

So the end-to-end worst case grows by exactly the tip screen's 30 seconds; a typical approved sale grows by the few seconds a customer takes to tap a percentage. Your 2026-10-05 request to cap the processor leg of POS-driven transactions at or below 60 seconds is **still open** on our side — it was not part of 6.2.12–6.2.14 — and is the lever if the 90-second register budget is to be met in the busy-MUX worst case. We would rather decide that one with you deliberately than fold it into the tips release.

**§7 — agreed.** Same-day mirror from the register side against the bench unit; propose a window and we will have `tips_enabled` on for tests 2, 4 and 5 and off for test 1.
