package castech.emvtxn;

import android.util.Log;
import castech.emvtxn.util.Converter;

public class Debugger {
    public static void addSTR(String tag, String title) {
        Log.d(tag, title);
    }

    public static void addINT(String tag, String title, int len) {
        Log.d(tag, title + String.format("= %d, (0x%02X)", len, len));
    }

    public static void addHex(String tag, String title, byte[] array, int len) {
        Log.d(tag, title + Converter.byteArray2HexString(array, len).toUpperCase());
    }

    public static void addHEX(String tag, String title, byte[] brray, int index, int len) {
        Log.d(tag, title + Converter.byteArray2HexString(brray, index, len).toUpperCase());
    }
}
