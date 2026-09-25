# Code Review Backlog

Pre-release review of the v6.2.5 delta plus the runtime subsystems it depends on
(ATM host/network layer, key management, POS proxy client, MainActivity lifecycle,
admin access tiers). Reviewed 2026-09-18 against branch `mksk-cfff-key`
(6.2.5 = `10187bf`). Fixes land on branch `release/v6.2.6`; each ticket is its own commit.

**How to use this file.** Each item is a ticket. Check the box when the fix has shipped
in a versioned PR and add the version in the line (e.g. `— shipped 6.2.6`). Items are
grouped by *target release*, not by subsystem, so the top group is the go-live gate.
Do not remove closed items; the history is the point.

**Legend**
- Severity: **HIGH** = money, availability, or PCI exposure · **MED** = wrong behaviour under a
  realistic sequence · **LOW** = hygiene / dead code / cosmetic
- Verification: **✔** = re-verified line-by-line by the lead reviewer · **●** = verified by the
  sub-reviewer; mechanism confirmed, not every line re-read
- Paths are relative to `app/src/main/java/castech/emvtxn/` unless stated.

---

## R1 — Fix before go-live (target **v6.2.6**)

Contained, low-risk, high-value. Nothing here changes a design; each is a guard, a
reordering, or a deletion. HOST-04 is the one item that wants a real end-to-end
transaction on the terminal before merge. The POS proxy items that also gate go-live
are in the next group (R1-POS) — D2 was decided YES on 2026-09-18.

- [x] **ADM-01** · HIGH · ✔ · `Fragment_page_admin_atm.java:1766,1771` (`updateReversalStatus`), `:1839` (`clearPendingReversals`) — **in release/v6.2.6 `f8d23b1` · on-device verification pending**
  **Defect:** `updateReversalStatus()` unconditionally re-enables and un-hides `btnClearReversals`, overriding the tier greying applied at login; `clearPendingReversals()` has no tier check.
  **Fails when:** Normal admin taps the allowed *Process Pending Reversals* while the host is unreachable → reversals stay pending → `onProcessingComplete` (`:1827`) re-enables the Super-only button → tap → confirm → `clearAllPendingReversals()` deletes unsent reversal records. Greying holds at login; this is a two-step bypass. Also reachable via *Request New Working Key* (`:1587`).
  **Fix:** `btnClearReversals.setEnabled(pendingCount > 0 && accessLevel == ACCESS_SUPER)`; guard the top of `clearPendingReversals()` with `accessLevel == ACCESS_SUPER`.

- [x] **SEC-01** · HIGH · ● · `atm/host/CastleKeyManager.java:1514` (+ `:444-445, :674, :802, :1358, :1520, :2020`) — **in release/v6.2.6 `650bfc4` · on-device verification pending**
  **Defect:** `Log.d(..., "Clear PIN block: " + maskKey(clearPinBlock))` — for an ISO 9564 Format-0 block the first four nibbles are `0, PIN-length, PIN[0], PIN[1]`, which the PAN block does **not** mask (its first four nibbles are `0000`). `maskKey`'s `substring(0,4)` therefore logs the PIN length and first two PIN digits in clear. The other lines log full encrypted PIN blocks, encrypted key parts, and a known-plaintext ciphertext under the master key at every init. `minifyEnabled false` → these ship in production logcat.
  **Fails when:** anyone with logcat access (adb is now enabled on terminal …680) reads a customer's PIN prefix. PCI exposure regardless of exploitation.
  **Fix:** delete all listed log lines. Log at most a KCV for keys and nothing for PIN blocks.

- [x] **ADM-05** · HIGH (POS product) · ✔ · `Fragment_page_admin_atm.java` — `requestNewWorkingKey()`, `testConnection()`, `downloadKeys()` each call `saveSettings()` before their host call; `saveSettings()` → `savePosSettings()` → `posConfig.setEnabled(cbEnablePosMode.isChecked())` — found during the 6.2.6 device pass (2026-09-18): POS mode silently OFF on the test unit — **in release/v6.2.6 `5dcfaee`** — **verified on device 2026-09-18** (Normal admin → Request New Working Key: no PosConfig write, flag intact, POS connection unbroken)
  **Defect:** an implicit save persists the POS-mode checkbox (and proxy URL / access key) — whatever they hold — even though the user only asked for a key or a connection test; and it persists them even when the POS section is greyed (read-only) for the current tier.
  **Fails when:** a stray tap unchecks "Enable POS Mode" on the long Admin page (no programmatic path flips it; it was unchecked in an admin session between 14:32 and 15:00 whose log had already been evicted — see LOG-01), then any later Request New Working Key / Test Connection / Save commits `enabled=false`. At 15:06:19 a Request New Working Key rewrote it (no "toggled" line because it was already false). From the next boot the unit runs as a walk-up ATM with no indication at the register; the proxy sees the terminal vanish. **At a POS site this is a silent loss of the primary mode of operation.**
  **Fix:** `saveSettings(boolean includePosSettings)` — the Save button passes true, the three implicit callers pass false; `savePosSettings()` persists a control only if it is enabled for this tier; the toggle log is now `Log.w` with the admin tier. Reproduced clean on 2026-09-18 (Normal admin → Request New Working Key rewrote the flag unchanged, `POS mode ENABLED`), fix verified by re-running the same sequence. The POS/SYS switch (POS-12) will move enable/disable behind its own PIN prompt; this fix stands regardless.

- [x] **SEC-02** · HIGH · ✔ · `MainActivity.java:3287, 3378, 3567, 3623, 3639, 3909, 3915, 4107, 4327-4328, 4337, 4361, 4448, 4497, 5551, 5894, 6244, 9343-9344, 9346, 9387` · `callback/MyEMVSPEvent.java:413, 599, 989` — found during the 6.2.6 device pass (2026-09-18) from the live transaction log — **in release/v6.2.6 `fa3335d` + `78ab0e2`** — part 2 found on the masked build's own log: PAN still appeared 9× per transaction inside EMV TLV dumps, `5A hex`/`57 hex`/`atmClearPan` debug lines and `HyosungMessageBuilder` Field 6, plus whole-message hex dumps on the reversal path (a Type 86 echoes the 85's EMV TLV). Fixed with `LogMask.tlv()` (BER-TLV walker redacting only 5A/57/5F20 values) and `LogMask.std1()` (field-wise STD1 renderer); 11-vector `LogMaskTest` pins the contract. Verified on-device: zero raw PAN occurrences in the step-3 log
  **Defect:** 25 log lines print card data or PIN blocks in the readable form. Twelve print **clear Track 2 or the full PAN** (`Tag 57 raw`, `rcData.track2Data`, `CLEAR PAN from Track2`, `Track2 raw hex` / `Track2 ASCII` on both CL and CT paths, the `asciiPAN` / `atmTrack2Data` debug dumps); ten print the encrypted PIN block; three print encrypted Track 2 ciphertext. SEC-01 covered `CastleKeyManager` only.
  **Fails when:** every transaction — the pass log showed `Track2 ASCII: ;443041******8318=2905…` (full PAN, expiry, service code) in logcat. Track 2 is sensitive authentication data; retaining it after authorisation in any form, logs included, is a PCI DSS violation, and adb is enabled on fielded units.
  **Fix:** new `LogMask` helper — `pan()` last four digits only, `track2()` length + masked PAN, `pinBlock()` byte count, `len()` for ciphertext — applied to all 25 lines. The `first-6 + ****` PAN lines already present are PCI-acceptable and unchanged. Log-only change; no behaviour change.

- [x] **HOST-01** · HIGH · ✔ · `atm/host/AtmTransactionManager.java:429` (flag) vs `:436` / `:444` (connect) — **in release/v6.2.6 `7f7da6c` · on-device verification pending**
  **Defect:** `requestSentToHost = true` is set *before* `ensureConnected()` runs (explicitly on the Triton branch, inside `connection.sendTransaction()` on the Hyosung branch).
  **Fails when:** host down, "Connection timeout after 30000ms", or TLS failure → catch at `:452` sees `requestSentToHost == true` → promotes the pre-send reversal (TIMEOUT or HOST_ERROR) for a request that was **never transmitted** → drain retries a Type 86 the host never saw → 15 attempts → `STATUS_FAILED` → `sessionState.outOfService()`. **A single connect-time blip takes the terminal out of service.**
  **Fix:** call `connection.ensureConnected()` explicitly first; on connect failure `clearPreSendReversal()` and report a plain connection error (no reversal); set `requestSentToHost = true` immediately before the write.

- [x] **HOST-02** · HIGH · ✔ · `atm/host/AtmHostConnection.java:706-756` (`completeHandshake`) — **in release/v6.2.6 `49718d5` · on-device verification pending**
  **Defect:** catches only `SocketTimeoutException` / `IOException` (`:745, :748`), but `readResponse()` throws `ConnectionException` (extends `Exception`, `:870`) on EOF (`:532`) or a non-STX byte (`:610`), and `socket.setSoTimeout()` (`:733, :743`) NPEs if a concurrent `disconnect()` nulled `socket`. Both escape before `currentResponse = response` (`AtmTransactionManager.java:446`).
  **Fails when:** a host/MUX closes the socket *without* sending EOT right after responding (normal for a MUX restart) → exception unwinds into the catch at `:452` with `requestSentToHost && currentResponse == null` → RECONNECT_AND_REVERSE → customer shown "Connection error" while the host approved. This is the exact regression the comment at `:707-716` says was fixed; the fix only covered `IOException`. Latent — testing hasn't hit it because test hosts send EOT.
  **Fix:** wrap the whole body in `catch (Exception e)` (log, never throw); null-guard `socket` before `setSoTimeout`. Extend `AtmHostConnectionHandshakeTest` with an EOF case (it currently cannot, because `socket` is never injected).

- [x] **HOST-03** · HIGH · ✔ · `atm/host/AtmHostConnection.java:158-177` (`connect`) — **in release/v6.2.6 `2a32564` · on-device verification pending**
  **Defect:** `sslSocket.startHandshake()` (`:174`) runs before `socket.setSoTimeout(...)` (`:177`); the underlying socket has `SO_TIMEOUT = 0` during the TLS handshake. `connectionTimeout` (`:161`) bounds only the TCP connect.
  **Fails when:** a MUX/middlebox ACKs the TCP connect and the ClientHello but never answers → no unacked data → no kernel retransmit timeout → blocks forever. In `performCashWithdrawal` the `finally` never runs → `transactionInProgress` stays true → every later customer gets "Transaction already in progress" until app restart. In `downloadKeysSync()` the **static** `keyDownloadLock` is held forever → every `openSession()` in the process blocks.
  **Fix:** `socket.setSoTimeout(config.getConnectionTimeout())` **before** `startHandshake()`, then set `responseTimeout` after. Two lines.

- [x] **HOST-04** · HIGH · ✔ · `atm/host/AtmHostConnection.java:212-214` (`isConnected`), `:511` (timeout, no disconnect), `:532`+`:870` (EOF path skips the `:513` disconnect handler), `:816-820` (`ensureConnected`); `atm/host/AtmTransactionManager.java:1021-1045` (`sendHealthCheck`, no disconnect), `:1170-1177` (`requestHostTotals` — force-disconnects first; the correct pattern) — **in release/v6.2.6 `6ffcf02` · **verified on device 2026-09-18** — approved withdrawal on a fresh socket, 1.5 s round trip, clean disconnect**
  **Defect:** `isConnected()` relies on `Socket.isConnected()`, which stays `true` after the peer sends FIN; `isClosed()` reflects only local close. The host closes right after responding (the code's own comment at `:710-711` calls this normal), and health checks / timeouts / EOF never `disconnect()`, so a dead socket passes `ensureConnected()`.
  **Fails when:** the 6-minute Type 89 health check (auto-started at `AtmHostService.java:170-172`) completes and the host closes → customer arrives → 85 written into the dead socket → EOF/RST → `ConnectionException` with `requestSentToHost == true` → bogus reversal → the HOST-01 chain to OUT_OF_SERVICE. Also affects retry attempts in `sendReversalWithRetry` (`:1342-1365`), which reuse the socket that just timed out.
  **Fix:** in `performCashWithdrawal` force `disconnect()` then `connect()` (mirror `requestHostTotals`); in `sendAndReceive` also `disconnect()` on `SocketTimeoutException` and on the EOF `ConnectionException`; `disconnect()` in a `finally` in every op (health check, status monitoring, config, reversal). **Verify with a real end-to-end approved transaction on the terminal before merge.**

- [x] **SDK-01** · HIGH · ✔ · `MainActivity.java:2273-2333` (`abortTransaction`), `:2927 / :2942 / :2953` (the only `txnAborted` checks), `:2565 / :2625-2627` (`btnTransaction_Click`), `Fragment_page_transaction.java:848` (Cancel → abort; button never hidden/disabled after card detect) — **in release/v6.2.6 `669e568` · **verified on device 2026-09-18** — Cancel 683 ms after TX refused, stayed on page, approval shown and printed**
  **Defect:** `txnAborted` is consulted only inside the card-detection loop. Once a card is detected nothing downstream (app select, PIN, host call, receipt nav) checks it. `abortTransaction()` waits ≤ 3 s + 1 s (`Thread.interrupt()` cannot unblock a native SDK call or a socket read) then **unconditionally** nulls `threadTxn` (`:2318`), clears `atmTransactionInProgress` (`:2322`), re-enables buttons, and the fragment navigates to the main menu.
  **Fails when:** customer taps Cancel during the host call — the phase that legitimately runs 120–150 s on the busy-MUX path, i.e. exactly when a customer *would* tap Cancel. 4 s later the UI is idle while the old thread is still inside the SDK/socket. (a) Next customer starts → a second thread issues CTOS calls concurrently with the first → the DeadObjectException / CTOS-service-crash class documented at `:399-406`. (b) Or the "cancelled" host call completes → money moves after Cancel → thread yanks the UI to the receipt page (`:4660-4671`).
  **Fix (minimal, for 6.2.6):** if the join times out, do **not** clear `atmTransactionInProgress` / `threadTxn` — leave the terminal busy until the thread really exits (have the thread's own `finally` clear them); check `txnAborted` immediately before the host call and before receipt navigation. **Full (R2 / SDK-03):** check at every phase boundary and move the join/flush off the UI thread.

- [x] **ADM-02** · MED · ● · `Fragment_page_admin_atm.java:776-789`, `:925-934`, hint text at `:755` — **in release/v6.2.6 `3916987` · on-device verification pending**
  **Defect:** the failed-attempt counter resets only on success (`:769`); after a lockout expires it is still at 3.
  **Fails when:** tech mistypes once after any prior lockout → `remaining = -1` → immediate 5-minute relock. Permanent one-strike lockout. Also the hint says "4-6 digit PIN" while the Super PIN is 7 digits.
  **Fix:** reset `KEY_FAILED_ATTEMPTS` when the lockout expires (or when setting it); fix the hint text.

- [x] **ADM-03** · LOW · ● · `Fragment_page_admin_atm.java:892` (`setViewEnabledDimmed`); XML `gone` defaults at `res/layout/fragment_page_admin_atm.xml:114, 463, 510` — **in release/v6.2.6 `635f6fa` · on-device verification pending**
  **Defect:** forces `VISIBLE` on every direct child it touches.
  **Fails when:** after login both `layoutFlatFee` and `layoutPercentageFee` show at once, and *Clear All Pending* / *Clear Transaction History* appear with zero pending. `btnClearHistory` has no click listener anywhere (only reference is `:852`) — a dead button.
  **Fix:** drop the `setVisibility(View.VISIBLE)` line; alpha + enabled is sufficient. Decide whether `btnClearHistory` gets a handler or is removed.

- [x] **REL-01** · LOW · ✔ · `res/layout/fragment_page_admin_atm.xml` — **in release/v6.2.6 `0fbda95` · on-device verification pending**
  **Defect:** the script that inserted the 9 section-header ids rewrote the file from CRLF to LF. Functionally harmless; the PR diff shows ~1,745 changed lines for 9 real ones.
  **Fix:** normalize back to CRLF before the PR so the diff shows only the id additions.

---

## R1-POS — Fix before go-live: POS proxy client (target **v6.2.6**)

**D2 decided 2026-09-18: YES.** Go-live units are cashier-driven POS installs — the POS
system drives transactions through the MyView proxy, with the walk-up flow as the
fallback when the proxy is unavailable. That makes POS-01..06 go-live items. POS-06
ships as the watchdog re-size only; its abort-on-fire half rides with SDK-01's full
fix. The POS LOW items (POS-07..11) moved to R4.

**What the code does today (verified 2026-09-18):** POS mode is a background stack
that runs *alongside* the normal ATM. `Fragment_page_main_menu`, `_amount_selection`
and `_transaction` contain zero references to POS state; `PosOrchestrator.currentState`
(`connecting / connected / reconnecting / out_of_service`) is tracked only for the
proxy's `info` reply and `MainActivity.getPosState()` — no UI reacts to it. So the
walk-up fallback already exists (by construction), but nothing enforces "POS drives all
transactions" while the proxy is connected. See POS-12 and decision D5.

- [x] **POS-01** · HIGH · ● · `pos/PosConnectionClient.java:385-388, 395-408` (`onClosed` / `onFailure` ignore the `compareAndSet` result), `:277` (`openSocket` overwrites without closing), `:253` vs `:349` (`openSocket` accepts RECONNECTING, `onOpen` accepts only CONNECTING), `:101-109` (`superviseConnection`) — **in release/v6.2.6 `6ccd2de` · on-device verification pending** — **verified on device 2026-09-18 — WiFi off 3.5 min: back-off 1/2/4/8/16/30 s, 11 attempts, one socket at the end, no superseded/replaced sockets, no bind_conflict, no supervisor recovery, JWT kept, reconnected 0.4 s after network return**
  **Defect:** stale-socket close/failure events trigger reconnects unconditionally, orphaning healthy connections.
  **Fails when:** the supervisor cancels a socket → OkHttp delivers `onFailure(ws_old, Canceled)` → `scheduleReconnect()` while the supervisor's own reconnect is already completing → two sockets opened, one orphaned → its `onClosed` schedules yet another reconnect while CONNECTED → `sendEnvelope()` returns false for the whole backoff → never converges. If the proxy enforces one binding per TSN (`bind_conflict`, `:229` comment) → `clearJwt()` → re-register → conflict → **5-minute park while a live socket exists.** Fires precisely during network trouble.
  **Fix:** `if (!socket.compareAndSet(webSocket, null)) return;` at the top of both callbacks; `WebSocket prev = socket.getAndSet(ws); if (prev != null) prev.cancel();` in `openSocket()`; no reconnect in the `onOpen` loser path; cancel `reconnectTask` inside `superviseConnection()`.

- [x] **POS-02** · HIGH · ● · `pos/AtmHostServiceGateway.java:33, :41` (final `hostService` captured once), `pos/PosOrchestrator.java:34`, `MainActivity.java:1122-1125` (`startPosModeIfEnabled`, only call site `:1042`), `:836-839` (host rebuild on signature change) — **in release/v6.2.6 `17a31bf` · on-device verification pending** — **verified on device 2026-09-18 — two config-change rebuilds (TID edit and revert): POS stack stopped and restarted each time (~4 s), then a register balance inquiry against the rebuilt service → approved rc=00**
  **Defect:** the POS stack holds the `AtmHostService` instance from first start; a config-signature rebuild shuts down the old instance (`initialized = false`, managers nulled) and `startPosModeIfEnabled()` returns "already running".
  **Fails when:** admin Save / Test Connection / Download Keys / Request New Key / CasHUB apply → from then on `isReady()` is false forever → every POS sale/balance/settlement answers `host_unreachable "host service not initialized"` until app restart. **This is POS trouble we cause ourselves — it violates the fallback requirement from the inside.**
  **Fix:** on host rebuild, `posOrchestrator.stop()` + restart, or resolve the service through a supplier.

- [x] **POS-03** · HIGH · ● · `pos/AtmHostServiceGateway.java:118-126, 141-155, 162-179` — **in release/v6.2.6 `d5fe583` · on-device verification pending** — **code-verified; register sale and cancel-at-terminal both answered correctly (approved rc=00 rrn=383900000005 / declined user_cancelled), POS slot released each time. The sale-during-customer-flow collision itself was not staged**
  **Defect:** readiness checks only `hostService.isTransactionInProgress()` (`AtmTransactionManager.transactionInProgress`, set only at `performWithdrawal` start `:308`), not `GlobalPara.atmTransactionInProgress` (set at `MainActivity.java:2632`). During card-detect/PIN the host flag is false.
  **Fails when:** a customer is on the transaction page waiting to tap → POS `sale` arrives → gateway overwrites `atmSelectedAmount / strAmount / atmTotal` mid-flow, arms a callback, navigates → `performClick` rejected by `:2565` → the customer's flow continues **with the POS amount** if not yet consumed, and its result fires the unconditional hooks (`:944 / :965 / :985 / :1003`) into the POS callback → POS receives an approval for a transaction it did not initiate.
  **Fix:** gateway refuses with `terminal_busy` when `GlobalPara.atmTransactionInProgress` is true; notify hooks carry a "POS-initiated" marker. **With D2 = YES this is the core invariant of the product, not a corner case:** the cashier and the customer screen must be mutually exclusive drivers at every instant, in both directions (see POS-12).

- [x] **POS-04** · MED · ● · `pos/AtmHostServiceGateway.java:197-243` (`startReversal` wrapper) vs `atm/host/AtmTransactionManager.java:1301-1305` (`sendReversal` returns silently when `currentRequest == null || currentResponse == null`) — **in release/v6.2.6 `c7559b5` · on-device verification pending** — **code-verified; reversal-with-nothing-to-reverse not sent from the register**
  **Defect:** the listener wrapper never restores when there is nothing to reverse; nested wrappers (two back-to-back reversals) drop the second.
  **Fails when:** fresh boot / post-drain reversal → `fired` never flips → POS gets no reply (proxy timeout) and the wrapper stays installed → the next `onError` from **any** host op (key download failure `AtmHostService.java:611 / :615`, health check) fires the stale POS callback with an unrelated error for a dead flowId.
  **Fix:** add `hasReversibleTransaction()` and reply immediately when false; give `sendReversal` a per-call callback like `requestHostTotals(reset, callback)` already has.

- [x] **POS-05** · HIGH (raised from MED — the crash path violates the fallback requirement) · ● · `pos/PosConnectionClient.java:161-162, 95-110, 303-307`; root cause `pos/PosRegistrationClient.java:213-216` (`connection_url` used verbatim) — **in release/v6.2.6 `6ccd2de` · on-device verification pending** — **code-verified + unit-tested (PosConnectionClientTest); reconnect task bodies exercised live by POS-01**
  **Defect:** supervisor and reconnect task bodies have no try/catch; `scheduleAtFixedRate` cancels the periodic task forever on any thrown exception.
  **Fails when:** proxy returns a non-URL `connection_url` → `Request.Builder().url(...)` throws `IllegalArgumentException` → from the reconnect task it is swallowed into the Future (state CONNECTING, no task) → supervisor throws → dead → **permanent POS wedge**; from `onRegistered` on the OkHttp thread a non-IOException is rethrown → uncaught → **process crash — takes the walk-up fallback down with it.**
  **Fix:** wrap both task bodies (`catch Throwable → scheduleReconnect()`); validate `connection_url` with `HttpUrl.parse` in the registrar and fall back to the default.

- [x] **POS-06** · HIGH (raised from MED — drops a real approval) · ● · `pos/PosTransactionObserver.java:47` (180 s watchdog), `:85-94` — **in release/v6.2.6 `653df8e` · on-device verification pending** — **code-verified (watchdog constant); not observable in a passing flow**
  **Defect:** the watchdog is shorter than a legitimate worst-case flow (card wait + 60 s PIN + 130–150 s busy-MUX host wait) and does not abort the flow when it fires.
  **Fails when:** watchdog clears the slot and sends `host_unreachable` → the real approval hits `cb == null` and is dropped → **customer debited, POS told "error", no reversal** (the terminal saw an approval). With the slot free but `GlobalPara.atmTransactionInProgress` still true, a retry `sale` passes the gateway and hits POS-03.
  **Fix (6.2.6):** size the watchdog above the full flow (card + PIN + 150 s gate + margin). **Fix (with SDK-01 full):** on fire call `abortTransaction()` so slot and flow release together.

- [x] **CFG-01** · MED (operability) · ✔ · `CasHubParams.applyToConfigDetailed`, `pos/PosParams`, `MainActivity.onPosParamsChanged` — **shipped 6.2.7 `8543d78` + `5a7e7c6`**
  **Gap:** POS mode, proxy URL and terminal access key existed only as on-terminal Admin fields; a POS site could not be provisioned or corrected from CasHUB, and (ADM-05) the flag could be lost by a local save with no central record to restore it.
  **Fix:** three CasHUB parameter keys (`pos_enabled`, `pos_proxy_url`, `pos_terminal_access_key`) applied to `PosConfig` with the host-settings precedence (CasHUB wins for keys it carries; absent keys leave local values). Applied live: the POS stack restarts on the new settings (JWT cleared when URL/key changed), deferred while a register transaction is in flight. Access key kept out of the host-config payload / KMS-II backup and masked in diagnostics. `PosParamsTest` (10). CLI recognises the keys. Values are entered in CasHUB manually by TFI ops, like the host parameters (decided 2026-09-24; no MyAdmin form).

- [x] **REV-01** · **CRITICAL (field outage)** · ● (seen on a shipped 6.2.6 terminal, video 2026-09-24) · `atm/host/AtmHostService.java` gate + drain, `ReversalPersistenceManager.isDrainableStatus`, `MainActivity` `[REVERSAL]` routing, `Fragment_page_admin_atm` — **shipped 6.2.7 (reversal policy)**
  **Defect:** a reversal record the host kept rejecting counted as drainable (FAILED ∈ `isDrainableStatus`), so every customer re-triggered the drain and was refused with "processing pending transactions"; the drain's own progress messages were written to *every* receipt ("REVERSAL IN PROGRESS"); exhaustion put the session in `OUT_OF_SERVICE` with no exit but a restart; the only field remedy was Super-admin "Clear All Pending", which destroys the evidence.
  **Policy (D6, user: "N = 2 and money shouldn't matter"):** (1) block customers only while a drain is *running* or an *active* record is about to be drained; (2) after exhaustion, trade + banner + background retry (15 min / network recovery / boot / Admin), record left FAILED with the host's real answer as last error; (3) safety stop at **2** FAILED records → "Out of service — pending reversals, contact TFI", lifted automatically when the count drops under 2; (4) per-record Retry-now and Super-only Resolve-with-reason (kept in history + journal `resolve`) replace Clear All; (5) receipt REVERSAL block only for the current transaction's own record (`ReversalProgress` `[REVERSAL:<id>]` vs `[DRAIN]`).
  **Code:** `ReversalGatePolicy` (pure, tested), `ReversalProgress` (pure, tested), `CompletedReversal.resolved(...)` + `addResolvedToHistory`, `AtmHostService.blockedByReversalGate / drainPendingReversalsBlocking(includeFailed) / retryReversal / resolveReversal / onNetworkAvailable / publishReversalBacklog`, `AtmEventListener.onReversalBacklog`, `AtmTransactionManager.getLastReversalFailure`, `GlobalPara.atmCurrentReversalId`, `MainActivity.updateReversalBanner`, Admin per-record cards. Also fixes: PROCESSING-after-crash orphan; operator alerts routed through `onError` (failed the current txn / could answer the POS); Admin/boot drain on its own thread racing the executor drain (part of HOST-11).

- [x] **AMT-01** · MED (business rule) · `Fragment_page_amount_selection.showCustomAmountDialog`, `AmountRounding` — **shipped 6.2.8** (user 2026-09-25: "it should round to the minimum… 12.50 goes to 20"; pulled out of 6.2.7, which had already shipped)
  **Gap:** the custom-amount entry had no step rule — an entry under the minimum was refused, anything in range went to the host exactly as typed (cents included).
  **Fix:** the minimum is the step; custom entries round UP to its next multiple (cents arithmetic, `AmountRoundingTest` 6), the customer is told ("Rounded up to $20.00 (withdrawals in $10.00 steps)") and still confirms with Continue; presets untouched; maximum still applies to the rounded amount. Not applied to POS-driven amounts (the register owns those).

- [x] **AMT-02** · LOW (business rule) · `AmountPresets`, `Fragment_page_amount_selection`, `fragment_page_amount_selection.xml` — **shipped 6.2.8** (user 2026-09-25: remove $500, add $10, keep ascending)
  **Change:** presets $10 · $20 · $40 · $60 · $100 · $200 from one list (`AmountPresets.PRESET_CENTS`, `AmountPresetsTest` 5); buttons relabelled/wired from it (`btnPreset0..5`); presets outside min/max greyed (`isOffered`), re-applied on every show so a CasHUB limit change applies live. A $10 button under the code-default $20 minimum would otherwise only ever produce "Amount too low".

- [x] **BI-01** · LOW · ● (log 2026-09-25, pre-existing, shipped in 6.2.7) — **fixed 6.2.8**: `MainActivity` sets `strAmount = "0"` whenever `atmBalanceInquiryMode`; `startBalanceInquiry` writes `"0"` (not `"0.00"`, which `amtPadding` cannot encode); the transaction page's parse-failure fallback is `"0"` instead of `"1000"`. · `MainActivity` ~3037 (`if atmSelectedAmount == "0.00" → strAmount = edtamount.getText()`), `Fragment_page_transaction.java:154` (`edtAmount.setText("1000")`)
  **Observation:** for a balance inquiry the chip amount (9F02 / `strAmount`) is read from the legacy transaction-page EditText, so it is `0` or `1000` depending on that hidden field's state (yesterday's BI logged `strAmount=0`, today's `strAmount=1000`); the host request itself carries Amount=$0 and EFX approved both. **Fix (later):** BI sets `strAmount = "0"` deterministically (or a documented nominal) and never reads the legacy field in ATM mode.

- [x] **CENTS-01** · MED (money) · ✔ · `Money.toCents` — **fixed 6.2.8.** Six dollars→cents casts (`(int)(x*100)` / `(long)(x*100)`) truncated: `(int)(2.95*100)` = 294, so a $2.95 fee went to the host as $2.94 while the receipt said $2.95; same for $1.15, $4.35, $10.30 (`10 + 0.3`). All go through `Money.toCents` (rounds); the amount screen computes cents first and derives every receipt string from the same integers. `MoneyTest` (5).

- [x] **FEE-01** · HIGH (money, percentage-fee sites and POS) · ✔ · `MainActivity` ~4534/4673 — **fixed 6.2.8.** The surcharge on the wire was ALWAYS `atmFlatFeeAmount`, regardless of `atmUseFlatFee` (percentage mode showed one fee on the receipt and charged another) and regardless of a POS-supplied surcharge (the register's surcharge was displayed but the terminal's flat fee was sent). Now `surchargeCentsFromReceipt()`: the fee already shown to the customer (`GlobalPara.atmFee`, written by the amount screen or the POS gateway), fee configuration as fallback, 0 for a balance inquiry (BI uses its own send path without a surcharge anyway).

- [x] **FEE-02 / EMV-01** · MED · ✔ · `pos/PosSaleFee`, `AtmHostServiceGateway`, `PosTransactionExecutor.TxnBridge`, `PosWire.RSP_TOTAL_CENTS` — **fixed 6.2.8 (decision D7, user 2026-09-25: "it shouldn't have to push the fee. this is controlled at the terminal").** The POS gateway applied whatever surcharge the register sent (and, per FEE-01, then put the flat fee on the wire anyway), and set the chip amount to the amount WITHOUT fee while walk-up used amount+fee. Now: the terminal's fee configuration sets the surcharge for POS sales exactly as for walk-up; the register's `surcharge` is advisory (warning logged if it differs); chip amount = amount + fee on both paths; the approved reply carries the applied `surcharge` and new `total_cents`. Spec updated. `PosSaleFeeTest` (3).

- [ ] **REV-02** · MED · target **6.2.8** — MyView: terminal posts the reversal backlog (active/failed/out-of-service, last error) so a pending reversal raises an alert in MyView, and a remote **Resolve** (with reason, audited) exists for the case where nobody is on site. Until then the banner + Admin screen are the only signals.

---

## R1-SWITCH — POS/SYS release switch (own PR, target **v6.2.7**, before go-live)

**D5 decided 2026-09-18.** Manual release instead of automatic fallback: a bottom-bar
**POS/SYS** button, gated by the admin PIN prompt (Normal `123456` or Super `8675309`,
same tier check and lockout as Admin), switches the terminal between POS-locked and
walk-up (SYS) mode. Chosen over auto-fallback because a human decides "POS is down"
(no flapping on a 30 s blip), it fits the tier model, and it lives entirely in the
UI/gateway layer — it never touches the host code the 6.2.6 fixes stabilize. Ships as
its own PR so 6.2.6 stays fixes-only and independently revertible; both land before
go-live.

- [ ] **POS-12** · HIGH (requirement) · ✔ · new work: bottom bar in `res/layout/activity_main.xml` (beside `txvServiceBanner`); POS-locked idle state in `Fragment_page_main_menu`; `released` flag in `pos/PosConfig`; refusals in `pos/AtmHostServiceGateway` / `pos/PosTransactionExecutor`; `info` state in `pos/PosOrchestrator`; shared PIN helper extracted from `Fragment_page_admin_atm.verifyPinTier` + lockout
  **Gap (as found):** with POS mode on, both drivers coexist with no arbitration and no visibility — `PosOrchestrator.currentState` (`:45-97`) is never surfaced; the customer fragments have zero POS references; `MainActivity.getPosState()` (`:1060`) is unused by the customer UI. A customer can self-serve at a POS site while the register is connected (till doesn't reconcile), and staff get no indication when the proxy drops.
  **Design rules (decided):**
  1. The switch is **disabled while any transaction is in progress**, in either direction — no release mid-POS-sale, no re-lock mid-walk-up. This single rule removes the transition races.
  2. **POS-locked idle screen** replaces the amount menu while locked ("POS mode — waiting for register"). The bottom bar shows live proxy state — *POS: connected* / *POS: offline* — plus the POS/SYS button; the state label is what tells staff to reach for it.
  3. **In SYS mode the proxy stays connected**; `info` reports `state: "released"` so the register knows why. `sale` and `balance_inquiry` are refused with `pos_released`. Switching back is instant (no re-register).
  4. **`reversal` is refused in SYS mode** — `sendReversal` targets the last host transaction, which in SYS mode may be a customer's walk-up; a POS-commanded reversal would undo a stranger's withdrawal.
  5. **`settlement` (host totals / close batch) stays allowed** in SYS mode — it touches no customer flow and the register may need to close its batch during outage recovery. *(Decided.)*
  6. **Released state persists across reboot** (`pos_config`) so a reboot during a POS outage doesn't silently re-lock; the bar keeps showing "POS: offline — released" until staff switch back. *(Decided.)*
  7. **No auto-return on reconnect.** When the proxy comes back while released, the bar shows "POS: connected — tap POS/SYS to return"; staff switch back deliberately.
  8. The PIN prompt reuses the Admin tier check and lockout via a shared helper; **fix ADM-02 in the same PR** so a mistyped release can't lock the terminal for 5 minutes at the worst moment.
  **Keeps:** POS-03's gateway guard (6.2.6) stays as belt-and-braces for the transition edge.
  **On-device test:** release/re-lock round-trip · refusal codes as seen by the register · switch disabled mid-transaction both ways · reboot while released · proxy reconnect while released.

---

## R2 — Host-layer robustness (own PR, target **v6.2.8**, after go-live)

These are the structural causes behind the same "bad spot after a network blip"
symptom. They need design and regression time and should not be squeezed into a
go-live release.

- [ ] **HOST-05** · HIGH · ● · `atm/host/AtmTransactionManager.java:706-762` (`requestConfiguration`, via RC-76 `downloadKeys()` at `:1412 / :1445`), `:927-949` (`scheduleKeyRenewal`); `MainActivity.java:936-945` (`onError` unconditionally counts down `atmTransactionLatch`)
  **Defect:** background key work runs on the *transaction* executor and reports through the *transaction* listener. `requestConfiguration` also bypasses `downloadKeysSync()`'s static lock / 30 s coalesce (the comment at `:799-806` claims RC-76 lands there; it does not).
  **Fails when:** decline RC-76 → Type 88 queued → customer retries within ~2 min → new latch → the 85 queues behind the 88 → if the 88 fails, `notifyError("Configuration download failed")` releases the customer's latch with an error; if it succeeds it has eaten most of the 130 s budget. Either way the 85 then runs and may be approved → `onTransactionApproved` hits a spent latch → `clearPreSendReversal()` removes the only reversal record → **funds debited, no voucher, no reversal.** Concurrent second 88 → double key rotation (the MUX-key poison).
  **Fix:** give key work its own executor and callback; route RC-76 through `downloadKeysSync()`; never count down a transaction latch from a non-transaction op.

- [ ] **HOST-06** · HIGH · ● · `atm/host/ProcessorConfig.java:78` (connect 30 s), `:161` (EFX response 120 s); `atm/host/AtmHostConnection.java:126 / :177` (SO_TIMEOUT is per-read; `readVisaNoStxEtxMessage` `:568 / :590` restarts it per read); `MainActivity.java:5347` (`await(130 s)`); `atm/host/AtmHostService.java:650-664` (`openSessionWithRetry`)
  **Defect:** budget does not nest: 30 s connect + 120 s response (+ unbounded handshake, HOST-03, + per-read resets) > 130 s latch. The latch also starts before the runnable is dequeued, so anything ahead on the FIFO executor (a health tick, HOST-05's 88) consumes budget.
  **Fails when:** lossy network, connect takes 12 s, MUX answers at 118 s → MainActivity gives up at 130 s → approval lands afterwards → pre-send reversal already cleared → orphaned approval (same outcome as HOST-05). Separately, `openSessionWithRetry` on `threadTxn` before the latch starts can block ≈ 3×(30+120) s + 60 s ≈ **8.5 min with the card in the reader** in MKSK mode when the key is missing.
  **Fix:** size the latch to connect + handshake + response + EOT with margin (and keep it > response so the code's own ordering holds); make SO_TIMEOUT a total-response deadline; bound `openSessionWithRetry` and run it inside the latch.

- [ ] **HOST-07** · HIGH · ● · `atm/host/AtmHostConnection.java:481-517` (`sendAndReceive`, unsynchronized), `:76-80` (`connect()` returns "Already connected" to a second caller who then shares the socket + `InputStream`); callers on ≥ 4 threads: transaction executor, `KeyDownload` (`AtmHostService.java:269 / :413 / :602`), `ReversalDrain` (`:923`), raw thread in `processPendingReversals` (`:1327`), `threadTxn` via `openSession()` (`MainActivity.java:5336`)
  **Defect:** one `AtmHostConnection` (one socket) is driven from several threads with no I/O mutex.
  **Fails when:** `processPendingReversalsOnStartup()` (`MainActivity.java:1032`) and `performStartupKeyRenewal()` (`:1035`) run back-to-back at every (re)init. After an outage with a pending record and an expired key, an 86 and an 88 go out on the same socket and both threads read the same stream → crossed responses → key download retries (each rotates the host key) and the reversal is marked FAILED.
  **Fix:** a single-flight host-operation lock (all host ops serialize), or one connection per operation. Run startup reversal drain and key renewal sequentially, not concurrently.

- [ ] **HOST-08** · MED · ● · `atm/host/AtmHostService.java:1405-1476` (`sendReversalSync`)
  **Defect:** swaps `this.listener` to a no-op stub for the whole reversal send (up to 3×120 s, ×5 via drain) and races `setEventListener()` (`MainActivity.java:5325`, admin fragment, POS gateway) so `finally` can restore a stale listener.
  **Fails when:** a customer arrives during a drain → `performWithdrawal`'s gate `notifyError("Please wait — processing pending transactions")` (`:763-767`) is dropped → latch never counted down → customer hangs 130 s to "Transaction timeout" instead of an immediate "Please wait".
  **Fix:** delete the swap — `sendReversalDirect` is already synchronous and needs no listener.

- [ ] **HOST-09** · MED · ● · `atm/host/AtmHostService.java:1074-1116` (drain, 5 attempts) × `atm/host/AtmTransactionManager.java:1342-1365` (`sendReversalWithRetry`, `maxReversalRetries = 3`, `ProcessorConfig.java:85`)
  **Defect:** nested retry ladders → 15 wire attempts, each up to 150 s, no disconnect between inner attempts (HOST-04). Javadoc at `:937-938` describes 5.
  **Fails when:** worst case ≈ 30+ min with `processingReversals = true` (customers blocked or hanging per HOST-08, health checks skipped), then OUT_OF_SERVICE.
  **Fix:** one retry ladder (drain owns backoff; inner call is single-shot with a fresh connection).

- [ ] **HOST-10** · MED · ✔ · `atm/host/AtmHostConnection.java:154-174`
  **Defect:** raw `SSLSocket` from `SSLSocketFactory.getDefault()` validates the chain but performs **no endpoint identification** (only `HttpsURLConnection` does that by default). Any certificate from a system-trusted CA for any name passes.
  **Fails when:** MITM position on PIN blocks / Track 2 with any trusted-CA cert.
  **Fix:** `SSLParameters p = sslSocket.getSSLParameters(); p.setEndpointIdentificationAlgorithm("HTTPS"); sslSocket.setSSLParameters(p);` before `startHandshake()`. **Prerequisite — decision D3:** confirm each processor's certificate carries the configured host/IP in SAN first, or this breaks production connectivity on merge.

- [~] **HOST-11** · MED · ● · `atm/host/AtmHostService.java` (legacy `processPendingReversals`; live via `MainActivity` on every re-init and the admin button) — **partly closed by REV-01 (6.2.7):** the Admin/boot path now runs on the single drain executor with the same backoff and bookkeeping; PRESEND records are no longer sent by it. Remaining: review the re-init trigger frequency.
  **Defect:** uses unfiltered `getPendingReversals()` (`:1312`) — sends Type 86 for `STATUS_PENDING_PRESEND` records, which the drain correctly excludes as in-flight. `processingReversals` check-then-set (`:1304 / :1321` vs `:978 / :986`) is not atomic across the two paths.
  **Fails when:** `initAtmHost` re-runs (settings re-apply, `MainActivity.java:836-840`) while a transaction's executor task is still running → the live transaction's pre-send record is reversed on the host and removed while the customer receives a voucher.
  **Fix:** filter with `isDrainableStatus()` (plus PRESEND older than N minutes for crash recovery); `AtomicBoolean` CAS for `processingReversals`.

- [ ] **HOST-12** · MED · ● · `atm/host/AtmTransactionManager.java:308-319`; TOCTOU at `atm/host/AtmHostService.java:787`
  **Defect:** CAS sets `transactionInProgress`, then `executor.execute()`; if the executor was shut down (`:1529-1532` via `AtmHostService.shutdown()`) `RejectedExecutionException` propagates after the flag is set with no `finally`. `transactionManager` can be nulled between `ensureInitialized()` and the call.
  **Fails when:** settings re-apply during a transaction → flag stuck true → "Transaction already in progress" until restart; latch never released.
  **Fix:** try/catch around `execute` → reset flag + `notifyError`; snapshot `transactionManager` before use.

- [ ] **HOST-13** · MED · ● · `atm/host/AtmHostConnection.java:529-541`
  **Defect:** treats first byte 0x02/03/04/05/06/15 as a bare control character before consulting framing; for `VISA_*` the first byte is the length high byte.
  **Fails when:** a response of 512–1791 or 5376–5631 bytes → truncated to one control byte → remainder left in stream → parse error → HOST_ERROR reversal path. Unlikely for 85s, plausible for a full Type 88.
  **Fix:** accept a bare control byte only where one is expected (the EOT read in `completeHandshake`).

- [ ] **SDK-02** · MED · ● · `atm/host/CastleKeyManager.java:264-331, 636-697`; called from `MainActivity.java:370` (onCreate, UI thread) and a bare `new Thread` at `Fragment_page_admin_atm.java:1125-1127`
  **Defect:** ~20 blocking CTOS KMS2 calls (`logKeySlotKcvs`, 4+ `checkKeyExists`, `testMkskKeyAtC001` encrypt+decrypt, `runKeyIdentificationTest` 6 encrypts) on the UI thread at boot while the EMV init thread is active, and from an unsynchronized thread on admin Save while a transaction may be live. Violates single-threaded SDK use; ANR risk. The identification/test routines are debug code.
  **Fix:** move `initialize()` to the transaction thread; delete `runKeyIdentificationTest` / `testMkskKeyAtC001` from the init path.

- [ ] **SDK-03** · MED · ✔ · `MainActivity.java:2295-2300` (joins), `:2311` (`msr.flushTracksBuffer()`); `Fragment_page_transaction.java:161-165, 744-748 → :848` (called from `onClick`)
  **Defect:** `abortTransaction()` blocks the UI thread up to 4 s on every Cancel, and if the join times out `flushTracksBuffer()` runs on the main thread while `threadTxn` is still inside the SDK — concurrent CTOS use. (`emvcl.cancelTransaction()` is legitimately cross-thread; the MSR flush is not.)
  **Fix:** move join/flush off the UI thread, or flush at the top of the next transaction thread only. Pairs with SDK-01's full fix.

- [ ] **KEY-01** · MED · ● · `atm/host/AtmHostService.java:1593-1596` (`shutdown()` → `clearWorkingKey()`); `atm/host/CastleKeyManager.java:1413-1421, 1784-1796`; trigger signature `MainActivity.java:196-200`
  **Defect:** `shutdown()` wipes the process-wide key state **and** the persisted TIMESTAMP + KCV; the rebuilt manager fails `restoreHardwareKeyStateFromPrefs()` (`:1691`) → `performStartupKeyRenewal` sees no key → redundant Type 88 → host rotates. The signature flips on TLS/port/protocol/TID edits for the *same* host, where no rotation is warranted. Defeats the design intent at `:88-97 / :140-143`.
  **Fix:** don't clear key state in `shutdown()`; clear only when the host address actually changes; exclude non-key-affecting fields from `currentHostConfigSignature()`.

- [ ] **KEY-02** · MED · ● · `atm/host/CastleKeyManager.java:1413-1421` (does not reset `mkskSessionKeyLoaded` / `mkskEncryptedSessionKey` / `mkskKeySet`), `:1784-1796` (does not remove `PREF_KEY_MKSK_BLOB`), `:1996-1999`, `:818-820`; caller `callback/MyEMVSPEvent.java:653`
  **Defect:** `clearWorkingKey()` leaves MKSK session state armed; `encryptPinWithMkskAtC001` bypasses the `workingKeyLoaded` gate.
  **Fails when:** `requestNewWorkingKey()` (`AtmHostService.java:595-599`) clears the key, then the download fails on network → `workingKeyLoaded = false` but `mkskSessionKeyLoaded = true` with the **old** blob → `isMkskReady()` true → PIN encrypted under a session key the host already rotated (the host rotates on the 88 it received even if the terminal timed out) → RC 76 decline.
  **Fix:** reset all `mksk*` statics in `clearWorkingKey()`; remove the blob in `clearPersistedKey()`; gate `encryptPinWithMkskAtC001` on `workingKeyLoaded`.

- [ ] **KEY-03** · MED · ● · `atm/host/CastleKeyManager.java:297-299, 315-317, 1300-1305`
  **Defect:** `initialize()` marks `workingKeyLoaded = true` for any key present at C000/0000 and then skips the MKSK blob restore.
  **Fails when:** a terminal migrated from DUKPT without factory reset → leftover DUKPT key at the slot → `encryptPinBlock()` tries `encryptPinBlockWithFixedKey` on it → fails → first customer PIN after every process start fails while `hasValidWorkingKey()` said ready.
  **Fix:** in the MKSK build ignore `pinKeyExists` (or require `mkskSessionKeyLoaded` before trusting it).

- [ ] **ADM-04** · MED · ● · `Fragment_page_admin_atm.java:738-800` (dialog, `setCancelable(false)`, untracked), `:213-236` (`onDestroyView` never dismisses it), `:942-943` (`rootView.findViewById` no null guard)
  **Defect:** the PIN dialog outlives the fragment view.
  **Fails when:** any external `navigateToPage` while the dialog is up — POS `UiBridge.navigateToTransactionPage` (`MainActivity.java:1152-1154`), receipt auto-timeout — detaches the admin fragment (`rootView = null`, `getContext() == null`) with the dialog still on screen. Correct PIN → `setContentVisible(true)` NPE; wrong PIN → `Toast.makeText(null, …)` NPE. App dies on the main thread.
  **Fix:** keep a `pinDialog` field, dismiss in `onDestroyView` (the transaction fragment already does this at `:639-643`); null-guard `rootView` / `getContext()`.

- [ ] **LIFE-01** · MED · ● · `MainActivity.java:306-325` + `CasHubParams.java:63-65` (receiver registered on the Activity, never unregistered); `MainActivity.java:381-392` (`onDestroy` stops POS + status bar but not `atmHostService`, `threadTxn`, or static `GlobalPara.mainActivity` set at `:285`)
  **Defect:** the CasHUB PARAMETER_UPDATED receiver is bound to `MainActivity.this` (not the `appCtx` computed at `:39`) from a raw 4 s-sleep thread; `AtmHostService.shutdown()` (`AtmHostService.java:1585`) is never called on destroy.
  **Fails when:** any activity destroy → framework force-unregisters and logs "Activity has leaked IntentReceiver" → **live CasHUB config pushes silently stop working** for the rest of the process. Recreation builds a second `AtmHostService` while the old executor lives, and double-registers the receiver.
  **Fix:** `ctx.getApplicationContext().registerReceiver(...)` in `CasHubParams`; add `atmHostService.shutdown()` + `GlobalPara.mainActivity = null` in `onDestroy`.

---

## R4 — Hygiene / dead code / low

- [ ] **HOST-L1** · LOW · ● · `atm/host/AtmHostService.java:1102-1108` + `atm/host/ReversalPersistenceManager.java:230-235` — `attemptReversalWithBackoff` leaves a record in `STATUS_PROCESSING` on `InterruptedException` (or crash mid-attempt); PROCESSING is not drainable → reversal invisible until the next restart's legacy path.
- [ ] **HOST-L2** · LOW · ● · `atm/host/AtmHostConnection.java:45-46`; `atm/host/AtmHostService.java:1585-1599` — `socketCloseExecutor` never shut down (one idle non-daemon thread per `AtmTransactionManager`, i.e. per settings re-apply); `shutdown()` does not call `stopPeriodicHealthCheck()` or shut `reversalDrainExecutor`.
- [ ] **HOST-L3** · LOW · ● · `atm/host/AtmTransactionManager.java:139-146, :446` — `currentRequest` / `currentResponse` never reset; `promoteOrCreatePendingReversal` (`:199-221`) reads the *previous* transaction's response to pick PENDING vs RECONNECT_AND_REVERSE and copies its authData. Not on the wire today (F3 uses `retrievalReference`) but the record's classification is wrong. Null both in `resetReversalState()`.
- [ ] **HOST-L4** · LOW (security hygiene) · ● · `atm/host/AtmHostService.java:479-558` — dead `requestPanLookup` (no callers) would POST masked PAN + encrypted Track 2 over plaintext `http://` to the processor host/port and never closes the connection. **Delete.**
- [ ] **HOST-L5** · LOW · ● · `atm/host/AtmTransactionManager.java:1079-1112` (dead `startHeartbeat`; if enabled drives the socket from its own thread — HOST-07), `atm/host/AtmHostConnection.java:803-811` (dead null-unsafe `sendNak`), `atm/host/AtmHostService.java:1195-1200` (operator health interval read but never applied); `AtmHostService` fields `initialized / config / keyManager / transactionManager / listener` non-volatile but read from 4+ threads (code already snapshots one at `:421` because of an observed NPE).
- [ ] **KEY-L1** · LOW · ● · `atm/host/CastleKeyManager.java:1033-1037, 1165-1166, 1239-1243` — CKBB / `findAndUseAvailableKey` / TR-31 paths set `workingKeyLoaded` without `saveKeyToPrefs()` → `isKeyMissingOrExpired()` immediately reports expired → repeat download (deprecated paths).
- [ ] **LIFE-02** · LOW · ● · `Fragment_page_transaction.java:647` — `view.removeCallbacks(null)` is a no-op; the 500 ms auto-click posts (`:616-623, :671-679`) can still fire after `onDestroyView`. Harmless today (guarded by `atmTransactionInProgress`) but not doing what the comment claims.
- [ ] **LIFE-03** · LOW (informational) · ● · `MainActivity.java:487-490` — a `NetworkCallback` already dispatched before `unregisterNetworkCallback` can still `runOnUiThread(updateSignalIcon)` after `onDestroy`. Harmless: try/caught and `findViewById` null-guarded. No action.
- [ ] **POS-07** · LOW · ● · `pos/PosTransactionExecutor.java:117-118, 144-151` — settlement (Type 87) refused with `key_not_loaded`; host totals need no PIN key (blocks settlement during boot-time renewal backoff).
- [ ] **POS-08** · LOW · ● · `pos/AtmHostServiceGateway.java:176-178` — every observer `onError` (PIN cancel, card fail, readiness gate) reported as `host_unreachable`; `PosWire` defines `card_read_failed` / `pin_entry_failed` / `timeout`.
- [ ] **POS-09** · LOW · ● · `pos/PosConnectionClient.java:362-368` — only `PosEnvelopeException` caught; a RuntimeException from `dispatcher.dispatch` tears the socket down via OkHttp `failWebSocket` (full reconnect for one bad frame). Catch Throwable and log.
- [ ] **POS-10** · LOW · ● · `pos/PosConnectionClient.java:71, 289, 295, 355` — `reconnectAttempt` plain int written from OkHttp and scheduler threads (benign: clamped index).
- [ ] **POS-11** · LOW · ● · `pos/PosOrchestrator.java:138-148` — `stop()` does not `PosTransactionObserver.clear()`; late result silently dropped. Be explicit.
- [ ] **LOG-01** · MED (diagnosability) · ✔ · app-wide — at current verbosity the app fills the device's default 256 KiB main logcat buffer in **~15 minutes** (the 15:15 capture's main buffer began at 15:00:41; the 14:32 boot and an entire admin session were already evicted, which cost an hour of root-causing ADM-05). Field diagnosis of anything that happened more than a few minutes ago is impossible. Biggest offenders: per-poll card-detection chatter, `TagData(...)` dumps per tag, `AGM/metadata` (system, not ours), `ui_ShowMsg` mirrors. Fix: demote per-poll / per-tag lines to `Log.v` behind a `BuildConfig.DEBUG` or a runtime `verboseLogging` flag; keep the transaction spine (connect / TX / RX / response / reversal / disconnect) at `Log.d`. **Pass procedure now:** `adb logcat -G 16M` before a device pass.
- [ ] **HOST-14** · LOW · ✔ · `atm/host/ProcessorConfig.java:forEfx` (`healthCheckEnabled=true` → Type 89) — observed on the 6.2.6 device pass: every 6-minute Type 89 tick against EFX ends in `Health check failed: Connection closed by host` (EFX does not answer Type 89; it closes). Harmless since HOST-04 (the socket is dropped, nothing acts on the result — `MainActivity.onHealthCheckResult` only logs), but it is a wasted TLS connection every 6 minutes and permanently misleading telemetry. Fix: use the H0 status-monitoring probe for EFX (`sendStatusMonitoring`, already described as the EFX-preferred method) or disable the Type 89 tick in `forEfx`. **Before HOST-04 this tick was the mechanism behind the intermittent "first transaction after idle fails" symptom** — it left a half-closed socket that `isConnected()` reported live.
- [ ] **TEST-01** · LOW · ✔ · three unit tests fail at 6.2.5 HEAD, before any 6.2.6 change (verified by running them against the stashed tree on 2026-09-18). `HyosungProtocolTest.testProcessorConfigDns` asserts `isHealthCheckEnabled()` is false but `ProcessorConfig.forDns` sets it true (`ProcessorConfig.java:105`) — the test is stale. `EmvTagEnhancerTest.testTerminalCapsOverriddenInAtmMode` / `testCvmResultsOverriddenInAtmMode` expect 9F33 → `E040C8` and 9F34 → `420000` in ATM mode; the enhancer no longer produces those overrides — decide whether the tests or the enhancer are stale (the constants still exist at `EmvTagEnhancer.java:38-39`) before touching either. Not fixed in 6.2.6 on purpose: a release PR should not change EMV tag behaviour to make a test pass. Everything else in the suite is green (172 tests).

---

## Decisions pending (block specific items)

- [ ] **D1 — Hardcoded admin PINs** (`SUPER_ADMIN_PIN = "8675309"`, `NORMAL_ADMIN_PIN = "123456"` in `Fragment_page_admin_atm.java`). Chosen deliberately ("only we can change the PIN"). Options: keep as-is; harden the values; deliver PINs via CasHUB parameter push (rotatable without a build). Flagged CRITICAL by the security review; acceptance is a business call.
- [x] **D7 — Who owns the surcharge on a POS sale?** **Decided 2026-09-25: the terminal.** The register sends the cash amount only; the terminal's fee configuration (CasHUB) sets the fee for POS and walk-up alike; the reply reports the applied surcharge and total. See FEE-02.
- [x] **D2 — Is POS mode enabled on the go-live units?** **Decided 2026-09-18: YES.** POS mode is integral — go-live units are cashier-driven POS installs (the POS system drives transactions through the MyView proxy), with the walk-up flow as the fallback when the proxy is unavailable. POS-01..06 moved into the go-live gate (section R1-POS); POS-05 and POS-06 raised to HIGH.
- [x] **D5 — Walk-up policy while the proxy is connected.** **Decided 2026-09-18: manual POS/SYS release switch**, not automatic fallback — a bottom-bar button gated by the admin PIN prompt (Normal `123456` or Super `8675309`). Sub-decisions: released state **persists across reboot**; **settlement stays allowed** while released; ships as **its own PR 6.2.7, before go-live**, so 6.2.6 stays fixes-only. Full design in R1-SWITCH / POS-12.
- [ ] **D3 — Processor certificate SANs.** Confirm each processor's TLS cert carries the configured host/IP before enabling hostname verification (HOST-10). Enabling it blind will break production connectivity.
- [ ] **D4 — Mirror this backlog to GitHub Issues?** (`toddb-amt/Castle-Triton`). Not done; awaiting go-ahead.

---

## What the review found solid (don't "fix" these)

- Pre-send reversal persistence with promote/clear semantics (`AtmTransactionManager.java:403-426, 160-230`) and balance-inquiry suppression — a crash between socket write and response handler cannot orphan an authorization. The R1/R2 items are leaks *around* this, not flaws *in* it.
- `downloadKeysSync()` (`:811-834`): process-wide static lock, 30 s coalesce, hardware key re-sync before deciding a Type 88 is needed; `initializeAtmHostService()` idempotent by config signature under `atmHostLock` (`MainActivity.java:820-833`). This is the right structure for the "redundant Type 88 = poison" constraint.
- Transaction-thread hardening: outer `catch (Throwable)` plus `UncaughtExceptionHandler` (`MainActivity.java:4685-4713`) both clear `atmTransactionInProgress`; the detection loop checks abort between every SDK poll.
- Status bar (`MainActivity.java:460-631`): Android system APIs only, never CTOS; main-looper handler; callbacks marshalled to UI; symmetric teardown; runs once per Activity instance. No leaks.
- Admin tier gating disables the whole view *tree* (not just alpha), and the Super-only re-application after the section pass is correctly ordered; `verifyPin` accepting either tier is only reachable from the Super-only Kiosk run.
- `PosTransactionObserver`: CAS-based exactly-once delivery; watchdog fires only if the *same* callback is still armed; `PosRegistrationClient` `done` AtomicBoolean gives exactly-once across all four OkHttp callbacks; JWT expiry self-heals.
- Connection hygiene basics: stream locals captured before I/O, off-thread socket close via one executor, 8192-byte bounds on every read loop, `logMessage` never logs payloads.
