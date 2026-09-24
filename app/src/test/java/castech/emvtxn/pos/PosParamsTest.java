package castech.emvtxn.pos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * CasHUB can push the three POS-mode settings centrally (pos_enabled,
 * pos_proxy_url, pos_terminal_access_key). PosParams is the pure parser between
 * the merged CasHUB key/value map and PosConfig: it decides what is present,
 * what is valid, and what changed — with no Android dependency, so the rules
 * are pinned here.
 */
public class PosParamsTest {

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---- presence ----------------------------------------------------------------

    @Test
    public void parse_withNoPosKeys_isEmptyAndTouchesNothing() {
        PosParams p = PosParams.parse(params("host_address", "18.189.225.36", "terminal_id", "MS008626"));
        assertTrue(p.isEmpty());
        assertNull(p.enabled);
        assertNull(p.proxyUrl);
        assertNull(p.accessKey);
        assertTrue(p.problems.isEmpty());
    }

    // ---- pos_enabled ---------------------------------------------------------------

    @Test
    public void parse_enabled_acceptsBooleanWordsAndDigits() {
        assertEquals(Boolean.TRUE,  PosParams.parse(params("pos_enabled", "true")).enabled);
        assertEquals(Boolean.FALSE, PosParams.parse(params("pos_enabled", "FALSE")).enabled);
        assertEquals(Boolean.TRUE,  PosParams.parse(params("pos_enabled", "1")).enabled);
        assertEquals(Boolean.FALSE, PosParams.parse(params("pos_enabled", " 0 ")).enabled);
    }

    @Test
    public void parse_enabled_rejectsGarbageWithAProblem() {
        PosParams p = PosParams.parse(params("pos_enabled", "yes please"));
        assertNull(p.enabled);
        assertFalse(p.isEmpty());                 // the key WAS present — it just isn't usable
        assertEquals(1, p.problems.size());
        assertTrue(p.problems.get(0), p.problems.get(0).contains("pos_enabled"));
    }

    // ---- pos_proxy_url -------------------------------------------------------------

    @Test
    public void parse_proxyUrl_acceptsWebSocketSchemesTrimsAndStripsTrailingSlash() {
        assertEquals("wss://proxy.myviewonline.com:60000",
                PosParams.parse(params("pos_proxy_url", "  wss://proxy.myviewonline.com:60000/  ")).proxyUrl);
        assertEquals("ws://10.0.0.5:60000",
                PosParams.parse(params("pos_proxy_url", "ws://10.0.0.5:60000")).proxyUrl);
    }

    @Test
    public void parse_proxyUrl_rejectsNonWebSocketOrBlank() {
        PosParams https = PosParams.parse(params("pos_proxy_url", "https://proxy.myviewonline.com:60000"));
        assertNull(https.proxyUrl);
        assertEquals(1, https.problems.size());
        assertTrue(https.problems.get(0), https.problems.get(0).contains("pos_proxy_url"));

        PosParams blank = PosParams.parse(params("pos_proxy_url", "   "));
        assertNull(blank.proxyUrl);
        assertEquals(1, blank.problems.size());
    }

    // ---- pos_terminal_access_key ---------------------------------------------------

    @Test
    public void parse_accessKey_isTrimmedAndBlankIsRejected() {
        assertEquals("270f502dbeed6aeb5b0a428feac6e13a",
                PosParams.parse(params("pos_terminal_access_key", " 270f502dbeed6aeb5b0a428feac6e13a ")).accessKey);
        PosParams blank = PosParams.parse(params("pos_terminal_access_key", ""));
        assertNull(blank.accessKey);
        assertEquals(1, blank.problems.size());
    }

    // ---- what changed --------------------------------------------------------------

    @Test
    public void diff_flagsEnabledAndCredentialChangesSeparately() {
        PosParams p = PosParams.parse(params(
                "pos_enabled", "true",
                "pos_proxy_url", "wss://proxy.myviewonline.com:60000",
                "pos_terminal_access_key", "newkey"));

        PosParams.Diff d = p.diffAgainst(false, "wss://proxy.myviewonline.com:60000", "oldkey");
        assertTrue(d.enabledChanged);
        assertTrue(d.credentialsChanged);
        assertTrue(d.any());

        PosParams.Diff urlOnly = p.diffAgainst(true, "wss://old-proxy:60000", "newkey");
        assertFalse(urlOnly.enabledChanged);
        assertTrue(urlOnly.credentialsChanged);
    }

    @Test
    public void diff_isEmptyWhenNothingDiffersAndAbsentKeysAreIgnored() {
        PosParams p = PosParams.parse(params("pos_enabled", "true"));   // url/key absent
        PosParams.Diff d = p.diffAgainst(true, "wss://whatever:60000", "anykey");
        assertFalse(d.enabledChanged);
        assertFalse(d.credentialsChanged);
        assertFalse(d.any());
    }

    @Test
    public void diff_invalidValueNeverCountsAsAChange() {
        PosParams p = PosParams.parse(params("pos_proxy_url", "https://not-a-websocket"));
        assertFalse(p.diffAgainst(true, "wss://proxy.myviewonline.com:60000", "k").any());
    }

    // ---- log hygiene ---------------------------------------------------------------

    @Test
    public void maskForLog_hidesTheAccessKeyInJsonAndKeyValueForms() {
        String json = "{\"pos_enabled\":true,\"pos_terminal_access_key\":\"270f502dbeed6aeb5b0a428feac6e13a\",\"pos_proxy_url\":\"wss://p:60000\"}";
        String masked = PosParams.maskForLog(json);
        assertFalse(masked, masked.contains("270f502d"));
        assertTrue(masked, masked.contains("pos_proxy_url"));

        String kv = "pos_terminal_access_key=270f502dbeed6aeb5b0a428feac6e13a\nhost_port=9020";
        String maskedKv = PosParams.maskForLog(kv);
        assertFalse(maskedKv, maskedKv.contains("270f502d"));
        assertTrue(maskedKv, maskedKv.contains("host_port=9020"));

        assertEquals("[null]", PosParams.maskForLog(null));
    }
}
