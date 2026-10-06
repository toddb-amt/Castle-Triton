package castech.emvtxn.pos;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import castech.emvtxn.atm.host.AtmHostService;

/**
 * Owns the lifecycle of POS mode: registration → connection → dispatcher →
 * executor → gateway. Single entry point for MainActivity to start/stop the
 * entire POS stack.
 *
 * <p>Construction is cheap (no I/O); {@link #start()} kicks off the
 * background connection. {@link #stop()} cleanly tears everything down.
 *
 * <p>Usage from MainActivity.onCreate (after AtmHostService is initialized):
 * <pre>
 *   PosConfig posConfig = new PosConfig(this);
 *   if (posConfig.isEnabled()) {
 *       posOrchestrator = new PosOrchestrator(this, posConfig,
 *           atmHostService, terminalSerial, appVersion);
 *       posOrchestrator.start();
 *   }
 * </pre>
 */
public final class PosOrchestrator {

    private static final String TAG = "PosOrchestrator";

    private final Context context;
    private final PosConfig posConfig;
    private final AtmHostService hostService;
    private final AtmHostServiceGateway.UiBridge uiBridge;
    private final String terminalSerial;
    private final String appVersion;
    private final String deviceModel;

    private PosConnectionClient connectionClient;
    private PosCommandDispatcher dispatcher;
    private PosTransactionExecutor executor;
    private PosTerminalGateway gateway;

    private volatile String currentState = "stopped";

    public PosOrchestrator(Context context, PosConfig posConfig, AtmHostService hostService,
                           AtmHostServiceGateway.UiBridge uiBridge,
                           String terminalSerial, String appVersion, String deviceModel) {
        this.context = context.getApplicationContext();
        this.posConfig = posConfig;
        this.hostService = hostService;
        this.uiBridge = uiBridge;
        this.terminalSerial = terminalSerial == null ? "" : terminalSerial;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.deviceModel = deviceModel == null ? "S1F4 PRO" : deviceModel;
    }

    /**
     * Boot the POS stack. Returns immediately — connection happens in background.
     * Safe to call before the host service is fully connected; the connection
     * client will retry as needed.
     */
    public void start() {
        if (connectionClient != null) {
            Log.w(TAG, "start() called but already running");
            return;
        }
        if (!posConfig.hasCredentials()) {
            Log.w(TAG, "POS mode is enabled but credentials (proxy URL / access key) are not configured");
            currentState = "no_credentials";
            return;
        }

        Log.d(TAG, "Starting POS stack: proxy=" + posConfig.getProxyBaseUrl()
                + " tsn=" + terminalSerial);

        gateway = new AtmHostServiceGateway(hostService, uiBridge);

        // ConnectionListener forwards inbound envelopes to the dispatcher
        // and tracks state for UI display.
        PosConnectionClient.ConnectionListener connListener = new PosConnectionClient.ConnectionListener() {
            @Override public void onConnected() {
                Log.d(TAG, "POS connected");
                currentState = "connected";
            }
            @Override public void onDisconnected(String reason) {
                Log.d(TAG, "POS disconnected: " + reason);
                currentState = "reconnecting";
            }
            @Override public void onEnvelope(PosEnvelope env) {
                if (dispatcher != null) dispatcher.dispatch(env);
            }
            @Override public void onTerminalError(String message) {
                Log.e(TAG, "POS terminal error: " + message);
                currentState = "out_of_service:" + message;
            }
        };

        connectionClient = new PosConnectionClient(posConfig,
                PosConnectionClient.defaultRegistrar(posConfig, terminalSerial, appVersion, deviceModel),
                connListener);

        // ResponseSender wraps the connection client so the executor can push responses.
        PosCommandDispatcher.ResponseSender sender = env -> {
            if (connectionClient == null) return false;
            return connectionClient.sendEnvelope(env);
        };

        executor = new PosTransactionExecutor(gateway, sender);

        PosCommandDispatcher.InfoProvider infoProvider = new PosCommandDispatcher.InfoProvider() {
            @Override public JSONObject buildInfoResponse() {
                JSONObject info = new JSONObject();
                try {
                    info.put("app_version", appVersion);
                    info.put("device_model", deviceModel);
                    info.put("tsn", terminalSerial);
                    info.put("state", currentState);
                    info.put("ready", gateway != null && gateway.isReady());
                    info.put("not_ready_reason", gateway == null ? "" : gateway.getNotReadyReason());
                    info.put("pending_reversals", gateway == null ? 0 : gateway.getPendingReversalCount());
                } catch (Exception ignored) {}
                return info;
            }
            @Override public int getPendingReversalCount() {
                return gateway == null ? 0 : gateway.getPendingReversalCount();
            }
        };

        dispatcher = new PosCommandDispatcher(sender, executor, infoProvider);

        currentState = "connecting";
        connectionClient.start();
        Log.d(TAG, "POS stack started");
    }

    public void stop() {
        Log.d(TAG, "Stopping POS stack");
        if (connectionClient != null) {
            connectionClient.stop();
            connectionClient = null;
        }
        dispatcher = null;
        executor = null;
        gateway = null;
        currentState = "stopped";
    }

    /** Current high-level state for UI display. */
    public String getState() { return currentState; }

    public boolean isRunning() { return connectionClient != null; }
}
