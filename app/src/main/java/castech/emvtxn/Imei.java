package castech.emvtxn;

/**
 * ADM-10 (6.2.20): the IMEI as the Admin Network card shows it. The S1F4 PRO's modem reports the
 * IMEI without its check digit (14 digits, bench 2026-10-22), while the device label, CasHUB and every
 * carrier form use the 15-digit form; the Luhn check digit is appended so the two match by eye. A
 * 15-digit value is shown as given; anything else is shown as given too, never altered. Pure Java,
 * pinned by ImeiTest.
 */
public final class Imei {

    private Imei() {}

    /** The 15-digit form for display, or the raw text when it is not a 14- or 15-digit number; null for nothing. */
    public static String display(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String digits = t.replaceAll("[\\s-]", "");
        if (digits.matches("\\d{14}")) return digits + checkDigit(digits);
        if (digits.matches("\\d{15}")) return digits;
        return t;
    }

    /** The Luhn check digit for the 14 leading digits of an IMEI. */
    public static int checkDigit(String fourteen) {
        int sum = 0;
        for (int i = 0; i < fourteen.length(); i++) {
            int d = fourteen.charAt(i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }      // every second digit from the left, doubled
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }

    /** For logs: the type allocation code (first eight digits) and stars. */
    public static String masked(String imei) {
        if (imei == null || imei.length() < 8) return "(none)";
        StringBuilder sb = new StringBuilder(imei.substring(0, 8));
        for (int i = 8; i < imei.length(); i++) sb.append('*');
        return sb.toString();
    }
}
