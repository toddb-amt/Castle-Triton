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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.security.MessageDigest;
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
    private static final String KEY_PIN_HASH = "admin_pin_hash";
    private static final String KEY_PIN_SALT = "admin_pin_salt";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT_TIME = "lockout_time";
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    private static final String DEFAULT_PIN = "123456";

    private static MainActivity mainActivity = null;
    private View rootView;
    private boolean isAuthenticated = false;
    private boolean isUserVisible = false;  // Track actual user visibility from setMenuVisibility

    // UI Elements - Fee Configuration
    private RadioGroup rgFeeType;
    private RadioButton rbFlatFee;
    private RadioButton rbPercentageFee;
    private LinearLayout layoutFlatFee;
    private LinearLayout layoutPercentageFee;
    private EditText edtFlatFee;
    private EditText edtPercentageFee;

    // UI Elements - Withdrawal Limits
    private EditText edtMinAmount;
    private EditText edtMaxAmount;

    // UI Elements - Host Settings
    private Spinner spinnerProcessorType;
    private Spinner spinnerProtocolType;
    private EditText edtHostAddress;
    private EditText edtHostPort;
    private EditText edtTerminalId;
    private android.widget.CheckBox chkUseTls;
    private TextView txvHostStatus;

    // UI Elements - Terminal Info
    private TextView txvTerminalInfo;

    // UI Elements - Buttons
    private Button btnTestConnection;
    private Button btnDownloadKeys;
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
    private static final int REQ_WIFI_SCAN_PERMISSION = 4711;
    private Button btnTestCardReader;
    private Button btnSaveSettings;
    private Button btnExit;

    // UI Elements - Reversal Management
    private TextView txvReversalStatus;
    private Button btnProcessReversals;
    private Button btnClearReversals;

    // UI Elements - POS Mode (semi-integrated proxy)
    private CheckBox cbEnablePosMode;
    private EditText edtPosProxyUrl;
    private EditText edtPosAccessKey;
    private TextView txvPosStatus;
    private castech.emvtxn.pos.PosConfig posConfig;

    // Processor list
    private List<String> processorList = new ArrayList<>();

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
        // Fee Configuration
        rgFeeType = rootView.findViewById(R.id.rgFeeType);
        rbFlatFee = rootView.findViewById(R.id.rbFlatFee);
        rbPercentageFee = rootView.findViewById(R.id.rbPercentageFee);
        layoutFlatFee = rootView.findViewById(R.id.layoutFlatFee);
        layoutPercentageFee = rootView.findViewById(R.id.layoutPercentageFee);
        edtFlatFee = rootView.findViewById(R.id.edtFlatFee);
        edtPercentageFee = rootView.findViewById(R.id.edtPercentageFee);

        // Withdrawal Limits
        edtMinAmount = rootView.findViewById(R.id.edtMinAmount);
        edtMaxAmount = rootView.findViewById(R.id.edtMaxAmount);

        // Host Settings
        spinnerProcessorType = rootView.findViewById(R.id.spinnerProcessorType);
        spinnerProtocolType = rootView.findViewById(R.id.spinnerProtocolType);
        edtHostAddress = rootView.findViewById(R.id.edtHostAddress);
        edtHostPort = rootView.findViewById(R.id.edtHostPort);
        edtTerminalId = rootView.findViewById(R.id.edtTerminalId);
        chkUseTls = rootView.findViewById(R.id.chkUseTls);
        txvHostStatus = rootView.findViewById(R.id.txvHostStatus);

        // Terminal Info
        txvTerminalInfo = rootView.findViewById(R.id.txvTerminalInfo);

        // Buttons
        btnTestConnection = rootView.findViewById(R.id.btnTestConnection);
        btnDownloadKeys = rootView.findViewById(R.id.btnDownloadKeys);
        btnRequestNewKey = rootView.findViewById(R.id.btnRequestNewKey);
        btnTestPrinter = rootView.findViewById(R.id.btnTestPrinter);
        btnTestCardReader = rootView.findViewById(R.id.btnTestCardReader);
        btnSaveSettings = rootView.findViewById(R.id.btnSaveSettings);
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
        btnClearReversals = rootView.findViewById(R.id.btnClearReversals);

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
        setupWifiSection();

        // Setup processor and protocol spinners
        setupProcessorSpinner();
        setupProtocolSpinner();
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

    private void setupProcessorSpinner() {
        // Default processor list - will be replaced by remote config
        processorList.clear();
        processorList.add("Select Processor...");
        processorList.add("EFX Financial");
        processorList.add("DNS Payment Network");
        processorList.add("Switch Commerce");
        processorList.add("FIS");
        processorList.add("Cardtronics");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            getContext(),
            android.R.layout.simple_spinner_item,
            processorList
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProcessorType.setAdapter(adapter);
    }

    private void setupProtocolSpinner() {
        List<String> protocolList = new ArrayList<>();
        protocolList.add("Hyosung STD1");
        protocolList.add("Triton Standard");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            getContext(),
            android.R.layout.simple_spinner_item,
            protocolList
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProtocolType.setAdapter(adapter);

        // Set current selection based on GlobalPara
        if ("TRITON".equals(GlobalPara.atmProtocolType)) {
            spinnerProtocolType.setSelection(1);
        } else {
            spinnerProtocolType.setSelection(0);
        }
    }

    private void setupListeners() {
        // Fee type radio group
        rgFeeType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbFlatFee) {
                layoutFlatFee.setVisibility(View.VISIBLE);
                layoutPercentageFee.setVisibility(View.GONE);
            } else {
                layoutFlatFee.setVisibility(View.GONE);
                layoutPercentageFee.setVisibility(View.VISIBLE);
            }
        });

        // Test Connection button
        btnTestConnection.setOnClickListener(v -> testConnection());

        // Download Keys button
        btnDownloadKeys.setOnClickListener(v -> downloadKeys());

        // Request New Key button
        btnRequestNewKey.setOnClickListener(v -> requestNewWorkingKey());

        // Test Printer button
        btnTestPrinter.setOnClickListener(v -> testPrinter());

        // Test Card Reader button
        btnTestCardReader.setOnClickListener(v -> testCardReader());

        // Save Settings button
        btnSaveSettings.setOnClickListener(v -> saveSettings());

        // Exit button
        btnExit.setOnClickListener(v -> exitAdmin());

        // Reversal Management buttons
        if (btnProcessReversals != null) {
            btnProcessReversals.setOnClickListener(v -> processPendingReversals());
        }
        if (btnClearReversals != null) {
            btnClearReversals.setOnClickListener(v -> clearPendingReversals());
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
        input.setHint("Enter 4-6 digit PIN");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String enteredPin = input.getText().toString();
            if (verifyPin(enteredPin)) {
                isAuthenticated = true;
                resetFailedAttempts();
                setContentVisible(true);
                Toast.makeText(getContext(), "Access granted", Toast.LENGTH_SHORT).show();
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

    private boolean verifyPin(String enteredPin) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(KEY_PIN_HASH, null);

        if (storedHash == null) {
            // W4 fix: First time — accept default PIN but force change
            if (enteredPin.equals(DEFAULT_PIN)) {
                // Force PIN change on first authentication
                promptChangePin();
                return true;
            }
            return false;
        }

        String salt = prefs.getString(KEY_PIN_SALT, "");
        String enteredHash = hashPin(enteredPin, salt);
        return storedHash.equals(enteredHash);
    }

    private void promptChangePin() {
        new AlertDialog.Builder(getContext())
            .setTitle("Change Default PIN")
            .setMessage("Default PIN detected. Would you like to set a new PIN?")
            .setPositiveButton("Yes", (dialog, which) -> showChangePinDialog())
            .setNegativeButton("Later", null)
            .show();
    }

    private void showChangePinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Set New Admin PIN");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText inputNew = new EditText(getContext());
        inputNew.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputNew.setHint("New PIN (4-6 digits)");
        layout.addView(inputNew);

        final EditText inputConfirm = new EditText(getContext());
        inputConfirm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputConfirm.setHint("Confirm PIN");
        layout.addView(inputConfirm);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newPin = inputNew.getText().toString();
            String confirmPin = inputConfirm.getText().toString();

            if (newPin.length() < 4 || newPin.length() > 6) {
                Toast.makeText(getContext(), "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!newPin.equals(confirmPin)) {
                Toast.makeText(getContext(), "PINs do not match", Toast.LENGTH_SHORT).show();
                return;
            }

            saveNewPin(newPin);
            Toast.makeText(getContext(), "PIN changed successfully", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void saveNewPin(String pin) {
        String salt = generateSalt();
        String hash = hashPin(pin, salt);

        SharedPreferences.Editor editor = getContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_PIN_HASH, hash);
        editor.putString(KEY_PIN_SALT, salt);
        editor.apply();
    }

    private String hashPin(String pin, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + pin).getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // S7 fix: Fail explicitly — never store/compare plaintext PIN
            Log.e(TAG, "Hash error: " + e.getMessage());
            throw new RuntimeException("SHA-256 unavailable — cannot hash PIN securely", e);
        }
    }

    private String generateSalt() {
        // W5 fix: Use SecureRandom instead of predictable timestamp
        byte[] saltBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(saltBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : saltBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LOCKOUT_TIME, lockoutUntil).apply();
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

        // Fee settings - prefer GlobalPara if already loaded by MainActivity
        boolean isFlatFee = GlobalPara.atmUseFlatFee; // Use GlobalPara (loaded on startup)
        if (isFlatFee) {
            rbFlatFee.setChecked(true);
            layoutFlatFee.setVisibility(View.VISIBLE);
            layoutPercentageFee.setVisibility(View.GONE);
        } else {
            rbPercentageFee.setChecked(true);
            layoutFlatFee.setVisibility(View.GONE);
            layoutPercentageFee.setVisibility(View.VISIBLE);
        }

        // Prefer GlobalPara values if they've been set (non-default)
        if (GlobalPara.atmFlatFeeAmount > 0) {
            edtFlatFee.setText(String.format("%.2f", GlobalPara.atmFlatFeeAmount));
            edtPercentageFee.setText(String.format("%.1f", GlobalPara.atmPercentageFee));
        } else {
            edtFlatFee.setText(prefs.getString("fee_flat_amount", "3.00"));
            edtPercentageFee.setText(prefs.getString("fee_percentage", "0.0"));
        }

        // Withdrawal limits - prefer GlobalPara if already loaded by MainActivity
        if (GlobalPara.atmMinAmount > 0) {
            edtMinAmount.setText(String.format("%.2f", GlobalPara.atmMinAmount));
            edtMaxAmount.setText(String.format("%.2f", GlobalPara.atmMaxAmount));
        } else {
            edtMinAmount.setText(prefs.getString("limit_min", "20.00"));
            edtMaxAmount.setText(prefs.getString("limit_max", "500.00"));
        }

        // Host settings - prefer GlobalPara if already loaded by MainActivity
        if (GlobalPara.atmHostAddress != null && !GlobalPara.atmHostAddress.isEmpty()) {
            Log.d(TAG, "Using GlobalPara values (loaded on startup)");
            // Set processor spinner based on GlobalPara.atmProcessorType
            int processorIndex = getSpinnerIndexFromProcessorType(GlobalPara.atmProcessorType);
            if (processorIndex >= 0 && processorIndex < spinnerProcessorType.getCount()) {
                spinnerProcessorType.setSelection(processorIndex);
            }
            edtHostAddress.setText(GlobalPara.atmHostAddress);
            edtHostPort.setText(String.valueOf(GlobalPara.atmHostPort));
            edtTerminalId.setText(GlobalPara.atmTerminalId);
        } else {
            Log.d(TAG, "Using SharedPreferences values (first run or no settings)");
            int processorIndex = prefs.getInt("processor_index", 0);
            if (processorIndex < spinnerProcessorType.getCount()) {
                spinnerProcessorType.setSelection(processorIndex);
            }
            // Load protocol selection
            int protocolIndex = prefs.getInt("protocol_index", 0);
            if (protocolIndex < spinnerProtocolType.getCount()) {
                spinnerProtocolType.setSelection(protocolIndex);
            }
            // Apply protocol type to GlobalPara immediately
            GlobalPara.atmProtocolType = protocolIndex == 1 ? "TRITON" : "HYOSUNG";

            edtHostAddress.setText(prefs.getString("host_address", ""));
            edtHostPort.setText(prefs.getString("host_port", "9057"));
            edtTerminalId.setText(prefs.getString("terminal_id", ""));
        }

        if (chkUseTls != null) {
            chkUseTls.setChecked(prefs.getBoolean("use_tls", true));
        }

        // PIN encryption settings — key location depends on protocol
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

        // Also update GlobalPara
        updateGlobalPara();
    }

    private void saveSettings() {
        try {
            SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();

            // Fee settings
            editor.putBoolean("fee_is_flat", rbFlatFee.isChecked());
            editor.putString("fee_flat_amount", edtFlatFee.getText().toString());
            editor.putString("fee_percentage", edtPercentageFee.getText().toString());

            // Withdrawal limits
            editor.putString("limit_min", edtMinAmount.getText().toString());
            editor.putString("limit_max", edtMaxAmount.getText().toString());

            // Host settings
            editor.putInt("processor_index", spinnerProcessorType.getSelectedItemPosition());
            editor.putInt("protocol_index", spinnerProtocolType.getSelectedItemPosition());
            editor.putString("host_address", edtHostAddress.getText().toString().trim());
            editor.putString("host_port", edtHostPort.getText().toString().trim());
            editor.putString("terminal_id", edtTerminalId.getText().toString().trim());
            if (chkUseTls != null) {
                editor.putBoolean("use_tls", chkUseTls.isChecked());
            }

            // DUKPT settings - auto-enable for processors that require Format 0
            String processorType = getProcessorTypeFromSpinner(spinnerProcessorType.getSelectedItemPosition());
            boolean dukptNeeded = "EFX".equals(processorType) ||
                                  "SWITCH_COMMERCE".equals(processorType) ||
                                  "CARDTRONICS".equals(processorType);

            editor.putBoolean("dukpt_enabled", GlobalPara.atmDukptEnabled);
            editor.putString("pin_block_format", GlobalPara.atmPinBlockFormat);
            editor.putInt("dukpt_key_set", GlobalPara.atmDukptKeySet);
            editor.putInt("dukpt_key_index", GlobalPara.atmDukptKeyIndex);

            Log.d(TAG, "Saved DUKPT settings: enabled=" + GlobalPara.atmDukptEnabled +
                       ", format=" + GlobalPara.atmPinBlockFormat +
                       ", processor=" + processorType + " (DUKPT needed: " + dukptNeeded + ")");

            editor.apply();

            // Persist POS Mode settings (own SharedPreferences file via PosConfig)
            savePosSettings();

            // Update GlobalPara
            updateGlobalPara();

            // Also persist via AtmSettingsManager for app restart
            if (mainActivity != null) {
                AtmSettingsManager settingsManager = mainActivity.getAtmSettingsManager();
                if (settingsManager != null) {
                    settingsManager.saveSettings();
                    Log.d(TAG, "Settings also persisted via AtmSettingsManager");
                }
            }

            // Re-initialize the host service so the new settings (processor, host
            // URL/port, TLS, terminal ID, DUKPT mode, etc.) take effect immediately.
            // Without this, the live AtmHostService keeps the old config (or remains
            // null if no service was ever initialized) and the next transaction
            // either hits the wrong host or fails with "AtmHostService not available".
            // Matches what the existing Test Connection button already does.
            if (mainActivity != null) {
                Log.d(TAG, "Settings saved — re-initializing host service so new config takes effect");
                new Thread(() -> {
                    try {
                        mainActivity.initializeAtmHostService();
                    } catch (Exception ex) {
                        Log.e(TAG, "Host re-init failed after settings save: " + ex.getMessage());
                    }
                }, "SaveSettings-HostReinit").start();
            }

            Toast.makeText(getContext(), "Settings saved — reloading host…", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Settings saved successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error saving settings: " + e.getMessage());
            Toast.makeText(getContext(), "Error saving settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateGlobalPara() {
        try {
            // Update fee settings
            if (rbFlatFee.isChecked()) {
                GlobalPara.atmUseFlatFee = true;
                GlobalPara.atmFlatFeeAmount = Double.parseDouble(edtFlatFee.getText().toString());
            } else {
                GlobalPara.atmUseFlatFee = false;
                GlobalPara.atmPercentageFee = Double.parseDouble(edtPercentageFee.getText().toString());
            }

            // Update limits
            GlobalPara.atmMinAmount = Double.parseDouble(edtMinAmount.getText().toString());
            GlobalPara.atmMaxAmount = Double.parseDouble(edtMaxAmount.getText().toString());

            // Update host settings
            GlobalPara.atmHostAddress = edtHostAddress.getText().toString().trim();
            GlobalPara.atmHostPort = Integer.parseInt(edtHostPort.getText().toString().trim());
            GlobalPara.atmTerminalId = edtTerminalId.getText().toString().trim();
            if (chkUseTls != null) {
                GlobalPara.atmUseTls = chkUseTls.isChecked();
            }

            // Update processor type based on spinner selection
            GlobalPara.atmProcessorType = getProcessorTypeFromSpinner(spinnerProcessorType.getSelectedItemPosition());

            // Update protocol type based on spinner selection
            GlobalPara.atmProtocolType = spinnerProtocolType.getSelectedItemPosition() == 1 ? "TRITON" : "HYOSUNG";

            Log.d(TAG, "GlobalPara updated - Processor: " + GlobalPara.atmProcessorType +
                      ", Protocol: " + GlobalPara.atmProtocolType +
                      ", Host: " + GlobalPara.atmHostAddress + ":" + GlobalPara.atmHostPort +
                      ", TLS: " + GlobalPara.atmUseTls);
        } catch (Exception e) {
            Log.e(TAG, "Error updating GlobalPara: " + e.getMessage());
        }
    }

    /**
     * Convert spinner index to processor type code.
     * Index 0 = "Select Processor..." (invalid)
     * Index 1 = "EFX Financial" -> "EFX"
     * Index 2 = "DNS Payment Network" -> "DNS"
     * Index 3 = "Switch Commerce" -> "SWITCH_COMMERCE"
     * Index 4 = "FIS" -> "FIS"
     * Index 5 = "Cardtronics" -> "CARDTRONICS"
     */
    private String getProcessorTypeFromSpinner(int index) {
        switch (index) {
            case 1: return "EFX";
            case 2: return "DNS";
            case 3: return "SWITCH_COMMERCE";
            case 4: return "FIS";
            case 5: return "CARDTRONICS";
            default: return "EFX"; // Default to EFX
        }
    }

    /**
     * Convert processor type code to spinner index.
     */
    private int getSpinnerIndexFromProcessorType(String processorType) {
        if (processorType == null) return 1; // Default to EFX
        switch (processorType.toUpperCase()) {
            case "EFX": return 1;
            case "DNS": return 2;
            case "SWITCH_COMMERCE": return 3;
            case "FIS": return 4;
            case "CARDTRONICS": return 5;
            default: return 1; // Default to EFX
        }
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

    private void testConnection() {
        Log.d(TAG, "testConnection() called");
        Toast.makeText(getContext(), "Testing connection...", Toast.LENGTH_SHORT).show();

        String host = edtHostAddress.getText().toString().trim();
        String port = edtHostPort.getText().toString().trim();

        Log.d(TAG, "testConnection: host=" + host + ", port=" + port);

        if (host.isEmpty() || port.isEmpty()) {
            Toast.makeText(getContext(), "Please enter host address and port", Toast.LENGTH_SHORT).show();
            return;
        }

        // Validate port number
        int portNum;
        try {
            portNum = Integer.parseInt(port);
            if (portNum < 1 || portNum > 65535) {
                Toast.makeText(getContext(), "Port must be 1-65535", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid port number", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save settings first so they're available to host service
        saveSettings();

        txvHostStatus.setText("Status: Initializing host service...");
        txvHostStatus.setTextColor(0xFF666666);

        Log.d(TAG, "testConnection: mainActivity=" + (mainActivity != null ? "OK" : "NULL"));

        if (mainActivity == null) {
            Log.e(TAG, "testConnection: mainActivity is NULL!");
            txvHostStatus.setText("Status: Internal error - activity null");
            txvHostStatus.setTextColor(0xFFFF0000);
            Toast.makeText(getContext(), "Internal error", Toast.LENGTH_SHORT).show();
            return;
        }

        // Initialize/reinitialize host service with current settings
        // Force update GlobalPara from current UI values right before init
        updateGlobalPara();
        Log.d(TAG, "testConnection: GlobalPara host=" + GlobalPara.atmHostAddress +
                   ", port=" + GlobalPara.atmHostPort + ", terminalId=" + GlobalPara.atmTerminalId);
        Log.d(TAG, "testConnection: calling initializeAtmHostService()");
        mainActivity.initializeAtmHostService();
        final AtmHostService hostService = mainActivity.getAtmHostService();

        // Refresh reversal status — host service now (re)initialized
        updateReversalStatus();

        Log.d(TAG, "testConnection: hostService=" + (hostService != null ? "OK" : "NULL"));

        if (hostService == null) {
            Log.e(TAG, "testConnection: hostService is NULL after init");
            txvHostStatus.setText("Status: Failed to initialize host service");
            txvHostStatus.setTextColor(0xFFFF0000);
            Toast.makeText(getContext(), "Failed to initialize host service", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "testConnection: setting status to Connecting...");
        txvHostStatus.setText("Status: Connecting to " + host + ":" + portNum + "...");

        // Track connection state
        final boolean[] connectionComplete = {false};
        connectionHandler = new android.os.Handler();

        // Set up listener for connection result (track for cleanup)
        currentEventListener = new AtmHostService.AtmEventListener() {
            @Override
            public void onProgress(String message) {
                Log.d(TAG, "onProgress: " + message);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        txvHostStatus.setText("Status: " + message);
                        txvHostStatus.setTextColor(0xFF666666);
                    });
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "onError: " + error);
                connectionComplete[0] = true;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        txvHostStatus.setText("Status: Error - " + error);
                        txvHostStatus.setTextColor(0xFFFF0000);
                        Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime,
                    long accountBalanceCents, long availableBalanceCents, String displayMessage) {}

            @Override
            public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) {}

            @Override
            public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {}

            @Override
            public void onKeysLoaded(String keyCheckValue) {}

            @Override
            public void onReversalComplete(boolean success) {}

            @Override
            public void onHealthCheckResult(boolean success) {
                Log.d(TAG, "onHealthCheckResult: " + success);
                connectionComplete[0] = true;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (success) {
                            txvHostStatus.setText("Status: Connected - Host OK");
                            txvHostStatus.setTextColor(0xFF00AA00);
                            Toast.makeText(getContext(), "Connection successful!", Toast.LENGTH_SHORT).show();
                        } else {
                            txvHostStatus.setText("Status: Connected but health check failed");
                            txvHostStatus.setTextColor(0xFFFF6600);
                            Toast.makeText(getContext(), "Health check failed", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            @Override
            public void onHostTotalsReceived(castech.emvtxn.atm.host.HostTotalsResponse response) {
                // Not used in connection test
            }
        };
        hostService.setEventListener(currentEventListener);

        // Start connection
        Log.d(TAG, "testConnection: calling hostService.connect()");
        hostService.connect();

        // Poll for connection status and send health check when ready
        // Retry every 2 seconds for up to 15 seconds
        final int[] attempts = {0};
        final int maxAttempts = 8; // 8 * 2 = 16 seconds max

        connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                attempts[0]++;
                Log.d(TAG, "checkConnection: attempt " + attempts[0] + ", connected=" + hostService.isConnected());

                if (connectionComplete[0]) {
                    // Already got result through callback
                    Log.d(TAG, "checkConnection: connection complete via callback");
                    return;
                }

                if (hostService.isConnected()) {
                    Log.d(TAG, "checkConnection: connected, sending status monitoring (H0)");
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            txvHostStatus.setText("Status: Connected, verifying...");
                        });
                    }
                    // Use status monitoring (H0) for EFX/Switch Commerce style processors
                    hostService.sendStatusMonitoring();
                } else if (attempts[0] < maxAttempts) {
                    // Not connected yet, update UI and try again
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            txvHostStatus.setText("Status: Connecting... (" + (attempts[0] * 2) + "s)");
                        });
                    }
                    connectionHandler.postDelayed(this, 2000);
                } else {
                    // Timeout
                    Log.e(TAG, "checkConnection: timeout after " + (maxAttempts * 2) + " seconds");
                    connectionComplete[0] = true;
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            txvHostStatus.setText("Status: Connection timeout");
                            txvHostStatus.setTextColor(0xFFFF0000);
                            Toast.makeText(getContext(), "Connection timeout - check host/port", Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }
        };

        // Start checking after initial delay
        connectionHandler.postDelayed(connectionCheckRunnable, 2000);
    }

    private void downloadKeys() {
        String terminalId = edtTerminalId.getText().toString();
        if (terminalId.isEmpty()) {
            Toast.makeText(getContext(), "Please enter Terminal ID first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure settings are saved (this is fast, OK on main thread)
        saveSettings();

        txvHostStatus.setText("Status: Initializing...");
        txvHostStatus.setTextColor(0xFF666666);

        // Run initialization and download on background thread to avoid ANR
        // KMS2 SDK calls can block, so must not be on main thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mainActivity == null) {
                        showError("Main activity not available");
                        return;
                    }

                    AtmHostService hostService = mainActivity.getAtmHostService();

                    if (hostService == null) {
                        // Initialize on background thread (KMS2 calls can block)
                        updateStatus("Initializing host service...");
                        mainActivity.initializeAtmHostService();
                        hostService = mainActivity.getAtmHostService();
                        // Refresh reversal status now that host is initialized
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> updateReversalStatus());
                        }
                    }

                    if (hostService == null) {
                        showError("Host service not configured");
                        return;
                    }

                    updateStatus("Downloading keys (Type 88)...");

                    final AtmHostService service = hostService;

                    // Set up listener for key download result
                    service.setEventListener(new AtmHostService.AtmEventListener() {
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
                                    txvHostStatus.setText("Status: Keys loaded (KCV: " + kcvDisplay + ")");
                                    txvHostStatus.setTextColor(0xFF00AA00);
                                    Toast.makeText(getContext(), "Working key loaded successfully", Toast.LENGTH_SHORT).show();
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

                    // Download keys (this is already async internally)
                    service.downloadKeys();

                } catch (Exception e) {
                    Log.e(TAG, "downloadKeys error: " + e.getMessage(), e);
                    showError("Error: " + e.getMessage());
                }
            }
        }).start();
    }

    // Helper to update status on UI thread
    private void updateStatus(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                txvHostStatus.setText("Status: " + message);
            });
        }
    }

    // Helper to show error on UI thread
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
        String terminalId = edtTerminalId.getText().toString();
        if (terminalId.isEmpty()) {
            Toast.makeText(getContext(), "Please enter Terminal ID first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure settings are saved (this is fast, OK on main thread)
        saveSettings();

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
                        "Terminal ID: " + edtTerminalId.getText().toString() + "\n" +
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
        if (hostService != null && hostService.isInitialized()) {
            int pendingCount = hostService.getPendingReversalCount();
            txvReversalStatus.setText("Pending Reversals: " + pendingCount);
            if (btnProcessReversals != null) {
                btnProcessReversals.setEnabled(pendingCount > 0);
            }
            if (btnClearReversals != null) {
                btnClearReversals.setEnabled(pendingCount > 0);
                // Toggle visibility — the XML defaults this button to gone so
                // operators can't accidentally tap it when no reversals exist.
                // Show it ONLY when there's something to clear; the existing
                // AlertDialog in clearPendingReversals() guards against typos.
                btnClearReversals.setVisibility(pendingCount > 0 ? View.VISIBLE : View.GONE);
            }
        } else {
            txvReversalStatus.setText("Pending Reversals: N/A (Host not configured)");
            if (btnProcessReversals != null) btnProcessReversals.setEnabled(false);
            if (btnClearReversals != null) {
                btnClearReversals.setEnabled(false);
                btnClearReversals.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Processes all pending reversals.
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

        hostService.processPendingReversals(new AtmHostService.ReversalProcessingCallback() {
            @Override
            public void onProcessingReversal(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        txvReversalStatus.setText("Processing: " + reversal.getFormattedAmount());
                    });
                }
            }

            @Override
            public void onReversalSuccess(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                Log.d(TAG, "Reversal success: " + reversal.getTransactionId());
            }

            @Override
            public void onReversalFailed(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal, String error) {
                Log.w(TAG, "Reversal failed: " + reversal.getTransactionId() + " - " + error);
            }

            @Override
            public void onProcessingComplete(final int successCount, final int failCount) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateReversalStatus();
                        String message = successCount + " succeeded, " + failCount + " failed";
                        Toast.makeText(getContext(), "Reversal processing complete: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    /**
     * Clears all pending reversals (with confirmation).
     */
    private void clearPendingReversals() {
        AtmHostService hostService = (mainActivity != null) ? mainActivity.getAtmHostService() : null;
        if (hostService == null || !hostService.isInitialized()) {
            Toast.makeText(getContext(), "Host service not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        int pendingCount = hostService.getPendingReversalCount();
        if (pendingCount == 0) {
            Toast.makeText(getContext(), "No pending reversals to clear", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show confirmation dialog
        new AlertDialog.Builder(getContext())
            .setTitle("Clear Pending Reversals")
            .setMessage("Are you sure you want to clear " + pendingCount + " pending reversal(s)?\n\n" +
                       "WARNING: This will delete the reversal records without processing them. " +
                       "Only do this if you're certain the original transactions were not approved.")
            .setPositiveButton("Clear", (dialog, which) -> {
                castech.emvtxn.atm.host.ReversalPersistenceManager reversalMgr = hostService.getReversalManager();
                if (reversalMgr != null) {
                    reversalMgr.clearAllPendingReversals();
                    updateReversalStatus();
                    Toast.makeText(getContext(), "Pending reversals cleared", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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
        if (edtPosProxyUrl != null)   posConfig.setProxyBaseUrl(edtPosProxyUrl.getText().toString().trim());
        if (edtPosAccessKey != null)  posConfig.setTerminalAccessKey(edtPosAccessKey.getText().toString().trim());
        if (cbEnablePosMode != null) {
            boolean wasEnabled = posConfig.isEnabled();
            boolean nowEnabled = cbEnablePosMode.isChecked();
            posConfig.setEnabled(nowEnabled);
            if (wasEnabled != nowEnabled) {
                Log.d(TAG, "POS Mode toggled " + (nowEnabled ? "ON" : "OFF") + " — restart required");
            }
        }
    }
}
