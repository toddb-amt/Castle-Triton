package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

/**
 * A pending record that a human resolves (with a reason) must survive as history
 * with that reason — never be silently dropped — and the record's last failure
 * detail must round-trip through persistence so the Admin screen can show it.
 */
public class ReversalRecordHistoryTest {

    private static ReversalPersistenceManager.PendingReversal pending() throws Exception {
        return ReversalPersistenceManager.PendingReversal.fromJson(new JSONObject(
                "{\"transactionId\":\"REV1\",\"terminalId\":\"TEST0001\",\"sequenceNumber\":7,"
              + "\"amountCents\":1000,\"surchargeCents\":350,\"status\":\"failed\",\"attemptCount\":5,"
              + "\"createdTime\":1790277096437,\"lastAttemptTime\":1790277200000,"
              + "\"lastError\":\"host responded 12 (invalid transaction)\"}"));
    }

    @Test
    public void lastErrorAndAttemptTiming_roundTrip() throws Exception {
        ReversalPersistenceManager.PendingReversal r = pending();
        JSONObject j = r.toJson();
        ReversalPersistenceManager.PendingReversal back = ReversalPersistenceManager.PendingReversal.fromJson(j);
        assertEquals("host responded 12 (invalid transaction)", back.getLastError());
        assertEquals(1790277200000L, back.getLastAttemptTime());
        assertEquals(5, back.getAttemptCount());
        assertEquals("failed", back.getStatus());
    }

    @Test
    public void resolvedHistoryEntry_keepsReasonAndWhoAndAmount() throws Exception {
        ReversalPersistenceManager.CompletedReversal c =
                ReversalPersistenceManager.CompletedReversal.resolved(pending(),
                        "processor confirmed original declined", "SUPER");
        JSONObject j = c.toJson();
        ReversalPersistenceManager.CompletedReversal back = ReversalPersistenceManager.CompletedReversal.fromJson(j);
        assertEquals("REV1", back.getTransactionId());
        assertEquals(1000L, back.getAmountCents());
        assertEquals("processor confirmed original declined", back.getResolution());
        assertEquals("SUPER", back.getResolvedBy());
        assertFalse(back.isSuccess());          // resolved != sent to the host
        assertEquals(5, back.getAttempts());
    }

    @Test
    public void completedEntry_fromDrainSuccess_hasNoResolution() throws Exception {
        ReversalPersistenceManager.CompletedReversal c =
                ReversalPersistenceManager.CompletedReversal.completed(pending(), true);
        ReversalPersistenceManager.CompletedReversal back = ReversalPersistenceManager.CompletedReversal.fromJson(c.toJson());
        assertEquals(true, back.isSuccess());
        assertEquals("", back.getResolution());
    }
}
