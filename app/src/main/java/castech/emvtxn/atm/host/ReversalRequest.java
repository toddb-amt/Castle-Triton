package castech.emvtxn.atm.host;

/**
 * Reversal Request (Type 86)
 *
 * Used to reverse a previously approved transaction when:
 * - Customer cancels after approval
 * - Dispense failure occurs
 * - Partial dispense occurs
 * - Card is retained
 * - Host error during completion
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (86)
 *   Field 3:  Original Authorization Data (from original response Field 5)
 *   Field 4:  Original Sequence Number
 *   Field 5:  Track 2 Data
 *   Field 6:  (Reserved - empty)
 *   Field 7:  PIN Block (16 hex chars)
 *   Field 8:  Original Amount (cents)
 *   Field 9:  Original Surcharge (cents)
 *   Field 10: Reversal Reason Code (optional)
 */
public class ReversalRequest {

    // ---- EFX/Pulse 10-field TC86 layout (what SWC + production processors accept) ----
    // Field positions per `docs/CASTLE_POS_INTEGRATION_SPEC.md` and the mux dev's audit.
    // The previous 11-field Hyosung STD1 spec layout was structurally misaligned with
    // what SWC parses (track 2 / PIN block / reversal reason are NOT carried in TC86;
    // retrieval ref / dispensed amount / misc flag / status monitoring / EMV TLV ARE).
    private String infoHeader;              // F0: H0.NNNNNN
    private String terminalId;              // F1: Terminal ID
    private String retrievalReference;      // F3: 26-char MMDDYYYY+HHMMSS+12-digit sequence
    private long originalAmountCents;       // F4: Original amount
    private long dispensedAmountCents;      // F5: Dispensed amount (0 for full reversal)
    private long originalSurchargeCents;    // F6: Original surcharge
    private String miscFlag;                // F7: Misc flag (typically "1")
    private String statusMonitoring;        // F8: Status monitoring block (same as original 85 F12)
    private String emvData;                 // F9: EMV TLV with "ud" prefix (same as original 85 F13)

    // ---- Legacy fields kept for backwards compatibility with older persisted records ----
    // Not emitted on the wire by the EFX/Pulse layout but still set by old callers.
    // Safe to ignore; future cleanup can remove once no records reference them.
    @Deprecated private String originalAuthData;
    @Deprecated private int originalSequenceNumber;
    @Deprecated private String track2Data;
    @Deprecated private String pinBlock;
    @Deprecated private String reversalReason;

    /**
     * Creates a new ReversalRequest with default values.
     */
    public ReversalRequest() {
        this.infoHeader = HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);
        this.miscFlag = "1";       // EFX/Pulse default
        this.dispensedAmountCents = 0;  // 0 = full reversal
        this.reversalReason = HyosungProtocol.REV_REASON_TIMEOUT;  // legacy, unused on wire
    }

    // ---- EFX/Pulse field accessors -------------------------------------------

    public String getRetrievalReference() { return retrievalReference; }
    public void setRetrievalReference(String retrievalReference) { this.retrievalReference = retrievalReference; }

    public long getDispensedAmountCents() { return dispensedAmountCents; }
    public void setDispensedAmountCents(long dispensedAmountCents) { this.dispensedAmountCents = dispensedAmountCents; }

    public String getMiscFlag() { return miscFlag; }
    public void setMiscFlag(String miscFlag) { this.miscFlag = miscFlag; }

    public String getStatusMonitoring() { return statusMonitoring; }
    public void setStatusMonitoring(String statusMonitoring) { this.statusMonitoring = statusMonitoring; }

    public String getEmvData() { return emvData; }
    public void setEmvData(String emvData) { this.emvData = emvData; }

    /**
     * Builds the 26-char retrieval reference for the F3 field.
     * Format: MMDDYYYY + HHMMSS + 12-digit zero-padded sequence number.
     * Example: "05252026105659000000001838" — May 25 2026 10:56:59, seq 1838.
     */
    public static String buildRetrievalReference(java.util.Date when, int sequenceNumber) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMddyyyyHHmmss", java.util.Locale.US);
        return sdf.format(when) + String.format(java.util.Locale.US, "%012d", sequenceNumber);
    }

    /**
     * Creates a reversal request from an original transaction and response.
     *
     * @param originalRequest The original transaction request
     * @param originalResponse The original transaction response
     * @param reason The reversal reason code
     * @return Configured ReversalRequest
     */
    public static ReversalRequest createFromTransaction(TransactionRequest originalRequest,
            TransactionResponse originalResponse, String reason) {
        ReversalRequest rev = new ReversalRequest();
        rev.setInfoHeader(originalRequest.getInfoHeader());
        rev.setTerminalId(originalRequest.getTerminalId());
        rev.setOriginalAuthData(originalResponse.getAuthorizationData());
        rev.setOriginalSequenceNumber(originalRequest.getSequenceNumber());
        rev.setTrack2Data(originalRequest.getTrack2Data());
        rev.setPinBlock(originalRequest.getPinBlock());
        rev.setOriginalAmountCents(originalRequest.getAmountCents());
        rev.setOriginalSurchargeCents(originalRequest.getSurchargeCents());
        rev.setReversalReason(reason);
        return rev;
    }

    /**
     * Creates a timeout reversal (no response received).
     */
    public static ReversalRequest createTimeoutReversal(TransactionRequest originalRequest) {
        ReversalRequest rev = new ReversalRequest();
        rev.setInfoHeader(originalRequest.getInfoHeader());
        rev.setTerminalId(originalRequest.getTerminalId());
        rev.setOriginalAuthData(""); // No auth data available
        rev.setOriginalSequenceNumber(originalRequest.getSequenceNumber());
        rev.setTrack2Data(originalRequest.getTrack2Data());
        rev.setPinBlock(originalRequest.getPinBlock());
        rev.setOriginalAmountCents(originalRequest.getAmountCents());
        rev.setOriginalSurchargeCents(originalRequest.getSurchargeCents());
        rev.setReversalReason(HyosungProtocol.REV_REASON_TIMEOUT);
        return rev;
    }

    /**
     * Creates a customer cancel reversal.
     */
    public static ReversalRequest createCustomerCancelReversal(TransactionRequest originalRequest,
            TransactionResponse originalResponse) {
        return createFromTransaction(originalRequest, originalResponse,
                HyosungProtocol.REV_REASON_CUSTOMER_CANCEL);
    }

    /**
     * Creates a dispense failure reversal.
     */
    public static ReversalRequest createDispenseFailureReversal(TransactionRequest originalRequest,
            TransactionResponse originalResponse) {
        return createFromTransaction(originalRequest, originalResponse,
                HyosungProtocol.REV_REASON_DISPENSE_FAIL);
    }

    /**
     * Creates a partial dispense reversal.
     */
    public static ReversalRequest createPartialDispenseReversal(TransactionRequest originalRequest,
            TransactionResponse originalResponse) {
        return createFromTransaction(originalRequest, originalResponse,
                HyosungProtocol.REV_REASON_PARTIAL_DISPENSE);
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
        return HyosungProtocol.MSG_TYPE_REVERSAL;
    }

    public String getOriginalAuthData() {
        return originalAuthData;
    }

    public void setOriginalAuthData(String originalAuthData) {
        this.originalAuthData = originalAuthData;
    }

    public int getOriginalSequenceNumber() {
        return originalSequenceNumber;
    }

    public void setOriginalSequenceNumber(int originalSequenceNumber) {
        this.originalSequenceNumber = originalSequenceNumber;
    }

    /**
     * Gets original sequence number as 4-digit string (zero-padded).
     */
    public String getOriginalSequenceNumberString() {
        return String.format("%04d", originalSequenceNumber);
    }

    public String getTrack2Data() {
        return track2Data;
    }

    public void setTrack2Data(String track2Data) {
        this.track2Data = track2Data;
    }

    public String getPinBlock() {
        return pinBlock;
    }

    public void setPinBlock(String pinBlock) {
        this.pinBlock = pinBlock;
    }

    public long getOriginalAmountCents() {
        return originalAmountCents;
    }

    public void setOriginalAmountCents(long originalAmountCents) {
        this.originalAmountCents = originalAmountCents;
    }

    public long getOriginalSurchargeCents() {
        return originalSurchargeCents;
    }

    public void setOriginalSurchargeCents(long originalSurchargeCents) {
        this.originalSurchargeCents = originalSurchargeCents;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(String reversalReason) {
        this.reversalReason = reversalReason;
    }

    /**
     * Gets human-readable description of reversal reason.
     */
    public String getReversalReasonDescription() {
        if (reversalReason == null || reversalReason.isEmpty()) {
            return "Timeout/No Response";
        }
        switch (reversalReason) {
            case "01": return "Customer Cancelled";
            case "02": return "Dispense Failure";
            case "03": return "Partial Dispense";
            case "04": return "Card Retained";
            case "05": return "Host Error";
            default: return "Unknown (" + reversalReason + ")";
        }
    }

    @Override
    public String toString() {
        return "ReversalRequest{" +
               "terminalId='" + terminalId + '\'' +
               ", originalSeq=" + originalSequenceNumber +
               ", amount=" + originalAmountCents +
               ", surcharge=" + originalSurchargeCents +
               ", reason='" + reversalReason + '\'' +
               '}';
    }
}
