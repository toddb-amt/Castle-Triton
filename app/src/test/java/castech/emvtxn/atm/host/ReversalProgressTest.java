package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Reversal progress messages travel through the generic onProgress channel.
 * Two kinds must be told apart: drain-level messages (about the queue — go to the
 * banner/admin, never onto a customer's receipt) and record-level messages (about
 * one transaction — go onto the receipt only when it is THIS customer's transaction).
 * The field incident printed another record's "Reversal in progress" on a
 * customer's receipt because the two were not distinguished.
 */
public class ReversalProgressTest {

    @Test
    public void drainMessage_isDrainKind() {
        ReversalProgress p = ReversalProgress.parse(ReversalProgress.drain("Reversal pending — service required"));
        assertEquals(ReversalProgress.Kind.DRAIN, p.kind);
        assertEquals("Reversal pending — service required", p.body);
        assertNull(p.transactionId);
    }

    @Test
    public void recordMessage_carriesItsTransactionId() {
        ReversalProgress p = ReversalProgress.parse(ReversalProgress.record("REV202609181514488932", "Reversal in progress…"));
        assertEquals(ReversalProgress.Kind.RECORD, p.kind);
        assertEquals("REV202609181514488932", p.transactionId);
        assertEquals("Reversal in progress…", p.body);
    }

    @Test
    public void recordMessage_appliesToReceiptOnlyForTheCurrentTransaction() {
        ReversalProgress p = ReversalProgress.parse(ReversalProgress.record("REV1", "Reversal approved"));
        assertEquals(true,  p.appliesToReceipt("REV1"));
        assertEquals(false, p.appliesToReceipt("REV2"));
        assertEquals(false, p.appliesToReceipt(null));
        assertEquals(false, ReversalProgress.parse(ReversalProgress.drain("x")).appliesToReceipt("REV1"));
    }

    @Test
    public void legacyAndOrdinaryMessages_areOther() {
        assertEquals(ReversalProgress.Kind.OTHER, ReversalProgress.parse("Connecting to host...").kind);
        assertEquals(ReversalProgress.Kind.OTHER, ReversalProgress.parse(null).kind);
        // The pre-6.2.7 prefix with no id is a drain-level message, never receipt-bound.
        assertEquals(ReversalProgress.Kind.DRAIN, ReversalProgress.parse("[REVERSAL] Reversal in progress…").kind);
    }
}
