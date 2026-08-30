# cashub — CasHUB fleet automation

CLI for the Castles CasHUB Web (RESTful) API. Replaces portal clicking for the
three fleet operations we do most:

| Command | What it does |
|---|---|
| `cashub status` | Fleet health — flags terminals disconnected AND silent > 26h (exit code 2, cron-friendly) |
| `cashub params push` | Push per-terminal central config (host/port/TID/fees) to `castech.emvtxn` |
| `cashub app push` | Roll an app version already uploaded to CasHUB out to a terminal or a whole merchant |

Zero dependencies. Requires Node 18+.

## Setup (one time)

Uses the **CasHUB Tenant API v3** (online docs 2.50.x): auth is a single
`POST /tenant/v3/token` with App ID + Secret — no portal login, no
Auth-Id/Tenant-Id headers.

1. **API credential**: CasHUB portal → My Enterprise → API Service → Create
   Credential. Note the **App ID** and **Secret**, add this machine's public
   IP to the credential's **Allow IP** list, and **bind an API User** to the
   credential (v3 requirement — calls are refused without it, status 1005).
2. **Base URL**: production is `https://api.us.cashub.cloud` (verified
   2026-08-29).
3. `cp config.example.json config.json` and fill in appid/secret
   (`config.json` is git-ignored — it holds the secret).
4. `node cashub.js login` — verifies the credential and shows the tenant.

## Parameter push

The manifest (`fleet.json`, start from `fleet.example.json`) holds shared
`defaults` plus one entry per terminal. `match` is how the terminal is named
**in CasHUB** (terminal name, hardware serial, or self-defined ID); `params`
must include the unique `terminal_id` (processor TID).

```bash
node cashub.js params render --fleet fleet.json            # offline preview
node cashub.js params push --fleet fleet.json --terminal <match> --dry-run
node cashub.js params push --fleet fleet.json --terminal <match>   # one terminal
node cashub.js params push --fleet fleet.json --all                # whole manifest
```

Recognized keys (anything else warns — the app ignores unknown keys):
`host_address, host_port, terminal_id, processor_type, protocol_type,
use_flat_fee, flat_fee, percentage_fee, min_amount, max_amount`
(source of truth: `CasHubParams.java` / `KmsConfigStore.java`).

**Merchant-level pushes** (`params push-merchant`) are for SHARED values only
(fees, limits). The app merges merchant parameters OVER terminal parameters
(last-wins in `CasHubParams.applyToConfig`), so the tool hard-refuses
`terminal_id` at merchant level — it would clobber every TID under the merchant.

Terminals apply pushed parameters on their next CasHUB poll, or immediately via
the `PARAMETER_UPDATED` broadcast when the app is running.

## App rollout

```bash
node cashub.js apps                                        # what's uploaded
node cashub.js app push --app castech.emvtxn --version 6.2.3 --terminal <match> --dry-run
node cashub.js app push --app castech.emvtxn --version 6.2.3 --merchant <name> \
    --at "2026-08-30 02:30:00"                             # staged overnight install
```

Uploading a NEW app version into CasHUB has no documented API (training doc
v1.0.0) — that step stays in the portal (Application Management). The push
itself is what this automates.

## To verify on first live run

- [ ] Credential works end-to-end (`cashub login` succeeds — if it fails with
      status 1005, bind an API User to the credential in the portal)
- [ ] Timezone of `execute_at` / `install_time` (server-side interpretation
      unknown; doc examples use a past time to mean "now")
- [ ] Whether repeated parameter pushes accumulate rows in CasHUB — the app
      merges last-wins and logs a "consolidate" warning. v3 has
      GET/PATCH/DELETE on `app-prms/{prm_id}`, so update-in-place is a
      possible upgrade for this tool.
- [ ] Terminals list pagination beyond `limit=500` (we warn if `total` exceeds
      returned items)

Note: v3 rate limit is 10 requests/second per application.

## Files

- `cashub.js` — the CLI (single file, no deps)
- `config.json` — credentials (git-ignored; template: `config.example.json`)
- `fleet.json` — terminal manifest of record (tracked; template: `fleet.example.json`)
- `.cashub-state.json` — cached token/authid (git-ignored; delete to force re-auth)
