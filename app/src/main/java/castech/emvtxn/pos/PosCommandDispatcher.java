package castech.emvtxn.pos;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Routes inbound POS request envelopes to the right handler.
 *
 * <p>Supported transaction commands ({@code resource.type}) are delegated to
 * the injected {@link TransactionHandler}; the handler is responsible for
 * eventually producing a response envelope (most txn commands are async).
 *
 * <p>Synchronous commands ({@code info}, {@code reversal_status}) are
 * answered immediately from data the {@link InfoProvider} supplies.
 *
 * <p>Explicitly-unsupported commands ({@code refund}, {@code void},
 * {@code preauth}, {@code preauth_completion}, {@code reprint}) get an
 * immediate {@code not_supported} response — these commands appear in the
 * unified POS API but the Castle ATM does not implement them.
 *
 * <p>Non-request envelopes (responses, events, event_acks) are not the
 * dispatcher's concern — they're handled by the orchestrating layer.
 *
 * <p>Threading: {@link #dispatch} is called on the OkHttp dispatcher thread
 * (via {@link PosConnectionClient.ConnectionListener#onEnvelope}). Handlers
 * must marshal to background or UI threads themselves as needed.
 */
public final class PosCommandDispatcher {

    private static final String TAG = "PosCommandDispatcher";

    private final ResponseSender sender;
    private final TransactionHandler txnHandler;
    private final InfoProvider infoProvider;

    public PosCommandDispatcher(ResponseSender sender,
                                 TransactionHandler txnHandler,
                                 InfoProvider infoProvider) {
        if (sender == null || txnHandler == null || infoProvider == null) {
            throw new IllegalArgumentException("sender, txnHandler, infoProvider required");
        }
        this.sender = sender;
        this.txnHandler = txnHandler;
        this.infoProvider = infoProvider;
    }

    /**
     * Route an incoming envelope. Non-request types are dropped silently
     * (logged at debug). Unknown/unsupported request types produce an
     * immediate error response.
     */
    public void dispatch(PosEnvelope envelope) {
        if (envelope == null) {
            Log.w(TAG, "dispatch called with null envelope");
            return;
        }
        if (!envelope.isRequest()) {
            // Responses / events / event_acks are not dispatched here.
            Log.d(TAG, "dropping non-request envelope: type=" + envelope.getType());
            return;
        }

        JSONObject resource = envelope.getResource();
        if (resource == null) {
            sendError(envelope.getFlowId(), PosWire.ERR_INVALID_REQUEST, "missing resource block");
            return;
        }

        String type = resource.optString("type", "");
        if (type.isEmpty()) {
            sendError(envelope.getFlowId(), PosWire.ERR_INVALID_REQUEST, "missing resource.type");
            return;
        }

        switch (type) {
            // ---- supported async transaction commands ----
            case PosWire.RES_SALE:
                txnHandler.onSale(envelope.getFlowId(), resource);
                break;
            case PosWire.RES_BALANCE_INQUIRY:
                txnHandler.onBalanceInquiry(envelope.getFlowId(), resource);
                break;
            case PosWire.RES_REVERSAL:
                txnHandler.onReversal(envelope.getFlowId(), resource);
                break;
            case PosWire.RES_SETTLEMENT:
                txnHandler.onSettlement(envelope.getFlowId(), resource);
                break;

            // ---- supported synchronous commands ----
            case PosWire.RES_INFO:
                sendInfo(envelope.getFlowId());
                break;
            case PosWire.RES_REVERSAL_STATUS:
                sendReversalStatus(envelope.getFlowId());
                break;

            // ---- explicitly unsupported commands ----
            case PosWire.RES_REFUND:
            case PosWire.RES_VOID:
            case PosWire.RES_PREAUTH:
            case PosWire.RES_PREAUTH_COMPLETION:
            case PosWire.RES_REPRINT:
                sendError(envelope.getFlowId(), PosWire.ERR_NOT_SUPPORTED,
                        "command '" + type + "' is not supported by Castle ATM terminals");
                break;

            // ---- everything else ----
            default:
                Log.w(TAG, "Unknown command type: " + type);
                sendError(envelope.getFlowId(), PosWire.ERR_NOT_SUPPORTED,
                        "unknown command type: " + type);
                break;
        }
    }

    // ---- synchronous command builders -----------------------------------------

    private void sendInfo(String flowId) {
        JSONObject info = infoProvider.buildInfoResponse();
        // Ensure capabilities field is always present and populated from our static list,
        // regardless of what the provider does.
        try {
            JSONArray caps = new JSONArray();
            for (String c : PosRegistrationClient.SUPPORTED_CAPABILITIES) caps.put(c);
            info.put(PosWire.REG_CAPABILITIES, caps);
        } catch (JSONException ignored) {}
        sender.send(PosEnvelope.response(flowId, info, null));
    }

    private void sendReversalStatus(String flowId) {
        JSONObject resource = new JSONObject();
        try {
            resource.put("pending_count", infoProvider.getPendingReversalCount());
        } catch (JSONException ignored) {}
        sender.send(PosEnvelope.response(flowId, resource, null));
    }

    private void sendError(String flowId, String code, String message) {
        sender.send(PosEnvelope.response(flowId, null, PosEnvelope.errorBlock(code, message)));
    }

    // ---- collaborator interfaces ----------------------------------------------

    /** Outbound bridge — typically wired to {@link PosConnectionClient#sendEnvelope}. */
    public interface ResponseSender {
        boolean send(PosEnvelope envelope);
    }

    /**
     * Handles async transaction commands. Each method is fire-and-forget — the
     * implementation owns the lifecycle and eventually calls
     * {@link ResponseSender#send} with a correlated response envelope.
     *
     * <p>Phase 6's {@code PosTransactionExecutor} is the production
     * implementation; it bridges to the existing {@code AtmHostService}.
     */
    public interface TransactionHandler {
        void onSale(String flowId, JSONObject resource);
        void onBalanceInquiry(String flowId, JSONObject resource);
        void onReversal(String flowId, JSONObject resource);
        void onSettlement(String flowId, JSONObject resource);
    }

    /**
     * Supplies snapshot data for synchronous info/status commands.
     */
    public interface InfoProvider {
        /**
         * Build the response resource for an {@code info} request. Should include
         * {@code app_version}, {@code device_model}, {@code state}, etc.
         * The dispatcher overlays the canonical capability list before sending.
         */
        JSONObject buildInfoResponse();

        /** Number of reversals waiting to drain. */
        int getPendingReversalCount();
    }
}
