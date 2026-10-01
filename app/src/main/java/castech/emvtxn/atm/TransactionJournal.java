package castech.emvtxn.atm;

import android.content.Context;
import android.util.Log;

import castech.emvtxn.GlobalPara;
import castech.emvtxn.Money;

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
            log.setTransactionId("TXN" + System.currentTimeMillis() + "-" + GlobalPara.atmSequenceNumber);
            log.setTransactionType(outcome.type);
            log.setTimestamp(System.currentTimeMillis());
            log.setCardLastFour(lastFour(GlobalPara.asciiPAN));
            log.setEntryMode(GlobalPara.atmEntryMode == 1 ? "CONTACT" : GlobalPara.atmEntryMode == 2 ? "CONTACTLESS"
                    : GlobalPara.atmEntryMode == 3 ? "MSR" : "UNKNOWN");
            boolean bi = "BALANCE_INQUIRY".equals(outcome.type);
            long amount = bi ? 0L : Money.toCents(parse(GlobalPara.atmSelectedAmount));
            long fee = bi ? 0L : Money.toCents(parse(GlobalPara.atmFee));
            log.setAmountCents(amount);
            log.setFeeCents(fee);
            log.setTipCents(0L);
            log.setTotalCents(amount + fee);
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
            long id = m.saveTransaction(log);
            Log.d(TAG, "journaled " + outcome.type + "/" + outcome.result + " seq=" + GlobalPara.atmSequenceNumber
                    + " batch=" + log.getBatchId() + " row=" + id);
        } catch (Throwable t) {
            Log.w(TAG, "journal write skipped: " + t.getMessage());
        }
    }

    public static void markReversed(Context ctx, int sequenceNumber) {
        try {
            TransactionLogManager m = TransactionLogManager.getInstance(ctx.getApplicationContext());
            boolean hit = m.markReversed(sequenceNumber, m.currentBatchId());
            Log.w(TAG, "reversal accepted for seq " + sequenceNumber + " → journal row "
                    + (hit ? "marked reversed" : "not found (pre-send failure, nothing approved)"));
        } catch (Throwable t) {
            Log.w(TAG, "markReversed skipped: " + t.getMessage());
        }
    }

    static String lastFour(String pan) {
        if (pan == null) return "";
        String digits = pan.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
    }
    private static double parse(String dollars) {
        try { return Double.parseDouble(dollars.trim()); } catch (Exception e) { return 0d; }
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
