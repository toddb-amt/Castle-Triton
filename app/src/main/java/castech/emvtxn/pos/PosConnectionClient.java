package castech.emvtxn.pos;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Persistent WebSocket client to the proxy's connection endpoint. Lives as
 * long as POS mode is enabled. Responsibilities:
 *
 * <ol>
 *   <li>Ensure a valid JWT before connecting (re-register via the supplied
 *       {@link Registrar} if {@link PosConfig#isJwtExpired()}).</li>
 *   <li>Open WebSocket to the connection URL with
 *       {@code Authorization: Bearer &lt;jwt&gt;} header.</li>
 *   <li>Send periodic heartbeat events on the configured interval.</li>
 *   <li>Auto-reconnect with exponential backoff on any failure.</li>
 *   <li>Forward inbound envelopes to {@link ConnectionListener}.</li>
 *   <li>Provide a thread-safe {@link #sendEnvelope} for callers to push
 *       responses + events to the proxy.</li>
 * </ol>
 *
 * <p>Threading: all listener callbacks fire on the OkHttp dispatcher thread or
 * the internal scheduler thread. Callers that need to touch UI must marshal
 * back to the main thread themselves.
 */
public final class PosConnectionClient {

    private static final String TAG = "PosConnectionClient";

    /** Default backoff schedule (seconds): 1, 2, 4, 8, 16, 30, 30, 30, ... */
    public static final long[] DEFAULT_BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};

    private enum State { STOPPED, CONNECTING, CONNECTED, RECONNECTING }

    // ---- Configuration --------------------------------------------------------

    private final PosConfig config;
    private final Registrar registrar;
    private final ConnectionListener listener;
    private final OkHttpClient httpClient;
    private final long[] backoffMillis;
    /** Active heartbeat interval — populated from PosWire default or registration response. */
    private volatile long heartbeatIntervalMillis = PosWire.DEFAULT_HEARTBEAT_INTERVAL_SEC * 1000L;
    /** Active connection URL — populated from registration response (or PosConfig.proxyBaseUrl + PATH_CONNECT fallback). */
    private volatile String connectionUrl = null;

    // ---- Runtime state --------------------------------------------------------

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final Object scheduleLock = new Object();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private int reconnectAttempt = 0;

    // ---- Construction ---------------------------------------------------------

    public PosConnectionClient(PosConfig config, Registrar registrar, ConnectionListener listener) {
        this(config, registrar, listener, defaultHttpClient(), backoffToMillis(DEFAULT_BACKOFF_SECONDS));
    }

    /** Test seam — inject HTTP client + custom backoff (e.g. 10/20/40 ms for fast tests). */
    PosConnectionClient(PosConfig config, Registrar registrar, ConnectionListener listener,
                        OkHttpClient httpClient, long[] backoffMillis) {
        this.config = config;
        this.registrar = registrar;
        this.listener = listener;
        this.httpClient = httpClient;
        this.backoffMillis = backoffMillis.clone();
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)  // we manage heartbeats at the application layer
                .build();
    }

    private static long[] backoffToMillis(long[] seconds) {
        long[] out = new long[seconds.length];
        for (int i = 0; i < seconds.length; i++) out[i] = seconds[i] * 1000L;
        return out;
    }

    // ---- Public lifecycle -----------------------------------------------------

    /**
     * Begin connecting. Idempotent — calling start() while already started is a no-op.
     */
    public void start() {
        if (!state.compareAndSet(State.STOPPED, State.CONNECTING)) {
            Log.d(TAG, "start() ignored — state=" + state.get());
            return;
        }
        synchronized (scheduleLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "PosConnection-scheduler");
                    t.setDaemon(true);
                    return t;
                });
            }
        }
        connectNow();
    }

    /**
     * Stop the client. Cancels reconnect, stops heartbeat, closes the socket.
     * Safe to call multiple times.
     */
    public void stop() {
        state.set(State.STOPPED);
        cancelScheduledTasks();
        WebSocket s = socket.getAndSet(null);
        if (s != null) {
            try { s.close(1000, "client stopped"); } catch (Exception ignored) {}
        }
        synchronized (scheduleLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    public boolean isConnected() {
        return state.get() == State.CONNECTED;
    }

    /**
     * Send an envelope to the proxy. Returns true if the send was queued
     * by OkHttp; false if not currently connected.
     */
    public boolean sendEnvelope(PosEnvelope env) {
        WebSocket s = socket.get();
        if (s == null || state.get() != State.CONNECTED) return false;
        try {
            return s.send(PosEnvelopeCodec.encode(env));
        } catch (Exception e) {
            Log.w(TAG, "sendEnvelope failed: " + e.getMessage());
            return false;
        }
    }

    // ---- Internal connect/reconnect -------------------------------------------

    private void connectNow() {
        if (state.get() == State.STOPPED) return;

        // Re-register if JWT is missing or expired
        if (config.isJwtExpired()) {
            Log.d(TAG, "JWT expired or missing — initiating registration");
            registrar.register(new Registrar.Callback() {
                @Override
                public void onRegistered(String jwt, long expiresAtMillis, String connUrl, int heartbeatSec) {
                    // PosConfig.setJwt is called by the registrar implementation
                    connectionUrl = connUrl;
                    heartbeatIntervalMillis = Math.max(5_000L, heartbeatSec * 1000L);
                    openSocket();
                }

                @Override
                public void onFailed(String code, String message) {
                    Log.e(TAG, "registration failed: " + code + " " + message);
                    if (isTerminalFailure(code)) {
                        listener.onTerminalError("registration failed: " + code + " " + message);
                        stop();
                    } else {
                        scheduleReconnect();
                    }
                }
            });
        } else {
            // JWT still valid — use cached connection URL if we have one, else default
            if (connectionUrl == null) {
                connectionUrl = config.getProxyBaseUrl().replaceAll("/+$", "") + PosWire.PATH_CONNECT;
            }
            openSocket();
        }
    }

    private void openSocket() {
        State s = state.get();
        if (s == State.STOPPED) return;
        if (s != State.CONNECTING && s != State.RECONNECTING) {
            // We may have raced with another transition; treat as a no-op.
            Log.d(TAG, "openSocket() skipped — state=" + s);
            return;
        }

        String url = connectionUrl;
        String jwt = config.getJwt();
        if (url == null || url.isEmpty() || jwt.isEmpty()) {
            scheduleReconnect();
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + jwt)
                .build();

        Log.d(TAG, "Opening connection socket to " + url);
        WebSocket ws = httpClient.newWebSocket(request, new ConnectionWebSocketListener());
        socket.set(ws);
    }

    private void scheduleReconnect() {
        if (state.get() == State.STOPPED) return;
        state.set(State.RECONNECTING);
        long delayMillis = backoffMillis[Math.min(reconnectAttempt, backoffMillis.length - 1)];
        reconnectAttempt++;
        Log.d(TAG, "Scheduling reconnect #" + reconnectAttempt + " in " + delayMillis + "ms");
        synchronized (scheduleLock) {
            if (scheduler == null || scheduler.isShutdown()) return;
            if (reconnectTask != null) reconnectTask.cancel(false);
            reconnectTask = scheduler.schedule(() -> {
                if (state.get() == State.STOPPED) return;
                state.set(State.CONNECTING);
                connectNow();
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleHeartbeat() {
        synchronized (scheduleLock) {
            if (scheduler == null || scheduler.isShutdown()) return;
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                    heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelScheduledTasks() {
        synchronized (scheduleLock) {
            if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
            if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
        }
    }

    private void sendHeartbeat() {
        if (!isConnected()) return;
        JSONObject hb = new JSONObject();
        try {
            hb.put("type", PosWire.EVT_HEARTBEAT);
            hb.put("uptime_millis", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        sendEnvelope(PosEnvelope.event(hb));
    }

    private static boolean isTerminalFailure(String code) {
        // Codes where retrying is pointless — surface to operator instead of looping.
        return PosWire.ERR_UNKNOWN_TERMINAL.equals(code)
            || PosWire.ERR_INVALID_CREDENTIALS.equals(code);
    }

    // ---- WebSocket listener ---------------------------------------------------

    private final class ConnectionWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (!state.compareAndSet(State.CONNECTING, State.CONNECTED)) {
                // Stop was called between newWebSocket() and onOpen() — close.
                try { webSocket.close(1000, "stopped before open"); } catch (Exception ignored) {}
                return;
            }
            Log.d(TAG, "Connection socket open");
            reconnectAttempt = 0;
            scheduleHeartbeat();
            listener.onConnected();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                PosEnvelope env = PosEnvelopeCodec.decode(text);
                listener.onEnvelope(env);
            } catch (PosEnvelopeCodec.PosEnvelopeException e) {
                Log.w(TAG, "Dropping malformed inbound envelope: " + e.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            // text-frame protocol; binary is ignored
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            try { webSocket.close(1000, "ack closing"); } catch (Exception ignored) {}
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "Connection socket closed: code=" + code + " reason=" + reason);
            cancelHeartbeat();
            socket.compareAndSet(webSocket, null);
            if (state.get() == State.STOPPED) return;
            listener.onDisconnected("closed code=" + code + " reason=" + reason);
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.w(TAG, "Connection socket failure: " + t.getMessage());
            cancelHeartbeat();
            socket.compareAndSet(webSocket, null);
            if (state.get() == State.STOPPED) return;
            // 401/403 → JWT may have been revoked; clear cached JWT so next attempt re-registers.
            if (response != null && (response.code() == 401 || response.code() == 403)) {
                Log.w(TAG, "Auth rejected (HTTP " + response.code() + ") — clearing JWT and reconnecting");
                config.clearJwt();
            }
            listener.onDisconnected("failure: " + (t.getMessage() == null ? "(no message)" : t.getMessage()));
            scheduleReconnect();
        }

        private void cancelHeartbeat() {
            synchronized (scheduleLock) {
                if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
            }
        }
    }

    // ---- Injected dependencies ------------------------------------------------

    /**
     * Lazy provider of a fresh JWT. Default production impl wraps
     * {@link PosRegistrationClient}; tests may supply an in-memory fake.
     */
    public interface Registrar {
        void register(Callback callback);

        interface Callback {
            void onRegistered(String jwt, long expiresAtMillis, String connectionUrl, int heartbeatIntervalSec);
            void onFailed(String code, String message);
        }
    }

    /**
     * Default {@link Registrar} that constructs a fresh {@link PosRegistrationClient}
     * on each call. Production code uses this; tests typically inject their own.
     */
    public static Registrar defaultRegistrar(PosConfig config, String tsn,
                                              String appVersion, String deviceModel) {
        return callback -> new PosRegistrationClient(config, tsn, appVersion, deviceModel)
                .register(new PosRegistrationClient.RegistrationCallback() {
                    @Override
                    public void onRegistered(String jwt, long expiresAtMillis, String connectionUrl, int heartbeatIntervalSec) {
                        callback.onRegistered(jwt, expiresAtMillis, connectionUrl, heartbeatIntervalSec);
                    }
                    @Override
                    public void onFailed(String code, String message) {
                        callback.onFailed(code, message);
                    }
                });
    }

    // ---- Listener -------------------------------------------------------------

    public interface ConnectionListener {
        /** Socket is open and authenticated. May fire multiple times across reconnects. */
        void onConnected();

        /** Socket dropped. Reconnect is automatic — this is informational. */
        void onDisconnected(String reason);

        /** Inbound envelope received from proxy. */
        void onEnvelope(PosEnvelope env);

        /**
         * Unrecoverable failure (e.g. {@code unknown_terminal}, {@code invalid_credentials}).
         * Client has stopped — operator intervention required.
         */
        void onTerminalError(String message);
    }
}
