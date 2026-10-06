package castech.emvtxn.atm.host;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * EMV Tag Enhancer
 *
 * Rebuilds EMV data in the exact order that the working terminal GH001038 uses.
 * This ensures processor compatibility by matching the expected tag order.
 *
 * Working terminal GH001038 tag order:
 * 9F02, 9F26, 95, 9F27, 9C, 9A, 5F24, 9F36, 82, 9F37, 9F10, 9F33, 9F34,
 * 4F, 5F2A, 5F34, 84, 9B, 9F03, 9F06, 9F09, 9F1A, 9F21, 9F35, 9F39,
 * 9F40, 9F41, 9F07, 9F1E, 50
 */
public class EmvTagEnhancer {

    private static final String TAG = "EmvTagEnhancer";

    // Terminal configuration (Castle S1F4 PRO)
    // Values matched to working terminal GH001038
    private static final String CURRENCY_CODE = "0840";      // USD
    private static final String COUNTRY_CODE = "0840";       // US
    private static final String TERMINAL_CAPABILITIES = "E040C8";  // Matches GH001038
    private static final String TERMINAL_TYPE = "22";        // Attended, online only
    private static final String ADDITIONAL_CAPABILITIES = "7000F0F001";  // Matches GH001038
    private static final String IFD_SERIAL_NUMBER = "S1F4PRO1";  // Terminal serial (8 chars)

    // ATM MODE: CVM Results override
    // With Online PIN approach (9F33=E040C8), kernel collects PIN via software encryption
    // 9F34 is still overridden to ensure consistency with processor expectations
    // The actual PIN is encrypted with software working key in eventOnlinePinBlockGet
    private static final String ATM_CVM_RESULTS = "420000";  // Online PIN verified
    private static final String ATM_TERMINAL_CAPS = "E040C8"; // Online PIN only (matches GH001038)

    // NOTE: TVR (95), Transaction Type (9C), and IAD (9F10) are ARQC inputs
    // and must NOT be overridden after the card generates the cryptogram.
    // However, 9F34 (CVM Results) is NOT part of ARQC calculation - it can be overridden.

    // Tag order from working terminal GH001038 (APPROVED transactions)
    private static final String[] TAG_ORDER = {
        "9F02",  // Amount Authorized
        "9F26",  // Application Cryptogram
        "95",    // TVR
        "9F27",  // CID
        "9C",    // Transaction Type
        "9A",    // Transaction Date
        "5F24",  // Expiration Date
        "9F36",  // ATC
        "82",    // AIP
        "9F37",  // Unpredictable Number
        "9F10",  // IAD
        "9F33",  // Terminal Capabilities
        "9F34",  // CVM Results
        "4F",    // AID
        "5F2A",  // Currency Code
        "5F34",  // PAN Sequence Number
        "84",    // DF Name
        "9B",    // TSI
        "9F03",  // Amount Other
        "9F06",  // AID Terminal
        "9F09",  // App Version Number
        "9F1A",  // Terminal Country Code
        "9F21",  // Transaction Time
        "9F35",  // Terminal Type
        "9F39",  // POS Entry Mode
        "9F40",  // Additional Terminal Capabilities
        "9F41",  // Transaction Sequence Counter
        "9F07",  // AUC
        "9F1E",  // IFD Serial Number
        "50"     // Application Label
    };

    // Transaction type codes (ISO 8583)
    public static final byte TXN_TYPE_PURCHASE = 0x00;
    public static final byte TXN_TYPE_CASH = 0x01;
    public static final byte TXN_TYPE_BALANCE_SAVINGS = 0x31;
    public static final byte TXN_TYPE_BALANCE_CHECKING = 0x30;

    // POS entry mode codes
    public static final String POS_ENTRY_CHIP = "05";
    public static final String POS_ENTRY_CONTACTLESS = "07";
    public static final String POS_ENTRY_MAG_STRIPE = "02";

    private int sequenceCounter = 1;

    /**
     * Enhances EMV data by parsing all tags, applying overrides, and rebuilding
     * in the exact order that working terminal GH001038 uses.
     *
     * @param existingEmvData Existing EMV data from card read (hex string)
     * @param transactionType Transaction type code (TXN_TYPE_*)
     * @param posEntryMode POS entry mode (POS_ENTRY_*)
     * @param isCashWithdrawal True for cash withdrawal, false for balance inquiry
     * @return Enhanced EMV data with tags in correct order
     */
    public String enhanceEmvData(String existingEmvData, byte transactionType,
            String posEntryMode, boolean isCashWithdrawal) {
        // Default: non-ATM mode (no 9F34 override)
        return enhanceEmvData(existingEmvData, transactionType, posEntryMode, isCashWithdrawal, false);
    }

    /**
     * Enhances EMV data with ATM mode option.
     *
     * @param existingEmvData Existing EMV data from card read (hex string)
     * @param transactionType Transaction type code (TXN_TYPE_*)
     * @param posEntryMode POS entry mode (POS_ENTRY_*)
     * @param isCashWithdrawal True for cash withdrawal, false for balance inquiry
     * @param isAtmMode True to override 9F34 to 420000 (Online PIN) for ATM transactions
     * @return Enhanced EMV data with tags in correct order
     */
    public String enhanceEmvData(String existingEmvData, byte transactionType,
            String posEntryMode, boolean isCashWithdrawal, boolean isAtmMode) {

        // Step 1: Parse all existing tags into a map
        Map<String, String> tagMap = parseEmvTags(existingEmvData);
        Log.d(TAG, "Parsed " + tagMap.size() + " tags from EMV data");
        Log.d(TAG, "ATM Mode: " + isAtmMode);

        // Step 2: Apply overrides
        //
        // ARQC CALCULATION INPUTS (preserved as-is):
        //   - 95 (TVR) - Terminal Verification Results - INCLUDED in ARQC
        //   - 9C (Transaction Type) - Set by terminal before GENERATE AC - INCLUDED in ARQC
        //   - 9F10 (IAD) - Issuer Application Data - OUTPUT from card, never modify!
        //
        // TERMINAL METADATA (safe to override after ARQC):
        //   - 9F34 (CVM Results) - NOT part of ARQC calculation per EMV Book 2
        //   - 9F33 (Terminal Capabilities) - Already sent to card, but can adjust for reporting
        //
        // For ATM transactions with No-CVM approach:
        //   - EMV kernel uses 9F33=E028C8 (no online PIN) so GENERATE AC completes
        //   - We override 9F34 to 420000 (Online PIN verified) for processor
        //   - We override 9F33 to E068C8 (includes Online PIN) for processor reporting
        //   - PIN is collected separately and encrypted with software working key

        // Log existing tags
        String existingTvr = tagMap.get("95");
        String existingCvm = tagMap.get("9F34");
        String existingTxnType = tagMap.get("9C");
        String existingIad = tagMap.get("9F10");

        Log.d(TAG, "Tags from EMV kernel:");
        Log.d(TAG, "  95 (TVR) = " + (existingTvr != null ? existingTvr : "not present"));
        Log.d(TAG, "  9F34 (CVM) = " + (existingCvm != null ? existingCvm : "not present"));
        Log.d(TAG, "  9C (TxnType) = " + (existingTxnType != null ? existingTxnType : "not present"));
        Log.d(TAG, "  9F10 (IAD) = " + (existingIad != null ? existingIad : "not present"));

        // ATM MODE: SDK PIN flow is now working correctly (Jan 2026)
        // eventOnlinePinBlockGet callback is registered and called, so SDK generates correct:
        //   - 9F34 (CVM Results) = 420000 (Online PIN verified)
        //   - TVR byte 3 = 0x04 (Online PIN entered)
        // NO OVERRIDES NEEDED for card-generated tags - but must ADD terminal config tags if missing
        if (isAtmMode) {
            Log.d(TAG, "=== ATM MODE: Using REAL SDK values (no overrides) ===");

            // Log actual values from SDK for verification
            String actualCvm = tagMap.get("9F34");
            Log.d(TAG, "  9F34 (CVM Results): " + (actualCvm != null ? actualCvm : "not present") + " (real SDK value)");

            String actualTvr = tagMap.get("95");
            if (actualTvr != null) {
                Log.d(TAG, "  95 (TVR): " + actualTvr + " (real SDK value)");
                if (actualTvr.length() >= 6) {
                    String byte3 = actualTvr.substring(4, 6);
                    Log.d(TAG, "  TVR byte 3: " + byte3 + " (0x04=Online PIN entered, 0x80/0x90=CVM failed)");
                }
            }

            // 9F33 is terminal config - SDK doesn't include it in EMV data, must add if missing
            if (!tagMap.containsKey("9F33")) {
                tagMap.put("9F33", ATM_TERMINAL_CAPS);  // E040C8 = Online PIN capability
                Log.d(TAG, "  9F33 (TermCaps): ADDED " + ATM_TERMINAL_CAPS + " (terminal config, not from SDK)");
            } else {
                Log.d(TAG, "  9F33 (TermCaps): " + tagMap.get("9F33") + " (from SDK)");
            }

            Log.d(TAG, "=== ATM MODE: Real SDK values will be sent ===");
        } else {
            // Non-ATM: Only override 9F33 if it's missing
            if (!tagMap.containsKey("9F33")) {
                tagMap.put("9F33", TERMINAL_CAPABILITIES);
                Log.d(TAG, "Added missing 9F33 (Term Caps) = " + TERMINAL_CAPABILITIES);
            } else {
                Log.d(TAG, "Preserved 9F33 (Term Caps) = " + tagMap.get("9F33"));
            }
        }

        // Step 3: Add missing terminal configuration tags

        // 5F2A - Currency Code
        if (!tagMap.containsKey("5F2A")) {
            tagMap.put("5F2A", CURRENCY_CODE);
            Log.d(TAG, "Added 5F2A (Currency) = " + CURRENCY_CODE);
        }

        // 9F1A - Terminal Country Code
        if (!tagMap.containsKey("9F1A")) {
            tagMap.put("9F1A", COUNTRY_CODE);
            Log.d(TAG, "Added 9F1A (Country) = " + COUNTRY_CODE);
        }

        // 9F21 - Transaction Time
        if (!tagMap.containsKey("9F21")) {
            String time = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
            tagMap.put("9F21", time);
            Log.d(TAG, "Added 9F21 (Time) = " + time);
        }

        // 9F35 - Terminal Type
        if (!tagMap.containsKey("9F35")) {
            tagMap.put("9F35", TERMINAL_TYPE);
            Log.d(TAG, "Added 9F35 (Term Type) = " + TERMINAL_TYPE);
        }

        // 9F39 - POS Entry Mode
        if (!tagMap.containsKey("9F39")) {
            tagMap.put("9F39", posEntryMode);
            Log.d(TAG, "Added 9F39 (POS Entry) = " + posEntryMode);
        }

        // 9F40 - Additional Terminal Capabilities
        if (!tagMap.containsKey("9F40")) {
            tagMap.put("9F40", ADDITIONAL_CAPABILITIES);
            Log.d(TAG, "Added 9F40 (Add Term Caps) = " + ADDITIONAL_CAPABILITIES);
        }

        // 9F41 - Transaction Sequence Counter
        if (!tagMap.containsKey("9F41")) {
            String seqHex = String.format("%08X", getNextSequenceCounter());
            tagMap.put("9F41", seqHex);
            Log.d(TAG, "Added 9F41 (Seq Counter) = " + seqHex);
        }

        // 9F1E - IFD Serial Number
        if (!tagMap.containsKey("9F1E")) {
            String serialHex = asciiToHex(padRight(IFD_SERIAL_NUMBER, 8));
            tagMap.put("9F1E", serialHex);
            Log.d(TAG, "Added 9F1E (IFD Serial) = " + IFD_SERIAL_NUMBER);
        }

        // Step 4: Rebuild EMV data in the exact order of GH001038
        // DIAGNOSTIC: Dump all tags being sent for debugging "12" response
        Log.d(TAG, "=== EMV TAGS BEING SENT (for debug) ===");
        for (String tag : TAG_ORDER) {
            String value = tagMap.get(tag);
            if (value != null && !value.isEmpty()) {
                Log.d(TAG, "  " + tag + " = " + value);
            }
        }
        Log.d(TAG, "=== END EMV TAGS ===");

        StringBuilder result = new StringBuilder();
        int tagsIncluded = 0;

        for (String tag : TAG_ORDER) {
            String value = tagMap.get(tag);
            if (value != null && !value.isEmpty()) {
                result.append(buildTag(tag, value));
                tagsIncluded++;
            }
        }

        // Include any tags not in the standard order (append at end)
        for (Map.Entry<String, String> entry : tagMap.entrySet()) {
            boolean inOrder = false;
            for (String orderedTag : TAG_ORDER) {
                if (orderedTag.equals(entry.getKey())) {
                    inOrder = true;
                    break;
                }
            }
            if (!inOrder && entry.getValue() != null && !entry.getValue().isEmpty()) {
                result.append(buildTag(entry.getKey(), entry.getValue()));
                tagsIncluded++;
                Log.d(TAG, "Appended extra tag " + entry.getKey());
            }
        }

        Log.d(TAG, "Rebuilt EMV data: " + tagsIncluded + " tags, " + result.length() + " chars");
        return result.toString();
    }

    /**
     * Parses EMV TLV data into a map of tag -> value (hex strings).
     */
    private Map<String, String> parseEmvTags(String emvData) {
        Map<String, String> tagMap = new HashMap<>();

        if (emvData == null || emvData.isEmpty()) {
            return tagMap;
        }

        String upperData = emvData.toUpperCase();
        int pos = 0;

        try {
            while (pos < upperData.length()) {
                // Parse tag (1 or 2 bytes)
                if (pos + 2 > upperData.length()) break;

                String tagByte1 = upperData.substring(pos, pos + 2);
                int tagLen = 2; // 1 byte = 2 hex chars

                // Check if this is a two-byte tag (first byte ends in 1F)
                int firstByte = Integer.parseInt(tagByte1, 16);
                if ((firstByte & 0x1F) == 0x1F) {
                    // Two-byte tag
                    tagLen = 4;
                    if (pos + 4 > upperData.length()) break;
                }

                String currentTag = upperData.substring(pos, pos + tagLen);
                int lengthPos = pos + tagLen;

                // Parse length
                if (lengthPos + 2 > upperData.length()) break;
                int lengthByte = Integer.parseInt(upperData.substring(lengthPos, lengthPos + 2), 16);
                int lengthSize = 2; // 1 byte = 2 hex chars
                int valueLen;

                if (lengthByte <= 0x7F) {
                    // Short form: length is the byte itself
                    valueLen = lengthByte;
                } else if (lengthByte == 0x81) {
                    // Long form: next byte is the length
                    lengthSize = 4;
                    if (lengthPos + 4 > upperData.length()) break;
                    valueLen = Integer.parseInt(upperData.substring(lengthPos + 2, lengthPos + 4), 16);
                } else if (lengthByte == 0x82) {
                    // Long form: next 2 bytes are the length
                    lengthSize = 6;
                    if (lengthPos + 6 > upperData.length()) break;
                    valueLen = Integer.parseInt(upperData.substring(lengthPos + 2, lengthPos + 6), 16);
                } else {
                    // Unsupported length format, skip this byte
                    pos += 2;
                    continue;
                }

                int valuePos = lengthPos + lengthSize;
                int valueEndPos = valuePos + (valueLen * 2);

                if (valueEndPos > upperData.length()) {
                    // Malformed data, stop parsing
                    break;
                }

                // Extract value and store in map
                String value = upperData.substring(valuePos, valueEndPos);
                tagMap.put(currentTag, value);

                pos = valueEndPos;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Error parsing EMV tags: " + e.getMessage());
        }

        return tagMap;
    }

    /**
     * Builds a TLV tag string.
     *
     * @param tag Tag identifier (e.g., "9F33")
     * @param valueHex Value in hex (e.g., "E040C8")
     * @return TLV string (tag + length + value)
     */
    private String buildTag(String tag, String valueHex) {
        int length = valueHex.length() / 2;
        return tag + String.format("%02X", length) + valueHex;
    }

    /**
     * Converts ASCII string to hex.
     */
    private String asciiToHex(String ascii) {
        StringBuilder hex = new StringBuilder();
        for (char c : ascii.toCharArray()) {
            hex.append(String.format("%02X", (int) c));
        }
        return hex.toString();
    }

    /**
     * Pads string to specified length with spaces.
     */
    private String padRight(String s, int length) {
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Gets and increments the sequence counter.
     */
    private synchronized int getNextSequenceCounter() {
        int seq = sequenceCounter++;
        if (sequenceCounter > 9999) {
            sequenceCounter = 1;
        }
        return seq;
    }

    /**
     * Gets the appropriate transaction type for an ATM transaction.
     *
     * @param isBalanceInquiry True for balance inquiry
     * @param accountType Account type ("CA", "SA", "CR")
     * @return Transaction type byte
     */
    public static byte getTransactionType(boolean isBalanceInquiry, String accountType) {
        if (isBalanceInquiry) {
            // Balance inquiry - use account-specific code
            if ("SA".equals(accountType)) {
                return TXN_TYPE_BALANCE_SAVINGS;  // 0x31
            } else {
                return TXN_TYPE_BALANCE_CHECKING; // 0x30
            }
        } else {
            // Cash withdrawal
            return TXN_TYPE_CASH; // 0x01
        }
    }

    /**
     * Builds terminal status monitoring data for Field 12.
     * Format matches Hyosung STD1 specification - matching GH001038 exactly.
     *
     * GH001038 format:
     * sXV1.05.00I20015001 30   30001100000000150011871200001300002K1200120000000000000000000000000000000000000000000000XX XX XX 5505.01.028000000000
     *
     * @return Status monitoring string with 's' prefix
     */
    public static String buildStatusMonitoring() {
        StringBuilder sb = new StringBuilder();

        // NOTE: deliberately does NOT poll the printer here. Status monitoring is
        // built on the transaction thread; the paper check lives in the pre-transaction
        // guard instead (see AtmHostService), keeping hardware I/O off this path.

        // Status prefix + Platform (X = Android/Hyosung)
        sb.append("sX");

        // Program Version (V + major.minor.patch)
        sb.append("V1.05.00");

        // Current Status (I = In Service)
        sb.append("I");

        // Error Code (7 digits matching GH001038)
        sb.append("2001500");

        // Alarm flags (2 chars: '1' + space = standard)
        sb.append("1 ");

        // Card Reader (type=3, status=0) - matching GH001038
        sb.append("30");

        // Cards Retained (3 spaces)
        sb.append("   ");

        // PIN Pad (type=3, status=0) - matching GH001038
        sb.append("30");

        // Receipt Printer (Type=0, Status=0, Paper=1=OK)
        //
        // DO NOT report a printer fault here. Sending Status=1/Paper=0 ("010")
        // caused Switch Commerce to reject the message and close the connection
        // ~9ms after TX, which orphaned the transaction and triggered a reversal
        // storm (2026-08-09). Out-of-paper is handled by BLOCKING the transaction
        // before it starts (AtmHostService.performWithdrawal / performBalanceInquiry),
        // so the terminal never transacts while out of paper.
        sb.append("001");

        // Journal (Type=1, Status=0, Paper=0, Count=0000)
        sb.append("1000000");

        // Dispenser type=0, status=0, noteStatus=1 (has notes)
        sb.append("001");

        // Cassette format: Denom(3) + Count(4) + Loaded(4) + Dispensed(4) + Rejects(3) = 18 chars each
        // Cassette A: Denom=500 ($5), Count=1187, Loaded=1200, Dispensed=0013, Rejects=000
        sb.append("500118712000013000");  // 18 chars

        // Cassette B: Denom=02K ($20), Count=1200, Loaded=1200, Dispensed=0000, Rejects=000
        sb.append("02K120012000000000");  // 18 chars

        // Cassette C: All zeros (empty)
        sb.append("000000000000000000");  // 18 chars

        // Cassette D: All zeros (empty)
        sb.append("000000000000000000");  // 18 chars

        // Depository A/B/C (XX XX XX = not present)
        sb.append("XX XX XX ");

        // Enhanced status info (matching GH001038)
        sb.append("5505.01.028000000000");

        // Communications type + Reserved (space + 19 spaces)
        sb.append("                    ");

        return sb.toString();
    }
}
