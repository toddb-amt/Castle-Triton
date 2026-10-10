package castech.emvtxn.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** SEC-03: `admin_pin` / `super_admin_pin` from CasHUB — six to eight digits, never logged. */
public class AdminPinParamsTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void validPins_areAccepted_trimmed() {
        AdminPinParams p = AdminPinParams.parse(map("admin_pin", " 246810 ", "super_admin_pin", "13579246"));
        assertEquals("246810", p.adminPin);
        assertEquals("13579246", p.superPin);
        assertTrue(p.problems.isEmpty());
        assertTrue(p.isPresent());
    }

    @Test
    public void tooShort_tooLong_orNotDigits_areProblems_andSetNothing() {
        assertNull(AdminPinParams.parse(map("admin_pin", "12345")).adminPin);
        assertNull(AdminPinParams.parse(map("admin_pin", "123456789")).adminPin);
        assertNull(AdminPinParams.parse(map("admin_pin", "12ab56")).adminPin);
        assertNull(AdminPinParams.parse(map("super_admin_pin", "")).superPin);
        assertEquals(1, AdminPinParams.parse(map("admin_pin", "12345")).problems.size());
    }

    @Test
    public void absent_isNotPresent_andLeavesTheStoredHashAlone() {
        AdminPinParams p = AdminPinParams.parse(map("host_address", "1.2.3.4"));
        assertFalse(p.isPresent());
        assertNull(p.adminPin);
        assertNull(p.superPin);
    }

    @Test
    public void describe_neverShowsAPin() {
        AdminPinParams p = AdminPinParams.parse(map("admin_pin", "246810"));
        assertEquals("admin pins: admin=[set] super=[unchanged]", p.describe());
        assertFalse(p.describe().contains("246810"));
    }

    @Test
    public void theProblemText_neverContainsTheValue() {
        AdminPinParams p = AdminPinParams.parse(map("admin_pin", "12345"));
        assertFalse(p.problems.get(0).contains("12345"));
    }
}
