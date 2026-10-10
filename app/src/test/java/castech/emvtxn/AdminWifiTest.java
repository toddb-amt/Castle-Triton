package castech.emvtxn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** ADM-09 (6.2.15): the Admin screen opens the system Wi‑Fi PANEL first, the full settings page only as a fallback. */
public class AdminWifiTest {
    @Test
    public void thePanelIsPreferred_andIsTheAndroidQPlusPanelAction() {
        assertEquals("android.settings.panel.action.WIFI", AdminWifi.PANEL_ACTION);
    }
    @Test
    public void theFallbackIsTheFullWifiSettingsPage_andDiffers() {
        assertEquals("android.settings.WIFI_SETTINGS", AdminWifi.SETTINGS_ACTION);
        assertNotEquals(AdminWifi.PANEL_ACTION, AdminWifi.SETTINGS_ACTION);
    }
}
