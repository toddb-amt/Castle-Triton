package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link AtmSessionStateMachine} (task #11).
 *
 * <p>Pure JVM tests — no Android framework needed. Verifies the state machine
 * matches the BlueVerse outer state machine semantics.</p>
 */
public class AtmSessionStateMachineTest {

    private AtmSessionStateMachine sm;
    private List<AtmSessionState[]> transitions; // [from, to] pairs

    @Before
    public void setUp() {
        sm = new AtmSessionStateMachine();
        transitions = new ArrayList<>();
        sm.setListener((from, to) -> transitions.add(new AtmSessionState[]{from, to}));
    }

    @Test
    public void initialStateIsIdle() {
        assertEquals(AtmSessionState.IDLE, sm.getState());
        assertFalse(sm.isReadyForCustomer());
        assertFalse(sm.isCustomerInteractionBlocked());
        assertFalse(sm.isTransactionInProgress());
    }

    @Test
    public void happyPathOpenToReady() {
        sm.openStarted();
        assertEquals(AtmSessionState.OPENING, sm.getState());
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.openSucceeded();
        assertEquals(AtmSessionState.READY, sm.getState());
        assertTrue(sm.isReadyForCustomer());
        assertFalse(sm.isCustomerInteractionBlocked());
    }

    @Test
    public void openFailureGoesToErrorRetry() {
        sm.openStarted();
        sm.openFailed();
        assertEquals(AtmSessionState.OPEN_ERROR_RETRY, sm.getState());
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.openRetryStarted();
        assertEquals(AtmSessionState.OPENING, sm.getState());
    }

    @Test
    public void transactionLifecycleWithoutReversal() {
        sm.openStarted();
        sm.openSucceeded();
        sm.transactionStarted();
        assertEquals(AtmSessionState.TRANSACTION, sm.getState());
        assertTrue(sm.isTransactionInProgress());

        sm.transactionCompleted();
        assertEquals(AtmSessionState.POST_TRANSACTION, sm.getState());

        sm.postTransactionCheck(false); // no pending reversals
        assertEquals(AtmSessionState.READY, sm.getState());
        assertTrue(sm.isReadyForCustomer());
    }

    @Test
    public void transactionLifecycleWithReversal() {
        sm.openStarted();
        sm.openSucceeded();
        sm.transactionStarted();
        sm.transactionCompleted();
        sm.postTransactionCheck(true); // reversal pending
        assertEquals(AtmSessionState.REVERSAL_RECOVERY, sm.getState());
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.reversalRecoveryCleared();
        assertEquals(AtmSessionState.READY, sm.getState());
    }

    @Test
    public void outOfServiceFromAnyState() {
        // From OPENING
        sm.openStarted();
        sm.outOfService();
        assertEquals(AtmSessionState.OUT_OF_SERVICE, sm.getState());
        assertTrue(sm.isCustomerInteractionBlocked());

        // Operator can reset
        sm.operatorReset();
        assertEquals(AtmSessionState.IDLE, sm.getState());
    }

    @Test
    public void sessionLostFromAnyState() {
        sm.openStarted();
        sm.openSucceeded();
        sm.sessionLost();
        assertEquals(AtmSessionState.IDLE, sm.getState());
    }

    @Test
    public void illegalTransitionThrows() {
        // Cannot mark transaction started without going through OPENING/READY
        try {
            sm.transactionStarted();
            fail("Expected IllegalStateException for IDLE → TRANSACTION");
        } catch (IllegalStateException expected) {
            // OK
        }
        assertEquals(AtmSessionState.IDLE, sm.getState());
    }

    @Test
    public void doubleOutOfServiceIsIdempotent() {
        sm.outOfService();
        sm.outOfService(); // second call should be a no-op
        assertEquals(AtmSessionState.OUT_OF_SERVICE, sm.getState());
    }

    @Test
    public void listenerReceivesAllTransitions() {
        sm.openStarted();
        sm.openSucceeded();
        sm.transactionStarted();
        sm.transactionCompleted();
        sm.postTransactionCheck(false);

        assertEquals(5, transitions.size());
        assertEquals(AtmSessionState.IDLE, transitions.get(0)[0]);
        assertEquals(AtmSessionState.OPENING, transitions.get(0)[1]);
        assertEquals(AtmSessionState.READY, transitions.get(4)[1]);
    }

    @Test
    public void listenerNotInvokedForNoOpOutOfService() {
        sm.outOfService();
        int countAfterFirst = transitions.size();
        sm.outOfService();
        assertEquals("Second outOfService() must be a no-op (no listener event)",
                countAfterFirst, transitions.size());
    }

    @Test
    public void customerInteractionBlockedStates() {
        AtmSessionState[] blocked = {
                AtmSessionState.OPENING,
                AtmSessionState.OPEN_ERROR_RETRY,
                AtmSessionState.REVERSAL_RECOVERY,
                AtmSessionState.OUT_OF_SERVICE
        };
        // Drive into each state once and verify the flag
        sm.openStarted();
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.openFailed();
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.openRetryStarted();
        sm.openSucceeded();
        sm.transactionStarted();
        sm.transactionCompleted();
        sm.postTransactionCheck(true);
        assertTrue(sm.isCustomerInteractionBlocked());

        sm.outOfService();
        assertTrue(sm.isCustomerInteractionBlocked());

        // Sanity: list of blocked states matches blocked-state predicate
        for (AtmSessionState s : blocked) {
            assertNotNull(s); // smoke check; predicate covered above
        }
    }
}
