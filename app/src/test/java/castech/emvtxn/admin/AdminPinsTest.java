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

    @Test
    public void newSalt_isRandomHex() {
        String s1 = AdminPins.newSaltHex(), s2 = AdminPins.newSaltHex();
        assertEquals(32, s1.length());
        assertTrue(s1.matches("[0-9a-f]+"));
        assertNotEquals(s1, s2);
    }
}
