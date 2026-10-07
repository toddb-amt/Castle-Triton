# Terminal reporting push — how MyView receives transactions and tips

Audience: the Castles terminal project. This is what the Ingenico terminals (TFI firmware 6.0.x) send to the
reporting portal today, and what the portal does with it. A Castles terminal that sends the same request gets the
same treatment. Last verified against production traffic on 2026-10-07.

## 1. Where tips come from

The portal has two feeds for a terminal's transactions:

| Feed | Direction | Carries tip / cash back? |
|---|---|---|
| **Reporting push** (this document) | the terminal POSTs each transaction to the portal as it completes | **Yes.** This is the only source of `TipAmount` and `CashBackAmount`. |
| Host log poll | the portal polls the processor-side log (mux / translator) every 60 s | No. The host record has no tip or cash-back field. |

So a terminal that does not push never shows tips in the portal, no matter what the host has. If the push arrives
after the poll already created the row, the portal merges the tip, cash back and RRN onto the polled row; if it
arrives first, the push row is the record and the poll is treated as a duplicate.

## 2. Endpoint

| | |
|---|---|
| Method | `POST` |
| URL | `https://t5wfhaal2k5usy2rfb5uwaszzm0ijsvn.lambda-url.us-east-1.on.aws/transactions/addTransaction` |
| Content-Type | `application/json` |
| Authentication | the tenant access key TFI issues, sent as `tenantAccessKey` in the JSON body (an `X-API-Key` header with the same value is also accepted) |
| Timeout to allow | 30 s |

There is no custom domain in front of it today; the host above is the production endpoint. A `GET` on
`/health` returns `200` and can be used as a connectivity check.

## 3. Request body

One transaction per request. Field names are case-sensitive as shown.

```json
{
  "flow_id": "69B266E6-ADD4-4BFC-847E-7BEA0A4C9A74",
  "tenantAccessKey": "<tenant access key from TFI>",
  "tsn": "59435215",
  "transactionJSON": {
    "tsn": "59435215",
    "TermID": "GH001191",
    "TerminalSequenceNum": 1,
    "TransDateTimeUTC": "2026-10-07 09:04:15",
    "RRN": "295300004276",
    "SourceAccount": "CA",
    "RequestedAmt": 21925,
    "TipAmount": 200,
    "CashBackAmount": 875,
    "SurchargeAmt": 350,
    "TotalAmt": 23350,
    "CardLast4": "1234",
    "ResponseDescription": "Transaction approved",
    "Host": "SPS2",
    "HostTermID": "GH001191",
    "BusinessDate": "10072026",
    "TransType": "WTH",
    "Approved": 1,
    "TimeZone": "EST",
    "TimeZoneDST": 1,
    "Software": "TFI 6.0.23.0431"
  }
}
```

That is a real push from this morning with the key, RRN and card digits replaced. It reads: a $219.25 sale, a
$2.00 tip, $8.75 cash back and a $3.50 surcharge, $233.50 in total.

### Field reference

| Field | Type | Required | Meaning / what the portal does with it |
|---|---|---|---|
| `tenantAccessKey` | string | yes | The key TFI issues. Rejected with `401` if missing or unknown. |
| `tsn` (top level and inside `transactionJSON`) | string | yes | Terminal's unique transaction serial. Echoed back in the response. |
| `transactionJSON.TermID` | string | yes | Terminal id as the portal knows it (`GH…`, `MP…`, `MS…`, `MQ…`, `AZ…`, `01…`, `25…`), 1–20 characters. |
| `TerminalSequenceNum` | integer ≥ 0 | yes | The terminal's own sequence number. Part of the duplicate / merge key together with `TermID`, date and time. |
| `TransDateTimeUTC` | `YYYY-MM-DD HH:MM:SS` | yes | **Terminal local time**, despite the name. The portal stamps the transaction date and time from this value. |
| `TimeZone`, `TimeZoneDST` | string, 0/1 | recommended | Describe the local time above (`EST` + `1` = Eastern daylight). |
| `BusinessDate` | `MMDDYYYY` | optional | Processor business day. Only a fallback when `TransDateTimeUTC` is absent; it rolls over in the afternoon, so do not rely on it for the calendar date. |
| `RequestedAmt` | integer, **cents** | yes | The **sale** amount, before tip and cash back. For an ATM withdrawal this is the withdrawal amount. |
| `TipAmount` | integer, **cents** | yes (send `0` when none) | Tip added by the customer. Stored as `tip_amount` and shown in the Tip column. |
| `CashBackAmount` | integer, **cents** | yes (send `0` when none) | Cash back added by the customer. Stored as `cash_back_amount`. |
| `SurchargeAmt` | integer, **cents** | yes | Surcharge / convenience fee. Kept on declines too. |
| `TotalAmt` | integer, **cents** | yes | `RequestedAmt + TipAmount + CashBackAmount + SurchargeAmt`. On an approval this is the settlement amount; on a decline the portal stores `0`. |
| `Approved` | `1`/`0` (boolean accepted) | yes | Approval flag. The push carries no ISO response code; the portal records `0` for approved and `99` ("Declined, terminal-reported") otherwise, keeping `ResponseDescription` as the text. |
| `ResponseDescription` | string | yes | Free text from the host (`Transaction approved`, `Transaction Declined`, …). |
| `TransType` | string | yes | `WTH` (withdrawal / sale), `RWT` (reversal), `INQ` (balance inquiry). Defaults to `WTH` if missing. |
| `RRN` | string | recommended | Retrieval reference number, normally 12 digits. Values that look like an authorization number or date are ignored. |
| `CardLast4` | string | recommended | Last four digits only. Never send a full PAN. |
| `SourceAccount` | string | optional | `CA` (checking) / `SA` (savings) / `CC`. Stored for reference. |
| `Host`, `HostTermID` | string | optional | Host name and the terminal id the host knows. Stored for reference. |
| `Software` | string | optional | Terminal firmware version. Logged. |
| `flow_id` | string | optional | Terminal-side request id. Logged. |
| `physicalTerminalId`, `virtualTerminalId` | string | optional | Only for multiplexed terminals where the display id (`GH…`) and host id (`HG…`) differ; when present they replace the `TermID` lookup. |

**All money fields are whole cents.** `21925` means $219.25. The portal divides by 100 on receipt.

**Amounts the portal derives:** dispensed = `RequestedAmt` when approved, else 0; settlement = `TotalAmt` when
approved, else 0; sale = `RequestedAmt`. The tip and cash back are stored separately and are *not* added to the
dispensed figure again, so send them exactly once, in their own fields.

## 4. Response

Every accepted push gets `200` with the TSI (Transaction Service Interface) acknowledgment fields the Ingenico
Store & Forward queue needs before it moves to the next transaction. Send the same fields' expectations into the
Castles implementation: a terminal that does not see `host_response_code: "00"` keeps retrying.

```json
{
  "success": true,
  "tsn": "59435215",
  "message": "Transaction ingested successfully",
  "transactionId": 54371234,
  "transactionDatetime": "2026-10-07T09:04:15.000Z",
  "isApproved": true,
  "isDispensed": true,
  "isDuplicate": false,
  "host_response_code": "00",
  "host_response_isocode": "00",
  "error_result_code": "000",
  "extended_error_code": "0000"
}
```

`message` is one of `Transaction ingested successfully`, `Transaction merged successfully` (tip, cash back and
RRN merged onto the row the host poll had already created), `Transaction updated with RRN`, or
`Transaction already exists` (an exact duplicate of a push already received). All of them are `200` and all mean
"delivered": the terminal must not resend.

| Status | Body | Meaning |
|---|---|---|
| `200` | as above | Received (inserted, merged or already present) |
| `401` | `{ "error": "Unauthorized" }` | Missing or unknown `tenantAccessKey` |
| `500` | `{ "error": "<reason>", "host_response_code": "96", "host_response_isocode": "96", "error_result_code": "100", "extended_error_code": "5000" }` | Rejected or failed: a malformed body, a required field missing or out of range (the reason names the field, e.g. `Invalid amounts: all amounts must be non-negative`), or a portal error. Safe to retry with the same `tsn` once the payload is fixed. |

Full detail of the acknowledgment contract: `docs/TSI-PROTOCOL-IMPLEMENTATION.md`.

## 5. Rules worth building to

- **Send every transaction, including declines and reversals.** Declines carry the surcharge and the decline text;
  reversals (`TransType: "RWT"`) reference the original's `TerminalSequenceNum` and do not carry a card.
- **Retries are safe.** The duplicate key is `TermID + TerminalSequenceNum + date + time (+ type)`. Resending the
  same push is idempotent; resending with a *different* sequence number creates a second transaction.
- **Keep `TotalAmt` consistent.** The portal trusts `TotalAmt` for the settled amount and does not recompute it.
- **Local time in `TransDateTimeUTC`.** Match the Ingenico behaviour (local time plus `TimeZone`/`TimeZoneDST`)
  rather than true UTC, or the day boundary moves.
- **No full card numbers, ever.** `CardLast4` only.

## 6. Batch endpoint (optional)

`POST /api/v1/live-transactions/batch` on the same host accepts `{ "transactions": [ <transactionJSON>, … ] }`
with the key in the `X-API-Key` header, for catch-up after an outage. The single-transaction push above is what
the Ingenico terminals use in production and is the recommended path.

## 7. Where the code lives (for TFI)

`backend/functions/live-transaction-ingestion/index.js`: routing (`/transactions/addTransaction`), authentication,
`ingestTerminalTransaction()` (cents → dollars, approval handling, terminal id mapping), and the duplicate / merge
logic onto polled rows (`has_rpt_data`, `rpt_merge_timestamp`, `merge_notes` columns on `daily_transactions`).
The function is exposed through a Lambda Function URL (`infrastructure/lib/reporting-portal-stack.ts`, "Live
Transaction Ingestion Function"). Related notes: `docs/LIVE-TRANSACTIONS.md`, `docs/TROUBLESHOOTING.md`
("Live Transaction Integration Issues"), backlog items BL-24 (local time), BL-27 (settled side lacks tip columns)
and BL-29 (sale vs total).
