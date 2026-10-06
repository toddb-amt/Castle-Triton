package castech.emvtxn.util;

/**
 * PCI-compliant PAN and Track 2 masking utility.
 * Shows first 6 (BIN) and last 4 digits only.
 */
public class PanMasker {

    /**
     * Masks a PAN to show first 6 and last 4 digits.
     * Example: "4430411234568318" -> "443041******8318"
     */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 10) {
            return "****";
        }
        String cleaned = pan.replaceAll("[^0-9]", "");
        if (cleaned.length() < 10) {
            return "****";
        }
        int maskLen = cleaned.length() - 10;
        StringBuilder masked = new StringBuilder();
        masked.append(cleaned.substring(0, 6));
        for (int i = 0; i < maskLen + 4; i++) {
            masked.append('*');
        }
        // Only show last 4 if length allows
        if (cleaned.length() > 10) {
            masked = new StringBuilder();
            masked.append(cleaned.substring(0, 6));
            for (int i = 0; i < cleaned.length() - 10; i++) {
                masked.append('*');
            }
            masked.append(cleaned.substring(cleaned.length() - 4));
        } else {
            masked = new StringBuilder();
            masked.append(cleaned.substring(0, 6));
            masked.append(cleaned.substring(cleaned.length() - 4));
        }
        return masked.toString();
    }

    /**
     * Masks Track 2 data to show only first 6 and last 4 of PAN portion.
     * Track 2 format: PAN=YYMM... or PAN separated by 'D' or '='
     * Example: "4430411234568318D2512..." -> "443041******8318D2512..."
     */
    public static String maskTrack2(String track2) {
        if (track2 == null || track2.isEmpty()) {
            return "****";
        }
        // Find separator (D or =)
        int sepIdx = track2.indexOf('D');
        if (sepIdx < 0) sepIdx = track2.indexOf('d');
        if (sepIdx < 0) sepIdx = track2.indexOf('=');

        if (sepIdx < 10) {
            return "****" + (sepIdx > 0 ? track2.substring(sepIdx) : "");
        }

        String pan = track2.substring(0, sepIdx);
        String rest = track2.substring(sepIdx);
        return maskPan(pan) + rest;
    }

    /**
     * Masks hex-encoded data for logging.
     * Shows first 12 hex chars (6 bytes) and last 8 hex chars (4 bytes).
     */
    public static String maskHex(String hex) {
        if (hex == null || hex.length() < 20) {
            return "****";
        }
        return hex.substring(0, 12) + "****" + hex.substring(hex.length() - 8);
    }
}
