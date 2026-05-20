package castech.emvtxn.pos;

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

public class PosTransactionExecutorTest {

    private FakeGateway gateway;
    private RecordingSender sender;
    private PosTransactionExecutor exec;

    @Before
    public void setUp() {
        gateway = new FakeGateway();
        sender = new RecordingSender();
        exec = new PosTransactionExecutor(gateway, sender);
    }

    // ---- sale: happy paths ----------------------------------------------------

    @Test
    public void approvedSaleBuildsApprovedResponseEnvelope() throws Exception {
        gateway.nextSaleResult = (cb) -> cb.onApproved(new PosTerminalGateway.TransactionResult(
                "00", "RRN12345", "AUTH99",
                "2026/05/20", "14:30:00",
                12345L, 12000L, "APPROVED"));

        PosEnvelope req = saleRequest(5000, 100, "checking");
        exec.onSale(req.getFlowId(), req.getResource());

        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertTrue(resp.isResponse());
        assertEquals(req.getFlowId(), resp.getFlowId());
        assertNull(resp.getError());
        JSONObject r = resp.getResource();
        assertEquals("approved", r.getString(PosWire.RSP_STATUS));
        assertEquals("00",       r.getString(PosWire.RSP_RESPONSE_CODE));
        assertEquals("RRN12345", r.getString(PosWire.RSP_REFERENCE_NUMBER));
        assertEquals("AUTH99",   r.getString(PosWire.RSP_AUTH_CODE));
        assertEquals("2026/05/20", r.getString(PosWire.RSP_AUTH_DATE));
        assertEquals(12345L, r.getLong(PosWire.RSP_ACCOUNT_BALANCE_CENTS));
        assertEquals(12000L, r.getLong(PosWire.RSP_AVAILABLE_BALANCE_CENTS));
        assertEquals(5000L,  r.getLong(PosWire.TXN_AMOUNT));
        assertEquals(100L,   r.getLong(PosWire.TXN_SURCHARGE));
    }

    @Test
    public void declinedSaleBuildsDeclinedResponseEnvelope() throws Exception {
        gateway.nextSaleResult = (cb) -> cb.onDeclined("51", "INSUFFICIENT FUNDS", false);

        PosEnvelope req = saleRequest(5000, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());

        assertEquals(1, sender.sent.size());
        PosEnvelope resp = sender.sent.get(0);
        assertNull(resp.getError());
        JSONObject r = resp.getResource();
        assertEquals("declined", r.getString(PosWire.RSP_STATUS));
        assertEquals("51", r.getString(PosWire.RSP_RESPONSE_CODE));
        assertEquals("INSUFFICIENT FUNDS", r.getString(PosWire.RSP_DISPLAY_MESSAGE));
        assertFalse(r.getBoolean(PosWire.RSP_RETAIN_CARD));
    }

    @Test
    public void saleErrorBuildsErrorEnvelope() throws Exception {
        gateway.nextSaleResult = (cb) -> cb.onError(PosWire.ERR_HOST_UNREACHABLE, "TLS handshake timeout");

        PosEnvelope req = saleRequest(5000, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());

        PosEnvelope resp = sender.sent.get(0);
        assertTrue(resp.hasError());
        assertEquals(PosWire.ERR_HOST_UNREACHABLE, resp.getError().getString(PosEnvelope.F_ERROR_CODE));
        assertEquals("TLS handshake timeout", resp.getError().getString(PosEnvelope.F_ERROR_MESSAGE));
    }

    @Test
    public void salePassesAmountAndAccountTypeToGateway() throws Exception {
        gateway.nextSaleResult = (cb) -> cb.onApproved(blankResult());

        PosEnvelope req = saleRequest(7500, 250, "savings");
        exec.onSale(req.getFlowId(), req.getResource());

        assertEquals(1, gateway.saleCalls.size());
        FakeGateway.SaleInvocation inv = gateway.saleCalls.get(0);
        assertEquals(7500, inv.amountCents);
        assertEquals(250, inv.surchargeCents);
        assertEquals("savings", inv.accountType);
    }

    // ---- sale: validation ----------------------------------------------------

    @Test
    public void zeroAmountReturnsInvalidRequest() throws Exception {
        PosEnvelope req = saleRequest(0, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
        assertEquals(0, gateway.saleCalls.size());
    }

    @Test
    public void negativeAmountReturnsInvalidRequest() throws Exception {
        PosEnvelope req = saleRequest(-1, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    @Test
    public void negativeSurchargeReturnsInvalidRequest() throws Exception {
        PosEnvelope req = saleRequest(5000, -50, "checking");
        exec.onSale(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    @Test
    public void invalidAccountTypeReturnsInvalidRequest() throws Exception {
        PosEnvelope req = saleRequest(5000, 0, "crypto");
        exec.onSale(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
        assertEquals(0, gateway.saleCalls.size());
    }

    @Test
    public void missingAmountReturnsInvalidRequest() throws Exception {
        JSONObject resource = new JSONObject()
                .put("type", PosWire.RES_SALE)
                .put(PosWire.TXN_ACCOUNT_TYPE, "checking");
        PosEnvelope req = PosEnvelope.request(resource);
        exec.onSale(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- sale: terminal not ready --------------------------------------------

    @Test
    public void saleWhenTerminalNotReadyReturnsHostError() throws Exception {
        gateway.ready = false;
        gateway.notReadyReason = "Host not connected";
        PosEnvelope req = saleRequest(5000, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());

        PosEnvelope resp = sender.sent.get(0);
        assertEquals(PosWire.ERR_HOST_UNREACHABLE, resp.getError().getString(PosEnvelope.F_ERROR_CODE));
        assertEquals("Host not connected", resp.getError().getString(PosEnvelope.F_ERROR_MESSAGE));
        assertEquals(0, gateway.saleCalls.size());
    }

    @Test
    public void saleWhenWorkingKeyMissingReturnsKeyNotLoaded() throws Exception {
        gateway.ready = false;
        gateway.notReadyReason = "No working key loaded";
        PosEnvelope req = saleRequest(5000, 0, "checking");
        exec.onSale(req.getFlowId(), req.getResource());

        PosEnvelope resp = sender.sent.get(0);
        assertEquals(PosWire.ERR_KEY_NOT_LOADED, resp.getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- balance inquiry ------------------------------------------------------

    @Test
    public void approvedBalanceInquiryReturnsBalances() throws Exception {
        gateway.nextBalanceResult = (cb) -> cb.onApproved(new PosTerminalGateway.TransactionResult(
                "00", "RRN-1", "", "2026/05/20", "14:30:00",
                100_000L, 95_000L, "APPROVED"));

        PosEnvelope req = balanceRequest("savings");
        exec.onBalanceInquiry(req.getFlowId(), req.getResource());

        JSONObject r = sender.sent.get(0).getResource();
        assertEquals("approved", r.getString(PosWire.RSP_STATUS));
        assertEquals(100_000L, r.getLong(PosWire.RSP_ACCOUNT_BALANCE_CENTS));
        assertEquals(95_000L,  r.getLong(PosWire.RSP_AVAILABLE_BALANCE_CENTS));
    }

    @Test
    public void balanceInquiryInvalidAccountTypeReturnsError() throws Exception {
        PosEnvelope req = balanceRequest("bitcoin");
        exec.onBalanceInquiry(req.getFlowId(), req.getResource());
        assertEquals(PosWire.ERR_INVALID_REQUEST,
                sender.sent.get(0).getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- reversal -------------------------------------------------------------

    @Test
    public void reversalSuccessReturnsOkResponse() throws Exception {
        gateway.nextReversalResult = (cb) -> cb.onSuccess("queued for drain");
        PosEnvelope req = PosEnvelope.request(new JSONObject().put("type", PosWire.RES_REVERSAL).put("reason", "test"));
        exec.onReversal(req.getFlowId(), req.getResource());

        PosEnvelope resp = sender.sent.get(0);
        assertNull(resp.getError());
        assertEquals("ok", resp.getResource().getString(PosWire.RSP_STATUS));
    }

    @Test
    public void reversalErrorReturnsErrorResponse() throws Exception {
        gateway.nextReversalResult = (cb) -> cb.onError(PosWire.ERR_HOST_UNREACHABLE, "host down");
        PosEnvelope req = PosEnvelope.request(new JSONObject().put("type", PosWire.RES_REVERSAL));
        exec.onReversal(req.getFlowId(), req.getResource());

        PosEnvelope resp = sender.sent.get(0);
        assertTrue(resp.hasError());
        assertEquals(PosWire.ERR_HOST_UNREACHABLE, resp.getError().getString(PosEnvelope.F_ERROR_CODE));
    }

    // ---- settlement -----------------------------------------------------------

    @Test
    public void settlementReturnsTotalsResource() throws Exception {
        gateway.nextSettlementResult = (cb) -> cb.onSettled(
                new PosTerminalGateway.SettlementResult(42, 17, 250_000L, 4_200L));

        PosEnvelope req = PosEnvelope.request(new JSONObject().put("type", PosWire.RES_SETTLEMENT).put("reset", true));
        exec.onSettlement(req.getFlowId(), req.getResource());

        JSONObject r = sender.sent.get(0).getResource();
        assertEquals("approved", r.getString(PosWire.RSP_STATUS));
        assertEquals(42, r.getInt("withdrawal_count"));
        assertEquals(17, r.getInt("balance_inquiry_count"));
        assertEquals(250_000L, r.getLong("total_cash_dispensed_cents"));
        assertEquals(4_200L,  r.getLong("total_surcharges_cents"));
    }

    // ---- account type normalization ------------------------------------------

    @Test
    public void normalizeAccountTypeHandlesVariants() {
        assertEquals("checking", PosTransactionExecutor.normalizeAccountType("checking"));
        assertEquals("checking", PosTransactionExecutor.normalizeAccountType("CHECKING"));
        assertEquals("checking", PosTransactionExecutor.normalizeAccountType("check"));
        assertEquals("checking", PosTransactionExecutor.normalizeAccountType(" ca "));
        assertEquals("savings",  PosTransactionExecutor.normalizeAccountType("SAVINGS"));
        assertEquals("savings",  PosTransactionExecutor.normalizeAccountType("SA"));
        assertEquals("credit",   PosTransactionExecutor.normalizeAccountType("Credit"));
        assertNull(PosTransactionExecutor.normalizeAccountType("foo"));
        assertNull(PosTransactionExecutor.normalizeAccountType(""));
        assertNull(PosTransactionExecutor.normalizeAccountType(null));
    }

    // ---- helpers --------------------------------------------------------------

    private static PosEnvelope saleRequest(long amount, long surcharge, String accountType) {
        try {
            return PosEnvelope.request(new JSONObject()
                    .put("type", PosWire.RES_SALE)
                    .put(PosWire.TXN_AMOUNT, amount)
                    .put(PosWire.TXN_SURCHARGE, surcharge)
                    .put(PosWire.TXN_ACCOUNT_TYPE, accountType));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static PosEnvelope balanceRequest(String accountType) {
        try {
            return PosEnvelope.request(new JSONObject()
                    .put("type", PosWire.RES_BALANCE_INQUIRY)
                    .put(PosWire.TXN_ACCOUNT_TYPE, accountType));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static PosTerminalGateway.TransactionResult blankResult() {
        return new PosTerminalGateway.TransactionResult("", "", "", "", "", 0, 0, "");
    }

    // ---- fakes ----------------------------------------------------------------

    private static final class RecordingSender implements PosCommandDispatcher.ResponseSender {
        final List<PosEnvelope> sent = new ArrayList<>();
        @Override public boolean send(PosEnvelope envelope) { sent.add(envelope); return true; }
    }

    /** Programmable in-memory gateway. Set the various {@code next*Result} fields per test. */
    private static final class FakeGateway implements PosTerminalGateway {
        boolean ready = true;
        String notReadyReason = "";
        int pendingReversalCount = 0;

        final List<SaleInvocation> saleCalls = new ArrayList<>();
        final List<String> balanceCalls = new ArrayList<>();
        final List<String> reversalCalls = new ArrayList<>();
        final List<Boolean> settlementCalls = new ArrayList<>();

        SaleHandler nextSaleResult = (cb) -> {};
        BalanceHandler nextBalanceResult = (cb) -> {};
        ReversalHandler nextReversalResult = (cb) -> {};
        SettlementHandler nextSettlementResult = (cb) -> {};

        @Override public boolean isReady() { return ready; }
        @Override public String getNotReadyReason() { return notReadyReason; }
        @Override public int getPendingReversalCount() { return pendingReversalCount; }

        @Override public void startSale(long amount, long surcharge, String acct, TransactionCallback cb) {
            saleCalls.add(new SaleInvocation(amount, surcharge, acct));
            nextSaleResult.respond(cb);
        }
        @Override public void startBalanceInquiry(String acct, TransactionCallback cb) {
            balanceCalls.add(acct);
            nextBalanceResult.respond(cb);
        }
        @Override public void startReversal(String reason, OperationCallback cb) {
            reversalCalls.add(reason);
            nextReversalResult.respond(cb);
        }
        @Override public void startSettlement(boolean reset, SettlementCallback cb) {
            settlementCalls.add(reset);
            nextSettlementResult.respond(cb);
        }

        static final class SaleInvocation {
            final long amountCents;
            final long surchargeCents;
            final String accountType;
            SaleInvocation(long a, long s, String t) { amountCents = a; surchargeCents = s; accountType = t; }
        }

        interface SaleHandler        { void respond(TransactionCallback cb); }
        interface BalanceHandler     { void respond(TransactionCallback cb); }
        interface ReversalHandler    { void respond(OperationCallback cb); }
        interface SettlementHandler  { void respond(SettlementCallback cb); }
    }
}
