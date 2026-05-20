package castech.emvtxn.pos;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implements {@link PosCommandDispatcher.TransactionHandler}, bridging
 * inbound POS commands to the existing host layer (via {@link PosTerminalGateway})
 * and translating the host's result back into POS response envelopes.
 *
 * <p>This is the load-bearing seam between the POS protocol and the existing
 * Castle Hyosung host stack. Everything below this class (gateway, AtmHostService,
 * EMV SDK) is unchanged from customer-driven mode; everything above it
 * (dispatcher, connection, registration) is POS-protocol-only.
 *
 * <p>Threading: dispatched on the OkHttp dispatcher thread; the gateway
 * methods are expected to handle their own threading.
 */
public final class PosTransactionExecutor implements PosCommandDispatcher.TransactionHandler {

    private static final String TAG = "PosTransactionExecutor";

    private final PosTerminalGateway gateway;
    private final PosCommandDispatcher.ResponseSender sender;

    public PosTransactionExecutor(PosTerminalGateway gateway,
                                   PosCommandDispatcher.ResponseSender sender) {
        if (gateway == null || sender == null) {
            throw new IllegalArgumentException("gateway and sender required");
        }
        this.gateway = gateway;
        this.sender = sender;
    }

    // ---- TransactionHandler ---------------------------------------------------

    @Override
    public void onSale(String flowId, JSONObject resource) {
        if (!checkReady(flowId)) return;

        long amount;
        long surcharge;
        String accountType;
        try {
            amount = resource.getLong(PosWire.TXN_AMOUNT);
            surcharge = resource.optLong(PosWire.TXN_SURCHARGE, 0L);
            accountType = normalizeAccountType(resource.optString(PosWire.TXN_ACCOUNT_TYPE, "checking"));
        } catch (JSONException e) {
            sendErrorEnvelope(flowId, PosWire.ERR_INVALID_REQUEST, "missing required field: " + e.getMessage());
            return;
        }

        if (amount <= 0) {
            sendErrorEnvelope(flowId, PosWire.ERR_INVALID_REQUEST,
                    PosWire.TXN_AMOUNT + " must be > 0 (got " + amount + ")");
            return;
        }
        if (surcharge < 0) {
            sendErrorEnvelope(flowId, PosWire.ERR_INVALID_REQUEST,
                    PosWire.TXN_SURCHARGE + " must be >= 0 (got " + surcharge + ")");
            return;
        }
        if (accountType == null) {
            sendErrorEnvelope(flowId, PosWire.ERR_INVALID_REQUEST,
                    "invalid " + PosWire.TXN_ACCOUNT_TYPE + " (must be checking|savings|credit)");
            return;
        }

        Log.d(TAG, "POS sale flow=" + flowId + " amt=" + amount + " surcharge=" + surcharge + " acct=" + accountType);
        gateway.startSale(amount, surcharge, accountType, new TxnBridge(flowId, amount, surcharge));
    }

    @Override
    public void onBalanceInquiry(String flowId, JSONObject resource) {
        if (!checkReady(flowId)) return;

        String accountType = normalizeAccountType(resource.optString(PosWire.TXN_ACCOUNT_TYPE, "checking"));
        if (accountType == null) {
            sendErrorEnvelope(flowId, PosWire.ERR_INVALID_REQUEST,
                    "invalid " + PosWire.TXN_ACCOUNT_TYPE + " (must be checking|savings|credit)");
            return;
        }

        Log.d(TAG, "POS balance inquiry flow=" + flowId + " acct=" + accountType);
        gateway.startBalanceInquiry(accountType, new TxnBridge(flowId, 0L, 0L));
    }

    @Override
    public void onReversal(String flowId, JSONObject resource) {
        // Reversal doesn't need ready-check for the working key — the existing reversal
        // logic in AtmHostService already handles offline-queue/replay semantics. We do
        // still want to fail fast if the host service isn't initialized at all.
        if (gateway == null) {
            sendErrorEnvelope(flowId, PosWire.ERR_INTERNAL, "gateway not initialized");
            return;
        }
        String reason = resource.optString("reason", "pos_requested");
        Log.d(TAG, "POS reversal flow=" + flowId + " reason=" + reason);
        gateway.startReversal(reason, new PosTerminalGateway.OperationCallback() {
            @Override public void onSuccess(String message) {
                JSONObject ok = new JSONObject();
                try {
                    ok.put(PosWire.RSP_STATUS, "ok");
                    ok.put(PosWire.RSP_DISPLAY_MESSAGE, message == null ? "" : message);
                } catch (JSONException ignored) {}
                sender.send(PosEnvelope.response(flowId, ok, null));
            }
            @Override public void onError(String code, String msg) {
                sendErrorEnvelope(flowId, code, msg);
            }
        });
    }

    @Override
    public void onSettlement(String flowId, JSONObject resource) {
        if (!checkReady(flowId)) return;
        boolean reset = resource.optBoolean("reset", false);
        Log.d(TAG, "POS settlement flow=" + flowId + " reset=" + reset);
        gateway.startSettlement(reset, new PosTerminalGateway.SettlementCallback() {
            @Override public void onSettled(PosTerminalGateway.SettlementResult result) {
                JSONObject r = new JSONObject();
                try {
                    r.put(PosWire.RSP_STATUS, "approved");
                    r.put("withdrawal_count", result.withdrawalCount);
                    r.put("balance_inquiry_count", result.balanceInquiryCount);
                    r.put("total_cash_dispensed_cents", result.totalCashDispensedCents);
                    r.put("total_surcharges_cents", result.totalSurchargesCents);
                } catch (JSONException ignored) {}
                sender.send(PosEnvelope.response(flowId, r, null));
            }
            @Override public void onError(String code, String msg) {
                sendErrorEnvelope(flowId, code, msg);
            }
        });
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Returns true if ready; otherwise sends an error response and returns false.
     */
    private boolean checkReady(String flowId) {
        if (gateway.isReady()) return true;
        String reason = gateway.getNotReadyReason();
        String code = reason != null && reason.toLowerCase().contains("key")
                ? PosWire.ERR_KEY_NOT_LOADED : PosWire.ERR_HOST_UNREACHABLE;
        sendErrorEnvelope(flowId, code, reason == null || reason.isEmpty() ? "terminal not ready" : reason);
        return false;
    }

    private void sendErrorEnvelope(String flowId, String code, String message) {
        sender.send(PosEnvelope.response(flowId, null, PosEnvelope.errorBlock(code, message)));
    }

    /**
     * Returns the canonical account type or null if invalid. Accepts both POS wire
     * vocabulary (checking/savings/credit) and any reasonable case/abbreviation.
     */
    static String normalizeAccountType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        switch (s) {
            case "checking": case "check": case "ca": case "c":
                return "checking";
            case "savings": case "save": case "sa": case "s":
                return "savings";
            case "credit": case "cr":
                return "credit";
            default:
                return null;
        }
    }

    // ---- TxnBridge: translates gateway callback → POS response envelope -------

    /**
     * Captures flow_id + original amount/surcharge, builds a response envelope
     * from the gateway's callback, and sends it via {@link #sender}.
     */
    private final class TxnBridge implements PosTerminalGateway.TransactionCallback {
        private final String flowId;
        private final long amountCents;
        private final long surchargeCents;

        TxnBridge(String flowId, long amountCents, long surchargeCents) {
            this.flowId = flowId;
            this.amountCents = amountCents;
            this.surchargeCents = surchargeCents;
        }

        @Override
        public void onApproved(PosTerminalGateway.TransactionResult r) {
            JSONObject resource = new JSONObject();
            try {
                resource.put(PosWire.RSP_STATUS, "approved");
                resource.put(PosWire.RSP_RESPONSE_CODE, r.responseCode);
                resource.put(PosWire.RSP_REFERENCE_NUMBER, r.referenceNumber);
                resource.put(PosWire.RSP_AUTH_CODE, r.authCode);
                resource.put(PosWire.RSP_AUTH_DATE, r.authDate);
                resource.put(PosWire.RSP_AUTH_TIME, r.authTime);
                resource.put(PosWire.RSP_ACCOUNT_BALANCE_CENTS, r.accountBalanceCents);
                resource.put(PosWire.RSP_AVAILABLE_BALANCE_CENTS, r.availableBalanceCents);
                resource.put(PosWire.RSP_DISPLAY_MESSAGE, r.displayMessage);
                if (amountCents > 0)    resource.put(PosWire.TXN_AMOUNT, amountCents);
                if (surchargeCents > 0) resource.put(PosWire.TXN_SURCHARGE, surchargeCents);
            } catch (JSONException ignored) {}
            sender.send(PosEnvelope.response(flowId, resource, null));
        }

        @Override
        public void onDeclined(String responseCode, String responseMessage, boolean retainCard) {
            JSONObject resource = new JSONObject();
            try {
                resource.put(PosWire.RSP_STATUS, "declined");
                resource.put(PosWire.RSP_RESPONSE_CODE, responseCode == null ? "" : responseCode);
                resource.put(PosWire.RSP_DISPLAY_MESSAGE, responseMessage == null ? "" : responseMessage);
                resource.put(PosWire.RSP_RETAIN_CARD, retainCard);
            } catch (JSONException ignored) {}
            sender.send(PosEnvelope.response(flowId, resource, null));
        }

        @Override
        public void onError(String code, String message) {
            sendErrorEnvelope(flowId, code, message);
        }
    }
}
