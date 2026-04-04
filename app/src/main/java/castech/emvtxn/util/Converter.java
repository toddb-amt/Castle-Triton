package castech.emvtxn.util;

public class Converter {
    public static byte[] hexString2ByteArray(String hexString) {
        // Null safety check
        if (hexString == null || hexString.isEmpty()) {
            return new byte[0];
        }
        byte[] byteArray = new byte[hexString.length() / 2];
        for (int i = 0; i < byteArray.length; i++) {
            byteArray[i] = (byte) Integer.parseInt(hexString.substring(2 * i, 2 * i + 2), 16);
        }

        return byteArray;
    }

    public static String byteArray2HexString(byte[] array, int len) {
        StringBuffer hexString = new StringBuffer();

        for (int i = 0; i < len; i++) {
            int intVal = array[i] & 0xFF;

            if (intVal < 0x10) {
                hexString.append("0");
            }

            hexString.append(Integer.toHexString(intVal).toUpperCase());
        }
        return hexString.toString();
    }

    public static String byteArray2HexString(byte[] array, int index, int len) {
        StringBuffer hexString = new StringBuffer();

        for (int i = index; i < len + index; i++) {
            int intVal = array[i] & 0xFF;

            if (intVal < 0x10) {
                hexString.append("0");
            }

            hexString.append(Integer.toHexString(intVal));
        }
        return hexString.toString();
    }

    public static String asciiBytesToString(byte[] bytes) {
        if ((bytes == null) || (bytes.length == 0)) {
            return "";
        }

        char[] result = new char[bytes.length];

        for (int i = 0; i < bytes.length; i++) {
            result[i] = (char) bytes[i];
        }

        return new String(result);
    }

    public static String amtPadding(String strAmt) {
        // Null safety check
        if (strAmt == null || strAmt.isEmpty()) {
            return "000000000000";
        }
        switch (strAmt.length()) {
            case 0:
                return "000000000000";
            case 1:
                return "00000000000" + strAmt;
            case 2:
                return "0000000000" + strAmt;
            case 3:
                return "000000000" + strAmt;
            case 4:
                return "00000000" + strAmt;
            case 5:
                return "0000000" + strAmt;
            case 6:
                return "000000" + strAmt;
            case 7:
                return "00000" + strAmt;
            case 8:
                return "0000" + strAmt;
            case 9:
                return "000" + strAmt;
            case 10:
                return "00" + strAmt;
            case 11:
                return "0" + strAmt;

            default:
                return strAmt;
        }

    }
}
