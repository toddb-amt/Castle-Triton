package castech.emvtxn.atm.report;

import java.util.ArrayList;
import java.util.List;

import castech.emvtxn.atm.TransactionLog;

/** Journal rows → report rows. */
public final class ReportRows {
    private ReportRows() {}

    public static List<ReportRow> fromLogs(List<TransactionLog> logs) {
        List<ReportRow> out = new ArrayList<>();
        for (TransactionLog l : logs) {
            int em = "CONTACT".equals(l.getEntryMode()) ? 1 : "CONTACTLESS".equals(l.getEntryMode()) ? 2
                    : "MSR".equals(l.getEntryMode()) ? 3 : 0;
            out.add(new ReportRow(l.getSequenceNumber(), l.getCardLastFour(), em, l.getAccountType(),
                    l.getAmountCents(), l.getFeeCents(), l.getTipCents(), l.getAuthCode(), l.getReferenceNumber(),
                    l.getClerkId(), l.getInvoiceNo(), l.getTransactionType(), l.getResult(), l.isReversed()));
        }
        return out;
    }
}
