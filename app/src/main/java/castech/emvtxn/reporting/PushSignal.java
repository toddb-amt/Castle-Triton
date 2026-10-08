package castech.emvtxn.reporting;

import java.util.concurrent.atomic.AtomicReference;

/** Decouples the journal (transaction thread) from the pusher: "a row was written, drain when you can". */
public final class PushSignal {
    private static final AtomicReference<Runnable> listener = new AtomicReference<>();
    private PushSignal() {}

    public static void setListener(Runnable r) { listener.set(r); }

    public static void newRow() {
        Runnable r = listener.get();
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) { /* the journal must never fail a transaction */ }
    }
}
