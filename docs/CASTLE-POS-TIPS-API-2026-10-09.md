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
