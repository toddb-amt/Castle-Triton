package castech.emvtxn.atm.host;

/**
 * Card Data Container
 *
 * Holds card data captured from any entry method (Contact, Contactless, MSR).
 * Used as input for ATM transactions.
 */
public class CastleCardData {

    // Entry mode constants for convenience
    public static final int ENTRY_MODE_CONTACT = 1;
    public static final int ENTRY_MODE_CONTACTLESS = 2;
    public static final int ENTRY_MODE_MSR = 3;
    public static final int ENTRY_MODE_MANUAL = 4;

    /**
     * Card entry method.
     */
    public enum EntryMethod {
        CONTACT,        // Chip card (EMV CT)
        CONTACTLESS,    // Tap/NFC (EMV CL)
        MSR,            // Magnetic stripe swipe
        MANUAL          // Manual key entry
    }

    /**
     * Sets entry mode using integer constant.
     */
    public void setEntryMode(int mode) {
        switch (mode) {
            case ENTRY_MODE_CONTACT:
                this.entryMethod = EntryMethod.CONTACT;
                break;
            case ENTRY_MODE_CONTACTLESS:
                this.entryMethod = EntryMethod.CONTACTLESS;
                break;
            case ENTRY_MODE_MSR:
                this.entryMethod = EntryMethod.MSR;
                break;
            case ENTRY_MODE_MANUAL:
                this.entryMethod = EntryMethod.MANUAL;
                break;
            default:
                this.entryMethod = EntryMethod.CONTACT;
                break;
        }
    }

    // Card identification
    private String pan;                     // Primary Account Number
    private String maskedPan;               // Masked PAN for display
    private String expirationDate;          // YYMM format
    private String track2Data;              // Full track 2 with sentinels

    // Entry method
    private EntryMethod entryMethod;

    // EMV specific data
    private String emvData;                 // TLV-encoded EMV tags for host
    private byte[] applicationCryptogram;   // Tag 9F26
    private byte[] cryptogramInfoData;      // Tag 9F27
    private byte[] applicationId;           // Tag 9F06 (AID)
    private String applicationLabel;        // Tag 50
    private byte[] issuerApplicationData;   // Tag 9F10

    // Encrypted data (from Castle SDK)
    private byte[] encryptedTrack2;
    private byte[] track2Checksum;
    private byte[] track2KSN;               // DUKPT Key Serial Number

    // PIN data
    private String encryptedPinBlock;       // Encrypted PIN block (hex)
    private byte[] pinBlockKSN;             // KSN for PIN encryption

    // Service code
    private String serviceCode;

    // Transaction metadata
    private boolean pinVerified;
    private boolean signatureRequired;
    private boolean offlineApproved;

    /**
     * Creates a new empty CastleCardData.
     */
    public CastleCardData() {
    }

    // =========================================================================
    // Factory Methods
    // =========================================================================

    /**
     * Creates card data from track 2 (MSR swipe).
     */
    public static CastleCardData fromTrack2(String track2, EntryMethod method) {
        CastleCardData data = new CastleCardData();
        data.setTrack2Data(track2);
        data.setEntryMethod(method);
        data.parseTrack2();
        return data;
    }

    /**
     * Creates card data for EMV transaction.
     */
    public static CastleCardData fromEmv(String pan, String expiry, String emvTags, EntryMethod method) {
        CastleCardData data = new CastleCardData();
        data.setPan(pan);
        data.setExpirationDate(expiry);
        data.setEmvData(emvTags);
        data.setEntryMethod(method);
        data.updateMaskedPan();
        return data;
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    public String getPan() {
        return pan;
    }

    public void setPan(String pan) {
        this.pan = pan;
        updateMaskedPan();
    }

    public String getMaskedPan() {
        return maskedPan;
    }

    public void setMaskedPan(String maskedPan) {
        this.maskedPan = maskedPan;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Gets expiration date formatted as MM/YY.
     */
    public String getFormattedExpiration() {
        if (expirationDate == null || expirationDate.length() < 4) {
            return null;
        }
        // Convert YYMM to MM/YY
        String yy = expirationDate.substring(0, 2);
        String mm = expirationDate.substring(2, 4);
        return mm + "/" + yy;
    }

    public String getTrack2Data() {
        return track2Data;
    }

    public void setTrack2Data(String track2Data) {
        this.track2Data = track2Data;
    }

    public EntryMethod getEntryMethod() {
        return entryMethod;
    }

    public void setEntryMethod(EntryMethod entryMethod) {
        this.entryMethod = entryMethod;
    }

    public String getEmvData() {
        return emvData;
    }

    public void setEmvData(String emvData) {
        this.emvData = emvData;
    }

    public byte[] getApplicationCryptogram() {
        return applicationCryptogram;
    }

    public void setApplicationCryptogram(byte[] applicationCryptogram) {
        this.applicationCryptogram = applicationCryptogram;
    }

    public byte[] getCryptogramInfoData() {
        return cryptogramInfoData;
    }

    public void setCryptogramInfoData(byte[] cryptogramInfoData) {
        this.cryptogramInfoData = cryptogramInfoData;
    }

    public byte[] getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(byte[] applicationId) {
        this.applicationId = applicationId;
    }

    public String getApplicationLabel() {
        return applicationLabel;
    }

    public void setApplicationLabel(String applicationLabel) {
        this.applicationLabel = applicationLabel;
    }

    public byte[] getIssuerApplicationData() {
        return issuerApplicationData;
    }

    public void setIssuerApplicationData(byte[] issuerApplicationData) {
        this.issuerApplicationData = issuerApplicationData;
    }

    public byte[] getEncryptedTrack2() {
        return encryptedTrack2;
    }

    public void setEncryptedTrack2(byte[] encryptedTrack2) {
        this.encryptedTrack2 = encryptedTrack2;
    }

    public byte[] getTrack2Checksum() {
        return track2Checksum;
    }

    public void setTrack2Checksum(byte[] track2Checksum) {
        this.track2Checksum = track2Checksum;
    }

    public byte[] getTrack2KSN() {
        return track2KSN;
    }

    public void setTrack2KSN(byte[] track2KSN) {
        this.track2KSN = track2KSN;
    }

    public String getEncryptedPinBlock() {
        return encryptedPinBlock;
    }

    public void setEncryptedPinBlock(String encryptedPinBlock) {
        this.encryptedPinBlock = encryptedPinBlock;
    }

    public byte[] getPinBlockKSN() {
        return pinBlockKSN;
    }

    public void setPinBlockKSN(byte[] pinBlockKSN) {
        this.pinBlockKSN = pinBlockKSN;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public boolean isPinVerified() {
        return pinVerified;
    }

    public void setPinVerified(boolean pinVerified) {
        this.pinVerified = pinVerified;
    }

    public boolean isSignatureRequired() {
        return signatureRequired;
    }

    public void setSignatureRequired(boolean signatureRequired) {
        this.signatureRequired = signatureRequired;
    }

    public boolean isOfflineApproved() {
        return offlineApproved;
    }

    public void setOfflineApproved(boolean offlineApproved) {
        this.offlineApproved = offlineApproved;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Checks if this is an EMV (chip) transaction.
     */
    public boolean isEmv() {
        return entryMethod == EntryMethod.CONTACT || entryMethod == EntryMethod.CONTACTLESS;
    }

    /**
     * Checks if this is a contactless transaction.
     */
    public boolean isContactless() {
        return entryMethod == EntryMethod.CONTACTLESS;
    }

    /**
     * Checks if EMV data is available.
     */
    public boolean hasEmvData() {
        return emvData != null && !emvData.isEmpty();
    }

    /**
     * Gets the last 4 digits of the PAN.
     */
    public String getLast4Digits() {
        if (pan != null && pan.length() >= 4) {
            return pan.substring(pan.length() - 4);
        }
        return null;
    }

    /**
     * Gets the card network based on BIN.
     */
    public String getCardNetwork() {
        return PinBlockFormatter.getCardNetwork(pan);
    }

    /**
     * Validates the PAN using Luhn algorithm.
     */
    public boolean isValidPan() {
        return PinBlockFormatter.validateLuhn(pan);
    }

    /**
     * Parses track 2 data to extract PAN and expiration.
     */
    private void parseTrack2() {
        if (track2Data == null || track2Data.isEmpty()) {
            return;
        }

        try {
            pan = PinBlockFormatter.extractPanFromTrack2(track2Data);
            expirationDate = PinBlockFormatter.extractExpirationFromTrack2(track2Data);
            updateMaskedPan();

            // Extract service code if available
            String data = track2Data;
            if (data.startsWith(";")) data = data.substring(1);
            if (data.endsWith("?")) data = data.substring(0, data.length() - 1);

            int sepIndex = data.indexOf('=');
            if (sepIndex == -1) sepIndex = data.indexOf('D');

            if (sepIndex > 0 && data.length() > sepIndex + 7) {
                serviceCode = data.substring(sepIndex + 5, sepIndex + 8);
            }
        } catch (Exception e) {
            // Parsing failed, leave fields null
        }
    }

    /**
     * Updates the masked PAN from the current PAN.
     */
    private void updateMaskedPan() {
        if (pan != null) {
            maskedPan = PinBlockFormatter.maskPan(pan);
        }
    }

    /**
     * Builds track 2 equivalent data from PAN and expiration.
     * Used when track 2 is not directly available (EMV).
     */
    public String buildTrack2Equivalent() {
        if (pan == null || expirationDate == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(';');
        sb.append(pan);
        sb.append('=');
        sb.append(expirationDate);
        if (serviceCode != null) {
            sb.append(serviceCode);
        } else {
            sb.append("000"); // Default service code
        }
        sb.append('?');
        return sb.toString();
    }

    /**
     * Gets the track 2 data for the transaction.
     * Returns actual track 2 if available, otherwise builds equivalent.
     */
    public String getTrack2ForTransaction() {
        if (track2Data != null && !track2Data.isEmpty()) {
            return track2Data;
        }
        return buildTrack2Equivalent();
    }

    @Override
    public String toString() {
        return "CastleCardData{" +
               "pan=" + maskedPan +
               ", exp=" + getFormattedExpiration() +
               ", entry=" + entryMethod +
               ", network=" + getCardNetwork() +
               ", hasEmv=" + hasEmvData() +
               ", pinVerified=" + pinVerified +
               '}';
    }
}
