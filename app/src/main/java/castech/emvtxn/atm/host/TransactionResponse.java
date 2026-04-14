package castech.emvtxn.atm.host;

/**
 * Transaction Response (Type 85)
 *
 * Represents a financial transaction response from the host processor.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (85)
 *   Field 3:  Sequence Number (echo of request)
 *   Field 4:  Response Code (2-digit ISO 8583)
 *   Field 5:  Authorization Data (date/time + retrieval ref)
 *   Field 6:  Settlement Data (audit trail + settlement date)
 *   Field 7:  Account Balance (cents)
 *   Field 8:  Available Balance (cents)
 *   Field 9:  Surcharge (cents)
 *   Field 10: Display Message
 *   Field 11: Config Indicator (01 = config required)
 *   Field 12: EMV Response Data
 */
public class TransactionResponse {

    private String infoHeader;              // Field 0
    private String terminalId;              // Field 1
    private int sequenceNumber;             // Field 3
    private String responseCode;            // Field 4
    private String authorizationData;       // Field 5: MMDDYYYYHHmmssRRRRRRRRRRRR
    private String settlementData;          // Field 6: AAAAAASSMMDDYYYY
    private long accountBalanceCents;       // Field 7
    private long availableBalanceCents;     // Field 8
    private long surchargeCents;            // Field 9
    private String displayMessage;          // Field 10
    private String configIndicator;         // Field 11
    private String emvResponseData;         // Field 12

    // Parsed authorization data components
    private String authMonth;
    private String authDay;
    private String authYear;
    private String authHour;
    private String authMinute;
    private String authSecond;
    private String retrievalReferenceNumber;

    // Parsed settlement data components
    private String auditTrailNumber;
    private String settlementIndicator;
    private String settlementDate;

    /**
     * Creates a new empty TransactionResponse.
     */
    public TransactionResponse() {
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    public String getInfoHeader() {
        return infoHeader;
    }

    public void setInfoHeader(String infoHeader) {
        this.infoHeader = infoHeader;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    public String getTransactionCode() {
        return HyosungProtocol.MSG_TYPE_TRANSACTION;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getAuthorizationData() {
        return authorizationData;
    }

    /**
     * Sets and parses the authorization data field.
     * Format: MMDDYYYYHHmmssRRRRRRRRRRRR (26 chars)
     */
    public void setAuthorizationData(String authorizationData) {
        this.authorizationData = authorizationData;
        parseAuthorizationData();
    }

    public String getSettlementData() {
        return settlementData;
    }

    /**
     * Sets and parses the settlement data field.
     * Format: AAAAAASSMMDDYYYY (16 chars)
     */
    public void setSettlementData(String settlementData) {
        this.settlementData = settlementData;
        parseSettlementData();
    }

    public long getAccountBalanceCents() {
        return accountBalanceCents;
    }

    public void setAccountBalanceCents(long accountBalanceCents) {
        this.accountBalanceCents = accountBalanceCents;
    }

    /**
     * Gets account balance in dollars.
     */
    public double getAccountBalanceDollars() {
        return accountBalanceCents / 100.0;
    }

    public long getAvailableBalanceCents() {
        return availableBalanceCents;
    }

    public void setAvailableBalanceCents(long availableBalanceCents) {
        this.availableBalanceCents = availableBalanceCents;
    }

    /**
     * Gets available balance in dollars.
     */
    public double getAvailableBalanceDollars() {
        return availableBalanceCents / 100.0;
    }

    public long getSurchargeCents() {
        return surchargeCents;
    }

    public void setSurchargeCents(long surchargeCents) {
        this.surchargeCents = surchargeCents;
    }

    public String getDisplayMessage() {
        return displayMessage;
    }

    public void setDisplayMessage(String displayMessage) {
        this.displayMessage = displayMessage;
    }

    public String getConfigIndicator() {
        return configIndicator;
    }

    public void setConfigIndicator(String configIndicator) {
        this.configIndicator = configIndicator;
    }

    public String getEmvResponseData() {
        return emvResponseData;
    }

    public void setEmvResponseData(String emvResponseData) {
        this.emvResponseData = emvResponseData;
    }

    // =========================================================================
    // Parsed Field Getters
    // =========================================================================

    public String getRetrievalReferenceNumber() {
        return retrievalReferenceNumber;
    }

    public String getAuditTrailNumber() {
        return auditTrailNumber;
    }

    public String getSettlementIndicator() {
        return settlementIndicator;
    }

    public String getSettlementDate() {
        return settlementDate;
    }

    /**
     * Gets formatted authorization date (MM/DD/YYYY).
     */
    public String getFormattedAuthDate() {
        if (authMonth != null && authDay != null && authYear != null) {
            return authMonth + "/" + authDay + "/" + authYear;
        }
        return null;
    }

    /**
     * Gets formatted authorization time (HH:mm:ss).
     */
    public String getFormattedAuthTime() {
        if (authHour != null && authMinute != null && authSecond != null) {
            return authHour + ":" + authMinute + ":" + authSecond;
        }
        return null;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if the transaction was approved.
     */
    public boolean isApproved() {
        // Check both Hyosung ("00") and Triton ("000") approval codes
        return HyosungProtocol.isApproved(responseCode) || "000".equals(responseCode);
    }

    /**
     * Gets human-readable description for Triton response codes.
     */
    public static String getTritonResponseDescription(String code) {
        if (code == null) return "Unknown";
        switch (code) {
            case "000": return "Transaction Approved";
            case "001": return "Expired Card";
            case "002": return "Unauthorized Usage";
            case "003": return "PIN Error";
            case "004": return "Invalid PIN";
            case "005": return "Bank Unavailable";
            case "006": return "Card Not Supported";
            case "007": return "Insufficient Funds";
            case "008": return "Ineligible Transaction";
            case "009": return "Ineligible Account";
            case "010": return "Daily Withdrawals Exceeded";
            case "011": return "Cannot Process Transaction";
            case "012": return "Amount Too Large";
            case "013": return "Account Closed";
            case "014": return "PIN Tries Exceeded";
            case "015": return "Database Problem";
            case "016": return "Withdrawal Limit Reached";
            case "017": return "Invalid Amount";
            case "018": return "External Decline";
            case "019": return "System Error";
            case "020": return "Contact Card Issuer";
            case "021": return "Routing Lookup Problem";
            case "022": return "Message Edit Error";
            case "023": return "Transaction Not Supported";
            case "024": return "Insufficient Funds";
            case "027": return "CRC Error";
            case "033": return "Response Exceeds Message Size";
            case "034": return "Missing Information";
            case "035": return "Second Invalid PIN";
            case "111": return "Reversal Declined";
            case "222": return "PIN Change Declined";
            default: return "Declined (" + code + ")";
        }
    }

    /**
     * Checks if the transaction was partially approved.
     */
    public boolean isPartialApproval() {
        return HyosungProtocol.RESP_PARTIAL_APPROVAL.equals(responseCode);
    }

    /**
     * Checks if the card should be retained.
     */
    public boolean shouldRetainCard() {
        return HyosungProtocol.shouldRetainCard(responseCode);
    }

    /**
     * Checks if a key sync is required.
     */
    public boolean requiresKeySync() {
        return HyosungProtocol.requiresKeySync(responseCode);
    }

    /**
     * Checks if configuration download is required.
     */
    public boolean requiresConfiguration() {
        return "01".equals(configIndicator);
    }

    /**
     * Gets the human-readable response description.
     */
    public String getResponseDescription() {
        return HyosungProtocol.getResponseDescription(responseCode);
    }

    /**
     * Parses the authorization data field into components.
     * Format: MMDDYYYYHHmmssRRRRRRRRRRRR
     */
    private void parseAuthorizationData() {
        if (authorizationData == null || authorizationData.length() < 14) {
            return;
        }

        try {
            authMonth = authorizationData.substring(0, 2);
            authDay = authorizationData.substring(2, 4);
            authYear = authorizationData.substring(4, 8);
            authHour = authorizationData.substring(8, 10);
            authMinute = authorizationData.substring(10, 12);
            authSecond = authorizationData.substring(12, 14);

            if (authorizationData.length() >= 26) {
                retrievalReferenceNumber = authorizationData.substring(14, 26);
            }
        } catch (Exception e) {
            // Invalid format, leave parsed fields null
        }
    }

    /**
     * Parses the settlement data field into components.
     * Format: AAAAAASSMMDDYYYY
     */
    private void parseSettlementData() {
        if (settlementData == null || settlementData.length() < 16) {
            return;
        }

        try {
            auditTrailNumber = settlementData.substring(0, 6);
            settlementIndicator = settlementData.substring(6, 8);
            settlementDate = settlementData.substring(8, 16); // MMDDYYYY
        } catch (Exception e) {
            // Invalid format, leave parsed fields null
        }
    }

    @Override
    public String toString() {
        return "TransactionResponse{" +
               "terminalId='" + terminalId + '\'' +
               ", seq=" + sequenceNumber +
               ", responseCode='" + responseCode + '\'' +
               ", approved=" + isApproved() +
               ", balance=" + accountBalanceCents +
               ", available=" + availableBalanceCents +
               ", rrn='" + retrievalReferenceNumber + '\'' +
               ", message='" + displayMessage + '\'' +
               '}';
    }
}
