package castech.emvtxn;

/**
 * The system Wi‑Fi entry points the Admin screen opens (ADM-09, 6.2.15). Pure constants so the
 * choice is documented and tested without Android: the PANEL is a sheet over our screen (toggle,
 * network list, password prompt, DONE returns here) and is preferred; the full SETTINGS page is the
 * fallback and lets an operator reach other settings, which is why Admin stays PIN-protected.
 */
public final class AdminWifi {
    private AdminWifi() {}

    /** {@code Settings.Panel.ACTION_WIFI} (API 29+). */
    public static final String PANEL_ACTION = "android.settings.panel.action.WIFI";
    /** {@code Settings.ACTION_WIFI_SETTINGS}. */
    public static final String SETTINGS_ACTION = "android.settings.WIFI_SETTINGS";
}
