package castech.emvtxn.atm.host;

/**
 * Reversal Response (Type 86)
 *
 * Response to a reversal request from the host processor.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (86)
 *   Field 3:  Sequence Number (echo of request)
 *   Field 4:  Response Code (00 = accepted)
 *   Field 5:  (Reserved - empty)
 */
public class ReversalResponse {

    private String infoHeader;      // Field 0
    private String terminalId;      // Field 1
    private int sequenceNumber;     // Field 3
    private String responseCode;    // Field 4

    /**
     * Creates a new empty ReversalResponse.
     */
    public ReversalResponse() {
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
        return HyosungProtocol.MSG_TYPE_REVERSAL;
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

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if the reversal was accepted.
     */
    public boolean isAccepted() {
        return HyosungProtocol.RESP_APPROVED.equals(responseCode);
    }

    /**
     * Gets the human-readable response description.
     */
    public String getResponseDescription() {
        return HyosungProtocol.getResponseDescription(responseCode);
    }

    @Override
    public String toString() {
        return "ReversalResponse{" +
               "terminalId='" + terminalId + '\'' +
               ", seq=" + sequenceNumber +
               ", responseCode='" + responseCode + '\'' +
               ", accepted=" + isAccepted() +
               '}';
    }
}
