package castech.emvtxn.atm;

/** Batch counter rules (terminal-owned, decision 2 of the spec). Pure. */
public final class BatchMath {
    private BatchMath() {}
    public static final int KEEP_BATCHES = 20;
    public static int firstBatchId() { return 1; }
    public static int nextBatchId(int current) { return current <= 0 ? 1 : current + 1; }
    public static long oldestBatchToKeep(int currentBatchId, int keep) { return Math.max(1, (long) currentBatchId - keep + 1); }
}
