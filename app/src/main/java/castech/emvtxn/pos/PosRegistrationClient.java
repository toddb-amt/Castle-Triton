package castech.emvtxn.pos;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Registers this terminal with the proxy and obtains a connection-server JWT.
 *
 * <p>Single-use: build a client, call {@link #register}, receive callback, done.
 * For renewal, build a new instance. Internally drives one WebSocket connection
 * to {@link PosWire#PATH_REGISTER} on the proxy, sends the registration request,
 * waits for the response, persists the JWT to {@link PosConfig}, and reports
 * success/failure via the callback.
 *
 * <p>Threading: callbacks fire on an OkHttp dispatcher thread. Callers that
 * need to touch UI must marshal back to the main thread themselves.
 *
 * <p>Does NOT retry — caller (typically the connection client in Phase 4)
 * decides retry/backoff policy.
 */
public final class PosRegistrationClient {

    private static final String TAG = "PosRegistrationClient";

    /** Capabilities this terminal advertises to the proxy. */
    public static final List<String> SUPPORTED_CAPABILITIES = java.util.Arrays.asList(
            PosWire.RES_SALE,
            PosWire.RES_BALANCE_INQUIRY,
            PosWire.RES_REVERSAL,
            PosWire.RES_SETTLEMENT,
            PosWire.RES_INFO,
            PosWire.RES_REVERSAL_STATUS
    );

    private final PosConfig config;
    private final String tsn;
    private final String appVersion;
    private final String deviceModel;
    private final OkHttpClient httpClient;

    public PosRegistrationClient(PosConfig config, String tsn, String appVersion, String deviceModel) {
        this(config, tsn, appVersion, deviceModel, defaultHttpClient());
    }

    /** Test seam — inject a configured OkHttpClient (e.g., with MockWebServer dispatcher). */
    PosRegistrationClient(PosConfig config, String tsn, String appVersion, String deviceModel,
                          OkHttpClient httpClient) {
        this.config = config;
        this.tsn = tsn == null ? "" : tsn;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        this.httpClient = httpClient;
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // 0 = no read timeout (WS is long-lived)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Initiates registration. The callback fires exactly once.
     *
     * @param callback receives either {@link RegistrationCallback#onRegistered}
     *                 or {@link RegistrationCallback#onFailed}, never both.
     */
    public void register(RegistrationCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback is null");

        String baseUrl = config.getProxyBaseUrl();
        String accessKey = config.getTerminalAccessKey();

        if (baseUrl.isEmpty() || accessKey.isEmpty()) {
            callback.onFailed(PosWire.ERR_INVALID_CREDENTIALS,
                    "proxy URL or terminal access key not configured");
            return;
        }

        String registrationUrl = baseUrl.replaceAll("/+$", "") + PosWire.PATH_REGISTER;
        Request request = new Request.Builder().url(registrationUrl).build();

        Log.d(TAG, "Registering tsn=" + tsn + " at " + registrationUrl);

        AtomicBoolean done = new AtomicBoolean(false);
        Listener listener = new Listener(callback, done, accessKey);

        WebSocket ws = httpClient.newWebSocket(request, listener);
        listener.attach(ws);

        // Watchdog: if no response within the registration timeout, fail and close.
        scheduleTimeout(ws, done, callback);
    }

    private void scheduleTimeout(WebSocket ws, AtomicBoolean done, RegistrationCallback callback) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(PosWire.REGISTRATION_TIMEOUT_MILLIS);
            } catch (InterruptedException ignored) { return; }
            if (done.compareAndSet(false, true)) {
                Log.w(TAG, "Registration timed out after " + PosWire.REGISTRATION_TIMEOUT_MILLIS + "ms");
                try { ws.close(1000, "registration timeout"); } catch (Exception ignored) {}
                callback.onFailed(PosWire.ERR_TIMEOUT, "registration timeout");
            }
        }, "PosRegistration-watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ---- WebSocket listener ---------------------------------------------------

    private final class Listener extends WebSocketListener {
        private final RegistrationCallback callback;
        private final AtomicBoolean done;
        private final String accessKey;
        private WebSocket ws;

        Listener(RegistrationCallback callback, AtomicBoolean done, String accessKey) {
            this.callback = callback;
            this.done = done;
            this.accessKey = accessKey;
        }

        void attach(WebSocket ws) { this.ws = ws; }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "Registration socket open — sending request");
            String payload = buildRegistrationRequest(accessKey);
            if (!webSocket.send(payload)) {
                fail(PosWire.ERR_CONNECTION_FAILED, "send failed on open");
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleResponse(text);
            try { webSocket.close(1000, "registration complete"); } catch (Exception ignored) {}
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            // We use text frames; ignore binary
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "Registration socket failure: " + t.getMessage(), t);
            fail(PosWire.ERR_CONNECTION_FAILED, t.getMessage() == null ? "websocket failure" : t.getMessage());
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (done.compareAndSet(false, true)) {
                // Server closed before sending a response.
                callback.onFailed(PosWire.ERR_CONNECTION_FAILED,
                        "socket closed before response (code=" + code + ", reason=" + reason + ")");
            }
        }

        private void handleResponse(String text) {
            PosEnvelope env;
            try {
                env = PosEnvelopeCodec.decode(text);
            } catch (PosEnvelopeCodec.PosEnvelopeException e) {
                fail(PosWire.ERR_MALFORMED_RESPONSE, e.getMessage());
                return;
            }

            if (!env.isResponse()) {
                fail(PosWire.ERR_INVALID_RESPONSE,
                        "expected response envelope, got type=" + env.getType());
                return;
            }

            if (env.hasError()) {
                JSONObject err = env.getError();
                String code = err.optString(PosEnvelope.F_ERROR_CODE, PosWire.ERR_INTERNAL);
                String msg  = err.optString(PosEnvelope.F_ERROR_MESSAGE, "registration denied");
                fail(code, msg);
                return;
            }

            JSONObject resource = env.getResource();
            if (resource == null) {
                fail(PosWire.ERR_INVALID_RESPONSE, "response missing resource block");
                return;
            }

            String jwt = resource.optString(PosWire.REG_JWT, "");
            long expiresAt = resource.optLong(PosWire.REG_JWT_EXPIRES_AT, 0L);
            if (jwt.isEmpty() || expiresAt <= 0L) {
                fail(PosWire.ERR_INVALID_RESPONSE,
                        "response missing jwt or jwt_expires_at");
                return;
            }

            String connectionUrl = resource.optString(PosWire.REG_CONNECTION_URL, "");
            if (connectionUrl.isEmpty()) {
                connectionUrl = config.getProxyBaseUrl().replaceAll("/+$", "") + PosWire.PATH_CONNECT;
            }

            int heartbeatSec = resource.optInt(PosWire.REG_HEARTBEAT_INTERVAL_SEC,
                    PosWire.DEFAULT_HEARTBEAT_INTERVAL_SEC);

            if (done.compareAndSet(false, true)) {
                config.setJwt(jwt, expiresAt);
                Log.d(TAG, "Registration approved — JWT cached, expires at " + expiresAt);
                callback.onRegistered(jwt, expiresAt, connectionUrl, heartbeatSec);
            }
        }

        private void fail(String code, String message) {
            if (done.compareAndSet(false, true)) {
                if (ws != null) {
                    try { ws.close(1000, "registration failed"); } catch (Exception ignored) {}
                }
                callback.onFailed(code, message);
            }
        }
    }

    // ---- Request construction -------------------------------------------------

    private String buildRegistrationRequest(String accessKey) {
        JSONObject resource = new JSONObject();
        try {
            resource.put(PosWire.REG_TYPE, PosWire.RES_REGISTER);
            resource.put(PosWire.REG_TSN, tsn);
            resource.put(PosWire.REG_TERMINAL_ACCESS_KEY, accessKey);
            resource.put(PosWire.REG_APP_VERSION, appVersion);
            resource.put(PosWire.REG_DEVICE_MODEL, deviceModel);
            JSONArray caps = new JSONArray();
            for (String c : SUPPORTED_CAPABILITIES) caps.put(c);
            resource.put(PosWire.REG_CAPABILITIES, caps);
        } catch (JSONException e) {
            throw new IllegalStateException("registration request build failed", e);
        }
        return PosEnvelopeCodec.encode(PosEnvelope.request(resource));
    }

    // ---- Callback -------------------------------------------------------------

    public interface RegistrationCallback {
        /**
         * @param jwt              RS256 JWT to present in {@code Authorization: Bearer} header on the connection socket
         * @param expiresAtMillis  millis-since-epoch when the JWT expires
         * @param connectionUrl    full ws:// or wss:// URL of the persistent connection socket
         * @param heartbeatIntervalSec how often the terminal should send heartbeat frames once connected
         */
        void onRegistered(String jwt, long expiresAtMillis, String connectionUrl, int heartbeatIntervalSec);

        /**
         * @param code     one of the {@code PosWire.ERR_*} constants
         * @param message  human-readable explanation
         */
        void onFailed(String code, String message);
    }
}
