package castech.emvtxn;

import android.content.Context;
import android.util.Log;

import CTOS.CtSettings;

/**
 * Manages kiosk mode configuration for the Castle S1F4 PRO terminal.
 * Controls auto-start on boot, navigation button locking, screen timeout,
 * and daily auto-reboot.
 */
public class KioskManager {
    private static final String TAG = "KioskManager";

    private final Context context;
    private final boolean isEmulator;

    public KioskManager(Context context, boolean isEmulator) {
        this.context = context;
        this.isEmulator = isEmulator;
    }

    /**
     * Applies kiosk mode settings based on GlobalPara configuration.
     * Skipped on emulator or when kiosk mode is disabled.
     */
    public void initialize() {
        if (isEmulator || !GlobalPara.atmKioskMode) {
            Log.d(TAG, "Kiosk mode skipped (emulator=" + isEmulator + ", kioskMode=" + GlobalPara.atmKioskMode + ")");
            return;
        }

        try {
            CtSettings ctSettings = new CtSettings();

            if (GlobalPara.atmAutoStartOnBoot) {
                ctSettings.setDefaultApp(context.getPackageName());
                Log.d(TAG, "Set default app to " + context.getPackageName());
            }

            if (GlobalPara.atmDisableNavButtons) {
                ctSettings.setNavigation(false, false, false);
                Log.d(TAG, "Navigation buttons disabled");
            }

            if (GlobalPara.atmScreenAlwaysOn) {
                ctSettings.setScreenTimeOut(0);
                Log.d(TAG, "Screen timeout disabled");
            }

            if (GlobalPara.atmAutoReboot) {
                ctSettings.setAutoRebootTime(GlobalPara.atmAutoRebootHour, GlobalPara.atmAutoRebootMinute);
                Log.d(TAG, "Auto reboot set to " + GlobalPara.atmAutoRebootHour + ":" + String.format("%02d", GlobalPara.atmAutoRebootMinute));
            }

            Log.d(TAG, "Kiosk mode initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing kiosk mode: " + e.getMessage());
        }
    }

    /**
     * Enables kiosk mode — sets app as default, disables nav, keeps screen on.
     */
    public void enable(String packageName) {
        if (isEmulator) return;
        try {
            CtSettings ctSettings = new CtSettings();
            ctSettings.setDefaultApp(packageName);
            ctSettings.setNavigation(false, false, false);
            ctSettings.setScreenTimeOut(0);
            Log.d(TAG, "Kiosk mode enabled");
        } catch (Exception e) {
            Log.e(TAG, "Error enabling kiosk: " + e.getMessage());
        }
    }

    /**
     * Disables kiosk mode — restores nav buttons, removes default app, restores screen timeout.
     */
    public void disable() {
        if (isEmulator) return;
        try {
            CtSettings ctSettings = new CtSettings();
            ctSettings.setDefaultApp("");
            ctSettings.setNavigation(true, true, true);
            ctSettings.setScreenTimeOut(300 * 1000); // 5 min timeout
            Log.d(TAG, "Kiosk mode disabled");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling kiosk: " + e.getMessage());
        }
    }
}
