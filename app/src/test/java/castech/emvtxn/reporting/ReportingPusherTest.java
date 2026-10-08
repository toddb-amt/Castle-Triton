package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** RPT-02: the drain loop against a fake portal and an in-memory journal. */
public class ReportingPusherTest {

    private static final long NOW = 1_760_000_000_000L;

    private MockWebServer server;
    private OkHttpClient http;
    private FakeStore store;
    private volatile String key = "test-key-not-real";
    private ReportingPusher pusher;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build();
        store = new FakeStore();
        pusher = new ReportingPusher(store, new ReportingClient(http),
                new ReportingPusher.Settings() {
                    @Override public String url() { return server.url("/transactions/addTransaction").toString(); }
                    @Override public String accessKey() { return key; }
                    @Override public PushPayload.Identity identity() {
                        return new PushPayload.Identity("MS00TEST", "0000195260000000", key, "EFX", "6.2.13");
                    }
                    @Override public TimeZone zone() { return TimeZone.getTimeZone("America/New_York"); }
                },
                () -> NOW);
    }

    @After
    public void tearDown() throws Exception {
        pusher.stop();
        http.dispatcher().executorService().shutdownNow();
        server.shutdown();
    }

    private static String ok(String message) {
        return "{\"success\":true,\"tsn\":\"0000195260000000\",\"message\":\"" + message
                + "\",\"host_response_code\":\"00\",\"host_response_isocode\":\"00\",\"error_result_code\":\"000\",\"extended_error_code\":\"0000\"}";
    }

    private static TransactionLog row(long id, int seq) {
        TransactionLog t = new TransactionLog();
        t.setId(id); t.setTransactionType("WITHDRAWAL"); t.setResult("APPROVED");
        t.setTimestamp(1_759_800_000_000L + id * 1000); t.setSequenceNumber(seq);
        t.setSaleCents(10_00); t.setAmountCents(10_00); t.setFeeCents(3_50); t.setTotalCents(13_50);
        t.setCardLastFour("1111"); t.setResponseCode("00"); t.setAccountType(20);
        t.setFlowId("FLOW-" + id); t.setPushState(PushEligibility.PUSH_PENDING);
        return t;
    }

    private String flowIdOf(RecordedRequest r) throws Exception {
        return new JSONObject(r.getBody().readUtf8()).getString("flow_id");
    }

    // ---- happy path -----------------------------------------------------------------

    @Test
    public void drainsPendingRowsOldestFirst_andMarksThemSentWithThePortalsMessage() throws Exception {
        store.add(row(2, 2)); store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.enqueue(new MockResponse().setBody(ok("Transaction merged successfully")));

        ReportingPusher.RunReport r = pusher.drainOnce();

        assertEquals(2, r.sent);
        RecordedRequest first = server.takeRequest();
        assertEquals("/transactions/addTransaction", first.getPath());
        assertEquals("test-key-not-real", first.getHeader("X-API-Key"));
        assertEquals("FLOW-1", flowIdOf(first));
        assertEquals("FLOW-2", flowIdOf(server.takeRequest()));
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
        assertEquals("Transaction ingested successfully", store.get(1).getPushMessage());
        assertEquals("Transaction merged successfully", store.get(2).getPushMessage());
        assertEquals(ReportingStatus.OK, r.state);
    }

    @Test
    public void alreadyExists_isDelivered_notAFailure() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction already exists")));
        assertEquals(1, pusher.drainOnce().sent);
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
    }

    // ---- nothing without a key --------------------------------------------------------

    @Test
    public void nothingIsSentWithoutAKey() throws Exception {
        key = "";
        store.add(row(1, 1));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.sent);
        assertEquals(0, server.getRequestCount());
        assertEquals(ReportingStatus.NOT_CONFIGURED, r.state);
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());   // kept
    }

    // ---- failures ----------------------------------------------------------------------

    @Test
    public void unauthorized_stopsTheRun_andReportsKeyRejected() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.KEY_REJECTED, r.state);
        assertEquals(1, server.getRequestCount());          // did not try row 2
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
        // Review I1: a bad key is never the row's fault — no attempt counted, no backoff
        assertEquals(0, store.get(1).getPushAttempts());
        assertEquals("Unauthorized", r.lastError);
    }

    @Test
    public void afterAKeyIsFixed_theHeadRowGoesFirst() throws Exception {
        // Review I1: the row that sat at the head during the bad-key period must not be in backoff
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"));
        pusher.drainOnce();
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.takeRequest();                                   // the 401 request
        ReportingPusher.RunReport r = pusher.drainOnce();       // key fixed in CasHUB → parameter-change run
        assertEquals(2, r.sent);
        assertEquals("FLOW-1", flowIdOf(server.takeRequest()));
        assertEquals("FLOW-2", flowIdOf(server.takeRequest()));
    }

    @Test
    public void anExceptionInsideARun_isReportedAndDoesNotKillLaterRuns() throws Exception {
        // Review I2: scheduleWithFixedDelay stops forever after one thrown exception; a URL that
        // passes the https:// prefix check but OkHttp rejects used to throw out of drainOnce.
        store.add(row(1, 1));
        ReportingPusher broken = new ReportingPusher(store, new ReportingClient(http),
                new ReportingPusher.Settings() {
                    @Override public String url() { return "https://bad host/transactions/addTransaction"; }
                    @Override public String accessKey() { return key; }
                    @Override public PushPayload.Identity identity() {
                        return new PushPayload.Identity("MS00TEST", "0000195260000000", key, "EFX", "6.2.13");
                    }
                    @Override public TimeZone zone() { return TimeZone.getTimeZone("America/New_York"); }
                },
                () -> NOW);
        ReportingPusher.RunReport r = broken.drainOnce();
        assertEquals(ReportingStatus.RETRYING, r.state);
        assertEquals(true, r.lastError.contains("IllegalArgumentException"));
        ReportingPusher.RunReport again = broken.drainOnce();   // the pusher is still alive
        assertEquals(ReportingStatus.RETRYING, again.state);
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
    }

    @Test
    public void aPoisonRow_isParked_whenALaterRowWasAcceptedInAnEarlierRun() throws Exception {
        // Review I3 / spec section 7: "failed 10+ times AND a row written after it has since been SENT"
        TransactionLog poison = row(1, 1); poison.setPushAttempts(10); store.add(poison);
        TransactionLog later = row(2, 2); later.setPushState(PushEligibility.PUSH_SENT); store.add(later);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Invalid amounts\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(1, r.parked);
        assertEquals(PushEligibility.PUSH_PARKED, store.get(1).getPushState());
        assertEquals("Invalid amounts", store.get(1).getPushLastError());
        assertEquals(0, store.countPending());
    }

    @Test
    public void serverError_backsOffAndStopsTheRun_rowStaysPending() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":\"Invalid amounts: all amounts must be non-negative\",\"host_response_code\":\"96\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.RETRYING, r.state);
        assertEquals(1, r.failed);
        assertEquals(1, server.getRequestCount());
        assertEquals("Invalid amounts: all amounts must be non-negative", store.get(1).getPushLastError());
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
    }

    @Test
    public void backoffSchedule_is5s_30s_2m_then5mCap() {
        assertEquals(5_000L, ReportingPusher.backoffMillis(1));
        assertEquals(30_000L, ReportingPusher.backoffMillis(2));
        assertEquals(120_000L, ReportingPusher.backoffMillis(3));
        assertEquals(300_000L, ReportingPusher.backoffMillis(4));
        assertEquals(300_000L, ReportingPusher.backoffMillis(50));
    }

    @Test
    public void aRowInBackoff_isSkippedUntilItsTime() throws Exception {
        TransactionLog r1 = row(1, 1); r1.setPushAttempts(1); store.add(r1);
        pusher.noteFailure(1L, NOW - 1_000);                 // failed 1 s ago; backoff after 1 attempt is 5 s
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.drainOnce();
        assertEquals(1, server.getRequestCount());           // only row 2 went
        assertEquals("FLOW-2", flowIdOf(server.takeRequest()));
    }

    @Test
    public void retryResendsTheIdenticalBody() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.drainOnce();
        pusher.forgetBackoff(1L);                             // backoff elapsed
        pusher.drainOnce();
        String a = server.takeRequest().getBody().readUtf8();
        String b = server.takeRequest().getBody().readUtf8();
        assertEquals(a, b);
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
    }

    // ---- parking -------------------------------------------------------------------------

    @Test
    public void allRowsFailingNeverParks() throws Exception {
        // Review focus 5: portal down (or terminal id wrong for every row) → retry forever, park nothing
        TransactionLog r1 = row(1, 1); r1.setPushAttempts(25); store.add(r1);
        TransactionLog r2 = row(2, 2); r2.setPushAttempts(25); store.add(r2);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"TermID required\"}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"TermID required\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.parked);
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
        assertEquals(PushEligibility.PUSH_PENDING, store.get(2).getPushState());
        assertEquals(ReportingStatus.RETRYING, r.state);
        assertEquals("TermID required", r.lastError);
    }

    @Test
    public void aRowThatKeepsFailingWhileALaterRowIsAccepted_isParked() throws Exception {
        TransactionLog bad = row(1, 1); bad.setPushAttempts(10); store.add(bad);
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Invalid amounts\"}"));   // row 1
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));              // row 2
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(1, r.sent);
        assertEquals(1, r.parked);
        assertEquals(PushEligibility.PUSH_PARKED, store.get(1).getPushState());
        assertEquals("Invalid amounts", store.get(1).getPushLastError());
        assertEquals(PushEligibility.PUSH_SENT, store.get(2).getPushState());
    }

    @Test
    public void aRowUnderTenFailures_isNotParked_evenIfLaterRowsWould() throws Exception {
        TransactionLog bad = row(1, 1); bad.setPushAttempts(3); store.add(bad);
        store.add(row(2, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"x\"}"));
        ReportingPusher.RunReport r = pusher.drainOnce();
        // under the threshold the run stops at the first failure (backoff) — row 2 is not tried this run
        assertEquals(0, r.parked);
        assertEquals(1, server.getRequestCount());
        assertEquals(PushEligibility.PUSH_PENDING, store.get(1).getPushState());
    }

    // ---- configuration lifecycle -----------------------------------------------------------

    @Test
    public void configIsReadOncePerRun() throws Exception {
        // Review focus 4: a key change mid-run does not affect the run in flight
        store.add(row(1, 1)); store.add(row(2, 2));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                key = "rotated-key";                      // rotates after the first request is seen
                return new MockResponse().setBody(ok("Transaction ingested successfully"));
            }
        });
        pusher.drainOnce();
        RecordedRequest r1 = server.takeRequest();
        RecordedRequest r2 = server.takeRequest();
        assertEquals("test-key-not-real", r1.getHeader("X-API-Key"));
        assertEquals(r1.getHeader("X-API-Key"), r2.getHeader("X-API-Key"));
    }

    @Test
    public void keyRemovedBetweenRuns_stopsSendingButKeepsRows() throws Exception {
        store.add(row(1, 1)); store.add(row(2, 2));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        server.enqueue(new MockResponse().setResponseCode(503));    // row 2 fails this run
        pusher.drainOnce();
        key = "";
        pusher.forgetBackoff(2L);
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(ReportingStatus.NOT_CONFIGURED, r.state);
        assertEquals(2, server.getRequestCount());                  // nothing more was sent
        assertEquals(PushEligibility.PUSH_PENDING, store.get(2).getPushState());   // nothing deleted
        assertEquals(1, store.countPending());
    }

    @Test
    public void requestRun_coalesces_andRunsOnTheExecutor() throws Exception {
        store.add(row(1, 1));
        server.enqueue(new MockResponse().setBody(ok("Transaction ingested successfully")));
        pusher.start(60_000L);
        pusher.requestRun(); pusher.requestRun(); pusher.requestRun();
        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("the executor must run the drain", first);
        Thread.sleep(300);
        assertEquals(1, server.getRequestCount());
        assertEquals(PushEligibility.PUSH_SENT, store.get(1).getPushState());
    }

    @Test
    public void emptyStore_isAQuietNoOp() throws Exception {
        ReportingPusher.RunReport r = pusher.drainOnce();
        assertEquals(0, r.sent + r.failed + r.parked);
        assertEquals(0, server.getRequestCount());
        assertEquals(ReportingStatus.OK, r.state);
    }

    // ---- fake store ---------------------------------------------------------------------------

    /** In-memory PushStore mirroring the journal's row semantics. */
    static final class FakeStore implements PushStore {
        final Map<Long, TransactionLog> rows = new HashMap<>();
        void add(TransactionLog t) { rows.put(t.getId(), t); }
        TransactionLog get(long id) { return rows.get(id); }
        @Override public List<TransactionLog> pendingPush(int limit) {
            List<TransactionLog> out = new ArrayList<>();
            for (TransactionLog t : rows.values()) if (t.getPushState() == PushEligibility.PUSH_PENDING) out.add(t);
            out.sort((a, b) -> a.getTimestamp() != b.getTimestamp()
                    ? Long.compare(a.getTimestamp(), b.getTimestamp()) : Long.compare(a.getId(), b.getId()));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
        @Override public boolean anySentAfter(long rowId) {
            for (TransactionLog t : rows.values()) if (t.getId() > rowId && t.getPushState() == PushEligibility.PUSH_SENT) return true;
            return false;
        }
        @Override public void markSent(long id, String message, long sentAt) {
            TransactionLog t = rows.get(id); t.setPushState(PushEligibility.PUSH_SENT); t.setPushMessage(message); t.setPushSentAt(sentAt);
        }
        @Override public void markFailed(long id, String error) {
            TransactionLog t = rows.get(id); t.setPushAttempts(t.getPushAttempts() + 1); t.setPushLastError(error);
        }
        @Override public void markParked(long id, String error) {
            TransactionLog t = rows.get(id); t.setPushState(PushEligibility.PUSH_PARKED); t.setPushLastError(error);
        }
        @Override public int countPending() { return pendingPush(Integer.MAX_VALUE).size(); }
        @Override public int countParked() {
            int n = 0; for (TransactionLog t : rows.values()) if (t.getPushState() == PushEligibility.PUSH_PARKED) n++; return n;
        }
    }
}
