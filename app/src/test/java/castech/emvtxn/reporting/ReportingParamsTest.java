package castech.emvtxn.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** RPT-02 (6.2.13): the two CasHUB keys that configure the MyView push. */
public class ReportingParamsTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void absentKeys_isEmpty_andManagesNothing() {
        ReportingParams p = ReportingParams.parse(map("terminal_id", "MS00TEST"));
        assertTrue(p.isEmpty());
        assertNull(p.accessKey);
        assertNull(p.url);
        assertTrue(p.problems.isEmpty());
    }

    @Test
    public void accessKey_isTrimmedAndKept() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "  test-key-not-real \n"));
        assertFalse(p.isEmpty());
        assertEquals("test-key-not-real", p.accessKey);
    }

    @Test
    public void blankAccessKey_isAProblem_andLeavesTheCurrentKeyAlone() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "   "));
        assertNull(p.accessKey);
        assertEquals(1, p.problems.size());
        assertTrue(p.problems.get(0).startsWith("reporting_access_key:"));
    }

    @Test
    public void url_mustBeHttps() {
        assertEquals("https://portal.example/transactions/addTransaction",
                ReportingParams.parse(map("reporting_url", " https://portal.example/transactions/addTransaction/ ")).url);
        ReportingParams bad = ReportingParams.parse(map("reporting_url", "http://portal.example/x"));
        assertNull(bad.url);
        assertEquals(1, bad.problems.size());
        assertTrue(bad.problems.get(0).startsWith("reporting_url:"));
    }

    @Test
    public void describe_neverContainsTheKey() {
        ReportingParams p = ReportingParams.parse(map("reporting_access_key", "test-key-not-real",
                "reporting_url", "https://portal.example/t"));
        String d = p.describe();
        assertFalse(d.contains("test-key-not-real"));
        assertTrue(d.contains("key=[set]"));
        assertTrue(d.contains("https://portal.example/t"));
    }

    @Test
    public void maskForLog_hidesTheKeyInJsonAndKeyValueForms() {
        String json = "{\"reporting_access_key\":\"test-key-not-real\",\"terminal_id\":\"MS00TEST\"}";
        String masked = ReportingParams.maskForLog(json);
        assertFalse(masked.contains("test-key-not-real"));
        assertTrue(masked.contains("MS00TEST"));
        assertEquals("reporting_access_key=[masked]", ReportingParams.maskForLog("reporting_access_key=test-key-not-real"));
    }

    @Test
    public void keysSet_isExactlyTheTwoKeys() {
        assertEquals(2, ReportingParams.KEYS.size());
        assertTrue(ReportingParams.KEYS.contains("reporting_access_key"));
        assertTrue(ReportingParams.KEYS.contains("reporting_url"));
    }
}
