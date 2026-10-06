package castech.emvtxn.pos;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Tests PosRegistrationClient against a MockWebServer. PosConfig is replaced
 * with an in-memory fake so we don't touch SharedPreferences (which would
 * require Android framework).
 */
public class PosRegistrationClientTest {

    private MockWebServer server;
    private FakePosConfig config;
    private OkHttpClient httpClient;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        config = new FakePosConfig();
        config.proxyBaseUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        config.terminalAccessKey = "test-access-key";
        httpClient = new OkHttpClient.Builder().build();
    }

    @After
    public void tearDown() throws Exception {
        // Drain OkHttp's internal executors so MockWebServer doesn't time out
        // waiting on the connection pool during shutdown.
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
        server.shutdown();
    }

    // ---- success path ---------------------------------------------------------

    @Test
    public void registerSuccessExtractsJwtAndPersists() throws Exception {
        long expiresAt = System.currentTimeMillis() + 3_600_000L;
        String jwt = "eyJhbGciOiJSUzI1NiJ9.test.signature";

        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                // Parse the registration request, then respond with success
                JSONObject success = new JSONObject();
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    JSONObject resource = new JSONObject()
                            .put(PosWire.REG_STATUS, "approved")
                            .put(PosWire.REG_JWT, jwt)
                            .put(PosWire.REG_JWT_EXPIRES_AT, expiresAt)
                            .put(PosWire.REG_HEARTBEAT_INTERVAL_SEC, 45);
                    success = resource;
                    PosEnvelope resp = PosEnvelope.response(req.getFlowId(), resource, null);
                    ws.send(PosEnvelopeCodec.encode(resp));
                    ws.close(1000, "test response sent");
                } catch (Exception e) {
                    fail("handler failure: " + e.getMessage());
                }
            }
        }));

        Latch latch = new Latch();
        PosRegistrationClient client = new PosRegistrationClient(
                config, "000195250202209", "6.1", "S1F4 PRO", httpClient);
        client.register(latch);
        latch.await();

        assertTrue("expected onRegistered, got onFailed: " + latch.errorCode.get() + " " + latch.errorMessage.get(),
                latch.success.get());
        assertEquals(jwt, latch.jwt.get());
        assertEquals(expiresAt, latch.expiresAt.get());
        assertEquals(45, latch.heartbeatSec.get());
        // connection_url omitted → should default to baseUrl + PATH_CONNECT
        assertEquals(config.proxyBaseUrl + PosWire.PATH_CONNECT, latch.connectionUrl.get());
        // PosConfig should now have the JWT cached
        assertEquals(jwt, config.jwt);
        assertEquals(expiresAt, config.jwtExpiresAt);
    }

    @Test
    public void registrationRequestEnvelopeHasExpectedShape() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        CountDownLatch handlerDone = new CountDownLatch(1);

        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                captured.set(text);
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    // Send a valid success response so the client can complete
                    JSONObject resource = new JSONObject()
                            .put(PosWire.REG_STATUS, "approved")
                            .put(PosWire.REG_JWT, "tok")
                            .put(PosWire.REG_JWT_EXPIRES_AT, System.currentTimeMillis() + 60_000L);
                    ws.send(PosEnvelopeCodec.encode(PosEnvelope.response(req.getFlowId(), resource, null)));
                    ws.close(1000, "test response sent");
                } catch (Exception ignored) {}
                handlerDone.countDown();
            }
        }));

        Latch latch = new Latch();
        new PosRegistrationClient(config, "TSN-001", "9.9", "S1F4 PRO", httpClient).register(latch);
        latch.await();

        String sent = captured.get();
        assertNotNull("registration request was not received by mock server", sent);
        PosEnvelope env = PosEnvelopeCodec.decode(sent);
        assertEquals(PosEnvelope.TYPE_REQUEST, env.getType());
        JSONObject r = env.getResource();
        assertEquals(PosWire.RES_REGISTER, r.getString(PosWire.REG_TYPE));
        assertEquals("TSN-001", r.getString(PosWire.REG_TSN));
        assertEquals("test-access-key", r.getString(PosWire.REG_TERMINAL_ACCESS_KEY));
        assertEquals("9.9", r.getString(PosWire.REG_APP_VERSION));
        assertEquals("S1F4 PRO", r.getString(PosWire.REG_DEVICE_MODEL));
        JSONArray caps = r.getJSONArray(PosWire.REG_CAPABILITIES);
        assertTrue("capabilities must include sale", containsString(caps, PosWire.RES_SALE));
        assertTrue("capabilities must include balance_inquiry", containsString(caps, PosWire.RES_BALANCE_INQUIRY));
        // Things we don't support must NOT be advertised
        assertFalse("must not advertise refund", containsString(caps, PosWire.RES_REFUND));
        assertFalse("must not advertise void",   containsString(caps, PosWire.RES_VOID));
        assertFalse("must not advertise preauth", containsString(caps, PosWire.RES_PREAUTH));
    }

    @Test
    public void connectionUrlOverrideFromProxyIsHonored() throws Exception {
        String customUrl = "wss://shard-2.proxy.example.com/castle/v1/connect";
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    JSONObject r = new JSONObject()
                            .put(PosWire.REG_JWT, "tok")
                            .put(PosWire.REG_JWT_EXPIRES_AT, System.currentTimeMillis() + 60_000L)
                            .put(PosWire.REG_CONNECTION_URL, customUrl);
                    ws.send(PosEnvelopeCodec.encode(PosEnvelope.response(req.getFlowId(), r, null)));
                    ws.close(1000, "test response sent");
                } catch (Exception ignored) {}
            }
        }));

        Latch latch = new Latch();
        new PosRegistrationClient(config, "T1", "v", "m", httpClient).register(latch);
        latch.await();
        assertTrue(latch.success.get());
        assertEquals(customUrl, latch.connectionUrl.get());
    }

    // ---- error paths ----------------------------------------------------------

    @Test
    public void serverReturnsErrorEnvelopePropagated() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    JSONObject err = PosEnvelope.errorBlock(PosWire.ERR_UNKNOWN_TERMINAL, "tsn not provisioned");
                    ws.send(PosEnvelopeCodec.encode(PosEnvelope.response(req.getFlowId(), null, err)));
                    ws.close(1000, "test response sent");
                } catch (Exception ignored) {}
            }
        }));

        Latch latch = new Latch();
        new PosRegistrationClient(config, "ghost", "v", "m", httpClient).register(latch);
        latch.await();
        assertFalse(latch.success.get());
        assertEquals(PosWire.ERR_UNKNOWN_TERMINAL, latch.errorCode.get());
        assertEquals("tsn not provisioned", latch.errorMessage.get());
    }

    @Test
    public void malformedJsonResponseFailsWithMalformedCode() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                ws.send("{not valid json");
                ws.close(1000, "test response sent");
            }
        }));

        Latch latch = new Latch();
        new PosRegistrationClient(config, "T", "v", "m", httpClient).register(latch);
        latch.await();
        assertFalse(latch.success.get());
        assertEquals(PosWire.ERR_MALFORMED_RESPONSE, latch.errorCode.get());
    }

    @Test
    public void responseMissingJwtFailsWithInvalidResponse() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    JSONObject r = new JSONObject().put(PosWire.REG_STATUS, "approved");
                    // intentionally no jwt / jwt_expires_at
                    ws.send(PosEnvelopeCodec.encode(PosEnvelope.response(req.getFlowId(), r, null)));
                    ws.close(1000, "test response sent");
                } catch (Exception ignored) {}
            }
        }));

        Latch latch = new Latch();
        new PosRegistrationClient(config, "T", "v", "m", httpClient).register(latch);
        latch.await();
        assertFalse(latch.success.get());
        assertEquals(PosWire.ERR_INVALID_RESPONSE, latch.errorCode.get());
    }

    @Test
    public void missingCredentialsFailsImmediately() throws Exception {
        config.proxyBaseUrl = "";
        config.terminalAccessKey = "";
        Latch latch = new Latch();
        new PosRegistrationClient(config, "T", "v", "m", httpClient).register(latch);
        latch.await(2, TimeUnit.SECONDS);
        assertFalse(latch.success.get());
        assertEquals(PosWire.ERR_INVALID_CREDENTIALS, latch.errorCode.get());
    }

    @Test
    public void requestEnvelopeIncludesTimestampAndFlowId() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                captured.set(text);
                try {
                    PosEnvelope req = PosEnvelopeCodec.decode(text);
                    JSONObject r = new JSONObject()
                            .put(PosWire.REG_JWT, "t")
                            .put(PosWire.REG_JWT_EXPIRES_AT, System.currentTimeMillis() + 60_000L);
                    ws.send(PosEnvelopeCodec.encode(PosEnvelope.response(req.getFlowId(), r, null)));
                    ws.close(1000, "test response sent");
                } catch (Exception ignored) {}
            }
        }));
        Latch latch = new Latch();
        new PosRegistrationClient(config, "T", "v", "m", httpClient).register(latch);
        latch.await();
        PosEnvelope env = PosEnvelopeCodec.decode(captured.get());
        assertNotNull(env.getFlowId());
        assertTrue("flow_id is uuid-like", env.getFlowId().length() >= 36);
        assertNotNull(env.getTimestamp());
        assertTrue(env.getTimestamp().endsWith("Z"));
    }

    // ---- helpers --------------------------------------------------------------

    private static boolean containsString(JSONArray arr, String needle) {
        for (int i = 0; i < arr.length(); i++) {
            if (needle.equals(arr.optString(i))) return true;
        }
        return false;
    }

    /** Simple latch-backed callback implementation for assertions. */
    private static final class Latch implements PosRegistrationClient.RegistrationCallback {
        final CountDownLatch done = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(false);
        final AtomicReference<String> jwt = new AtomicReference<>();
        final AtomicLong expiresAt = new AtomicLong();
        final AtomicReference<String> connectionUrl = new AtomicReference<>();
        final AtomicInteger heartbeatSec = new AtomicInteger();
        final AtomicReference<String> errorCode = new AtomicReference<>();
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        @Override
        public void onRegistered(String j, long e, String url, int hb) {
            jwt.set(j); expiresAt.set(e); connectionUrl.set(url); heartbeatSec.set(hb);
            success.set(true);
            done.countDown();
        }
        @Override
        public void onFailed(String code, String message) {
            errorCode.set(code); errorMessage.set(message);
            done.countDown();
        }
        void await() throws InterruptedException {
            if (!done.await(10, TimeUnit.SECONDS)) fail("callback did not fire within 10s");
        }
        void await(long t, TimeUnit unit) throws InterruptedException { done.await(t, unit); }
    }

    /**
     * In-memory replacement for PosConfig that doesn't touch SharedPreferences.
     * Extends PosConfig to satisfy the type, overrides every method we use.
     */
    private static final class FakePosConfig extends PosConfig {
        String proxyBaseUrl = "";
        String terminalAccessKey = "";
        String jwt = "";
        long jwtExpiresAt = 0L;

        FakePosConfig() {
            super(new FakeContext());
        }
        @Override public String getProxyBaseUrl() { return proxyBaseUrl; }
        @Override public String getTerminalAccessKey() { return terminalAccessKey; }
        @Override public String getJwt() { return jwt; }
        @Override public long getJwtExpiresAtMillis() { return jwtExpiresAt; }
        @Override public void setJwt(String j, long e) { jwt = j; jwtExpiresAt = e; }
        @Override public void clearJwt() { jwt = ""; jwtExpiresAt = 0L; }
    }

    /** Stub Context for FakePosConfig — never used because we override every getter/setter. */
    private static final class FakeContext extends android.content.ContextWrapper {
        FakeContext() { super(null); }
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return null; // unused — overridden methods don't read it
        }
    }
}
