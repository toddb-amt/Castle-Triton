package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/** RPT-02: the journal tells the pusher "a row was written" without knowing the pusher. */
public class PushSignalTest {
    @After public void tearDown() { PushSignal.setListener(null); }

    @Test
    public void newRow_reachesTheRegisteredListener_once() {
        AtomicInteger calls = new AtomicInteger();
        PushSignal.setListener(calls::incrementAndGet);
        PushSignal.newRow();
        assertEquals(1, calls.get());
    }

    @Test
    public void newRow_withNoListener_isANoOp() {
        PushSignal.newRow();   // must not throw
    }

    @Test
    public void aThrowingListener_doesNotPropagate() {
        PushSignal.setListener(() -> { throw new IllegalStateException("boom"); });
        PushSignal.newRow();   // the journal must never fail a transaction because of the pusher
    }
}
