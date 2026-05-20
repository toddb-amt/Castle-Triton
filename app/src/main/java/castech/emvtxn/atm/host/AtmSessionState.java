package castech.emvtxn.atm.host;

/**
 * Session state for the ATM transaction lifecycle.
 *
 * Mirrors the outer state machine observed in deployed Hyosung BlueVerse ATM software
 * (see docs/HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md §11 and §12).
 *
 * Each state represents a distinct phase of ATM operation. Transitions are driven by
 * {@link AtmSessionStateMachine} and gate which operations can be performed.
 *
 * State machine summary:
 *
 *   IDLE  ──(first txn request)──>  OPENING  ──(open success)──>  READY
 *                                      │                            │
 *                                      ├──(open failed)──>  OPEN_ERROR_RETRY
 *                                      │                            │
 *                                      │           ┌────────────────┘
 *                                      │           ▼
 *                                      └──  (60s wait + retry)
 *
 *   READY  ──(customer card)──>  TRANSACTION  ──(complete)──>  POST_TRANSACTION
 *                                      │                            │
 *                                      └──(reversal flagged)──>  REVERSAL_RECOVERY
 *                                                                   │
 *                                              POST_TRANSACTION ────┘
 *                                                      │
 *                                                      ├──(no reversal)──> READY
 *                                                      └──(reversal pending)──> REVERSAL_RECOVERY
 *
 *   REVERSAL_RECOVERY ──(queue clears)──> READY
 *                     ──(retries exhausted)──> OUT_OF_SERVICE
 *
 *   Any state ──(host disconnect detected)──> IDLE
 */
public enum AtmSessionState {

    /**
     * No active host session. No host connection attempted yet, or disconnected.
     * Transitions to {@link #OPENING} on first transaction request.
     */
    IDLE,

    /**
     * Open procedure in progress. Sending Type 88 config download, loading working key.
     * Customer interaction blocked. Transitions to {@link #READY} on success,
     * {@link #OPEN_ERROR_RETRY} on failure.
     */
    OPENING,

    /**
     * Open procedure failed. Waiting (typically 60 seconds) before retry attempt.
     * Customer interaction blocked. Transitions back to {@link #OPENING} after the
     * wait, or to {@link #OUT_OF_SERVICE} if retry count exhausted.
     */
    OPEN_ERROR_RETRY,

    /**
     * Host session established, working key loaded, ready to accept customer transactions.
     * This is the "idle, waiting for customer" state — equivalent to BlueVerse state 5/9.
     * Transitions to {@link #TRANSACTION} when a customer presents a card,
     * or to {@link #REVERSAL_RECOVERY} if a pending reversal is detected before
     * the customer can start.
     */
    READY,

    /**
     * Customer transaction in progress. Card has been read, awaiting completion.
     * Transitions to {@link #POST_TRANSACTION} on completion.
     */
    TRANSACTION,

    /**
     * Transaction has completed (success or failure). Checking whether any reversal
     * is now pending. This is a transient state that immediately transitions to either
     * {@link #READY} (no reversal needed) or {@link #REVERSAL_RECOVERY} (reversal pending).
     */
    POST_TRANSACTION,

    /**
     * Synchronous reversal recovery loop running. Will continue dispatching reversals
     * until the pending queue clears or retry count exhausted. Customer interaction
     * blocked. Transitions to {@link #READY} on queue clear,
     * {@link #OUT_OF_SERVICE} on retry exhaustion.
     */
    REVERSAL_RECOVERY,

    /**
     * Terminal is out of service. Reached after persistent failures (Open retries exhausted,
     * reversal retries exhausted). Requires operator intervention to recover. Customer
     * interaction blocked. No automatic transition out — operator must reset.
     */
    OUT_OF_SERVICE
}
