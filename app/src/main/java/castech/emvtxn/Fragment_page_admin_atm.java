package castech.emvtxn;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import castech.emvtxn.atm.host.AtmHostService;
import castech.emvtxn.atm.host.CastleKeyManager;
import castech.emvtxn.test.EmvCryptogramTest;

/**
 * Admin Configuration Fragment for Cashless ATM
 * Features:
 * - PIN-protected access (4-6 digit PIN)
 * - Fee configuration (flat/percentage)
 * - Withdrawal limits
 * - Host settings (processor, host, port, terminal ID)
 * - Key management (TMK injection, working key download)
 * - Diagnostics (printer test, card reader test)
 */
public class Fragment_page_admin_atm extends Fragment {
    private static final String TAG = "AdminATM";
    private static final String PREFS_NAME = "ATM_Admin_Prefs";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT_TIME = "lockout_time";
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    // Fixed admin passwords — only TFI changes these (by shipping a new build).
    // The old user-changeable stored-hash / "Change Default PIN" flow was
    // removed 2026-09-11. Super Admin sees everything; Normal Admin is limited
    // to Reversal Management, View Transaction History, WiFi, and Diagnostics.
    private static final String SUPER_ADMIN_PIN = "8675309";
    private static final String NORMAL_ADMIN_PIN = "123456";

    // Access tiers returned by verifyPinTier().
    private static final int ACCESS_NONE = 0;
    private static final int ACCESS_NORMAL = 1;
    private static final int ACCESS_SUPER = 2;

    private static MainActivity mainActivity = null;
    private View rootView;
    private boolean isAuthenticated = false;
    private int accessLevel = ACCESS_NONE;  // set on successful admin login
    private boolean isUserVisible = false;  // Track actual user visibility from setMenuVisibility

    // UI Elements - Fee Configuration

    // UI Elements - Withdrawal Limits

    // UI Elements - Host Settings
    private TextView txvHostStatus;
    // Fee / limits / host are CasHUB-managed since 6.2.9: read-only text, no inputs.
    private TextView txvFeeConfig;
    private TextView txvLimits;
    private TextView txvHostConfig;
    private TextView txvNetwork;

    // UI Elements - Terminal Info
    private TextView txvTerminalInfo;

    // UI Elements - Buttons
    private Button btnRequestNewKey;
    private Button btnTestPrinter;

    // WiFi configuration
    private android.widget.EditText edtWifiSsid;
    private android.widget.EditText edtWifiPassword;
    private android.widget.Spinner spinnerWifiSecurity;
    private TextView txvWifiStatus;
    private Button btnWifiConnect;
    private Button btnWifiStatus;
    private Button btnWifiScan;
    private android.widget.Switch swWifiPower;
    /** Set while the code (not the operator) moves the WiFi switch. */
    private boolean wifiSwitchProgrammatic = false;
    private static final int REQ_WIFI_SCAN_PERMISSION = 4711;
    private Button btnTestCardReader;
    private Button btnSaveSettings;
    private Button btnClearHistory;
    private Button btnExit;

    // UI Elements - Reversal Management
    private TextView txvReversalStatus;
    private Button btnProcessReversals;
    private LinearLayout layReversalRecords;

    // UI Elements - POS Mode (semi-integrated proxy)
    private CheckBox cbEnablePosMode;
    private EditText edtPosProxyUrl;
    private EditText edtPosAccessKey;
    private TextView txvPosStatus;
    private castech.emvtxn.pos.PosConfig posConfig;

    // Processor list

    // UI Elements - Kiosk Mode
    private android.widget.Switch switchKioskMode;
    private android.widget.Switch switchAutoStart;
    private android.widget.Switch switchScreenAlwaysOn;
    private android.widget.Switch switchAutoReboot;
    private Button btnApplyKiosk;

    // For cleanup - track pending operations
    private android.os.Handler connectionHandler;
    private Runnable connectionCheckRunnable;
    private AtmHostService.AtmEventListener currentEventListener;

    public Fragment_page_admin_atm(MainActivity activity) {
        mainActivity = activity;
    }

    // Required no-arg constructor for Android fragment restoration on activity
    // recreation (see Fragment_page_main_menu). mainActivity is static so it
    // survives. Without this, recreation crashed with NoSuchMethodException.
    public Fragment_page_admin_atm() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        try {
            rootView = inflater.inflate(R.layout.fragment_page_admin_atm, container, false);
            initializeViews();
            loadSettings();
            setupListeners();
            updateTerminalInfo();

            // Initially hide content until PIN verified
            setContentVisible(false);

            // Show PIN dialog after a short delay to ensure view is ready
            // This fixes the blank screen on first selection issue
            // IMPORTANT: Use isUserVisible (set by setMenuVisibility) instead of isVisible()
            // because isVisible() returns true even when ViewPager pre-creates adjacent fragments
            rootView.post(() -> {
                if (isUserVisible && !isAuthenticated && !pinDialogShown) {
                    Log.d(TAG, "onCreateView post: showing PIN dialog (isUserVisible=true)");
                    pinDialogShown = true;
                    showPinDialog();
                } else {
                    Log.d(TAG, "onCreateView post: NOT showing PIN dialog (isUserVisible=" + isUserVisible + ", isAuthenticated=" + isAuthenticated + ", pinDialogShown=" + pinDialogShown + ")");
                }
            });

            return rootView;
        } catch (Exception e) {
            Log.e(TAG, "Error creating admin view: " + e.getMessage());
            android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
            fallback.setBackgroundColor(0xFFFF0000); // Red = admin error
            return fallback;
        }
    }

    // Track if PIN dialog has been shown for this session
    private boolean pinDialogShown = false;

    @Override
    public void setMenuVisibility(boolean menuVisible) {
        super.setMenuVisibility(menuVisible);
        isUserVisible = menuVisible;  // Track user visibility for onCreateView post check
        Log.d(TAG, "setMenuVisibility: " + menuVisible + ", authenticated: " + isAuthenticated);

        // Only show PIN dialog when this page becomes visible and we haven't authenticated yet
        if (menuVisible && !isAuthenticated && !pinDialogShown && rootView != null) {
            Log.d(TAG, "Admin page now visible - showing PIN dialog");
            pinDialogShown = true;
            showPinDialog();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reset pinDialogShown if we're coming back to admin and not authenticated
        if (!isAuthenticated) {
            pinDialogShown = false;
        }
        // Refresh reversal status — host may have been initialized since fragment created
        updateReversalStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Cancel any pending connection checks
        if (connectionHandler != null && connectionCheckRunnable != null) {
            connectionHandler.removeCallbacks(connectionCheckRunnable);
        }
        connectionHandler = null;
        connectionCheckRunnable = null;

        // Clear event listener to prevent callbacks to destroyed fragment
        if (mainActivity != null) {
            AtmHostService hostService = mainActivity.getAtmHostService();
            if (hostService != null && currentEventListener != null) {
                hostService.setEventListener(null);
            }
        }
        currentEventListener = null;

        // Clear view reference
        rootView = null;

        Log.d(TAG, "onDestroyView: cleaned up handlers and listeners");
    }

    private void initializeViews() {
        // Fee / limits / host: CasHUB-managed, read-only (6.2.9)
        txvFeeConfig = rootView.findViewById(R.id.txvFeeConfig);
        txvLimits = rootView.findViewById(R.id.txvLimits);
        txvHostConfig = rootView.findViewById(R.id.txvHostConfig);
        txvHostStatus = rootView.findViewById(R.id.txvHostStatus);

        // Terminal Info
        txvTerminalInfo = rootView.findViewById(R.id.txvTerminalInfo);
        txvNetwork = rootView.findViewById(R.id.txvNetwork);

        // Buttons
        btnRequestNewKey = rootView.findViewById(R.id.btnRequestNewKey);
        btnTestPrinter = rootView.findViewById(R.id.btnTestPrinter);
        btnTestCardReader = rootView.findViewById(R.id.btnTestCardReader);
        btnSaveSettings = rootView.findViewById(R.id.btnSaveSettings);
        btnClearHistory = rootView.findViewById(R.id.btnClearHistory);
        btnExit = rootView.findViewById(R.id.btnExit);

        // POS Mode controls
        cbEnablePosMode = rootView.findViewById(R.id.cbEnablePosMode);
        edtPosProxyUrl = rootView.findViewById(R.id.edtPosProxyUrl);
        edtPosAccessKey = rootView.findViewById(R.id.edtPosAccessKey);
        txvPosStatus = rootView.findViewById(R.id.txvPosStatus);
        posConfig = new castech.emvtxn.pos.PosConfig(getContext());
        loadPosSettings();

        // Reversal Management
        txvReversalStatus = rootView.findViewById(R.id.txvReversalStatus);
        btnProcessReversals = rootView.findViewById(R.id.btnProcessReversals);
        layReversalRecords = rootView.findViewById(R.id.layReversalRecords);

        // Kiosk Mode
        switchKioskMode = rootView.findViewById(R.id.switchKioskMode);
        switchAutoStart = rootView.findViewById(R.id.switchAutoStart);
        switchScreenAlwaysOn = rootView.findViewById(R.id.switchScreenAlwaysOn);
        switchAutoReboot = rootView.findViewById(R.id.switchAutoReboot);
        btnApplyKiosk = rootView.findViewById(R.id.btnApplyKiosk);

        // WiFi configuration
        edtWifiSsid = rootView.findViewById(R.id.edtWifiSsid);
        edtWifiPassword = rootView.findViewById(R.id.edtWifiPassword);
        spinnerWifiSecurity = rootView.findViewById(R.id.spinnerWifiSecurity);
        txvWifiStatus = rootView.findViewById(R.id.txvWifiStatus);
        btnWifiConnect = rootView.findViewById(R.id.btnWifiConnect);
        btnWifiStatus = rootView.findViewById(R.id.btnWifiStatus);
        btnWifiScan = rootView.findViewById(R.id.btnWifiScan);
        swWifiPower = rootView.findViewById(R.id.swWifiPower);
        setupWifiSection();
    }

    // ==================== WiFi Configuration ====================
    // Uses Castle CtSettings (settings service). Security `type` per the Castles
    // Android API Reference v5.0: 1=NOPASS, 2=WEP, 3=WPA/WPA2. All CtSettings
    // calls run off the UI thread (binder calls into the settings service).

    /** Spinner order — index maps to CtSettings type via WIFI_TYPE_VALUES. */
    private static final String[] WIFI_TYPE_LABELS = {"WPA/WPA2", "WEP", "Open (no password)"};
    private static final int[] WIFI_TYPE_VALUES = {3, 2, 1};

    private void setupWifiSection() {
        if (spinnerWifiSecurity != null) {
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                    getContext(), android.R.layout.simple_spinner_item, WIFI_TYPE_LABELS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerWifiSecurity.setAdapter(adapter);
            spinnerWifiSecurity.setSelection(0);  // WPA/WPA2 default
        }
        if (btnWifiConnect != null) {
            btnWifiConnect.setOnClickListener(v -> connectWifi());
        }
        if (btnWifiStatus != null) {
            btnWifiStatus.setOnClickListener(v -> refreshWifiStatus());
        }
        if (swWifiPower != null) {
            syncWifiSwitch();
            swWifiPower.setOnCheckedChangeListener((btn, on) -> {
                if (wifiSwitchProgrammatic) return;
                onWifiSwitchToggled(on);
            });
        }
        if (btnWifiScan != null) {
            btnWifiScan.setOnClickListener(v -> scanWifiNetworks());
        }
        // Show current state when the admin screen opens
        refreshWifiStatus();
    }

    /**
     * Scans for visible WiFi networks and shows a pick-list. Android gates scan
     * RESULTS behind location permission, so request it on first use (one-time
     * system dialog on the admin screen).
     */
    private void scanWifiNetworks() {
        if (getContext() == null) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(getContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_WIFI_SCAN_PERMISSION);
            return;  // continues in onRequestPermissionsResult
        }
        // Android suppresses scan RESULTS system-wide when Location Services are
        // off (even with the permission granted) — detect that up front and give
        // the admin a one-tap path to the system toggle instead of an empty list.
        if (!isLocationEnabled()) {
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("Location Services Off")
                    .setMessage("Android requires Location Services to be ON to list WiFi "
                            + "networks (system rule). Turn it on, then scan again.")
                    .setPositiveButton("Open Location Settings", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (Throwable t) {
                            Toast.makeText(getContext(), "Could not open settings: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        doWifiScan();
    }

    private boolean isLocationEnabled() {
        try {
            int mode = android.provider.Settings.Secure.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF);
            return mode != android.provider.Settings.Secure.LOCATION_MODE_OFF;
        } catch (Throwable t) {
            return true;  // can't tell — let the scan try rather than block it
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WIFI_SCAN_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                doWifiScan();
            } else {
                Toast.makeText(getContext(),
                        "Location permission is required by Android to list WiFi networks",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doWifiScan() {
        setWifiStatusText("Scanning for networks...");
        if (btnWifiScan != null) btnWifiScan.setEnabled(false);

        new Thread(() -> {
            java.util.List<android.net.wifi.ScanResult> results = null;
            String error = null;
            try {
                // Make sure the radio is on (Castle settings service; app can't
                // toggle WiFi itself on targetSdk >= 29)
                try { new CTOS.CtSettings().openWifi(); } catch (Throwable ignore) {}

                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        requireContext().getApplicationContext()
                                .getSystemService(android.content.Context.WIFI_SERVICE);
                wm.startScan();  // may be throttled — cached results still work
                Thread.sleep(2500);
                results = wm.getScanResults();
            } catch (SecurityException se) {
                error = "Permission denied reading scan results";
            } catch (Throwable t) {
                error = "Scan failed: " + t.getMessage();
            }

            // Dedupe by SSID keeping the strongest signal, drop hidden/empty SSIDs
            final java.util.List<android.net.wifi.ScanResult> networks = new java.util.ArrayList<>();
            if (results != null) {
                java.util.Map<String, android.net.wifi.ScanResult> best = new java.util.LinkedHashMap<>();
                for (android.net.wifi.ScanResult r : results) {
                    if (r.SSID == null || r.SSID.isEmpty()) continue;
                    android.net.wifi.ScanResult prev = best.get(r.SSID);
                    if (prev == null || r.level > prev.level) best.put(r.SSID, r);
                }
                networks.addAll(best.values());
                java.util.Collections.sort(networks, (a, b) -> b.level - a.level);
            }

            final String err = error;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (btnWifiScan != null) btnWifiScan.setEnabled(true);
                if (err != null) {
                    setWifiStatusText(err);
                    return;
                }
                if (networks.isEmpty()) {
                    setWifiStatusText("No networks found. If WiFi is on, check that "
                            + "Location Services are enabled (Android requires them for scans).");
                    return;
                }
                showWifiPickList(networks);
                refreshWifiStatus();
            });
        }).start();
    }

    /** Signal bars + security label per network; tap fills SSID + security type. */
    private void showWifiPickList(final java.util.List<android.net.wifi.ScanResult> networks) {
        String[] items = new String[networks.size()];
        for (int i = 0; i < networks.size(); i++) {
            android.net.wifi.ScanResult r = networks.get(i);
            items[i] = wifiSignalBars(r.level) + "  " + r.SSID
                    + "  (" + wifiSecurityLabel(r.capabilities) + ")";
        }
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Select WiFi Network (" + networks.size() + " found)")
                .setItems(items, (dialog, which) -> {
                    android.net.wifi.ScanResult picked = networks.get(which);
                    if (edtWifiSsid != null) edtWifiSsid.setText(picked.SSID);
                    if (spinnerWifiSecurity != null) {
                        spinnerWifiSecurity.setSelection(wifiSecuritySpinnerIndex(picked.capabilities));
                    }
                    if (edtWifiPassword != null) {
                        edtWifiPassword.setText("");
                        edtWifiPassword.requestFocus();
                    }
                    Toast.makeText(getContext(),
                            "Selected \"" + picked.SSID + "\" — enter the password and tap Connect",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String wifiSignalBars(int dbm) {
        if (dbm >= -55) return "▂▄▆█";
        if (dbm >= -66) return "▂▄▆ ";
        if (dbm >= -77) return "▂▄  ";
        return "▂   ";
    }

    private String wifiSecurityLabel(String caps) {
        if (caps == null) return "Open";
        if (caps.contains("WPA")) return caps.contains("WPA3") ? "WPA3" : "WPA/WPA2";
        if (caps.contains("WEP")) return "WEP";
        return "Open";
    }

    /** Maps ScanResult capabilities to the security spinner index (WPA / WEP / Open). */
    private int wifiSecuritySpinnerIndex(String caps) {
        if (caps != null && caps.contains("WPA")) return 0;
        if (caps != null && caps.contains("WEP")) return 1;
        if (caps == null || caps.contains("ESS") && !caps.contains("WPA") && !caps.contains("WEP")) return 2;
        return 0;
    }

    private void connectWifi() {
        final String ssid = edtWifiSsid != null ? edtWifiSsid.getText().toString().trim() : "";
        final String password = edtWifiPassword != null ? edtWifiPassword.getText().toString() : "";
        final int typeIdx = spinnerWifiSecurity != null ? spinnerWifiSecurity.getSelectedItemPosition() : 0;
        final int type = WIFI_TYPE_VALUES[Math.max(0, Math.min(typeIdx, WIFI_TYPE_VALUES.length - 1))];

        if (ssid.isEmpty()) {
            Toast.makeText(getContext(), "Enter an SSID", Toast.LENGTH_SHORT).show();
            return;
        }
        if (type != 1 && password.isEmpty()) {
            Toast.makeText(getContext(), "Enter the WiFi password (or choose Open)", Toast.LENGTH_SHORT).show();
            return;
        }

        setWifiStatusText("Connecting to \"" + ssid + "\" ...");
        if (btnWifiConnect != null) btnWifiConnect.setEnabled(false);

        new Thread(() -> {
            String result;
            try {
                CTOS.CtSettings settings = new CTOS.CtSettings();
                settings.openWifi();
                // DHCP connect; returns a success/failure message string
                String ret = settings.setDhcpWifi(ssid, password, type);
                result = "Connect result: " + (ret != null ? ret : "(no response)");
                Log.d(TAG, "WiFi setDhcpWifi(\"" + ssid + "\", type=" + type + ") -> " + ret);
            } catch (Throwable t) {
                result = "WiFi connect failed: " + t.getMessage();
                Log.e(TAG, "WiFi connect error", t);
            }
            final String msg = result;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (btnWifiConnect != null) btnWifiConnect.setEnabled(true);
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                });
            }
            // Give the association a moment, then show the resulting state
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            refreshWifiStatus();
        }).start();
    }

    /** Reflects the real radio state on the switch without firing its listener. */
    private void syncWifiSwitch() {
        if (swWifiPower == null || getContext() == null) return;
        boolean on = false;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            on = wm != null && wm.isWifiEnabled();
        } catch (Throwable ignore) {}
        wifiSwitchProgrammatic = true;
        swWifiPower.setChecked(on);
        wifiSwitchProgrammatic = false;
    }

    /** True when the terminal has a cellular data connection it could fall back to. */
    private boolean cellularDataConnected() {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    getContext().getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getDataState() == android.telephony.TelephonyManager.DATA_CONNECTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Operator moved the WiFi switch. Off while WiFi is the only connection asks first. */
    private void onWifiSwitchToggled(final boolean on) {
        if (!on && !cellularDataConnected()) {
            new AlertDialog.Builder(getContext())
                .setTitle("Turn WiFi off?")
                .setMessage("WiFi is this terminal's only connection. Turning it off takes the "
                        + "terminal offline — host, POS and CasHUB — until WiFi is turned back on.")
                .setPositiveButton("Turn off", (d, w) -> setWifiPower(false))
                .setNegativeButton("Cancel", (d, w) -> syncWifiSwitch())
                .setOnCancelListener(d -> syncWifiSwitch())
                .show();
            return;
        }
        setWifiPower(on);
    }

    /** Radio on/off through Castle's settings service (same calls Connect uses). */
    private void setWifiPower(final boolean on) {
        if (swWifiPower != null) swWifiPower.setEnabled(false);
        new Thread(() -> {
            String msg;
            try {
                CTOS.CtSettings settings = new CTOS.CtSettings();
                if (on) settings.openWifi(); else settings.closeWifi();
                Log.w(TAG, "WiFi radio turned " + (on ? "ON" : "OFF") + " by admin tier="
                        + (accessLevel == ACCESS_SUPER ? "SUPER" : "NORMAL"));
                msg = "WiFi " + (on ? "on" : "off");
            } catch (Throwable t) {
                Log.e(TAG, "WiFi power change failed", t);
                msg = "WiFi power change failed: " + t.getMessage();
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            final String m = msg;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (swWifiPower != null) swWifiPower.setEnabled(true);
                    syncWifiSwitch();   // show what the radio actually did
                    Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
                });
            }
            refreshWifiStatus();
        }, "WifiPower").start();
    }

    private void refreshWifiStatus() {
        new Thread(() -> {
            String status;
            String currentSsid = null;
            try {
                CTOS.CtSettings settings = new CTOS.CtSettings();
                java.util.Map<?, ?> cfg = settings.getWifiConfig();
                if (cfg == null || cfg.isEmpty()) {
                    status = "WiFi: no configuration returned";
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<?, ?> e : cfg.entrySet()) {
                        sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                        // Remember the connected SSID so we can prefill the field
                        String k = String.valueOf(e.getKey()).toLowerCase();
                        if (k.contains("ssid") && e.getValue() != null) {
                            currentSsid = String.valueOf(e.getValue())
                                    .replace("\"", "").trim();
                        }
                    }
                    status = sb.toString().trim();
                }
            } catch (Throwable t) {
                status = "WiFi status unavailable: " + t.getMessage();
                Log.w(TAG, "getWifiConfig failed: " + t.getMessage());
            }

            // Fallback for the current SSID: WifiManager connection info (works
            // when the Castles config map doesn't include an ssid field)
            if (currentSsid == null || currentSsid.isEmpty()
                    || "<unknown ssid>".equalsIgnoreCase(currentSsid)) {
                try {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                            requireContext().getApplicationContext()
                                    .getSystemService(android.content.Context.WIFI_SERVICE);
                    android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        String s = info.getSSID().replace("\"", "").trim();
                        if (!s.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(s)) {
                            currentSsid = s;
                        }
                    }
                } catch (Throwable ignore) {}
            }

            final String st = status;
            final String ssid = currentSsid;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (txvWifiStatus != null) {
                        txvWifiStatus.setText(ssid != null && !ssid.isEmpty()
                                ? "Connected: " + ssid + "\n" + st : st);
                    }
                    // Prefill the SSID field with the current network — only if the
                    // admin hasn't typed anything (never clobber their input)
                    if (edtWifiSsid != null && ssid != null && !ssid.isEmpty()
                            && edtWifiSsid.getText().toString().trim().isEmpty()) {
                        edtWifiSsid.setText(ssid);
                    }
                });
            }
        }).start();
    }

    private void setWifiStatusText(final String text) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (txvWifiStatus != null) {
                    txvWifiStatus.setText(text);
                }
            });
        }
    }

    private void setupListeners() {
        // Request New Key button (the only host action left on the terminal, 6.2.9)
        btnRequestNewKey.setOnClickListener(v -> requestNewWorkingKey());

        // Test Printer button
        btnTestPrinter.setOnClickListener(v -> testPrinter());

        // Test Card Reader button
        btnTestCardReader.setOnClickListener(v -> testCardReader());

        // Save Settings button
        btnSaveSettings.setOnClickListener(v -> saveSettings());
        // Clear Transaction History (6.2.11): closed batches only, Super
        if (btnClearHistory != null) btnClearHistory.setOnClickListener(v -> clearClosedBatches());

        // Exit button
        btnExit.setOnClickListener(v -> exitAdmin());

        // Reversal Management buttons
        if (btnProcessReversals != null) {
            btnProcessReversals.setOnClickListener(v -> processPendingReversals());
        }

        // Update reversal status
        updateReversalStatus();

        // Kiosk Mode controls
        if (switchKioskMode != null) {
            switchKioskMode.setChecked(GlobalPara.atmKioskMode);
            switchAutoStart.setChecked(GlobalPara.atmAutoStartOnBoot);
            switchScreenAlwaysOn.setChecked(GlobalPara.atmScreenAlwaysOn);
            switchAutoReboot.setChecked(GlobalPara.atmAutoReboot);

            // Master toggle enables/disables sub-switches
            switchKioskMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                switchAutoStart.setEnabled(isChecked);
                switchScreenAlwaysOn.setEnabled(isChecked);
                switchAutoReboot.setEnabled(isChecked);
            });

            // Set initial enabled state
            switchAutoStart.setEnabled(switchKioskMode.isChecked());
            switchScreenAlwaysOn.setEnabled(switchKioskMode.isChecked());
            switchAutoReboot.setEnabled(switchKioskMode.isChecked());
        }

        if (btnApplyKiosk != null) {
            btnApplyKiosk.setOnClickListener(v -> applyKioskSettings());
        }
    }

    // ==================== PIN Protection ====================

    private void showPinDialog() {
        if (isLockedOut()) {
            long remainingTime = getRemainingLockoutTime();
            long minutes = remainingTime / 60000;
            long seconds = (remainingTime % 60000) / 1000;
            Toast.makeText(getContext(),
                String.format("Locked out. Try again in %d:%02d", minutes, seconds),
                Toast.LENGTH_LONG).show();
            exitAdmin();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Admin PIN Required");

        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter admin PIN");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String enteredPin = input.getText().toString();
            int tier = verifyPinTier(enteredPin);
            if (tier != ACCESS_NONE) {
                isAuthenticated = true;
                accessLevel = tier;
                resetFailedAttempts();
                setContentVisible(true);
                applyAccessLevel(tier);
                Toast.makeText(getContext(),
                    tier == ACCESS_SUPER ? "Super Admin access granted" : "Admin access granted",
                    Toast.LENGTH_SHORT).show();
            } else {
                incrementFailedAttempts();
                int remaining = MAX_FAILED_ATTEMPTS - getFailedAttempts();
                if (remaining > 0) {
                    Toast.makeText(getContext(),
                        "Invalid PIN. " + remaining + " attempts remaining.",
                        Toast.LENGTH_LONG).show();
                    showPinDialog(); // Show dialog again
                } else {
                    setLockoutTime();
                    Toast.makeText(getContext(),
                        "Too many failed attempts. Locked for 5 minutes.",
                        Toast.LENGTH_LONG).show();
                    exitAdmin();
                }
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            exitAdmin();
        });

        builder.setCancelable(false);
        builder.show();
    }

    /**
     * Returns the access tier for an entered password, or {@link #ACCESS_NONE}.
     * Passwords are fixed in the build (only TFI changes them by shipping a new
     * version) — there is no on-terminal PIN change.
     */
    private int verifyPinTier(String enteredPin) {
        if (SUPER_ADMIN_PIN.equals(enteredPin)) return ACCESS_SUPER;
        if (NORMAL_ADMIN_PIN.equals(enteredPin)) return ACCESS_NORMAL;
        return ACCESS_NONE;
    }

    /** Any valid admin password (used by the kiosk-apply re-confirmation). */
    private boolean verifyPin(String enteredPin) {
        return verifyPinTier(enteredPin) != ACCESS_NONE;
    }

    /**
     * Enables/greys admin sections by access tier — restricted sections stay
     * VISIBLE but are disabled and dimmed, not hidden.
     * <ul>
     *   <li>Super Admin ({@code 8675309}) — everything active.</li>
     *   <li>Normal Admin ({@code 123456}) — only Reversal Management,
     *       View Transaction History, WiFi Configuration, and Diagnostics are
     *       active; all other sections are greyed out. The destructive "Clear"
     *       buttons stay Super-only (greyed for Normal).</li>
     * </ul>
     * Sections are flat siblings in the scroll column, each led by a header
     * with an id; {@link #setSectionEnabled} enables/dims the run of views
     * between one header and the next.
     */
    private void applyAccessLevel(int tier) {
        if (rootView == null) return;
        boolean sup = (tier == ACCESS_SUPER);

        // Fee / limits / terminal info / host are CasHUB-managed read-only text since
        // 6.2.9 — readable by both tiers; the only control there (Request New Working
        // Key) is for both tiers.
        setSectionEnabled(R.id.hdrFeeConfig,    R.id.hdrWithdrawal,   true);
        setSectionEnabled(R.id.hdrWithdrawal,   R.id.hdrTerminalInfo, true);
        setSectionEnabled(R.id.hdrTerminalInfo, R.id.hdrHostSettings, true);
        setSectionEnabled(R.id.hdrHostSettings, R.id.hdrReversal,     true);
        // Normal-admin sections — always active once authenticated
        setSectionEnabled(R.id.hdrReversal,     R.id.hdrHistory,      true);
        setSectionEnabled(R.id.hdrHistory,      R.id.hdrWifi,         true);
        setSectionEnabled(R.id.hdrWifi,         R.id.hdrDiagnostics,  true);
        setSectionEnabled(R.id.hdrDiagnostics,  R.id.hdrKiosk,        true);
        // Kiosk is the last section — 0 = "to end of column"
        setSectionEnabled(R.id.hdrKiosk,        0,                    sup);

        // Destructive "Clear" actions live inside the normal sections, so re-apply
        // them AFTER the section pass above: Super-only, greyed for Normal. (Per-record
        // reversal Resolve buttons are built in updateReversalStatus() with the tier.)
        setViewEnabledDimmed(rootView.findViewById(R.id.btnClearHistory), sup);
        updateReversalStatus();

        setViewEnabledDimmed(rootView.findViewById(R.id.btnRequestNewKey), true);
        renderManagedConfig();
        renderNetworkCard();
    }

    /**
     * Enables (or disables + dims) every direct child of the scroll column from
     * {@code startHeaderId} (inclusive) up to {@code endHeaderId} (exclusive);
     * {@code endHeaderId <= 0} means "to the end of the column". Views stay
     * visible either way.
     */
    private void setSectionEnabled(int startHeaderId, int endHeaderId, boolean enabled) {
        View start = rootView.findViewById(startHeaderId);
        if (start == null || !(start.getParent() instanceof ViewGroup)) return;
        ViewGroup col = (ViewGroup) start.getParent();
        int from = col.indexOfChild(start);
        if (from < 0) return;
        int to = col.getChildCount();
        if (endHeaderId > 0) {
            View end = rootView.findViewById(endHeaderId);
            if (end != null) {
                int ei = col.indexOfChild(end);
                if (ei >= 0) to = ei;
            }
        }
        for (int i = from; i < to; i++) {
            setViewEnabledDimmed(col.getChildAt(i), enabled);
        }
    }

    /**
     * Enables/disables a view and its entire subtree, dimming to 40% alpha when
     * disabled so a restricted control reads as "greyed out". Leaves visibility
     * untouched: a control the layout defaults to gone (the flat/percentage fee
     * sub-layouts, the Clear buttons) must stay hidden until its own logic shows
     * it — forcing VISIBLE here showed both fee layouts at once and surfaced the
     * Clear buttons with nothing to clear.
     */
    private void setViewEnabledDimmed(View v, boolean enabled) {
        if (v == null) return;
        v.setAlpha(enabled ? 1f : 0.4f);
        setViewTreeEnabled(v, enabled);
    }

    private void setViewTreeEnabled(View v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), enabled);
            }
        }
    }

    private boolean isLockedOut() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lockoutTime = prefs.getLong(KEY_LOCKOUT_TIME, 0);
        return System.currentTimeMillis() < lockoutTime;
    }

    private long getRemainingLockoutTime() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lockoutTime = prefs.getLong(KEY_LOCKOUT_TIME, 0);
        return Math.max(0, lockoutTime - System.currentTimeMillis());
    }

    private int getFailedAttempts() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_FAILED_ATTEMPTS, 0);
    }

    private void incrementFailedAttempts() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply();
    }

    private void resetFailedAttempts() {
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply();
    }

    private void setLockoutTime() {
        long lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
        // The lockout IS the penalty for the failed attempts, so the counter starts
        // over with it. It used to stay at MAX after the lockout expired (it was only
        // reset on success), so one mistype on the next visit gave remaining = -1 and
        // an immediate 5-minute relock — a permanent one-strike lockout. No attempt
        // is possible while locked (the PIN dialog is gated on isLockedOut()), so
        // resetting here cannot grant extra tries.
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LOCKOUT_TIME, lockoutUntil).putInt(KEY_FAILED_ATTEMPTS, 0).apply();
    }

    private void setContentVisible(boolean visible) {
        View scrollView = rootView.findViewById(R.id.scrollView);
        if (scrollView != null) {
            scrollView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ==================== Settings Management ====================

    private void loadSettings() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Migration: one-time PIN-key reset. MUST follow the build flavor — this
        // previously forced DUKPT at C000/0000 unconditionally, which broke the MKSK
        // build on every fresh install (PIN encrypt at C000/0000 → 0x2905 key not
        // exist; MKSK's master key lives at C000/0010).
        if (!prefs.getBoolean("migrated_to_dukpt_v2", false)) {
            boolean dukptMode = "DUKPT".equals(BuildConfig.KEY_MODE);
            Log.d(TAG, "Migrating settings: KEY_MODE=" + BuildConfig.KEY_MODE
                    + " → dukpt_enabled=" + dukptMode);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("dukpt_enabled", dukptMode);
            if (dukptMode) {
                editor.putInt("dukpt_key_set", 0x0000C000);    // DUKPT IPEK at C000/0000
                editor.putInt("dukpt_key_index", 0x00000000);
            }
            editor.putString("pin_block_format", "FORMAT0");
            editor.putBoolean("migrated_to_dukpt_v2", true);
            editor.apply();
            // Re-read prefs after migration
            prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        // 6.2.9: fee, limit and host values are CasHUB-managed — shown read-only, never
        // written from this screen.
        renderManagedConfig();

        // PIN encryption settings — key location depends on protocol (the protocol itself
        // comes from CasHUB's protocol_type; default HYOSUNG)
        GlobalPara.atmPinBlockFormat = prefs.getString("pin_block_format", "FORMAT0");

        // Build flavor determines DUKPT vs MKSK; key location depends on protocol
        GlobalPara.atmDukptEnabled = "DUKPT".equals(BuildConfig.KEY_MODE);
        if ("TRITON".equals(GlobalPara.atmProtocolType)) {
            GlobalPara.atmDukptKeySet = 0x0000CFFF;
            GlobalPara.atmDukptKeyIndex = 0x00000000;
        } else {
            GlobalPara.atmDukptKeySet = prefs.getInt("dukpt_key_set", 0x0000C000);
            GlobalPara.atmDukptKeyIndex = prefs.getInt("dukpt_key_index", 0x00000000);
        }
        GlobalPara.onlinePinKeySet = GlobalPara.atmDukptKeySet;
        GlobalPara.onlinePinKeyIndex = GlobalPara.atmDukptKeyIndex;
        Log.d(TAG, "Loaded PIN settings (" + GlobalPara.atmProtocolType + "/" +
                   BuildConfig.KEY_MODE + "): key=" +
                   String.format("0x%04X/0x%04X", GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));
    }

    /**
     * Renders the CasHUB-managed values read-only (6.2.9): processor as a CODE (never its
     * name), the port but never the host address, the terminal ID in full (field techs
     * quote it to the processor).
     */
    private void renderManagedConfig() {
        if (txvFeeConfig != null) {
            String fee = GlobalPara.atmUseFlatFee
                    ? "Flat fee: $" + Money.dollars(Money.toCents(GlobalPara.atmFlatFeeAmount))
                    : "Percentage fee: " + String.format(java.util.Locale.US, "%.2f", GlobalPara.atmPercentageFee) + " %";
            txvFeeConfig.setText(fee);
        }
        if (txvLimits != null) {
            txvLimits.setText("Minimum: $" + Money.dollars(Money.toCents(GlobalPara.atmMinAmount))
                    + "  ·  Maximum: $" + Money.dollars(Money.toCents(GlobalPara.atmMaxAmount)));
        }
        if (txvHostConfig != null) {
            boolean configured = GlobalPara.atmHostAddress != null && !GlobalPara.atmHostAddress.trim().isEmpty()
                    && GlobalPara.atmTerminalId != null && !GlobalPara.atmTerminalId.trim().isEmpty();
            if (!configured) {
                txvHostConfig.setText("Awaiting configuration from CasHUB\n(no host parameters have been pushed to this terminal)");
                txvHostConfig.setTextColor(0xFFD32F2F);
            } else {
                txvHostConfig.setText("Processor: " + ProcessorLabel.codeFor(GlobalPara.atmProcessorType)
                        + "  ·  Port " + GlobalPara.atmHostPort + (GlobalPara.atmUseTls ? "  ·  TLS" : "")
                        + "\nTerminal ID: " + GlobalPara.atmTerminalId);
                txvHostConfig.setTextColor(0xFF333333);
            }
        }
    }

    /** The Save button: persists everything on the page, POS-mode settings included. */
    private void saveSettings() {
        saveSettings(true);
    }

    /**
     * @param includePosSettings false for the IMPLICIT saves that Test Connection,
     *        Download Keys and Request New Working Key run before their host call.
     *        Those exist to persist the host settings they depend on; they must not
     *        also commit the POS-mode checkbox. That is how a POS-site terminal lost
     *        POS mode on 2026-09-18: a stray tap had unchecked "Enable POS Mode" and a
     *        later Request New Working Key silently persisted it.
     */
    private void saveSettings(boolean includePosSettings) {
        try {
            // 6.2.9: host, fee and limit values are CasHUB-managed and never written from
            // this screen. The only editable section left is POS mode.
            if (!includePosSettings) {
                Log.d(TAG, "saveSettings(false): nothing to persist — host/fee/limits are CasHUB-managed");
                return;
            }
            savePosSettings();
            // Same re-init the explicit Save always did, so a POS-mode change takes effect
            // without a reboot (idempotent when nothing changed).
            if (mainActivity != null) {
                new Thread(() -> {
                    try {
                        mainActivity.initializeAtmHostService();
                    } catch (Exception ex) {
                        Log.e(TAG, "Re-init failed after settings save: " + ex.getMessage());
                    }
                }, "SaveSettings-Reinit").start();
            }
            Toast.makeText(getContext(), "POS settings saved", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Settings saved (POS section; host/fee/limits are CasHUB-managed)");
        } catch (Exception e) {
            Log.e(TAG, "Error saving settings: " + e.getMessage());
            Toast.makeText(getContext(), "Error saving settings", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Network card (6.2.10): what the terminal is actually using right now. System APIs
     * that need no runtime permission, plus the APN the Castle settings service reports.
     * The APN lookup is a binder call, so the card is assembled on a worker thread.
     */
    private void renderNetworkCard() {
        if (txvNetwork == null || getContext() == null) return;
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                // Transport in use
                String transport = "none";
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network n = cm.getActiveNetwork();
                    android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                    if (caps != null) {
                        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) transport = "WiFi";
                        else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "Cellular";
                        else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "Ethernet";
                        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) transport += " (no internet)";
                    }
                }
                sb.append("In use: ").append(transport).append("\n");

                // Cellular
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) {
                    sb.append("Cellular: not available\n");
                } else {
                    boolean sim = tm.getSimState() == android.telephony.TelephonyManager.SIM_STATE_READY;
                    if (!sim) {
                        sb.append("SIM: none / not ready\n");
                    } else {
                        String simCarrier = String.valueOf(tm.getSimOperatorName());
                        String netCarrier = String.valueOf(tm.getNetworkOperatorName());
                        String data;
                        switch (tm.getDataState()) {
                            case android.telephony.TelephonyManager.DATA_CONNECTED:  data = "connected"; break;
                            case android.telephony.TelephonyManager.DATA_CONNECTING: data = "connecting"; break;
                            case android.telephony.TelephonyManager.DATA_SUSPENDED:  data = "suspended"; break;
                            default: data = "not connected"; break;
                        }
                        int bars = -1;
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 28 && tm.getSignalStrength() != null) bars = tm.getSignalStrength().getLevel();
                        } catch (Throwable ignore) {}
                        sb.append("SIM: ").append(simCarrier.isEmpty() ? "present" : simCarrier)
                          .append("  ·  Network: ").append(netCarrier.isEmpty() ? "not registered" : netCarrier)
                          .append("  ·  Data: ").append(data)
                          .append(bars >= 0 ? "  ·  Signal " + bars + "/4" : "").append("\n");
                        sb.append("APN in use: ").append(castech.emvtxn.net.ApnApplier.currentApnSummary()).append("\n");
                        castech.emvtxn.net.ApnApplier.Status st = castech.emvtxn.net.ApnApplier.readStatus(ctx);
                        if (st.attempted()) {
                            sb.append("CasHUB APN: ").append(st.desiredApn).append(" — ").append(st.result)
                              .append(" (").append(new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(new java.util.Date(st.at))).append(")\n");
                        }
                    }
                }

                // Battery present? (the strip shows "No batt" when the OS reports none)
                android.content.Intent bat = ctx.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                if (bat != null && !bat.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true)) {
                    sb.append("Battery: NOT PRESENT (check the pack is seated)\n");
                }
            } catch (Throwable t) {
                sb.append("Network state unavailable: ").append(t.getMessage()).append("\n");
            }
            final String text = sb.toString().trim();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> { if (txvNetwork != null) txvNetwork.setText(text); });
            }
        }, "AdminNetworkCard").start();
    }

    /** Super only (greyed for Normal in applyAccessLevel). Clears journal rows of CLOSED batches; the open batch is never touched. */
    private void clearClosedBatches() {
        if (accessLevel != ACCESS_SUPER) {
            Toast.makeText(getContext(), "Super Admin only", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getContext())
            .setTitle("Clear transaction history?")
            .setMessage("Deletes the journal rows of closed batches. The current (open) batch is kept so its Detail Report stays complete.")
            .setPositiveButton("Clear", (d, w) -> {
                try {
                    int n = castech.emvtxn.atm.TransactionLogManager
                            .getInstance(getContext().getApplicationContext()).clearClosedBatches();
                    Toast.makeText(getContext(), n + " row(s) cleared", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(getContext(), "Clear failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateTerminalInfo() {
        String info = "Terminal: S1F4 PRO\n" +
                     "App Version: " + MainActivity.APP_VERSION + "\n" +
                     "Serial: " + getDeviceSerial();
        if (txvTerminalInfo != null) {
            txvTerminalInfo.setText(info);
        }
    }

    private String getDeviceSerial() {
        if (GlobalPara.mainActivity != null) {
            String sn = GlobalPara.mainActivity.getHardwareSerialNumber();
            if (!sn.isEmpty()) return sn;
        }
        return "(unavailable)";
    }

    // ==================== Host Operations ====================

    private void updateStatus(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                txvHostStatus.setText("Status: " + message);
            });
        }
    }

    private void showError(String error) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                txvHostStatus.setText("Status: Error - " + error);
                txvHostStatus.setTextColor(0xFFFF0000);
                Toast.makeText(getContext(), "Key download failed: " + error, Toast.LENGTH_LONG).show();
            });
        }
    }

    private void requestNewWorkingKey() {
        String terminalId = GlobalPara.atmTerminalId == null ? "" : GlobalPara.atmTerminalId.trim();
        if (terminalId.isEmpty() || GlobalPara.atmHostAddress == null || GlobalPara.atmHostAddress.trim().isEmpty()) {
            Toast.makeText(getContext(), "Terminal not configured — push host parameters from CasHUB first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        txvHostStatus.setText("Status: Initializing...");
        txvHostStatus.setTextColor(0xFF666666);

        // Run on background thread to avoid ANR (KMS2 SDK calls can block)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mainActivity == null) {
                        showError("Main activity not available");
                        return;
                    }

                    // Always reinitialize to pick up current protocol/host settings
                    updateStatus("Initializing host service...");
                    mainActivity.initializeAtmHostService();
                    AtmHostService hostService = mainActivity.getAtmHostService();

                    if (hostService == null) {
                        showError("Host service not configured");
                        return;
                    }

                    // Refresh reversal status now that host is (re)initialized
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateReversalStatus());
                    }

                    updateStatus("Requesting new working key...");

                    // Set up listener
                    hostService.setEventListener(new AtmHostService.AtmEventListener() {
                        @Override
                        public void onProgress(String message) {
                            updateStatus(message);
                        }

                        @Override
                        public void onError(String error) {
                            showError(error);
                        }

                        @Override
                        public void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime,
                                long accountBalanceCents, long availableBalanceCents, String displayMessage) {}

                        @Override
                        public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) {}

                        @Override
                        public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {}

                        @Override
                        public void onKeysLoaded(String keyCheckValue) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    String kcvDisplay = keyCheckValue != null ? keyCheckValue : "N/A";
                                    txvHostStatus.setText("Status: New key loaded (KCV: " + kcvDisplay + ")");
                                    txvHostStatus.setTextColor(0xFF00AA00);
                                });
                            }
                        }

                        @Override
                        public void onReversalComplete(boolean success) {}

                        @Override
                        public void onHealthCheckResult(boolean success) {}

                        @Override
                        public void onHostTotalsReceived(castech.emvtxn.atm.host.HostTotalsResponse response) {}
                    });

                    // Clear current key and request new one
                    hostService.requestNewWorkingKey();

                } catch (Exception e) {
                    Log.e(TAG, "requestNewWorkingKey error: " + e.getMessage(), e);
                    showError("Error: " + e.getMessage());
                }
            }
        }).start();
    }

    // ==================== Diagnostics ====================

    private void testPrinter() {
        Toast.makeText(getContext(), "Testing printer...", Toast.LENGTH_SHORT).show();

        if (mainActivity != null) {
            try {
                MainActivity.CTOS_Printer printer = mainActivity.getPrinter();
                if (printer != null) {
                    // Build ONE page and print it in a single printf() call.
                    // (printf is self-contained: initPage + drawText + printPage.
                    // Calling it per-line printed a separate page each time, and
                    // goprintf() printed the legacy hard-coded SAMPLE RECEIPT.)
                    // This exercises the real receipt print path so the test shows
                    // exactly the font/bold/width a live receipt will have.
                    String test =
                        "================================\n" +
                        "         PRINTER TEST           \n" +
                        "================================\n" +
                        "\n" +
                        "ATM Version: " + MainActivity.APP_VERSION + "\n" +
                        "Date: " + new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
                                .format(new java.util.Date()) + "\n" +
                        "Terminal ID: " + GlobalPara.atmTerminalId + "\n" +
                        "--------------------------------\n" +
                        "Withdrawal Amount: $100.00\n" +
                        "Service Fee:       $3.00\n" +
                        "Total Charged:     $103.00\n" +
                        "TransID: TXN1786284020883\n" +
                        "================================\n" +
                        "\n\n\n";
                    printer.printf(test);
                    Toast.makeText(getContext(), "Printer test successful", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Printer not available", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Printer test error: " + e.getMessage());
                Toast.makeText(getContext(), "Printer error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void testCardReader() {
        // Repurposed as "EMV Diagnostics" button - scans keys and tests EMV cryptogram config
        Toast.makeText(getContext(), "Running EMV Diagnostics...", Toast.LENGTH_SHORT).show();

        if (mainActivity == null) {
            Toast.makeText(getContext(), "Error: Activity not available", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder fullReport = new StringBuilder();

        // 1. Run EMV Cryptogram Test (diagnoses missing 9F26, 9F27, etc.)
        try {
            fullReport.append("=== EMV CRYPTOGRAM DIAGNOSTICS ===\n\n");
            EmvCryptogramTest cryptogramTest = new EmvCryptogramTest(getContext());
            String cryptogramReport = cryptogramTest.runAllTests();
            fullReport.append(cryptogramReport);
            fullReport.append("\n");
        } catch (Exception e) {
            fullReport.append("EMV Cryptogram Test Error: " + e.getMessage() + "\n\n");
            Log.e(TAG, "EMV Cryptogram Test failed", e);
        }

        // 2. Scan for DUKPT keys (using CtKMS2Dukpt API)
        try {
            fullReport.append("\n=== DUKPT KEY SCAN ===\n\n");
            String dukptReport = mainActivity.scanDukptKeyLocations();
            fullReport.append(dukptReport);
            fullReport.append("\n");
        } catch (Exception e) {
            fullReport.append("DUKPT Scan Error: " + e.getMessage() + "\n\n");
        }

        // 3. Scan for regular keys (using CtKMS2Key API)
        try {
            fullReport.append("\n=== REGULAR KEY SCAN ===\n\n");
            AtmHostService hostService = mainActivity.getAtmHostService();
            CastleKeyManager keyManager = (hostService != null) ? hostService.getKeyManager() : null;

            if (keyManager == null) {
                // Create a temporary key manager just for scanning
                keyManager = new CastleKeyManager(getContext());
                keyManager.initialize();
            }

            String regularReport = keyManager.getKeyDiagnosticReport();
            fullReport.append(regularReport);
        } catch (Exception e) {
            fullReport.append("Regular Key Scan Error: " + e.getMessage() + "\n\n");
        }

        Log.d(TAG, fullReport.toString());

        // Show in an alert dialog (scrollable for long content)
        new AlertDialog.Builder(getContext())
            .setTitle("EMV Diagnostics Results")
            .setMessage(fullReport.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    // ==================== Reversal Management ====================

    /**
     * Updates the reversal status display.
     */
    private void updateReversalStatus() {
        if (txvReversalStatus == null) return;

        AtmHostService hostService = (mainActivity != null) ? mainActivity.getAtmHostService() : null;
        if (hostService == null || !hostService.isInitialized()) {
            txvReversalStatus.setText("Pending Reversals: N/A (Host not configured)");
            if (btnProcessReversals != null) btnProcessReversals.setEnabled(false);
            if (layReversalRecords != null) layReversalRecords.removeAllViews();
            return;
        }

        castech.emvtxn.atm.host.ReversalPersistenceManager mgr = hostService.getReversalManager();
        List<castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal> records =
                (mgr != null) ? mgr.getPendingReversals() : new ArrayList<>();
        AtmHostService.ReversalBacklog backlog = hostService.getReversalBacklog();
        castech.emvtxn.atm.host.ReversalGatePolicy.Decision gate = backlog.gate();

        String gateText;
        switch (gate.outcome) {
            case OUT_OF_SERVICE:   gateText = "OUT OF SERVICE — safety stop ("
                    + castech.emvtxn.atm.host.ReversalGatePolicy.SAFETY_STOP_FAILED_RECORDS
                    + "+ failed). Resolve or retry to restore service."; break;
            case WAIT_DRAIN_RUNNING: gateText = "Drain running — customers wait"; break;
            case WAIT_START_DRAIN:   gateText = "Active records — next customer starts the drain"; break;
            default:                 gateText = backlog.failedCount > 0
                    ? "In service — failed record(s) retried in background every 15 min"
                    : "In service"; break;
        }
        txvReversalStatus.setText("Pending Reversals: " + records.size()
                + " (active " + backlog.activeCount + ", failed " + backlog.failedCount + ")\n" + gateText);
        if (btnProcessReversals != null) {
            btnProcessReversals.setEnabled(!records.isEmpty() && !backlog.drainRunning);
        }
        renderReversalRecords(hostService, records);
    }

    /**
     * One card per pending record: when / seq / amount / status / attempts / last error,
     * with Retry (any admin) and Resolve (Super only, reason required, kept in history).
     */
    private void renderReversalRecords(final AtmHostService hostService,
                                       List<castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal> records) {
        if (layReversalRecords == null || getContext() == null) return;
        layReversalRecords.removeAllViews();
        final boolean isSuper = accessLevel == ACCESS_SUPER;
        final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US);
        for (final castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal rec : records) {
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(12, 8, 12, 8);
            card.setBackgroundColor(0xFFF5F5F5);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            card.setLayoutParams(lp);

            boolean failed = castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal.STATUS_FAILED
                    .equals(rec.getStatus());
            String last = rec.getLastAttemptTime() > 0 ? fmt.format(new java.util.Date(rec.getLastAttemptTime())) : "never";
            String err = (rec.getLastError() == null || rec.getLastError().isEmpty()) ? "—" : rec.getLastError();

            TextView head = new TextView(getContext());
            head.setTextSize(13);
            head.setTextColor(failed ? 0xFFD32F2F : 0xFF333333);
            head.setText(fmt.format(new java.util.Date(rec.getCreatedTime()))
                    + "  seq " + rec.getSequenceNumber()
                    + "  " + rec.getFormattedAmount()
                    + "  " + rec.getStatus().toUpperCase(java.util.Locale.US));
            card.addView(head);

            TextView detail = new TextView(getContext());
            detail.setTextSize(11);
            detail.setTextColor(0xFF666666);
            detail.setText("Attempts: " + rec.getAttemptCount() + "   Last: " + last
                    + (rec.getRetrievalReference() != null && !rec.getRetrievalReference().isEmpty()
                        ? "   RRN: " + rec.getRetrievalReference() : "")
                    + "\nLast error: " + err);
            card.addView(detail);

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button btnRetry = new Button(getContext());
            btnRetry.setText("Retry now");
            btnRetry.setTextSize(12);
            btnRetry.setOnClickListener(v -> retryReversal(hostService, rec));
            row.addView(btnRetry, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button btnResolve = new Button(getContext());
            btnResolve.setText("Resolve…");
            btnResolve.setTextSize(12);
            btnResolve.setEnabled(isSuper);
            btnResolve.setAlpha(isSuper ? 1f : 0.4f);
            btnResolve.setOnClickListener(v -> resolveReversal(hostService, rec));
            row.addView(btnResolve, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);

            layReversalRecords.addView(card);
        }
    }

    private void retryReversal(AtmHostService hostService,
                               castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal rec) {
        if (hostService.isTransactionInProgress()) {
            Toast.makeText(getContext(), "Transaction in progress — try again shortly", Toast.LENGTH_SHORT).show();
            return;
        }
        txvReversalStatus.setText("Retrying reversal seq " + rec.getSequenceNumber() + "…");
        if (btnProcessReversals != null) btnProcessReversals.setEnabled(false);
        hostService.retryReversal(rec.getTransactionId(), reversalUiCallback());
    }

    /**
     * Super-only. Removes the record from the pending list with a REQUIRED reason; it
     * stays in history (reason + who). Lifts the safety stop if the failed count drops
     * under it. This is the field escape hatch for a record the host will never accept.
     */
    private void resolveReversal(final AtmHostService hostService,
                                 final castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal rec) {
        if (accessLevel != ACCESS_SUPER) {
            Log.w(TAG, "resolveReversal refused — Super Admin only (tier=" + accessLevel + ")");
            Toast.makeText(getContext(), "Super Admin only", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Reason (required) — e.g. processor confirmed original declined");
        new AlertDialog.Builder(getContext())
            .setTitle("Resolve reversal seq " + rec.getSequenceNumber() + " (" + rec.getFormattedAmount() + ")")
            .setMessage("Removes this record from the pending list WITHOUT sending it. "
                    + "It stays in history with your reason.\n\n"
                    + "Only resolve after confirming with the processor that the original "
                    + "was declined or has already been reversed. Last error:\n"
                    + (rec.getLastError() == null ? "—" : rec.getLastError()))
            .setView(input)
            .setPositiveButton("Resolve", (dialog, which) -> {
                String reason = input.getText() == null ? "" : input.getText().toString().trim();
                if (reason.length() < 4) {
                    Toast.makeText(getContext(), "A reason is required", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean ok = hostService.resolveReversal(rec.getTransactionId(), reason, "super-admin");
                Toast.makeText(getContext(), ok ? "Reversal resolved — kept in history" : "Record not found",
                        Toast.LENGTH_SHORT).show();
                updateReversalStatus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Processes all pending reversals (FAILED ones included) on the drain executor.
     */
    private void processPendingReversals() {
        AtmHostService hostService = (mainActivity != null) ? mainActivity.getAtmHostService() : null;
        if (hostService == null || !hostService.isInitialized()) {
            Toast.makeText(getContext(), "Host service not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        int pendingCount = hostService.getPendingReversalCount();
        if (pendingCount == 0) {
            Toast.makeText(getContext(), "No pending reversals", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button during processing
        if (btnProcessReversals != null) btnProcessReversals.setEnabled(false);
        txvReversalStatus.setText("Processing " + pendingCount + " reversal(s)...");
        hostService.processPendingReversals(reversalUiCallback());
    }

    /** Shared UI callback for Process / Retry: shows WHY a record failed, not just a count. */
    private AtmHostService.ReversalProcessingCallback reversalUiCallback() {
        final List<String> failures = new ArrayList<>();
        return new AtmHostService.ReversalProcessingCallback() {
            @Override
            public void onProcessingReversal(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            txvReversalStatus.setText("Processing: " + reversal.getFormattedAmount()
                                    + " (seq " + reversal.getSequenceNumber() + ")"));
                }
            }

            @Override
            public void onReversalSuccess(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                Log.d(TAG, "Reversal success: " + reversal.getTransactionId());
            }

            @Override
            public void onReversalFailed(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal, String error) {
                Log.w(TAG, "Reversal failed: " + reversal.getTransactionId() + " - " + error);
                failures.add("seq " + reversal.getSequenceNumber() + ": " + error);
            }

            @Override
            public void onProcessingComplete(final int successCount, final int failCount) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateReversalStatus();
                        String message = successCount + " succeeded, " + failCount + " failed";
                        if (!failures.isEmpty()) message += "\n" + String.join("\n", failures);
                        Toast.makeText(getContext(), "Reversal processing: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            }
        };
    }

    private void applyKioskSettings() {
        boolean kioskEnabled = switchKioskMode != null && switchKioskMode.isChecked();
        boolean autoStart = switchAutoStart != null && switchAutoStart.isChecked();
        boolean screenOn = switchScreenAlwaysOn != null && switchScreenAlwaysOn.isChecked();
        boolean autoReboot = switchAutoReboot != null && switchAutoReboot.isChecked();

        // Confirm with password before applying
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Confirm Kiosk Settings");

        String message = kioskEnabled ?
            "This will:\n" +
            (autoStart ? "- Set ATM as default app (auto-start on boot)\n" : "") +
            "- " + (kioskEnabled ? "Disable" : "Enable") + " navigation buttons\n" +
            (screenOn ? "- Keep screen always on\n" : "") +
            (autoReboot ? "- Enable daily reboot at 3:00 AM\n" : "") +
            "\nEnter admin PIN to confirm:" :
            "This will DISABLE kiosk mode:\n" +
            "- Re-enable navigation buttons\n" +
            "- Remove auto-start\n" +
            "- Restore screen timeout\n" +
            "\nEnter admin PIN to confirm:";

        builder.setMessage(message);

        final EditText pinInput = new EditText(getContext());
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("Admin PIN");
        builder.setView(pinInput);

        builder.setPositiveButton("Apply", (dialog, which) -> {
            String pin = pinInput.getText().toString();
            if (verifyPin(pin)) {
                // Update GlobalPara
                GlobalPara.atmKioskMode = kioskEnabled;
                GlobalPara.atmAutoStartOnBoot = autoStart;
                GlobalPara.atmDisableNavButtons = kioskEnabled;
                GlobalPara.atmScreenAlwaysOn = screenOn;
                GlobalPara.atmAutoReboot = autoReboot;

                // Apply via CtSettings on real hardware
                if (mainActivity != null && !mainActivity.isEmulator()) {
                    try {
                        CTOS.CtSettings ctSettings = new CTOS.CtSettings();

                        if (kioskEnabled) {
                            // Enable kiosk
                            if (autoStart) {
                                ctSettings.setDefaultApp(mainActivity.getPackageName());
                            }
                            ctSettings.setNavigation(false, false, false);
                            if (screenOn) {
                                ctSettings.setScreenTimeOut(0);
                            }
                            if (autoReboot) {
                                ctSettings.setAutoRebootTime(GlobalPara.atmAutoRebootHour, GlobalPara.atmAutoRebootMinute);
                            }
                        } else {
                            // Disable kiosk - restore defaults
                            ctSettings.setDefaultApp("");
                            ctSettings.setNavigation(true, true, true);
                            ctSettings.setScreenTimeOut(300 * 1000); // 5 min timeout
                        }

                        Log.d(TAG, "Kiosk settings applied: mode=" + kioskEnabled);
                        Toast.makeText(getContext(), "Kiosk settings applied", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Error applying kiosk settings: " + e.getMessage());
                        Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Log.d(TAG, "Kiosk settings saved (emulator - not applied to hardware)");
                    Toast.makeText(getContext(), "Settings saved (emulator mode)", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getContext(), "Invalid PIN", Toast.LENGTH_SHORT).show();
                // Revert switches
                if (switchKioskMode != null) switchKioskMode.setChecked(GlobalPara.atmKioskMode);
                if (switchAutoStart != null) switchAutoStart.setChecked(GlobalPara.atmAutoStartOnBoot);
                if (switchScreenAlwaysOn != null) switchScreenAlwaysOn.setChecked(GlobalPara.atmScreenAlwaysOn);
                if (switchAutoReboot != null) switchAutoReboot.setChecked(GlobalPara.atmAutoReboot);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            // Revert switches
            if (switchKioskMode != null) switchKioskMode.setChecked(GlobalPara.atmKioskMode);
            if (switchAutoStart != null) switchAutoStart.setChecked(GlobalPara.atmAutoStartOnBoot);
            if (switchScreenAlwaysOn != null) switchScreenAlwaysOn.setChecked(GlobalPara.atmScreenAlwaysOn);
            if (switchAutoReboot != null) switchAutoReboot.setChecked(GlobalPara.atmAutoReboot);
        });

        builder.show();
    }

    private void exitAdmin() {
        isAuthenticated = false;
        accessLevel = ACCESS_NONE;
        if (mainActivity != null) {
            mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
        }
    }

    // ---- POS Mode settings ----------------------------------------------------

    /** Populates the POS Mode controls from {@link castech.emvtxn.pos.PosConfig}. */
    private void loadPosSettings() {
        if (posConfig == null) return;
        if (cbEnablePosMode != null)  cbEnablePosMode.setChecked(posConfig.isEnabled());
        if (edtPosProxyUrl != null)   edtPosProxyUrl.setText(posConfig.getProxyBaseUrl());
        if (edtPosAccessKey != null)  edtPosAccessKey.setText(posConfig.getTerminalAccessKey());
        if (txvPosStatus != null) {
            String state = (mainActivity != null && mainActivity.getPosOrchestratorState() != null)
                    ? mainActivity.getPosOrchestratorState() : "not running";
            boolean jwtExpired = posConfig.isJwtExpired();
            int pending = (mainActivity != null && mainActivity.getAtmHostService() != null)
                    ? mainActivity.getAtmHostService().getPendingReversalCount() : 0;
            txvPosStatus.setText("POS state: " + state
                    + " | JWT: " + (jwtExpired ? "expired/missing" : "cached")
                    + " | pending reversals: " + pending);
        }
    }

    /**
     * Persists the POS Mode controls to {@link castech.emvtxn.pos.PosConfig}.
     * Changes take effect on next app restart (orchestrator boots from
     * MainActivity.startPosModeIfEnabled at startup).
     */
    private void savePosSettings() {
        if (posConfig == null) return;
        // A greyed control is read-only for this tier (the POS section is Super-only);
        // its state is never persisted, whatever it happens to hold.
        if (edtPosProxyUrl != null && edtPosProxyUrl.isEnabled()) {
            posConfig.setProxyBaseUrl(edtPosProxyUrl.getText().toString().trim());
        }
        if (edtPosAccessKey != null && edtPosAccessKey.isEnabled()) {
            posConfig.setTerminalAccessKey(edtPosAccessKey.getText().toString().trim());
        }
        if (cbEnablePosMode != null && cbEnablePosMode.isEnabled()) {
            boolean wasEnabled = posConfig.isEnabled();
            boolean nowEnabled = cbEnablePosMode.isChecked();
            posConfig.setEnabled(nowEnabled);
            if (wasEnabled != nowEnabled) {
                // Loud and attributable: this flips the terminal between POS-driven
                // and walk-up operation.
                Log.w(TAG, "POS Mode toggled " + (nowEnabled ? "ON" : "OFF")
                        + " by admin tier=" + (accessLevel == ACCESS_SUPER ? "SUPER" : "NORMAL")
                        + " via Save Settings — restart required");
            }
        }
    }
}
