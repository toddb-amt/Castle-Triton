package castech.emvtxn.reporting;

/** Pusher status for the Admin line (RPT-02). Rendering is added with the pusher. */
public final class ReportingStatus {
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String OK = "OK";
    public static final String RETRYING = "RETRYING";
    public static final String KEY_REJECTED = "KEY_REJECTED";
    private ReportingStatus() {}
}
