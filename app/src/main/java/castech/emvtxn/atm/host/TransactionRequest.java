package castech.emvtxn.atm.host;

/**
 * Transaction Request (Type 85)
 *
 * Represents a financial transaction request such as cash withdrawal,
 * balance inquiry, or transfer.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (85)
 *   Field 3:  Transaction Type (CWCACA, BISASA, etc.)
 *   Field 4:  Sequence Number (0001-9999)
 *   Field 5:  (Reserved - empty)
 *   Field 6:  Track 2 Data
 *   Field 7:  (Reserved - empty)
 *   Field 8:  PIN Block (16 hex chars)
 *   Field 9:  Amount (cents)
 *   Field 10: Surcharge (cents)
 *   Field 11: Dispense Flag (1=dispense, 0=no dispense)
 *   Field 12: Status Monitoring
 *   Field 13: EMV Data
 */
public class TransactionRequest {

    private String infoHeader;          // Field 0: H0.NNNNNN
    private String terminalId;          // Field 1: Terminal ID (6-8 chars)
    private String transactionType;     // Field 3: e.g., CWCACA, BISASA
    private int sequenceNumber;         // Field 4: 0001-9999
    private String track2Data;          // Field 6: Card track 2 with sentinels
    private String encryptedTrack2;     // DUKPT encrypted track 2 (hex string)
    private String track2Ksn;           // DUKPT KSN for track 2 encryption (hex string)
    private String pinKsn;              // DUKPT KSN for PIN encryption (hex string) - may differ from track2Ksn
    private String pinBlock;            // Field 8: 16 hex characters (encrypted)
    private long amountCents;           // Field 9: Amount in cents
    private long surchargeCents;        // Field 10: Surcharge in cents
    private boolean dispense;           // Field 11: Whether to dispense cash
    private String statusMonitoring;    // Field 12: Terminal status data
    private String emvData;             // Field 13: EMV chip data (prefixed with "ud")

    /**
     * Creates a new TransactionRequest with default values.
     */
    public TransactionRequest() {
        this.infoHeader = HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);
        this.sequenceNumber = 1;
        this.dispense = true;
    }

    /**
     * Creates a cash withdrawal request.
     *
     * @param terminalId Terminal ID
     * @param track2Data Card track 2 data
     * @param pinBlock Encrypted PIN block
     * @param amountCents Amount in cents
     * @param surchargeCents Surcharge in cents
     * @param accountType Account type (CA, SA, CR)
     * @return Configured TransactionRequest
     */
    public static TransactionRequest createCashWithdrawal(String terminalId, String track2Data,
            String pinBlock, long amountCents, long surchargeCents, String accountType) {
        TransactionRequest req = new TransactionRequest();
        req.setTerminalId(terminalId);
        req.setTransactionType(HyosungProtocol.OP_CASH_WITHDRAWAL + accountType + accountType);
        req.setTrack2Data(track2Data);
        req.setPinBlock(pinBlock);
        req.setAmountCents(amountCents);
        req.setSurchargeCents(surchargeCents);
        req.setDispense(true);
        return req;
    }

    /**
     * Creates a balance inquiry request.
     *
     * @param terminalId Terminal ID
     * @param track2Data Card track 2 data
     * @param pinBlock Encrypted PIN block
     * @param accountType Account type (CA, SA, CR)
     * @return Configured TransactionRequest
     */
    public static TransactionRequest createBalanceInquiry(String terminalId, String track2Data,
            String pinBlock, String accountType) {
        TransactionRequest req = new TransactionRequest();
        req.setTerminalId(terminalId);
        req.setTransactionType(HyosungProtocol.OP_BALANCE_INQUIRY + accountType + accountType);
        req.setTrack2Data(track2Data);
        req.setPinBlock(pinBlock);
        req.setAmountCents(0);
        req.setSurchargeCents(0);
        req.setDispense(false);
        return req;
    }

    /**
     * Creates a transfer request.
     *
     * @param terminalId Terminal ID
     * @param track2Data Card track 2 data
     * @param pinBlock Encrypted PIN block
     * @param amountCents Amount to transfer in cents
     * @param sourceAccount Source account type (CA, SA)
     * @param destAccount Destination account type (CA, SA)
     * @return Configured TransactionRequest
     */
    public static TransactionRequest createTransfer(String terminalId, String track2Data,
            String pinBlock, long amountCents, String sourceAccount, String destAccount) {
        TransactionRequest req = new TransactionRequest();
        req.setTerminalId(terminalId);
        req.setTransactionType(HyosungProtocol.OP_TRANSFER + sourceAccount + destAccount);
        req.setTrack2Data(track2Data);
        req.setPinBlock(pinBlock);
        req.setAmountCents(amountCents);
        req.setSurchargeCents(0);
        req.setDispense(false);
        return req;
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

    public void setRoutingId(String routingId) {
        this.infoHeader = HyosungProtocol.buildInfoHeader(routingId);
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

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        // Wrap at 9999 back to 0001
        if (sequenceNumber > 9999) {
            sequenceNumber = 1;
        } else if (sequenceNumber < 1) {
            sequenceNumber = 1;
        }
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Gets sequence number as 4-digit string (zero-padded).
     */
    public String getSequenceNumberString() {
        return String.format("%04d", sequenceNumber);
    }

    public String getTrack2Data() {
        return track2Data;
    }

    public void setTrack2Data(String track2Data) {
        this.track2Data = track2Data;
    }

    public String getEncryptedTrack2() {
        return encryptedTrack2;
    }

    public void setEncryptedTrack2(String encryptedTrack2) {
        this.encryptedTrack2 = encryptedTrack2;
    }

    public String getTrack2Ksn() {
        return track2Ksn;
    }

    public void setTrack2Ksn(String track2Ksn) {
        this.track2Ksn = track2Ksn;
    }

    public String getPinKsn() {
        return pinKsn;
    }

    public void setPinKsn(String pinKsn) {
        this.pinKsn = pinKsn;
    }

    /**
     * Checks if encrypted track 2 data is available.
     */
    public boolean hasEncryptedTrack2() {
        return encryptedTrack2 != null && !encryptedTrack2.isEmpty()
            && track2Ksn != null && !track2Ksn.isEmpty();
    }

    public String getPinBlock() {
        return pinBlock;
    }

    public void setPinBlock(String pinBlock) {
        this.pinBlock = pinBlock;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    /**
     * Sets amount in dollars (converted to cents).
     */
    public void setAmountDollars(double dollars) {
        this.amountCents = Math.round(dollars * 100);
    }

    /**
     * Gets amount in dollars.
     */
    public double getAmountDollars() {
        return amountCents / 100.0;
    }

    public long getSurchargeCents() {
        return surchargeCents;
    }

    public void setSurchargeCents(long surchargeCents) {
        this.surchargeCents = surchargeCents;
    }

    /**
     * Sets surcharge in dollars (converted to cents).
     */
    public void setSurchargeDollars(double dollars) {
        this.surchargeCents = Math.round(dollars * 100);
    }

    public boolean isDispense() {
        return dispense;
    }

    public void setDispense(boolean dispense) {
        this.dispense = dispense;
    }

    /**
     * Gets dispense flag as string ("1" or "0").
     */
    public String getDispenseFlag() {
        return dispense ? HyosungProtocol.DISPENSE_YES : HyosungProtocol.DISPENSE_NO;
    }

    public String getStatusMonitoring() {
        return statusMonitoring;
    }

    public void setStatusMonitoring(String statusMonitoring) {
        this.statusMonitoring = statusMonitoring;
    }

    public String getEmvData() {
        return emvData;
    }

    public void setEmvData(String emvData) {
        this.emvData = emvData;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if this is a cash withdrawal transaction.
     */
    public boolean isCashWithdrawal() {
        return transactionType != null && transactionType.startsWith(HyosungProtocol.OP_CASH_WITHDRAWAL);
    }

    /**
     * Checks if this is a balance inquiry transaction.
     */
    public boolean isBalanceInquiry() {
        return transactionType != null && transactionType.startsWith(HyosungProtocol.OP_BALANCE_INQUIRY);
    }

    /**
     * Checks if this is a transfer transaction.
     */
    public boolean isTransfer() {
        return transactionType != null && transactionType.startsWith(HyosungProtocol.OP_TRANSFER);
    }

    /**
     * Gets the operation type from the transaction type.
     */
    public String getOperation() {
        if (transactionType != null && transactionType.length() >= 2) {
            return transactionType.substring(0, 2);
        }
        return null;
    }

    /**
     * Gets the source account type from the transaction type.
     */
    public String getSourceAccountType() {
        if (transactionType != null && transactionType.length() >= 4) {
            return transactionType.substring(2, 4);
        }
        return null;
    }

    /**
     * Gets the destination account type from the transaction type.
     */
    public String getDestAccountType() {
        if (transactionType != null && transactionType.length() >= 6) {
            return transactionType.substring(4, 6);
        }
        return null;
    }

    /**
     * Extracts PAN (masked) from track 2 data.
     * Returns last 4 digits with masking.
     */
    public String getMaskedPan() {
        if (track2Data == null || track2Data.isEmpty()) {
            return null;
        }

        // Track 2 format: ;PAN=YYMM...?
        String pan = track2Data;
        if (pan.startsWith(";")) {
            pan = pan.substring(1);
        }
        int sepIndex = pan.indexOf('=');
        if (sepIndex > 0) {
            pan = pan.substring(0, sepIndex);
        }

        if (pan.length() >= 4) {
            return "************" + pan.substring(pan.length() - 4);
        }
        return pan;
    }

    /**
     * Gets the total amount (transaction + surcharge) in cents.
     */
    public long getTotalAmountCents() {
        return amountCents + surchargeCents;
    }

    @Override
    public String toString() {
        return "TransactionRequest{" +
               "terminalId='" + terminalId + '\'' +
               ", txnType='" + transactionType + '\'' +
               ", seq=" + sequenceNumber +
               ", amount=" + amountCents +
               ", surcharge=" + surchargeCents +
               ", dispense=" + dispense +
               ", pan=" + getMaskedPan() +
               '}';
    }
}
