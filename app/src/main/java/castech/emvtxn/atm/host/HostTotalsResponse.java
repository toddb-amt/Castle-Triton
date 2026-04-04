package castech.emvtxn.atm.host;

/**
 * Response data for Host Totals (Type 87) message.
 *
 * Response format:
 *   Field 0: Information Header
 *   Field 1: Terminal ID
 *   Field 2: Transaction Code (87)
 *   Field 3: Transaction Counts (16 chars: CCCCTTTTBBBBNNNN)
 *   Field 4: Total Cash Dispensed (cents)
 *   Field 5: Total Non-Cash (cents)
 *   Field 6: Total Surcharges (cents)
 */
public class HostTotalsResponse {

    // Raw fields from response
    private String terminalId;
    private String transactionCounts;  // Field 3: 16-char count string
    private long totalCashDispensed;   // Field 4: in cents
    private long totalNonCash;         // Field 5: in cents
    private long totalSurcharges;      // Field 6: in cents

    // Parsed transaction counts
    private int withdrawalCount;       // Positions 1-4
    private int transferCount;         // Positions 5-8
    private int balanceInquiryCount;   // Positions 9-12
    private int nonCashCount;          // Positions 13-16

    // Response status
    private boolean success;
    private String errorMessage;

    public HostTotalsResponse() {
        this.success = false;
    }

    // =========================================================================
    // Setters with parsing
    // =========================================================================

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    /**
     * Sets and parses the transaction counts field.
     * Format: CCCCTTTTBBBBNNNN (16 chars)
     *   CCCC = Cash Withdrawal count
     *   TTTT = Transfer count
     *   BBBB = Balance Inquiry count
     *   NNNN = Non-Cash Withdrawal count
     */
    public void setTransactionCounts(String counts) {
        this.transactionCounts = counts;
        parseTransactionCounts();
    }

    public void setTotalCashDispensed(long cents) {
        this.totalCashDispensed = cents;
    }

    public void setTotalNonCash(long cents) {
        this.totalNonCash = cents;
    }

    public void setTotalSurcharges(long cents) {
        this.totalSurcharges = cents;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getTerminalId() {
        return terminalId;
    }

    public String getTransactionCounts() {
        return transactionCounts;
    }

    public long getTotalCashDispensed() {
        return totalCashDispensed;
    }

    public long getTotalNonCash() {
        return totalNonCash;
    }

    public long getTotalSurcharges() {
        return totalSurcharges;
    }

    public int getWithdrawalCount() {
        return withdrawalCount;
    }

    public int getTransferCount() {
        return transferCount;
    }

    public int getBalanceInquiryCount() {
        return balanceInquiryCount;
    }

    public int getNonCashCount() {
        return nonCashCount;
    }

    public int getTotalTransactionCount() {
        return withdrawalCount + transferCount + balanceInquiryCount + nonCashCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // =========================================================================
    // Formatted output helpers
    // =========================================================================

    /**
     * Returns total cash dispensed as formatted dollar string.
     */
    public String getTotalCashDispensedFormatted() {
        return String.format("$%.2f", totalCashDispensed / 100.0);
    }

    /**
     * Returns total non-cash as formatted dollar string.
     */
    public String getTotalNonCashFormatted() {
        return String.format("$%.2f", totalNonCash / 100.0);
    }

    /**
     * Returns total surcharges as formatted dollar string.
     */
    public String getTotalSurchargesFormatted() {
        return String.format("$%.2f", totalSurcharges / 100.0);
    }

    /**
     * Returns a summary string for display.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== HOST TOTALS ===\n\n");
        sb.append("Terminal: ").append(terminalId != null ? terminalId : "N/A").append("\n\n");

        sb.append("TRANSACTION COUNTS:\n");
        sb.append("  Withdrawals:      ").append(withdrawalCount).append("\n");
        sb.append("  Balance Inquiries: ").append(balanceInquiryCount).append("\n");
        sb.append("  Transfers:        ").append(transferCount).append("\n");
        sb.append("  Non-Cash:         ").append(nonCashCount).append("\n");
        sb.append("  -------------------\n");
        sb.append("  TOTAL:            ").append(getTotalTransactionCount()).append("\n\n");

        sb.append("AMOUNTS:\n");
        sb.append("  Cash Dispensed:   ").append(getTotalCashDispensedFormatted()).append("\n");
        sb.append("  Non-Cash:         ").append(getTotalNonCashFormatted()).append("\n");
        sb.append("  Surcharges:       ").append(getTotalSurchargesFormatted()).append("\n");

        return sb.toString();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void parseTransactionCounts() {
        if (transactionCounts == null || transactionCounts.length() < 16) {
            withdrawalCount = 0;
            transferCount = 0;
            balanceInquiryCount = 0;
            nonCashCount = 0;
            return;
        }

        try {
            withdrawalCount = Integer.parseInt(transactionCounts.substring(0, 4));
            transferCount = Integer.parseInt(transactionCounts.substring(4, 8));
            balanceInquiryCount = Integer.parseInt(transactionCounts.substring(8, 12));
            nonCashCount = Integer.parseInt(transactionCounts.substring(12, 16));
        } catch (NumberFormatException e) {
            withdrawalCount = 0;
            transferCount = 0;
            balanceInquiryCount = 0;
            nonCashCount = 0;
        }
    }

    @Override
    public String toString() {
        return "HostTotalsResponse{" +
                "terminalId='" + terminalId + '\'' +
                ", withdrawals=" + withdrawalCount +
                ", transfers=" + transferCount +
                ", balanceInquiries=" + balanceInquiryCount +
                ", nonCash=" + nonCashCount +
                ", totalCash=" + getTotalCashDispensedFormatted() +
                ", surcharges=" + getTotalSurchargesFormatted() +
                ", success=" + success +
                '}';
    }
}
