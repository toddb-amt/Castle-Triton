package castech.emvtxn.atm.host;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The STD1 terminal sequence number (Field 4, 0001–9999), continued across app restarts (SEQ-01,
 * 6.2.14). Assignment is unchanged — next value, +1, wrap 9999 → 1 — only the memory is durable:
 * the number AFTER the one handed out is saved before the caller can use it, so a restart (or a
 * crash) can never hand the same number out twice. Thread-safe.
 */
public final class SequenceCounter {

    /** Where the next number lives between runs. */
    public interface Store {
        /** The next number to hand out; anything outside 1–9999 means "fresh". */
        int load();
        void save(int next);
    }

    private static final String PREFS = "atm_sequence";
    private static final String KEY_NEXT = "next";

    private final Store store;
    private int next;

    public SequenceCounter(Store store) {
        this.store = store;
        int loaded = store.load();
        this.next = loaded >= 1 && loaded <= 9999 ? loaded : 1;
    }

    /** Hands out the next number and persists its successor first. */
    public synchronized int next() {
        int n = next;
        next = n >= 9999 ? 1 : n + 1;
        store.save(next);
        return n;
    }

    /** Backed by app-private preferences (survives restarts and reinstalls over the same package). */
    public static SequenceCounter persistent(Context ctx) {
        final SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new SequenceCounter(new Store() {
            @Override public int load() { return p.getInt(KEY_NEXT, 0); }
            @Override public void save(int next) { p.edit().putInt(KEY_NEXT, next).commit(); }   // commit: durable before use
        });
    }

    /** The pre-6.2.14 behaviour (restarts at 1); for tests and tools without a Context. */
    public static SequenceCounter inMemory() {
        return new SequenceCounter(new Store() {
            int v = 0;
            @Override public int load() { return v; }
            @Override public void save(int next) { v = next; }
        });
    }
}
