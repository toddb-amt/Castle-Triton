package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Security review 2026-10-10 (parser differential): provider rows were masked by regexes while
 * the merge used org.json — anywhere the two disagreed, a secret reached the log. Masking now
 * goes through the SAME parser as the merge; the regex path is only for text org.json rejects.
 */
public class ParamLogMaskTest {

    @Test
    public void jsonSecrets_areMasked_forEveryKnownSecretKey() throws Exception {
        String json = "{\"admin_pin\":\"246810\",\"super_admin_pin\":13579246,\"reporting_access_key\":\"rk-not-real\","
                + "\"pos_terminal_access_key\":\"pk-not-real\",\"apn_password\":\"pw-not-real\",\"host_port\":9020}";
        String masked = ParamLogMask.mask(json);
        for (String s : new String[] {"246810", "13579246", "rk-not-real", "pk-not-real", "pw-not-real"}) assertFalse(masked, masked.contains(s));
        assertTrue(masked, masked.contains("9020"));
        assertEquals("[masked]", new JSONObject(masked).getString("admin_pin"));   // still valid JSON
    }

    @Test
    public void aUnicodeEscapedKey_isMaskedBecauseTheMergeWouldAcceptIt() {
        // org.json decodes "admin_pin" to admin_pin and the merge applies it; the regex never saw it
        String masked = ParamLogMask.mask("{\"admin\\u005fpin\":\"246810\"}");
        assertFalse(masked, masked.contains("246810"));
    }

    @Test
    public void anEscapedQuoteInsideTheValue_doesNotLeakTheRest() {
        String masked = ParamLogMask.mask("{\"admin_pin\":\"24\\\"6810\",\"x\":1}");
        assertFalse(masked, masked.contains("6810"));
    }

    @Test
    public void nonJson_fallsBackToLineMasking() {
        String masked = ParamLogMask.mask("admin_pin=246810\nreporting_access_key = rk-not-real\nhost_port=9020\n");
        assertFalse(masked, masked.contains("246810"));
        assertFalse(masked, masked.contains("rk-not-real"));
        assertTrue(masked, masked.contains("9020"));
    }

    @Test
    public void nullAndPlainText_areSafe() {
        assertEquals("[null]", ParamLogMask.mask(null));
        assertEquals("hello", ParamLogMask.mask("hello"));
    }
}
