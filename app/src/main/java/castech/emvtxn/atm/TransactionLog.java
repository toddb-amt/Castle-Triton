package castech.emvtxn.atm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Data model for ATM transaction log entries.
 * Stores all relevant transaction details for audit trail purposes.
 */
public class TransactionLog {

    // Transaction types
    public static final String TYPE_WITHDRAWAL = "WITHDRAWAL";
    public static final String TYPE_BALANCE_INQUIRY = "BALANCE_INQUIRY";
    public static final String TYPE_REVERSAL = "REVERSAL";

    // Transaction results
    public static final String RESULT_APPROVED = "APPROVED";
    public static final String RESULT_DECLINED = "DECLINED";
    public static final String RESULT_TIMEOUT = "TIMEOUT";
    public static final String RESULT_ERROR = "ERROR";
    public static final String RESULT_CANCELLED = "CANCELLED";

    // Entry modes
    public static final String ENTRY_CONTACT = "CONTACT";
    public static final String ENTRY_CONTACTLESS = "CONTACTLESS";
    public static final String ENTRY_MSR = "MSR";
    public static final String ENTRY_UNKNOWN = "UNKNOWN";

    // Fields
    private long id;                    // Database ID
    private String transactionId;       // Unique transaction identifier
    private String transactionType;     // WITHDRAWAL, BALANCE_INQUIRY, REVERSAL
    private long timestamp;             // Unix timestamp in milliseconds
    private String cardLastFour;        // Last 4 digits of card
    private String entryMode;           // CONTACT, CONTACTLESS, MSR
    private long amountCents;           // Amount in cents
    private long feeCents;              // Fee in cents
    private long totalCents;            // Total in cents
    private String result;              // APPROVED, DECLINED, etc.
    private String responseCode;        // Host response code (e.g., "00", "51")
    private String authCode;            // Authorization code from host
    private String referenceNumber;     // Reference number from host
    private String terminalId;          // Terminal ID used
    private String processorType;       // DNS, SWITCH_COMMERCE, EFX
    private long balanceAccountCents;   // Account balance (for balance inquiry)
    private long balanceAvailableCents; // Available balance (for balance inquiry)
    private String errorMessage;        // Error details if failed

    /**
     * Default constructor.
     */
    public TransactionLog() {
        this.timestamp = System.currentTimeMillis();
        this.transactionId = generateTransactionId();
    }

    /**
     * Creates a withdrawal transaction log entry.
     */
    public static TransactionLog createWithdrawal(String cardLastFour, String entryMode,
            long amountCents, long feeCents, String terminalId, String processorType) {
        TransactionLog log = new TransactionLog();
        log.transactionType = TYPE_WITHDRAWAL;
        log.cardLastFour = cardLastFour;
        log.entryMode = entryMode;
        log.amountCents = amountCents;
        log.feeCents = feeCents;
        log.totalCents = amountCents + feeCents;
        log.terminalId = terminalId;
        log.processorType = processorType;
        return log;
    }

    /**
     * Creates a balance inquiry transaction log entry.
     */
    public static TransactionLog createBalanceInquiry(String cardLastFour, String entryMode,
            String terminalId, String processorType) {
        TransactionLog log = new TransactionLog();
        log.transactionType = TYPE_BALANCE_INQUIRY;
        log.cardLastFour = cardLastFour;
        log.entryMode = entryMode;
        log.amountCents = 0;
        log.feeCents = 0;
        log.totalCents = 0;
        log.terminalId = terminalId;
        log.processorType = processorType;
        return log;
    }

    /**
     * Creates a reversal transaction log entry.
     */
    public static TransactionLog createReversal(String originalTransactionId,
            long amountCents, String terminalId) {
        TransactionLog log = new TransactionLog();
        log.transactionType = TYPE_REVERSAL;
        log.amountCents = amountCents;
        log.terminalId = terminalId;
        log.referenceNumber = originalTransactionId;  // Store original txn ID
        return log;
    }

    /**
     * Generates a unique transaction ID.
     */
    private String generateTransactionId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        String timestamp = sdf.format(new Date());
        int random = (int) (Math.random() * 9999);
        return timestamp + String.format(Locale.US, "%04d", random);
    }

    /**
     * Marks the transaction as approved.
     */
    public void setApproved(String authCode, String referenceNumber) {
        this.result = RESULT_APPROVED;
        this.authCode = authCode;
        this.referenceNumber = referenceNumber;
        this.responseCode = "00";
    }

    /**
     * Marks the transaction as declined.
     */
    public void setDeclined(String responseCode, String errorMessage) {
        this.result = RESULT_DECLINED;
        this.responseCode = responseCode;
        this.errorMessage = errorMessage;
    }

    /**
     * Marks the transaction as timed out.
     */
    public void setTimeout() {
        this.result = RESULT_TIMEOUT;
        this.responseCode = "91";
        this.errorMessage = "Transaction timed out";
    }

    /**
     * Marks the transaction as errored.
     */
    public void setError(String errorMessage) {
        this.result = RESULT_ERROR;
        this.errorMessage = errorMessage;
    }

    /**
     * Sets balance inquiry results.
     */
    public void setBalanceResult(long accountBalanceCents, long availableBalanceCents) {
        this.balanceAccountCents = accountBalanceCents;
        this.balanceAvailableCents = availableBalanceCents;
    }

    // Getters and Setters

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getCardLastFour() { return cardLastFour; }
    public void setCardLastFour(String cardLastFour) { this.cardLastFour = cardLastFour; }

    public String getEntryMode() { return entryMode; }
    public void setEntryMode(String entryMode) { this.entryMode = entryMode; }

    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

    public long getFeeCents() { return feeCents; }
    public void setFeeCents(long feeCents) { this.feeCents = feeCents; }

    public long getTotalCents() { return totalCents; }
    public void setTotalCents(long totalCents) { this.totalCents = totalCents; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }

    public String getAuthCode() { return authCode; }
    public void setAuthCode(String authCode) { this.authCode = authCode; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String terminalId) { this.terminalId = terminalId; }

    public String getProcessorType() { return processorType; }
    public void setProcessorType(String processorType) { this.processorType = processorType; }

    public long getBalanceAccountCents() { return balanceAccountCents; }
    public void setBalanceAccountCents(long balanceAccountCents) { this.balanceAccountCents = balanceAccountCents; }

    public long getBalanceAvailableCents() { return balanceAvailableCents; }
    public void setBalanceAvailableCents(long balanceAvailableCents) { this.balanceAvailableCents = balanceAvailableCents; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    // Formatting helpers

    /**
     * Gets formatted date/time string.
     */
    public String getFormattedDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    /**
     * Gets formatted date string only.
     */
    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    /**
     * Gets formatted time string only.
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    /**
     * Gets formatted amount as currency string.
     */
    public String getFormattedAmount() {
        return String.format(Locale.US, "$%.2f", amountCents / 100.0);
    }

    /**
     * Gets formatted fee as currency string.
     */
    public String getFormattedFee() {
        return String.format(Locale.US, "$%.2f", feeCents / 100.0);
    }

    /**
     * Gets formatted total as currency string.
     */
    public String getFormattedTotal() {
        return String.format(Locale.US, "$%.2f", totalCents / 100.0);
    }

    /**
     * Gets masked card number.
     */
    public String getMaskedCard() {
        if (cardLastFour == null || cardLastFour.isEmpty()) {
            return "****";
        }
        return "****" + cardLastFour;
    }

    /**
     * Gets a one-line summary of the transaction.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(getFormattedDateTime());
        sb.append(" | ");
        sb.append(transactionType);
        if (TYPE_WITHDRAWAL.equals(transactionType)) {
            sb.append(" ").append(getFormattedAmount());
        }
        sb.append(" | ");
        sb.append(result != null ? result : "PENDING");
        sb.append(" | ");
        sb.append(getMaskedCard());
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TransactionLog{" +
                "id=" + id +
                ", transactionId='" + transactionId + '\'' +
                ", type='" + transactionType + '\'' +
                ", timestamp=" + getFormattedDateTime() +
                ", card='" + getMaskedCard() + '\'' +
                ", amount=" + getFormattedAmount() +
                ", result='" + result + '\'' +
                '}';
    }
}
