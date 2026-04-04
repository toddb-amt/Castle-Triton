package castech.emvtxn.atm.host;

/**
 * Longitudinal Redundancy Check (LRC) Calculator
 *
 * Calculates the LRC checksum for Hyosung STD1 protocol messages.
 * The LRC is computed by XORing all bytes from after STX up to and including ETX.
 */
public final class LrcCalculator {

    private LrcCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Calculates LRC for message data between STX and ETX (exclusive of STX).
     *
     * Algorithm:
     *   LRC = 0x00
     *   for each byte from position (STX + 1) to ETX inclusive:
     *       LRC = LRC XOR byte
     *
     * @param data The message data (should include all bytes from after STX to ETX inclusive)
     * @return The calculated LRC byte
     */
    public static byte calculate(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }

        byte lrc = 0;
        for (byte b : data) {
            lrc ^= b;
        }
        return lrc;
    }

    /**
     * Calculates LRC for a portion of the byte array.
     *
     * @param data The full byte array
     * @param offset Starting position (inclusive)
     * @param length Number of bytes to include
     * @return The calculated LRC byte
     */
    public static byte calculate(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) {
            return 0;
        }

        byte lrc = 0;
        for (int i = offset; i < offset + length; i++) {
            lrc ^= data[i];
        }
        return lrc;
    }

    /**
     * Calculates LRC for message content (String) plus ETX.
     * This is a convenience method for building messages.
     *
     * @param content The message content (fields separated by FS)
     * @return The calculated LRC byte
     */
    public static byte calculateForMessage(String content) {
        if (content == null || content.isEmpty()) {
            return HyosungProtocol.ETX; // Just ETX if empty
        }

        byte lrc = 0;
        // XOR all content bytes
        byte[] contentBytes = content.getBytes();
        for (byte b : contentBytes) {
            lrc ^= b;
        }
        // XOR with ETX
        lrc ^= HyosungProtocol.ETX;
        return lrc;
    }

    /**
     * Calculates LRC for a complete message (from after STX to ETX inclusive).
     *
     * @param messageWithoutStx Message bytes starting after STX, including ETX
     * @return The calculated LRC byte
     */
    public static byte calculateFromMessage(byte[] messageWithoutStx) {
        return calculate(messageWithoutStx);
    }

    /**
     * Verifies the LRC of a received message.
     *
     * @param data The message data (from after STX to ETX inclusive)
     * @param receivedLrc The LRC byte received with the message
     * @return true if the LRC is valid
     */
    public static boolean verify(byte[] data, byte receivedLrc) {
        return calculate(data) == receivedLrc;
    }

    /**
     * Verifies the LRC of a complete received message (including LRC).
     * XORing all bytes including the LRC should result in 0x00 if valid.
     *
     * @param dataWithLrc The message data including the LRC byte at the end
     * @return true if the LRC is valid
     */
    public static boolean verifyComplete(byte[] dataWithLrc) {
        if (dataWithLrc == null || dataWithLrc.length == 0) {
            return false;
        }
        // XOR of all bytes including LRC should be 0 if valid
        byte result = 0;
        for (byte b : dataWithLrc) {
            result ^= b;
        }
        return result == 0;
    }

    /**
     * Extracts and verifies LRC from a complete framed message.
     * Expects message in format: [STX][content][ETX][LRC]
     *
     * @param framedMessage The complete message including STX, content, ETX, and LRC
     * @return true if the message has valid framing and LRC
     */
    public static boolean verifyFramedMessage(byte[] framedMessage) {
        if (framedMessage == null || framedMessage.length < 4) {
            return false; // Minimum: STX + 1 byte + ETX + LRC
        }

        // Check STX at start
        if (framedMessage[0] != HyosungProtocol.STX) {
            return false;
        }

        // Find ETX
        int etxPos = -1;
        for (int i = 1; i < framedMessage.length - 1; i++) {
            if (framedMessage[i] == HyosungProtocol.ETX) {
                etxPos = i;
                break;
            }
        }

        if (etxPos == -1 || etxPos != framedMessage.length - 2) {
            return false; // ETX not found or not in expected position
        }

        // LRC is the last byte
        byte receivedLrc = framedMessage[framedMessage.length - 1];

        // Calculate LRC from byte after STX to ETX inclusive
        byte calculatedLrc = calculate(framedMessage, 1, framedMessage.length - 2);

        return calculatedLrc == receivedLrc;
    }
}
