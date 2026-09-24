package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The customer-transaction gate for pending reversals. Decided 2026-09-24:
 * block only while a drain is actually running (or about to run for an active
 * record); a record whose retries are exhausted (FAILED) does NOT block by itself —
 * the terminal trades and alerts; the safety stop is a COUNT of failed records
 * (N = 2), no money threshold.
 */
public class ReversalGatePolicyTest {

    @Test
    public void nothingPending_allows() {
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(false, 0, 0);
        assertEquals(ReversalGatePolicy.Outcome.ALLOW, d.outcome);
    }

    @Test
    public void drainRunning_waitsWithTheProcessingMessage() {
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(true, 1, 0);
        assertEquals(ReversalGatePolicy.Outcome.WAIT_DRAIN_RUNNING, d.outcome);
        assertEquals("Please wait — processing pending transactions", d.customerMessage);
    }

    @Test
    public void activeRecordNoDrain_waitsAndStartsTheDrain() {
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(false, 1, 0);
        assertEquals(ReversalGatePolicy.Outcome.WAIT_START_DRAIN, d.outcome);
        assertEquals("Please wait — processing pending transactions", d.customerMessage);
    }

    @Test
    public void oneFailedRecord_tradesAndAlerts() {
        // The field incident: an exhausted record must not anchor the terminal.
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(false, 0, 1);
        assertEquals(ReversalGatePolicy.Outcome.ALLOW, d.outcome);
    }

    @Test
    public void failedRecordsAtTheSafetyStop_outOfService() {
        assertEquals(2, ReversalGatePolicy.SAFETY_STOP_FAILED_RECORDS);
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(false, 0, 2);
        assertEquals(ReversalGatePolicy.Outcome.OUT_OF_SERVICE, d.outcome);
        assertEquals("Out of service — pending reversals, contact TFI", d.customerMessage);
        assertEquals(ReversalGatePolicy.Outcome.OUT_OF_SERVICE, ReversalGatePolicy.decide(false, 0, 5).outcome);
    }

    @Test
    public void safetyStopDominatesARunningDrain() {
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(true, 0, 2);
        assertEquals(ReversalGatePolicy.Outcome.OUT_OF_SERVICE, d.outcome);
    }

    @Test
    public void oneFailedPlusOneActive_waitsForTheActiveOne() {
        ReversalGatePolicy.Decision d = ReversalGatePolicy.decide(false, 1, 1);
        assertEquals(ReversalGatePolicy.Outcome.WAIT_START_DRAIN, d.outcome);
    }

    // ---- status classification the gate relies on ----

    @Test
    public void activeStatuses_arePendingAndReconnectVariants() {
        assertTrue(ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_PENDING));
        assertTrue(ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_PENDING_RECONNECT_AND_EXIT));
        assertTrue(ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_PENDING_RECONNECT_AND_REVERSE));
        assertEquals(false, ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_FAILED));
        assertEquals(false, ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_PENDING_PRESEND));
        assertEquals(false, ReversalGatePolicy.isActive(ReversalPersistenceManager.PendingReversal.STATUS_PROCESSING));
    }

    @Test
    public void failedIsRetryableInTheBackgroundButNotActive() {
        assertTrue(ReversalGatePolicy.isRetryable(ReversalPersistenceManager.PendingReversal.STATUS_FAILED));
        assertTrue(ReversalGatePolicy.isRetryable(ReversalPersistenceManager.PendingReversal.STATUS_PENDING));
        assertEquals(false, ReversalGatePolicy.isRetryable(ReversalPersistenceManager.PendingReversal.STATUS_PENDING_PRESEND));
        assertEquals(false, ReversalGatePolicy.isRetryable(ReversalPersistenceManager.PendingReversal.STATUS_PROCESSING));
    }
}
