package castech.emvtxn.atm.host;

/**
 * Configuration Request (Type 88)
 *
 * Used to request configuration or key download from the host processor.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (88)
 *   Field 3:  Config Type (1-5)
 */
public class ConfigRequest {

    private String infoHeader;      // Field 0: H0.NNNNNN
    private String terminalId;      // Field 1: Terminal ID
    private String configType;      // Field 3: Configuration type

    /**
     * Creates a new ConfigRequest with default values.
     */
    public ConfigRequest() {
        this.infoHeader = HyosungProtocol.buildInfoHeader(HyosungProtocol.DEFAULT_ROUTING_ID);
        this.configType = HyosungProtocol.CONFIG_FULL; // Default: full config
    }

    /**
     * Creates a full configuration download request.
     *
     * @param terminalId Terminal ID
     * @return Configured ConfigRequest
     */
    public static ConfigRequest createFullConfig(String terminalId) {
        ConfigRequest req = new ConfigRequest();
        req.setTerminalId(terminalId);
        req.setConfigType(HyosungProtocol.CONFIG_FULL);
        return req;
    }

    /**
     * Creates a key download only request.
     *
     * @param terminalId Terminal ID
     * @return Configured ConfigRequest
     */
    public static ConfigRequest createKeyDownload(String terminalId) {
        ConfigRequest req = new ConfigRequest();
        req.setTerminalId(terminalId);
        req.setConfigType(HyosungProtocol.CONFIG_KEY_ONLY);
        return req;
    }

    /**
     * Creates a surcharge update request.
     *
     * @param terminalId Terminal ID
     * @return Configured ConfigRequest
     */
    public static ConfigRequest createSurchargeUpdate(String terminalId) {
        ConfigRequest req = new ConfigRequest();
        req.setTerminalId(terminalId);
        req.setConfigType(HyosungProtocol.CONFIG_SURCHARGE);
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
        return HyosungProtocol.MSG_TYPE_CONFIG;
    }

    public String getConfigType() {
        return configType;
    }

    public void setConfigType(String configType) {
        this.configType = configType;
    }

    /**
     * Gets human-readable description of config type.
     */
    public String getConfigTypeDescription() {
        if (configType == null) {
            return "Unknown";
        }
        switch (configType) {
            case "1": return "Full Configuration Download";
            case "2": return "Partial Configuration Update";
            case "3": return "Key Download Only";
            case "4": return "Surcharge Update";
            case "5": return "Extended Configuration";
            default: return "Unknown (" + configType + ")";
        }
    }

    @Override
    public String toString() {
        return "ConfigRequest{" +
               "terminalId='" + terminalId + '\'' +
               ", configType='" + configType + '\'' +
               " (" + getConfigTypeDescription() + ")" +
               '}';
    }
}
