package castech.emvtxn.atm.host;

import java.security.SecureRandom;

/**
 * PIN Block Formatter
 *
 * Creates ISO 9564-1 PIN blocks for ATM transactions.
 *
 * Supports two formats:
 *
 * Format 0 PIN Block (requires PAN):
 *   PIN Block = (Format + PIN + Padding) XOR (0000 + PAN12)
 *   PIN Component: 0 + length + PIN + F padding
 *   PAN Component: 0000 + rightmost 12 PAN digits (excluding check digit)
 *
 * Format 1 PIN Block (NO PAN required):
 *   PIN Block = Format + PIN + Random padding
 *   Format: 1 + length + PIN + random hex digits
 *   No XOR with PAN - uses random padding instead
 *
 * Format 1 is useful when:
 * - Terminal doesn't have access to clear PAN (PCI compliance masking)
 * - Server can translate Format 1 to Format 0 using PAN from track data
 */
public final class PinBlockFormatter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PinBlockFormatter() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates an ISO 9564-1 Format 0 PIN block (clear text, not encrypted).
     *
     * @param pin The PIN (4-6 digits)
     * @param pan The Primary Account Number (13-19 digits)
     * @return The PIN block as 16 hex character string
     * @throws IllegalArgumentException if PIN or PAN is invalid
     */
    public static String createFormat0PinBlock(String pin, String pan) {
        validatePin(pin);
        validatePan(pan);

        // Build PIN component: 0 + length + PIN + padding
        String pinComponent = buildPinComponent(pin);

        // Build PAN component: 0000 + rightmost 12 digits (excluding check digit)
        String panComponent = buildPanComponent(pan);

        // XOR the two components
        byte[] pinBytes = hexStringToBytes(pinComponent);
        byte[] panBytes = hexStringToBytes(panComponent);
        byte[] pinBlock = new byte[8];

        for (int i = 0; i < 8; i++) {
            pinBlock[i] = (byte) (pinBytes[i] ^ panBytes[i]);
        }

        return bytesToHexString(pinBlock);
    }

    /**
     * Creates an ISO 9564-1 Format 0 PIN block from track 2 data.
     *
     * @param pin The PIN (4-6 digits)
     * @param track2 Track 2 data with sentinels (;PAN=YYMM...?)
     * @return The PIN block as 16 hex character string
     * @throws IllegalArgumentException if PIN or track 2 is invalid
     */
    public static String createFormat0PinBlockFromTrack2(String pin, String track2) {
        String pan = extractPanFromTrack2(track2);
        return createFormat0PinBlock(pin, pan);
    }

    /**
     * Creates an ISO 9564-1 Format 1 PIN block (NO PAN required).
     *
     * Format 1 uses random padding instead of PAN XOR.
     * Structure: 1 | PIN length | PIN digits | random padding
     *
     * This is useful when the terminal doesn't have access to the clear PAN
     * (e.g., Castle SDK masks PAN for PCI compliance). The server can
     * translate Format 1 to Format 0 using the PAN from decrypted track data.
     *
     * @param pin The PIN (4-6 digits)
     * @return The PIN block as 16 hex character string
     * @throws IllegalArgumentException if PIN is invalid
     */
    public static String createFormat1PinBlock(String pin) {
        validatePin(pin);

        StringBuilder sb = new StringBuilder(16);

        // Format code '1'
        sb.append('1');

        // PIN length (as hex digit)
        sb.append(Integer.toHexString(pin.length()).toUpperCase());

        // PIN digits
        sb.append(pin);

        // Random padding to fill remaining positions
        // Each position must be a random hex digit (0-9, A-F)
        // but must NOT match any digit in the PIN
        while (sb.length() < 16) {
            // Generate random hex digit (0-F)
            int rand = RANDOM.nextInt(16);
            char randChar;
            if (rand < 10) {
                randChar = (char) ('0' + rand);
            } else {
                randChar = (char) ('A' + rand - 10);
            }

            // ISO 9564-1 Format 1 requires fill digits to not match PIN digits
            // This helps prevent certain attacks
            if (pin.indexOf(randChar) == -1) {
                sb.append(randChar);
            }
        }

        return sb.toString();
    }

    /**
     * Builds the PIN component for Format 0.
     * Format: 0 + length + PIN + F padding
     *
     * @param pin The PIN digits
     * @return 16 hex character PIN component
     */
    private static String buildPinComponent(String pin) {
        StringBuilder sb = new StringBuilder(16);

        // Format code '0'
        sb.append('0');

        // PIN length (as hex digit)
        sb.append(Integer.toHexString(pin.length()).toUpperCase());

        // PIN digits
        sb.append(pin);

        // Padding with 'F' to 16 characters
        while (sb.length() < 16) {
            sb.append('F');
        }

        return sb.toString();
    }

    /**
     * Builds the PAN component for Format 0.
     * Format: 0000 + rightmost 12 PAN digits (excluding check digit)
     *
     * @param pan The PAN
     * @return 16 hex character PAN component
     */
    private static String buildPanComponent(String pan) {
        // Remove any spaces or dashes
        pan = pan.replaceAll("[\\s-]", "");

        // Get rightmost 13 digits, then exclude check digit (last one)
        // This gives us the rightmost 12 digits excluding check
        int startIndex = Math.max(0, pan.length() - 13);
        String pan13 = pan.substring(startIndex);

        // Remove check digit (last digit)
        String pan12 = pan13.substring(0, Math.min(12, pan13.length() - 1));

        // Pad to 12 if needed (shouldn't happen with valid PAN)
        while (pan12.length() < 12) {
            pan12 = "0" + pan12;
        }

        // Prepend with 0000
        return "0000" + pan12;
    }

    /**
     * Extracts PAN from track 2 data.
     *
     * @param track2 Track 2 data (;PAN=YYMM...? or PAN=YYMM...)
     * @return The PAN
     * @throws IllegalArgumentException if track 2 format is invalid
     */
    public static String extractPanFromTrack2(String track2) {
        if (track2 == null || track2.isEmpty()) {
            throw new IllegalArgumentException("Track 2 data is empty");
        }

        String data = track2;

        // Remove start sentinel
        if (data.startsWith(";")) {
            data = data.substring(1);
        }

        // Remove end sentinel
        if (data.endsWith("?")) {
            data = data.substring(0, data.length() - 1);
        }

        // Find separator
        int sepIndex = data.indexOf('=');
        if (sepIndex == -1) {
            sepIndex = data.indexOf('D'); // Some systems use 'D' as separator
        }

        if (sepIndex == -1) {
            throw new IllegalArgumentException("Invalid track 2 format: no separator found");
        }

        String pan = data.substring(0, sepIndex);

        if (pan.length() < 13 || pan.length() > 19) {
            throw new IllegalArgumentException("Invalid PAN length: " + pan.length());
        }

        return pan;
    }

    /**
     * Extracts expiration date from track 2 data.
     *
     * @param track2 Track 2 data
     * @return Expiration date as YYMM
     */
    public static String extractExpirationFromTrack2(String track2) {
        if (track2 == null || track2.isEmpty()) {
            return null;
        }

        String data = track2;
        if (data.startsWith(";")) {
            data = data.substring(1);
        }
        if (data.endsWith("?")) {
            data = data.substring(0, data.length() - 1);
        }

        int sepIndex = data.indexOf('=');
        if (sepIndex == -1) {
            sepIndex = data.indexOf('D');
        }
        if (sepIndex == -1 || sepIndex + 4 >= data.length()) {
            return null;
        }

        return data.substring(sepIndex + 1, sepIndex + 5);
    }

    /**
     * Validates the PIN.
     */
    private static void validatePin(String pin) {
        if (pin == null || pin.isEmpty()) {
            throw new IllegalArgumentException("PIN is empty");
        }
        if (pin.length() < 4 || pin.length() > 6) {
            throw new IllegalArgumentException("PIN must be 4-6 digits, got: " + pin.length());
        }
        if (!pin.matches("\\d+")) {
            throw new IllegalArgumentException("PIN must contain only digits");
        }
    }

    /**
     * Validates the PAN.
     */
    private static void validatePan(String pan) {
        if (pan == null || pan.isEmpty()) {
            throw new IllegalArgumentException("PAN is empty");
        }

        String cleanPan = pan.replaceAll("[\\s-]", "");

        if (cleanPan.length() < 13 || cleanPan.length() > 19) {
            throw new IllegalArgumentException("PAN must be 13-19 digits, got: " + cleanPan.length());
        }
        if (!cleanPan.matches("\\d+")) {
            throw new IllegalArgumentException("PAN must contain only digits");
        }
    }

    /**
     * Converts hex string to byte array.
     */
    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Converts byte array to hex string.
     */
    private static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Masks a PAN for display (shows first 6 and last 4).
     *
     * @param pan The PAN
     * @return Masked PAN (e.g., "443041******8318")
     */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 13) {
            return pan;
        }

        String cleanPan = pan.replaceAll("[\\s-]", "");
        int len = cleanPan.length();

        StringBuilder sb = new StringBuilder();
        sb.append(cleanPan.substring(0, 6));
        for (int i = 6; i < len - 4; i++) {
            sb.append('*');
        }
        sb.append(cleanPan.substring(len - 4));

        return sb.toString();
    }

    /**
     * Masks a PAN showing only last 4 digits.
     *
     * @param pan The PAN
     * @return Masked PAN (e.g., "************8318")
     */
    public static String maskPanLast4(String pan) {
        if (pan == null || pan.length() < 4) {
            return pan;
        }

        String cleanPan = pan.replaceAll("[\\s-]", "");
        int len = cleanPan.length();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len - 4; i++) {
            sb.append('*');
        }
        sb.append(cleanPan.substring(len - 4));

        return sb.toString();
    }

    /**
     * Validates PAN using Luhn algorithm (check digit validation).
     *
     * @param pan The PAN to validate
     * @return true if the PAN passes Luhn check
     */
    public static boolean validateLuhn(String pan) {
        if (pan == null || pan.isEmpty()) {
            return false;
        }

        String cleanPan = pan.replaceAll("[\\s-]", "");
        if (!cleanPan.matches("\\d+")) {
            return false;
        }

        int sum = 0;
        boolean alternate = false;

        for (int i = cleanPan.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cleanPan.charAt(i));

            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum += digit;
            alternate = !alternate;
        }

        return sum % 10 == 0;
    }

    /**
     * Gets the card network from the PAN (based on BIN).
     *
     * @param pan The PAN
     * @return Card network name (Visa, Mastercard, etc.)
     */
    public static String getCardNetwork(String pan) {
        if (pan == null || pan.length() < 1) {
            return "Unknown";
        }

        String cleanPan = pan.replaceAll("[\\s-]", "");
        char first = cleanPan.charAt(0);

        // Simple BIN-based detection
        if (first == '4') {
            return "Visa";
        } else if (first == '5') {
            if (cleanPan.length() >= 2) {
                char second = cleanPan.charAt(1);
                if (second >= '1' && second <= '5') {
                    return "Mastercard";
                }
            }
            return "Mastercard";
        } else if (first == '3') {
            if (cleanPan.length() >= 2) {
                char second = cleanPan.charAt(1);
                if (second == '4' || second == '7') {
                    return "Amex";
                }
            }
            return "JCB/Amex";
        } else if (first == '6') {
            if (cleanPan.startsWith("6011") || cleanPan.startsWith("65")) {
                return "Discover";
            }
            return "Discover/UnionPay";
        }

        return "Unknown";
    }
}
