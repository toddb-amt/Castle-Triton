package castech.emvtxn;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Manages persistent storage of ATM configuration settings.
 * Uses regular SharedPreferences (encryption disabled due to Castle terminal compatibility).
 */
public class AtmSettingsManager {

    private static final String TAG = "AtmSettingsManager";

    // Preference file names
    private static final String HOST_PREFS_FILE = "atm_host_settings";
    private static final String REGULAR_PREFS_FILE = "atm_settings";

    // Host configuration keys
    private static final String KEY_HOST_ADDRESS = "host_address";
    private static final String KEY_HOST_PORT = "host_port";
    private static final String KEY_TERMINAL_ID = "terminal_id";
    private static final String KEY_PROCESSOR_TYPE = "processor_type";

    // Regular preference keys (non-sensitive data)
    private static final String KEY_USE_FLAT_FEE = "use_flat_fee";
    private static final String KEY_FLAT_FEE_AMOUNT = "flat_fee_amount";
    private static final String KEY_PERCENTAGE_FEE = "percentage_fee";
    private static final String KEY_MIN_AMOUNT = "min_amount";
    private static final String KEY_MAX_AMOUNT = "max_amount";
    private static final String KEY_SETTINGS_INITIALIZED = "settings_initialized";

    private final Context context;
    private SharedPreferences hostPrefs;
    private SharedPreferences regularPrefs;
    private boolean initialized = false;

    /**
     * Creates a new AtmSettingsManager instance.
     *
     * @param context Application context
     */
    public AtmSettingsManager(Context context) {
        this.context = context.getApplicationContext();
        initialize();
    }

    /**
     * Initializes the SharedPreferences instances.
     */
    private void initialize() {
        try {
            // Use regular SharedPreferences (encryption disabled for Castle terminal compatibility)
            hostPrefs = context.getSharedPreferences(HOST_PREFS_FILE, Context.MODE_PRIVATE);
            regularPrefs = context.getSharedPreferences(REGULAR_PREFS_FILE, Context.MODE_PRIVATE);

            initialized = true;
            Log.d(TAG, "Settings manager initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize preferences: " + e.getMessage());
            initialized = false;
        }
    }

    /**
     * Checks if this is the first time the app is running (no saved settings).
     *
     * @return true if no settings have been saved yet
     */
    public boolean isFirstRun() {
        return !regularPrefs.getBoolean(KEY_SETTINGS_INITIALIZED, false);
    }

    /**
     * Loads all saved settings into GlobalPara.
     * Should be called during app startup after SDK initialization.
     */
    public void loadSettings() {
        if (!initialized) {
            Log.e(TAG, "Settings manager not initialized");
            return;
        }

        Log.d(TAG, "Loading ATM settings from storage");

        // Load host configuration
        GlobalPara.atmHostAddress = hostPrefs.getString(KEY_HOST_ADDRESS, "");
        GlobalPara.atmHostPort = hostPrefs.getInt(KEY_HOST_PORT, 8002);
        GlobalPara.atmTerminalId = hostPrefs.getString(KEY_TERMINAL_ID, "");
        GlobalPara.atmProcessorType = hostPrefs.getString(KEY_PROCESSOR_TYPE, "DNS");

        // Load fee configuration
        GlobalPara.atmUseFlatFee = regularPrefs.getBoolean(KEY_USE_FLAT_FEE, true);
        GlobalPara.atmFlatFeeAmount = getDouble(regularPrefs, KEY_FLAT_FEE_AMOUNT, 3.00);
        GlobalPara.atmPercentageFee = getDouble(regularPrefs, KEY_PERCENTAGE_FEE, 0.0);
        GlobalPara.atmMinAmount = getDouble(regularPrefs, KEY_MIN_AMOUNT, 20.00);
        GlobalPara.atmMaxAmount = getDouble(regularPrefs, KEY_MAX_AMOUNT, 500.00);

        Log.d(TAG, "Settings loaded - Host: " + GlobalPara.atmHostAddress +
                ", Port: " + GlobalPara.atmHostPort +
                ", Terminal: " + GlobalPara.atmTerminalId +
                ", Processor: " + GlobalPara.atmProcessorType);
        Log.d(TAG, "Fee settings - Flat: " + GlobalPara.atmUseFlatFee +
                ", Amount: $" + GlobalPara.atmFlatFeeAmount +
                ", Min: $" + GlobalPara.atmMinAmount +
                ", Max: $" + GlobalPara.atmMaxAmount);
    }

    /**
     * Saves all current GlobalPara settings to persistent storage.
     * Should be called when settings are changed in the admin screen.
     */
    public void saveSettings() {
        if (!initialized) {
            Log.e(TAG, "Settings manager not initialized");
            return;
        }

        Log.d(TAG, "Saving ATM settings to storage");

        // Save host configuration
        SharedPreferences.Editor hostEditor = hostPrefs.edit();
        hostEditor.putString(KEY_HOST_ADDRESS, GlobalPara.atmHostAddress);
        hostEditor.putInt(KEY_HOST_PORT, GlobalPara.atmHostPort);
        hostEditor.putString(KEY_TERMINAL_ID, GlobalPara.atmTerminalId);
        hostEditor.putString(KEY_PROCESSOR_TYPE, GlobalPara.atmProcessorType);
        hostEditor.apply();

        // Save fee configuration
        SharedPreferences.Editor regularEditor = regularPrefs.edit();
        regularEditor.putBoolean(KEY_USE_FLAT_FEE, GlobalPara.atmUseFlatFee);
        putDouble(regularEditor, KEY_FLAT_FEE_AMOUNT, GlobalPara.atmFlatFeeAmount);
        putDouble(regularEditor, KEY_PERCENTAGE_FEE, GlobalPara.atmPercentageFee);
        putDouble(regularEditor, KEY_MIN_AMOUNT, GlobalPara.atmMinAmount);
        putDouble(regularEditor, KEY_MAX_AMOUNT, GlobalPara.atmMaxAmount);
        regularEditor.putBoolean(KEY_SETTINGS_INITIALIZED, true);
        regularEditor.apply();

        Log.d(TAG, "Settings saved successfully");
    }

    /**
     * Saves only the host configuration settings.
     * Called when host settings are changed in admin screen.
     */
    public void saveHostSettings() {
        if (!initialized) {
            Log.e(TAG, "Settings manager not initialized");
            return;
        }

        SharedPreferences.Editor editor = hostPrefs.edit();
        editor.putString(KEY_HOST_ADDRESS, GlobalPara.atmHostAddress);
        editor.putInt(KEY_HOST_PORT, GlobalPara.atmHostPort);
        editor.putString(KEY_TERMINAL_ID, GlobalPara.atmTerminalId);
        editor.putString(KEY_PROCESSOR_TYPE, GlobalPara.atmProcessorType);
        editor.apply();

        // Mark settings as initialized
        regularPrefs.edit().putBoolean(KEY_SETTINGS_INITIALIZED, true).apply();

        Log.d(TAG, "Host settings saved");
    }

    /**
     * Saves only the fee configuration settings.
     * Called when fee settings are changed in admin screen.
     */
    public void saveFeeSettings() {
        if (!initialized) {
            Log.e(TAG, "Settings manager not initialized");
            return;
        }

        SharedPreferences.Editor editor = regularPrefs.edit();
        editor.putBoolean(KEY_USE_FLAT_FEE, GlobalPara.atmUseFlatFee);
        putDouble(editor, KEY_FLAT_FEE_AMOUNT, GlobalPara.atmFlatFeeAmount);
        putDouble(editor, KEY_PERCENTAGE_FEE, GlobalPara.atmPercentageFee);
        putDouble(editor, KEY_MIN_AMOUNT, GlobalPara.atmMinAmount);
        putDouble(editor, KEY_MAX_AMOUNT, GlobalPara.atmMaxAmount);
        editor.putBoolean(KEY_SETTINGS_INITIALIZED, true);
        editor.apply();

        Log.d(TAG, "Fee settings saved");
    }

    /**
     * Clears all saved settings.
     * Use with caution - this will reset all configuration to defaults.
     */
    public void clearAllSettings() {
        if (!initialized) {
            Log.e(TAG, "Settings manager not initialized");
            return;
        }

        hostPrefs.edit().clear().apply();
        regularPrefs.edit().clear().apply();

        Log.d(TAG, "All settings cleared");
    }

    /**
     * Checks if host configuration has been set.
     *
     * @return true if host address and terminal ID are configured
     */
    public boolean hasHostConfiguration() {
        String hostAddress = hostPrefs.getString(KEY_HOST_ADDRESS, "");
        String terminalId = hostPrefs.getString(KEY_TERMINAL_ID, "");
        return hostAddress != null && !hostAddress.isEmpty() &&
               terminalId != null && !terminalId.isEmpty();
    }

    // Getter methods for ATM host initialization

    /**
     * Gets the configured processor type.
     * @return Processor type string (DNS, SWITCH_COMMERCE, EFX)
     */
    public String getProcessorType() {
        return GlobalPara.atmProcessorType;
    }

    /**
     * Gets the configured host address.
     * @return Host address string
     */
    public String getHostAddress() {
        return GlobalPara.atmHostAddress;
    }

    /**
     * Gets the configured terminal ID.
     * @return Terminal ID string
     */
    public String getTerminalId() {
        return GlobalPara.atmTerminalId;
    }

    /**
     * Gets the configured host port.
     * @return Host port number
     */
    public int getHostPort() {
        return GlobalPara.atmHostPort;
    }

    // Helper methods for storing doubles (SharedPreferences doesn't support double directly)

    private void putDouble(SharedPreferences.Editor editor, String key, double value) {
        editor.putLong(key, Double.doubleToRawLongBits(value));
    }

    private double getDouble(SharedPreferences prefs, String key, double defaultValue) {
        if (!prefs.contains(key)) {
            return defaultValue;
        }
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToRawLongBits(defaultValue)));
    }

    /**
     * Gets a summary of current settings for display.
     *
     * @return String summary of settings
     */
    public String getSettingsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(GlobalPara.atmHostAddress.isEmpty() ? "(not set)" : GlobalPara.atmHostAddress);
        sb.append("\nPort: ").append(GlobalPara.atmHostPort);
        sb.append("\nTerminal ID: ").append(GlobalPara.atmTerminalId.isEmpty() ? "(not set)" : GlobalPara.atmTerminalId);
        sb.append("\nProcessor: ").append(GlobalPara.atmProcessorType);
        sb.append("\nFee Type: ").append(GlobalPara.atmUseFlatFee ? "Flat" : "Percentage");
        if (GlobalPara.atmUseFlatFee) {
            sb.append("\nFee Amount: $").append(String.format("%.2f", GlobalPara.atmFlatFeeAmount));
        } else {
            sb.append("\nFee Percent: ").append(String.format("%.2f%%", GlobalPara.atmPercentageFee));
        }
        sb.append("\nMin Amount: $").append(String.format("%.2f", GlobalPara.atmMinAmount));
        sb.append("\nMax Amount: $").append(String.format("%.2f", GlobalPara.atmMaxAmount));
        return sb.toString();
    }
}
