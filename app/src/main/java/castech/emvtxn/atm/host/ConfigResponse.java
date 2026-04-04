package castech.emvtxn.atm.host;

/**
 * Configuration Response (Type 88)
 *
 * Response containing working keys and/or configuration data.
 *
 * Field Layout:
 *   Field 0:  Information Header (H0.NNNNNN)
 *   Field 1:  Terminal ID
 *   Field 2:  Transaction Code (88)
 *   Field 3:  (Reserved - empty)
 *   Field 4:  (Reserved - empty)
 *   Field 5:  Working Key Data (Key Part 1 or TR-31 block)
 *   Field 6:  Surcharge (cents)
 *   Field 7:  (Reserved - empty)
 *   Field 8:  Working Key Part 2 (if not TR-31)
 */
public class ConfigResponse {

    private String infoHeader;          // Field 0
    private String terminalId;          // Field 1
    private String workingKeyPart1;     // Field 5: Key Part A or TR-31 block
    private long surchargeCents;        // Field 6
    private String workingKeyPart2;     // Field 8: Key Part B (if standard format)

    // Derived fields
    private boolean isTr31Format;
    private String combinedWorkingKey;  // For standard format: Part1 + Part2

    /**
     * Creates a new empty ConfigResponse.
     */
    public ConfigResponse() {
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    public String getInfoHeader() {
        return infoHeader;
    }

    public void setInfoHeader(String infoHeader) {
        this.infoHeader = infoHeader;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    public String getTransactionCode() {
        return HyosungProtocol.MSG_TYPE_CONFIG;
    }

    public String getWorkingKeyPart1() {
        return workingKeyPart1;
    }

    public void setWorkingKeyPart1(String workingKeyPart1) {
        this.workingKeyPart1 = workingKeyPart1;
        detectKeyFormat();
    }

    public long getSurchargeCents() {
        return surchargeCents;
    }

    public void setSurchargeCents(long surchargeCents) {
        this.surchargeCents = surchargeCents;
    }

    /**
     * Gets surcharge in dollars.
     */
    public double getSurchargeDollars() {
        return surchargeCents / 100.0;
    }

    public String getWorkingKeyPart2() {
        return workingKeyPart2;
    }

    public void setWorkingKeyPart2(String workingKeyPart2) {
        this.workingKeyPart2 = workingKeyPart2;
        detectKeyFormat();
    }

    // =========================================================================
    // Key Format Methods
    // =========================================================================

    /**
     * Checks if the key is in TR-31 format.
     */
    public boolean isTr31Format() {
        return isTr31Format;
    }

    /**
     * Detects whether the key is TR-31 or standard format.
     *
     * TR-31 detection:
     * - Field 5 contains full TR-31 key block (starts with A, B, C, or D)
     * - Field 8 is empty
     *
     * Standard format options:
     * - Field 5 contains 16 hex chars (Key Part A), Field 8 contains 16 hex chars (Key Part B)
     * - Field 5 contains "PART1|PART2" pipe-separated format (33 chars)
     * - Field 5 contains full 32-char combined key
     */
    private void detectKeyFormat() {
        if (workingKeyPart1 == null || workingKeyPart1.isEmpty()) {
            isTr31Format = false;
            return;
        }

        // TR-31 blocks start with version identifier (A, B, C, or D)
        char firstChar = workingKeyPart1.charAt(0);
        if ((firstChar == 'A' || firstChar == 'B' || firstChar == 'C' || firstChar == 'D')
                && workingKeyPart1.length() > 20) {
            isTr31Format = true;
            return;
        }

        isTr31Format = false;

        // Check for pipe-separated format: "XXXXXXXXXXXXXXXX|YYYYYYYYYYYYYYYY"
        if (workingKeyPart1.contains("|")) {
            String[] parts = workingKeyPart1.split("\\|");
            if (parts.length == 2 && parts[0].length() == 16 && parts[1].length() == 16) {
                combinedWorkingKey = parts[0] + parts[1];
                return;
            }
        }

        // Check for full 32-char combined key in field 5
        if (workingKeyPart1.length() == 32) {
            combinedWorkingKey = workingKeyPart1;
            return;
        }

        // Standard two-field format
        if (workingKeyPart1.length() == 16 && workingKeyPart2 != null
                && workingKeyPart2.length() == 16) {
            combinedWorkingKey = workingKeyPart1 + workingKeyPart2;
        }
    }

    /**
     * Gets the combined working key (for standard format).
     * Returns null for TR-31 format.
     *
     * @return 32 hex character double-length 3DES key, or null
     */
    public String getCombinedWorkingKey() {
        if (isTr31Format) {
            return null;
        }
        return combinedWorkingKey;
    }

    /**
     * Gets the TR-31 key block (if TR-31 format).
     * Returns null for standard format.
     *
     * @return TR-31 key block string, or null
     */
    public String getTr31KeyBlock() {
        if (isTr31Format) {
            return workingKeyPart1;
        }
        return null;
    }

    // =========================================================================
    // TR-31 Parsing Methods
    // =========================================================================

    /**
     * Gets the TR-31 version identifier (A, B, C, or D).
     */
    public String getTr31Version() {
        if (!isTr31Format || workingKeyPart1 == null || workingKeyPart1.isEmpty()) {
            return null;
        }
        return workingKeyPart1.substring(0, 1);
    }

    /**
     * Gets the TR-31 key usage code (positions 5-6).
     * Common codes: P0 (PIN), B0 (BDK), D0 (Data), M0/M3 (MAC), K0 (KEK)
     */
    public String getTr31KeyUsage() {
        if (!isTr31Format || workingKeyPart1 == null || workingKeyPart1.length() < 7) {
            return null;
        }
        return workingKeyPart1.substring(5, 7);
    }

    /**
     * Gets the TR-31 algorithm code (position 7).
     * T = 3DES, A = AES-128, B = AES-192, C = AES-256
     */
    public String getTr31Algorithm() {
        if (!isTr31Format || workingKeyPart1 == null || workingKeyPart1.length() < 8) {
            return null;
        }
        return workingKeyPart1.substring(7, 8);
    }

    /**
     * Gets the TR-31 block length (positions 1-4).
     */
    public int getTr31BlockLength() {
        if (!isTr31Format || workingKeyPart1 == null || workingKeyPart1.length() < 5) {
            return 0;
        }
        try {
            return Integer.parseInt(workingKeyPart1.substring(1, 5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Checks if this is a PIN encryption key.
     */
    public boolean isPinEncryptionKey() {
        String usage = getTr31KeyUsage();
        return "P0".equals(usage);
    }

    /**
     * Checks if this is a MAC key.
     */
    public boolean isMacKey() {
        String usage = getTr31KeyUsage();
        return "M0".equals(usage) || "M3".equals(usage);
    }

    /**
     * Checks if the key uses 3DES algorithm.
     */
    public boolean is3DesKey() {
        String algo = getTr31Algorithm();
        return "T".equals(algo);
    }

    /**
     * Checks if the key uses AES algorithm.
     */
    public boolean isAesKey() {
        String algo = getTr31Algorithm();
        return "A".equals(algo) || "B".equals(algo) || "C".equals(algo);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ConfigResponse{");
        sb.append("terminalId='").append(terminalId).append('\'');
        sb.append(", surcharge=").append(surchargeCents);
        if (isTr31Format) {
            sb.append(", keyFormat=TR-31");
            sb.append(", version=").append(getTr31Version());
            sb.append(", usage=").append(getTr31KeyUsage());
            sb.append(", algo=").append(getTr31Algorithm());
        } else {
            sb.append(", keyFormat=Standard");
            if (combinedWorkingKey != null) {
                // Only show first/last 4 chars for security
                sb.append(", key=").append(combinedWorkingKey.substring(0, 4));
                sb.append("...").append(combinedWorkingKey.substring(28));
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
