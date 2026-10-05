package castech.emvtxn.pos;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PosTransactionObserverTest {

    @After
    public void tearDown() {
        PosTransactionObserver.clear();
    }

    // ---- arming + isArmed -----------------------------------------------------

    @Test
    public void notArmedInitially() {
        assertFalse(PosTransactionObserver.isArmed());
    }

    @Test
    public void armSetsArmed() {
        PosTransactionObserver.arm(noopCallback());
        assertTrue(PosTransactionObserver.isArmed());
    }

    @Test
    public void clearRemovesActiveCallback() {
        PosTransactionObserver.arm(noopCallback());
        PosTransactionObserver.clear();
        assertFalse(PosTransactionObserver.isArmed());
    }

    @Test
    public void armReturnsPreviousCallback() {
        PosTransactionObserver.Callback first = noopCallback();
        PosTransactionObserver.Callback prev1 = PosTransactionObserver.arm(first);
        assertNull("first arm should return null prev", prev1);
        PosTransactionObserver.Callback second = noopCallback();
        PosTransactionObserver.Callback prev2 = PosTransactionObserver.arm(second);
        assertEquals("second arm should return first as prev", first, prev2);
    }

    // ---- notify dispatches and auto-clears ------------------------------------

    @Test
    public void notifyApprovedFiresAndClears() {
        AtomicReference<String> capturedRrn = new AtomicReference<>();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String responseCode, String referenceNumber,
                                              String authDate, String authTime,
                                              long acctBal, long availBal, String displayMessage) {
                capturedRrn.set(referenceNumber);
            }
            @Override public void onDeclined(String c, String m, boolean r) { throw new AssertionError(); }
            @Override public void onError(String e) { throw new AssertionError(); }
        });

        PosTransactionObserver.notifyApproved("00", "RRN-X", "2026/05/21", "10:00:00", 5000L, 4500L, "OK");

        assertEquals("RRN-X", capturedRrn.get());
        assertFalse("observer must auto-clear after firing", PosTransactionObserver.isArmed());
    }

    @Test
    public void notifyDeclinedFiresAndClears() {
        AtomicReference<String> code = new AtomicReference<>();
        AtomicReference<Boolean> retain = new AtomicReference<>();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                throw new AssertionError();
            }
            @Override public void onDeclined(String responseCode, String responseMessage, boolean retainCard) {
                code.set(responseCode);
                retain.set(retainCard);
            }
            @Override public void onError(String e) { throw new AssertionError(); }
        });

        PosTransactionObserver.notifyDeclined("51", "INSUFFICIENT FUNDS", true);

        assertEquals("51", code.get());
        assertTrue(retain.get());
        assertFalse(PosTransactionObserver.isArmed());
    }

    @Test
    public void notifyErrorFiresAndClears() {
        AtomicReference<String> err = new AtomicReference<>();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                throw new AssertionError();
            }
            @Override public void onDeclined(String c, String m, boolean r) { throw new AssertionError(); }
            @Override public void onError(String error) { err.set(error); }
        });

        PosTransactionObserver.notifyError("host unreachable");

        assertEquals("host unreachable", err.get());
        assertFalse(PosTransactionObserver.isArmed());
    }

    // ---- safety: notify with no callback armed is a no-op ---------------------

    @Test
    public void notifyApprovedWithoutArmIsNoOp() {
        // Just verify nothing throws and isArmed stays false
        PosTransactionObserver.notifyApproved("00", "r", "d", "t", 0, 0, "");
        PosTransactionObserver.notifyDeclined("51", "msg", false);
        PosTransactionObserver.notifyError("err");
        assertFalse(PosTransactionObserver.isArmed());
    }

    // ---- callback throwing must not leak state --------------------------------

    @Test
    public void callbackThrowingDoesNotLeaveArmedState() {
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                throw new RuntimeException("simulated callback failure");
            }
            @Override public void onDeclined(String c, String m, boolean r) {}
            @Override public void onError(String e) {}
        });
        // notify should swallow the throw; observer must still be cleared.
        PosTransactionObserver.notifyApproved("00", "r", "", "", 0, 0, "");
        assertFalse(PosTransactionObserver.isArmed());
    }

    // ---- only one notify fires per arming -------------------------------------

    @Test
    public void secondNotifyAfterFirstIsNoOp() {
        AtomicInteger calls = new AtomicInteger();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                calls.incrementAndGet();
            }
            @Override public void onDeclined(String c, String m, boolean r) { calls.incrementAndGet(); }
            @Override public void onError(String e) { calls.incrementAndGet(); }
        });

        PosTransactionObserver.notifyApproved("00", "r", "", "", 0, 0, "");
        PosTransactionObserver.notifyApproved("00", "r2", "", "", 0, 0, "");
        PosTransactionObserver.notifyError("late error");

        assertEquals("callback must fire exactly once per arming", 1, calls.get());
    }

    // ---- cancel path (terminal-side cancel answers the POS caller) ------------

    @Test
    public void cancelStyleDeclineFiresAndFreesSlot() {
        // Mirrors Fragment_page_transaction.cancelTransaction(): an armed POS
        // transaction cancelled at the terminal must answer the caller AND free
        // the slot so the next POS command isn't rejected with terminal_busy.
        AtomicReference<String> code = new AtomicReference<>();
        AtomicReference<String> msg = new AtomicReference<>();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                throw new AssertionError();
            }
            @Override public void onDeclined(String responseCode, String responseMessage, boolean retainCard) {
                code.set(responseCode);
                msg.set(responseMessage);
            }
            @Override public void onError(String e) { throw new AssertionError(); }
        });

        PosTransactionObserver.notifyDeclined("user_cancelled", "cancelled at terminal", false);

        assertEquals("user_cancelled", code.get());
        assertEquals("cancelled at terminal", msg.get());
        assertFalse("slot must be free for the next POS command", PosTransactionObserver.isArmed());
    }

    // ---- watchdog ---------------------------------------------------------------

    @Test
    public void watchdogReleasesWedgedSlotAndFiresError() throws Exception {
        java.util.concurrent.CountDownLatch fired = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> err = new AtomicReference<>();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                throw new AssertionError();
            }
            @Override public void onDeclined(String c, String m, boolean r) { throw new AssertionError(); }
            @Override public void onError(String error) { err.set(error); fired.countDown(); }
        }, 50L);

        assertTrue("watchdog should fire", fired.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("error should identify the watchdog: " + err.get(), err.get().contains("watchdog"));
        assertFalse("slot must be released", PosTransactionObserver.isArmed());
    }

    @Test
    public void watchdogIsNoOpWhenResultArrivedFirst() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
                calls.incrementAndGet();
            }
            @Override public void onDeclined(String c, String m, boolean r) { calls.incrementAndGet(); }
            @Override public void onError(String e) { calls.incrementAndGet(); }
        }, 50L);

        PosTransactionObserver.notifyApproved("00", "RRN", "", "", 0, 0, "");
        Thread.sleep(250); // let the watchdog window elapse
        assertEquals("watchdog must not double-fire a completed transaction", 1, calls.get());
    }

    @Test
    public void watchdogDoesNotKillANewerTransaction() throws Exception {
        // First transaction's watchdog expires AFTER a second transaction armed —
        // the CAS on the first callback must fail and leave the second armed.
        PosTransactionObserver.arm(noopCallback(), 50L);
        PosTransactionObserver.clear();
        AtomicInteger secondErrors = new AtomicInteger();
        PosTransactionObserver.arm(new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {}
            @Override public void onDeclined(String c, String m, boolean r) {}
            @Override public void onError(String e) { secondErrors.incrementAndGet(); }
        }, 60_000L);

        Thread.sleep(300); // first watchdog window elapses
        assertTrue("second transaction must survive the first txn's watchdog",
                PosTransactionObserver.isArmed());
        assertEquals(0, secondErrors.get());
    }

    // ---- a transaction that ends at the terminal answers ITS OWN slot (POS-13) ----
    //
    // The transaction thread remembers the slot that was armed when it started and,
    // on its way out, answers that slot if nobody has. It must never answer a slot it
    // did not start with: a host result may already have fired, or the register may
    // have armed the next transaction in the instant this one finished.

    @Test
    public void localEnding_answersTheSlotTheTransactionStartedWith() {
        Recorder rec = new Recorder();
        PosTransactionObserver.arm(rec);
        Object slot = PosTransactionObserver.armedToken();

        boolean answered = PosTransactionObserver.notifyDeclinedIf(slot, "MSR_NA", "Swipe not supported", false);

        assertTrue(answered);
        assertEquals("declined:MSR_NA:Swipe not supported:false", rec.calls.toString());
        assertFalse("slot must be free for the next POS command", PosTransactionObserver.isArmed());
    }

    @Test
    public void localEnding_saysNothingOnceTheHostHasAnswered() {
        Recorder rec = new Recorder();
        PosTransactionObserver.arm(rec);
        Object slot = PosTransactionObserver.armedToken();
        PosTransactionObserver.notifyApproved("00", "RRN", "", "", 0, 0, "APPROVED");

        boolean answered = PosTransactionObserver.notifyDeclinedIf(slot, "terminal_declined", "x", false);

        assertFalse("an approval must never be followed by a decline", answered);
        assertEquals("approved:00", rec.calls.toString());
    }

    @Test
    public void localEnding_cannotAnswerTheNextTransaction() {
        Recorder first = new Recorder();
        PosTransactionObserver.arm(first);
        Object firstSlot = PosTransactionObserver.armedToken();
        PosTransactionObserver.notifyDeclined("51", "INSUFFICIENT FUNDS", false); // host answered the first
        Recorder second = new Recorder();
        PosTransactionObserver.arm(second);                                       // register starts the next

        boolean answered = PosTransactionObserver.notifyDeclinedIf(firstSlot, "terminal_declined", "x", false);

        assertFalse(answered);
        assertEquals("the next transaction must not be answered by the previous one", "", second.calls.toString());
        assertTrue(PosTransactionObserver.isArmed());
    }

    @Test
    public void localEnding_ofAWalkUpTransaction_leavesAFreshlyArmedSlotAlone() {
        Object slot = PosTransactionObserver.armedToken();   // walk-up: nothing armed at start
        assertNull(slot);
        Recorder next = new Recorder();
        PosTransactionObserver.arm(next);                    // register arms as the walk-up finishes

        boolean answered = PosTransactionObserver.notifyDeclinedIf(slot, "terminal_declined", "x", false);

        assertFalse(answered);
        assertEquals("", next.calls.toString());
        assertTrue(PosTransactionObserver.isArmed());
    }

    @Test
    public void localEnding_withNoSlotAndNothingArmed_isANoOp() {
        assertFalse(PosTransactionObserver.notifyDeclinedIf(null, "terminal_declined", "x", false));
        assertFalse(PosTransactionObserver.isArmed());
    }

    /** Records what the register would have been told, in order. */
    private static final class Recorder implements PosTransactionObserver.Callback {
        final StringBuilder calls = new StringBuilder();
        @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {
            calls.append("approved:").append(c);
        }
        @Override public void onDeclined(String c, String m, boolean retain) {
            calls.append("declined:").append(c).append(':').append(m).append(':').append(retain);
        }
        @Override public void onError(String e) { calls.append("error:").append(e); }
    }

    private static PosTransactionObserver.Callback noopCallback() {
        return new PosTransactionObserver.Callback() {
            @Override public void onApproved(String c, String r, String d, String t, long a, long v, String m) {}
            @Override public void onDeclined(String c, String m, boolean r) {}
            @Override public void onError(String e) {}
        };
    }
}
