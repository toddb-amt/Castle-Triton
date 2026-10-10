package castech.emvtxn.admin;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Admin PIN hashing and tier decision (SEC-03, 6.2.15). Pure Java.
 *
 * <p>Until 6.2.14 the two Admin PINs were string constants in the APK, identical on every terminal
 * and readable by anyone who unzipped it. Now the terminal keeps only a salted PBKDF2-SHA256 hash
 * (50 000 rounds, a per-terminal random salt) and compares hash to hash, in constant time. The
 * same PIN hashes differently on every terminal, and a storage dump gives an attacker a slow
 * offline search rather than the PIN.
 */
public final class AdminPins {

    public static final int TIER_NONE = 0;
    public static final int TIER_NORMAL = 1;
    public static final int TIER_SUPER = 2;

    static final int ROUNDS = 50_000;
    private static final int KEY_BITS = 256;

    private AdminPins() {}

    /** Hex PBKDF2-SHA256 of the PIN under the salt (hex). */
    public static String hash(String pin, String saltHex) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), fromHex(saltHex), ROUNDS, KEY_BITS);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return toHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable: " + e);
        }
    }

    /** True when the entered PIN hashes to the stored hash under the salt. Null/empty never match. */
    public static boolean matches(String enteredPin, String storedHashHex, String saltHex) {
        if (enteredPin == null || enteredPin.isEmpty() || storedHashHex == null || storedHashHex.isEmpty() || saltHex == null) return false;
        byte[] a = fromHex(hash(enteredPin, saltHex));
        byte[] b = fromHex(storedHashHex);
        return MessageDigest.isEqual(a, b);
    }

    /** Super is checked first so the two PINs can never be confused; nothing configured → NONE. */
    public static int tier(String enteredPin, String adminHashHex, String superHashHex, String saltHex) {
        if (matches(enteredPin, superHashHex, saltHex)) return TIER_SUPER;
        if (matches(enteredPin, adminHashHex, saltHex)) return TIER_NORMAL;
        return TIER_NONE;
    }

    /** 16 random bytes as hex, generated once per terminal. */
    public static String newSaltHex() {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        return toHex(s);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
