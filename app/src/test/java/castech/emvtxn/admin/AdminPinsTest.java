package castech.emvtxn.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SEC-03 (6.2.15): Admin PINs are no longer constants in the APK. The terminal keeps a salted,
 * slow hash (PBKDF2) and compares hash to hash; the same PIN hashes differently on every terminal.
 */
public class AdminPinsTest {

    private static final String SALT_A = "00112233445566778899aabbccddeeff";
    private static final String SALT_B = "ffeeddccbbaa99887766554433221100";

    @Test
    public void hash_isDeterministicForOneSalt_andDiffersAcrossSalts() {
        String h1 = AdminPins.hash("246810", SALT_A);
        String h2 = AdminPins.hash("246810", SALT_A);
        String h3 = AdminPins.hash("246810", SALT_B);
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertEquals(64, h1.length());                       // 32 bytes, hex
        assertFalse(h1.contains("246810"));
    }

    @Test
    public void matches_onlyTheRightPin_underTheRightSalt() {
        String stored = AdminPins.hash("246810", SALT_A);
        assertTrue(AdminPins.matches("246810", stored, SALT_A));
        assertFalse(AdminPins.matches("246811", stored, SALT_A));
        assertFalse(AdminPins.matches("246810", stored, SALT_B));
        assertFalse(AdminPins.matches("", stored, SALT_A));
        assertFalse(AdminPins.matches(null, stored, SALT_A));
        assertFalse(AdminPins.matches("246810", null, SALT_A));   // nothing configured → never matches
    }

    @Test
    public void tier_superBeforeNormal_noneOtherwise() {
        String admin = AdminPins.hash("246810", SALT_A);
        String sup = AdminPins.hash("13579246", SALT_A);
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("13579246", admin, sup, SALT_A));
        assertEquals(AdminPins.TIER_NORMAL, AdminPins.tier("246810", admin, sup, SALT_A));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("000000", admin, sup, SALT_A));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("246810", null, null, SALT_A));   // unconfigured terminal
    }

    /**
     * Known-answer vector (computed independently with Python hashlib.pbkdf2_hmac). The Gradle build
     * hashes the built-in Super Admin PIN with the same routine (SEC-05, 6.2.18); if either side
     * drifts from PBKDF2-SHA256, 50 000 rounds, 32 bytes, lowercase hex, this test or that PIN breaks.
     */
    @Test
    public void hash_matchesIndependentPbkdf2Vector() {
        assertEquals("7aad7c78ecb23a97acda7edde5179321bf84b89350bf8dcd7650c8f0eb18ae8b", AdminPins.hash("246810", SALT_A));
        assertEquals("1f642668dcaf64e49456f668b711827d9db0262a499fcab7af4740806b164298", AdminPins.hash("97531864", SALT_B));
    }

    // SEC-05 (6.2.18): a built-in Super Admin PIN, hashed into the build under its own salt, applies
    // until CasHUB pushes super_admin_pin. The built-in hash is checked under the BUILD salt, the
    // pushed hashes under the per-terminal salt.

    private static final String BUILT_IN_SALT = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final String BUILT_IN_SUPER = AdminPins.hash("97531864", BUILT_IN_SALT);

    @Test
    public void tier_builtInSuperApplies_whenNothingPushed() {
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("97531864", null, null, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("97531865", null, null, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("", null, null, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
    }

    @Test
    public void tier_builtInSuperStillApplies_whenOnlyAdminPushed() {
        String admin = AdminPins.hash("246810", SALT_A);
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("97531864", admin, null, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_NORMAL, AdminPins.tier("246810", admin, null, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("97531864", admin, "", SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));   // empty = not pushed
    }

    @Test
    public void tier_pushedSuperReplacesBuiltIn() {
        String sup = AdminPins.hash("13579246", SALT_A);
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("13579246", null, sup, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("97531864", null, sup, SALT_A, BUILT_IN_SUPER, BUILT_IN_SALT));   // built-in revoked
    }

    @Test
    public void tier_builtInIsCheckedUnderTheBuildSalt_notTheTerminalSalt() {
        // Same PIN hashed under the terminal salt must NOT count as the built-in: only the build's own
        // hash+salt pair does. Guards against the store passing the wrong salt.
        String wrongPair = AdminPins.hash("97531864", SALT_A);
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("97531864", null, null, SALT_A, wrongPair, BUILT_IN_SALT));
        assertEquals(AdminPins.TIER_SUPER, AdminPins.tier("97531864", null, null, SALT_A, wrongPair, SALT_A));
    }

    @Test
    public void tier_noBuiltIn_andNothingPushed_isNone() {
        // A debug build made without admin-pins.properties carries empty strings.
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("97531864", null, null, SALT_A, "", ""));
        assertEquals(AdminPins.TIER_NONE, AdminPins.tier("97531864", null, null, SALT_A, null, null));
    }

    @Test
    public void newSalt_isRandomHex() {
        String s1 = AdminPins.newSaltHex(), s2 = AdminPins.newSaltHex();
        assertEquals(32, s1.length());
        assertTrue(s1.matches("[0-9a-f]+"));
        assertNotEquals(s1, s2);
    }
}
