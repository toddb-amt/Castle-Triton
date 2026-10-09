package castech.emvtxn.reporting;

/** Pusher status for the Admin line (RPT-02): the four states and the one line that shows them. */
public final class ReportingStatus {
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String OK = "OK";
    public static final String RETRYING = "RETRYING";
    public static final String KEY_REJECTED = "KEY_REJECTED";
    private ReportingStatus() {}

    public static String render(String state, int pending, int parked, long lastSentAt, String lastError, long now) {
        String s;
        if (NOT_CONFIGURED.equals(state)) {
            s = "Reporting: not configured";
        } else if (KEY_REJECTED.equals(state)) {
            s = "Reporting: key rejected · " + pending + " pending";
        } else if (RETRYING.equals(state)) {
            s = "Reporting: " + pending + " pending · retrying (" + (lastError == null || lastError.isEmpty() ? "error" : lastError) + ")";
        } else {
            s = "Reporting: configured · " + pending + " pending · " + (lastSentAt <= 0 ? "nothing sent yet" : "last sent " + ago(now - lastSentAt));
        }
        if (parked > 0) s += " · " + parked + " parked";
        return s;
    }

    private static String ago(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        return h < 24 ? h + " h ago" : (h / 24) + " d ago";
    }
}
