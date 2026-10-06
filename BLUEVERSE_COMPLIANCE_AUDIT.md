# Castle Cashless ATM — BlueVerse Compliance Audit

**Date:** 2026-05-17
**Auditor:** Static code analysis against reverse-engineered Hyosung BlueVerse behavior
**Scope:** All ATM host orchestration code, both Hyosung STD1 and Triton STD3 paths
**Reference target:** `docs/HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md` §10–§16

## Executive Summary

The current Castle Cashless ATM implementation has a **request-scoped architecture** where each transaction stands alone. BlueVerse uses a **session-scoped architecture** where transactions are wrapped in an outer session state machine with explicit Open/Recovery phases.

The protocol-level code (Hyosung/Triton message build & parse) is mature and not the issue. The gap is at the orchestration layer above the protocols — and because BlueVerse's orchestration is protocol-agnostic, fixing it ONCE makes both our protocols compliant.

**Critical findings:**
- **0 of 10 BlueVerse patterns are fully present**
- **4 of 10 are partially present** (#3, #4, #9, #10)
- **6 of 10 are entirely missing** (#1, #2, #5, #6, #7, #8)
- The single largest architectural conflict: every transaction `finally` block force-disconnects the host socket, which fundamentally prevents the session-scoped model

## Files Audited

| File | Lines | Role |
|---|---|---|
| `AtmHostService.java` | 1219 | Service-layer orchestrator |
| `AtmTransactionManager.java` | 1310 | Transaction lifecycle |
| `ReversalPersistenceManager.java` | 524 | Reversal storage |
| `AtmHostConnection.java` | 830 | TCP/TLS socket layer |
| `ReversalRequest.java` | 231 | Reversal data model |

Triton classes (`TritonMessageBuilder/Parser/Protocol`) and Hyosung classes (`HyosungMessageBuilder/Parser/Protocol`) are protocol-only and not in scope for this audit — they're called BY the orchestrator and don't need changes for compliance.

---

## Gap Analysis (10 Target Behaviors)

### 1. Pre-persist reversal record BEFORE sending request (Task #1)

**Current state:** Post-failure only. `storePendingReversalIfNeeded()` is called from `catch` blocks at `AtmTransactionManager.java:300, 312, 320` — after a send already failed.

**Evidence:**
- Line 278: `requestSentToHost = true;` set right before `connection.sendTransaction(request)`
- Line 293: send happens
- Lines 300-327: catch blocks call `storePendingReversalIfNeeded(reason)`
- No `createAndStorePendingReversal` call before line 293

**Gap:** Power loss between socket write and catch block → reversal record orphaned. Target writes reversal pre-send and clears it on confirmed approval.

**Complexity:** Trivial.

**Affected:** `AtmTransactionManager.java` only.

---

### 2. Block new transactions while reversals pending (Task #2)

**Current state:** Not present. Pending reversals do not gate transaction entry.

**Evidence:**
- `AtmHostService.performWithdrawal()` line 483: only checks `hasWorkingKeys()`
- `AtmHostService.performBalanceInquiry()` line 518: same
- `AtmTransactionManager.performCashWithdrawal()` line 191: only guard is `transactionInProgress.compareAndSet`
- `hasPendingReversals()` defined at line 596 but never called from entry paths

**Gap:** App will accept new transactions with N pending reversals on disk. Whittle §4 violation.

**Complexity:** Trivial.

**Affected:** `AtmHostService.java`, `AtmTransactionManager.java`.

---

### 3. Bounded retry with backoff (Task #3)

**Current state:** Partial. Two retry sites, neither matches target.

**Evidence:**
- `downloadKeysSyncInternal()` line 553: hardcoded `maxRetries = 3`, fixed 500ms delay
- `sendReversalWithRetry()` line 1016: uses `config.getMaxReversalRetries()` and `config.getRetryDelayMs()` — flat delay
- `processPendingReversals()` line 644: single attempt per reversal
- No exponential backoff anywhere
- No operator-alert escalation path

**Gap:** Target = 5 retries with exponential backoff (1s, 2s, 4s, 8s, 16s), then operator alert.

**Complexity:** Moderate.

**Affected:** `AtmTransactionManager.java`, `AtmHostService.java`, plus new `AtmEventListener.onOperatorAlert()` callback.

---

### 4. Distinguish Timeout Reversal vs Financial Reversal (Task #4)

**Current state:** Partial. Reason code distinguishes timeout from other errors, but reversal payload composition does not differ.

**Evidence:**
- Lines 304-309: `isTimeout` boolean derived from exception message substring
- Lines 149-151 in `storePendingReversalIfNeeded()`: always pulls `currentResponse.getAuthorizationData()` if any response exists
- `ReversalRequest.createTimeoutReversal()` (line 72) explicitly sets empty `originalAuthData` — but **this factory is dead code**, never called
- `ReversalRequest.createFromTransaction()` (line 54) always uses response data — would NPE if called on a true timeout (no response)

**Gap:** Target distinguishes payload composition: timeout = request data only, financial = may include response data. Our `createTimeoutReversal` factory exists but is orphaned.

**Complexity:** Moderate.

**Affected:** `AtmTransactionManager.java`, `ReversalRequest.java`, `ReversalPersistenceManager.java`.

---

### 5. KEY-LOADED flag pattern (Task #17)

**Current state:** Not present as the dispatch-scoped pattern.

**Evidence:**
- `AtmHostService.java:44` has `keyDownloadInProgress` but it's a concurrency guard, not a load-state flag
- Key state is queried via `hasWorkingKeys()` / `hasValidWorkingKey()` / `needsKeyRenewal()` against `CastleKeyManager`
- No per-dispatch local boolean cleared at start and set on successful load

**Gap:** Target = explicit `boolean keyLoaded = false` at top of each dispatch, set to true when load succeeds, returned/checked at end. Single source of truth per dispatch.

**Complexity:** Trivial.

**Affected:** `AtmTransactionManager.java`.

---

### 6. 60-second retry on Open failure (Task #14)

**Current state:** Not present.

**Evidence:**
- `AtmHostConnection.connect()` line 74: single-attempt, fail-fast
- `AtmTransactionManager.connect()` line 1129: no retry on ConnectionException
- `ensureConnected()` line 767: single attempt
- Only retry exists in `downloadKeysSyncInternal` (3 attempts, 500ms) — not 60s
- No UI-lock primitive tied to connection retry

**Gap:** Target = on Open failure, retry for 60 seconds while UI is locked, then escalate. Current = fail-fast, single attempt, no UI coordination.

**Complexity:** Moderate (requires UI cooperation in fragments).

**Affected:** `AtmTransactionManager.java`, `AtmHostService.java`, `AtmEventListener` (new callback), `Fragment_page_amount_selection.java` (lock UI).

---

### 7. Synchronous reversal recovery loop after each transaction (Task #15)

**Current state:** Not present. No drain runs after a transaction.

**Evidence:**
- `performCashWithdrawal()` finally block lines 328-333: only disconnect + clear flag
- `performBalanceInquiry()` finally block lines 452-457: identical
- `processPendingReversals()` (line 620) is the only drain, but it must be **manually invoked** ("Should be called on app startup and can be triggered manually from admin")
- Drain iterates `pendingList` once — not `while (hasPendingReversals())`
- `processingReversals` flag (line 43) actively prevents re-entry — opposite of recovery loop

**Gap:** **The single most important compliance item.** Target runs a blocking drain after every transaction; current never auto-runs it. Whittle §4 violation.

**Complexity:** Moderate.

**Affected:** `AtmTransactionManager.java` (call drain from finally), `AtmHostService.java` (convert single-pass to while-loop).

---

### 8. Three reversal states (Task #16)

**Current state:** Not present. Only flat status strings exist.

**Evidence:**
- `ReversalPersistenceManager.PendingReversal` constants (lines 323-325): `STATUS_PENDING`, `STATUS_PROCESSING`, `STATUS_FAILED`
- These track lifecycle of a single attempt, not the three-way host-connectivity state machine
- No `NEEDED_NOW`, `NEEDS_RECONNECT_AND_EXIT`, `NEEDS_RECONNECT_AND_REVERSE` taxonomy

**Gap:** Entire three-state model is absent. No classification of host-down vs host-up reversal handling.

**Complexity:** Significant.

**Affected:** New enum in `ReversalPersistenceManager.java`, decision logic in drain loop, persistence schema migration.

---

### 9. Open-before-session (Type 88 config download) (Task #12)

**Current state:** Partial. Auto-download tied to local key state, not to session lifecycle.

**Evidence:**
- `performWithdrawal()` lines 487-510: `if (!isDukpt && !hasWorkingKeys()) { downloadKeysSync() }`
- `performBalanceInquiry()` lines 522-539: identical
- `performStartupKeyRenewal()` (line 273) exists for explicit startup, but not auto-invoked from session entry
- **DUKPT mode skips Open entirely** (line 506: "DUKPT mode - no working key download needed") — but Open also handles surcharge tables, KSN sync, config download, not just keys
- No `sessionEstablished` flag

**Gap:** Target sends Type 88 on first transaction of a session OR after reconnect, regardless of key mode. Current only triggers on empty MKSK key state.

**Complexity:** Moderate.

**Affected:** `AtmHostService.java`, `AtmTransactionManager.java`.

---

### 10. Skip Open if host alive (connection state caching) (Task #13)

**Current state:** Partial. Capability exists but is actively defeated by policy.

**Evidence:**
- `AtmHostConnection.isConnected()` line 210: proper liveness check
- `ensureConnected()` line 767: correctly skips connect if alive
- **However**, every transaction `finally` block force-disconnects:
  - Line 330 (withdrawal): `connection.disconnect();`
  - Line 454 (balance inquiry): same
  - Lines 488, 507, 595 (config flow): same
  - Comments at lines 329, 453: "Always disconnect - server closes connection after each transaction"
- `requestHostTotals` force-disconnects-then-reconnects on entry (lines 884-891)
- Result: caching mechanism exists but every transaction starts cold

**Gap:** Target keeps connection alive between transactions, skips Open if cached connection responds.

**Complexity:** Moderate (requires understanding which processors actually require disconnect — may need per-processor flag).

**Affected:** `AtmTransactionManager.java` (remove unconditional disconnects), `ProcessorConfig.java` (add `keepAlive` flag).

---

## Cross-Cutting Observations

### Architecture Mismatch
The codebase is **request-scoped** — each transaction stands alone with no concept of an ATM session. BlueVerse is **session-scoped** — transactions are nested inside a session with explicit Open/Recovery phases.

To match BlueVerse, we need to add a layer ABOVE `AtmTransactionManager` that owns:
- Session state (IDLE / OPEN_IN_PROGRESS / READY_FOR_CUSTOMER / TRANSACTION_RUNNING / REVERSAL_PROCESSING / ERROR_LOCKED)
- Proc-code dispatch
- The reversal recovery loop
- The Open-before-session trigger
- Connection lifetime management

### Forced Disconnect Anti-Pattern
The "always disconnect" comments suggest a historical assumption that processors close after each transaction. This may be true for some processors but NOT for all — and certainly is NOT what BlueVerse does. This single design choice prevents tasks #6, #7, #9, #10 from working as designed.

**Recommendation:** Add a per-processor `keepAlive: boolean` config. Default to false initially (preserves current behavior), set to true once we've validated keep-alive works for each processor.

### Reversal Persistence is "Passive"
Persistence happens AFTER failure, drain requires EXTERNAL trigger. BlueVerse is the opposite — persist BEFORE send, drain AUTOMATICALLY after every transaction.

This is a fundamental flip in lifecycle ownership. Once made, tasks #1, #2, #7 all become trivial — they're consequences of the right model.

### Dead Code Signal
`ReversalRequest.createTimeoutReversal()` exists but is never called. This suggests someone designed for the right behavior, then the production path bypassed it. Worth understanding why before re-wiring.

---

## Proposed Refactor Sequence

Based on dependencies and risk:

### Phase 1 — Foundation (no behavior change yet, ~1 day)
- **Task #19**: Add session state machine class (`AtmSessionStateMachine`)
- **Task #18**: Refactor `AtmTransactionManager` calls into proc-code dispatch
- Both can land as scaffolding without changing existing behavior

### Phase 2 — Critical Correctness (~2 days)
- **Task #1**: Pre-persist reversal before send
- **Task #15**: Synchronous reversal recovery loop after each transaction
- **Task #2**: Block new transactions while reversals pending
- These three together implement Whittle §4 compliance — the most important fix

### Phase 3 — Open Flow (~2 days)
- **Task #13**: Remove force-disconnects, add `keepAlive` config (per processor)
- **Task #12**: Add Open-before-session trigger via session state machine
- **Task #14**: 60-second retry on Open failure with UI lock
- **Task #17**: KEY-LOADED flag pattern in dispatch

### Phase 4 — Reversal Semantics (~2 days)
- **Task #4**: Distinguish timeout vs financial reversal
- **Task #16**: Three reversal states
- **Task #20**: IsReversalCondition logic in response parsers (both Hyosung and Triton)

### Phase 5 — Robustness (~2 days)
- **Task #3**: Bounded retry with backoff and operator alert
- **Task #5**: Per-destination upload tracking (UP_TYPE)
- **Task #6**: Migrate persistence to append-only journal
- **Task #7**: Date-partitioned journal files

### Phase 6 — Operations (~1 day)
- **Task #8**: Periodic Type 89 health check
- **Task #9**: Operator alert UI for stuck reversals
- **Task #10**: Reversal configuration in admin settings

### Phase 7 — Validation (~2 days)
- **Task #11**: Unit tests for new state machine + reversal lifecycle

**Total estimate: ~12 days of focused work** to fully match BlueVerse behavior, with the most-critical compliance items landing in the first 3 days (Phases 1+2).

---

## Recommendation

Start with **Phase 1 + Phase 2** — they deliver the largest compliance improvement (Whittle §4) for the least effort, and establish the architectural scaffolding the remaining phases build on.

The single biggest decision to make BEFORE starting: **do we accept the "always disconnect" pattern as a per-processor config, or do we move to keep-alive as the default?** This affects Phase 3's design significantly.

My recommendation: keep-alive as default, with per-processor opt-out. Matches BlueVerse, simpler to reason about, and the few processors that need disconnect-per-transaction can declare it explicitly.

---

*Audit complete. Ready to begin refactor when you give the word.*
