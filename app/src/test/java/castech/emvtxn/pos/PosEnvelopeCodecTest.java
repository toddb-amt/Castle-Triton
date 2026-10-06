package castech.emvtxn.pos;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PosEnvelopeCodecTest {

    // ---- request round-trip ---------------------------------------------------

    @Test
    public void requestRoundTripPreservesAllFields() throws Exception {
        JSONObject resource = new JSONObject()
                .put("type", "sale")
                .put("amount", 5000)
                .put("tender_type", "debit");

        PosEnvelope original = PosEnvelope.request(resource);
        String json = PosEnvelopeCodec.encode(original);
        PosEnvelope decoded = PosEnvelopeCodec.decode(json);

        assertEquals(PosEnvelope.TYPE_REQUEST, decoded.getType());
        assertEquals(original.getFlowId(), decoded.getFlowId());
        assertEquals(original.getTimestamp(), decoded.getTimestamp());
        assertEquals("sale", decoded.getResource().getString("type"));
        assertEquals(5000, decoded.getResource().getInt("amount"));
        assertEquals("debit", decoded.getResource().getString("tender_type"));
        assertNull(decoded.getError());
        assertTrue(decoded.isRequest());
    }

    // ---- success response -----------------------------------------------------

    @Test
    public void successResponseHasNoErrorBlock() throws Exception {
        JSONObject result = new JSONObject()
                .put("status", "approved")
                .put("reference_number", "RRN12345")
                .put("auth_code", "AUTH99");

        PosEnvelope env = PosEnvelope.response("flow-abc", result, null);
        String json = PosEnvelopeCodec.encode(env);

        // wire form should NOT carry an "error" key for success responses
        JSONObject wire = new JSONObject(json);
        assertFalse("success response must not contain 'error' key", wire.has(PosEnvelope.F_ERROR));

        PosEnvelope decoded = PosEnvelopeCodec.decode(json);
        assertEquals("flow-abc", decoded.getFlowId());
        assertEquals("approved", decoded.getResource().getString("status"));
        assertFalse(decoded.hasError());
    }

    // ---- error response -------------------------------------------------------

    @Test
    public void errorResponseEncodesErrorBlock() throws Exception {
        JSONObject err = PosEnvelope.errorBlock("host_unreachable", "TLS handshake timeout");
        PosEnvelope env = PosEnvelope.response("flow-xyz", null, err);

        String json = PosEnvelopeCodec.encode(env);
        PosEnvelope decoded = PosEnvelopeCodec.decode(json);

        assertTrue(decoded.hasError());
        assertEquals("host_unreachable", decoded.getError().getString(PosEnvelope.F_ERROR_CODE));
        assertEquals("TLS handshake timeout", decoded.getError().getString(PosEnvelope.F_ERROR_MESSAGE));
        assertNull("resource null when only error returned", decoded.getResource());
    }

    // ---- event + event_ack ----------------------------------------------------

    @Test
    public void eventAndEventAckShareFlowId() throws Exception {
        PosEnvelope evt = PosEnvelope.event(new JSONObject().put("state", "reconnecting"));
        PosEnvelope ack = PosEnvelope.eventAck(evt.getFlowId());

        assertTrue(evt.isEvent());
        assertTrue(ack.isEventAck());
        assertEquals(evt.getFlowId(), ack.getFlowId());

        // event_ack has no resource — encode/decode must preserve that
        PosEnvelope decodedAck = PosEnvelopeCodec.decode(PosEnvelopeCodec.encode(ack));
        assertNull(decodedAck.getResource());
        assertEquals(evt.getFlowId(), decodedAck.getFlowId());
    }

    // ---- malformed input ------------------------------------------------------

    @Test
    public void rejectsEmptyPayload() {
        try {
            PosEnvelopeCodec.decode("");
            fail("expected exception for empty payload");
        } catch (PosEnvelopeCodec.PosEnvelopeException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    @Test
    public void rejectsMalformedJson() {
        try {
            PosEnvelopeCodec.decode("{not json}");
            fail("expected exception for malformed JSON");
        } catch (PosEnvelopeCodec.PosEnvelopeException expected) {
            assertTrue(expected.getMessage().contains("malformed"));
        }
    }

    @Test
    public void rejectsMissingType() {
        String json = "{\"flow_id\":\"abc\"}";
        try {
            PosEnvelopeCodec.decode(json);
            fail("expected exception for missing type");
        } catch (PosEnvelopeCodec.PosEnvelopeException expected) {
            assertTrue(expected.getMessage().contains(PosEnvelope.F_TYPE));
        }
    }

    @Test
    public void rejectsMissingFlowId() {
        String json = "{\"type\":\"request\"}";
        try {
            PosEnvelopeCodec.decode(json);
            fail("expected exception for missing flow_id");
        } catch (PosEnvelopeCodec.PosEnvelopeException expected) {
            assertTrue(expected.getMessage().contains(PosEnvelope.F_FLOW_ID));
        }
    }

    @Test
    public void rejectsUnknownEnvelopeType() {
        String json = "{\"type\":\"poke\",\"flow_id\":\"abc\"}";
        try {
            PosEnvelopeCodec.decode(json);
            fail("expected exception for unknown type");
        } catch (PosEnvelopeCodec.PosEnvelopeException expected) {
            assertTrue(expected.getMessage().contains("poke"));
        }
    }

    // ---- helpers --------------------------------------------------------------

    @Test
    public void errorBlockHandlesNullCodeAndMessage() throws JSONException {
        JSONObject e = PosEnvelope.errorBlock(null, null);
        assertEquals("", e.getString(PosEnvelope.F_ERROR_CODE));
        assertEquals("", e.getString(PosEnvelope.F_ERROR_MESSAGE));
    }

    @Test
    public void timestampIsIso8601Utc() throws Exception {
        PosEnvelope env = PosEnvelope.request(new JSONObject());
        String ts = env.getTimestamp();
        assertNotNull(ts);
        // shape: 2026-05-20T14:30:00.123Z  → length 24, ends with Z, has T separator
        assertEquals(24, ts.length());
        assertTrue(ts.endsWith("Z"));
        assertTrue(ts.charAt(10) == 'T');
    }

    @Test
    public void decodeWithMissingTimestampSubstitutesNow() throws Exception {
        String json = "{\"type\":\"event_ack\",\"flow_id\":\"f1\"}";
        PosEnvelope decoded = PosEnvelopeCodec.decode(json);
        assertNotNull(decoded.getTimestamp());
        assertTrue(decoded.getTimestamp().endsWith("Z"));
    }
}
