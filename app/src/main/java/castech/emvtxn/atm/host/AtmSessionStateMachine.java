package castech.emvtxn.atm.host;

import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Session state machine for the ATM transaction lifecycle.
 *
 * Mirrors the outer state machine observed in deployed Hyosung BlueVerse ATM software
 * (see docs/HYOSUNG_BLUEVERSE_REVERSE_ENGINEERING_FINDINGS.md §11 — FUN_0006a280).
 *
 * This class is pure state-transition logic. It does not perform I/O directly;
 * the owning service (typically {@link AtmHostService}) checks the current state
 * before performing operations and reports outcomes via the state-transition methods.
 *
 * Thread safety: state transitions are atomic via {@link AtomicReference}. Listeners
 * are invoked on the calling thread of the transition method, not a dedicated
 * dispatcher thread.
 *
 * Phase 1 of BlueVerse compliance work (task #19). Phase 2 adds the
 * pre-send persistence and recovery loop integration.
 */
public class AtmSessionStateMachine {

    private static final String TAG = "AtmSessionSM";

    private final AtomicReference<AtmSessionState> state =
            new AtomicReference<>(AtmSessionState.IDLE);

    private volatile Listener listener;

    /**
     * Listener for state transitions. Invoked synchronously on the thread that
     * triggered the transition. Implementations should be fast and non-blocking;
     * defer heavy work to a background thread.
     */
    public interface Listener {
        /**
         * Called when the session state changes.
         *
         * @param from previous state
         * @param to new state
         */
        void onStateChanged(AtmSessionState from, AtmSessionState to);
    }

    public AtmSessionStateMachine() {
    }

    /**
     * Sets the state-transition listener. Replaces any previous listener.
     *
     * @param listener listener to invoke on state changes, or null to clear
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Returns the current session state.
     */
    public AtmSessionState getState() {
        return state.get();
    }

    /**
     * Returns true if the terminal is in a state where customer transactions
     * may begin. Equivalent to {@code getState() == READY}.
     */
    public boolean isReadyForCustomer() {
        return state.get() == AtmSessionState.READY;
    }

    /**
     * Returns true if the terminal is in a state where customer interaction
     * should be blocked (Open in progress, reversal recovery running, etc.).
     */
    public boolean isCustomerInteractionBlocked() {
        AtmSessionState s = state.get();
        return s == AtmSessionState.OPENING
                || s == AtmSessionState.OPEN_ERROR_RETRY
                || s == AtmSessionState.REVERSAL_RECOVERY
                || s == AtmSessionState.OUT_OF_SERVICE;
    }

    /**
     * Returns true if the terminal is currently processing a transaction.
     */
    public boolean isTransactionInProgress() {
        AtmSessionState s = state.get();
        return s == AtmSessionState.TRANSACTION
                || s == AtmSessionState.POST_TRANSACTION;
    }

    // =========================================================================
    // State transition methods
    //
    // Each method represents a single legal transition. Illegal transitions
    // throw IllegalStateException — callers are expected to check getState()
    // before invoking.
    //
    // Naming convention: action verbs describe what happened, not what should
    // happen next. E.g., openStarted() is called by the service after it has
    // begun the Open procedure, not as a request to begin one.
    // =========================================================================

    /**
     * Transition: IDLE → OPENING.
     * Called when the service has begun the Open procedure (Type 88 download).
     */
    public void openStarted() {
        transition(AtmSessionState.IDLE, AtmSessionState.OPENING);
    }

    /**
     * Transition: OPENING → READY.
     * Called when Open completes successfully (host connected, working key loaded).
     */
    public void openSucceeded() {
        transition(AtmSessionState.OPENING, AtmSessionState.READY);
    }

    /**
     * Transition: OPENING → OPEN_ERROR_RETRY.
     * Called when Open fails and a retry will be attempted.
     */
    public void openFailed() {
        transition(AtmSessionState.OPENING, AtmSessionState.OPEN_ERROR_RETRY);
    }

    /**
     * Transition: OPEN_ERROR_RETRY → OPENING.
     * Called when the retry timer has elapsed and another Open attempt begins.
     */
    public void openRetryStarted() {
        transition(AtmSessionState.OPEN_ERROR_RETRY, AtmSessionState.OPENING);
    }

    /**
     * Transition: any state → OUT_OF_SERVICE.
     * Called when retries are exhausted (Open or reversal) and operator
     * intervention is required.
     */
    public void outOfService() {
        AtmSessionState previous = state.getAndSet(AtmSessionState.OUT_OF_SERVICE);
        if (previous != AtmSessionState.OUT_OF_SERVICE) {
            Log.w(TAG, "Transition: " + previous + " → OUT_OF_SERVICE");
            notifyListener(previous, AtmSessionState.OUT_OF_SERVICE);
        }
    }

    /**
     * Transition: READY → TRANSACTION.
     * Called when a customer transaction begins (card inserted / amount selected).
     */
    public void transactionStarted() {
        transition(AtmSessionState.READY, AtmSessionState.TRANSACTION);
    }

    /**
     * Transition: TRANSACTION → POST_TRANSACTION.
     * Called when the transaction handler completes its work (regardless of
     * approval/decline outcome). The state machine will then check for pending
     * reversals via {@link #postTransactionCheck(boolean)}.
     */
    public void transactionCompleted() {
        transition(AtmSessionState.TRANSACTION, AtmSessionState.POST_TRANSACTION);
    }

    /**
     * Transition: POST_TRANSACTION → READY (no reversal) or REVERSAL_RECOVERY (pending).
     *
     * Called immediately after {@link #transactionCompleted()} once the service
     * has determined whether a reversal is needed.
     *
     * @param hasPendingReversals true if any reversals are queued and require processing
     */
    public void postTransactionCheck(boolean hasPendingReversals) {
        if (hasPendingReversals) {
            transition(AtmSessionState.POST_TRANSACTION, AtmSessionState.REVERSAL_RECOVERY);
        } else {
            transition(AtmSessionState.POST_TRANSACTION, AtmSessionState.READY);
        }
    }

    /**
     * Transition: REVERSAL_RECOVERY → READY.
     * Called when the reversal recovery loop has cleared the pending queue.
     */
    public void reversalRecoveryCleared() {
        transition(AtmSessionState.REVERSAL_RECOVERY, AtmSessionState.READY);
    }

    /**
     * Transition: any state → IDLE.
     * Called when the host connection is lost or the terminal is restarting
     * the session.
     */
    public void sessionLost() {
        AtmSessionState previous = state.getAndSet(AtmSessionState.IDLE);
        if (previous != AtmSessionState.IDLE) {
            Log.w(TAG, "Transition: " + previous + " → IDLE (session lost)");
            notifyListener(previous, AtmSessionState.IDLE);
        }
    }

    /**
     * Transition: OUT_OF_SERVICE → IDLE.
     * Called by operator action to clear the out-of-service condition and
     * re-attempt the Open procedure on next transaction request.
     */
    public void operatorReset() {
        AtmSessionState previous = state.getAndSet(AtmSessionState.IDLE);
        Log.i(TAG, "Operator reset: " + previous + " → IDLE");
        notifyListener(previous, AtmSessionState.IDLE);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Performs an atomic state transition. Throws IllegalStateException if the
     * current state is not the expected source state.
     */
    private void transition(AtmSessionState expected, AtmSessionState next) {
        if (!state.compareAndSet(expected, next)) {
            AtmSessionState actual = state.get();
            String msg = "Illegal transition: expected " + expected + " → " + next
                    + " but state was " + actual;
            Log.e(TAG, msg);
            throw new IllegalStateException(msg);
        }
        Log.d(TAG, "Transition: " + expected + " → " + next);
        notifyListener(expected, next);
    }

    private void notifyListener(AtmSessionState from, AtmSessionState to) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onStateChanged(from, to);
            } catch (Throwable t) {
                Log.e(TAG, "Listener threw during state change: " + t.getMessage(), t);
            }
        }
    }
}
