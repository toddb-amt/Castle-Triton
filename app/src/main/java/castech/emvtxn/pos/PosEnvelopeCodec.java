package castech.emvtxn.pos;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON encode/decode for {@link PosEnvelope}.
 *
 * <p>Stateless. All operations are static. The codec round-trips the {@code resource}
 * JSONObject verbatim — callers are responsible for understanding the per-command
 * resource shape (see {@link PosCommandDispatcher}).
 *
 * <p>Wire format is documented in {@link PosEnvelope}.
 */
public final class PosEnvelopeCodec {

    private PosEnvelopeCodec() {}

    /**
     * Encode the envelope to a JSON string suitable for WebSocket transmission.
     */
    public static String encode(PosEnvelope env) {
        if (env == null) throw new IllegalArgumentException("envelope is null");
        JSONObject root = new JSONObject();
        try {
            root.put(PosEnvelope.F_TYPE, env.getType());
            root.put(PosEnvelope.F_FLOW_ID, env.getFlowId());
            root.put(PosEnvelope.F_TIMESTAMP, env.getTimestamp());
            if (env.getResource() != null) {
                root.put(PosEnvelope.F_RESOURCE, env.getResource());
            }
            if (env.hasError()) {
                root.put(PosEnvelope.F_ERROR, env.getError());
            }
        } catch (JSONException e) {
            throw new IllegalStateException("envelope encode failed", e);
        }
        return root.toString();
    }

    /**
     * Decode a JSON string from the wire into a {@link PosEnvelope}.
     *
     * @throws PosEnvelopeException if the JSON is malformed or required fields are missing.
     */
    public static PosEnvelope decode(String json) throws PosEnvelopeException {
        if (json == null || json.isEmpty()) {
            throw new PosEnvelopeException("empty payload");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new PosEnvelopeException("malformed JSON: " + e.getMessage(), e);
        }

        String type = optString(root, PosEnvelope.F_TYPE);
        if (type == null) {
            throw new PosEnvelopeException("missing required field: " + PosEnvelope.F_TYPE);
        }
        if (!isValidType(type)) {
            throw new PosEnvelopeException("unknown envelope type: " + type);
        }

        String flowId = optString(root, PosEnvelope.F_FLOW_ID);
        if (flowId == null) {
            throw new PosEnvelopeException("missing required field: " + PosEnvelope.F_FLOW_ID);
        }

        String timestamp = optString(root, PosEnvelope.F_TIMESTAMP);
        // timestamp absence is tolerated — we substitute "now" rather than reject
        if (timestamp == null) timestamp = PosEnvelope.nowIso();

        JSONObject resource = root.optJSONObject(PosEnvelope.F_RESOURCE);
        JSONObject error    = root.optJSONObject(PosEnvelope.F_ERROR);

        return PosEnvelope.reconstruct(type, flowId, timestamp, resource, error);
    }

    private static boolean isValidType(String type) {
        return PosEnvelope.TYPE_REQUEST.equals(type)
            || PosEnvelope.TYPE_RESPONSE.equals(type)
            || PosEnvelope.TYPE_EVENT.equals(type)
            || PosEnvelope.TYPE_EVENT_ACK.equals(type);
    }

    /**
     * {@link JSONObject#optString} returns the literal string {@code "null"} when the key is
     * present-but-null, and "" when missing. We want a real null in both cases so callers can
     * distinguish "explicitly present" from "absent".
     */
    private static String optString(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) return null;
        String v = obj.optString(key, null);
        if (v == null || v.isEmpty()) return null;
        return v;
    }

    /** Thrown when an inbound JSON payload cannot be parsed into a valid envelope. */
    public static final class PosEnvelopeException extends Exception {
        public PosEnvelopeException(String message) { super(message); }
        public PosEnvelopeException(String message, Throwable cause) { super(message, cause); }
    }
}
