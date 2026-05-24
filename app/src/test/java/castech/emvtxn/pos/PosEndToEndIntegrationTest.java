package castech.emvtxn.pos;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end integration test: wires up PosConnectionClient + PosCommandDispatcher
 * + PosTransactionExecutor + a fake gateway, points at a MockWebServer that
 * impersonates the proxy, and verifies the full request/response round trip
 * for each supported command type.
 *
 * <p>This is the canonical test proving the wire contract in
 * docs/CASTLE_POS_INTEGRATION_SPEC.md. If you change a field name or
 * envelope shape, this test fails first.
 *
 * <p>The PosOrchestrator class is bypassed in favor of manual wiring so we
 * can substitute a fake gateway (PosOrchestrator owns the AtmHostServiceGateway
 * which requires an actual AtmHostService).
 */
public class PosEndToEndIntegrationTest {

    private static final long[] FAST_BACKOFF = {10L, 20L, 40L};

    private MockWebServer server;
    private OkHttpClient httpClient;
    private FakePosConfig config;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        config = new FakePosConfig();
        config.proxyBaseUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        config.terminalAccessKey = "test-access-key";
        // Pre-cache a valid JWT so we don't need a registration endpoint for these tests
        config.setJwt("test-jwt-token", System.currentTimeMillis() + 3_600_000L);
        httpClient = new OkHttpClient.Builder().build();
    }

    @After
    public void tearDown() throws Exception {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
        server.shutdown();
    }

    /**
     * Wraps a server-side listener so close-handshake completes cleanly.
     * Without this MockWebServer's shutdown hangs.
     */
    private static WebSocketListener completing(WebSocketListener inner) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, okhttp3.Response r) { inner.onOpen(ws, r); }
            @Override public void onMessage(WebSocket ws, String t) { inner.onMessage(ws, t); }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
                inner.onClosing(ws, code, reason);
            }
        };
    }

    // ---- info command --------------------------------------------------------

    @Test
    public void proxySendsInfo_terminalRespondsWithCapabilitiesAndState() throws Exception {
        ProxyHarness harness = new ProxyHarness();
        FakeGateway gateway = new FakeGateway();
        PosTransactionExecutor executor = new PosTransactionExecutor(gateway, harness::send);
        PosCommandDispatcher.InfoProvider infoProvider = new PosCommandDispatcher.InfoProvider() {
            @Override public JSONObject buildInfoResponse() {
                JSONObject info = new JSONObject();
                try {
                    info.put("app_version", "6.1");
                    info.put("device_model", "S1F4 PRO");
                    info.put("tsn", "TSN-001");
                    info.put("state", "connected");
                    info.put("ready", true);
                    info.put("not_ready_reason", "");
                    info.put("pending_reversals", 0);
                } catch (Exception ignored) {}
                return info;
            }
            @Override public int getPendingReversalCount() { return 0; }
        };
        PosCommandDispatcher dispatcher = new PosCommandDispatcher(harness::send, executor, infoProvider);

        bootClient(harness, dispatcher);
        harness.waitConnected();

        // Proxy sends info request
        PosEnvelope infoRequest = PosEnvelope.request(new JSONObject().put("type", PosWire.RES_INFO));
        harness.serverSocket.get().send(PosEnvelopeCodec.encode(infoRequest));

        PosEnvelope response = harness.awaitInbound();
        assertNotNull(response);
        assertEquals(PosEnvelope.TYPE_RESPONSE, response.getType());
        assertEquals(infoRequest.getFlowId(), response.getFlowId());
        assertNull(response.getError());

        JSONObject info = response.getResource();
        assertEquals("6.1", info.getString("app_version"));
        assertEquals("S1F4 PRO", info.getString("device_model"));
        assertEquals("connected", info.getString("state"));
        assertTrue(info.getBoolean("ready"));
        // Capabilities overlaid by dispatcher from PosRegistrationClient.SUPPORTED_CAPABILITIES
        assertTrue("capabilities must include sale",
                jsonArrayContains(info.getJSONArray(PosWire.REG_CAPABILITIES), PosWire.RES_SALE));
        assertFalse("capabilities must not include refund",
                jsonArrayContains(info.getJSONArray(PosWire.REG_CAPABILITIES), PosWire.RES_REFUND));
    }

    // ---- approved sale -------------------------------------------------------

    @Test
    public void proxySendsSale_gatewayApproves_terminalRespondsApproved() throws Exception {
        ProxyHarness harness = new ProxyHarness();
        FakeGateway gateway = new FakeGateway();
        gateway.nextSaleHandler = (cb) -> cb.onApproved(new PosTerminalGateway.TransactionResult(
                "00", "RRN-77", "AUTH-2", "2026/05/21", "14:30:00",
                15_000L, 14_500L, "APPROVED"));
        PosTransactionExecutor executor = new PosTransactionExecutor(gateway, harness::send);
        PosCommandDispatcher dispatcher = new PosCommandDispatcher(harness::send, executor, stubInfo());

        bootClient(harness, dispatcher);
        harness.waitConnected();

        PosEnvelope saleReq = PosEnvelope.request(new JSONObject()
                .put("type", PosWire.RES_SALE)
                .put(PosWire.TXN_AMOUNT, 5_000)
                .put(PosWire.TXN_SURCHARGE, 250)
                .put(PosWire.TXN_ACCOUNT_TYPE, "checking"));
        harness.serverSocket.get().send(PosEnvelopeCodec.encode(saleReq));

        PosEnvelope resp = harness.awaitInbound();
        assertEquals(saleReq.getFlowId(), resp.getFlowId());
        assertNull(resp.getError());
        assertEquals("approved", resp.getResource().getString(PosWire.RSP_STATUS));
        assertEquals("RRN-77",  resp.getResource().getString(PosWire.RSP_REFERENCE_NUMBER));
        assertEquals(15_000L,   resp.getResource().getLong(PosWire.RSP_ACCOUNT_BALANCE_CENTS));

        // Gateway saw the right inputs
        assertEquals(1, gateway.saleCalls.size());
        FakeGateway.SaleInvocation inv = gateway.saleCalls.get(0);
        assertEquals(5_000L, inv.amountCents);
        assertEquals(250L,   inv.surchargeCents);
        assertEquals("checking", inv.accountType);
    }

    // ---- declined sale -------------------------------------------------------

    @Test
    public void proxySendsSale_gatewayDeclines_terminalRespondsDeclined() throws Exception {
        ProxyHarness harness = new ProxyHarness();
        FakeGateway gateway = new FakeGateway();
        gateway.nextSaleHandler = (cb) -> cb.onDeclined("51", "INSUFFICIENT FUNDS", false);
        PosCommandDispatcher dispatcher = new PosCommandDispatcher(harness::send,
                new PosTransactionExecutor(gateway, harness::send), stubInfo());

        bootClient(harness, dispatcher);
        harness.waitConnected();

        PosEnvelope saleReq = PosEnvelope.request(new JSONObject()
                .put("type", PosWire.RES_SALE)
                .put(PosWire.TXN_AMOUNT, 5_000)
                .put(PosWire.TXN_ACCOUNT_TYPE, "checking"));
        harness.serverSocket.get().send(PosEnvelopeCodec.encode(saleReq));

        PosEnvelope resp = harness.awaitInbound();
        assertEquals("declined", resp.getResource().getString(PosWire.RSP_STATUS));
        assertEquals("51", resp.getResource().getString(PosWire.RSP_RESPONSE_CODE));
        assertFalse(resp.getResource().getBoolean(PosWire.RSP_RETAIN_CARD));
    }

    // ---- not-supported command ----------------------------------------------

    @Test
    public void proxySendsRefund_terminalRespondsNotSupported() throws Exception {
        ProxyHarness harness = new ProxyHarness();
        PosCommandDispatcher dispatcher = new PosCommandDispatcher(harness::send,
                new PosTransactionExecutor(new FakeGateway(), harness::send), stubInfo());

        bootClient(harness, dispatcher);
        harness.waitConnected();

        PosEnvelope refund = PosEnvelope.request(new JSONObject().put("type", PosWire.RES_REFUND));
        harness.serverSocket.get().send(PosEnvelopeCodec.encode(refund));

        PosEnvelope resp = harness.awaitInbound();
        assertEquals(refund.getFlowId(), resp.getFlowId());
        assertTrue(resp.hasError());
        assertEquals(PosWire.ERR_NOT_SUPPORTED,
                resp.getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- heartbeat is sent on schedule --------------------------------------

    @Test
    public void terminalSendsHeartbeatEvent() throws Exception {
        ProxyHarness harness = new ProxyHarness();
        PosCommandDispatcher dispatcher = new PosCommandDispatcher(harness::send,
                new PosTransactionExecutor(new FakeGateway(), harness::send), stubInfo());

        // Build a connection client with very fast heartbeat
        PosConnectionClient.ConnectionListener listener = new PosConnectionClient.ConnectionListener() {
            @Override public void onConnected() { harness.connected.countDown(); }
            @Override public void onDisconnected(String reason) {}
            @Override public void onEnvelope(PosEnvelope env) {
                if (env.isRequest()) dispatcher.dispatch(env);
            }
            @Override public void onTerminalError(String message) {}
        };
        harness.server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, okhttp3.Response r) { harness.serverSocket.set(ws); }
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    PosEnvelope env = PosEnvelopeCodec.decode(text);
                    if (env.isEvent()) harness.inboundOnServer.set(env);
                    harness.serverGotMessage.countDown();
                } catch (Exception ignored) {}
            }
        })));
        // Sneaky: stage server enqueue inside our harness server.
        // (We're using harness.server which is the same as our @Before's `server`.)
        PosConnectionClient client = buildClientWithHeartbeat(listener, /* heartbeat ms */ 100);
        harness.clientToStop = client;
        client.start();
        harness.waitConnected();

        // Wait long enough for at least one heartbeat to fire
        boolean got = harness.serverGotMessage.await(2, TimeUnit.SECONDS);
        assertTrue("expected heartbeat event within 2s", got);
        PosEnvelope hb = harness.inboundOnServer.get();
        assertNotNull(hb);
        assertEquals(PosEnvelope.TYPE_EVENT, hb.getType());
        assertEquals(PosWire.EVT_HEARTBEAT, hb.getResource().getString("type"));
    }

    // ---- helpers -------------------------------------------------------------

    private void bootClient(ProxyHarness harness, PosCommandDispatcher dispatcher) {
        PosConnectionClient.ConnectionListener listener = new PosConnectionClient.ConnectionListener() {
            @Override public void onConnected() { harness.connected.countDown(); }
            @Override public void onDisconnected(String reason) {}
            @Override public void onEnvelope(PosEnvelope env) {
                if (env.isRequest()) dispatcher.dispatch(env);
            }
            @Override public void onTerminalError(String message) {}
        };
        harness.server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, okhttp3.Response r) { harness.serverSocket.set(ws); }
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    PosEnvelope env = PosEnvelopeCodec.decode(text);
                    harness.inboundOnServer.set(env);
                    harness.serverGotMessage.countDown();
                } catch (Exception ignored) {}
            }
        })));
        PosConnectionClient client = new PosConnectionClient(config,
                cb -> cb.onRegistered("test-jwt-token",
                        System.currentTimeMillis() + 3_600_000L,
                        config.proxyBaseUrl + PosWire.PATH_CONNECT, 30),
                listener, httpClient, FAST_BACKOFF);
        harness.clientToStop = client;
        client.start();
    }

    private PosConnectionClient buildClientWithHeartbeat(PosConnectionClient.ConnectionListener l,
                                                          long heartbeatMillis) throws Exception {
        // Override heartbeat by injecting a tiny one via reflection on the
        // public default — we don't have a setter, so use a clone of the
        // default class with the field overridden.
        // Simpler: just build with default backoff and accept that the heartbeat
        // is 30s. This test is best-effort: skip if heartbeat doesn't fire in time.
        // Actually for the test to be deterministic, we DO need a faster heartbeat.
        // Use reflection on the volatile field.
        PosConnectionClient client = new PosConnectionClient(config,
                cb -> cb.onRegistered("test-jwt-token",
                        System.currentTimeMillis() + 3_600_000L,
                        config.proxyBaseUrl + PosWire.PATH_CONNECT, 30),
                l, httpClient, FAST_BACKOFF);
        java.lang.reflect.Field f = PosConnectionClient.class.getDeclaredField("heartbeatIntervalMillis");
        f.setAccessible(true);
        f.set(client, heartbeatMillis);
        return client;
    }

    private static boolean jsonArrayContains(org.json.JSONArray arr, String needle) {
        for (int i = 0; i < arr.length(); i++) {
            if (needle.equals(arr.optString(i))) return true;
        }
        return false;
    }

    private static PosCommandDispatcher.InfoProvider stubInfo() {
        return new PosCommandDispatcher.InfoProvider() {
            @Override public JSONObject buildInfoResponse() { return new JSONObject(); }
            @Override public int getPendingReversalCount() { return 0; }
        };
    }

    /**
     * Shared harness: tracks the server-side socket, what the server received,
     * and the response the dispatcher built and sent back.
     */
    private final class ProxyHarness {
        final MockWebServer server = PosEndToEndIntegrationTest.this.server;
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<WebSocket> serverSocket = new AtomicReference<>();
        final CountDownLatch serverGotMessage = new CountDownLatch(1);
        final AtomicReference<PosEnvelope> inboundOnServer = new AtomicReference<>();
        PosConnectionClient clientToStop;

        /** ResponseSender impl — what the dispatcher uses to push responses to the proxy. */
        boolean send(PosEnvelope env) {
            if (clientToStop == null) return false;
            return clientToStop.sendEnvelope(env);
        }

        void waitConnected() throws InterruptedException {
            assertTrue("client did not connect within 2s", connected.await(2, TimeUnit.SECONDS));
            assertNotNull("server-side socket not captured", serverSocket.get());
        }

        /** Block until the server receives a frame; return the decoded envelope. */
        PosEnvelope awaitInbound() throws Exception {
            assertTrue("server did not receive a frame within 2s",
                    serverGotMessage.await(2, TimeUnit.SECONDS));
            PosEnvelope env = inboundOnServer.get();
            assertNotNull(env);
            return env;
        }
    }

    @After
    public void stopClient() {
        // nothing — harness's clientToStop is captured per test; httpClient + server
        // are shut down in the top-level tearDown which evicts pooled connections.
    }

    /** Programmable in-memory gateway. */
    private static final class FakeGateway implements PosTerminalGateway {
        boolean ready = true;
        SaleHandler nextSaleHandler = (cb) -> {};
        java.util.List<SaleInvocation> saleCalls = new java.util.ArrayList<>();

        @Override public boolean isReady() { return ready; }
        @Override public String getNotReadyReason() { return ""; }
        @Override public int getPendingReversalCount() { return 0; }
        @Override public void startSale(long a, long s, String t, TransactionCallback cb) {
            saleCalls.add(new SaleInvocation(a, s, t));
            nextSaleHandler.respond(cb);
        }
        @Override public void startBalanceInquiry(String t, TransactionCallback cb) {}
        @Override public void startReversal(String reason, OperationCallback cb) {}
        @Override public void startSettlement(boolean reset, SettlementCallback cb) {}

        static final class SaleInvocation {
            final long amountCents, surchargeCents;
            final String accountType;
            SaleInvocation(long a, long s, String t) { amountCents = a; surchargeCents = s; accountType = t; }
        }
        interface SaleHandler { void respond(TransactionCallback cb); }
    }

    private static final class FakePosConfig extends PosConfig {
        String proxyBaseUrl = "";
        String terminalAccessKey = "";
        String jwt = "";
        long jwtExpiresAt = 0L;
        FakePosConfig() { super(new FakeContext()); }
        @Override public String getProxyBaseUrl() { return proxyBaseUrl; }
        @Override public String getTerminalAccessKey() { return terminalAccessKey; }
        @Override public String getJwt() { return jwt; }
        @Override public long getJwtExpiresAtMillis() { return jwtExpiresAt; }
        @Override public void setJwt(String j, long e) { jwt = j; jwtExpiresAt = e; }
        @Override public void clearJwt() { jwt = ""; jwtExpiresAt = 0L; }
        @Override public boolean isJwtExpired() {
            return jwt.isEmpty() || System.currentTimeMillis() + JWT_RENEWAL_MARGIN_MILLIS >= jwtExpiresAt;
        }
    }

    private static final class FakeContext extends android.content.ContextWrapper {
        FakeContext() { super(null); }
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    }
}
