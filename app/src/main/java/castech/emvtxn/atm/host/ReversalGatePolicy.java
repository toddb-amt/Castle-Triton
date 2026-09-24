package castech.emvtxn.atm.host;

import castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal;

/**
 * Decides whether a customer transaction may start while reversal records exist.
 *
 * <p>Policy (decided 2026-09-24, after a field terminal was anchored for a day by
 * one reversal the host kept rejecting):
 * <ul>
 *   <li>Customers wait only while a drain is <em>actually running</em>, or is about to
 *       run for an <em>active</em> record (one whose retries are not exhausted).</li>
 *   <li>A record whose retries are exhausted ({@code STATUS_FAILED}) does not block on
 *       its own: the terminal trades, shows the service banner, and the record is
 *       retried in the background — never on every customer.</li>
 *   <li>The safety stop is a <em>count</em>: {@link #SAFETY_STOP_FAILED_RECORDS} failed
 *       records take the terminal out of service with an honest message. No money
 *       threshold — one stuck record never anchors a store; a pattern does.</li>
 * </ul>
 * Pure Java so the rules are unit-tested; {@code AtmHostService} supplies the counts.
 */
public final class ReversalGatePolicy {

    private ReversalGatePolicy() {}

    /** Failed (retry-exhausted) records at which the terminal stops taking customers. */
    public static final int SAFETY_STOP_FAILED_RECORDS = 2;

    public static final String MSG_PROCESSING     = "Please wait — processing pending transactions";
    public static final String MSG_OUT_OF_SERVICE = "Out of service — pending reversals, contact TFI";

    public enum Outcome {
        /** Take the transaction. */
        ALLOW,
        /** A drain is running now; the customer waits. */
        WAIT_DRAIN_RUNNING,
        /** Active records are on disk; start a drain, the customer waits. */
        WAIT_START_DRAIN,
        /** Safety stop reached; refuse until an operator resolves records. */
        OUT_OF_SERVICE
    }

    public static final class Decision {
        public final Outcome outcome;
        /** What the customer is told when not allowed; null when allowed. */
        public final String customerMessage;

        Decision(Outcome outcome, String customerMessage) {
            this.outcome = outcome;
            this.customerMessage = customerMessage;
        }

        public boolean allowed() {
            return outcome == Outcome.ALLOW;
        }
    }

    /**
     * @param drainRunning a drain loop is currently executing
     * @param activeCount  records with an active status (see {@link #isActive})
     * @param failedCount  records with {@code STATUS_FAILED}
     */
    public static Decision decide(boolean drainRunning, int activeCount, int failedCount) {
        if (failedCount >= SAFETY_STOP_FAILED_RECORDS) {
            return new Decision(Outcome.OUT_OF_SERVICE, MSG_OUT_OF_SERVICE);
        }
        if (drainRunning) {
            return new Decision(Outcome.WAIT_DRAIN_RUNNING, MSG_PROCESSING);
        }
        if (activeCount > 0) {
            return new Decision(Outcome.WAIT_START_DRAIN, MSG_PROCESSING);
        }
        return new Decision(Outcome.ALLOW, null);
    }

    /** A record the terminal must still try to send before taking customers. */
    public static boolean isActive(String status) {
        return PendingReversal.STATUS_PENDING.equals(status)
                || PendingReversal.STATUS_PENDING_RECONNECT_AND_EXIT.equals(status)
                || PendingReversal.STATUS_PENDING_RECONNECT_AND_REVERSE.equals(status)
                // PROCESSING outlives the drain only after a crash/power cut mid-attempt;
                // such a record must still be drained, not orphaned.
                || PendingReversal.STATUS_PROCESSING.equals(status);
    }

    /**
     * A record a drain may attempt: every active status plus {@code STATUS_FAILED},
     * which is retried only by the background schedule, on network recovery, at boot,
     * or by an operator. {@code PENDING_PRESEND} (in flight) and {@code PROCESSING}
     * (being attempted right now) are never picked up.
     */
    public static boolean isRetryable(String status) {
        return isActive(status) || PendingReversal.STATUS_FAILED.equals(status);
    }
}
