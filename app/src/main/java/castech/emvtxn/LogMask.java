package castech.emvtxn;

/**
 * Masks for log output.
 *
 * <p>Card data (PAN, Track 2) and PIN blocks must never reach logcat in a
 * readable form: PCI DSS forbids retaining sensitive authentication data after
 * authorisation, and logs count as retention — adb is enabled on fielded
 * terminals. Encrypted PIN blocks are logged as a byte count only (SEC-01
 * applied the same rule inside CastleKeyManager). PANs show at most the last
 * four digits, which the printed receipt already shows.</p>
 */
public final class LogMask {

    private LogMask() {}

    /** {@code "****1234"} — the last four digits of a PAN, or a placeholder. */
    public static String pan(String pan) {
        if (pan == null) return "[null]";
        String digits = pan.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "[empty]";
        if (digits.length() <= 4) return "****";
        return "****" + digits.substring(digits.length() - 4);
    }

    /**
     * {@code "[Track 2: 37 chars, PAN ****1234]"} — never the data itself.
     * Accepts the ASCII form ({@code ;PAN=...?}) or the hex/BCD form
     * ({@code PAN D ...}); the PAN is everything before the field separator.
     */
    public static String track2(String track2) {
        if (track2 == null) return "[Track 2: null]";
        if (track2.isEmpty()) return "[Track 2: empty]";
        String pan = track2.replaceFirst("^;", "").split("[=Dd]", 2)[0];
        return "[Track 2: " + track2.length() + " chars, PAN " + pan(pan) + "]";
    }

    /** {@code "[8 bytes]"} for a hex-encoded block — never the ciphertext. */
    public static String pinBlock(String hex) {
        if (hex == null) return "[null]";
        if (hex.isEmpty()) return "[empty]";
        return "[" + (hex.length() / 2) + " bytes]";
    }

    /** {@code "[N chars]"} for any other value that must not be logged verbatim. */
    public static String len(String s) {
        return s == null ? "[null]" : "[" + s.length() + " chars]";
    }
}
