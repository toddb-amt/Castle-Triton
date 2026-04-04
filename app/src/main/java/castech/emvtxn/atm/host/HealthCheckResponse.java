package castech.emvtxn.atm.host;

/**
 * Health Check Response (Type 89)
 *
 * Response to a health check request from the host processor.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (89)
 *   Field 3:  Status (00 = OK)
 */
public class HealthCheckResponse {

    private String infoHeader;      // Field 0
    private String terminalId;      // Field 1
    private String status;          // Field 3

    /**
     * Creates a new empty HealthCheckResponse.
     */
    public HealthCheckResponse() {
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
        return HyosungProtocol.MSG_TYPE_HEALTH_CHECK;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if the health check was successful.
     */
    public boolean isOk() {
        return "00".equals(status);
    }

    @Override
    public String toString() {
        return "HealthCheckResponse{" +
               "terminalId='" + terminalId + '\'' +
               ", status='" + status + '\'' +
               ", ok=" + isOk() +
               '}';
    }
}
