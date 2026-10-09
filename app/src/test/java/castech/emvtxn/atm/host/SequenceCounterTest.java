package castech.emvtxn.atm.host;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * SEQ-01 (6.2.14): the STD1 terminal sequence (Field 4, 0001–9999) must continue across app
 * restarts. It restarted at 1 on every start, so the day's rows after a restart repeated the
 * morning's numbers — and the portal, which keys duplicates on terminal id + sequence, silently
 * kept only the first of each pair (bench 2026-10-09). How sequences are assigned and sent is
 * unchanged: next value, +1, wrap 9999 → 1.
 */
public class SequenceCounterTest {

    /** A store that remembers what was saved, like SharedPreferences would. */
    private static final class MemoryStore implements SequenceCounter.Store {
        int value; int saves;
        MemoryStore(int initial) { value = initial; }
        @Override public int load() { return value; }
        @Override public void save(int next) { value = next; saves++; }
    }

    @Test
    public void aFreshTerminal_startsAtOne_andCounts() {
        SequenceCounter c = new SequenceCounter(new MemoryStore(0));
        assertEquals(1, c.next());
        assertEquals(2, c.next());
        assertEquals(3, c.next());
    }

    @Test
    public void afterARestart_itContinuesWhereItLeftOff() {
        MemoryStore store = new MemoryStore(0);
        SequenceCounter first = new SequenceCounter(store);
        first.next(); first.next(); first.next();            // 1, 2, 3 used
        SequenceCounter afterRestart = new SequenceCounter(store);
        assertEquals(4, afterRestart.next());
    }

    @Test
    public void everyNumberHandedOut_isPersistedBeforeItIsUsed() {
        MemoryStore store = new MemoryStore(0);
        SequenceCounter c = new SequenceCounter(store);
        c.next();                                             // 1 handed out → the store must already say "next is 2"
        assertEquals(2, store.value);
        assertEquals(1, store.saves);
    }

    @Test
    public void wrapsFrom9999ToOne_asBefore() {
        SequenceCounter c = new SequenceCounter(new MemoryStore(9999));
        assertEquals(9999, c.next());
        assertEquals(1, c.next());
    }

    @Test
    public void aCorruptStoredValue_isTreatedAsFresh() {
        assertEquals(1, new SequenceCounter(new MemoryStore(-5)).next());
        assertEquals(1, new SequenceCounter(new MemoryStore(10_000)).next());
    }

    @Test
    public void inMemory_behavesLikeBefore_forTests() {
        SequenceCounter c = SequenceCounter.inMemory();
        assertEquals(1, c.next());
        assertEquals(2, c.next());
    }
}
