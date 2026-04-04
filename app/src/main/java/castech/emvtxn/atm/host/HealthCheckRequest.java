package castech.emvtxn.atm.host;

/**
 * Health Check Request (Type 89)
 *
 * Used to verify connectivity to the host processor.
 * Should be sent periodically when enabled (typically every 6 minutes).
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (89)
 */
public class HealthCheckRequest {

    private String infoHeader;      // Field 0: H0.NNNNNN
    private String terminalId;      // Field 1: Terminal ID

    /**
     * Creates a new HealthCheckRequest with default values.
     */
    public HealthCheckRequest() {
        this.infoHeader = HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);
    }

    /**
     * Creates a health check request.
     *
     * @param terminalId Terminal ID
     * @return Configured HealthCheckRequest
     */
    public static HealthCheckRequest create(String terminalId) {
        HealthCheckRequest req = new HealthCheckRequest();
        req.setTerminalId(terminalId);
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
        return HyosungProtocol.MSG_TYPE_HEALTH_CHECK;
    }

    @Override
    public String toString() {
        return "HealthCheckRequest{" +
               "terminalId='" + terminalId + '\'' +
               '}';
    }
}
