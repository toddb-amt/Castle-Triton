package castech.emvtxn.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * 6.2.10: the cellular APN as CasHUB parameters. {@code apn} is the trigger; the rest are
 * optional with safe defaults. Absent keys mean "not managed"; a bad value is reported and
 * ignored. The password is a credential and never appears in logs.
 */
public class ApnParamsTest {

    private static Map<String, String> m(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    @Test
    public void noApnKeys_isEmpty() {
        ApnParams p = ApnParams.parse(m("host_address", "1.2.3.4"));
        assertTrue(p.isEmpty());
        assertFalse(p.isActionable());
        assertTrue(p.problems.isEmpty());
    }

    @Test
    public void apnAlone_getsDefaults() {
        ApnParams p = ApnParams.parse(m("apn", " BROADBAND "));
        assertTrue(p.isActionable());
        assertEquals("BROADBAND", p.apn);
        assertEquals("BROADBAND", p.name);       // name defaults to the APN
        assertEquals("IP", p.protocol);
        assertEquals(ApnParams.AUTH_NONE, p.authType);
        assertNull(p.user);
        assertNull(p.password);
        assertTrue(p.problems.isEmpty());
    }

    @Test
    public void fullSet_isParsed() {
        ApnParams p = ApnParams.parse(m("apn", "10569.mcs", "apn_name", "TFI private", "apn_user", "tfi",
                "apn_password", "s3cret", "apn_auth_type", "chap", "apn_protocol", "ipv4v6"));
        assertEquals("10569.mcs", p.apn);
        assertEquals("TFI private", p.name);
        assertEquals("tfi", p.user);
        assertEquals("s3cret", p.password);
        assertEquals(ApnParams.AUTH_CHAP, p.authType);
        assertEquals("IPV4V6", p.protocol);
    }

    @Test
    public void authType_acceptsNamesAndNumbers() {
        assertEquals(ApnParams.AUTH_PAP,  ApnParams.parse(m("apn", "x", "apn_auth_type", "pap")).authType);
        assertEquals(ApnParams.AUTH_BOTH, ApnParams.parse(m("apn", "x", "apn_auth_type", "3")).authType);
        assertEquals(ApnParams.AUTH_NONE, ApnParams.parse(m("apn", "x", "apn_auth_type", "none")).authType);
        ApnParams bad = ApnParams.parse(m("apn", "x", "apn_auth_type", "kerberos"));
        assertEquals(ApnParams.AUTH_NONE, bad.authType);           // falls back, still actionable
        assertTrue(bad.isActionable());
        assertEquals(1, bad.problems.size());
    }

    @Test
    public void badProtocol_fallsBackToIpWithAProblem() {
        ApnParams p = ApnParams.parse(m("apn", "x", "apn_protocol", "ipx"));
        assertEquals("IP", p.protocol);
        assertEquals(1, p.problems.size());
    }

    @Test
    public void blankOrIllegalApn_isNotActionable() {
        ApnParams blank = ApnParams.parse(m("apn", "  "));
        assertFalse(blank.isEmpty());
        assertFalse(blank.isActionable());
        assertEquals(1, blank.problems.size());
        ApnParams illegal = ApnParams.parse(m("apn", "bad apn/with spaces"));
        assertFalse(illegal.isActionable());
    }

    @Test
    public void otherKeysWithoutApn_areAProblemNotAnAction() {
        ApnParams p = ApnParams.parse(m("apn_user", "tfi"));
        assertFalse(p.isEmpty());
        assertFalse(p.isActionable());
        assertEquals(1, p.problems.size());
    }

    @Test
    public void needsChange_comparesWhatTheModemUses_notTheDisplayName() {
        ApnParams p = ApnParams.parse(m("apn", "BROADBAND", "apn_name", "ATT"));
        assertFalse(p.needsChange("broadband", "", "IP", ApnParams.AUTH_NONE));   // case-insensitive apn
        assertTrue(p.needsChange("nxtgenphone", "", "IP", ApnParams.AUTH_NONE));
        assertTrue(p.needsChange("BROADBAND", "someone", "IP", ApnParams.AUTH_NONE));
        assertTrue(p.needsChange("BROADBAND", "", "IPV6", ApnParams.AUTH_NONE));
        assertTrue(p.needsChange(null, null, null, 0));
    }

    @Test
    public void describeAndMask_neverExposeThePassword() {
        ApnParams p = ApnParams.parse(m("apn", "x", "apn_user", "tfi", "apn_password", "s3cret"));
        String d = p.describe();
        assertTrue(d, d.contains("apn=x"));
        assertTrue(d, d.contains("user=[set]"));
        assertFalse(d, d.contains("s3cret"));
        assertFalse(d, d.contains("tfi"));
        String json = "{\"apn\":\"x\",\"apn_password\":\"s3cret\",\"apn_user\":\"tfi\"}";
        assertEquals("{\"apn\":\"x\",\"apn_password\":\"[masked]\",\"apn_user\":\"tfi\"}", ApnParams.maskForLog(json));
        assertEquals("apn_password=[masked]", ApnParams.maskForLog("apn_password=s3cret"));
    }
}
