package castech.emvtxn.atm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BatchMathTest {
    @Test public void firstBatchIsOne() { assertEquals(1, BatchMath.firstBatchId()); }
    @Test public void nextBatchId_onlyAdvancesOnClose() {
        assertEquals(2, BatchMath.nextBatchId(1));
        assertEquals(4, BatchMath.nextBatchId(3));
        assertEquals(1, BatchMath.nextBatchId(0));     // nothing yet → 1
        assertEquals(1, BatchMath.nextBatchId(-5));
    }
    @Test public void pruneKeepsTheLastNBatches() {
        assertEquals(1, BatchMath.oldestBatchToKeep(5, 20));
        assertEquals(6, BatchMath.oldestBatchToKeep(25, 20));
        assertEquals(1, BatchMath.oldestBatchToKeep(20, 20));
    }
}
