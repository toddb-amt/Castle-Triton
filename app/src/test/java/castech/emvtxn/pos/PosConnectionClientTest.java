package castech.emvtxn.pos;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PosConnectionClientTest {

    /** Fast backoff so tests don't wait seconds — 10ms, 20ms, 40ms. */
    private static final long[] FAST_BACKOFF = {10L, 20L, 40L};

    private MockWebServer server;
    private FakePosConfig config;
    private OkHttpClient httpClient;
    private List<PosConnectionClient> clientsToStop;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        config = new FakePosConfig();
        config.proxyBaseUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        config.terminalAccessKey = "test-key";
        config.setJwt("test-jwt-token", System.currentTimeMillis() + 3_600_000L);
        httpClient = new OkHttpClient.Builder().build();
        clientsToStop = new ArrayList<>();
    }

    @After
    public void tearDown() throws Exception {
        for (PosConnectionClient c : clientsToStop) {
            try { c.stop(); } catch (Exception ignored) {}
        }
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
        server.shutdown();
    }

    private PosConnectionClient newClient(Recorder recorder, Registrar registrar) {
        PosConnectionClient c = new PosConnectionClient(
                config, registrar, recorder, httpClient, FAST_BACKOFF);
        clientsToStop.add(c);
        return c;
    }

    /**
     * Wraps a server-side WebSocketListener so that close-handshake initiated
     * by the client is acknowledged from the server side. Without this,
     * MockWebServer's shutdown hangs waiting on the open socket.
     */
    private static WebSocketListener completing(WebSocketListener inner) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, okhttp3.Response r) { inner.onOpen(ws, r); }
            @Override public void onMessage(WebSocket ws, String t) { inner.onMessage(ws, t); }
            @Override public void onMessage(WebSocket ws, okio.ByteString b) { inner.onMessage(ws, b); }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
                inner.onClosing(ws, code, reason);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                inner.onClosed(ws, code, reason);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, okhttp3.Response r) {
                inner.onFailure(ws, t, r);
            }
        };
    }

    // ---- handshake + auth header ---------------------------------------------

    @Test
    public void startOpensSocketWithBearerJwtHeader() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {})));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        client.start();

        assertTrue("expected onConnected within 2s", rec.connected.await(2, TimeUnit.SECONDS));
        assertTrue(client.isConnected());

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("Bearer test-jwt-token", req.getHeader("Authorization"));
        assertEquals(PosWire.PATH_CONNECT, req.getPath());
    }

    // ---- inbound envelope forwarding ------------------------------------------

    @Test
    public void inboundEnvelopeIsForwardedToListener() throws Exception {
        AtomicReference<WebSocket> serverSocket = new AtomicReference<>();
        CountDownLatch serverConnected = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                serverSocket.set(webSocket);
                serverConnected.countDown();
            }
        })));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        client.start();
        rec.connected.await(2, TimeUnit.SECONDS);
        serverConnected.await(2, TimeUnit.SECONDS);

        // Server sends a sale request
        JSONObject saleResource = new JSONObject()
                .put("type", PosWire.RES_SALE)
                .put(PosWire.TXN_AMOUNT, 5000);
        PosEnvelope saleRequest = PosEnvelope.request(saleResource);
        serverSocket.get().send(PosEnvelopeCodec.encode(saleRequest));

        assertTrue("expected envelope within 2s", rec.envelopeReceived.await(2, TimeUnit.SECONDS));
        PosEnvelope received = rec.lastEnvelope.get();
        assertEquals(saleRequest.getFlowId(), received.getFlowId());
        assertEquals(PosWire.RES_SALE, received.getResource().getString("type"));
    }

    // ---- outbound send --------------------------------------------------------

    @Test
    public void sendEnvelopeWhileDisconnectedReturnsFalse() {
        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        // Not started — must return false, must NOT throw
        boolean sent = client.sendEnvelope(PosEnvelope.event(new JSONObject()));
        assertFalse(sent);
    }

    @Test
    public void sendEnvelopeWhileConnectedReachesServer() throws Exception {
        AtomicReference<String> serverReceived = new AtomicReference<>();
        CountDownLatch serverGotMessage = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                serverReceived.set(text);
                serverGotMessage.countDown();
            }
        })));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        client.start();
        rec.connected.await(2, TimeUnit.SECONDS);

        JSONObject resource = new JSONObject().put("type", PosWire.EVT_STATE_CHANGE).put("state", "idle");
        boolean sent = client.sendEnvelope(PosEnvelope.event(resource));
        assertTrue("send must succeed when connected", sent);

        assertTrue("server must receive message", serverGotMessage.await(2, TimeUnit.SECONDS));
        PosEnvelope echoed = PosEnvelopeCodec.decode(serverReceived.get());
        assertEquals("idle", echoed.getResource().getString("state"));
    }

    // ---- reconnect ------------------------------------------------------------

    @Test
    public void serverDisconnectTriggersReconnect() throws Exception {
        // First server closes immediately; second accepts the reconnect attempt
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                webSocket.close(1011, "simulated server drop");
            }
        })));
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {})));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        client.start();

        // Expect TWO onConnected events: initial connect + reconnect after drop
        assertTrue("first connect", rec.connected.await(2, TimeUnit.SECONDS));
        assertTrue("disconnect signal", rec.disconnected.await(2, TimeUnit.SECONDS));
        assertTrue("reconnect onConnected", rec.connectedAgain.await(2, TimeUnit.SECONDS));
        assertEquals(2, rec.connectCount.get());
    }

    // ---- JWT re-registration --------------------------------------------------

    @Test
    public void expiredJwtTriggersRegistrationBeforeConnect() throws Exception {
        // Mark cached JWT as expired
        config.setJwt("stale-jwt", System.currentTimeMillis() - 1000L);

        AtomicBoolean registrarCalled = new AtomicBoolean(false);
        Registrar registrar = callback -> {
            registrarCalled.set(true);
            config.setJwt("fresh-jwt", System.currentTimeMillis() + 3_600_000L);
            callback.onRegistered("fresh-jwt", System.currentTimeMillis() + 3_600_000L,
                    config.proxyBaseUrl + PosWire.PATH_CONNECT, 30);
        };

        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {})));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, registrar);
        client.start();

        assertTrue("expected connection within 2s", rec.connected.await(2, TimeUnit.SECONDS));
        assertTrue("registrar must have been called", registrarCalled.get());

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("Bearer fresh-jwt", req.getHeader("Authorization"));
    }

    // ---- terminal failure on unknown_terminal --------------------------------

    @Test
    public void terminalRegistrationFailureFiresOnTerminalError() throws Exception {
        config.setJwt("", 0L);  // force registration

        Registrar registrar = callback ->
                callback.onFailed(PosWire.ERR_UNKNOWN_TERMINAL, "tsn not in DB");

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, registrar);
        client.start();

        assertTrue("expected onTerminalError within 2s",
                rec.terminalError.await(2, TimeUnit.SECONDS));
        assertTrue(rec.terminalErrorMessage.get().contains(PosWire.ERR_UNKNOWN_TERMINAL));
        assertFalse(client.isConnected());
    }

    @Test
    public void transientRegistrationFailureTriggersReconnectBackoff() throws Exception {
        config.setJwt("", 0L);  // force registration

        AtomicInteger registrarCalls = new AtomicInteger(0);
        Registrar registrar = callback -> {
            int n = registrarCalls.incrementAndGet();
            if (n == 1) {
                callback.onFailed(PosWire.ERR_CONNECTION_FAILED, "transient");
            } else {
                config.setJwt("recovered-jwt", System.currentTimeMillis() + 60_000L);
                callback.onRegistered("recovered-jwt", System.currentTimeMillis() + 60_000L,
                        config.proxyBaseUrl + PosWire.PATH_CONNECT, 30);
            }
        };

        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {})));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, registrar);
        client.start();

        assertTrue("expected eventual connection", rec.connected.await(3, TimeUnit.SECONDS));
        assertEquals(2, registrarCalls.get());
    }

    // ---- stop() lifecycle -----------------------------------------------------

    @Test
    public void stopCancelsReconnect() throws Exception {
        // Server immediately drops the connection
        server.enqueue(new MockResponse().withWebSocketUpgrade(completing(new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                webSocket.close(1011, "server reject");
            }
        })));

        Recorder rec = new Recorder();
        PosConnectionClient client = newClient(rec, neverCalledRegistrar());
        client.start();
        rec.connected.await(2, TimeUnit.SECONDS);
        rec.disconnected.await(2, TimeUnit.SECONDS);

        // Now stop — should NOT see another onConnected, and no further server requests
        client.stop();
        assertFalse(client.isConnected());

        // Wait long enough that any pending reconnect WOULD have fired (> 40ms max backoff)
        Thread.sleep(200);
        assertFalse("must not reconnect after stop", rec.connectedAgain.await(50, TimeUnit.MILLISECONDS));
    }

    // ---- helpers --------------------------------------------------------------

    /** Minimal registrar that fails the test if called — for happy-path tests with valid JWT. */
    private static Registrar neverCalledRegistrar() {
        return callback -> fail("registrar must not be called when JWT is valid");
    }

    private static final class Recorder implements PosConnectionClient.ConnectionListener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch connectedAgain = new CountDownLatch(2);   // second connect == reconnect
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch envelopeReceived = new CountDownLatch(1);
        final CountDownLatch terminalError = new CountDownLatch(1);
        final AtomicInteger connectCount = new AtomicInteger(0);
        final AtomicReference<PosEnvelope> lastEnvelope = new AtomicReference<>();
        final AtomicReference<String> terminalErrorMessage = new AtomicReference<>();

        @Override public void onConnected() {
            connectCount.incrementAndGet();
            connected.countDown();
            connectedAgain.countDown();
        }
        @Override public void onDisconnected(String reason) { disconnected.countDown(); }
        @Override public void onEnvelope(PosEnvelope env) {
            lastEnvelope.set(env);
            envelopeReceived.countDown();
        }
        @Override public void onTerminalError(String message) {
            terminalErrorMessage.set(message);
            terminalError.countDown();
        }
    }

    /** Compact alias to avoid the longer PosConnectionClient.Registrar at call sites. */
    private interface Registrar extends PosConnectionClient.Registrar {}

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
