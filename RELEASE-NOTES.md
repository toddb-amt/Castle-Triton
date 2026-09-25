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

## 6.2.8 — 2026-09-25 · PR #4 · base 6.2.7 · versionCode 70

### Highlights

**Custom amounts round up to the next multiple of the minimum.** The configured minimum is
also the step. With a $10 minimum, $12.50 becomes $20 and $5 becomes $10; $20 stays $20.
Previously an entry under the minimum was refused and anything in range went to the host
exactly as typed, cents included. This was pulled out of 6.2.7 because that build had
already shipped to terminals when the rule was decided.

### What operators and customers will notice

- **Amount screen, custom entry:** the rounded amount is what the screen shows, with a
  brief "Rounded up to $20.00 (withdrawals in $10.00 steps)" note. The customer still
  presses Continue. Preset buttons are exact and never round. The maximum still applies to
  the rounded amount.
- Nothing changes for POS-driven sales; the register owns those amounts.

### Fixes

**Amount entry (`AMT-01`)**
- `AmountRounding` (pure, cents arithmetic; the dollar overload converts through cents so
  binary-double noise cannot pick the wrong step) applied in the custom-amount dialog.

### Known issues and deferred

- MyView alert and remote resolve for a pending reversal (`REV-02`), POS/SYS release switch
  (`POS-12`), host-layer robustness (R2), `HOST-14`, `LOG-01`, `TEST-01` — unchanged.

### Verification

- `AmountRoundingTest` (6) — written before the helper: below-minimum, between steps, exact
  multiples, non-whole-dollar minimum, no usable step, float-drift safety. Full suite 216
  tests; the 3 pre-existing `TEST-01` failures only.
- Device: pending — custom $12.50 with a $10 minimum shows $20 and the rounding note,
  custom $5 shows $10, custom $20 stays $20, preset $20 stays $20, custom $495 → $500,
  custom $501 → "Amount too high".

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
