package castech.emvtxn.pos;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * POS wire envelope. Mirrors the USI shape (see tfi-proxy/backend-v2/src/usi/envelope.ts)
 * so the proxy team's mental model carries straight over.
 *
 * <p>Wire format (JSON):
 * <pre>
 * {
 *   "type":      "request" | "response" | "event" | "event_ack",
 *   "flow_id":   "&lt;uuid&gt;",
 *   "timestamp": "2026-05-20T14:30:00.123Z",   // ISO-8601 UTC, millisecond precision
 *   "resource":  { ... },                       // request/response/event body — shape depends on type
 *   "error":     { "code": "...", "message": "..." }  // optional, only on responses
 * }
 * </pre>
 *
 * <p>Envelopes are <b>immutable</b>. Build new envelopes via the static factories
 * ({@link #request}, {@link #response}, {@link #event}, {@link #eventAck}).
 *
 * <p>Resource shape is intentionally opaque at this layer — the {@code resource}
 * payload is whatever JSON the command/response needs. The codec round-trips it
 * verbatim; {@link PosCommandDispatcher} (Phase 5) is responsible for parsing
 * per-command fields out of {@link #resource}.
 */
public final class PosEnvelope {

    // ---- Envelope type constants ----------------------------------------------

    public static final String TYPE_REQUEST   = "request";
    public static final String TYPE_RESPONSE  = "response";
    public static final String TYPE_EVENT     = "event";
    public static final String TYPE_EVENT_ACK = "event_ack";

    // ---- Field name constants (also the wire keys) ----------------------------

    public static final String F_TYPE      = "type";
    public static final String F_FLOW_ID   = "flow_id";
    public static final String F_TIMESTAMP = "timestamp";
    public static final String F_RESOURCE  = "resource";
    public static final String F_ERROR     = "error";

    public static final String F_ERROR_CODE    = "code";
    public static final String F_ERROR_MESSAGE = "message";

    // ---- Instance fields ------------------------------------------------------

    private final String type;
    private final String flowId;
    private final String timestamp;
    private final JSONObject resource;
    private final JSONObject error;

    private PosEnvelope(String type, String flowId, String timestamp,
                        JSONObject resource, JSONObject error) {
        this.type = type;
        this.flowId = flowId;
        this.timestamp = timestamp;
        this.resource = resource;
        this.error = error;
    }

    // ---- Accessors ------------------------------------------------------------

    public String getType()      { return type; }
    public String getFlowId()    { return flowId; }
    public String getTimestamp() { return timestamp; }
    public JSONObject getResource() { return resource; }
    public JSONObject getError()    { return error; }

    public boolean isRequest()   { return TYPE_REQUEST.equals(type); }
    public boolean isResponse()  { return TYPE_RESPONSE.equals(type); }
    public boolean isEvent()     { return TYPE_EVENT.equals(type); }
    public boolean isEventAck()  { return TYPE_EVENT_ACK.equals(type); }
    public boolean hasError()    { return error != null; }

    // ---- Factories ------------------------------------------------------------

    /**
     * Build a {@code request} envelope. Generates a fresh flow_id and current timestamp.
     */
    public static PosEnvelope request(JSONObject resource) {
        return new PosEnvelope(TYPE_REQUEST, newFlowId(), nowIso(), resource, null);
    }

    /**
     * Build a {@code response} envelope correlated with the given request flow_id.
     * Pass {@code error == null} for a success response; pass an error JSONObject
     * (with {@code code} and {@code message} fields) for a failure response.
     */
    public static PosEnvelope response(String flowId, JSONObject resource, JSONObject error) {
        return new PosEnvelope(TYPE_RESPONSE, flowId, nowIso(), resource, error);
    }

    /**
     * Build an unsolicited {@code event} envelope (terminal-initiated status push).
     * The proxy is expected to send back an {@link #eventAck} with the same flow_id.
     */
    public static PosEnvelope event(JSONObject resource) {
        return new PosEnvelope(TYPE_EVENT, newFlowId(), nowIso(), resource, null);
    }

    /**
     * Build an {@code event_ack} envelope correlated with an event's flow_id.
     */
    public static PosEnvelope eventAck(String flowId) {
        return new PosEnvelope(TYPE_EVENT_ACK, flowId, nowIso(), null, null);
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Build a standard error JSONObject for the {@link #F_ERROR} field.
     */
    public static JSONObject errorBlock(String code, String message) {
        JSONObject e = new JSONObject();
        try {
            e.put(F_ERROR_CODE, code == null ? "" : code);
            e.put(F_ERROR_MESSAGE, message == null ? "" : message);
        } catch (JSONException ignored) {
            // JSONObject.put on a String key never throws — present for API compatibility.
        }
        return e;
    }

    static String newFlowId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Current time in ISO-8601 UTC with millisecond precision (e.g. {@code 2026-05-20T14:30:00.123Z}).
     * Uses {@link String#format} to avoid the SimpleDateFormat / TimeZone allocation in hot paths.
     */
    static String nowIso() {
        long now = System.currentTimeMillis();
        long secs = now / 1000L;
        long millis = now % 1000L;
        if (millis < 0) { secs -= 1; millis += 1000; }
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(secs * 1000L);
        return String.format(Locale.US,
                "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND),
                millis);
    }

    /**
     * Package-private constructor used by {@link PosEnvelopeCodec} to reconstruct from JSON.
     * External callers go through the factory methods.
     */
    static PosEnvelope reconstruct(String type, String flowId, String timestamp,
                                   JSONObject resource, JSONObject error) {
        return new PosEnvelope(type, flowId, timestamp, resource, error);
    }
}
