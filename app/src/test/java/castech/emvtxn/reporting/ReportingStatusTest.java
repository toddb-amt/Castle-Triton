package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** RPT-02: the one Admin line. */
public class ReportingStatusTest {
    private static final long NOW = 1_760_000_000_000L;

    @Test public void notConfigured() {
        assertEquals("Reporting: not configured", ReportingStatus.render(ReportingStatus.NOT_CONFIGURED, 0, 0, 0, "", NOW));
    }
    @Test public void okWithNothingPending_showsLastSent() {
        String s = ReportingStatus.render(ReportingStatus.OK, 0, 0, NOW - 5 * 60_000, "", NOW);
        assertEquals("Reporting: configured · 0 pending · last sent 5 min ago", s);
    }
    @Test public void okNeverSent() {
        assertEquals("Reporting: configured · 0 pending · nothing sent yet", ReportingStatus.render(ReportingStatus.OK, 0, 0, 0, "", NOW));
    }
    @Test public void retrying_showsCountAndReason() {
        assertEquals("Reporting: 3 pending · retrying (timeout)", ReportingStatus.render(ReportingStatus.RETRYING, 3, 0, 0, "timeout", NOW));
    }
    @Test public void keyRejected() {
        assertEquals("Reporting: key rejected · 2 pending", ReportingStatus.render(ReportingStatus.KEY_REJECTED, 2, 0, 0, "Unauthorized", NOW));
    }
    @Test public void parkedCount_isAppended() {
        assertEquals("Reporting: configured · 0 pending · last sent 1 min ago · 1 parked",
                ReportingStatus.render(ReportingStatus.OK, 0, 1, NOW - 60_000, "", NOW));
    }
}
