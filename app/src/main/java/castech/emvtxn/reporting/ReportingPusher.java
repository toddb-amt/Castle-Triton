package castech.emvtxn.reporting;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import castech.emvtxn.atm.TransactionLog;

/**
 * Drains PENDING journal rows to the portal (RPT-02, 6.2.13). One background thread; never the
 * transaction thread; never the SDK. The core ({@link #drainOnce()}) is synchronous and tested
 * against a fake portal; {@link #requestRun()} coalesces triggers onto the executor.
 *
 * <p>Per run: read the settings ONCE; stop at once when there is no key; send rows oldest first.
 * 200 → SENT. 401 → stop, KEY_REJECTED. Anything else → attempts++, back off (5 s, 30 s, 2 min,
 * 5 min cap) and stop the run — unless the row has failed 10+ times already, in which case skip it
 * and try the next row; if a later row is then accepted, park the skipped one (the portal is up,
 * that payload is the problem). While the portal is down everything fails and nothing is parked.
 *
 * <p>Backoff bookkeeping (row → last failure time) is in memory: after a restart every pending row
 * gets one immediate try, which is what we want.
 */
public final class ReportingPusher {

    public interface Settings {
        String url();
        String accessKey();
        PushPayload.Identity identity();
        TimeZone zone();
    }
    public interface Clock { long now(); }

    /** Listener for status changes (Admin line, log). */
    public interface StatusListener { void onStatus(String state, int pending, int parked, long lastSentAt, String lastError); }

    public static final class RunReport {
        public int sent, failed, parked;
        public String state = ReportingStatus.OK;
        public String lastError = "";
    }

    static final int PARK_AFTER_FAILURES = 10;
    static final int BATCH = 50;
    private static final long[] BACKOFF_MS = { 5_000L, 30_000L, 120_000L, 300_000L };

    private final PushStore store;
    private final ReportingClient client;
    private final Settings settings;
    private final Clock clock;
    private final Map<Long, Long> lastFailureAt = new ConcurrentHashMap<>();
    private final AtomicBoolean runRequested = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> sweep;
    private volatile StatusListener statusListener;
    private volatile long lastSentAt = 0L;

    public ReportingPusher(PushStore store, ReportingClient client, Settings settings, Clock clock) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.clock = clock;
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    /** Backoff after the n-th consecutive failure of a row (n >= 1). */
    public static long backoffMillis(int attempts) {
        if (attempts <= 0) return 0L;
        return BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1];
    }

    // ---- test seams for the in-memory backoff map ----------------------------------------
    void noteFailure(long rowId, long at) { lastFailureAt.put(rowId, at); }
    void forgetBackoff(long rowId) { lastFailureAt.remove(rowId); }

    /** One synchronous drain. Safe to call from any thread; runs never overlap. */
    public RunReport drainOnce() {
        RunReport report = new RunReport();
        if (!running.compareAndSet(false, true)) return report;
        try {
            // Settings are read once per run: a key rotated mid-run does not affect the run in flight.
            String key = settings.accessKey();
            String url = settings.url();
            PushPayload.Identity id = settings.identity();
            TimeZone zone = settings.zone();
            if (key == null || key.isEmpty()) {
                report.state = ReportingStatus.NOT_CONFIGURED;
                publish(report);
                return report;
            }
            long now = clock.now();
            List<TransactionLog> pending = store.pendingPush(BATCH);
            Long skippedCandidate = null;   // a 10+ failure row skipped this run, waiting for proof the portal is up
            String skippedError = null;
            for (TransactionLog row : pending) {
                Long failedAt = lastFailureAt.get(row.getId());
                if (failedAt != null && now - failedAt < backoffMillis(row.getPushAttempts())) continue;   // still backing off

                JSONObject body = PushPayload.of(row, id, zone);
                ReportingClient.Result res = client.post(url, key, body);
                if (res.accepted()) {
                    store.markSent(row.getId(), res.message, now);
                    lastFailureAt.remove(row.getId());
                    lastSentAt = now;
                    report.sent++;
                    if (skippedCandidate != null) {
                        store.markParked(skippedCandidate, skippedError);
                        report.parked++;
                        skippedCandidate = null;
                    }
                    continue;
                }
                int attemptsBefore = row.getPushAttempts();
                store.markFailed(row.getId(), res.error);
                lastFailureAt.put(row.getId(), now);
                report.failed++;
                report.lastError = res.error;
                if (res.unauthorized()) {
                    report.state = ReportingStatus.KEY_REJECTED;
                    publish(report);
                    return report;
                }
                if (attemptsBefore + 1 >= PARK_AFTER_FAILURES && skippedCandidate == null) {
                    skippedCandidate = row.getId();      // give the next row a chance to prove the portal is up
                    skippedError = res.error;
                    continue;
                }
                report.state = ReportingStatus.RETRYING;
                publish(report);
                return report;                             // back off; the next trigger or sweep resumes
            }
            report.state = skippedCandidate != null ? ReportingStatus.RETRYING : ReportingStatus.OK;
            publish(report);
            return report;
        } finally {
            running.set(false);
            if (runRequested.getAndSet(false) && executor != null) schedule(0);
        }
    }

    /** Coalescing trigger: at most one queued run beyond the one in flight. */
    public void requestRun() {
        if (executor == null) return;
        if (running.get()) { runRequested.set(true); return; }
        if (runRequested.compareAndSet(false, true)) schedule(0);
    }

    private void schedule(long delayMs) {
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        try {
            ex.schedule(() -> { runRequested.set(false); drainOnce(); }, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) { /* shutting down */ }
    }

    /** Starts the executor and the periodic sweep. */
    public synchronized void start(long sweepIntervalMs) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReportingPusher");
            t.setDaemon(true);
            return t;
        });
        sweep = executor.scheduleWithFixedDelay(this::drainOnce, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (sweep != null) sweep.cancel(false);
        if (executor != null) executor.shutdownNow();
        executor = null;
        sweep = null;
    }

    private void publish(RunReport r) {
        StatusListener l = statusListener;
        if (l == null) return;
        try {
            l.onStatus(r.state, store.countPending(), store.countParked(), lastSentAt, r.lastError);
        } catch (Throwable ignored) { /* status is best effort */ }
    }
}
