# Castle POS Integration Spec

**Status:** Draft 1.0
**Audience:** TFI Proxy team (`~/Documents/TFI/Ingenico/tfi-proxy`)
**Source of truth:** `app/src/main/java/castech/emvtxn/pos/PosWire.java` —
any change in `PosWire.java` is a breaking change to this contract.

This document defines the wire protocol between a Castle S1F4 PRO terminal
running the Cashless ATM app (in POS mode) and the TFI proxy. The proxy
exposes the same unified `/tsi/v1/payment` POS-facing API for both Ingenico
and Castle terminals; this spec is the Castle-side contract that the proxy's
`src/castle/` adapter must satisfy.

## 1. Architecture

```
┌──────────────┐    HMAC-signed REST    ┌──────────────┐    WebSocket+JWT    ┌──────────────────────┐
│   POS App    │ ────────────────────▶  │  TFI Proxy   │ ─────────────────▶  │ Castle S1F4 PRO ATM  │
│ (Flowhub,    │                        │  /castle/v1  │                     │ (POS mode enabled)   │
│  Sweed, ...) │ ◀────────────────────  │              │ ◀─────────────────  │                      │
└──────────────┘    JSON response       └──────────────┘    response env     └──────────────────────┘
                                              │
                                              │ HMAC + JWT minting + per-merchant routing
                                              ▼
                                        ┌──────────┐
                                        │ Postgres │
                                        └──────────┘
```

- **POS-facing API** (proxy ↔ POS): exactly the same surface used today for Ingenico.
- **Terminal-facing API** (proxy ↔ Castle): defined in this document.
- **Card processing** (Castle ↔ Hyosung-class processors): unchanged. The Castle
  terminal still talks directly to the configured processor (Switch Commerce / EFX
  / DNS) for the actual financial transaction. The proxy only orchestrates *which*
  transaction runs on *which* terminal.

## 2. Transport

- **Protocol:** WebSocket (RFC 6455) over TLS.
- **Framing:** text frames only. One JSON envelope per frame.
- **Encoding:** UTF-8. JSON per RFC 8259.
- **Two endpoints:**

| Endpoint   | Path                       | Purpose                                     | Auth             |
|------------|----------------------------|---------------------------------------------|------------------|
| Registration | `/castle/v1/register`    | One-shot: send creds, receive JWT           | none (creds in body) |
| Connection   | `/castle/v1/connect`     | Long-lived: transactions + events           | `Authorization: Bearer <jwt>` header |

These are paths only; the host portion is whatever the operator configures as
`PosConfig.proxyBaseUrl` (e.g. `wss://proxy.myviewonline.com`).

## 3. Envelope shape

Every frame in both directions is a JSON object with this shape:

```json
{
  "type":      "request" | "response" | "event" | "event_ack",
  "flow_id":   "<uuid v4>",
  "timestamp": "2026-05-21T14:30:00.123Z",
  "resource":  { "type": "<command>", ... },
  "error":     { "code": "<code>", "message": "<msg>" }
}
```

- `type` — required. One of the four constants. Anything else is rejected with `unknown envelope type`.
- `flow_id` — required. UUID v4. Used to correlate request↔response and event↔event_ack.
- `timestamp` — ISO-8601 UTC with millisecond precision (`YYYY-MM-DDTHH:MM:SS.sssZ`, length 24).
  If missing on the wire, decoder substitutes "now"; tolerated for robustness, but
  senders should always populate it.
- `resource` — request/response/event body. Shape depends on `resource.type`.
  Absent on `event_ack` envelopes.
- `error` — only present on `response` envelopes that failed. Mutually exclusive
  with `resource` in practice (success responses carry data; failure responses
  carry an error block).

## 4. Connection lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│ Terminal startup:                                               │
│   if PosConfig.isEnabled():                                     │
│     if JWT cached and not expired (with 60s margin):            │
│       → connect immediately with cached JWT                     │
│     else:                                                       │
│       → open /castle/v1/register, send register request         │
│       → receive JWT + connection_url                            │
│       → close registration socket                               │
│       → open connection_url with Authorization: Bearer JWT      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ Steady state:                                                   │
│   - Terminal sends heartbeat event every N seconds              │
│     (N = registration response's heartbeat_interval_sec,        │
│      default 30s)                                               │
│   - Proxy sends request envelopes; terminal responds            │
│   - Terminal sends unsolicited event envelopes; proxy acks      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ Disconnect:                                                     │
│   - Auto-reconnect with exponential backoff:                    │
│     1s → 2s → 4s → 8s → 16s → 30s (cap)                          │
│   - Reconnect attempt counter resets on successful open         │
│   - If proxy returns HTTP 401/403 on reconnect:                 │
│     → terminal clears cached JWT, next attempt re-registers     │
│   - Terminal failures (unknown_terminal / invalid_credentials): │
│     → terminal stops trying, fires onTerminalError              │
│     → operator intervention required                            │
└─────────────────────────────────────────────────────────────────┘
```

### 4.1 Registration request

```json
{
  "type": "request",
  "flow_id": "abc-123-...",
  "timestamp": "2026-05-21T14:30:00.000Z",
  "resource": {
    "type": "register",
    "tsn": "000195250202209",
    "terminal_access_key": "<credential provisioned via proxy admin portal>",
    "capabilities": ["sale", "balance_inquiry", "reversal", "settlement", "info", "reversal_status"],
    "app_version": "6.1",
    "device_model": "S1F4 PRO"
  }
}
```

### 4.2 Registration response (success)

```json
{
  "type": "response",
  "flow_id": "abc-123-...",
  "timestamp": "2026-05-21T14:30:00.050Z",
  "resource": {
    "status": "approved",
    "jwt": "<RS256 token>",
    "jwt_expires_at": 1747800000000,
    "connection_url": "wss://proxy.myviewonline.com/castle/v1/connect",
    "heartbeat_interval_sec": 30
  }
}
```

- `jwt_expires_at` — millis since epoch.
- `connection_url` — optional. If absent, terminal falls back to
  `proxyBaseUrl + /castle/v1/connect`. Useful for sharding.
- `heartbeat_interval_sec` — optional, default 30.

### 4.3 Registration response (failure)

```json
{
  "type": "response",
  "flow_id": "abc-123-...",
  "timestamp": "2026-05-21T14:30:00.050Z",
  "error": { "code": "unknown_terminal", "message": "tsn not provisioned" }
}
```

Registration error codes:

| Code                     | Behavior on terminal                                        |
|--------------------------|-------------------------------------------------------------|
| `unknown_terminal`       | **Terminal stops.** Operator must provision the terminal in the proxy admin portal. |
| `invalid_credentials`    | **Terminal stops.** Re-issue the `terminal_access_key`.     |
| `connection_failed`      | Transient. Terminal reconnects with backoff.                |
| `malformed_response`     | Logged. Terminal reconnects with backoff.                   |
| `invalid_response`       | Logged. Terminal reconnects with backoff.                   |
| `timeout`                | Logged. Terminal reconnects with backoff.                   |

## 5. Commands (proxy → terminal request, terminal → proxy response)

### 5.1 Supported commands

#### 5.1.1 `sale` — cash withdrawal

**Request:**
```json
{
  "type": "request",
  "flow_id": "<uuid>",
  "timestamp": "2026-05-21T14:30:00.000Z",
  "resource": {
    "type": "sale",
    "amount": 5000,
    "surcharge": 250,
    "account_type": "checking",
    "tender_type": "debit",
    "invoice_no": "POS-12345",
    "clerk_id": "C001"
  }
}
```

- `amount` — **required**, integer cents, must be > 0
- `surcharge` — optional, integer cents, must be >= 0, default 0
- `account_type` — optional, `"checking"` | `"savings"` | `"credit"`, default `"checking"`
- `tender_type`, `invoice_no`, `clerk_id` — optional, passed through to host as available

**Response (approved):**
```json
{
  "type": "response",
  "flow_id": "<echo>",
  "timestamp": "2026-05-21T14:30:42.000Z",
  "resource": {
    "status": "approved",
    "response_code": "00",
    "reference_number": "RRN12345678",
    "auth_code": "AUTH99",
    "auth_date": "2026/05/21",
    "auth_time": "14:30:00",
    "account_balance_cents": 12345,
    "available_balance_cents": 12000,
    "display_message": "APPROVED",
    "amount": 5000,
    "surcharge": 250
  }
}
```

**Response (declined):**
```json
{
  "resource": {
    "status": "declined",
    "response_code": "51",
    "display_message": "INSUFFICIENT FUNDS",
    "retain_card": false
  }
}
```

**Response (error):**
```json
{
  "error": { "code": "host_unreachable", "message": "TLS handshake timeout" }
}
```

#### 5.1.2 `balance_inquiry`

**Request:**
```json
{
  "resource": {
    "type": "balance_inquiry",
    "account_type": "checking"
  }
}
```

**Response (approved):**
```json
{
  "resource": {
    "status": "approved",
    "response_code": "00",
    "account_balance_cents": 100000,
    "available_balance_cents": 95000,
    "display_message": "APPROVED"
  }
}
```

#### 5.1.3 `reversal`

**Request:**
```json
{
  "resource": {
    "type": "reversal",
    "reason": "merchant_cancel"
  }
}
```

**Response (success):**
```json
{
  "resource": {
    "status": "ok",
    "display_message": "reversal complete"
  }
}
```

Reversals draw from the terminal's persisted reversal queue. The terminal does
not require a specific transaction reference — the host-side correlation is by
sequence number which the terminal already has.

#### 5.1.4 `settlement` (host totals)

**Request:**
```json
{
  "resource": {
    "type": "settlement",
    "reset": true
  }
}
```

- `reset` — optional, default false. `true` resets totals on the host (closing
  the batch); `false` is a query only.

**Response (success):**
```json
{
  "resource": {
    "status": "approved",
    "withdrawal_count": 42,
    "balance_inquiry_count": 17,
    "total_cash_dispensed_cents": 250000,
    "total_surcharges_cents": 4200
  }
}
```

#### 5.1.5 `info` — terminal status snapshot

**Request:**
```json
{
  "resource": { "type": "info" }
}
```

**Response:**
```json
{
  "resource": {
    "app_version": "6.1",
    "device_model": "S1F4 PRO",
    "tsn": "000195250202209",
    "state": "connected",
    "ready": true,
    "not_ready_reason": "",
    "pending_reversals": 0,
    "capabilities": ["sale", "balance_inquiry", "reversal", "settlement", "info", "reversal_status"]
  }
}
```

- `state` — one of `"stopped"`, `"no_credentials"`, `"connecting"`, `"connected"`, `"reconnecting"`, `"out_of_service:<reason>"`
- `ready` — true when terminal can accept a new sale/balance_inquiry
- `not_ready_reason` — human-readable explanation when `ready` is false

#### 5.1.6 `reversal_status` — count of pending reversals

**Request:**
```json
{
  "resource": { "type": "reversal_status" }
}
```

**Response:**
```json
{
  "resource": { "pending_count": 0 }
}
```

### 5.2 Explicitly unsupported commands

These commands appear in the unified POS API but the Castle ATM does not
implement them. Sending them produces a deterministic error:

| Command                | Error response                                              |
|------------------------|-------------------------------------------------------------|
| `refund`               | `not_supported`, `"command 'refund' is not supported by Castle ATM terminals"` |
| `void`                 | `not_supported`, ...                                        |
| `preauth`              | `not_supported`, ...                                        |
| `preauth_completion`   | `not_supported`, ...                                        |
| `reprint`              | `not_supported`, ...                                        |

The proxy should gate these per-terminal based on the `capabilities` list
returned at registration time (or from `info`).

### 5.3 Unknown / malformed requests

- Unknown `resource.type` → `not_supported`, `"unknown command type: <name>"`
- Missing `resource` block → `invalid_request`, `"missing resource block"`
- Missing `resource.type` → `invalid_request`, `"missing resource.type"`

## 6. Events (terminal → proxy)

Events are unsolicited messages from the terminal. The proxy is expected to
acknowledge each event with an `event_ack` carrying the same `flow_id` (the
terminal does not currently retry events; ack is for proxy bookkeeping only).

### 6.1 `heartbeat`

```json
{
  "type": "event",
  "flow_id": "<uuid>",
  "timestamp": "...",
  "resource": {
    "type": "heartbeat",
    "uptime_millis": 1747800123456
  }
}
```

Sent every `heartbeat_interval_sec` (default 30). Absence of a heartbeat for
>2x the interval should be treated by the proxy as a dropped terminal.

### 6.2 `state_change` (future use — reserved)

Will carry transitions like "out of paper", "powering down", "host connection lost".
Not yet emitted by the current terminal code.

## 7. Standard error codes

Sent in the `error.code` field. Stable identifiers — never localized; pair with
`error.message` for human-readable detail.

| Code                  | Meaning                                                          |
|-----------------------|------------------------------------------------------------------|
| `not_supported`       | Command exists in the protocol but this terminal doesn't implement it |
| `terminal_busy`       | Another transaction is in progress                               |
| `host_unreachable`    | Cannot reach the card processor (TLS, timeout, conn refused)     |
| `key_not_loaded`      | No working PIN encryption key — operator must initialize keys    |
| `timeout`             | Operation exceeded its time budget                               |
| `card_read_failed`    | Card insertion/swipe/tap failed                                  |
| `pin_entry_failed`    | PIN pad collection failed                                        |
| `reversal_pending`    | Cannot start new txn until pending reversals drain               |
| `invalid_request`     | Required field missing or invalid value                          |
| `internal_error`      | Unexpected internal failure (logged on terminal)                 |
| `unknown_terminal`    | (Registration only) Proxy doesn't recognize this `tsn`           |
| `invalid_credentials` | (Registration only) Bad `terminal_access_key`                    |
| `connection_failed`   | Generic transport-level failure                                  |
| `malformed_response`  | Server sent unparseable JSON                                     |
| `invalid_response`    | Server sent valid JSON but with missing/invalid required fields  |

## 8. Implementation references

| Concern                       | File                                                            |
|-------------------------------|-----------------------------------------------------------------|
| Constants (URL paths, field names, codes) | `app/src/main/java/castech/emvtxn/pos/PosWire.java` |
| Envelope codec                | `pos/PosEnvelope.java`, `pos/PosEnvelopeCodec.java`             |
| Registration client           | `pos/PosRegistrationClient.java`                                |
| Persistent connection         | `pos/PosConnectionClient.java`                                  |
| Command router                | `pos/PosCommandDispatcher.java`                                 |
| Transaction executor          | `pos/PosTransactionExecutor.java`                               |
| Host-layer adapter            | `pos/AtmHostServiceGateway.java`                                |
| Orchestrator (lifecycle)      | `pos/PosOrchestrator.java`                                      |
| MainActivity hooks            | `MainActivity.startPosModeIfEnabled()`, `MainActivity.atmTransactionEventListener` |

## 9. Versioning + breaking changes

- This spec is **Draft 1.0**. Once both sides are in production, changes
  go through normal versioning (semver) and the path prefix bumps:
  `/castle/v2/...`.
- Adding new optional response fields is non-breaking.
- Adding new commands is non-breaking (terminals can return `not_supported`).
- Renaming/removing fields, changing field types, or removing commands
  is breaking — bump the version path.

## 10. Testing checklist (proxy team)

When implementing the `src/castle/` adapter against this spec:

- [ ] Registration: succeeds with valid creds; returns `unknown_terminal` for
      unregistered TSN; returns `invalid_credentials` for wrong access key.
- [ ] Connection: rejects without `Authorization: Bearer JWT` header;
      rejects with expired JWT (terminal will clear cache + re-register).
- [ ] Heartbeat: terminal sends at the configured interval. Proxy should
      mark terminal disconnected if 2x interval elapses without one.
- [ ] Sale / balance_inquiry / reversal / settlement / info / reversal_status:
      see §5 for each. Verify proxy can build all 6 requests and parse all 6
      response shapes (approved + declined + error variants where applicable).
- [ ] Unsupported commands: verify proxy receives `not_supported` and
      surfaces it cleanly to the POS caller.
- [ ] Disconnect + reconnect: terminal auto-reconnects with backoff. Proxy
      should not assume socket identity across reconnects — match by TSN.
- [ ] Sharding: if the proxy returns a `connection_url` in the registration
      response that differs from the registration host, the terminal will use it.
