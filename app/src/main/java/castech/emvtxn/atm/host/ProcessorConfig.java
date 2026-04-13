package castech.emvtxn.atm.host;

/**
 * Processor Configuration
 *
 * Holds configuration settings for a specific ATM processor.
 * Includes host address, port, framing type, timeouts, and other processor-specific settings.
 */
public class ProcessorConfig {

    // Connection settings
    private String name;
    private String host;
    private int port;
    private boolean useTls;
    private String tlsVersion;

    // Protocol type
    public enum ProtocolType {
        TRITON_STANDARD,
        HYOSUNG_STD1
    }

    // Protocol settings
    private ProtocolType protocolType;
    private HyosungProtocol.FramingType framingType;
    private String routingId;
    private String communicationHeader;  // Processor-specific header (if required)

    // Terminal settings
    private String terminalId;

    // Timeout settings (milliseconds)
    private int connectionTimeout;
    private int responseTimeout;
    private int ackTimeout;
    private int eotTimeout;

    // Health check settings
    private boolean healthCheckEnabled;
    private int healthCheckIntervalMs;

    // Reversal settings
    private boolean reversalOnHostError;
    private int maxReversalRetries;

    // Retry settings
    private int maxRetries;
    private int retryDelayMs;

    // Development mode — disables TLS certificate validation
    private boolean devMode = false;

    /**
     * Creates a new ProcessorConfig with default values.
     */
    public ProcessorConfig() {
        this.useTls = true;
        this.tlsVersion = "TLSv1.2";
        this.protocolType = ProtocolType.TRITON_STANDARD;  // Default to Triton
        this.framingType = HyosungProtocol.FramingType.STANDARD;
        this.routingId = HyosungProtocol.DEFAULT_ROUTING_ID;
        this.connectionTimeout = 30000;     // 30 seconds
        this.responseTimeout = 60000;       // 60 seconds
        this.ackTimeout = 10000;            // 10 seconds
        this.eotTimeout = 5000;             // 5 seconds
        this.healthCheckEnabled = false;
        this.healthCheckIntervalMs = 360000; // 6 minutes
        this.reversalOnHostError = true;
        this.maxReversalRetries = 3;
        this.maxRetries = 3;
        this.retryDelayMs = 1000;
    }

    // =========================================================================
    // Pre-configured Processor Profiles
    // =========================================================================

    /**
     * Creates configuration for DNS processor.
     */
    public static ProcessorConfig forDns(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("DNS");
        config.setHost(host);
        config.setPort(8002);
        config.setFramingType(HyosungProtocol.FramingType.STANDARD);
        config.setRoutingId("000000");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    /**
     * Creates configuration for FIS processor.
     */
    public static ProcessorConfig forFis(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("FIS");
        config.setHost(host);
        config.setPort(443);
        config.setFramingType(HyosungProtocol.FramingType.STANDARD);
        config.setRoutingId("000000");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(true);
        config.setHealthCheckIntervalMs(360000); // 6 minutes
        return config;
    }

    /**
     * Creates configuration for Switch Commerce processor.
     * Uses VISA framing WITHOUT STX/ETX/LRC.
     */
    public static ProcessorConfig forSwitchCommerce(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("Switch Commerce");
        config.setHost(host);
        config.setPort(1440);
        config.setFramingType(HyosungProtocol.FramingType.VISA_NO_STX_ETX);
        config.setRoutingId("SC101");
        config.setCommunicationHeader("123SC101");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    /**
     * Creates configuration for EFX processor.
     * Uses VISA framing WITHOUT STX/ETX/LRC.
     */
    public static ProcessorConfig forEfx(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("EFX");
        config.setHost(host);
        config.setPort(9057);
        config.setFramingType(HyosungProtocol.FramingType.VISA_NO_STX_ETX);
        config.setRoutingId("000000");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    /**
     * Creates configuration for Cardtronics processor.
     */
    public static ProcessorConfig forCardtronics(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("Cardtronics");
        config.setHost(host);
        config.setPort(5550);
        config.setFramingType(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
        config.setRoutingId("CTSTRA");
        config.setCommunicationHeader("CTSTRI");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    /**
     * Creates configuration for CDS processor.
     */
    public static ProcessorConfig forCds(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("CDS");
        config.setHost(host);
        config.setPort(6965);
        config.setFramingType(HyosungProtocol.FramingType.STANDARD);
        config.setRoutingId("CDHY");
        config.setCommunicationHeader("CDSAA0");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(true);
        config.setHealthCheckIntervalMs(360000);
        return config;
    }

    /**
     * Creates configuration for Worldpay processor.
     */
    public static ProcessorConfig forWorldpay(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("Worldpay");
        config.setHost(host);
        config.setPort(6661);
        config.setFramingType(HyosungProtocol.FramingType.STANDARD);
        config.setRoutingId("LNKATM");
        config.setCommunicationHeader("LNKATM");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    /**
     * Creates configuration for Elan/Genpass processor.
     */
    public static ProcessorConfig forElan(String host, String terminalId) {
        ProcessorConfig config = new ProcessorConfig();
        config.setName("Elan/Genpass");
        config.setHost(host);
        config.setPort(5166);
        config.setFramingType(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
        config.setRoutingId("000000");
        config.setTerminalId(terminalId);
        config.setHealthCheckEnabled(false);
        return config;
    }

    // =========================================================================
    // Builder Methods
    // =========================================================================

    /**
     * Creates a protocol implementation for this processor configuration.
     * Returns either Triton or Hyosung based on protocolType.
     */
    public AtmProtocol createProtocol() {
        if (protocolType == ProtocolType.TRITON_STANDARD) {
            // Triton: disable optional header group — send minimal messages
            // (Terminal ID + Transaction Code only)
            return new castech.emvtxn.atm.host.triton.TritonProtocolImpl();
        } else {
            // Legacy Hyosung protocol — return wrapper (to be implemented)
            return null; // TODO: HyosungProtocolImpl
        }
    }

    /**
     * Creates a message builder configured for this processor (legacy Hyosung).
     */
    public HyosungMessageBuilder createMessageBuilder() {
        return new HyosungMessageBuilder(framingType);
    }

    /**
     * Creates a message parser configured for this processor (legacy Hyosung).
     */
    public HyosungMessageParser createMessageParser() {
        return new HyosungMessageParser(framingType);
    }

    /**
     * Gets the information header for this processor.
     */
    public String getInfoHeader() {
        return HyosungProtocol.buildInfoHeader(routingId);
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public String getTlsVersion() {
        return tlsVersion;
    }

    public void setTlsVersion(String tlsVersion) {
        this.tlsVersion = tlsVersion;
    }

    public HyosungProtocol.FramingType getFramingType() {
        return framingType;
    }

    public void setFramingType(HyosungProtocol.FramingType framingType) {
        this.framingType = framingType;
    }

    public String getRoutingId() {
        return routingId;
    }

    public void setRoutingId(String routingId) {
        this.routingId = routingId;
    }

    public String getCommunicationHeader() {
        return communicationHeader;
    }

    public void setCommunicationHeader(String communicationHeader) {
        this.communicationHeader = communicationHeader;
    }

    public boolean hasCommunicationHeader() {
        return communicationHeader != null && !communicationHeader.isEmpty();
    }

    public String getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(int responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public int getAckTimeout() {
        return ackTimeout;
    }

    public void setAckTimeout(int ackTimeout) {
        this.ackTimeout = ackTimeout;
    }

    public int getEotTimeout() {
        return eotTimeout;
    }

    public void setEotTimeout(int eotTimeout) {
        this.eotTimeout = eotTimeout;
    }

    public boolean isHealthCheckEnabled() {
        return healthCheckEnabled;
    }

    public void setHealthCheckEnabled(boolean healthCheckEnabled) {
        this.healthCheckEnabled = healthCheckEnabled;
    }

    public int getHealthCheckIntervalMs() {
        return healthCheckIntervalMs;
    }

    public void setHealthCheckIntervalMs(int healthCheckIntervalMs) {
        this.healthCheckIntervalMs = healthCheckIntervalMs;
    }

    public boolean isReversalOnHostError() {
        return reversalOnHostError;
    }

    public void setReversalOnHostError(boolean reversalOnHostError) {
        this.reversalOnHostError = reversalOnHostError;
    }

    public int getMaxReversalRetries() {
        return maxReversalRetries;
    }

    public void setMaxReversalRetries(int maxReversalRetries) {
        this.maxReversalRetries = maxReversalRetries;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public ProtocolType getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(ProtocolType protocolType) {
        this.protocolType = protocolType;
    }

    public boolean isTritonProtocol() {
        return protocolType == ProtocolType.TRITON_STANDARD;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }

    @Override
    public String toString() {
        return "ProcessorConfig{" +
               "name='" + name + '\'' +
               ", host='" + host + '\'' +
               ", port=" + port +
               ", tls=" + useTls +
               ", framing=" + framingType +
               ", terminalId='" + terminalId + '\'' +
               ", healthCheck=" + healthCheckEnabled +
               '}';
    }
}
