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

    /**
     * Renders a Hyosung STD1 message for logging with card data removed. Fields
     * are split on FS (0x1C); a Track-2-shaped field is rendered by {@link #track2},
     * a long hex field (EMV TLV, with or without the {@code ud} prefix) by
     * {@link #tlv}, an encrypted-track field ({@code e} + hex) and a PIN-block-shaped
     * field as byte counts; everything else as ASCII with control bytes escaped.
     * This keeps the reversal diagnostics readable — more so than a hex dump —
     * without the PAN or Track 2 that a Type 86 echoes from the original 85.
     */
    public static String std1(byte[] msg) {
        if (msg == null) return "[null]";
        StringBuilder out = new StringBuilder(msg.length + 32);
        out.append("[").append(msg.length).append(" bytes] ");
        StringBuilder field = new StringBuilder();
        for (int i = 0; i <= msg.length; i++) {
            if (i == msg.length || msg[i] == 0x1C) {
                out.append(std1Field(field.toString()));
                field.setLength(0);
                if (i < msg.length) out.append("<FS>");
                continue;
            }
            int b = msg[i] & 0xFF;
            if (b >= 0x20 && b < 0x7F) field.append((char) b);
            else field.append(String.format("\\x%02X", b));
        }
        return out.toString();
    }

    private static String std1Field(String f) {
        if (f.isEmpty()) return "";
        // Framing bytes share a field with the payload (no FS around STX / the
        // length header at the front, ETX / LRC at the back). Classify the payload
        // between the escaped \xNN sequences and keep them in the output.
        int p = 0;
        while (f.startsWith("\\x", p) && p + 4 <= f.length()) p += 4;
        // ETX closes the payload; the LRC after it may be a printable byte, so it
        // cannot be found by scanning for escapes from the end. A payload never
        // contains a backslash, so "\x03" can only be a real ETX.
        int q = f.indexOf("\\x03", p);
        if (q < 0) {
            q = f.length();
            while (q - 4 >= p && f.startsWith("\\x", q - 4)) q -= 4;
        }
        return f.substring(0, p) + std1Payload(f.substring(p, q)) + f.substring(q);
    }

    private static String std1Payload(String f) {
        if (f.isEmpty()) return "";
        if (f.matches("^;?[0-9]{13,19}[=Dd].*")) return track2(f);              // clear Track 2
        if (f.matches("^e[0-9A-Fa-f]{32,}$")) return "e[" + ((f.length() - 1) / 2) + " bytes encrypted]";
        if (f.startsWith("ud")) return "ud" + tlv(f.substring(2));                // EMV TLV — "ud" prefix is definitive
        if (f.length() >= 32 && f.matches("^[0-9A-Fa-f]+$")) return tlv(f);      // unprefixed EMV TLV; short hex (KSN) stays readable
        if (f.matches("^[0-9A-Fa-f]{16}$") && !f.matches("^[0-9]+$")) return pinBlock(f); // PIN block
        return f;
    }

    /** EMV tags whose value must never be logged: PAN, Track 2 equivalent, cardholder name. */
    private static final java.util.Set<String> REDACTED_TAGS =
            new java.util.HashSet<>(java.util.Arrays.asList("5A", "57", "5F20"));

    /**
     * Re-emits a BER-TLV hex string with the values of {@link #REDACTED_TAGS}
     * replaced by {@code [N bytes redacted]}. An EMV dump keeps its diagnostic
     * value — cryptogram, TVR, AIP, CVM results, issuer data — without the card
     * data that PCI forbids retaining. Constructed tags are walked. If the string
     * stops parsing part-way, the remainder is emitted as a placeholder, never raw.
     */
    public static String tlv(String hex) {
        if (hex == null) return "[null]";
        if (hex.isEmpty()) return "[empty]";
        StringBuilder out = new StringBuilder(hex.length());
        try {
            redactTlv(hex.toUpperCase(), out);
        } catch (RuntimeException e) {
            return "[" + hex.length() + " chars, unparsed]";
        }
        return out.toString();
    }

    private static void redactTlv(String h, StringBuilder out) {
        int i = 0, n = h.length();
        while (i < n) {
            if (i + 2 > n) { out.append("[").append(n - i).append(" chars, unparsed]"); return; }
            // 00 / FF between objects is padding
            if (h.startsWith("00", i) || h.startsWith("FF", i)) { out.append(h, i, i + 2); i += 2; continue; }

            int tagStart = i;
            int first = Integer.parseInt(h.substring(i, i + 2), 16); i += 2;
            if ((first & 0x1F) == 0x1F) {                       // multi-byte tag
                int b;
                do {
                    if (i + 2 > n) { out.append(h, tagStart, n).append("[trunc]"); return; }
                    b = Integer.parseInt(h.substring(i, i + 2), 16); i += 2;
                } while ((b & 0x80) != 0);
            }
            String tag = h.substring(tagStart, i);

            if (i + 2 > n) { out.append(tag).append("[trunc]"); return; }
            int lenStart = i;
            int len = Integer.parseInt(h.substring(i, i + 2), 16); i += 2;
            if (len >= 0x80) {                                   // long-form length
                int nb = len & 0x7F;
                if (nb == 0 || nb > 3 || i + 2 * nb > n) { out.append(tag).append("[bad len]"); return; }
                len = Integer.parseInt(h.substring(i, i + 2 * nb), 16); i += 2 * nb;
            }
            String lenHex = h.substring(lenStart, i);

            int vEnd = i + 2 * len;
            if (vEnd > n) {
                out.append(tag).append(lenHex).append("[trunc ").append((n - i) / 2).append(" of ").append(len).append(" bytes]");
                return;
            }
            String value = h.substring(i, vEnd);
            out.append(tag).append(lenHex);
            if (REDACTED_TAGS.contains(tag)) {
                out.append("[").append(len).append(" bytes redacted]");
            } else if ((first & 0x20) != 0) {                    // constructed: walk the children
                redactTlv(value, out);
            } else {
                out.append(value);
            }
            i = vEnd;
        }
    }
}
