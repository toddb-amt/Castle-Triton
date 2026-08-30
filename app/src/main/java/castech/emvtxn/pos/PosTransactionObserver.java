package castech.emvtxn.pos;

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static registry for the single in-flight POS transaction's result callback.
 * Used as a small hook into MainActivity's existing {@code atmTransactionEventListener}
 * so POS-driven transactions can receive results without duplicating the listener
 * or restructuring MainActivity.
 *
 * <p><b>Lifecycle (per POS sale / balance_inquiry):</b>
 * <ol>
 *   <li>{@link AtmHostServiceGateway} calls {@link #arm} with a {@link Callback}
 *       before navigating to the transaction page.</li>
 *   <li>The existing card-read + host-call flow runs unchanged.</li>
 *   <li>MainActivity's {@code atmTransactionEventListener} fires {@code onTransactionApproved},
 *       {@code onTransactionDeclined}, {@code onBalanceReceived}, or {@code onError}.</li>
 *   <li>At the end of each of those methods, MainActivity calls
 *       {@link #notifyApproved} / {@link #notifyDeclined} / {@link #notifyError}.</li>
 *   <li>This class fires the active callback exactly once, then auto-clears.</li>
 * </ol>
 *
 * <p>Terminals process one transaction at a time, so a single global slot is
 * sufficient — no need for per-flow correlation here (correlation is at the
 * envelope layer above).
 */
public final class PosTransactionObserver {

    private static final String TAG = "PosTransactionObserver";

    private static final AtomicReference<Callback> active = new AtomicReference<>();

    /**
     * Watchdog: releases a wedged slot if no result arrives within this window.
     * Sized to comfortably outlast the slowest legitimate transaction (the host
     * transaction latch waits up to 130s on the busy-MUX path, plus card/PIN
     * entry time). This is slot RECOVERY, not caller feedback — the proxy
     * abandons the flow after its own 90s window and safely discards late
     * frames; the point is that one wedged transaction must never require an
     * app restart (or a site visit) before the next POS command can run.
     */
    static final long WATCHDOG_MILLIS = 180_000L;

    private static final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PosTxnObserver-watchdog");
                t.setDaemon(true);
                return t;
            });

    private PosTransactionObserver() {}

    /**
     * Arms the observer with a callback. Returns the previously-armed callback
     * (or null) so callers can verify nothing was overwritten. A watchdog
     * releases the slot (firing {@code onError} on the armed callback) if no
     * result arrives within {@link #WATCHDOG_MILLIS}.
     */
    public static Callback arm(Callback callback) {
        return arm(callback, WATCHDOG_MILLIS);
    }

    /** Test seam: arm with a custom watchdog window. */
    static Callback arm(Callback callback, long watchdogMillis) {
        Callback prev = active.getAndSet(callback);
        if (prev != null) {
            Log.w(TAG, "arm() overwrote an active callback — possible txn overlap");
        }
        scheduleWatchdog(callback, watchdogMillis);
        return prev;
    }

    private static void scheduleWatchdog(final Callback armed, final long delayMillis) {
        watchdog.schedule(new Runnable() {
            @Override
            public void run() {
                // Only fires when the SAME transaction is still armed — if the
                // transaction completed, was cancelled, or a new one was armed,
                // the CAS fails and this is a no-op.
                if (active.compareAndSet(armed, null)) {
                    Log.w(TAG, "watchdog: POS transaction produced no result in "
                            + delayMillis + "ms — releasing slot");
                    try {
                        armed.onError("watchdog: no result within "
                                + (delayMillis / 1000) + "s — slot released");
                    } catch (Throwable t) {
                        Log.e(TAG, "watchdog callback threw: " + t.getMessage(), t);
                    }
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Explicitly clear the active callback without firing it. */
    public static void clear() {
        active.set(null);
    }

    /** Returns true if a callback is currently armed. */
    public static boolean isArmed() {
        return active.get() != null;
    }

    // ---- Notify (called from MainActivity's atmTransactionEventListener) ------

    public static void notifyApproved(String responseCode, String referenceNumber,
                                       String authDate, String authTime,
                                       long accountBalanceCents, long availableBalanceCents,
                                       String displayMessage) {
        Callback cb = active.getAndSet(null);
        if (cb == null) return;
        try {
            cb.onApproved(responseCode, referenceNumber, authDate, authTime,
                    accountBalanceCents, availableBalanceCents, displayMessage);
        } catch (Throwable t) {
            Log.e(TAG, "callback onApproved threw: " + t.getMessage(), t);
        }
    }

    public static void notifyDeclined(String responseCode, String responseMessage, boolean retainCard) {
        Callback cb = active.getAndSet(null);
        if (cb == null) return;
        try {
            cb.onDeclined(responseCode, responseMessage, retainCard);
        } catch (Throwable t) {
            Log.e(TAG, "callback onDeclined threw: " + t.getMessage(), t);
        }
    }

    public static void notifyError(String error) {
        Callback cb = active.getAndSet(null);
        if (cb == null) return;
        try {
            cb.onError(error);
        } catch (Throwable t) {
            Log.e(TAG, "callback onError threw: " + t.getMessage(), t);
        }
    }

    // ---- Callback contract ----------------------------------------------------

    public interface Callback {
        void onApproved(String responseCode, String referenceNumber,
                        String authDate, String authTime,
                        long accountBalanceCents, long availableBalanceCents,
                        String displayMessage);
        void onDeclined(String responseCode, String responseMessage, boolean retainCard);
        void onError(String error);
    }
}
