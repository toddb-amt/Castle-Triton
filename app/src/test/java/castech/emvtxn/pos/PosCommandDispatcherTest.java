package castech.emvtxn.pos;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PosCommandDispatcherTest {

    private RecordingSender sender;
    private RecordingTxnHandler txnHandler;
    private StubInfoProvider infoProvider;
    private PosCommandDispatcher dispatcher;

    @Before
    public void setUp() {
        sender = new RecordingSender();
        txnHandler = new RecordingTxnHandler();
        infoProvider = new StubInfoProvider();
        dispatcher = new PosCommandDispatcher(sender, txnHandler, infoProvider);
    }

    // ---- async txn commands → handler ----------------------------------------

    @Test
    public void saleRoutedToTransactionHandler() throws Exception {
        JSONObject resource = new JSONObject()
                .put("type", PosWire.RES_SALE)
                .put(PosWire.TXN_AMOUNT, 5000)
                .put(PosWire.TXN_ACCOUNT_TYPE, "checking");
        PosEnvelope req = PosEnvelope.request(resource);

        dispatcher.dispatch(req);

        assertEquals(1, txnHandler.saleCalls.size());
        assertEquals(req.getFlowId(), txnHandler.saleCalls.get(0).flowId);
        assertEquals(5000, txnHandler.saleCalls.get(0).resource.getInt(PosWire.TXN_AMOUNT));
        // No immediate response — async handler owns the response lifecycle
        assertEquals(0, sender.sent.size());
    }

    @Test
    public void balanceInquiryRoutedToHandler() {
        PosEnvelope req = makeRequest(PosWire.RES_BALANCE_INQUIRY);
        dispatcher.dispatch(req);
        assertEquals(1, txnHandler.balanceCalls.size());
        assertEquals(req.getFlowId(), txnHandler.balanceCalls.get(0).flowId);
    }

    @Test
    public void reversalRoutedToHandler() {
        PosEnvelope req = makeRequest(PosWire.RES_REVERSAL);
        dispatcher.dispatch(req);
        assertEquals(1, txnHandler.reversalCalls.size());
    }

    @Test
    public void settlementRoutedToHandler() {
        PosEnvelope req = makeRequest(PosWire.RES_SETTLEMENT);
        dispatcher.dispatch(req);
        assertEquals(1, txnHandler.settlementCalls.size());
    }

    // ---- sync commands → immediate response -----------------------------------

    @Test
    public void infoRespondsWithCapabilitiesAndProviderData() throws Exception {
        infoProvider.infoFields.put("app_version", "6.1");
        infoProvider.infoFields.put("device_model", "S1F4 PRO");
        infoProvider.infoFields.put("state", "idle");

        PosEnvelope req = makeRequest(PosWire.RES_INFO);
        dispatcher.dispatch(req);

        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertTrue(resp.isResponse());
        assertEquals(req.getFlowId(), resp.getFlowId());
        assertNull(resp.getError());
        JSONObject info = resp.getResource();
        assertEquals("6.1", info.getString("app_version"));
        assertEquals("S1F4 PRO", info.getString("device_model"));
        assertEquals("idle", info.getString("state"));
        // Capabilities must be the canonical list — not the provider's
        JSONArray caps = info.getJSONArray(PosWire.REG_CAPABILITIES);
        assertTrue(contains(caps, PosWire.RES_SALE));
        assertTrue(contains(caps, PosWire.RES_BALANCE_INQUIRY));
        assertTrue(contains(caps, PosWire.RES_REVERSAL));
        assertTrue(contains(caps, PosWire.RES_SETTLEMENT));
        assertFalse("unsupported commands must not appear", contains(caps, PosWire.RES_REFUND));
        assertFalse("unsupported commands must not appear", contains(caps, PosWire.RES_VOID));
    }

    @Test
    public void reversalStatusReturnsPendingCount() throws Exception {
        infoProvider.pendingReversals = 3;
        PosEnvelope req = makeRequest(PosWire.RES_REVERSAL_STATUS);
        dispatcher.dispatch(req);

        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertEquals(req.getFlowId(), resp.getFlowId());
        assertEquals(3, resp.getResource().getInt("pending_count"));
    }

    // ---- unsupported commands → immediate not_supported -----------------------

    @Test
    public void refundReturnsNotSupported() throws Exception {
        assertNotSupported(PosWire.RES_REFUND);
    }

    @Test
    public void voidReturnsNotSupported() throws Exception {
        assertNotSupported(PosWire.RES_VOID);
    }

    @Test
    public void preauthReturnsNotSupported() throws Exception {
        assertNotSupported(PosWire.RES_PREAUTH);
    }

    @Test
    public void preauthCompletionReturnsNotSupported() throws Exception {
        assertNotSupported(PosWire.RES_PREAUTH_COMPLETION);
    }

    @Test
    public void reprintReturnsNotSupported() throws Exception {
        assertNotSupported(PosWire.RES_REPRINT);
    }

    @Test
    public void unknownCommandReturnsNotSupported() throws Exception {
        PosEnvelope req = makeRequest("invent_a_command");
        dispatcher.dispatch(req);
        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertTrue(resp.hasError());
        assertEquals(PosWire.ERR_NOT_SUPPORTED, resp.getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- malformed requests ---------------------------------------------------

    @Test
    public void requestWithoutResourceReturnsInvalidRequest() throws Exception {
        // Build a request envelope with no resource block at all
        PosEnvelope req = PosEnvelope.reconstruct(PosEnvelope.TYPE_REQUEST,
                "flow-xyz", "2026-01-01T00:00:00.000Z", null, null);
        dispatcher.dispatch(req);
        assertEquals(1, sender.sent.size());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    @Test
    public void requestWithoutResourceTypeReturnsInvalidRequest() throws Exception {
        PosEnvelope req = PosEnvelope.request(new JSONObject().put("amount", 100));
        dispatcher.dispatch(req);
        assertEquals(1, sender.sent.size());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- non-request envelopes are dropped silently ---------------------------

    @Test
    public void responseEnvelopeIsIgnored() {
        PosEnvelope resp = PosEnvelope.response("f", new JSONObject(), null);
        dispatcher.dispatch(resp);
        assertEquals(0, sender.sent.size());
        assertEquals(0, txnHandler.totalCalls());
    }

    @Test
    public void eventEnvelopeIsIgnored() {
        PosEnvelope evt = PosEnvelope.event(new JSONObject());
        dispatcher.dispatch(evt);
        assertEquals(0, sender.sent.size());
    }

    @Test
    public void eventAckEnvelopeIsIgnored() {
        PosEnvelope ack = PosEnvelope.eventAck("f");
        dispatcher.dispatch(ack);
        assertEquals(0, sender.sent.size());
    }

    @Test
    public void nullEnvelopeIsIgnored() {
        dispatcher.dispatch(null);
        assertEquals(0, sender.sent.size());
    }

    // ---- helpers --------------------------------------------------------------

    private static PosEnvelope makeRequest(String type) {
        try {
            return PosEnvelope.request(new JSONObject().put("type", type));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void assertNotSupported(String type) throws Exception {
        PosEnvelope req = makeRequest(type);
        dispatcher.dispatch(req);
        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertEquals(req.getFlowId(), resp.getFlowId());
        assertTrue(resp.hasError());
        assertEquals(PosWire.ERR_NOT_SUPPORTED,
                resp.getError().getString(PosEnvelope.F_ERROR_CODE));
        // Handler must NOT have been called for an unsupported command
        assertEquals("handler must not be invoked for unsupported command",
                0, txnHandler.totalCalls());
    }

    private static boolean contains(JSONArray arr, String needle) {
        for (int i = 0; i < arr.length(); i++) {
            if (needle.equals(arr.optString(i))) return true;
        }
        return false;
    }

    // ---- fakes ----------------------------------------------------------------

    private static final class RecordingSender implements PosCommandDispatcher.ResponseSender {
        final List<PosEnvelope> sent = new ArrayList<>();
        @Override public boolean send(PosEnvelope envelope) {
            sent.add(envelope);
            return true;
        }
    }

    private static final class HandlerCall {
        final String flowId;
        final JSONObject resource;
        HandlerCall(String flowId, JSONObject resource) {
            this.flowId = flowId;
            this.resource = resource;
        }
    }

    private static final class RecordingTxnHandler implements PosCommandDispatcher.TransactionHandler {
        final List<HandlerCall> saleCalls       = new ArrayList<>();
        final List<HandlerCall> balanceCalls    = new ArrayList<>();
        final List<HandlerCall> reversalCalls   = new ArrayList<>();
        final List<HandlerCall> settlementCalls = new ArrayList<>();
        @Override public void onSale(String f, JSONObject r)            { saleCalls.add(new HandlerCall(f, r)); }
        @Override public void onBalanceInquiry(String f, JSONObject r)  { balanceCalls.add(new HandlerCall(f, r)); }
        @Override public void onReversal(String f, JSONObject r)        { reversalCalls.add(new HandlerCall(f, r)); }
        @Override public void onSettlement(String f, JSONObject r)      { settlementCalls.add(new HandlerCall(f, r)); }
        int totalCalls() {
            return saleCalls.size() + balanceCalls.size() + reversalCalls.size() + settlementCalls.size();
        }
    }

    private static final class StubInfoProvider implements PosCommandDispatcher.InfoProvider {
        final JSONObject infoFields = new JSONObject();
        int pendingReversals = 0;
        @Override public JSONObject buildInfoResponse() {
            // Return a copy so the dispatcher's overlay of capabilities doesn't mutate our stub
            JSONObject copy = new JSONObject();
            try {
                java.util.Iterator<String> it = infoFields.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    copy.put(k, infoFields.get(k));
                }
            } catch (Exception ignored) {}
            return copy;
        }
        @Override public int getPendingReversalCount() { return pendingReversals; }
    }
}
