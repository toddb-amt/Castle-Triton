package castech.emvtxn.atm;

import android.content.Context;
import android.util.Log;

import castech.emvtxn.GlobalPara;

/**
 * Writes one row per finished transaction into TransactionLogManager (6.2.11). Called at the
 * single completion point in MainActivity's transaction thread, which both walk-up and POS
 * flows pass through. Must never break a transaction: everything is caught and logged.
 */
public final class TransactionJournal {
    private static final String TAG = "TxnJournal";
    private TransactionJournal() {}

    public static void record(Context ctx, JournalOutcome outcome) {
        try {
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            TransactionLog log = new TransactionLog();
            // Identity shared with the reversal machinery: the pre-send reversal record id, when
            // one was armed; otherwise a local id. markReversed() keys on this (review #1).
            String rid = nz(GlobalPara.atmCurrentReversalId).trim();
            log.setTransactionId(!rid.isEmpty() ? rid : "TXN" + System.currentTimeMillis() + "-" + GlobalPara.atmSequenceNumber);
            log.setTransactionType(outcome.type);
            log.setTimestamp(System.currentTimeMillis());
            log.setCardLastFour(lastFour(GlobalPara.asciiPAN));
            log.setEntryMode(GlobalPara.atmEntryMode == 1 ? "CONTACT" : GlobalPara.atmEntryMode == 2 ? "CONTACTLESS"
                    : GlobalPara.atmEntryMode == 3 ? "MSR" : "UNKNOWN");
            boolean bi = "BALANCE_INQUIRY".equals(outcome.type);
            // AMT-03: the breakdown is the source; amount_cents stays the WITHDRAWAL (host amount)
            castech.emvtxn.AmountBreakdown a = bi ? castech.emvtxn.AmountBreakdown.balanceInquiry() : GlobalPara.atmAmounts;
            log.setSaleCents(a.sale);
            log.setTipCents(a.tip);
            log.setAmountCents(a.withdrawal);
            log.setCashBackCents(a.cashBack);
            log.setFeeCents(a.fee);
            log.setTotalCents(a.total);
            log.setResult(outcome.result);
            log.setResponseCode(nz(GlobalPara.atmResponseCode));
            log.setAuthCode(nz(GlobalPara.atmAuthCode));
            log.setReferenceNumber(nz(GlobalPara.atmReferenceNumber));
            log.setTerminalId(nz(GlobalPara.atmTerminalId));
            log.setProcessorType(nz(GlobalPara.atmProcessorType));
            log.setErrorMessage("APPROVED".equals(outcome.result) ? null : nz(GlobalPara.atmResponseMessage));
            log.setSequenceNumber(GlobalPara.atmSequenceNumber);
            log.setAccountType(GlobalPara.atmAccountType);
            log.setClerkId(blankToNull(GlobalPara.atmClerkId));
            log.setInvoiceNo(blankToNull(GlobalPara.atmInvoiceNo));
            log.setBatchId(m.currentBatchId());
            log.setReversed(false);
            // RPT-02: push bookkeeping. Pending only when a reporting key exists and the row is sendable.
            boolean configured = new castech.emvtxn.reporting.ReportingConfig(ctx).isConfigured();
            log.setFlowId(java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US));
            log.setPushState(castech.emvtxn.reporting.PushEligibility.initialState(outcome.type, outcome.result, configured));
            long id = m.saveTransaction(log);
            Log.d(TAG, "journaled " + outcome.type + "/" + outcome.result + " seq=" + GlobalPara.atmSequenceNumber
                    + " batch=" + log.getBatchId() + " row=" + id
                    + " push=" + (log.getPushState() == castech.emvtxn.reporting.PushEligibility.PUSH_PENDING ? "pending" : "n/a"));
            if (id != -1 && log.getPushState() == castech.emvtxn.reporting.PushEligibility.PUSH_PENDING) {
                castech.emvtxn.reporting.PushSignal.newRow();
            }
        } catch (Throwable t) {
            Log.w(TAG, "journal write skipped: " + t.getMessage());
        } finally {
            // Consumed: never let a register's clerk/invoice or this sequence leak into the next
            // transaction (the receipt's "New Transaction" path skips the full reset) — review #8/#10.
            GlobalPara.atmClerkId = "";
            GlobalPara.atmInvoiceNo = "";
            GlobalPara.atmSequenceNumber = 0;
        }
    }

    public static void markReversed(Context ctx, String transactionId) {
        try {
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            boolean hit = m.markReversed(transactionId);
            Log.w(TAG, "reversal accepted for " + transactionId + " → journal row "
                    + (hit ? "marked reversed" : "not found (nothing journaled under that id)"));
            // RPT-02: the accepted reversal becomes its own row, pushed to the portal as RWT
            if (hit) {
                TransactionLog original = m.getTransactionById(transactionId);
                if (original != null) {
                    boolean configured = new castech.emvtxn.reporting.ReportingConfig(ctx).isConfigured();
                    long row = m.insertReversalRow(original, configured);
                    Log.w(TAG, "reversal row " + row + " written for seq " + original.getSequenceNumber()
                            + (configured ? " (pending push as RWT)" : ""));
                    if (row != -1 && configured) castech.emvtxn.reporting.PushSignal.newRow();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "markReversed skipped: " + t.getMessage());
        }
    }

    static String lastFour(String pan) {
        if (pan == null) return "";
        String digits = pan.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
