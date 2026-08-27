package castech.emvtxn;

import android.util.Log;

import java.nio.charset.StandardCharsets;

import CTOS.CtKMS2UserData;

/**
 * Persists a snapshot of the ATM configuration into Castle KMS-II user-data
 * storage ({@link CTOS.CtKMS2UserData}), which SURVIVES an app reinstall — unlike
 * SharedPreferences.
 *
 * Why this exists: when the app is deployed via CasHUB, the agent re-clean-installs
 * it on every boot (verified 2026-08-14 — firstInstallTime resets each reboot),
 * wiping /data/data (SharedPreferences) and therefore all host/TID/fee config.
 * KMS-II storage is the same secure store that held the PIN keys through every one
 * of those reinstalls, so config written here comes back after each reinstall.
 *
 * Uses the COMMON area (isCommon=true): 64K, NOT tied to the app's install
 * identity, so it is guaranteed to persist across reinstalls. The config stored
 * here (host address, terminal id, processor, fees) is not secret, so the common
 * area is acceptable; the PIN keys live in the separate key slots, never here.
 *
 * Layout at common-area offset 0:
 *   [4 bytes magic "CFG1"][4 bytes big-endian length N][N bytes UTF-8 payload]
 * Payload is newline-delimited key=value lines.
 */
public final class KmsConfigStore {

    private static final String TAG = "KmsConfigStore";

    private static final boolean COMMON = true;    // common area survives reinstall
    private static final int OFFSET = 0;           // start of the common area
    private static final byte[] MAGIC = {'C', 'F', 'G', '1'};
    private static final int HEADER_LEN = 8;       // magic(4) + length(4)
    private static final int MAX_PAYLOAD = 2000;   // KMS write limit is 2048

    private KmsConfigStore() {}

    /**
     * Serializes the current GlobalPara ATM config and writes it to KMS-II.
     * Call after any settings save. Never throws.
     *
     * @return true on success
     */
    public static boolean backup() {
        try {
            String payload =
                    "host_address="   + nz(GlobalPara.atmHostAddress)   + "\n" +
                    "host_port="      + GlobalPara.atmHostPort           + "\n" +
                    "terminal_id="    + nz(GlobalPara.atmTerminalId)     + "\n" +
                    "processor_type=" + nz(GlobalPara.atmProcessorType)  + "\n" +
                    "protocol_type="  + nz(GlobalPara.atmProtocolType)   + "\n" +
                    "use_flat_fee="   + GlobalPara.atmUseFlatFee         + "\n" +
                    "flat_fee="       + GlobalPara.atmFlatFeeAmount      + "\n" +
                    "percentage_fee=" + GlobalPara.atmPercentageFee      + "\n" +
                    "min_amount="     + GlobalPara.atmMinAmount          + "\n" +
                    "max_amount="     + GlobalPara.atmMaxAmount          + "\n";

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_PAYLOAD) {
                Log.e(TAG, "Config payload too large (" + body.length + " bytes) — not backed up");
                return false;
            }

            byte[] buf = new byte[HEADER_LEN + body.length];
            System.arraycopy(MAGIC, 0, buf, 0, 4);
            buf[4] = (byte) (body.length >>> 24);
            buf[5] = (byte) (body.length >>> 16);
            buf[6] = (byte) (body.length >>> 8);
            buf[7] = (byte) (body.length);
            System.arraycopy(body, 0, buf, HEADER_LEN, body.length);

            CtKMS2UserData ud = new CtKMS2UserData();
            ud.write(COMMON, OFFSET, buf);
            Log.w(TAG, "Config backed up to KMS-II (" + body.length + " bytes): host="
                    + GlobalPara.atmHostAddress + " tid=" + GlobalPara.atmTerminalId);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "KMS-II config backup failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Reads the KMS-II config backup (if any) into GlobalPara. Call at startup
     * when SharedPreferences is empty (fresh install / reinstall). Never throws.
     *
     * @return true if a valid backup was found and applied
     */
    public static boolean restore() {
        try {
            CtKMS2UserData ud = new CtKMS2UserData();

            byte[] header = ud.read(COMMON, OFFSET, HEADER_LEN);
            if (header == null || header.length < HEADER_LEN
                    || header[0] != MAGIC[0] || header[1] != MAGIC[1]
                    || header[2] != MAGIC[2] || header[3] != MAGIC[3]) {
                Log.d(TAG, "No KMS-II config backup present (magic mismatch/empty)");
                return false;
            }

            int len = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                    | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            if (len <= 0 || len > MAX_PAYLOAD) {
                Log.w(TAG, "KMS-II config length invalid: " + len);
                return false;
            }

            byte[] body = ud.read(COMMON, OFFSET + HEADER_LEN, len);
            if (body == null || body.length < len) {
                Log.w(TAG, "KMS-II config short read");
                return false;
            }

            applyPayload(new String(body, 0, len, StandardCharsets.UTF_8));
            Log.w(TAG, "Config restored from KMS-II: host=" + GlobalPara.atmHostAddress
                    + " tid=" + GlobalPara.atmTerminalId + " processor=" + GlobalPara.atmProcessorType);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "KMS-II config restore failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Applies a newline-delimited {@code key=value} payload to the GlobalPara ATM
     * config. Package-private so CasHubParams can feed it the CasHUB-pushed config
     * (converted to the same key=value form) and reuse this single mapping. Unknown
     * keys and malformed lines are skipped.
     */
    static void applyPayload(String payload) {
        for (String line : payload.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            try {
                switch (k) {
                    case "host_address":   GlobalPara.atmHostAddress = v; break;
                    case "host_port":      GlobalPara.atmHostPort = Integer.parseInt(v.trim()); break;
                    case "terminal_id":    GlobalPara.atmTerminalId = v; break;
                    case "processor_type": GlobalPara.atmProcessorType = v; break;
                    case "protocol_type":  GlobalPara.atmProtocolType = v; break;
                    case "use_flat_fee":   GlobalPara.atmUseFlatFee = Boolean.parseBoolean(v.trim()); break;
                    case "flat_fee":       GlobalPara.atmFlatFeeAmount = Double.parseDouble(v.trim()); break;
                    case "percentage_fee": GlobalPara.atmPercentageFee = Double.parseDouble(v.trim()); break;
                    case "min_amount":     GlobalPara.atmMinAmount = Double.parseDouble(v.trim()); break;
                    case "max_amount":     GlobalPara.atmMaxAmount = Double.parseDouble(v.trim()); break;
                    default: break;
                }
            } catch (Throwable ignore) {
                // Skip any malformed line rather than fail the whole restore
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
