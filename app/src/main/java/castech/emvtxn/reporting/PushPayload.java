package castech.emvtxn.reporting;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.json.JSONException;
import org.json.JSONObject;

import castech.emvtxn.atm.TransactionLog;

/**
 * Builds the MyView ingestion request from a journal row (RPT-02, 6.2.13). Pure; pinned by
 * PushPayloadTest against the portal contract's own example. All money is whole cents.
 * {@code TransDateTimeUTC} is, per the contract, the terminal's LOCAL time of the row.
 */
public final class PushPayload {

    /** Who we are to the portal. */
    public static final class Identity {
        public final String terminalId;      // CasHUB terminal_id → TermID / HostTermID
        public final String hardwareSerial;  // Castle factory serial → tsn
        public final String accessKey;       // tenantAccessKey
        public final String processorName;   // Host
        public final String appVersion;      // Software
        public Identity(String terminalId, String hardwareSerial, String accessKey, String processorName, String appVersion) {
            this.terminalId = nz(terminalId);
            this.hardwareSerial = nz(hardwareSerial);
            this.accessKey = nz(accessKey);
            this.processorName = nz(processorName);
            this.appVersion = nz(appVersion);
        }
    }

    private PushPayload() {}

    public static JSONObject of(TransactionLog row, Identity id, TimeZone zone) {
        try {
            Date when = new Date(row.getTimestamp());
            SimpleDateFormat dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat bd = new SimpleDateFormat("MMddyyyy", Locale.US);
            dt.setTimeZone(zone);
            bd.setTimeZone(zone);
            boolean approved = "APPROVED".equals(row.getResult());

            JSONObject t = new JSONObject();
            t.put("tsn", id.hardwareSerial);
            t.put("TermID", id.terminalId);
            t.put("TerminalSequenceNum", Math.max(0, row.getSequenceNumber()));
            t.put("TransDateTimeUTC", dt.format(when));
            t.put("RRN", nz(row.getReferenceNumber()));
            t.put("SourceAccount", sourceAccount(row.getAccountType()));
            t.put("RequestedAmt", row.getSaleCents());
            t.put("TipAmount", row.getTipCents());
            t.put("CashBackAmount", row.getCashBackCents());
            t.put("SurchargeAmt", row.getFeeCents());
            t.put("TotalAmt", row.getAmountCents() + row.getFeeCents());
            t.put("CardLast4", nz(row.getCardLastFour()));
            t.put("ResponseDescription", description(row, approved));
            t.put("Host", id.processorName);
            t.put("HostTermID", id.terminalId);
            t.put("BusinessDate", bd.format(when));
            t.put("TransType", transType(row.getTransactionType()));
            t.put("Approved", approved ? 1 : 0);
            t.put("TimeZone", zone.getDisplayName(false, TimeZone.SHORT, Locale.US));
            t.put("TimeZoneDST", zone.inDaylightTime(when) ? 1 : 0);
            t.put("Software", "Castle S1FP-TFI " + id.appVersion);

            JSONObject body = new JSONObject();
            body.put("flow_id", nz(row.getFlowId()));
            body.put("tenantAccessKey", id.accessKey);
            body.put("tsn", id.hardwareSerial);
            body.put("transactionJSON", t);
            return body;
        } catch (JSONException e) {
            throw new IllegalStateException("payload build failed: " + e.getMessage(), e);
        }
    }

    /** WTH for withdrawals and sales, INQ for balance inquiries, RWT for reversals (contract section 3). */
    public static String transType(String transactionType) {
        if ("BALANCE_INQUIRY".equals(transactionType)) return "INQ";
        if ("REVERSAL".equals(transactionType)) return "RWT";
        return "WTH";
    }

    /** A two-digit response code came from the host; anything else is the terminal's own ending. */
    public static boolean isHostCode(String responseCode) {
        return responseCode != null && responseCode.matches("\\d{2}");
    }

    private static String description(TransactionLog row, boolean approved) {
        if (approved) return "Transaction approved";
        String msg = nz(row.getErrorMessage()).trim();
        if (isHostCode(row.getResponseCode())) return msg.isEmpty() ? "Transaction declined" : msg;
        return "Declined at terminal: " + (msg.isEmpty() ? "no reason recorded" : msg);
    }

    /** GlobalPara.ATM_ACCOUNT_SAVINGS = 10, _CHECKING = 20, _CREDIT = 30. */
    private static String sourceAccount(int accountType) {
        switch (accountType) {
            case 10: return "SA";
            case 30: return "CC";
            default: return "CA";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
