package castech.emvtxn.atm.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Message Framing for Hyosung STD1 Protocol
 *
 * Supports two framing types:
 * 1. Standard (STX/ETX): Used by DNS, FIS, CDS, ITS Systems, Planet Payment, Worldpay
 * 2. VISA (Length Header): Used by Switch Commerce, Cardtronics, ASAI, EFX, Elan/Genpass, NRT/TNS
 */
public final class MessageFraming {

    private MessageFraming() {
        // Utility class - prevent instantiation
    }

    /**
     * Frames message content using Standard framing (STX/ETX).
     *
     * Format: [STX][content][ETX][LRC]
     *
     * @param content The message content (fields already joined with FS)
     * @return The framed message as byte array
     */
    public static byte[] frameStandard(String content) {
        byte[] contentBytes = content.getBytes();
        byte[] result = new byte[contentBytes.length + 3]; // STX + content + ETX + LRC

        result[0] = HyosungProtocol.STX;
        System.arraycopy(contentBytes, 0, result, 1, contentBytes.length);
        result[result.length - 2] = HyosungProtocol.ETX;

        // Calculate LRC (from after STX to ETX inclusive)
        result[result.length - 1] = LrcCalculator.calculate(result, 1, result.length - 2);

        return result;
    }

    /**
     * Frames message content using Standard framing (STX/ETX).
     *
     * @param contentBytes The message content as bytes
     * @return The framed message as byte array
     */
    public static byte[] frameStandard(byte[] contentBytes) {
        byte[] result = new byte[contentBytes.length + 3]; // STX + content + ETX + LRC

        result[0] = HyosungProtocol.STX;
        System.arraycopy(contentBytes, 0, result, 1, contentBytes.length);
        result[result.length - 2] = HyosungProtocol.ETX;

        // Calculate LRC (from after STX to ETX inclusive)
        result[result.length - 1] = LrcCalculator.calculate(result, 1, result.length - 2);

        return result;
    }

    /**
     * Frames message content using VISA framing (2-byte length header).
     *
     * Format: [2-byte length][STX][content][ETX][LRC]
     * Length = STX + content + ETX + LRC (excludes the 2 length bytes)
     *
     * @param content The message content (fields already joined with FS)
     * @return The framed message as byte array
     */
    public static byte[] frameVisa(String content) {
        byte[] contentBytes = content.getBytes();

        // Calculate length: STX(1) + content + ETX(1) + LRC(1)
        int messageLength = 1 + contentBytes.length + 1 + 1;

        byte[] result = new byte[2 + messageLength]; // 2-byte length + message

        // Big-endian length header
        result[0] = (byte) ((messageLength >> 8) & 0xFF);
        result[1] = (byte) (messageLength & 0xFF);

        // STX
        result[2] = HyosungProtocol.STX;

        // Content
        System.arraycopy(contentBytes, 0, result, 3, contentBytes.length);

        // ETX
        result[result.length - 2] = HyosungProtocol.ETX;

        // Calculate LRC (from after STX to ETX inclusive, which is indices 3 to length-2)
        result[result.length - 1] = LrcCalculator.calculate(result, 3, result.length - 4);

        return result;
    }

    /**
     * Frames message content using VISA framing (2-byte length header).
     *
     * @param contentBytes The message content as bytes
     * @return The framed message as byte array
     */
    public static byte[] frameVisa(byte[] contentBytes) {
        // Calculate length: STX(1) + content + ETX(1) + LRC(1)
        int messageLength = 1 + contentBytes.length + 1 + 1;

        byte[] result = new byte[2 + messageLength]; // 2-byte length + message

        // Big-endian length header
        result[0] = (byte) ((messageLength >> 8) & 0xFF);
        result[1] = (byte) (messageLength & 0xFF);

        // STX
        result[2] = HyosungProtocol.STX;

        // Content
        System.arraycopy(contentBytes, 0, result, 3, contentBytes.length);

        // ETX
        result[result.length - 2] = HyosungProtocol.ETX;

        // Calculate LRC (from after STX to ETX inclusive)
        result[result.length - 1] = LrcCalculator.calculate(result, 3, result.length - 4);

        return result;
    }

    /**
     * Frames message content using VISA framing WITHOUT STX/ETX/LRC.
     * Used by Switch Commerce style processors.
     *
     * Format: [2-byte length][content]
     * Length = content length only (no STX/ETX/LRC)
     *
     * @param content The message content (fields already joined with FS)
     * @return The framed message as byte array
     */
    public static byte[] frameVisaNoStxEtx(String content) {
        byte[] contentBytes = content.getBytes();
        return frameVisaNoStxEtx(contentBytes);
    }

    /**
     * Frames message content using VISA framing WITHOUT STX/ETX/LRC.
     * Used by Switch Commerce style processors.
     *
     * Format: [2-byte length][content]
     * Length = content length only (no STX/ETX/LRC)
     *
     * @param contentBytes The message content as bytes
     * @return The framed message as byte array
     */
    public static byte[] frameVisaNoStxEtx(byte[] contentBytes) {
        // Length is just the content length (no STX/ETX/LRC)
        int messageLength = contentBytes.length;

        byte[] result = new byte[2 + messageLength]; // 2-byte length + content

        // Big-endian length header
        result[0] = (byte) ((messageLength >> 8) & 0xFF);
        result[1] = (byte) (messageLength & 0xFF);

        // Content (no STX, ETX, or LRC)
        System.arraycopy(contentBytes, 0, result, 2, contentBytes.length);

        return result;
    }

    /**
     * Frames message using the specified framing type.
     *
     * @param content The message content
     * @param framingType The framing type to use
     * @return The framed message as byte array
     */
    public static byte[] frame(String content, HyosungProtocol.FramingType framingType) {
        switch (framingType) {
            case VISA_LENGTH_PREFIX:
                return frameVisa(content);
            case VISA_NO_STX_ETX:
                return frameVisaNoStxEtx(content);
            case STANDARD:
            default:
                return frameStandard(content);
        }
    }

    /**
     * Frames message using the specified framing type.
     *
     * @param contentBytes The message content as bytes
     * @param framingType The framing type to use
     * @return The framed message as byte array
     */
    public static byte[] frame(byte[] contentBytes, HyosungProtocol.FramingType framingType) {
        switch (framingType) {
            case VISA_LENGTH_PREFIX:
                return frameVisa(contentBytes);
            case VISA_NO_STX_ETX:
                return frameVisaNoStxEtx(contentBytes);
            case STANDARD:
            default:
                return frameStandard(contentBytes);
        }
    }

    /**
     * Unframes a Standard format message.
     * Verifies STX, ETX, and LRC.
     *
     * @param framedMessage The framed message bytes
     * @return The content bytes (without STX, ETX, LRC), or null if invalid
     * @throws FramingException if the message format is invalid
     */
    public static byte[] unframeStandard(byte[] framedMessage) throws FramingException {
        if (framedMessage == null || framedMessage.length < 4) {
            throw new FramingException("Message too short for Standard framing");
        }

        // Check STX
        if (framedMessage[0] != HyosungProtocol.STX) {
            throw new FramingException("Missing STX at start of message");
        }

        // Check ETX
        if (framedMessage[framedMessage.length - 2] != HyosungProtocol.ETX) {
            throw new FramingException("Missing ETX before LRC");
        }

        // Verify LRC
        byte receivedLrc = framedMessage[framedMessage.length - 1];
        byte calculatedLrc = LrcCalculator.calculate(framedMessage, 1, framedMessage.length - 2);
        if (receivedLrc != calculatedLrc) {
            throw new FramingException(String.format(
                "LRC mismatch: received 0x%02X, calculated 0x%02X", receivedLrc, calculatedLrc));
        }

        // Extract content (between STX and ETX)
        int contentLength = framedMessage.length - 3; // Total - STX - ETX - LRC
        byte[] content = new byte[contentLength];
        System.arraycopy(framedMessage, 1, content, 0, contentLength);

        return content;
    }

    /**
     * Unframes a VISA format message.
     * Verifies length header, STX, ETX, and LRC.
     *
     * @param framedMessage The framed message bytes
     * @return The content bytes (without length header, STX, ETX, LRC), or null if invalid
     * @throws FramingException if the message format is invalid
     */
    public static byte[] unframeVisa(byte[] framedMessage) throws FramingException {
        if (framedMessage == null || framedMessage.length < 6) {
            throw new FramingException("Message too short for VISA framing");
        }

        // Extract and verify length header (big-endian)
        int declaredLength = ((framedMessage[0] & 0xFF) << 8) | (framedMessage[1] & 0xFF);
        int actualLength = framedMessage.length - 2; // Exclude length bytes

        if (declaredLength != actualLength) {
            throw new FramingException(String.format(
                "Length mismatch: declared %d, actual %d", declaredLength, actualLength));
        }

        // Check STX
        if (framedMessage[2] != HyosungProtocol.STX) {
            throw new FramingException("Missing STX after length header");
        }

        // Check ETX
        if (framedMessage[framedMessage.length - 2] != HyosungProtocol.ETX) {
            throw new FramingException("Missing ETX before LRC");
        }

        // Verify LRC (from after STX to ETX inclusive)
        byte receivedLrc = framedMessage[framedMessage.length - 1];
        byte calculatedLrc = LrcCalculator.calculate(framedMessage, 3, framedMessage.length - 4);
        if (receivedLrc != calculatedLrc) {
            throw new FramingException(String.format(
                "LRC mismatch: received 0x%02X, calculated 0x%02X", receivedLrc, calculatedLrc));
        }

        // Extract content (between STX and ETX)
        int contentLength = framedMessage.length - 5; // Total - 2 length bytes - STX - ETX - LRC
        byte[] content = new byte[contentLength];
        System.arraycopy(framedMessage, 3, content, 0, contentLength);

        return content;
    }

    /**
     * Unframes a VISA format message WITHOUT STX/ETX/LRC.
     * Used by Switch Commerce style processors.
     * Only verifies length header.
     *
     * Format: [2-byte length][content]
     *
     * @param framedMessage The framed message bytes
     * @return The content bytes (without length header)
     * @throws FramingException if the message format is invalid
     */
    public static byte[] unframeVisaNoStxEtx(byte[] framedMessage) throws FramingException {
        if (framedMessage == null || framedMessage.length < 3) {
            throw new FramingException("Message too short for VISA (no STX/ETX) framing");
        }

        // Extract and verify length header (big-endian)
        int declaredLength = ((framedMessage[0] & 0xFF) << 8) | (framedMessage[1] & 0xFF);
        int actualLength = framedMessage.length - 2; // Exclude length bytes

        if (declaredLength != actualLength) {
            throw new FramingException(String.format(
                "Length mismatch: declared %d, actual %d", declaredLength, actualLength));
        }

        // Extract content (everything after length header)
        byte[] content = new byte[actualLength];
        System.arraycopy(framedMessage, 2, content, 0, actualLength);

        return content;
    }

    /**
     * Unframes a message using the specified framing type.
     *
     * @param framedMessage The framed message bytes
     * @param framingType The framing type expected
     * @return The content bytes
     * @throws FramingException if the message format is invalid
     */
    public static byte[] unframe(byte[] framedMessage, HyosungProtocol.FramingType framingType)
            throws FramingException {
        switch (framingType) {
            case VISA_LENGTH_PREFIX:
                return unframeVisa(framedMessage);
            case VISA_NO_STX_ETX:
                return unframeVisaNoStxEtx(framedMessage);
            case STANDARD:
            default:
                return unframeStandard(framedMessage);
        }
    }

    /**
     * Auto-detects framing type and unframes the message.
     * VISA framing starts with 2-byte length, Standard starts with STX.
     *
     * @param framedMessage The framed message bytes
     * @return The content bytes
     * @throws FramingException if the message format is invalid
     */
    public static byte[] unframeAuto(byte[] framedMessage) throws FramingException {
        if (framedMessage == null || framedMessage.length < 4) {
            throw new FramingException("Message too short");
        }

        // If first byte is STX, it's Standard framing
        if (framedMessage[0] == HyosungProtocol.STX) {
            return unframeStandard(framedMessage);
        }

        // Otherwise assume VISA framing
        return unframeVisa(framedMessage);
    }

    /**
     * Detects the framing type of a message.
     *
     * @param framedMessage The framed message bytes
     * @return The detected framing type
     */
    public static HyosungProtocol.FramingType detectFramingType(byte[] framedMessage) {
        if (framedMessage != null && framedMessage.length > 0
                && framedMessage[0] == HyosungProtocol.STX) {
            return HyosungProtocol.FramingType.STANDARD;
        }
        return HyosungProtocol.FramingType.VISA_LENGTH_PREFIX;
    }

    /**
     * Reads the expected message length from VISA framing header.
     * Useful for reading from streams where you need to know how many bytes to read.
     *
     * @param lengthHeader The 2-byte length header
     * @return The declared message length (excludes the 2 length bytes)
     */
    public static int getVisaMessageLength(byte[] lengthHeader) {
        if (lengthHeader == null || lengthHeader.length < 2) {
            return -1;
        }
        return ((lengthHeader[0] & 0xFF) << 8) | (lengthHeader[1] & 0xFF);
    }

    /**
     * Joins fields with FS separator.
     *
     * @param fields The fields to join
     * @return Byte array with fields joined by FS
     */
    public static byte[] joinFields(String... fields) {
        if (fields == null || fields.length == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                baos.write(HyosungProtocol.FS);
            }
            if (fields[i] != null) {
                byte[] fieldBytes = fields[i].getBytes();
                baos.write(fieldBytes, 0, fieldBytes.length);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Splits content by FS separator.
     *
     * @param content The message content bytes
     * @return Array of field values as strings
     */
    public static String[] splitFields(byte[] content) {
        if (content == null || content.length == 0) {
            return new String[0];
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        java.util.List<String> fields = new java.util.ArrayList<>();

        for (byte b : content) {
            if (b == HyosungProtocol.FS) {
                fields.add(baos.toString());
                baos.reset();
            } else {
                baos.write(b);
            }
        }
        // Add last field
        fields.add(baos.toString());

        return fields.toArray(new String[0]);
    }

    /**
     * Exception thrown when message framing is invalid.
     */
    public static class FramingException extends Exception {
        public FramingException(String message) {
            super(message);
        }

        public FramingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
