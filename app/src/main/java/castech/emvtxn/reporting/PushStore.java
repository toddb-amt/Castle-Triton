package castech.emvtxn.reporting;

import java.util.List;

import castech.emvtxn.atm.TransactionLog;

/** What the pusher needs from the journal. Implemented by TransactionLogManager; faked in tests. */
public interface PushStore {
    /** PENDING rows, oldest first (timestamp, then id). */
    List<TransactionLog> pendingPush(int limit);
    /** True when a row with a greater id has state SENT — proof the portal accepted something newer. */
    boolean anySentAfter(long rowId);
    void markSent(long rowId, String message, long sentAt);
    void markFailed(long rowId, String error);
    void markParked(long rowId, String error);
    int countPending();
    int countParked();
}
