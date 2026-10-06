# Cashless ATM — What changed since 6.2.1 (insights for the Axium team)

**Purpose:** visibility into the behavior changes made on the **Castle S1F4 PRO**
codebase since 6.2.1, so the Axium team knows what problems were found and how
they were resolved. This is a knowledge briefing, **not** a change request against
the Axium codebase — how (or whether) any of it applies there is entirely the
Axium team's call.

**Source:** Castle branch `mksk-cfff-key`, as of 2026-09-03.
**Span:** 6.2.1 → 6.2.4 plus two fixes committed after 6.2.4.
File references point at the **Castle** code, purely so you can see where the
behavior lives.

Severity shown as **P1** (correctness/robustness, high impact) / **P2** / **P3**.

---

## At a glance

| Area | What was wrong before | What changed | Sev |
|------|-----------------------|--------------|-----|
| Key state | PIN encryption failed with a valid key on device; ~52s key-rotation loop on slow hosts | Key state made process-wide; session key persisted; redundant key requests eliminated | P1 |
| Key download | Duplicate/parallel key requests under load | Single choke point with static lock + 30s coalesce | P1 |
| Timeouts | In-flight auths abandoned on slow hosts | Response 120s / txn latch 130s / ready gate 150s | P1 |
| Key expiry | Just-loaded key read as "expired" → re-request loop | Persist key timestamp + KCV + session blob | P1 |
| Reboot | Nightly reboot → "Terminal Not Started" (key fetch beat WiFi) | Exponential-backoff self-heal + gate kick | P1 |
| POS readiness | Every POS sale/BI rejected "not connected" while manual worked | Readiness no longer requires a live idle socket | P1 |
| POS cancel | Caller hung 90s on a cancelled txn; slot stayed wedged | Cancel sends immediate decline; slot freed | P1 |
| POS wedge | One stuck txn needed an app restart | 180s slot watchdog | P1 |
| POS reconnect | Silent forever after a proxy restart | Any token rejection re-registers; stall supervisor | P1 |
| POS identity | Registered under processor TID | Registers under device hardware serial | P2 |
| Tap receipt | Wrong PAN last-4 on contactless receipts | Contactless track2 decoded correctly; PAN sourced from Tag 5A, stored masked | P1 |

---

## 6.2.2 — Key-state architecture (the big one)

Made MKSK solid on a **high-latency host** — our EFX path runs through a
deliberately busy MUX (~50–80s round trips). A 7-bug campaign; the insights:

- **Key state was per-instance, but the app churns manager instances** — so the
  component that *downloaded* the key wasn't the one that *encrypted the PIN*, and
  PIN encryption failed even though a valid key was on the device. Resolution:
  key state is now **process-wide**.
- **The encrypted session key is persisted** (ciphertext under the master key —
  safe at rest). After a restart the terminal is transaction-ready with **zero
  wire traffic**; no key re-download.
- **Redundant key requests are actively harmful.** On our host, every key request
  makes the host **rotate the working key**, so a "just re-download to be safe"
  turned into a self-inflicted ~52s rotation loop that looked (to the MUX team)
  like key rejection but wasn't. Insight: treat a valid unexpired key as
  authoritative and do **not** re-request it.
- **Init is idempotent** — same config keeps the running service; startup renewal
  skips when a valid key exists.
- **Download is coalesced at one choke point** (static lock + 30s window). Plain
  `synchronized` *queues* duplicate requests rather than suppressing them — the
  fix is a coalesce check, not just a lock. Early-return "already in progress"
  guards placed upstream caused a stuck-status bug — avoid those.
- **Key metadata must be persisted with the key.** A subtle one: the hardware-key
  save path skipped writing the expiry timestamp, so a just-loaded key computed as
  "expired" and triggered an endless re-request loop. Persisting timestamp + KCV +
  session blob fixed it.

**Busy-host timeout values that worked for us:** response **120s**, transaction
latch **130s** (must outlast the socket layer or in-flight auths get abandoned
"after the fact"), readiness gate **150s**. Ordering matters: latch > socket.

*(CasHUB central config via `CasHubParams`/`KmsConfigStore` shipped in 6.2.1 —
already in your build; mentioned only to mark the boundary.)*

---

## 6.2.3 — Self-healing key acquisition after reboot

Terminals reboot nightly. On a normal midnight reboot the app auto-started
**before WiFi associated**, so the key download failed `ENETUNREACH` a few times
in ~3s and then nothing retried → "Terminal Not Started, try again later" until
someone intervened.

Insight: a boot-time key fetch needs an **unbounded retry with backoff**, not a
few fast attempts. Resolution: exponential backoff (15s → 5min cap), plus the
transaction screen actively kicks a download if the user arrives without a key.

---

## 6.2.4 — POS integration fixes (from first live proxy testing)

All found and fixed live against the proxy:

- **Readiness gate tested the wrong thing.** It required a live processor socket,
  but with keep-alive off the socket is opened **per transaction** — so at idle
  there's never one, and *every* POS sale/BI was rejected as "not connected" while
  manual transactions worked fine. Insight: readiness ≠ a live socket in a
  per-transaction-connect model.
- **Cancel sent no response.** Cancelling a POS-driven transaction notified the
  caller of nothing, so the proxy hung until its 90s timeout. Now cancel returns an
  immediate decline (`user_cancelled`).
- **Cancel wedged the terminal.** The single POS transaction slot stayed armed
  after a cancel, so every later command returned `terminal_busy` until an app
  restart. Now the slot is freed on cancel, and a **180s watchdog** guarantees no
  cause can wedge it permanently.
- **Registration identity.** The terminal registered under the processor TID; it
  now registers under the **device hardware serial** (a stable identity separate
  from the processor account). Note for coordination: the proxy binds the identity
  on first registration and rejects a mismatch, so changing it requires a binding
  reset on the proxy side.

---

## Post-6.2.4 (committed, not yet in a release build)

- **POS reconnect could go silent forever.** After a routine proxy restart the
  client presented its expired token, was rejected, and then made **no further
  attempt of any kind** for 40+ minutes — a power-cycle was the only recovery.
  Two insights: (a) with short-lived tokens (15 min), an expired token at
  reconnect is the *normal* case and **any** rejection should fall back to
  re-registration — not just an HTTP 401/403; (b) a client needs a **stall
  supervisor** — every non-connected state carries a deadline, and a periodic
  check force-recovers if a deadline passes, so no wedge (including an upgrade
  handshake that never answers under an unbounded read timeout) can silence it.

- **Contactless receipts showed the wrong PAN.** Tap receipts printed a wrong
  last-4 (e.g. `1383` for a card ending `8318`). Root-cause insight: **the Track 2
  buffer arrives in different encodings by entry mode** — on Castle, contactless
  track2 is **ASCII** (`;PAN=…?`) while contact/DF35 is **BCD-packed**. The code
  parsed the ASCII buffer with BCD logic (cut at the first `D`, which in ASCII is
  the `D` inside the `=` byte `0x3D`) → a mid-byte cut and a garbage last-4. The
  PIN block was always correct because it uses **Tag 5A** (the authoritative clear
  PAN), which is why only the receipt was affected. Resolution: decode track2 with
  encoding auto-detection, and source the display PAN from Tag 5A. Also worth
  knowing: the display PAN field is **printed on the receipt**, so it must be
  stored **masked**, never as a raw parse.
  → **Worth verifying on Ingenico:** what encoding your USDK hands you for
  contactless track2, and confirming your tap receipt last-4 matches the card.

---

## Not applicable to the app

- **cashub CLI** and the MyAdmin CasHUB integration are fleet-automation tooling
  around Castles CasHUB — not part of the terminal application.
- **Castle-specific key-injection attributes** (CFFF/0000 ZMK + C000/0010 MKSK
  master) are particular to the Castle key model.

## One build gotcha (bit us, may bite anyone on this AGP)

Build with **JDK 17**. JDK 21 makes AGP 7.4.2's D8 dexer NPE on every class —
it presents as a mass, mysterious dex failure with no obvious cause.
