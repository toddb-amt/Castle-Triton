package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Security review 2026-10-10 (parser differential, twice): provider rows used to be logged with
 * their values masked by regexes, then by the merge's JSON parser — and anything the parser
 * rejected still went to the log as raw text. The dump now never logs a value at all: key names,
 * value lengths, a [secret] marker, and for non-JSON content only its length.
 */
public class ParamLogMaskTest {

    @Test
    public void json_isSummarisedAsKeysAndLengths_secretsMarked_noValuesAtAll() {
        String json = "{\"admin_pin\":\"246810\",\"super_admin_pin\":13579246,\"reporting_access_key\":\"rk-not-real\","
                + "\"pos_terminal_access_key\":\"pk-not-real\",\"apn_password\":\"pw-not-real\",\"host_address\":\"18.189.225.36\",\"host_port\":9020}";
        String out = ParamLogMask.summarize(json);
        for (String s : new String[] {"246810", "13579246", "rk-not-real", "pk-not-real", "pw-not-real", "18.189.225.36", "9020"}) {
            assertFalse(out, out.contains(s));
        }
        assertTrue(out, out.contains("admin_pin[secret]"));
        assertTrue(out, out.contains("super_admin_pin[secret]"));
        assertTrue(out, out.contains("host_address(13)"));
        assertTrue(out, out.contains("host_port(4)"));
    }

    @Test
    public void aUnicodeEscapedSecretKey_isStillRecognised_becauseTheSameParserDecodesIt() {
        String out = ParamLogMask.summarize("{\"admin\\u005fpin\":\"246810\"}");
        assertFalse(out, out.contains("246810"));
        assertTrue(out, out.contains("admin_pin[secret]"));
    }

    @Test
    public void contentTheParserRejects_isLoggedAsLengthOnly() {
        String out = ParamLogMask.summarize("[{\"admin\\u005fpin\":\"246810\"}] trailing admin_pin=246810");
        assertFalse(out, out.contains("246810"));
        assertFalse(out, out.contains("admin_pin"));
        assertTrue(out, out.startsWith("<non-JSON"));
    }

    @Test
    public void emptyAndNull_areSafe() {
        assertEquals("[null]", ParamLogMask.summarize(null));
        assertEquals("<empty>", ParamLogMask.summarize("   "));
    }

    @Test
    public void theSecretKeyList_coversEveryCredentialParameter() {
        for (String k : new String[] {"pos_terminal_access_key", "apn_password", "reporting_access_key", "admin_pin", "super_admin_pin"}) {
            assertTrue(k, ParamLogMask.SECRET_KEYS.contains(k));
        }
    }
}
