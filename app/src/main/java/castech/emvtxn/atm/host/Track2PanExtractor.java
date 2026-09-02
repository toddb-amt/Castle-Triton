package castech.emvtxn.atm.host;

/**
 * Extracts the clear PAN from a Track 2 buffer, auto-detecting whether the
 * buffer is ASCII-encoded or BCD-packed.
 *
 * <p><b>Why this exists:</b> the same {@code track2Data} field arrives in two
 * different encodings depending on entry mode:
 * <ul>
 *   <li><b>Contactless (EMVCL {@code rcData.track2Data})</b> is <b>ASCII</b>:
 *       the start sentinel {@code ';'} (0x3B), digits as ASCII bytes, field
 *       separator {@code '='} (0x3D), end sentinel {@code '?'} (0x3F).</li>
 *   <li><b>Contact / DF35 / Tag 57</b> is <b>BCD-packed</b>: two decimal digits
 *       per byte, nibble {@code D} separates PAN from expiry, nibble {@code F}
 *       pads.</li>
 * </ul>
 *
 * The old contactless code hex-dumped the ASCII buffer and searched for a
 * literal {@code 'D'} (the BCD separator). In ASCII that {@code 'D'} first
 * appears inside {@code 3D} (the {@code '='} separator's own byte), landing at
 * an odd, mid-byte offset — so the receipt's "last four digits" came out as
 * meaningless hex nibbles while the PIN block (which uses Tag 5A) was correct.
 * See CASTLE-POS-DEFECTS — reproduced on S1F4 PRO 2026-09-02.
 *
 * <p>Pure and side-effect free so it can be unit-tested off-device.
 */
public final class Track2PanExtractor {

    private Track2PanExtractor() {}

    /**
     * Returns the clear PAN (digits only, no sentinels/separator) parsed from a
     * Track 2 buffer, or {@code null} if none can be read. Encoding (ASCII vs
     * BCD) is auto-detected.
     *
     * @param track2 raw Track 2 bytes (may be null)
     * @param len    number of valid bytes; clamped to the array length
     */
    public static String extractPan(byte[] track2, int len) {
        if (track2 == null) return null;
        if (len < 0 || len > track2.length) len = track2.length;
        if (len == 0) return null;

        if (isAscii(track2, len)) {
            // ASCII: ";PAN=YYMM...?" — collect digits up to the field separator.
            StringBuilder pan = new StringBuilder();
            for (int i = 0; i < len; i++) {
                char c = (char) (track2[i] & 0xFF);
                if (c == '=' || c == 'D' || c == 'd') break; // separator ('=' std; 'D' tolerated)
                if (c >= '0' && c <= '9') pan.append(c);
                // ';' start sentinel and anything else is skipped
            }
            return pan.length() > 0 ? pan.toString() : null;
        }

        // BCD-packed: two digits per byte; cut at the first 'D' nibble, drop 'F' padding.
        StringBuilder hex = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            hex.append(Character.forDigit((track2[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(track2[i] & 0xF, 16));
        }
        String h = hex.toString().toUpperCase();
        int sep = h.indexOf('D');
        String panPart = (sep >= 0) ? h.substring(0, sep) : h;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < panPart.length(); i++) {
            char c = panPart.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else break; // stop at first non-digit (e.g. leftover 'F' padding)
        }
        return digits.length() > 0 ? digits.toString() : null;
    }

    /**
     * Masks a PAN for receipt display / storage: keeps the first 6 and last 4
     * digits, masks the middle with {@code '*'} (PCI-safe). Non-digits are
     * stripped first. Short PANs keep only the last 4. Returns the input
     * unchanged if it holds fewer than 4 digits.
     */
    public static String maskPan(String pan) {
        if (pan == null) return null;
        String d = pan.replaceAll("[^0-9]", "");
        int n = d.length();
        if (n < 4) return pan;
        StringBuilder sb = new StringBuilder();
        if (n <= 10) {
            for (int i = 0; i < n - 4; i++) sb.append('*');
        } else {
            sb.append(d, 0, 6);
            for (int i = 0; i < n - 10; i++) sb.append('*');
        }
        sb.append(d.substring(n - 4));
        return sb.toString();
    }

    /**
     * True if the buffer is ASCII Track 2 (start sentinel {@code ';'}, or every
     * byte in the printable digit/sentinel range 0x30-0x3F). A BCD-packed PAN
     * starts with packed digits (e.g. 0x44 for "44"), so it fails both checks.
     */
    private static boolean isAscii(byte[] b, int len) {
        if ((b[0] & 0xFF) == ';') return true;
        for (int i = 0; i < len; i++) {
            int v = b[i] & 0xFF;
            if (v < 0x30 || v > 0x3F) return false;
        }
        return true;
    }
}
