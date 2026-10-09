package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.Test;

import castech.emvtxn.atm.TransactionLog;

/**
 * RPT-02 (6.2.13): the request body, built from a journal row, in the shape the portal's
 * contract (docs/MYVIEW-TERMINAL-PUSH-API.md) shows. Synthetic identifiers throughout.
 */
public class PushPayloadTest {

    private static final TimeZone EASTERN = TimeZone.getTimeZone("America/New_York");
    private static final PushPayload.Identity ID = new PushPayload.Identity(
            "MS00TEST", "0000195260000000", "test-key-not-real", "EFX", "6.2.13");

    /** A wall-clock instant in Eastern time, as epoch millis. */
    private static long eastern(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(EASTERN);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    private static TransactionLog approvedSale() {
        TransactionLog t = new TransactionLog();
        t.setId(42);
        t.setTransactionType("WITHDRAWAL");
        t.setResult("APPROVED");
        t.setTimestamp(eastern(2026, 10, 7, 9, 4, 15));
        t.setSequenceNumber(1);
        t.setSaleCents(219_25);
        t.setTipCents(2_00);
        t.setCashBackCents(8_75);
        t.setFeeCents(3_50);
        t.setAmountCents(230_00);          // withdrawal
        t.setTotalCents(233_50);
        t.setCardLastFour("1111");
        t.setReferenceNumber("295300004276");
        t.setResponseCode("00");
        t.setAccountType(20);              // checking
        t.setFlowId("69B266E6-ADD4-4BFC-847E-7BEA0A4C9A74");
        return t;
    }

    private static JSONObject txn(TransactionLog row) throws Exception {
        return PushPayload.of(row, ID, EASTERN).getJSONObject("transactionJSON");
    }

    @Test
    public void approvedSale_matchesTheContractExample() throws Exception {
        JSONObject body = PushPayload.of(approvedSale(), ID, EASTERN);
        assertEquals("69B266E6-ADD4-4BFC-847E-7BEA0A4C9A74", body.getString("flow_id"));
        assertEquals("test-key-not-real", body.getString("tenantAccessKey"));
        assertEquals("0000195260000000", body.getString("tsn"));
        JSONObject t = body.getJSONObject("transactionJSON");
        assertEquals("0000195260000000", t.getString("tsn"));
        assertEquals("MS00TEST", t.getString("TermID"));
        assertEquals("MS00TEST", t.getString("HostTermID"));
        assertEquals(1, t.getInt("TerminalSequenceNum"));
        assertEquals("2026-10-07 09:04:15", t.getString("TransDateTimeUTC"));
        assertEquals("295300004276", t.getString("RRN"));
        assertEquals("CA", t.getString("SourceAccount"));
        assertEquals(21925, t.getInt("RequestedAmt"));
        assertEquals(200, t.getInt("TipAmount"));
        assertEquals(875, t.getInt("CashBackAmount"));
        assertEquals(350, t.getInt("SurchargeAmt"));
        assertEquals(23350, t.getInt("TotalAmt"));
        assertEquals("1111", t.getString("CardLast4"));
        assertEquals("Transaction approved", t.getString("ResponseDescription"));
        assertEquals("EFX", t.getString("Host"));
        assertEquals("10072026", t.getString("BusinessDate"));
        assertEquals("WTH", t.getString("TransType"));
        assertEquals(1, t.getInt("Approved"));
        assertEquals("EST", t.getString("TimeZone"));
        assertEquals(1, t.getInt("TimeZoneDST"));
        assertEquals("Castle S1FP-TFI 6.2.13", t.getString("Software"));
    }

    @Test
    public void totalEqualsSalePlusTipPlusCashBackPlusSurcharge() throws Exception {
        JSONObject t = txn(approvedSale());
        assertEquals(t.getInt("TotalAmt"),
                t.getInt("RequestedAmt") + t.getInt("TipAmount") + t.getInt("CashBackAmount") + t.getInt("SurchargeAmt"));
    }

    @Test
    public void hostDecline_isApprovedZero_withTheHostsText() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("51");
        d.setErrorMessage("INSUFFICIENT FUNDS");
        d.setReferenceNumber(null);
        JSONObject t = txn(d);
        assertEquals(0, t.getInt("Approved"));
        assertEquals("INSUFFICIENT FUNDS", t.getString("ResponseDescription"));
        assertEquals("", t.getString("RRN"));
        assertEquals(350, t.getInt("SurchargeAmt"));   // declines keep the surcharge (contract section 3)
    }

    @Test
    public void terminalSideDecline_isLabelled_andKeepsSequenceZero() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("MSR_NA");
        d.setErrorMessage("Swipe not supported");
        d.setSequenceNumber(0);
        d.setReferenceNumber("");
        JSONObject t = txn(d);
        assertEquals(0, t.getInt("TerminalSequenceNum"));
        assertEquals("Declined at terminal: Swipe not supported", t.getString("ResponseDescription"));
        assertEquals(0, t.getInt("Approved"));
    }

    @Test
    public void timedOutRequest_keepsItsRealSequence_butIsStillTerminalLabelled() throws Exception {
        TransactionLog d = approvedSale();
        d.setResult("DECLINED");
        d.setResponseCode("");
        d.setErrorMessage("Transaction timeout");
        d.setSequenceNumber(17);
        JSONObject t = txn(d);
        assertEquals(17, t.getInt("TerminalSequenceNum"));
        assertEquals("Declined at terminal: Transaction timeout", t.getString("ResponseDescription"));
    }

    @Test
    public void balanceInquiry_isINQ_withZeroMoney() throws Exception {
        TransactionLog b = approvedSale();
        b.setTransactionType("BALANCE_INQUIRY");
        b.setSaleCents(0); b.setTipCents(0); b.setCashBackCents(0); b.setFeeCents(0); b.setAmountCents(0); b.setTotalCents(0);
        JSONObject t = txn(b);
        assertEquals("INQ", t.getString("TransType"));
        assertEquals(0, t.getInt("RequestedAmt") + t.getInt("TipAmount") + t.getInt("CashBackAmount")
                + t.getInt("SurchargeAmt") + t.getInt("TotalAmt"));
    }

    @Test
    public void reversalRow_isRWT_withTheOriginalsSequence() throws Exception {
        TransactionLog r = approvedSale();
        r.setTransactionType("REVERSAL");
        r.setSequenceNumber(1);
        JSONObject t = txn(r);
        assertEquals("RWT", t.getString("TransType"));
        assertEquals(1, t.getInt("TerminalSequenceNum"));
    }

    @Test
    public void accountTypes_mapToPortalCodes() throws Exception {
        TransactionLog s = approvedSale(); s.setAccountType(10);
        assertEquals("SA", txn(s).getString("SourceAccount"));
        TransactionLog c = approvedSale(); c.setAccountType(30);
        assertEquals("CC", txn(c).getString("SourceAccount"));
    }

    @Test
    public void timeZoneFieldsFollowTheRowInstant() throws Exception {
        // Review focus 1: a January row is standard time even if sent in July
        TransactionLog w = approvedSale();
        w.setTimestamp(eastern(2026, 1, 15, 18, 30, 0));
        JSONObject t = txn(w);
        assertEquals("2026-01-15 18:30:00", t.getString("TransDateTimeUTC"));
        assertEquals("01152026", t.getString("BusinessDate"));
        assertEquals("EST", t.getString("TimeZone"));
        assertEquals(0, t.getInt("TimeZoneDST"));
    }

    @Test
    public void missingOptionalFieldsBecomeEmptyStrings() throws Exception {
        // Review focus 2: nulls must never reach the wire or throw
        TransactionLog n = approvedSale();
        n.setCardLastFour(null); n.setReferenceNumber(null); n.setErrorMessage(null); n.setFlowId(null);
        n.setResult("DECLINED"); n.setResponseCode("05");
        JSONObject body = PushPayload.of(n, ID, EASTERN);
        JSONObject t = body.getJSONObject("transactionJSON");
        assertEquals("", t.getString("CardLast4"));
        assertEquals("", t.getString("RRN"));
        assertEquals("Transaction declined", t.getString("ResponseDescription"));
        assertFalse(body.toString().contains("null"));
    }

    @Test
    public void emptyTerminalIdStillBuilds() throws Exception {
        // Review focus 5: an unconfigured terminal still produces a body (the portal will say why it is wrong)
        PushPayload.Identity bare = new PushPayload.Identity("", "0000195260000000", "test-key-not-real", "EFX", "6.2.13");
        JSONObject t = PushPayload.of(approvedSale(), bare, EASTERN).getJSONObject("transactionJSON");
        assertEquals("", t.getString("TermID"));
    }

    @Test
    public void neverContainsAFullPan() throws Exception {
        // The transaction block without the 16-digit hardware serial must hold no 13-19 digit run
        JSONObject t = txn(approvedSale());
        t.remove("tsn");
        assertFalse(t.toString(), t.toString().matches("(?s).*\\d{13,19}.*"));
    }

    @Test
    public void isHostCode_isTwoDigits() {
        assertTrue(PushPayload.isHostCode("00"));
        assertTrue(PushPayload.isHostCode("91"));
        assertFalse(PushPayload.isHostCode("MSR_NA"));
        assertFalse(PushPayload.isHostCode("A0000002"));
        assertFalse(PushPayload.isHostCode(""));
        assertFalse(PushPayload.isHostCode(null));
    }
}
