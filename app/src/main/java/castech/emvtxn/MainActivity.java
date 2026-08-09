package castech.emvtxn;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.binder.aidl.IKMS2Callback;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import CTOS.CtSettings;
import CTOS.CtEMV;
import CTOS.CtEMVCL;
import CTOS.CtEMVEDL;
import CTOS.CtEMVMSR;
import CTOS.CtEMVManualEntry;
import CTOS.CtKMS2Dukpt;
import CTOS.CtKMS2FixedKey;
import CTOS.CtKMS2RSAKey;
import CTOS.CtKMS2VirtualPINPad;
import CTOS.CtKMS2System;
import CTOS.CtKMS2Callback;
import CTOS.CtKMS2Key;
import CTOS.CtKMS2SymmetryKey;
import CTOS.CtPrint;
import CTOS.CtSC;
import CTOS.EMVEDLBinInfo;
import CTOS.EMVEDLEncryptedData;
import CTOS.emv.CAPublicKey;
import CTOS.emv.EMVAppInfo;
import CTOS.emv.EMVAppListExData;
import CTOS.emv.EMVApplicationPara;
import CTOS.emv.EMVByteBufData;
import CTOS.emv.EMVCandidateData;
import CTOS.emv.EMVCandidateDirEntry;
import CTOS.emv.EMVCandidateList;
import CTOS.emv.EMVCardAcquisitionData;
import CTOS.emv.EMVConfigData;
import CTOS.emv.EMVDataGetExPara;
import CTOS.emv.EMVEvent;
import CTOS.emv.EMVGetPINFuncPara;
import CTOS.emv.EMVMSREncryptedTracks;
import CTOS.emv.EMVMSRMaskedTracks;
import CTOS.emv.EMVMSRTracksLen;
import CTOS.emv.EMVManualEntryEncryptedData;
import CTOS.emv.EMVManualEntryEvent;
import CTOS.emv.EMVOnlinePinData;
import CTOS.emv.EMVOnlineResponseData;

// ATM Host Integration
import castech.emvtxn.atm.host.AtmHostService;
import castech.emvtxn.atm.host.CastleCardData;
import castech.emvtxn.atm.host.CastleKeyManager;
import castech.emvtxn.atm.host.PinBlockFormatter;
import castech.emvtxn.atm.host.ProcessorConfig;
import castech.emvtxn.atm.host.HyosungProtocol;
import castech.emvtxn.test.EmvCryptogramTest;
// Transaction Logging - DISABLED FOR TESTING
// import castech.emvtxn.atm.TransactionLog;
// import castech.emvtxn.atm.TransactionLogManager;
import CTOS.emv.EMVSecureDataInfo;
import CTOS.emv.EMVTxnData;
import CTOS.emv.EMVTxnPSERsp;
import CTOS.emv.TlvData;
import CTOS.emvcl.EMVCLActData;
import CTOS.emvcl.EMVCLAidGetTagData;
import CTOS.emvcl.EMVCLAidSetTagData;
import CTOS.emvcl.EMVCLCAPublicKey;
import CTOS.emvcl.EMVCLParameterData;
import CTOS.emvcl.EMVCLRcDataAnalyze;
import CTOS.emvcl.EMVCLRcDataEx;
import CTOS.emvcl.EMVCLSecureDataInfo;
import CTOS.emvcl.EMVCLUIReqData;

import castech.emvtxn.util.Converter;
import castech.emvtxn.util.TLVUtility;
import castech.emvtxn.util.TLVUtility_CT;
import castech.emvtxn.callback.MyEMVEvent;
import castech.emvtxn.callback.MyEMVSPEvent;
import castech.emvtxn.callback.MyEMVCLSPEvent;
import castech.emvtxn.callback.MyManualEntryEvent;

public class MainActivity extends AppCompatActivity {
    public static String TAG = "EMV_AP";
    public static String APP_TITLE = "MyView";
    public static String APP_VERSION = "6.1-ATM";  // DUKPT support with Format 1 fallback
    public static String DATE_STR = "20200812";
    public static String BUILD_NUM = "0001";

    public static boolean Chinese = false;
    public static int Finish_Printf = 0;

    Bitmap panel = null;
    CTOS_Printer Printer;

    Thread threadTxn;
    Thread threadCancel;
    Thread thGetOnlinPin;
    Thread thInit;
    Thread threadJson;
    Thread threadME;

    // Transaction abort flag
    private volatile boolean txnAborted = false;
    private volatile boolean needsSdkReinit = true;  // True on first transaction or after abort
    // Track if we're in the card detection loop (safe to call cancelTransaction)
    private volatile boolean inCardDetectionLoop = false;

    private SectionsPagerAdapter mSectionsPagerAdapter;

    ViewPager mViewPager;

    // Castle SDK - lazy initialized to avoid ANR on emulator
    public CtEMV emv = null;
    private CtEMVCL emvcl = null;
    private MyEMVEvent emvEvent = null;
    private MyEMVSPEvent myEMVSpEvent = null;
    private MyEMVCLSPEvent myCLSpEvent = null;
    private MyManualEntryEvent myManualEntryEvent = null;
    private CtEMVManualEntry mentry = null;
    private CtSC sc = null;
    private CtEMVMSR msr = null;
    private CtEMVEDL edl = null;
    private boolean sdkInitialized = false;
    private static boolean isRunningOnEmulator = false;

    // v6.0-PERF: Track if EMV configuration has been loaded to skip on subsequent transactions
    // SetConfiguration() takes ~500ms - only needs to be done once at startup
    private volatile boolean emvConfigurationLoaded = false;

    //TLV
    TlvData TLVData = new TlvData();
    TLVUtility tlvUtility = new TLVUtility();            //For CtEMVCL
    TLVUtility_CT tlvUtility_ct = new TLVUtility_CT();    //For CtEMV

    // ATM Host Service
    // Package-private for access from MyEMVSPEvent callback
    public AtmHostService atmHostService = null;
    private final Object atmHostLock = new Object();
    private volatile java.util.concurrent.CountDownLatch atmTransactionLatch = null;

    // ATM Transaction Event Listener - stored so we can re-set before each transaction
    // (Fragment_page_admin_atm may overwrite it during connection tests)
    private AtmHostService.AtmEventListener atmTransactionEventListener = null;

    // ATM Settings Manager
    private AtmSettingsManager atmSettingsManager = null;

    // POS-mode integration (proxy connection for semi-integrated POS).
    // Started lazily in initializeAtmHostService() if PosConfig.isEnabled().
    // See castech.emvtxn.pos.* for the wire protocol and component design.
    private castech.emvtxn.pos.PosOrchestrator posOrchestrator = null;

    // Transaction Log Manager - DISABLED FOR TESTING
    // private TransactionLogManager transactionLogManager = null;
    // private TransactionLog currentTransactionLog = null;



    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while plugged in (ATM should never sleep)
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Detect emulator FIRST before any SDK initialization
        isRunningOnEmulator = checkIsEmulator();
        Log.d(TAG, "Running on emulator: " + isRunningOnEmulator);

        setContentView(R.layout.activity_main);
        setTitle(APP_TITLE + "  Ver." + APP_VERSION);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager(), this);

        mViewPager = (ViewPager) findViewById(R.id.container);
        mViewPager.setAdapter(mSectionsPagerAdapter);
        mViewPager.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });

        TabLayout tabLayout = (TabLayout) findViewById(R.id.tabs);
        tabLayout.setupWithViewPager(mViewPager);

        GlobalPara gp = new GlobalPara();
        MyUtility.setViewPager(this, mViewPager, gp);

        // Start at page 0 (main menu - idle removed for debugging)
        mViewPager.setCurrentItem(0, false);

        // Initialize Castle SDK (lazy - only on real hardware)
        initializeCastleSdk();

        // Configure kiosk mode (auto-start, lock navigation)
        initializeKioskMode();

        // Only register broadcast receiver if SDK is available
        if (!isRunningOnEmulator && GlobalPara.isScrnBrdcstRecverReg == false && emv != null) {
            GlobalPara.scrnBrdcstRecver = new ClsScreenBroadcastReceiver(emv);
            GlobalPara.scrnIntentFilter = new IntentFilter();

            GlobalPara.scrnIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            GlobalPara.scrnIntentFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

            getApplicationContext().registerReceiver(GlobalPara.scrnBrdcstRecver, GlobalPara.scrnIntentFilter);

            GlobalPara.isScrnBrdcstRecverReg = true;
        }

        try {
            GlobalPara.audio = new ClsAudioInidcator(R.raw.ok_tone, R.raw.alert_tone, R.raw.cancel_key_tone, this);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing audio: " + e.getMessage());
        }

        GlobalPara.mainActivity = this;

        // Initialize printer only on real hardware
        try {
            if (!isRunningOnEmulator) {
                Printer = new CTOS_Printer();
                Printer.Init();
                // NOTE: no background paper poll here — see refreshPaperStateSafely().
                // The banner is driven from idle screens + the receipt print thread,
                // never a timer (a concurrent poll crashed the CTOS service).
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing printer: " + e.getMessage());
        }

        GlobalPara.tag = TAG;

        // EMV Cryptogram Diagnostic Test DISABLED — causes ANR/crash on some terminals
        // if (!isRunningOnEmulator) {
        //     runEmvDiagnosticTest();
        // }

        //Cless LED
        try {
            if (GlobalPara.isEuropeUIType) {
                GlobalPara.clLED = new ClessLed(R.drawable.ledg, R.drawable.ledoff,
                        R.drawable.ledg, R.drawable.ledoff,
                        R.drawable.ledg, R.drawable.ledoff,
                        R.drawable.ledg, R.drawable.ledoff);

                GlobalPara.clLED.setUIType(ClessLed.UI_TYPE_EUROPE);
            } else {
                GlobalPara.clLED = new ClessLed(R.drawable.ledr, R.drawable.ledoff,
                        R.drawable.ledg, R.drawable.ledoff,
                        R.drawable.ledy, R.drawable.ledoff,
                        R.drawable.ledb, R.drawable.ledoff);

                GlobalPara.clLED.setUIType(ClessLed.UI_TYPE_NORMAL);
            }

            // Note: setImgView is called by fragments when they create their views
            // The ImageViews are in fragment layouts, not activity_main
            GlobalPara.clLED.setActivity(this);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing LED: " + e.getMessage());
        }

        // Initialize ATM Settings Manager and Host Service
        try {
            Log.d(TAG, "Loading ATM settings from storage...");
            if (atmSettingsManager == null) {
                atmSettingsManager = new AtmSettingsManager(this);
            }
            atmSettingsManager.loadSettings();
            Log.d(TAG, "ATM Settings loaded: host=" + GlobalPara.atmHostAddress +
                       ", port=" + GlobalPara.atmHostPort);

            // Initialize host service if settings are available
            if (GlobalPara.atmHostAddress != null && !GlobalPara.atmHostAddress.isEmpty()) {
                Log.d(TAG, "Initializing ATM Host Service from saved settings...");
                initializeAtmHostService();
            } else {
                Log.d(TAG, "ATM Host not configured - set in Admin Settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing ATM subsystem: " + e.getMessage(), e);
        }

        Log.d(TAG, "MainActivity onCreate()-->");
    }

    @Override
    protected void onDestroy() {
        if (posOrchestrator != null) {
            Log.d(TAG, "onDestroy: stopping POS orchestrator");
            try { posOrchestrator.stop(); } catch (Exception e) {
                Log.w(TAG, "Error stopping POS orchestrator: " + e.getMessage());
            }
            posOrchestrator = null;
        }
        super.onDestroy();
    }

    // ── Receipt paper awareness ───────────────────────────────────────────────
    // Transactions are NEVER blocked by paper state (Hyosung/BlueVerse behaviour:
    // RECEIPTONSCREEN / SELECTRECEIPT). A persistent banner is shown while out of
    // paper, and receipts fall back to on-screen.
    //
    // IMPORTANT: the Castle SDK is single-threaded — every SDK call must happen on
    // the one transaction thread, never concurrently. An earlier version polled
    // Print.status() on a 15s background timer; a poll landing DURING a transaction
    // crashed the whole CTOS service (DeadObjectException across all services,
    // 2026-08-09). So there is NO timer. Paper is read only at points guaranteed
    // not to overlap a transaction: idle/main-menu and the receipt print thread
    // (which is already sequential with the transaction). Never call this while a
    // transaction is in progress.

    /**
     * Reads paper state from the printer and updates the banner. MUST be called
     * only when no transaction is running (idle screens / print thread). Returns
     * false (paper OK) on any error, and refuses to touch the SDK if a transaction
     * is in progress — so it can never race the EMV thread.
     */
    public boolean refreshPaperStateSafely() {
        if (GlobalPara.atmTransactionInProgress) {
            // Never touch the SDK concurrently with a live transaction.
            return GlobalPara.atmPrinterOutOfPaper;
        }
        boolean outOfPaper = GlobalPara.atmPrinterOutOfPaper;
        try {
            CTOS_Printer printer = getPrinter();
            if (printer != null) {
                int status = printer.getStatus();
                if (status >= 0) {
                    outOfPaper = (status == CtPrint.STATUS_NOPAPPER_ERR);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Paper state read failed (assuming OK): " + t.getMessage());
            outOfPaper = false;
        }
        if (outOfPaper != GlobalPara.atmPrinterOutOfPaper) {
            Log.w(TAG, "Printer paper state changed: " + (outOfPaper ? "OUT OF PAPER" : "paper OK"));
        }
        GlobalPara.atmPrinterOutOfPaper = outOfPaper;
        final boolean show = outOfPaper;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updatePaperBanner(show);
            }
        });
        return outOfPaper;
    }

    /**
     * Shows/hides the persistent bottom service banner. UI thread only.
     */
    public void updatePaperBanner(boolean outOfPaper) {
        try {
            android.widget.TextView banner = findViewById(R.id.txvServiceBanner);
            if (banner != null) {
                banner.setVisibility(outOfPaper ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Banner update failed: " + t.getMessage());
        }
    }

    /**
     * Checks if running on an emulator vs real Castle hardware.
     */
    private static boolean checkIsEmulator() {
        // Log build properties for debugging
        Log.d(TAG, "=== BUILD PROPERTIES ===");
        Log.d(TAG, "FINGERPRINT: " + android.os.Build.FINGERPRINT);
        Log.d(TAG, "MODEL: " + android.os.Build.MODEL);
        Log.d(TAG, "MANUFACTURER: " + android.os.Build.MANUFACTURER);
        Log.d(TAG, "BRAND: " + android.os.Build.BRAND);
        Log.d(TAG, "DEVICE: " + android.os.Build.DEVICE);
        Log.d(TAG, "PRODUCT: " + android.os.Build.PRODUCT);
        Log.d(TAG, "HARDWARE: " + android.os.Build.HARDWARE);
        Log.d(TAG, "========================");

        // First, check if it's a Castle device - never treat as emulator
        String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
        String model = android.os.Build.MODEL.toLowerCase();
        if (manufacturer.contains("castle") || model.contains("s1f") || model.contains("saturn")) {
            Log.d(TAG, "Detected Castle device - NOT emulator");
            return false;
        }

        // Check for emulator signatures - use strict checks
        boolean isEmulator = android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish")
                || android.os.Build.FINGERPRINT.contains("generic/sdk")
                || android.os.Build.FINGERPRINT.contains("emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MODEL.contains("Emulator")
                || "google_sdk".equals(android.os.Build.PRODUCT)
                || android.os.Build.MANUFACTURER.contains("Genymotion");

        Log.d(TAG, "isEmulator check result: " + isEmulator);
        return isEmulator;
    }

    /**
     * Returns true if running on emulator (for UI simulation mode).
     */
    public static boolean isEmulator() {
        return isRunningOnEmulator;
    }

    /**
     * Lazily initializes Castle SDK components.
     * Call this before using any SDK features.
     * Safe to call multiple times.
     */
    private void initializeKioskMode() {
        if (isRunningOnEmulator || !GlobalPara.atmKioskMode) {
            Log.d(TAG, "Kiosk mode skipped (emulator=" + isRunningOnEmulator + ", kioskMode=" + GlobalPara.atmKioskMode + ")");
            return;
        }

        try {
            CtSettings ctSettings = new CtSettings();

            // Set this app as the default (auto-launches on boot)
            if (GlobalPara.atmAutoStartOnBoot) {
                ctSettings.setDefaultApp(getPackageName());
                Log.d(TAG, "Kiosk: Set default app to " + getPackageName());
            }

            // Disable Home, Back, Search navigation buttons
            if (GlobalPara.atmDisableNavButtons) {
                ctSettings.setNavigation(false, false, false);
                Log.d(TAG, "Kiosk: Navigation buttons disabled");
            }

            // Keep screen always on (no timeout)
            if (GlobalPara.atmScreenAlwaysOn) {
                ctSettings.setScreenTimeOut(0);
                Log.d(TAG, "Kiosk: Screen timeout disabled");
            }

            // Set auto reboot time (daily maintenance reboot)
            if (GlobalPara.atmAutoReboot) {
                ctSettings.setAutoRebootTime(GlobalPara.atmAutoRebootHour, GlobalPara.atmAutoRebootMinute);
                Log.d(TAG, "Kiosk: Auto reboot set to " + GlobalPara.atmAutoRebootHour + ":" + String.format("%02d", GlobalPara.atmAutoRebootMinute));
            }

            Log.d(TAG, "Kiosk mode initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing kiosk mode: " + e.getMessage());
        }
    }

    private void initializeCastleSdk() {
        if (sdkInitialized) {
            return;
        }

        if (isRunningOnEmulator) {
            Log.w(TAG, "Running on emulator - Castle SDK not initialized");
            Log.w(TAG, "Card operations will be simulated");
            sdkInitialized = true;
            return;
        }

        Log.d(TAG, "Initializing Castle SDK...");
        try {
            emv = new CtEMV();
            emvcl = new CtEMVCL();
            emvEvent = new MyEMVEvent(this, emv);
            myEMVSpEvent = new MyEMVSPEvent();
            myCLSpEvent = new MyEMVCLSPEvent();
            myManualEntryEvent = new MyManualEntryEvent(this);
            mentry = new CtEMVManualEntry();
            sc = new CtSC();
            msr = new CtEMVMSR();
            edl = new CtEMVEDL();
            sdkInitialized = true;
            Log.d(TAG, "Castle SDK initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Castle SDK: " + e.getMessage());
            sdkInitialized = false;
        }
    }

    /**
     * Returns true if Castle SDK is available (not on emulator).
     */
    public boolean isSdkAvailable() {
        return sdkInitialized && !isRunningOnEmulator;
    }

    // ATM Settings Manager and Transaction Log - DISABLED
    private void initializeAtmSettingsManager() {
        Log.d(TAG, "ATM Settings Manager DISABLED");
    }

    public AtmSettingsManager getAtmSettingsManager() {
        if (atmSettingsManager == null) {
            atmSettingsManager = new AtmSettingsManager(this);
        }
        return atmSettingsManager;
    }

    public Object getTransactionLogManager() {
        return null;
    }

    /**
     * Initialize ATM Host Service with current settings.
     * Called from admin screen after settings are saved, or on app startup.
     */
    public void initializeAtmHostService() {
        Log.d(TAG, "initializeAtmHostService() called");

        // Check if GlobalPara already has valid settings (set by Admin UI)
        // If so, don't overwrite them by loading from AtmSettingsManager
        boolean hasValidSettings = GlobalPara.atmHostAddress != null && !GlobalPara.atmHostAddress.isEmpty();
        Log.d(TAG, "GlobalPara has valid settings: " + hasValidSettings +
                   " (host=" + GlobalPara.atmHostAddress + ", port=" + GlobalPara.atmHostPort + ")");

        // Initialize settings manager but only load settings if GlobalPara is empty
        if (atmSettingsManager == null) {
            Log.d(TAG, "Creating AtmSettingsManager...");
            atmSettingsManager = new AtmSettingsManager(this);
            if (!hasValidSettings) {
                atmSettingsManager.loadSettings();
                Log.d(TAG, "ATM Settings loaded from storage: " + atmSettingsManager.getSettingsSummary());
            } else {
                Log.d(TAG, "Skipping loadSettings - using GlobalPara values from Admin UI");
            }
        }

        synchronized (atmHostLock) {
            // Shutdown existing service if any
            if (atmHostService != null) {
                Log.d(TAG, "Shutting down existing ATM Host Service");
                atmHostService.shutdown();
                atmHostService = null;
            }

            // Check if we have required settings
            String hostAddress = GlobalPara.atmHostAddress;
            int hostPort = GlobalPara.atmHostPort;
            String terminalId = GlobalPara.atmTerminalId;

            Log.d(TAG, "initAtmHost: hostAddress=" + hostAddress + ", port=" + hostPort + ", terminalId=" + terminalId);

            if (hostAddress == null || hostAddress.isEmpty()) {
                Log.w(TAG, "ATM Host not configured - hostAddress is empty");
                return;
            }
            if (terminalId == null || terminalId.isEmpty()) {
                Log.w(TAG, "ATM Host not configured - terminalId is empty (using placeholder)");
                // Use a default terminal ID if not set - allow connection test without terminal ID
                terminalId = "TEST001";
                GlobalPara.atmTerminalId = terminalId;
            }

            try {
                // Create processor config based on settings
                ProcessorConfig config;
                String processorType = GlobalPara.atmProcessorType;

                if ("EFX".equalsIgnoreCase(processorType)) {
                    config = ProcessorConfig.forEfx(hostAddress, terminalId);
                    // Override port if different from default
                    if (hostPort != 9057) {
                        config.setPort(hostPort);
                    }
                } else if ("DNS".equalsIgnoreCase(processorType)) {
                    config = ProcessorConfig.forDns(hostAddress, terminalId);
                    if (hostPort != 8002) {
                        config.setPort(hostPort);
                    }
                } else if ("SWITCH_COMMERCE".equalsIgnoreCase(processorType)) {
                    config = ProcessorConfig.forSwitchCommerce(hostAddress, terminalId);
                    if (hostPort != 1440) {
                        config.setPort(hostPort);
                    }
                } else {
                    // Default to EFX for custom configurations
                    config = ProcessorConfig.forEfx(hostAddress, terminalId);
                    config.setPort(hostPort);
                }

                // Apply TLS setting from GlobalPara
                config.setUseTls(GlobalPara.atmUseTls);

                Log.d(TAG, "Creating ATM Host Service for " + config.getName() +
                      " at " + hostAddress + ":" + config.getPort() +
                      " (TLS=" + config.isUseTls() + ")");

                // Create and initialize service
                atmHostService = new AtmHostService(this);
                if (atmHostService.initialize(config)) {
                    Log.d(TAG, "ATM Host Service initialized successfully");

                    // Set up event listener to receive transaction results
                    // Store in member variable so we can re-set it before each transaction
                    // (Fragment_page_admin_atm may overwrite it during connection tests)
                    atmTransactionEventListener = new AtmHostService.AtmEventListener() {
                        @Override
                        public void onProgress(String message) {
                            Log.d(TAG, "ATM Host Progress: " + message);
                            // Route [REVERSAL] progress messages to the GlobalPara fields the
                            // receipt fragment displays. Customer-visible — they should see
                            // the reversal actually happening, not silently in logs.
                            if (message != null && message.startsWith("[REVERSAL] ")) {
                                String body = message.substring("[REVERSAL] ".length());
                                GlobalPara.atmReversalStatus = body;
                                if (body.toLowerCase().contains("in progress")) {
                                    GlobalPara.atmReversalInProgress = true;
                                } else {
                                    // Any non-"in progress" message marks the drain as finished.
                                    GlobalPara.atmReversalInProgress = false;
                                    if (body.toLowerCase().contains("approved")
                                            || body.toLowerCase().contains("complete")) {
                                        GlobalPara.atmReversalSent = true;
                                    }
                                }
                                // Ask the receipt fragment to refresh so the customer sees the
                                // status update without waiting for a tap.
                                runOnUiThread(() -> {
                                    SectionsPagerAdapter adapter = mSectionsPagerAdapter;
                                    if (adapter != null) {
                                        Fragment_page_receipt receipt = adapter.getReceiptFragment();
                                        if (receipt != null) receipt.refreshDisplay();
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "ATM Host Error: " + error);
                            GlobalPara.atmHostCallSuccess = false;
                            GlobalPara.atmResponseMessage = error;
                            if (atmTransactionLatch != null) {
                                atmTransactionLatch.countDown();
                            }
                            // POS hook (no-op if no POS callback is armed)
                            castech.emvtxn.pos.PosTransactionObserver.notifyError(error);
                        }

                        @Override
                        public void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime,
                                long accountBalanceCents, long availableBalanceCents, String displayMessage) {
                            Log.d(TAG, "ATM Host APPROVED: responseCode=" + responseCode + ", ref=" + referenceNumber);
                            GlobalPara.atmHostCallSuccess = true;
                            GlobalPara.atmReferenceNumber = referenceNumber;
                            GlobalPara.atmAuthDate = authDate;
                            GlobalPara.atmAuthTime = authTime;
                            GlobalPara.atmAccountBalance = accountBalanceCents;
                            GlobalPara.atmAvailableBalance = availableBalanceCents;
                            GlobalPara.atmResponseCode = responseCode;  // Read from actual response
                            GlobalPara.atmResponseMessage = displayMessage != null ? displayMessage : "APPROVED";
                            // Set transactionResult to approved so receipt shows success
                            GlobalPara.transactionResult = 0x0002;  // Approved
                            if (atmTransactionLatch != null) {
                                atmTransactionLatch.countDown();
                            }
                            // POS hook (no-op if no POS callback is armed)
                            castech.emvtxn.pos.PosTransactionObserver.notifyApproved(
                                    responseCode, referenceNumber, authDate, authTime,
                                    accountBalanceCents, availableBalanceCents, displayMessage);
                        }

                        @Override
                        public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) {
                            Log.e(TAG, ">>> CALLBACK onTransactionDeclined: code=" + responseCode + ", msg=" + responseMessage);
                            Log.e(TAG, ">>> CALLBACK onTransactionDeclined: latch=" + (atmTransactionLatch != null ? "SET" : "NULL"));
                            GlobalPara.atmHostCallSuccess = false;
                            GlobalPara.atmResponseCode = responseCode;
                            GlobalPara.atmResponseMessage = responseMessage;
                            if (atmTransactionLatch != null) {
                                Log.e(TAG, ">>> CALLBACK onTransactionDeclined: counting down latch...");
                                atmTransactionLatch.countDown();
                                Log.e(TAG, ">>> CALLBACK onTransactionDeclined: latch counted down");
                            } else {
                                Log.e(TAG, ">>> CALLBACK onTransactionDeclined: LATCH IS NULL - cannot signal!");
                            }
                            // POS hook (no-op if no POS callback is armed)
                            castech.emvtxn.pos.PosTransactionObserver.notifyDeclined(
                                    responseCode, responseMessage, retainCard);
                        }

                        @Override
                        public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) {
                            Log.d(TAG, "ATM Host Balance: responseCode=" + responseCode + ", account=" + accountBalanceCents + ", available=" + availableBalanceCents);
                            GlobalPara.atmHostCallSuccess = true;
                            GlobalPara.atmAccountBalance = accountBalanceCents;
                            GlobalPara.atmAvailableBalance = availableBalanceCents;
                            GlobalPara.atmResponseCode = responseCode;  // Read from actual response
                            GlobalPara.atmResponseMessage = "APPROVED";
                            // Set transactionResult to approved so receipt shows success
                            GlobalPara.transactionResult = 0x0002;  // Approved
                            if (atmTransactionLatch != null) {
                                atmTransactionLatch.countDown();
                            }
                            // POS hook — balance inquiry flows here, not onTransactionApproved
                            castech.emvtxn.pos.PosTransactionObserver.notifyApproved(
                                    responseCode, "", "", "",
                                    accountBalanceCents, availableBalanceCents, "APPROVED");
                        }

                        @Override
                        public void onKeysLoaded(String keyCheckValue) {
                            Log.d(TAG, "ATM Host Keys loaded: KCV=" + keyCheckValue);
                        }

                        @Override
                        public void onReversalComplete(boolean success) {
                            Log.d(TAG, "ATM Host Reversal complete: success=" + success);
                        }

                        @Override
                        public void onHealthCheckResult(boolean success) {
                            Log.d(TAG, "ATM Host Health check: success=" + success);
                        }

                        @Override
                        public void onHostTotalsReceived(castech.emvtxn.atm.host.HostTotalsResponse response) {
                            Log.d(TAG, "ATM Host Totals received: " + response);
                        }
                    };
                    atmHostService.setEventListener(atmTransactionEventListener);
                    Log.d(TAG, "ATM Host Service event listener set");

                    // Process any pending reversals from previous sessions
                    processPendingReversalsOnStartup();

                    // Check and renew working key if needed
                    performStartupKeyRenewal();

                    // POS-mode boot — runs alongside the customer-driven flow.
                    // When PosConfig.isEnabled() the orchestrator opens a persistent
                    // WebSocket to the proxy and handles inbound POS commands.
                    // Reversal + settlement work end-to-end today; sale + balance_inquiry
                    // return not_supported until Phase 7b (card-read trigger integration).
                    startPosModeIfEnabled();
                } else {
                    Log.e(TAG, "ATM Host Service initialization failed");
                    atmHostService = null;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error initializing ATM Host Service: " + e.getMessage());
                atmHostService = null;
            }
        }
    }

    /**
     * Returns the POS orchestrator's current state for admin UI display.
     * Returns null when POS mode is off / not started.
     */
    public String getPosOrchestratorState() {
        return posOrchestrator == null ? null : posOrchestrator.getState();
    }

    /**
     * Boots the POS-mode orchestrator when {@code PosConfig.isEnabled()}.
     * Idempotent — calling twice is a no-op.
     *
     * <p>This is the single integration point between the existing customer-driven
     * Cashless ATM app and the new POS proxy client. All POS-specific code lives
     * in {@code castech.emvtxn.pos.*}.
     */
    private void startPosModeIfEnabled() {
        castech.emvtxn.pos.PosConfig posConfig = new castech.emvtxn.pos.PosConfig(this);
        if (!posConfig.isEnabled()) {
            Log.d(TAG, "POS mode disabled — skipping orchestrator boot");
            return;
        }
        if (posOrchestrator != null) {
            Log.w(TAG, "POS orchestrator already running");
            return;
        }
        if (atmHostService == null) {
            Log.w(TAG, "POS mode enabled but AtmHostService unavailable — cannot start");
            return;
        }

        String terminalSerial = atmSettingsManager == null ? "" : GlobalPara.atmTerminalId;
        String appVersion = BuildConfig.VERSION_NAME;
        String deviceModel = android.os.Build.MODEL;

        // UI bridge — lets the gateway navigate to the transaction page so the
        // existing card-detection loop runs for POS-initiated sale / balance_inquiry.
        final MainActivity self = this;
        castech.emvtxn.pos.AtmHostServiceGateway.UiBridge uiBridge =
            new castech.emvtxn.pos.AtmHostServiceGateway.UiBridge() {
                @Override public void runOnUi(Runnable r) {
                    self.runOnUiThread(r);
                }
                @Override public void navigateToTransactionPage() {
                    self.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
                }
            };

        posOrchestrator = new castech.emvtxn.pos.PosOrchestrator(
                this, posConfig, atmHostService, uiBridge,
                terminalSerial, appVersion, deviceModel);
        posOrchestrator.start();
        Log.d(TAG, "POS orchestrator started — state=" + posOrchestrator.getState());
    }

    /**
     * Performs working key renewal on startup if needed.
     * Called automatically after ATM Host Service is initialized.
     */
    private void performStartupKeyRenewal() {
        if (atmHostService == null || !atmHostService.isInitialized()) {
            Log.d(TAG, "ATM Host Service not ready - skipping key renewal");
            return;
        }

        Log.d(TAG, "Checking working key status: " + atmHostService.getKeyStatus());

        atmHostService.performStartupKeyRenewal(new AtmHostService.KeyRenewalCallback() {
            @Override
            public void onRenewalSuccess(final String keyStatus) {
                Log.d(TAG, "Startup key renewal success: " + keyStatus);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Optionally show a brief status update
                        Log.d(TAG, "Working key ready: " + keyStatus);
                    }
                });
            }

            @Override
            public void onRenewalFailed(final String error) {
                Log.w(TAG, "Startup key renewal failed: " + error);
                // The key usually arrives moments later via the normal init/open
                // path — a warning here was flashing a raw exception at the customer
                // even though the terminal ended up with a valid working key.
                // Re-check after a grace period and only warn if it's STILL missing.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (atmHostService != null && atmHostService.hasValidWorkingKey()) {
                            Log.d(TAG, "Working key arrived after startup-renewal failure — no warning shown");
                            return;
                        }
                        android.widget.Toast.makeText(MainActivity.this,
                            "Warning: Working key not available — transactions may fail",
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }, 10000);
            }
        });
    }

    /**
     * Processes any pending reversals from previous sessions.
     * Called automatically after ATM Host Service is initialized.
     */
    private void processPendingReversalsOnStartup() {
        if (atmHostService == null || !atmHostService.isInitialized()) {
            Log.d(TAG, "ATM Host Service not ready - skipping pending reversals");
            return;
        }

        int pendingCount = atmHostService.getPendingReversalCount();
        if (pendingCount == 0) {
            Log.d(TAG, "No pending reversals to process");
            return;
        }

        Log.d(TAG, "Found " + pendingCount + " pending reversal(s) from previous session");

        // Process on background thread
        atmHostService.processPendingReversals(new AtmHostService.ReversalProcessingCallback() {
            @Override
            public void onProcessingReversal(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                Log.d(TAG, "Processing pending reversal: " + reversal.getTransactionId());
            }

            @Override
            public void onReversalSuccess(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal) {
                Log.d(TAG, "Pending reversal processed successfully: " + reversal.getTransactionId());
            }

            @Override
            public void onReversalFailed(castech.emvtxn.atm.host.ReversalPersistenceManager.PendingReversal reversal, String error) {
                Log.w(TAG, "Pending reversal failed: " + reversal.getTransactionId() + " - " + error);
            }

            @Override
            public void onProcessingComplete(final int successCount, final int failCount) {
                Log.d(TAG, "Reversal processing complete: " + successCount + " succeeded, " + failCount + " failed");
                if (failCount > 0) {
                    // Notify user about failed reversals
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(MainActivity.this,
                                failCount + " pending reversal(s) failed - check Admin screen",
                                android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    // Build card data from transaction - DISABLED
    private Object buildCardDataFromTransaction(int entryMode, byte[] track2Data, int track2Len,
                                                 byte[] emvData, int emvDataLen) {
        Log.d(TAG, "buildCardDataFromTransaction DISABLED");
        return null;
    }

    // Perform ATM host transaction - DISABLED
    private boolean performAtmHostTransaction(Object cardData, long amountCents, long surchargeCents) {
        Log.d(TAG, "performAtmHostTransaction DISABLED");
        return false;
    }

    private String getEntryModeString(int entryMode) {
        switch (entryMode) {
            case 1: return "CONTACT";
            case 2: return "CONTACTLESS";
            case 3: return "MSR";
            default: return "UNKNOWN";
        }
    }

    // Perform ATM balance inquiry - DISABLED
    private boolean performAtmBalanceInquiry(Object cardData) {
        Log.d(TAG, "performAtmBalanceInquiry DISABLED");
        return false;
    }

    /**
     * Combine issuer scripts (Tags 71 and 72) into a single buffer for txnCompletion.
     * Returns null if both scripts are null/empty.
     */
    private byte[] combineIssuerScripts(byte[] script71, byte[] script72) {
        int len71 = (script71 != null) ? script71.length : 0;
        int len72 = (script72 != null) ? script72.length : 0;

        if (len71 == 0 && len72 == 0) {
            return null;
        }

        byte[] combined = new byte[len71 + len72];
        int offset = 0;

        if (len71 > 0) {
            System.arraycopy(script71, 0, combined, offset, len71);
            offset += len71;
        }
        if (len72 > 0) {
            System.arraycopy(script72, 0, combined, offset, len72);
        }

        return combined;
    }

    /**
     * Displays the balance inquiry result in a dialog.
     * Shows both account balance and available balance.
     */
    private void showBalanceResultDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Format balances as currency
                double accountBalance = GlobalPara.atmAccountBalance / 100.0;
                double availableBalance = GlobalPara.atmAvailableBalance / 100.0;

                String message = String.format(
                    "Account Balance: $%.2f\n\nAvailable Balance: $%.2f",
                    accountBalance, availableBalance
                );

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Account Balance");
                builder.setMessage(message);
                builder.setCancelable(false);

                builder.setPositiveButton("Print Receipt", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        printBalanceReceipt();
                        navigateToPage(GlobalDef.d_PAGE_IDLE);
                    }
                });

                builder.setNegativeButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        navigateToPage(GlobalDef.d_PAGE_IDLE);
                    }
                });

                builder.show();

                // Play success feedback
                playAtmSuccessFeedback();
            }
        });
    }

    /**
     * Prints a balance inquiry receipt.
     */
    private void printBalanceReceipt() {
        if (Printer == null) {
            Log.e(TAG, "Printer not available");
            return;
        }

        try {
            double accountBalance = GlobalPara.atmAccountBalance / 100.0;
            double availableBalance = GlobalPara.atmAvailableBalance / 100.0;

            // Print header
            Printer.printf("================================\n");
            Printer.printf("       BALANCE INQUIRY\n");
            Printer.printf("================================\n\n");

            // Print date/time
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss", java.util.Locale.US);
            String dateTime = sdf.format(new java.util.Date());
            Printer.printf("Date: " + dateTime + "\n\n");

            // Print card info (masked)
            if (GlobalPara.atmLastFourDigits != null && !GlobalPara.atmLastFourDigits.isEmpty()) {
                Printer.printf("Card: ************" + GlobalPara.atmLastFourDigits + "\n\n");
            }

            // Print balances
            Printer.printf("--------------------------------\n");
            Printer.printf("Account Balance:\n");
            Printer.printf(String.format("         $%.2f\n\n", accountBalance));
            Printer.printf("Available Balance:\n");
            Printer.printf(String.format("         $%.2f\n", availableBalance));
            Printer.printf("--------------------------------\n\n");

            // Print footer
            Printer.printf("Thank you for using\n");
            Printer.printf("our ATM services.\n\n");
            Printer.printf("================================\n\n\n\n");

            // Execute print
            Printer.goprintf();

            Log.d(TAG, "Balance receipt printed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error printing balance receipt: " + e.getMessage());
        }
    }

    /**
     * Prints a batch close receipt with host totals summary.
     * @param response The HostTotalsResponse containing batch totals
     */
    private void printBatchCloseReceipt(castech.emvtxn.atm.host.HostTotalsResponse response) {
        if (Printer == null) {
            Log.e(TAG, "Printer not available for batch close receipt");
            return;
        }

        try {
            // Print header
            Printer.printf("================================\n");
            Printer.printf("      BATCH CLOSE REPORT\n");
            Printer.printf("================================\n\n");

            // Print date/time
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss", java.util.Locale.US);
            String dateTime = sdf.format(new java.util.Date());
            Printer.printf("Date: " + dateTime + "\n\n");

            // Print terminal ID
            String terminalId = response.getTerminalId();
            if (terminalId != null && !terminalId.isEmpty()) {
                Printer.printf("Terminal: " + terminalId + "\n\n");
            }

            // Print transaction counts
            Printer.printf("--- TRANSACTION COUNTS ---\n");
            Printer.printf("Withdrawals:       " + String.format("%4d", response.getWithdrawalCount()) + "\n");
            Printer.printf("Balance Inquiries: " + String.format("%4d", response.getBalanceInquiryCount()) + "\n");
            Printer.printf("Transfers:         " + String.format("%4d", response.getTransferCount()) + "\n");
            Printer.printf("Non-Cash:          " + String.format("%4d", response.getNonCashCount()) + "\n");
            Printer.printf("                   ----\n");
            Printer.printf("TOTAL:             " + String.format("%4d", response.getTotalTransactionCount()) + "\n\n");

            // Print amounts
            Printer.printf("--- AMOUNTS ---\n");
            Printer.printf("Cash Dispensed: " + response.getTotalCashDispensedFormatted() + "\n");
            Printer.printf("Non-Cash:       " + response.getTotalNonCashFormatted() + "\n");
            Printer.printf("Surcharges:     " + response.getTotalSurchargesFormatted() + "\n\n");

            // Print footer
            Printer.printf("--------------------------------\n");
            Printer.printf("     BATCH CLOSED SUCCESSFULLY\n");
            Printer.printf("--------------------------------\n\n\n\n");

            // Execute print
            Printer.goprintf();

            Log.d(TAG, "Batch close receipt printed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error printing batch close receipt: " + e.getMessage());
        }
    }

    // =========================================================================
    // ATM Error Handling UI Methods
    // =========================================================================

    /**
     * Shows an error dialog with optional retry functionality.
     * Called on the UI thread.
     *
     * @param title Dialog title
     * @param message Error message to display
     * @param showRetry Whether to show a retry button
     * @param onRetry Callback for retry action (can be null if showRetry is false)
     */
    public void showAtmErrorDialog(String title, String message, boolean showRetry, final Runnable onRetry) {
        final String t = title;
        final String m = message;
        final boolean retry = showRetry;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(t);
                builder.setMessage(m);
                builder.setCancelable(false);

                if (retry && onRetry != null) {
                    builder.setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            onRetry.run();
                        }
                    });
                    builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
                        }
                    });
                } else {
                    builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            navigateToPage(GlobalDef.d_PAGE_RECEIPT);
                        }
                    });
                }

                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });
    }

    /**
     * Shows a transaction declined dialog with the reason from the host.
     *
     * @param responseCode Host response code
     * @param responseMessage Host response message
     */
    public void showAtmDeclinedDialog(String responseCode, String responseMessage) {
        String title = "Transaction Declined";
        String message;

        if (responseMessage != null && !responseMessage.isEmpty()) {
            message = responseMessage;
        } else if (responseCode != null && !responseCode.isEmpty()) {
            message = "Response Code: " + responseCode + "\n" + getResponseCodeDescription(responseCode);
        } else {
            message = "Your transaction could not be completed.";
        }

        // Play error feedback
        playAtmErrorFeedback();

        final String t = title;
        final String m = message;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(t);
                builder.setMessage(m);
                builder.setCancelable(false);
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        navigateToPage(GlobalDef.d_PAGE_RECEIPT);
                    }
                });

                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });
    }

    /**
     * Shows a connection error dialog with retry option.
     *
     * @param errorMessage The error message
     * @param onRetry Callback for retry action
     */
    public void showAtmConnectionErrorDialog(String errorMessage, Runnable onRetry) {
        String title = "Connection Error";
        String message = errorMessage != null ? errorMessage : "Unable to connect to the host.";
        message += "\n\nPlease check your network connection and try again.";

        // Play error feedback
        playAtmErrorFeedback();

        showAtmErrorDialog(title, message, true, onRetry);
    }

    /**
     * Shows a timeout error dialog with retry option.
     *
     * @param onRetry Callback for retry action
     */
    public void showAtmTimeoutDialog(Runnable onRetry) {
        String title = "Transaction Timeout";
        String message = "The transaction timed out waiting for a response.\n\nWould you like to try again?";

        // Play error feedback
        playAtmErrorFeedback();

        showAtmErrorDialog(title, message, true, onRetry);
    }

    /**
     * Plays error audio and LED feedback.
     */
    public void playAtmErrorFeedback() {
        // Play alert sound
        if (GlobalPara.audio != null) {
            GlobalPara.audio.playAlertSound();
        }

        // Show error LED pattern (all red or flash pattern)
        showAtmErrorLedPattern();
    }

    /**
     * Shows error LED pattern - flashes all LEDs red briefly.
     */
    private void showAtmErrorLedPattern() {
        if (GlobalPara.clLED != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Flash pattern: all on, off, on, off
                        for (int i = 0; i < 3; i++) {
                            GlobalPara.clLED.setLED((byte) 0x0F, (byte) 0x0F); // All on
                            Thread.sleep(150);
                            GlobalPara.clLED.setLED((byte) 0x0F, (byte) 0x00); // All off
                            Thread.sleep(150);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }).start();
        }
    }

    /**
     * Shows success LED pattern and plays success sound.
     */
    public void playAtmSuccessFeedback() {
        // Play OK sound
        if (GlobalPara.audio != null) {
            GlobalPara.audio.playOKSound();
        }

        // Success LED pattern is handled by existing EMVCL LED events
    }

    /**
     * Updates the transaction screen with a status message.
     *
     * @param message Status message to display
     * @param isError Whether this is an error message (changes color)
     */
    public void updateAtmStatusMessage(String message, boolean isError) {
        final String msg = message;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ui_ShowMsg(msg + "\n");
            }
        });
    }

    /**
     * Gets a human-readable description for common response codes.
     *
     * @param responseCode The response code
     * @return Human-readable description
     */
    private String getResponseCodeDescription(String responseCode) {
        if (responseCode == null) return "";

        switch (responseCode) {
            case "00": return "Approved";
            case "01": return "Refer to card issuer";
            case "02": return "Refer to card issuer, special condition";
            case "03": return "Invalid merchant";
            case "04": return "Pick up card";
            case "05": return "Do not honor";
            case "06": return "Error";
            case "07": return "Pick up card, special condition";
            case "12": return "Invalid transaction";
            case "13": return "Invalid amount";
            case "14": return "Invalid card number";
            case "15": return "No such issuer";
            case "19": return "Re-enter transaction";
            case "30": return "Format error";
            case "41": return "Lost card - pick up";
            case "43": return "Stolen card - pick up";
            case "51": return "Insufficient funds";
            case "54": return "Expired card";
            case "55": return "Incorrect PIN";
            case "57": return "Transaction not permitted to cardholder";
            case "58": return "Transaction not permitted to terminal";
            case "61": return "Exceeds withdrawal limit";
            case "62": return "Restricted card";
            case "63": return "Security violation";
            case "65": return "Exceeds withdrawal frequency limit";
            case "75": return "PIN tries exceeded";
            case "76": return "Invalid account";
            case "77": return "No account";
            case "78": return "No card record";
            case "80": return "Bad date (date not valid)";
            case "81": return "Cryptographic error";
            case "82": return "Incorrect CVV";
            case "83": return "Unable to verify PIN";
            case "84": return "Duplicate transaction";
            case "85": return "Card OK (no action)";
            case "86": return "Cannot verify PIN";
            case "87": return "Purchase amount only, no cash back";
            case "88": return "Cryptographic failure";
            case "89": return "Authentication failure";
            case "91": return "Issuer or switch unavailable";
            case "92": return "Unable to route transaction";
            case "93": return "Violation of law";
            case "94": return "Duplicate transmission";
            case "96": return "System malfunction";
            default: return "Transaction declined";
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_admin) {
            // Navigate to Admin screen
            navigateToPage(GlobalDef.d_PAGE_SETTING);
            return true;
        } else if (id == R.id.action_host_totals) {
            // Request Host Totals from processor
            requestHostTotals();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    Object syncTokenInit = new Object();

    @Override
    public void onStart() {
        super.onStart();

        thInit = new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                // TEST v2.10: Add msr + edl
                Log.d(TAG, ">>> v2.10: thInit STARTING <<<");

                if (emv == null || emvEvent == null) {
                    Log.e(TAG, ">>> emv or emvEvent is NULL - skipping");
                    GlobalPara.isInitThreadFinish = true;
                    return;
                }

                emvEvent.version = 1;
                int intRtn = emv.initialize(emvEvent);
                Log.d(TAG, ">>> emv.initialize returned: " + String.format("0x%08X", intRtn));

                // Set contact interface available if EMV init succeeded
                if (intRtn == 0) {
                    GlobalPara.isContactInterfaceAvaliable = true;
                    Log.d(TAG, ">>> Contact (CT) interface ENABLED");

                    // v5.9-ATM: Enable EMV additional debug (Castle support recommendation)
                    // Must be called after emv.initialize()
                    try {
                        int debugRtn = emv.setDebug((byte)0, (byte)0x00);  // Debug disabled for performance
                        Log.d(TAG, ">>> EMV setDebug disabled: " + String.format("0x%08X", debugRtn));
                    } catch (Exception e) {
                        Log.e(TAG, ">>> v5.9: EMV setDebug failed: " + e.getMessage());
                    }
                }

                // v2.14: Full EMV init with special events
                if (intRtn == 0 && myEMVSpEvent != null) {
                    Log.d(TAG, ">>> v2.14: Registering EMV special events...");
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_SHOW_VIRTUAL_PIN_EX, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_GET_PIN_DONE, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_ONLINE_PINBLOCK_GET, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_PIN_BYPASS, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_APP_LIST_ALWAYS, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    // CAPK callback - SDK calls this to request CAPK during chip transaction
                    emv.specialEventRegister((byte) CtEMV.d_EVENTID_CAPK_GET, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                    Log.d(TAG, ">>> v2.14: EMV special events registered (including CAPK_GET)");

                    // v6.0-PERF: Skip SetConfiguration if already loaded (e.g., from previous init)
                    if (!emvConfigurationLoaded) {
                        Log.d(TAG, ">>> v2.14: Calling SetConfiguration...");
                        SetConfiguration("emv_config.xml", true);
                        Log.d(TAG, ">>> v2.14: SetConfiguration done");
                    } else {
                        Log.d(TAG, ">>> v6.0-PERF: SKIPPING EMV SetConfiguration (already loaded)");
                    }

                    // v5.9-ATM: Add EMV secureDataEncryptInfoSet for PIN/Track2 encryption
                    // USE WORKING DUKPT KEY: C000/0000 (terminal has DUKPT here, C001/00A1 is FIXED key)
                    Log.d(TAG, ">>> Setting up EMV secure data encryption (DUKPT at C000/0000)...");
                    EMVSecureDataInfo emvSecureInfo = new EMVSecureDataInfo();
                    emvSecureInfo.version = 4;  // Match original sample
                    emvSecureInfo.keyType = (byte) 2;  // DUKPT type
                    emvSecureInfo.cipherKeySet = GlobalPara.atmDukptKeySet;   // C000 - working DUKPT location
                    emvSecureInfo.cipherKeyIndex = GlobalPara.atmDukptKeyIndex; // 0000 - working DUKPT location
                    emvSecureInfo.cipherMethod = 0x01;  // CBC
                    emvSecureInfo.checksumType = 0;
                    // ICVLen=8 for CBC encryption
                    emvSecureInfo.ICVLen = 8;
                    emvSecureInfo.ICV = new byte[8];
                    emvSecureInfo.paddingMethod = 1;
                    int emvSecRtn = emv.secureDataEncryptInfoSet(emvSecureInfo);
                    if (emvSecRtn != 0) {
                        Log.e(TAG, ">>> v5.7: EMV secureDataEncryptInfoSet FAILED: " + String.format("0x%08X", emvSecRtn));
                    } else {
                        Log.d(TAG, ">>> v5.7: EMV secureDataEncryptInfoSet OK (DUKPT: " +
                            String.format("0x%04X/0x%04X", GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex) + ")");
                    }

                    // v5.4-FIX: Add whitelist to allow unmasked Track2 access
                    // Format: 0000000A = 10 entries, each "01 3X" = length 1 + ASCII digit (0-9)
                    Log.d(TAG, ">>> v5.4: Setting EMV whitelist for all BINs (0-9)...");
                    byte[] emvWhitelistData = Converter.hexString2ByteArray("0000000A0131013201330134013501360137013801390130");
                    int emvWlRtn = emv.secureDataWhitelistSet((byte) 0, emvWhitelistData, emvWhitelistData.length);
                    if (emvWlRtn != 0) {
                        Log.e(TAG, ">>> v5.4: EMV whitelistSet FAILED: " + String.format("0x%08X", emvWlRtn));
                    } else {
                        Log.d(TAG, ">>> v5.4: EMV whitelistSet OK (all BINs 0-9 whitelisted)");
                    }
                }

                // v2.14: Full EMVCL init with special events and config
                if (emvcl != null) {
                    Log.d(TAG, ">>> v2.14: Calling emvcl.initialize()...");
                    intRtn = emvcl.initialize();
                    Log.d(TAG, ">>> v2.14: emvcl.initialize returned: " + String.format("0x%08X", intRtn));

                    // Set contactless interface available if EMVCL init succeeded
                    if (intRtn == 0) {
                        GlobalPara.isContactlessInterfaceAvaliable = true;
                        Log.d(TAG, ">>> Contactless (CL) interface ENABLED");
                    }

                    if (intRtn == 0 && myCLSpEvent != null) {
                        Log.d(TAG, ">>> v2.14: Registering EMVCL special events...");
                        emvcl.specialEventRegister((byte) CtEMVCL.d_EMVCL_EVENTID_LED_PIC_SHOW, (CtEMVCL.ISpecialEvent) myCLSpEvent);
                        emvcl.specialEventRegister((byte) CtEMVCL.d_EMVCL_EVENTID_AUDIO_INDICATION, (CtEMVCL.ISpecialEvent) myCLSpEvent);
                        emvcl.specialEventRegister((byte) CtEMVCL.d_EMVCL_EVENTID_SHOW_MESSAGE, (CtEMVCL.ISpecialEvent) myCLSpEvent);
                        Log.d(TAG, ">>> v2.14: EMVCL special events registered");

                        // v6.0-PERF: Skip EMVCL setConfiguration if already loaded
                        if (!emvConfigurationLoaded) {
                            try {
                                Log.d(TAG, ">>> v2.14: Loading emvcl_config.xml...");
                                InputStream xmlStream = getApplicationContext().getAssets().open("emvcl_config.xml");
                                emvcl.setConfiguration(xmlStream);
                                Log.d(TAG, ">>> v2.14: EMVCL setConfiguration done");
                            } catch (Exception e) {
                                Log.e(TAG, ">>> v2.14: EMVCL config error: " + e.getMessage());
                            }
                        } else {
                            Log.d(TAG, ">>> v6.0-PERF: SKIPPING EMVCL setConfiguration (already loaded)");
                        }

                        // v5.9-ATM: Add EMVCL secureDataEncryptInfoSet for PIN/Track2 encryption
                        // USE WORKING DUKPT KEY: C000/0000 (terminal has DUKPT here, C001/00A1 is FIXED key)
                        Log.d(TAG, ">>> v5.9: Setting up EMVCL secure data encryption (DUKPT at C000/0000)...");
                        EMVCLSecureDataInfo emvclSecureInfo = new EMVCLSecureDataInfo();
                        emvclSecureInfo.version = 4;  // Match original sample
                        emvclSecureInfo.keyType = (byte) 2;  // DUKPT type
                        emvclSecureInfo.cipherKeySet = GlobalPara.atmDukptKeySet;   // C000 - working DUKPT location
                        emvclSecureInfo.cipherKeyIndex = GlobalPara.atmDukptKeyIndex; // 0000 - working DUKPT location
                        emvclSecureInfo.cipherMethod = 0x01;  // CBC
                        emvclSecureInfo.checksumType = 0;
                        // ICVLen=8 for CBC encryption
                        emvclSecureInfo.ICVLen = 8;
                        emvclSecureInfo.ICV = new byte[8];
                        emvclSecureInfo.paddingMethod = 0;
                        emvclSecureInfo.LRCIncluded = 0;
                        emvclSecureInfo.SS_ESIncluded = 0;
                        emvclSecureInfo.isKSNFixed = 0;
                        int emvclSecRtn = emvcl.secureDataEncryptInfoSet(emvclSecureInfo);
                        if (emvclSecRtn != 0) {
                            Log.e(TAG, ">>> v5.7: EMVCL secureDataEncryptInfoSet FAILED: " + String.format("0x%08X", emvclSecRtn));
                        } else {
                            Log.d(TAG, ">>> v5.7: EMVCL secureDataEncryptInfoSet OK (DUKPT: " +
                                String.format("0x%04X/0x%04X", GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex) + ")");
                        }

                        // v5.4-FIX: Add whitelist to allow unmasked Track2 access for EMVCL
                        Log.d(TAG, ">>> v5.4: Setting EMVCL whitelist for all BINs (0-9)...");
                        byte[] emvclWhitelistData = Converter.hexString2ByteArray("0000000A0131013201330134013501360137013801390130");
                        int emvclWlRtn = emvcl.secureDataWhitelistSet((byte) 0, emvclWhitelistData, emvclWhitelistData.length);
                        if (emvclWlRtn != 0) {
                            Log.e(TAG, ">>> v5.4: EMVCL whitelistSet FAILED: " + String.format("0x%08X", emvclWlRtn));
                        } else {
                            Log.d(TAG, ">>> v5.4: EMVCL whitelistSet OK (all BINs 0-9 whitelisted)");
                        }
                    }
                }

                // v2.10: Add msr.initialize()
                if (msr != null) {
                    Log.d(TAG, ">>> v2.10: Calling msr.initialize()...");
                    intRtn = msr.initialize();
                    Log.d(TAG, ">>> v2.10: msr.initialize returned: " + String.format("0x%08X", intRtn));

                    // Set MSR interface available if MSR init succeeded
                    if (intRtn == 0) {
                        GlobalPara.isMSRInterfaceAvaliable = true;
                        Log.d(TAG, ">>> MSR interface ENABLED");

                        // v5.3-ATM: Track 2 DUKPT encryption DISABLED
                        // Clear Track 2 is now obtained via bin.json "format": "clear"
                        // PIN block encryption still uses DUKPT via KMS2 separately
                        // TLS protects Track 2 in transit to processor (PCI compliant)
                        Log.d(TAG, ">>> v5.3: Track 2 DUKPT encryption DISABLED - using clear Track 2 via bin.json");

                        // Set mask characters (still needed for display purposes)
                        msr.setTracksMaskChar((byte) '*');
                        msr.setPANMaskChar((byte) '*');
                        Log.d(TAG, ">>> v5.2: MSR mask chars set");
                    }
                }

                // v2.10: Add edl.initialize()
                if (edl != null) {
                    Log.d(TAG, ">>> v2.10: Calling edl.initialize()...");
                    intRtn = edl.initialize();
                    Log.d(TAG, ">>> v2.10: edl.initialize returned: " + String.format("0x%08X", intRtn));
                }

                // v2.13: Re-enable UI updates (fixed null checks)
                Log.d(TAG, ">>> v2.13: Calling UI updates (with null checks)...");
                GlobalPara.mainActivity.ui_EnableAllButton();
                GlobalPara.mainActivity.ui_ShowMsg("SDK Init OK\n");

                // v6.0-PERF: Mark config as loaded at startup
                emvConfigurationLoaded = true;
                Log.d(TAG, ">>> v6.0-PERF: EMV/EMVCL configuration loaded at startup");

                GlobalPara.isInitThreadFinish = true;
                Log.d(TAG, ">>> v2.13: thInit DONE <<<");
              } catch (Throwable t) {
                Log.e(TAG, "CRITICAL: thInit crashed: " + t.getClass().getName() + " - " + t.getMessage());
                GlobalPara.isInitThreadFinish = true;
              }
            }
        });

        //GlobalPara.mainActivity.ui_DisableAllButton();
        // TEST v2.6: RE-ENABLE thInit (SDK init) - special events disabled inside
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                android.util.Log.d(TAG, ">>> v2.6: Starting thInit (special events DISABLED) <<<");
                thInit.start();
            }
        }, 2000);

        GlobalPara.asciiPAN = null;
    }

    // Helper method for page navigation
    public void navigateToPage(int pageIndex) {
        Log.d(TAG, "navigateToPage() called with pageIndex=" + pageIndex + " (current=" +
            (mViewPager != null ? mViewPager.getCurrentItem() : "null") + ")");

        if (mViewPager != null && pageIndex >= 0 && pageIndex < mSectionsPagerAdapter.getCount()) {
            // If navigating to transaction page, force fragment refresh for ATM mode
            if (pageIndex == GlobalDef.d_PAGE_TRANSACTION) {
                Fragment fragment = mSectionsPagerAdapter.getCachedFragment(pageIndex);
                if (fragment instanceof Fragment_page_transaction) {
                    Log.d(TAG, "Calling checkAndRefreshMode on Transaction fragment");
                    ((Fragment_page_transaction) fragment).checkAndRefreshMode();
                }
            }
            // If navigating to receipt page, force fragment refresh to display results
            // ViewPager doesn't trigger onResume() for adjacent fragments
            if (pageIndex == GlobalDef.d_PAGE_RECEIPT) {
                Fragment fragment = mSectionsPagerAdapter.getCachedFragment(pageIndex);
                if (fragment instanceof Fragment_page_receipt) {
                    Log.d(TAG, "Calling refreshDisplay on Receipt fragment");
                    ((Fragment_page_receipt) fragment).refreshDisplay();
                }
            }
            Log.d(TAG, "navigateToPage() setting ViewPager to page " + pageIndex);
            mViewPager.setCurrentItem(pageIndex, false);
        } else {
            Log.e(TAG, "navigateToPage() FAILED: mViewPager=" + (mViewPager != null) +
                ", pageIndex=" + pageIndex + ", count=" +
                (mSectionsPagerAdapter != null ? mSectionsPagerAdapter.getCount() : "null"));
        }
    }

    // Helper method to access printer
    public CTOS_Printer getPrinter() {
        return Printer;
    }

    /**
     * Get the ATM Host Service instance.
     * May return null if not configured or not initialized.
     */
    public AtmHostService getAtmHostService() {
        synchronized (atmHostLock) {
            return atmHostService;
        }
    }

    /**
     * Check if ATM Host Service is available and initialized.
     */
    public boolean isAtmHostServiceReady() {
        synchronized (atmHostLock) {
            return atmHostService != null && atmHostService.isInitialized();
        }
    }

    /**
     * Request Host Totals (Type 87) from the processor.
     * Displays results in a dialog.
     */
    public void requestHostTotals() {
        Log.d(TAG, "requestHostTotals() called");

        if (!isAtmHostServiceReady()) {
            // Try to initialize first
            initializeAtmHostService();
        }

        final AtmHostService hostService = getAtmHostService();
        if (hostService == null) {
            new AlertDialog.Builder(this)
                .setTitle("Host Totals")
                .setMessage("Host service not configured.\n\nPlease configure host settings in Admin first.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }

        // Show progress dialog
        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("Requesting host totals...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Set up listener for host totals response
        hostService.setEventListener(new AtmHostService.AtmEventListener() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> progressDialog.setMessage(message));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Host Totals Error")
                        .setMessage("Failed to retrieve host totals:\n\n" + error)
                        .setPositiveButton("OK", null)
                        .show();
                });
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
            public void onHealthCheckResult(boolean success) {}

            @Override
            public void onHostTotalsReceived(castech.emvtxn.atm.host.HostTotalsResponse response) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();

                    if (response.isSuccess()) {
                        // Show totals in dialog
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Host Totals")
                            .setMessage(response.getSummary())
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Close Batch", (dialog, which) -> {
                                // Request totals with reset flag (closes batch on processor)
                                requestHostTotalsWithReset();
                            })
                            .show();
                    } else {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Host Totals Error")
                            .setMessage("Failed to retrieve host totals:\n\n" +
                                    (response.getErrorMessage() != null ? response.getErrorMessage() : "Unknown error"))
                            .setPositiveButton("OK", null)
                            .show();
                    }
                });
            }
        });

        // Request host totals (query only, no reset)
        hostService.requestHostTotals(false);
    }

    /**
     * Close Batch - Sends Host Totals (Type 87) with reset flag to processor.
     * This retrieves the current totals and then resets them on the processor side.
     */
    private void requestHostTotalsWithReset() {
        final AtmHostService hostService = getAtmHostService();
        if (hostService == null) {
            return;
        }

        // Confirm batch close
        new AlertDialog.Builder(this)
            .setTitle("Close Batch?")
            .setMessage("This will close the current batch and reset all transaction totals on the processor.\n\nAre you sure?")
            .setPositiveButton("Close Batch", (dialog, which) -> {
                // Show progress
                final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
                progressDialog.setMessage("Closing batch...");
                progressDialog.setCancelable(false);
                progressDialog.show();

                // Create a one-time listener for this batch close operation
                final AtmHostService.AtmEventListener originalListener = hostService.getEventListener();
                hostService.setEventListener(new AtmHostService.AtmEventListener() {
                    @Override
                    public void onHostTotalsReceived(castech.emvtxn.atm.host.HostTotalsResponse response) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            if (response.isSuccess()) {
                                // Print batch close receipt on background thread to avoid ANR
                                new Thread(() -> {
                                    printBatchCloseReceipt(response);
                                    runOnUiThread(() -> {
                                        new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("Batch Closed")
                                            .setMessage("Batch closed successfully.\n\n" + response.getSummary() + "\n\nReceipt printed.")
                                            .setPositiveButton("OK", null)
                                            .show();
                                    });
                                }).start();
                            } else {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Close Batch Failed")
                                    .setMessage("Failed to close batch:\n\n" +
                                            (response.getErrorMessage() != null ? response.getErrorMessage() : "Unknown error"))
                                    .setPositiveButton("OK", null)
                                    .show();
                            }
                            // Restore original listener
                            hostService.setEventListener(originalListener);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Close Batch Error")
                                .setMessage("Error closing batch:\n\n" + error)
                                .setPositiveButton("OK", null)
                                .show();
                            // Restore original listener
                            hostService.setEventListener(originalListener);
                        });
                    }

                    // Forward other callbacks to original listener
                    @Override public void onProgress(String message) { if (originalListener != null) originalListener.onProgress(message); }
                    @Override public void onTransactionApproved(String responseCode, String referenceNumber, String authDate, String authTime, long accountBalanceCents, long availableBalanceCents, String displayMessage) { if (originalListener != null) originalListener.onTransactionApproved(responseCode, referenceNumber, authDate, authTime, accountBalanceCents, availableBalanceCents, displayMessage); }
                    @Override public void onTransactionDeclined(String responseCode, String responseMessage, boolean retainCard) { if (originalListener != null) originalListener.onTransactionDeclined(responseCode, responseMessage, retainCard); }
                    @Override public void onBalanceReceived(String responseCode, long accountBalanceCents, long availableBalanceCents) { if (originalListener != null) originalListener.onBalanceReceived(responseCode, accountBalanceCents, availableBalanceCents); }
                    @Override public void onKeysLoaded(String keyCheckValue) { if (originalListener != null) originalListener.onKeysLoaded(keyCheckValue); }
                    @Override public void onReversalComplete(boolean success) { if (originalListener != null) originalListener.onReversalComplete(success); }
                    @Override public void onHealthCheckResult(boolean success) { if (originalListener != null) originalListener.onHealthCheckResult(success); }
                });

                // Request with reset flag (closes batch)
                hostService.requestHostTotals(true);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Abort any in-progress transaction.
     * Sets the abort flag and tries to interrupt the transaction thread.
     */
    public void abortTransaction() {
        Log.d(TAG, "abortTransaction() called");
        txnAborted = true;
        needsSdkReinit = true;  // Force full SDK re-init on next transaction

        // Cancel both EMVCL and EMV transactions to unblock any waiting SDK calls
        try {
            if (emvcl != null) {
                Log.d(TAG, "Calling emvcl.cancelTransaction() to unblock...");
                emvcl.cancelTransaction();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling emvcl.cancelTransaction: " + e.getMessage());
        }

        // Note: EMV SDK doesn't have a txnCancel() method
        // Full SDK re-init on next transaction (needsSdkReinit=true) will reset state

        // Wait for thread to exit
        if (threadTxn != null && threadTxn.isAlive()) {
            try {
                Log.d(TAG, "Waiting for transaction thread to exit...");
                threadTxn.join(3000);  // Wait up to 3 seconds
                if (threadTxn.isAlive()) {
                    Log.w(TAG, "Thread still alive after 3 seconds, interrupting");
                    threadTxn.interrupt();
                    // Give it one more second after interrupt
                    threadTxn.join(1000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error waiting for thread: " + e.getMessage());
            }
        }

        // Flush MSR buffer
        try {
            if (msr != null) {
                Log.d(TAG, "Flushing MSR tracks buffer");
                msr.flushTracksBuffer();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error flushing MSR: " + e.getMessage());
        }

        // Clear the thread reference
        threadTxn = null;

        // Reset state flags
        GlobalPara.atmHostCallInProgress = false;
        GlobalPara.atmTransactionInProgress = false;
        inCardDetectionLoop = false;

        // Re-enable UI buttons
        try {
            ui_EnableAllButton();
        } catch (Exception e) {
            Log.e(TAG, "Error enabling buttons: " + e.getMessage());
        }

        Log.d(TAG, "abortTransaction() complete - SDK will re-init on next transaction");
    }

    /**
     * Reset the abort flag before starting a new transaction.
     */
    public void resetAbortFlag() {
        txnAborted = false;
    }

    /**
     * Check if transaction has been aborted.
     */
    public boolean isTransactionAborted() {
        return txnAborted;
    }

    public class SectionsPagerAdapter extends FragmentPagerAdapter {
        private SparseArray<Fragment> map = new SparseArray<Fragment>();

        private MainActivity activity;


        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }


        public SectionsPagerAdapter(FragmentManager fm, MainActivity activity) {
            super(fm);
            this.activity = activity;
            //Log.d("SectionsPagerAdapter", "SectionsPagerAdapter()-->");
        }

        private Button btnTransaction = null;
        private Button btnClearMsg = null;
        private View view;

        @Override
        public Fragment getItem(int position) {
            // Check cache first
            Fragment cached = map.get(position);
            if (cached != null) {
                return cached;
            }

            // Create new fragment and cache it
            Fragment fragment = null;
            // ATM flow: Main Menu -> Amount Selection -> Transaction -> Receipt
            switch (position) {
                case 0:  // d_PAGE_IDLE
                    fragment = new Fragment_page_main_menu(this.activity);  // Idle shows main menu
                    break;
                case 1:  // d_PAGE_MAIN_MENU
                    fragment = new Fragment_page_main_menu(this.activity);
                    break;
                case 2:  // d_PAGE_AMOUNT_SELECTION
                    fragment = new Fragment_page_amount_selection(this.activity);  // ATM amount selection
                    break;
                case 3:  // d_PAGE_TRANSACTION
                    fragment = new Fragment_page_transaction(this.activity);
                    break;
                case 4:  // d_PAGE_RECEIPT
                    fragment = new Fragment_page_receipt(this.activity);  // ATM receipt
                    break;
                case 5:  // d_PAGE_SETTING
                    fragment = new Fragment_page_admin_atm(this.activity);  // ATM Admin (PIN-protected)
                    break;
            }

            if (fragment != null) {
                map.put(position, fragment);
            }
            return fragment;
        }

        /**
         * Get cached fragment instance by position
         */
        public Fragment getCachedFragment(int position) {
            return map.get(position);
        }

        @Override
        public int getCount() {
            return 6;  // All pages (Account Type disabled for now)
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case GlobalDef.d_PAGE_IDLE:
                    return "Idle";
                case GlobalDef.d_PAGE_MAIN_MENU:
                    return "Main Menu";
                case GlobalDef.d_PAGE_AMOUNT_SELECTION:
                    return "Amount";
                case GlobalDef.d_PAGE_TRANSACTION:
                    return "Transaction";
                case GlobalDef.d_PAGE_RECEIPT:
                    return "Receipt";
                case GlobalDef.d_PAGE_SETTING:
                    return "Admin";
            }
            return null;
        }

        /**
         * Returns the cached receipt fragment (or null if it hasn't been created yet).
         * Used by the host event listener to push reversal-progress updates into the
         * already-visible receipt page without making the user tap anything.
         */
        public Fragment_page_receipt getReceiptFragment() {
            Fragment f = map.get(GlobalDef.d_PAGE_RECEIPT);
            return (f instanceof Fragment_page_receipt) ? (Fragment_page_receipt) f : null;
        }
    }

    public int btnClearMsg_Click(View view) {
        ui_ClearMsg();

        return 0;
    }


    public int SeparateSelectAPI_appSelectedConfirm(EMVCandidateList candidate) {
        short shRtn = (short) 0xFFFF;

        GlobalPara.appSelectedConfirmOK = 0;

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Please Confirm to Execute Following App_");
        builder.setIcon(R.drawable.arrow);
        builder.setMessage(new String(candidate.candidateBuf[0].appLabel));

        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                GlobalPara.appSelectedConfirmOK = 1;
            }

        });
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                GlobalPara.appSelectedConfirmOK = 2;
            }

        });


        this.runOnUiThread(new Runnable() {
                               @Override
                               public void run() {
                                   AlertDialog ad;
                                   ad = builder.create();
                                   ad.setCancelable(false);
                                   ad.setCanceledOnTouchOutside(false);
                                   ad.show();
                               }
                           }
        );

        do {
            MyUtility.sleep(500);
        } while (GlobalPara.appSelectedConfirmOK == 0);


        if (GlobalPara.appSelectedConfirmOK == 1) {
            return 0;
        }

        return 1;
    }

    public short SeparateSelectAPI_appListSelect(EMVCandidateList candidate) {
        final String TAG;

        TAG = GlobalPara.tag;
        GlobalPara.appListOK = false;
        GlobalPara.appSelectedIndex = 0;

        ClsListViewAdapter myAdapter = new ClsListViewAdapter(this);
        for (int i = 0; i < candidate.candidateNum; i++) {
            String appLabel = new String(candidate.candidateBuf[i].appLabel);
            myAdapter.addItem(appLabel, String.format("App %d\n", i + 1));
        }

        View view = this.getLayoutInflater().inflate(R.layout.list_view_layout, null);
        ListView lv = (ListView) view.findViewById(R.id.id_ListView);
        lv.setAdapter(myAdapter.getAdapter());
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(final AdapterView<?> adapterView, View view, final int pos, long id) {
                Log.d(TAG, "SeparateSelectAPI_appListSelect onItemClick : " + String.format("%d", pos));
                GlobalPara.appSelectedIndex = (short) (pos);
                GlobalPara.appListOK = true;
                GlobalPara.alertDialog.dismiss();
            }
        });

        final AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this);
        builder.setTitle("Please Select One App to Execute_");
        builder.setIcon(R.drawable.arrow);
        builder.setView(view);
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog ad = builder.create();
                ad.getWindow().setBackgroundDrawableResource(R.drawable.alert_dialog_shape);
                ad.setCancelable(false);
                ad.setCanceledOnTouchOutside(false);
                ad.show();
                GlobalPara.alertDialog = ad;
            }
        });

        do {
            MyUtility.sleep(500);
        } while (GlobalPara.appListOK == false);

        //Range of appSelectedIndexValue is 0 to (candidate.candidateNum - 1)
        return GlobalPara.appSelectedIndex;
    }

    public int btnTransaction_Click(final View view) {
        Log.d(TAG, "btnTransaction_Click() ***");

        // Show immediate feedback that button was clicked
        ui_ShowMsg("BTN CLICKED!\n");

        // Prevent starting a new transaction while one is in progress
        if (GlobalPara.atmTransactionInProgress) {
            Log.w(TAG, "Transaction already in progress, ignoring click");
            ui_ShowMsg("ERROR: Txn already in progress\n");
            return 0;
        }

        // Readiness gate: don't begin card processing until the host service is up
        // and a working key is loaded. Starting a transaction too soon after boot
        // (before the async Type 88 key download finishes) reached PIN encryption
        // with no key and abended. Applies to the MKSK build only — a DUKPT build
        // has its PIN key injected in hardware and needs no working-key download.
        //
        // The transaction page auto-starts in kiosk mode, so rather than dead-end on
        // "please wait", poll until ready and then proceed automatically (bounded).
        if (!isTransactionReady()) {
            if (txnReadyRetryCount < TXN_READY_MAX_RETRIES) {
                txnReadyRetryCount++;
                Log.w(TAG, "Transaction not ready — waiting for key download (attempt "
                        + txnReadyRetryCount + "/" + TXN_READY_MAX_RETRIES
                        + ", hostReady=" + isAtmHostServiceReady()
                        + ", workingKey=" + (atmHostService != null && atmHostService.hasWorkingKeys()) + ")");
                ui_ShowMsg("Terminal starting up —\nplease wait...");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        btnTransaction_Click(view);
                    }
                }, TXN_READY_RETRY_INTERVAL_MS);
                return 0;
            }
            // Gave up after the retry window — surface a clear message, don't proceed.
            Log.e(TAG, "Transaction blocked — terminal still not ready after "
                    + TXN_READY_MAX_RETRIES + " retries");
            txnReadyRetryCount = 0;
            ui_ShowMsg("Terminal not ready —\nplease try again shortly");
            return 0;
        }
        txnReadyRetryCount = 0;  // ready — reset for next time

        ui_ShowMsg("Starting transaction...\n");

        // NOTE: SDK re-initialization is done INSIDE the thread (more thorough)
        // Removed duplicate pre-thread initialization to reduce delay

        // Simple cleanup - just clear old thread reference (NO SDK calls)
        if (threadTxn != null) {
            threadTxn = null;
        }

        // Reset flags and mark transaction as in progress
        resetAbortFlag();
        inCardDetectionLoop = false;  // Will be set true when we enter the loop
        GlobalPara.atmTransactionInProgress = true;
        GlobalPara.resetATMHostResponse();

        ui_ShowMsg("Creating thread...\n");
        Log.d(TAG, "About to create Thread object");

        Thread newThread = null;
        Runnable txnRunnable = null;

        try {
            Log.d(TAG, "Creating transaction Runnable");

            txnRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Transaction thread started");

                // DUKPT diagnostic tests REMOVED - they were slowing down transactions
                // and incrementing the KSN counter unnecessarily
                // To run diagnostics, use the admin screen or call manually

              try {
                // Variable declarations
                short shRtn = (short) 0xFFFF;
                int intRtn = 0xFFFFFFFF;
                String cardType;
                String CVMStr;
                short EMVCLTxResult;
                byte action = 0x00;
                byte ARC[] = new byte[2];
                int IADLen;
                byte IAD[] = new byte[128];
                int ScriptLen;
                byte Script[] = new byte[256];
                byte amount[] = new byte[128];
                byte te[] = new byte[16];
                String tempAmount;
                boolean isCTAvaliable;
                boolean isCLAvaliable;
                boolean isMSRAvaliable;
                byte entryMode;
                short selectedIndex;
                EMVDataGetExPara authRequestData = new EMVDataGetExPara();

                // Set up date/time
                Date dateTime = new Date();
                String strDate;
                String strTime;
                SimpleDateFormat sdf;

                sdf = new SimpleDateFormat("yyyy/MM/dd");
                strDate = sdf.format(dateTime);
                sdf = new SimpleDateFormat("HH:mm:ss");
                strTime = sdf.format(dateTime);

                GlobalPara.DateTime = strDate + " " + strTime;

                // Disable UI buttons during transaction
                ui_DisableTxnButton();
                ui_DisableGetPinButton();
                ui_DisableManualEntry();
                ui_DisableEncryp();
                ui_DisableSetting();

                ui_ClearMsg();
                ui_ShowMsg("Initializing...\n");

                // Pre-check: Verify SDK initialization before proceeding
                if (!sdkInitialized) {
                    Log.e(TAG, "SDK not initialized! Cannot process transaction.");
                    ui_ShowMsg("Error: SDK not initialized. Please restart app.");
                    GlobalPara.atmTransactionInProgress = false;
                    ui_EnableAllButton();
                    return;
                }

                // Verify critical SDK objects
                if (emv == null || emvcl == null || msr == null || sc == null) {
                    Log.e(TAG, "Critical SDK objects are null! emv=" + (emv != null) +
                            ", emvcl=" + (emvcl != null) + ", msr=" + (msr != null) + ", sc=" + (sc != null));
                    ui_ShowMsg("Error: Card reader not ready. Please restart app.");
                    GlobalPara.atmTransactionInProgress = false;
                    ui_EnableAllButton();
                    return;
                }

                // Load json.bin
                load_json();

                // v6.0-PERF: Optimized per-transaction EMV setup
                // - Skip SetConfiguration if already loaded (saves ~500ms)
                // - Keep initialize() to reset transaction state
                // - Keep event registration and whitelist (needed per transaction)
                try {
                    if (emv != null && emvEvent != null) {
                        Log.d(TAG, "v6.0-PERF: EMV per-transaction setup (configLoaded=" + emvConfigurationLoaded + ")");
                        emvEvent.version = 1;
                        int emvInitRtn = emv.initialize(emvEvent);
                        Log.d(TAG, "emv.initialize() returned: " + String.format("0x%08X", emvInitRtn));

                        if (emvInitRtn == 0) {
                            emv.setDebug((byte)0, (byte)0x00);  // Debug disabled for performance

                            // ALWAYS reload config after initialize() - emv.initialize() clears config state
                            // The v6.0-PERF optimization only applies to thInit (startup/navigation),
                            // NOT per-transaction setup, because initialize() resets the kernel.
                            Log.d(TAG, "v6.0-PERF: Loading EMV config (required after initialize)");
                            int configRtn = SetConfiguration("emv_config.xml", true);
                            Log.d(TAG, "SetConfiguration returned: " + String.format("0x%08X", configRtn));

                            // Re-register event callbacks (required after initialize)
                            if (myEMVSpEvent != null) {
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_SHOW_VIRTUAL_PIN_EX, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_GET_PIN_DONE, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_ONLINE_PINBLOCK_GET, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_PIN_BYPASS, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_APP_LIST_ALWAYS, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                                emv.specialEventRegister((byte) CtEMV.d_EVENTID_CAPK_GET, (CtEMV.IEMVSpecialEvent) myEMVSpEvent);
                            }

                            // CRITICAL: Re-apply whitelist after initialize() - enables clear PAN
                            try {
                                byte[] whitelistData = Converter.hexString2ByteArray("0000000A0131013201330134013501360137013801390130");
                                int wlRtn = emv.secureDataWhitelistSet((byte) 0, whitelistData, whitelistData.length);
                                Log.d(TAG, "EMV secureDataWhitelistSet returned: " + String.format("0x%08X", wlRtn));
                            } catch (Exception wle) {
                                Log.e(TAG, "EMV whitelistSet error: " + wle.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in EMV per-transaction setup: " + e.getMessage());
                }

                // v6.0-PERF: Optimized per-transaction EMVCL setup
                try {
                    if (emvcl != null) {
                        Log.d(TAG, "v6.0-PERF: EMVCL per-transaction setup (configLoaded=" + emvConfigurationLoaded + ")");
                        int emvclInitRtn = emvcl.initialize();
                        Log.d(TAG, "emvcl.initialize() returned: " + String.format("0x%08X", emvclInitRtn));

                        if (emvclInitRtn == 0) {
                            // ALWAYS reload config after initialize() - emvcl.initialize() clears config state
                            Log.d(TAG, "v6.0-PERF: Loading EMVCL config (required after initialize)");
                            try {
                                InputStream xmlStream = getApplicationContext().getAssets().open("emvcl_config.xml");
                                emvcl.setConfiguration(xmlStream);
                            } catch (Exception ce) {
                                Log.e(TAG, "Error loading EMVCL config: " + ce.getMessage());
                            }

                            // CRITICAL: Re-apply whitelist after initialize() - enables clear PAN
                            try {
                                byte[] whitelistData = Converter.hexString2ByteArray("0000000A0131013201330134013501360137013801390130");
                                int wlRtn = emvcl.secureDataWhitelistSet((byte) 0, whitelistData, whitelistData.length);
                                Log.d(TAG, "EMVCL secureDataWhitelistSet returned: " + String.format("0x%08X", wlRtn));
                            } catch (Exception wle) {
                                Log.e(TAG, "EMVCL secureDataWhitelistSet error: " + wle.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in EMVCL per-transaction setup: " + e.getMessage());
                }

                // v6.0-PERF: Mark config as loaded after first successful transaction setup
                if (!emvConfigurationLoaded) {
                    emvConfigurationLoaded = true;
                    Log.d(TAG, "v6.0-PERF: EMV configuration marked as loaded - future transactions will be faster");
                }

                // Flush MSR buffer
                try {
                    if (msr != null) {
                        msr.flushTracksBuffer();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error flushing MSR: " + e.getMessage());
                }

                // Set up event handlers with null checks
                if (myEMVSpEvent != null) {
                    myEMVSpEvent.setVirtualPINUIComponent(MainActivity.this, mViewPager);
                }
                if (emvEvent != null) {
                    emvEvent.setUIComponent(mViewPager);
                }

                EMVCLRcDataEx rcData = new EMVCLRcDataEx();
                EMVCLActData actData = new EMVCLActData();

                do {
                    CheckBox cbox = (CheckBox) findViewById(R.id.checkBox);
                    GlobalPara.isQuickChipTransaction = false;
                    if (cbox != null && cbox.isChecked() == true) {
                        GlobalPara.isQuickChipTransaction = true;
                    }

                    GlobalPara.isNeedSignature = false;
                    GlobalPara.cardType = " ";
                    GlobalPara.asciiPAN = " ";
                    Log.d(TAG, "emv test start =========================================>");

                    actData.transactionData = new byte[128];
                    te = Converter.hexString2ByteArray("9F0206");
                    System.arraycopy(te, 0, actData.transactionData, 0, 3);

                    // In ATM mode, amount is already set from amount selection screen
                    // Only read from EditText if NOT in ATM mode
                    try {
                        if (GlobalPara.atmSelectedAmount == null || "0.00".equals(GlobalPara.atmSelectedAmount)) {
                            // Balance inquiry or non-ATM mode - read from EditText field
                            if (GlobalPara.edtamount != null) {
                                GlobalPara.strAmount = GlobalPara.edtamount.getText().toString();
                            } else {
                                GlobalPara.strAmount = "0";
                            }
                        }
                        // else: ATM mode - amount already set in Fragment_page_amount_selection

                        // Ensure strAmount is never null
                        if (GlobalPara.strAmount == null || GlobalPara.strAmount.isEmpty()) {
                            GlobalPara.strAmount = "0";
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting amount: " + e.getMessage());
                        GlobalPara.strAmount = "0";
                    }

                    Log.d(TAG, "ATM Mode: atmSelectedAmount=" + GlobalPara.atmSelectedAmount + ", strAmount=" + GlobalPara.strAmount);
                    ui_ShowLog("amount:" + GlobalPara.strAmount);

                    tempAmount = Converter.amtPadding(GlobalPara.strAmount);
                    amount = Converter.hexString2ByteArray(tempAmount);
                    System.arraycopy(amount, 0, actData.transactionData, 3, 6);

                    // Transaction Type (9C): 00=Purchase, 01=Cash (ATM), 09=Cashback, 20=Refund, 30=Balance Inquiry
                    // EMV Book 4: 0x01 is Cash/ATM Withdrawal, NOT 0x30 (which is Balance Inquiry)
                    byte tempData[];
                    if (GlobalPara.atmMode) {
                        tempData = Converter.hexString2ByteArray("9F03060000000000009C0101DF9F04020101DF9F05020101"); // 9C=01 (Cash/ATM)
                        Log.d(TAG, "ATM MODE: Setting Transaction Type 9C=0x01 (Cash/ATM Withdrawal) for ARQC");
                    } else {
                        tempData = Converter.hexString2ByteArray("9F03060000000000009C0100DF9F04020101DF9F05020101"); // 9C=00 (Purchase)
                    }
                    //byte tempData[] = Converter.hexString2ByteArray("9F03060000000000009C0120DF9F04020101DF9F05020101"); // Refund.
                    System.arraycopy(tempData, 0, actData.transactionData, 9, tempData.length);

                    actData.start = 0;
                    actData.tagNum = 3;
                    actData.transactionDataLen = 21;

                    emvcl.initTransactionEx(actData.tagNum, actData.transactionData, actData.transactionDataLen);
                    Log.d(TAG, "initTransaction done");

                    isCTAvaliable = GlobalPara.isContactInterfaceAvaliable;
                    isCLAvaliable = GlobalPara.isContactlessInterfaceAvaliable;
                    isMSRAvaliable = GlobalPara.isMSRInterfaceAvaliable;
                    entryMode = 0;

                    // v5.3-ATM: MSR retry counter (industry standard: 3 attempts)
                    int msrRetryCount = 0;
                    final int MSR_MAX_RETRIES = 3;

                    // Enhanced logging for debugging
                    Log.d(TAG, "=== CARD DETECTION STARTING ===");
                    Log.d(TAG, "CT Available: " + isCTAvaliable + " (sc=" + (sc != null) + ")");
                    Log.d(TAG, "CL Available: " + isCLAvaliable + " (emvcl=" + (emvcl != null) + ")");
                    Log.d(TAG, "MSR Available: " + isMSRAvaliable + " (msr=" + (msr != null) + ")");
                    Log.d(TAG, "ATM Mode: amount=" + GlobalPara.atmSelectedAmount + ", BI=" + GlobalPara.atmBalanceInquiryMode);
                    Log.d(TAG, "strAmount=" + GlobalPara.strAmount);

                    ui_ShowLog("CT Avaliable : " + String.valueOf(isCTAvaliable));
                    ui_ShowLog("CL Avaliable : " + String.valueOf(isCLAvaliable));
                    ui_ShowLog("MSR Avaliable : " + String.valueOf(isMSRAvaliable));

                    //detect 3 type of card

                    if (isCLAvaliable == true && GlobalPara.clLED != null) {
                        try {
                            GlobalPara.clLED.stopIdleLEDBehavior();
                        } catch (Exception e) {
                            Log.e(TAG, "Error stopping LED: " + e.getMessage());
                        }
                    }

                    // v5.3-ATM: MSR retry loop label
                    msrRetryLoop:
                    while (true) {
                    // Card detection loop
                    ui_ShowMsg("Insert card\n");
                    inCardDetectionLoop = true;

                    do {
                        // Check for abort
                        if (txnAborted) {
                            Log.d(TAG, "Abort detected - breaking loop");
                            break;
                        }

                        // Contactless
                        if (isCLAvaliable == true) {
                            intRtn = emvcl.performTransactionEx(rcData);
                            if (intRtn != 0x80000020) {
                                Log.d(TAG, "CL Rtn: " + String.format("0x%08X", intRtn));
                                entryMode = GlobalDef.d_ENTRY_MODE_CL;
                                break;
                            }
                        }

                        if (txnAborted) { break; }

                        // MSR
                        if (isMSRAvaliable == true) {
                            intRtn = msr.readTracks();
                            if (intRtn == 0 || (intRtn != 0 && intRtn != CtEMVMSR.d_EMVMSR_ERR_NO_SWIPE)) {
                                entryMode = GlobalDef.d_ENTRY_MODE_MSR;
                                break;
                            }
                        }

                        if (txnAborted) { break; }

                        // Contact
                        if (isCTAvaliable == true) {
                            sc.status(0);
                            int status = sc.getStatus();
                            if ((status & 0x01) == 0x01) {
                                entryMode = GlobalDef.d_ENTRY_MODE_CT;
                                break;
                            }
                        }

                        // Sleep between card detection polls (150ms balances responsiveness vs CPU)
                        try { Thread.sleep(150); } catch (InterruptedException ie) { break; }

                    } while (true);

                    inCardDetectionLoop = false;  // Exited the loop
                    Log.d(TAG, "Card detection ended, entryMode=" + entryMode);

                    // If cancelled (no card detected), exit cleanly
                    if (entryMode == 0) {
                        Log.d(TAG, "No card detected - exiting");
                        ui_ShowMsg("Cancelled\n");
                        // cancelTransaction was already called by abortTransaction()
                        GlobalPara.atmTransactionInProgress = false;
                        ui_EnableAllButton();
                        return;
                    }

                    if (entryMode == GlobalDef.d_ENTRY_MODE_CT) {
                        Log.d(TAG, "=== CHIP CARD PROCESSING STARTING ===");
                        Log.d(TAG, "emv object: " + (emv != null));
                        Log.d(TAG, "useSeparateSelectAPI: " + GlobalPara.useSeparateSelectAPI);
                        Log.d(TAG, "strAmount: " + GlobalPara.strAmount);
                        Log.d(TAG, "atmSelectedAmount: " + GlobalPara.atmSelectedAmount);
                        Log.d(TAG, "atmBalanceInquiryMode: " + GlobalPara.atmBalanceInquiryMode);

                      try {
                    //    txnCardAcquisition(); //These functions are not needed to run a transaction  UC-4415
                        //preferredOrder("07A0000000291010");
                        //appFilteringPara("01000005A000000003000005A000000025");

                        if (GlobalPara.useSeparateSelectAPI == 0) {
                            ui_ShowMsg("Processing ...");

                            Log.d(TAG, "txnAppSelect ***********************************************");
                            EMVAppInfo selectedAppInfo = new EMVAppInfo();
                            selectedAppInfo.version = 1;
                            selectedAppInfo.aid = new byte[16];
                            selectedAppInfo.aidLen = 0;
                            selectedAppInfo.appLabel = new byte[33];
                            selectedAppInfo.appLabelLen = 0;
                            intRtn = emv.txnAppSelect(selectedAppInfo);
                            ui_ShowLog("txnAppSelect Rtn: " + String.format("0x%08X", intRtn));
                            if (intRtn != 0) {
                                break;
                            }
                        } else    //using low level select API
                        {

                            ui_ShowMsg("Processing ...");

                            selectedIndex = (short) 0xFF;

                            Log.d(TAG, "txnCandidateList ***********************************************");
                            EMVCandidateList candidate = new EMVCandidateList();
                            candidate.version = 1;
                            candidate.maxCandidateNum = 50;
                            candidate.candidateBuf = new EMVCandidateData[50];
                            for (int i = 0; i < candidate.maxCandidateNum; i++) {
                                candidate.candidateBuf[i] = new EMVCandidateData();
                            }
                            candidate.newSel = 1;
                            intRtn = emv.txnCandidateList(candidate);
                            ui_ShowLog("txnCandidateList Rtn: " + String.format("0x%08X", intRtn));
                            if (intRtn != 0) {
                                ui_ShowMsg("txnCandidateList Rtn: " + String.format("0x%08X", intRtn));
                                break;
                            } else {
                                Log.d(TAG, "txnPSERsp ***********************************************");
                                EMVTxnPSERsp rsp = new EMVTxnPSERsp();
                                rsp.version = 1;
                                rsp.maxRspBufSize = 256;
                                rsp.rspBuf = new byte[256];
                                intRtn = emv.txnPSERsp(rsp);
                                ui_ShowLog("txnPSERsp Rtn: " + String.format("0x%08X", intRtn));
                                if (intRtn == 0) {
                                    Log.d(TAG, "PSE Response : " + Converter.byteArray2HexString(rsp.rspBuf, rsp.rspBufLen));
                                }

                                Log.d(TAG, "txnCandidateDirEntry ***********************************************");
                                EMVCandidateDirEntry entry = new EMVCandidateDirEntry();
                                entry.version = 1;
                                entry.maxDirEntrySize = 256;
                                entry.dirEntry = new byte[256];
                                for (int i = 0; i < candidate.candidateNum; i++) {
                                    Log.d(TAG, "Candidate List index : " + String.format("%d", i));
                                    Log.d(TAG, "label : " + new String(candidate.candidateBuf[i].appLabel));
                                    Log.d(TAG, "aid : " + Converter.byteArray2HexString(candidate.candidateBuf[i].aid, candidate.candidateBuf[i].aidLen));

                                    entry.appIdx = i;
                                    intRtn = emv.txnCandidateDirEntry(entry);
                                    ui_ShowLog("txnCandidateDirEntry Rtn: " + String.format("0x%08X", intRtn));
                                    if (intRtn == 0) {
                                        Log.d(TAG, "txnCandidateDirEntry : " + Converter.byteArray2HexString(entry.dirEntry, entry.dirEntryLen));
                                    }

                                    Log.d(TAG, "------------------------");
                                }

                                if (candidate.candidateNum == 0) {
                                    // Set decline result so receipt page shows error
                                    if (GlobalPara.atmMode) {
                                        GlobalPara.transactionResult = 0x0003; // Decline
                                        GlobalPara.atmResponseCode = "NO_APP";
                                        GlobalPara.atmResponseMessage = "No payment application found on card";
                                        GlobalPara.atmHostCallSuccess = false;
                                    }
                                    ui_ShowMsg("Transaction Error ! No Candidate");
                                    break;
                                } else if (candidate.candidateNum == 1 && candidate.isCardholderConfirmation == true) {
                                    intRtn = SeparateSelectAPI_appSelectedConfirm(candidate);
                                    if (intRtn == 0) {
                                        selectedIndex = 0;
                                    } else {
                                        // Set decline result so receipt page shows error
                                        if (GlobalPara.atmMode) {
                                            GlobalPara.transactionResult = 0x0003; // Decline
                                            GlobalPara.atmResponseCode = "USER_CANCEL";
                                            GlobalPara.atmResponseMessage = "Transaction cancelled by user";
                                            GlobalPara.atmHostCallSuccess = false;
                                        }
                                        ui_ShowMsg("User Denied !");
                                        break;
                                    }
                                } else if (candidate.candidateNum == 1) {
                                    selectedIndex = 0;
                                } else if (candidate.candidateNum > 1) {
                                    selectedIndex = SeparateSelectAPI_appListSelect(candidate);
                                }
                            }

                            boolean isbuildCandidateLstAgain = false;
                            boolean isSelectSuccess = false;
                            do {
                                isbuildCandidateLstAgain = false;
                                isSelectSuccess = false;

                                Log.d(TAG, "txnFinalSelection ***********************************************");
                                intRtn = emv.txnFinalSelection(selectedIndex);
                                ui_ShowLog("txnFinalSelection Rtn: " + String.format("0x%08X", intRtn));
                                if (intRtn == 0) {
                                    isSelectSuccess = true;
                                    break;
                                } else if (intRtn == 4) {
                                    isbuildCandidateLstAgain = true;
                                    ui_ShowMsg("Select Fail, Need Build Candidate List Again !");
                                } else if (intRtn != 0) {
                                    break;
                                }

                                if (isbuildCandidateLstAgain == true) {
                                    Log.d(TAG, "txnCandidateList 2 ***********************************************");
                                    candidate.candidateBuf = new EMVCandidateData[50];
                                    for (int i = 0; i < candidate.maxCandidateNum; i++) {
                                        candidate.candidateBuf[i] = new EMVCandidateData();
                                    }
                                    candidate.newSel = 0;
                                    intRtn = emv.txnCandidateList(candidate);
                                    ui_ShowLog("txnCandidateList Rtn: " + String.format("0x%08X", intRtn));
                                    if (intRtn != 0) {
                                        break;
                                    } else if (candidate.candidateNum == 0) {
                                        break;
                                    } else {
                                        selectedIndex = SeparateSelectAPI_appListSelect(candidate);
                                    }

                                }

                            } while (isSelectSuccess == false);


                            if (isSelectSuccess == false) {
                                // Set decline result so receipt page shows error
                                if (GlobalPara.atmMode) {
                                    GlobalPara.transactionResult = 0x0003; // Decline
                                    GlobalPara.atmResponseCode = "APP_SEL";
                                    GlobalPara.atmResponseMessage = "Card application selection failed";
                                    GlobalPara.atmHostCallSuccess = false;
                                }
                                ui_ShowMsg("Transaction Error !");
                                break;
                            }
                        }

                        //Delete all
                        //intRtn = emv.secureDataWhitelistSet((byte)1, listData, listDatLen);

                        TlvData tlvData = new TlvData();
                        tlvData.version = 1;
                        tlvData.value = new byte[256];

                        //EMVDataGetExPara
                        EMVDataGetExPara reqData = new EMVDataGetExPara();
                        //	String tagList = "9F095A57DF30DF31DF32DF33DF34DF35DFC4DFC2DFC3";
                        // Added 5F24 (Expiration Date) and 5F30 (Service Code) for Track 2 building
                        String tagList = "9F095A57DF30DF31DF32DF33DF345F245F30DFC4DFC2DFC3";

                        reqData.tagList = Converter.hexString2ByteArray(tagList);
                        reqData.tagListLen = (tagList.length() / 2);
                        reqData.maxTlvBufSize = 256;
                        reqData.tlvBuf = new byte[256];
                        intRtn = emv.dataGetEx(reqData);
                        ui_ShowLog("dataGetEx Rtn: " + String.format("0x%08X", intRtn));
                        if (intRtn == 0) {
                            //Succeed

                            tlvUtility_ct.TLVDataClear();
                            tlvUtility_ct.TLVDataParse(reqData.tlvBuf, reqData.tlvLen);
                            Log.d(TAG, "TLVData(UtilityDB) : " + Converter.byteArray2HexString(tlvUtility_ct.TLVDataBase, tlvUtility_ct.intTLVDataBaseLen));
                            Log.d(TAG, "reqData.tlvBuf     : " + Converter.byteArray2HexString(reqData.tlvBuf, reqData.tlvLen));

                            //Version
                            TLVData.tag = 0x9F09;
                            TLVData.len = 256;
                            TLVData.value = new byte[256];
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(9F09):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(9F09)");
                            }

                            //PAN--------------------------------------
                            TLVData.tag = 0x5A;
                            TLVData.len = 256;
                            TLVData.value = new byte[256];
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                String tag5aHex = Converter.byteArray2HexString(TLVData.value, TLVData.len);
                                Log.d(TAG, "TagData(5A):" + castech.emvtxn.util.PanMasker.maskHex(tag5aHex));
                                // ATM MODE: Store CLEAR PAN from Tag 5A for PIN block Format 0
                                // Tag 5A is BCD-encoded PAN, may have trailing F padding
                                if (GlobalPara.atmMode) {
                                    String clearPan = tag5aHex.toUpperCase().replaceAll("F+$", "");
                                    GlobalPara.atmClearPan = clearPan;
                                    Log.d(TAG, "ATM: CLEAR PAN from Tag 5A stored: " + clearPan.substring(0, Math.min(6, clearPan.length())) + "****");
                                }
                            } else {
                                Log.d(TAG, "NoTag(5A)");
                            }

                            //MASK
                            TLVData.tag = 0xDF32;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(DF32):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                                GlobalPara.asciiPAN = new String(TLVData.value, 0, TLVData.len);

                                Log.d(TAG, "tag :" + "0xDF32 (PAN mask)");
                                Log.d(TAG, "value :" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                                Log.d(TAG, "ASCII :" + GlobalPara.asciiPAN);
                                ui_ShowMsg("PAN :" + GlobalPara.asciiPAN);
                                // Removed 1.3s sleep - PAN visible during PIN entry
                            } else {
                                Log.d(TAG, "NoTag(DF32)");
                            }

                            //Checksum
                            TLVData.tag = 0xDF31;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "tag :" + "0xDF31 (PAN checksum)");
                                Log.d(TAG, "TagData(DF31):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(DF31)");
                            }

                            //Encrypt
                            TLVData.tag = 0xDF30;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "tag :" + "0xDF30 (PAN encrypt)");
                                Log.d(TAG, "TagData(DF30):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(DF30)");
                            }

                            ///Track 2--------------------------------------
                            TLVData.tag = 0x57;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(57): [masked]");
                            } else {
                                Log.d(TAG, "NoTag(57)");
                            }

                            //Encrypt Checksum
                            TLVData.tag = 0xDF34;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "tag :" + "0xDF34 (Track2 checksum)");
                                Log.d(TAG, "TagData(DF34):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(DF34)");
                            }

                            // ATM MODE: Try to get CLEAR Track 2 via direct emv.dataGet() like CastlesHost
                            // CastlesHost uses direct API call, not TLV parsing
                            if (GlobalPara.atmMode) {
                                TlvData df35Data = new TlvData();
                                df35Data.tag = 0xDF35;
                                df35Data.len = 40;
                                df35Data.value = new byte[40];
                                int df35Rtn = emv.dataGet(df35Data);
                                Log.d(TAG, "ATM: Direct emv.dataGet(DF35) rtn=" + String.format("0x%08X", df35Rtn) + ", len=" + df35Data.len);
                                if (df35Rtn == 0 && df35Data.len > 0) {
                                    // Convert BCD-encoded Track 2 to ASCII string
                                    // Format: BCD bytes where D=separator, F=padding
                                    String hexTrack2 = Converter.byteArray2HexString(df35Data.value, df35Data.len);
                                    Log.d(TAG, "ATM: DF35 raw hex: [masked]");
                                    // Convert to ISO 7813 format: ;PAN=YYMM...?
                                    String clearTrack2 = ";" + hexTrack2.toUpperCase()
                                            .replace("D", "=")
                                            .replaceAll("F+$", "") + "?";
                                    Log.d(TAG, "ATM: Track2 from DF35: [masked]");
                                    GlobalPara.atmTrack2Data = clearTrack2;
                                    // Extract clear PAN for PIN translation
                                    if (clearTrack2.contains("=")) {
                                        String clearPan = clearTrack2.replace(";", "").split("=")[0];
                                        GlobalPara.atmClearPan = clearPan;
                                        Log.d(TAG, "ATM: PAN extracted: " + castech.emvtxn.util.PanMasker.maskPan(clearPan));
                                    }
                                } else {
                                    Log.w(TAG, "ATM: DF35 not available via direct dataGet, falling back to DF33/DF32");
                                }
                            }

                            //Encrypt
                            TLVData.tag = 0xDF33;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0 && TLVData.len > 0) {
                                Log.d(TAG, "tag :" + "0xDF33 (Track2 encrypt)");
                                Log.d(TAG, "TagData(DF33):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                                // Store encrypted Track 2 for ATM host transactions (chip cards)
                                if (GlobalPara.atmTrack2Data == null || GlobalPara.atmTrack2Data.isEmpty() || GlobalPara.atmTrack2Data.contains("*")) {
                                    GlobalPara.atmTrack2Data = "E:" + Converter.byteArray2HexString(TLVData.value, TLVData.len);
                                    Log.d(TAG, "Stored ATM Track2 Data (CT encrypted): " + GlobalPara.atmTrack2Data);
                                }
                            } else {
                                Log.d(TAG, "NoTag(DF33) or empty, trying DF32 (masked)");
                                // Try masked Track 2 (DF32) as fallback
                                TLVData.tag = 0xDF32;
                                TLVData.len = 256;
                                intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                                if (intRtn == 0 && TLVData.len > 0) {
                                    // DF32 is ASCII masked PAN - need to build proper Track 2 format
                                    String maskedPan = new String(TLVData.value, 0, TLVData.len);
                                    Log.d(TAG, "DF32 masked PAN: " + maskedPan);

                                    // Try to get expiration date from tag 5F24
                                    String expiry = "0000"; // Default if not found
                                    TLVData.tag = 0x5F24;
                                    TLVData.len = 256;
                                    TLVData.value = new byte[256];
                                    int expRtn = tlvUtility_ct.TLVDataGet(TLVData);
                                    if (expRtn == 0 && TLVData.len >= 2) {
                                        // 5F24 is YYMMDD in BCD, we need YYMM
                                        expiry = Converter.byteArray2HexString(TLVData.value, 2);
                                        Log.d(TAG, "5F24 expiry (YYMM): " + expiry);
                                    } else {
                                        Log.w(TAG, "NoTag(5F24) - using default expiry 0000");
                                    }

                                    // Try to get service code from tag 5F30
                                    String serviceCode = "201"; // Default for chip card (international chip, no restrictions)
                                    TLVData.tag = 0x5F30;
                                    TLVData.len = 256;
                                    TLVData.value = new byte[256];
                                    int svcRtn = tlvUtility_ct.TLVDataGet(TLVData);
                                    if (svcRtn == 0 && TLVData.len >= 2) {
                                        // 5F30 is 2 bytes BCD (e.g., 02 01 = 201)
                                        serviceCode = Converter.byteArray2HexString(TLVData.value, TLVData.len);
                                        // Remove leading zeros if present (e.g., "0201" -> "201")
                                        if (serviceCode.startsWith("0") && serviceCode.length() > 3) {
                                            serviceCode = serviceCode.substring(1);
                                        }
                                        Log.d(TAG, "5F30 service code: " + serviceCode);
                                    } else {
                                        Log.w(TAG, "NoTag(5F30) - using default service code 201");
                                    }

                                    // Build Track 2 in ISO format: ;PAN=YYMMsss?
                                    // Server expects this format and strips ; and ?
                                    GlobalPara.atmTrack2Data = ";" + maskedPan + "=" + expiry + serviceCode + "?";
                                    Log.d(TAG, "Stored ATM Track2 Data (CT masked ISO format): " + GlobalPara.atmTrack2Data);
                                } else {
                                    Log.d(TAG, "NoTag(DF32) - no Track2 available");
                                }
                            }
                        } else {
                            //Fail
                        }


                        //AID
                        tlvData.tag = 0x84;
                        tlvData.len = 256;
                        intRtn = emv.dataGet(tlvData);
                        ui_ShowLog("dataGet Rtn: " + String.format("0x%08X", intRtn));
                        if (intRtn == 0) {
                            Log.d(TAG, "tag :" + "0x84 (AID)");
                            Log.d(TAG, "AID contact value :" + Converter.byteArray2HexString(tlvData.value, tlvData.len));
                        }

                        String strAID = Converter.byteArray2HexString(tlvData.value, 5);   // RID
                        String fullAID = Converter.byteArray2HexString(tlvData.value, tlvData.len);
                        Log.d(TAG, "AID=" + fullAID + " (RID=" + strAID + ")");
                        GlobalPara.cardType = cardBrandFromAid(fullAID);

                        if (GlobalPara.isQuickChipTransaction == true) {
                            //Set Floor limit to 0
                            //Set TAC-Online to all 0xFF --> for requesting online
                            //Set TAC-Default to all 0xFF --> for decline transaction at GAC2
                            String txnsetEx = "9F1B0400000000DFC805FFFFFFFFFFDFC905FFFFFFFFFF";
                            byte[] tlvdat = new byte[(txnsetEx.length() / 2)];
                            int tlvdatlen = txnsetEx.length() / 2;
                            System.arraycopy(Converter.hexString2ByteArray(txnsetEx), 0, tlvdat, 0, tlvdatlen);

                            intRtn = emv.txnDataSetEx(tlvdat, tlvdatlen);
                            if (intRtn != 0) {
                                Debugger.addINT(TAG, "txnDataSetEx NG, Rtn", intRtn);
                            }
                        }

                        // ATM MODE: Force online authorization for ALL transactions
                        // ATM transactions must NEVER be approved offline
                        if (GlobalPara.atmMode) {
                            Log.d(TAG, "ATM MODE: Forcing online authorization...");
                            // Config has 9F33=E0F1C8 (Online PIN enabled)
                            // DO NOT override 9F33 here - card already saw config value during txnAppSelect()
                            // Overriding after txnAppSelect() is TOO LATE - card already decided CVM!
                            //
                            // With Online PIN enabled, EMV kernel will call onGetPINNotify
                            // We set key location to C000/0000 (our DUKPT key) in that callback
                            //
                            // CRITICAL: 9F02 (Amount) MUST be set for chip card CASH WITHDRAWALS!
                            // For balance inquiry, amount is $0.00
                            //
                            // ATM settings:
                            // 9F02 = Amount Authorized (6 bytes BCD) - $0 for balance inquiry
                            // 9C = Transaction Type: 30 (Cash) or 31 (Balance Inquiry)
                            // 9F1B = Floor Limit: 0 (all online)
                            // DFC7 = TAC-Online: force online for any condition
                            // DFC8 = TAC-Default: decline if can't go online
                            // DFC6 = TAC-Denial: don't deny offline

                            String amountBcd;
                            String txnTypeHex;
                            if (GlobalPara.atmBalanceInquiryMode) {
                                // Balance Inquiry: Amount = $0, TxnType = 0x31 (Savings) or 0x30 (Checking)
                                // EMV Book 4: 0x30 = Balance Inquiry
                                amountBcd = "000000000000";
                                txnTypeHex = "31";
                                Log.d(TAG, "ATM MODE (CT): BALANCE INQUIRY - Amount=$0, TxnType=0x31");
                            } else {
                                // Cash Withdrawal: TxnType = 0x01 (Cash/ATM Withdrawal)
                                // EMV Book 4: 0x00=Purchase, 0x01=Cash, 0x09=Cashback, 0x30=Balance
                                // CRITICAL: 0x01 is correct for ATM cash withdrawal, NOT 0x30!
                                amountBcd = Converter.amtPadding(GlobalPara.strAmount);
                                txnTypeHex = "01";  // 0x01 = Cash (ATM Withdrawal)
                                Log.d(TAG, "ATM MODE (CT): CASH WITHDRAWAL - Amount=" + GlobalPara.strAmount + " -> BCD: " + amountBcd + ", TxnType=0x01");
                            }

                            String atmTxnSet = "9F0206" + amountBcd +  // Amount Authorized
                                               "9C01" + txnTypeHex +   // Transaction Type
                                               "9F1B0400000000" +      // Floor Limit: 0
                                               "DFC705FFFFFFFFFF" +    // TAC-Online: Force online
                                               "DFC805FFFFFFFFFF" +    // TAC-Default: Decline if offline
                                               "DFC60500000000FF";     // TAC-Denial: Don't deny
                            Log.d(TAG, "ATM MODE: Setting ATM tags - 9F02=" + amountBcd + ", 9C=" + txnTypeHex);
                            byte[] atmTlvDat = new byte[(atmTxnSet.length() / 2)];
                            int atmTlvLen = atmTxnSet.length() / 2;
                            System.arraycopy(Converter.hexString2ByteArray(atmTxnSet), 0, atmTlvDat, 0, atmTlvLen);

                            intRtn = emv.txnDataSetEx(atmTlvDat, atmTlvLen);
                            if (intRtn != 0) {
                                Log.e(TAG, "ATM txnDataSetEx failed: " + String.format("0x%08X", intRtn));
                            } else {
                                Log.d(TAG, "ATM MODE: ATM config set (9F33=E0F1C8 Online PIN enabled)");
                            }

                            // Config has 9F33=E0F1C8 (Online PIN enabled)
                            // EMV kernel will call onGetPINNotify when card requires online PIN
                            // We set key location to C000/0000 in that callback
                            // If SDK PIN fails (0x1003), we collect PIN manually via DUKPT after txnPerform
                            Log.d(TAG, "ATM MODE (CT): Online PIN enabled - key at C000/0000");
                            GlobalPara.atmEntryMode = 1; // Contact (chip)

                            // Clear any previous PIN block
                            GlobalPara.atmEncryptedPinBlock = "";
                            GlobalPara.atmPinCollectedPostTransaction = false;
                        }


                        Log.d(TAG, "txnPerform ***********************************************");
                        intRtn = emv.txnPerform();

                        // ATM MODE: Dump EMV tags to check PAN availability
                        if (GlobalPara.atmMode) {
                            Log.d(TAG, "ATM MODE: Dumping EMV tags after txnPerform...");
                            dumpCurrentEmvTags();
                            verifyPinBlockFormat();
                        }

                        // ATM MODE: Collect PIN manually AFTER Generate AC (No-CVM approach)
                        // With Terminal Caps set to E028C8 (No PIN support), the card uses
                        // alternative CVM (Signature/No CVM) and Generate AC executes successfully.
                        // We then collect PIN separately using our working DUKPT implementation.
                        if (GlobalPara.atmMode && (GlobalPara.atmEncryptedPinBlock == null || GlobalPara.atmEncryptedPinBlock.isEmpty())) {
                            Log.d(TAG, "ATM MODE (CT): Collecting PIN POST-TRANSACTION (No-CVM cryptogram approach)");

                            // CRITICAL: Read clear PAN from EMV kernel BEFORE PIN entry (for Format 0)
                            // Try Tag 57 (Track 2) first, then Tag 5A (PAN)
                            if (GlobalPara.atmClearPan == null || GlobalPara.atmClearPan.isEmpty()) {
                                Log.d(TAG, "ATM MODE (CT): Reading clear PAN from EMV kernel for Format 0...");

                                // Try Tag 57 (Track 2 Equivalent Data)
                                TlvData tag57 = new TlvData();
                                tag57.tag = (short) 0x57;
                                tag57.len = 40;
                                tag57.value = new byte[40];
                                int tag57Rtn = emv.dataGet(tag57);
                                Log.d(TAG, "ATM MODE (CT): Tag 57 dataGet returned: " + String.format("0x%08X", tag57Rtn) + ", len=" + tag57.len);
                                if (tag57Rtn == 0 && tag57.len > 0) {
                                    String track2Hex = Converter.byteArray2HexString(tag57.value, tag57.len);
                                    Log.d(TAG, "ATM MODE (CT): Tag 57 raw: " + track2Hex);
                                    // BCD encoded - PAN is before 'D' separator
                                    int sepIdx = track2Hex.toUpperCase().indexOf("D");
                                    if (sepIdx > 0) {
                                        String panHex = track2Hex.substring(0, sepIdx);
                                        if (!panHex.contains("*")) {
                                            GlobalPara.atmClearPan = panHex;
                                            Log.d(TAG, "ATM MODE (CT): Clear PAN from Tag 57: " + panHex.substring(0, Math.min(6, panHex.length())) + "****");
                                        }
                                    }
                                }

                                // If Tag 57 didn't work, try Tag 5A (PAN)
                                if (GlobalPara.atmClearPan == null || GlobalPara.atmClearPan.isEmpty()) {
                                    TlvData tag5a = new TlvData();
                                    tag5a.tag = (short) 0x5A;
                                    tag5a.len = 20;
                                    tag5a.value = new byte[20];
                                    int tag5aRtn = emv.dataGet(tag5a);
                                    Log.d(TAG, "ATM MODE (CT): Tag 5A dataGet returned: " + String.format("0x%08X", tag5aRtn) + ", len=" + tag5a.len);
                                    if (tag5aRtn == 0 && tag5a.len > 0) {
                                        String panHex = Converter.byteArray2HexString(tag5a.value, tag5a.len);
                                        panHex = panHex.toUpperCase().replaceAll("F+$", "");
                                        if (!panHex.contains("*")) {
                                            GlobalPara.atmClearPan = panHex;
                                            Log.d(TAG, "ATM MODE (CT): Clear PAN from Tag 5A: " + panHex.substring(0, Math.min(6, panHex.length())) + "****");
                                        }
                                    }
                                }

                                if (GlobalPara.atmClearPan == null || GlobalPara.atmClearPan.isEmpty()) {
                                    Log.e(TAG, "ATM MODE (CT): Could not get clear PAN from EMV kernel - Format 0 will fail!");
                                }
                            }

                            Log.d(TAG, "ATM MODE (CT): Requesting PIN entry...");

                            boolean pinOK = requestATMPin();
                            if (!pinOK) {
                                Log.e(TAG, "ATM PIN: PIN entry failed or cancelled");
                                ui_ShowMsg("Transaction Cancelled\n\nPIN Required");
                                MyUtility.sleep(2000);

                                // Navigate back to main menu
                                GlobalPara.resetATMTransactionState();
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mViewPager.setCurrentItem(GlobalDef.d_PAGE_MAIN_MENU);
                                    }
                                });
                                break;
                            }
                            Log.d(TAG, "ATM PIN: PIN entry successful (POST-TRANSACTION, No-CVM approach)");
                            GlobalPara.atmPinCollectedPostTransaction = true;  // Mark as post-cryptogram PIN
                        } else if (GlobalPara.atmMode) {
                            Log.d(TAG, "ATM MODE (CT): PIN collected via EMV callback (ISO-0): " + GlobalPara.atmEncryptedPinBlock);
                            GlobalPara.atmPinCollectedPostTransaction = false;  // PIN was collected during EMV flow
                        }
                        if (GlobalPara.scrnBrdcstRecver != null) {
                            GlobalPara.scrnBrdcstRecver.setVirtualPINStatus(ClsScreenBroadcastReceiver.d_VPIN_IS_NOT_PERFORMING);
                        }
                        Log.d(TAG, ">>> txnPerform returned: 0x" + String.format("%08X", intRtn));
                        ui_ShowLog("txnPerform Rtn: " + String.format("0x%08X", intRtn));
                        if (intRtn == 0x0304) {
                            //Shoud Go Online
                            Log.d(TAG, ">>> txnPerform: Go Online (0x0304)");
                        } else if (intRtn == 0x00001003 && GlobalPara.atmMode) {
                            // ATM MODE: PIN error 0x1003 - SDK PIN handling failed
                            // BUT if we collected PIN manually via DUKPT, we can continue!
                            if (GlobalPara.atmEncryptedPinBlock != null && !GlobalPara.atmEncryptedPinBlock.isEmpty()) {
                                Log.w(TAG, ">>> ATM: SDK PIN failed (0x1003) but manual DUKPT PIN collected - CONTINUING");
                                Log.d(TAG, ">>> ATM: PIN block = " + GlobalPara.atmEncryptedPinBlock);
                                Log.d(TAG, ">>> ATM: KSN = " + GlobalPara.atmDukptKsn);
                                // CRITICAL: Set transaction result to "Go Online" so host authorization proceeds
                                GlobalPara.transactionResult = 0x0004;  // Go Online
                                Log.d(TAG, ">>> ATM: Set transactionResult = 0x0004 (Go Online)");
                                // Continue with transaction - we have the PIN block from manual DUKPT entry
                            } else {
                                Log.e(TAG, ">>> ATM: SDK PIN failed (0x1003) and no manual PIN - FAIL");
                                // Set decline result so receipt page shows error
                                GlobalPara.transactionResult = 0x0003; // Decline
                                GlobalPara.atmResponseCode = String.format("%08X", intRtn);
                                GlobalPara.atmResponseMessage = "Card processing error - PIN entry failed";
                                GlobalPara.atmHostCallSuccess = false;
                                ui_ShowMsg("PIN Entry Error");
                                break;
                            }
                        } else if (intRtn != 0) {
                            Log.e(TAG, ">>> txnPerform ERROR: 0x" + String.format("%08X", intRtn));
                            // Set decline result so receipt page shows error
                            GlobalPara.transactionResult = 0x0003; // Decline
                            GlobalPara.atmResponseCode = String.format("%08X", intRtn);
                            GlobalPara.atmResponseMessage = "Card processing error - " + String.format("0x%08X", intRtn);
                            GlobalPara.atmHostCallSuccess = false;
                            ui_ShowMsg("Transaction Error");
                            break;
                        }

                        //EMVDataGetExPara
                        authRequestData = new EMVDataGetExPara();
                        // EMV tags for online authorization (per Hyosung spec + processor requirements):
                        // Original: 5A=PAN, 95=TVR, 9F27=CID, 9F26=AC, 9F36=ATC, 9F10=IAD, 9F02=Amount,
                        //           9C=TxnType, 9F03=Cashback, 9F37=UN, 9A=TxnDate, 9F34=CVMResults, 5F24=AppExpiry
                        // Added per processor: 4F=AID, 50=AppLabel, 5F34=PANSeq, 82=AIP, 84=DFName,
                        //           9B=TSI, 9F06=AID2, 9F07=AppUsageCtrl, 9F09=AppVersion
                        tagList = "5A959F279F269F369F109F029C9F039F379A9F345F24" +
                                  "4F505F3482849B9F069F079F09";

                        authRequestData.tagList = Converter.hexString2ByteArray(tagList);
                        authRequestData.tagListLen = (tagList.length() / 2);
                        authRequestData.maxTlvBufSize = 256;
                        authRequestData.tlvBuf = new byte[256];
                        intRtn = emv.dataGetEx(authRequestData);
                        if (intRtn == 0) {
                            //Succeed
                            tlvUtility_ct.TLVDataParse(authRequestData.tlvBuf, authRequestData.tlvLen);

                            // Store EMV data for ATM host transaction (includes 5A for PIN translation)
                            if (GlobalPara.atmMode && authRequestData.tlvLen > 0) {
                                GlobalPara.atmEmvData = Converter.byteArray2HexString(authRequestData.tlvBuf, authRequestData.tlvLen);
                                Log.d(TAG, "Stored ATM EMV Data (CT): " + GlobalPara.atmEmvData);
                            }

                            TLVData.tag = 0x95;
                            TLVData.len = 256;
                            TLVData.value = new byte[256];
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(95):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(95)");
                            }

                            TLVData.tag = 0x9F27;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(9F27):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(9F27)");
                            }

                            TLVData.tag = 0x9F26;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(9F26):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(9F26)");
                            }

                            TLVData.tag = 0x9F36;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(9F36):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(9F36)");
                            }

                            TLVData.tag = 0x9F10;
                            TLVData.len = 256;
                            intRtn = tlvUtility_ct.TLVDataGet(TLVData);
                            if (intRtn == 0) {
                                Log.d(TAG, "TagData(9F10):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));
                            } else {
                                Log.d(TAG, "NoTag(9F10)");
                            }
                        }

                        // ATM MODE: Request sensitive EMV data (5A, 57) SEPARATELY
                        // This is how CastlesHost does it - separate request for PAN-related tags
                        if (GlobalPara.atmMode) {
                            EMVDataGetExPara sensitiveData = new EMVDataGetExPara();
                            sensitiveData.tagList = new byte[]{0x5A, 0x57};  // Just 5A and 57
                            sensitiveData.tagListLen = 2;
                            sensitiveData.maxTlvBufSize = 256;
                            sensitiveData.tlvBuf = new byte[256];
                            int sensRtn = emv.dataGetEx(sensitiveData);
                            Log.d(TAG, "ATM: Sensitive data request (5A,57) rtn=" + String.format("0x%08X", sensRtn) + ", len=" + sensitiveData.tlvLen);
                            if (sensRtn == 0 && sensitiveData.tlvLen > 0) {
                                String sensitiveHex = Converter.byteArray2HexString(sensitiveData.tlvBuf, sensitiveData.tlvLen);
                                Log.d(TAG, "ATM: Sensitive EMV data (5A,57): " + sensitiveHex);
                                // Store the clear PAN for PIN translation
                                GlobalPara.atmSensitiveEmvData = sensitiveHex;
                            } else {
                                Log.w(TAG, "ATM: Could not get sensitive data (5A,57) - may be masked by SDK");
                            }
                        }
                      } catch (Exception e) {
                        // Catch any crash during chip card processing
                        Log.e(TAG, "CHIP CARD PROCESSING CRASH: " + e.getMessage());
                        Log.e(TAG, "CRASH STACK TRACE: ", e);
                        // Set decline result so receipt page shows error
                        if (GlobalPara.atmMode) {
                            GlobalPara.transactionResult = 0x0003; // Decline
                            GlobalPara.atmResponseCode = "EXCEPTION";
                            GlobalPara.atmResponseMessage = "Card processing error - " + e.getMessage();
                            GlobalPara.atmHostCallSuccess = false;
                        }
                        ui_ShowMsg("Card Error: " + e.getMessage());
                        e.printStackTrace();
                      }
                    } else if (entryMode == GlobalDef.d_ENTRY_MODE_MSR) {
                        Log.d(TAG, "d_ENTRY_MODE_MSR ***********************************************");
                        if (intRtn != 0) {
                            msrRetryCount++;
                            Log.d(TAG, "MSR Error = " + String.format("0x%08X", intRtn) + " (attempt " + msrRetryCount + "/" + MSR_MAX_RETRIES + ")");

                            // v5.3-ATM: Retry logic - 3 attempts before giving up
                            if (msrRetryCount < MSR_MAX_RETRIES) {
                                ui_ShowMsg("Swipe Error!\nPlease swipe again\n(" + msrRetryCount + "/" + MSR_MAX_RETRIES + ")\n");
                                Log.d(TAG, "MSR retry " + msrRetryCount + " - flushing buffer and retrying...");
                                MyUtility.sleep(1500);  // Brief pause for user to see message
                                msr.flushTracksBuffer();  // Clear MSR buffer
                                entryMode = 0;  // Reset entry mode
                                continue msrRetryLoop;  // Go back to card detection
                            } else {
                                // Set decline result so receipt page shows error
                                if (GlobalPara.atmMode) {
                                    GlobalPara.transactionResult = 0x0003; // Decline
                                    GlobalPara.atmResponseCode = "MSR_FAIL";
                                    GlobalPara.atmResponseMessage = "Card swipe failed after multiple attempts";
                                    GlobalPara.atmHostCallSuccess = false;
                                }
                                ui_ShowMsg("Swipe Failed!\nPlease try another card\nor use chip/tap\n");
                                Log.d(TAG, "MSR failed after " + MSR_MAX_RETRIES + " attempts");
                                break msrRetryLoop;  // Exit after max retries
                            }
                        } else {
                            Log.d(TAG, "getTracksLen***********************************************");
                            EMVMSRTracksLen tracksLen;
                            tracksLen = msr.getTracksLen();
                            Log.d(TAG, "T1 enable : " + String.valueOf(tracksLen.track1Enabled));
                            Log.d(TAG, "T1 Len : " + String.valueOf(tracksLen.track1Len));
                            Log.d(TAG, "T2 2enable : " + String.valueOf(tracksLen.track2Enabled));
                            Log.d(TAG, "T2 Len : " + String.valueOf(tracksLen.track2Len));
                            Log.d(TAG, "T3 enable : " + String.valueOf(tracksLen.track3Enabled));
                            Log.d(TAG, "T3 Len : " + String.valueOf(tracksLen.track3Len));

                            Log.d(TAG, "getMaskedPAN***********************************************");
                            byte[] temp = msr.getMaskedPAN();
                            if (temp == null) {
                                temp = new byte[2];
                            }

                            byte[] maskedPAN = new byte[temp.length + 1];


                            System.arraycopy(temp, 0, maskedPAN, 0, temp.length);
                            Log.d(TAG, "Masked PAN : " + new String(maskedPAN));
                            ui_ShowMsg("PAN : " + new String(maskedPAN) + "\n");
                            MyUtility.sleep(2500);

                            GlobalPara.asciiPAN = new String(maskedPAN).trim();

                            // Store last 4 digits for ATM receipt
                            String panStr = GlobalPara.asciiPAN.replaceAll("[^0-9]", "");
                            if (panStr.length() >= 4) {
                                GlobalPara.atmLastFourDigits = panStr.substring(panStr.length() - 4);
                                Log.d(TAG, "Stored ATM last 4 digits: " + GlobalPara.atmLastFourDigits);
                            }

                            // ATM MODE: Request PIN entry for MSR (swipe) transactions
                            // NOTE: MSR doesn't go through EMV flow, so SDK PIN callback won't trigger
                            // We must use Format 1 PIN blocks (no clear PAN available)
                            // Mux will need to handle Format 1 or translate server-side
                            if (GlobalPara.atmMode) {
                                Log.d(TAG, "ATM MODE (MSR): Requesting PIN entry...");
                                GlobalPara.atmEntryMode = 3; // MSR

                                // Request PIN entry (DUKPT or Format 1)
                                boolean pinOK = requestATMPin();
                                if (!pinOK) {
                                    Log.e(TAG, "ATM PIN (MSR): PIN entry failed or cancelled");
                                    ui_ShowMsg("Transaction Cancelled\n\nPIN Required");
                                    MyUtility.sleep(2000);

                                    // Navigate back to main menu
                                    GlobalPara.resetATMTransactionState();
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            mViewPager.setCurrentItem(GlobalDef.d_PAGE_MAIN_MENU);
                                        }
                                    });
                                    break msrRetryLoop;
                                }
                                Log.d(TAG, "ATM PIN (MSR): PIN entry successful");
                            }

                            GlobalPara.cardType = cardBrandFromPan(new String(maskedPAN));


                            Log.d(TAG, "getMaskedTracks***********************************************");
                            EMVMSRMaskedTracks maksedTracks = msr.getMaskedTracks();
                            Log.d(TAG, "Masked Track1 Len : " + String.valueOf(maksedTracks.track1maskedDataLen));
                            if (maksedTracks.track1maskedDataLen > 0) {
                                byte[] maskedTracks1 = new byte[maksedTracks.track1maskedDataLen + 1];
                                System.arraycopy(maksedTracks.track1maskedData, 0, maskedTracks1, 0, maksedTracks.track1maskedDataLen);
                                Log.d(TAG, "Masked Track1 : " + new String(maskedTracks1));
                            }
                            Log.d(TAG, "Masked Track2 Len : " + String.valueOf(maksedTracks.track2maskedDataLen));
                            if (maksedTracks.track2maskedDataLen > 0) {
                                byte[] maskedTracks2 = new byte[maksedTracks.track2maskedDataLen + 1];
                                System.arraycopy(maksedTracks.track2maskedData, 0, maskedTracks2, 0, maksedTracks.track2maskedDataLen);
                                String maskedTrack2Str = new String(maskedTracks2);
                                Log.d(TAG, "Masked Track2 : " + maskedTrack2Str);

                                // ===== CASTLE SUPPORT DEBUG: DUKPT TRACK2 MASKING ISSUE =====
                                // ISSUE: When setTracksEncryptInfo() is enabled for DUKPT encryption,
                                // getMaskedTracks() returns Track2 with last 4 chars of discretionary data
                                // replaced with asterisks (*). We need the FULL clear Track2 for the processor.
                                //
                                // EXPECTED: Full clear Track2 like: 4761739001010119D22122011758928889
                                // ACTUAL:   Masked Track2 like:     4761739001010119D2212201175892****
                                //
                                // The masking makes it impossible to send proper clear Track2 to ATM processor
                                // while also getting the DUKPT-encrypted Track2 from getEncrptedTracks().
                                Log.e(TAG, "=== CASTLE SUPPORT DEBUG: TRACK2 MASKING ISSUE ===");
                                Log.e(TAG, "getMaskedTracks() Track2: [" + maskedTrack2Str + "]");
                                Log.e(TAG, "Track2 contains asterisks: " + maskedTrack2Str.contains("*"));
                                Log.e(TAG, "DUKPT encryption enabled via setTracksEncryptInfo()");
                                Log.e(TAG, "We need FULL clear Track2, but SDK masks last 4 chars of discretionary data");
                                Log.e(TAG, "=== END CASTLE SUPPORT DEBUG ===");
                            }
                            Log.d(TAG, "Masked Track3 Len : " + String.valueOf(maksedTracks.track3maskedDataLen));
                            if (maksedTracks.track3maskedDataLen > 0) {
                                byte[] maskedTracks3 = new byte[maksedTracks.track3maskedDataLen + 1];
                                System.arraycopy(maksedTracks.track3maskedData, 0, maskedTracks3, 0, maksedTracks.track3maskedDataLen);
                                Log.d(TAG, "Masked Track3 : " + new String(maskedTracks3));
                            }

                            Log.d(TAG, "getEncrptedTracks***********************************************");
                            EMVMSREncryptedTracks encryptedTracks = msr.getEncrptedTracks();
                            if (encryptedTracks.track1EncryptedDataLen > 0) {
                                Log.d(TAG, "Encrypted Track1 : " + Converter.byteArray2HexString(encryptedTracks.track1EncryptedData, encryptedTracks.track1EncryptedDataLen));
                                Log.d(TAG, "Checksum Track1 : " + Converter.byteArray2HexString(encryptedTracks.track1Checksum, encryptedTracks.track1ChecksumLen));
                                Log.d(TAG, "KSN Track1 : " + Converter.byteArray2HexString(encryptedTracks.track1KSN, encryptedTracks.track1KSNLen));
                            }
                            if (encryptedTracks.track2EncryptedDataLen > 0) {
                                Log.d(TAG, "Encrypted Track2 : " + Converter.byteArray2HexString(encryptedTracks.track2EncryptedData, encryptedTracks.track2EncryptedDataLen));
                                Log.d(TAG, "Checksum Track2 : " + Converter.byteArray2HexString(encryptedTracks.track2Checksum, encryptedTracks.track2ChecksumLen));
                                Log.d(TAG, "KSN Track2 : " + Converter.byteArray2HexString(encryptedTracks.track2KSN, encryptedTracks.track2KSNLen));

                                // Store encrypted track 2 for ATM transactions
                                GlobalPara.atmTrack2Data = Converter.byteArray2HexString(encryptedTracks.track2EncryptedData, encryptedTracks.track2EncryptedDataLen);
                                Log.d(TAG, "Stored ATM Track2 Data: " + GlobalPara.atmTrack2Data);
                            }
                            if (encryptedTracks.track3EncryptedDataLen > 0) {
                                Log.d(TAG, "Encrypted Track3 : " + Converter.byteArray2HexString(encryptedTracks.track3EncryptedData, encryptedTracks.track3EncryptedDataLen));
                                Log.d(TAG, "Checksum Track3 : " + Converter.byteArray2HexString(encryptedTracks.track3Checksum, encryptedTracks.track3ChecksumLen));
                                Log.d(TAG, "KSN Track3 : " + Converter.byteArray2HexString(encryptedTracks.track3KSN, encryptedTracks.track3KSNLen));
                            }

                            GlobalPara.transactionResult = 0x0004;
                            GlobalPara.isNeedSignature = false;
                            Log.d(TAG, "----Let's see if there are tracks in clear----");
                            EMVEDLBinInfo matchdata = new EMVEDLBinInfo();

                            int rtn = edl.getWhiteListMatchedBinInfo(matchdata);
                            if (rtn == 0 && matchdata.isMatch == true) {
                                Log.e(TAG, "~~**************************************************************\n");
                                Log.d(TAG, "~~isMatch: " + matchdata.isMatch + "\n");
                                Log.d(TAG, "~~binLen: " + matchdata.binLen + "\n");
                                Log.d(TAG, "~~binStart: " + Converter.asciiBytesToString(matchdata.binStart) + "\n");
                                Log.d(TAG, "~~binEnd: " + Converter.asciiBytesToString(matchdata.binEnd) + "\n");


                                if (matchdata.brandLen > 0) {
                                    Log.d(TAG, "~~brand: " + Converter.asciiBytesToString(matchdata.brand) + "\n");
                                }

                                if (matchdata.typeLen > 0) {
                                    Log.d(TAG, "~~type: " + Converter.asciiBytesToString(matchdata.type) + "\n");
                                }

                                if (matchdata.gotCipher == true) {
                                    Log.d(TAG, "~~isCipher: " + matchdata.isCipher + "\n");
                                } else {
                                    //Default is Cipher if this item isn't presented
                                }

                                if (matchdata.gotPanLen == true) {
                                    Log.d(TAG, "~~panLenMin: " + matchdata.panLenMin + "\n");
                                    Log.d(TAG, "~~panLenMax: " + matchdata.panLenMax + "\n");
                                }
                                //invalidPanLen is only checked for manual entry
                                Log.d(TAG, "~~invalidPanLen: " + matchdata.invalidPanLen + "\n");
                                if (matchdata.invalidPanLen == true) {
                                    //invalid pan len, input again or terminated
                                    return;
                                }

                                if (matchdata.gotMaskDigit == true) {
                                    Log.d(TAG, "~~maskDigit1_BeginOfPan: " + matchdata.maskDigit1_BeginOfPan + "\n");
                                    Log.d(TAG, "~~maskDigit2_EndOfPan: " + matchdata.maskDigit2_EndOfPan + "\n");
                                    Log.d(TAG, "~~maskDigit2_AfterDelimiter: " + matchdata.maskDigit2_AfterDelimiter + "\n");
                                }

                                //for Manual Entry
                                if (matchdata.gotExpdate == true) {
                                    if (matchdata.expdatePrompt == true) {
                                        //disaply expdate information on screen
                                        Log.d(TAG, "~~expdateLenMin: " + matchdata.expdateLenMin + "\n");
                                        Log.d(TAG, "~~expdateLenMax: " + matchdata.expdateLenMax + "\n");
                                        Log.d(TAG, "~~expdateFormatLen: " + matchdata.expdateFormatLen + "\n");
                                        Log.d(TAG, "~~expdateFormat: " + Converter.asciiBytesToString(matchdata.expdateFormat) + "\n");
                                        Log.d(TAG, "~~expdateLabelLen: " + matchdata.expdateLabelLen + "\n");
                                        Log.d(TAG, "~~expdateLabel: " + Converter.asciiBytesToString(matchdata.expdateLabel) + "\n");

                                        //manual entry expdate
                                    }
                                }

                                if (matchdata.gotCVV == true) {
                                    if (matchdata.cvvPrompt == true) {
                                        //disaply cvv information on screen
                                        Log.d(TAG, "~~cvvLenMin: " + matchdata.cvvLenMin + "\n");
                                        Log.d(TAG, "~~cvvLenMax: " + matchdata.cvvLenMax + "\n");
                                        Log.d(TAG, "~~cvvFormatLen: " + matchdata.cvvFormatLen + "\n");
                                        Log.d(TAG, "~~cvvFormat: " + Converter.asciiBytesToString(matchdata.cvvFormat) + "\n");
                                        Log.d(TAG, "~~cvvLabelLen: " + matchdata.cvvLabelLen + "\n");
                                        Log.d(TAG, "~~cvvLabel: " + Converter.asciiBytesToString(matchdata.cvvLabel) + "\n");

                                        //manual entry for cvv
                                    }
                                }

                                if (matchdata.gotPostcode == true) {
                                    if (matchdata.postcodePrompt == true) {
                                        //disaply postcode information on screen
                                        Log.d(TAG, "~~postcodeLenMin: " + matchdata.postcodeLenMin + "\n");
                                        Log.d(TAG, "~~postcodeLenMax: " + matchdata.postcodeLenMax + "\n");
                                        Log.d(TAG, "~~postcodeFormatLen: " + matchdata.postcodeFormatLen + "\n");
                                        Log.d(TAG, "~~postcodeFormat: " + Converter.asciiBytesToString(matchdata.postcodeFormat) + "\n");
                                        Log.d(TAG, "~~postcodeLabelLen: " + matchdata.postcodeLabelLen + "\n");
                                        Log.d(TAG, "~~postcodeLabel: " + Converter.asciiBytesToString(matchdata.postcodeLabel) + "\n");

                                        //manual entry for postcode
                                    }
                                }

                                if (matchdata.gotAddr == true) {
                                    if (matchdata.addrPrompt == true) {
                                        //disaply addr information on screen
                                        Log.d(TAG, "~~addrLenMin: " + matchdata.addrLenMin + "\n");
                                        Log.d(TAG, "~~addrLenMax: " + matchdata.addrLenMax + "\n");
                                        Log.d(TAG, "~~addrFormatLen: " + matchdata.addrFormatLen + "\n");
                                        Log.d(TAG, "~~addrFormat: " + Converter.asciiBytesToString(matchdata.addrFormat) + "\n");
                                        Log.d(TAG, "~~addrLabelLen: " + matchdata.addrLabelLen + "\n");
                                        Log.d(TAG, "~~addrLabel: " + Converter.asciiBytesToString(matchdata.addrLabel) + "\n");

                                        //manual entry for addr
                                    }
                                }

                            }
                            Log.d(TAG, "----End for let's see if there are tracks in clear----");

                        }

                    } else if (entryMode == GlobalDef.d_ENTRY_MODE_CL) {
                        Log.d(TAG, "d_ENTRY_MODE_CL ***********************************************");
                        // Accept multiple valid CL return codes:
                        // 0xA0000001 = standard success
                        // 0x80000021 = alternate success
                        // 0xA00000E9 = contactless card detected/processed
                        if (intRtn != 0xA0000001 && intRtn != 0x80000021 && intRtn != 0xA00000E9) {
                            // Set decline result so receipt page shows error
                            if (GlobalPara.atmMode) {
                                GlobalPara.transactionResult = 0x0003; // Decline
                                GlobalPara.atmResponseCode = String.format("%08X", intRtn);
                                GlobalPara.atmResponseMessage = "Contactless read error - " + String.format("0x%08X", intRtn);
                                GlobalPara.atmHostCallSuccess = false;
                            }
                            ui_ShowMsg("Transaction Error !\n");
                            ui_ShowLog("performTransactionEx Rtn: " + String.format("0x%08X", intRtn));
                            break;
                        }
                        Log.d(TAG, "CL return code accepted: " + String.format("0x%08X", intRtn));

                        Log.d(TAG, String.format("SID : 0x%02X", rcData.sid));
                        switch ((rcData.sid)) {
                            case (byte) 0x13:
                                cardType = "VISA Old US MSD";
                                break;
                            case (byte) 0x16:
                                cardType = "VisaWave 2";
                                break;
                            case (byte) 0x17:
                                cardType = "VisaWave qVSDC";
                                break;
                            case (byte) 0x18:
                                cardType = "VisaWave MSD";
                                break;
                            case (byte) 0x20:
                                cardType = "PayPass M-Stripe";
                                break;
                            case (byte) 0x21:
                                cardType = "PayPass MChip";
                                break;
                            case (byte) 0x41:
                                cardType = "Zip";
                                break;
                            case (byte) 0x50:
                                cardType = "ExpressPay EMV";
                                break;
                            case (byte) 0x52:
                                cardType = "AE M-Stripe";
                                break;
                            case (byte) 0x61:
                                cardType = "J/Speedy Wave 2";
                                break;
                            case (byte) 0x62:
                                cardType = "J/Speedy qVSDC";
                                break;
                            case (byte) 0x91:
                                cardType = "Quick Pass";
                                break;

                            default:
                                cardType = "CardType No Def.";
                                break;
                        }
                        Log.d(TAG, "CL kernel type: " + cardType);
                        // GlobalPara.cardType (printed on the receipt) is set from the
                        // card BRAND (AID tag 4F) below via cardBrandFromAid, so it reads
                        // VISA/MC/DEBIT rather than the verbose contactless kernel type.

                        TLVData.version = 1;
                        TLVData.value = new byte[256];
                        TLVData.len = 0;

                        tlvUtility.TLVDataClear();
                        tlvUtility.TLVDataParse(rcData.chipData, rcData.chipDataLen);
                        tlvUtility.TLVDataParse(rcData.additionalData, rcData.additionalDataLen);
                        Log.d(TAG, "TLVData(UtilityDB) : " + Converter.byteArray2HexString(tlvUtility.TLVDataBase, tlvUtility.intTLVDataBaseLen));
                        Log.d(TAG, "rcData.track1Data  : " + Converter.byteArray2HexString(rcData.track1Data, rcData.track1Len));
                        Log.d(TAG, "rcData.track2Data  : " + Converter.byteArray2HexString(rcData.track2Data, rcData.track2Len));
                        Log.d(TAG, "rcData.chipData    : " + Converter.byteArray2HexString(rcData.chipData, rcData.chipDataLen));
                        Log.d(TAG, "rcData.addData     : " + Converter.byteArray2HexString(rcData.additionalData, rcData.additionalDataLen));

                        // Card brand (abbreviated) for the receipt, from AID tag 4F.
                        TLVData.tag = 0x4F;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        if (tlvUtility.TLVDataGet(TLVData) == 0 && TLVData.len > 0) {
                            String clAid = Converter.byteArray2HexString(TLVData.value, TLVData.len);
                            GlobalPara.cardType = cardBrandFromAid(clAid);
                            Log.d(TAG, "CL AID=" + clAid + " brand=" + GlobalPara.cardType);
                        } else {
                            GlobalPara.cardType = "CARD";
                        }

                        // Store EMV data for ATM host transaction (CL)
                        if (GlobalPara.atmMode && tlvUtility.intTLVDataBaseLen > 0) {
                            GlobalPara.atmEmvData = Converter.byteArray2HexString(tlvUtility.TLVDataBase, tlvUtility.intTLVDataBaseLen);
                            Log.d(TAG, "Stored ATM EMV Data (CL): " + GlobalPara.atmEmvData);
                        }

                        // Try to get tag 5A (PAN) for PIN translation - contactless
                        TLVData.tag = 0x5A;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        if (intRtn == 0 && TLVData.len > 0) {
                            String tag5aHexCL = Converter.byteArray2HexString(TLVData.value, TLVData.len);
                            Log.d(TAG, "TagData(5A-CL):" + tag5aHexCL);
                            // ATM MODE: Store CLEAR PAN from Tag 5A for PIN block Format 0
                            if (GlobalPara.atmMode) {
                                String clearPanCL = tag5aHexCL.toUpperCase().replaceAll("F+$", "");
                                GlobalPara.atmClearPan = clearPanCL;
                                Log.d(TAG, "ATM (CL): CLEAR PAN from Tag 5A stored: " + clearPanCL.substring(0, Math.min(6, clearPanCL.length())) + "****");
                            }
                        } else {
                            Log.d(TAG, "NoTag(5A) in CL data - will use Track 2 for PAN");
                        }

                        TLVData.tag = 0xDF35;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "TagData(0xDF35):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                        TLVData.tag = 0xDF38;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "TagData(0xDF38):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                        TLVData.tag = 0x9F26;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "TagData(9F26):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                        TLVData.tag = 0x57;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "TagData(57): [masked]");

                        TLVData.tag = 0x84;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "AID contact-less tag84:" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                        TLVData.tag = 0x50;
                        TLVData.len = 256;
                        TLVData.value = new byte[256];
                        intRtn = tlvUtility.TLVDataGet(TLVData);
                        Log.d(TAG, "TagData(50):" + Converter.byteArray2HexString(TLVData.value, TLVData.len));

                        EMVCLRcDataAnalyze rcDataAnalyze = new EMVCLRcDataAnalyze();

                        intRtn = emvcl.analyzeTransactionEx(rcData, rcDataAnalyze);
                        Log.d(TAG, "EMVCL, analyze Transaction rtn:" + String.format("0x%08X", intRtn));
                        if (intRtn != 0x00) {
                            // Set decline result so receipt page shows error
                            if (GlobalPara.atmMode) {
                                GlobalPara.transactionResult = 0x0003; // Decline
                                GlobalPara.atmResponseCode = String.format("%08X", intRtn);
                                GlobalPara.atmResponseMessage = "Card analysis error - " + String.format("0x%08X", intRtn);
                                GlobalPara.atmHostCallSuccess = false;
                            }
                            ui_ShowMsg("analyzeTransactionEx Error !");
                            break;
                        }

                        // ATM MODE: Request PIN entry for contactless (tap) transactions
                        // For ATM, PIN is ALWAYS required regardless of card CVM rules
                        // NOTE: Contactless doesn't use the same EMV PIN callback as contact chip
                        // We must use Format 1 PIN blocks (no clear PAN available from EMVCL)
                        // Mux will need to handle Format 1 or translate server-side
                        if (GlobalPara.atmMode) {
                            Log.d(TAG, "ATM MODE (CL): Requesting PIN entry (Format 1 - no clear PAN)...");
                            GlobalPara.atmEntryMode = 2; // Contactless

                            // Extract PAN from Track 2 for display and DUKPT Format 0
                            String clPan = null;
                            if (rcData.track2Len > 0) {
                                String track2Hex = Converter.byteArray2HexString(rcData.track2Data, rcData.track2Len);
                                int sepIdx = track2Hex.indexOf("D");
                                if (sepIdx > 0) {
                                    clPan = track2Hex.substring(0, sepIdx).replaceAll("[Ff]+$", "");
                                }
                            }
                            if (clPan != null && clPan.length() >= 4) {
                                GlobalPara.asciiPAN = clPan;
                                GlobalPara.atmLastFourDigits = clPan.substring(clPan.length() - 4);
                                Log.d(TAG, "ATM CL: Stored last 4 digits: " + GlobalPara.atmLastFourDigits);
                                // Also store as clear PAN if not already set from Tag 5A
                                if ((GlobalPara.atmClearPan == null || GlobalPara.atmClearPan.isEmpty()) && clPan.length() >= 13) {
                                    GlobalPara.atmClearPan = clPan;
                                    Log.d(TAG, "ATM CL: CLEAR PAN from Track2 stored: " + clPan.substring(0, Math.min(6, clPan.length())) + "****");
                                }
                            }

                            // Request PIN entry (DUKPT or Format 1)
                            boolean pinOK = requestATMPin();
                            if (!pinOK) {
                                Log.e(TAG, "ATM PIN (CL): PIN entry failed or cancelled");
                                ui_ShowMsg("Transaction Cancelled\n\nPIN Required");
                                MyUtility.sleep(2000);

                                // Navigate back to main menu
                                GlobalPara.resetATMTransactionState();
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mViewPager.setCurrentItem(GlobalDef.d_PAGE_MAIN_MENU);
                                    }
                                });
                                break;
                            }
                            Log.d(TAG, "ATM PIN (CL): PIN entry successful");
                        }

                        //Check CVM
                        CVMStr = "                ";
                        if (rcDataAnalyze.cvmAnalysis == 0x01)    //d_EMVCL_CVM_REQUIRED_SIGNATURE rcDataAnalyze.cvmAnalysis == 0x01
                        {
                            CVMStr = "CVM->Signature";
                            GlobalPara.isNeedSignature = true;
                        } else if (rcDataAnalyze.cvmAnalysis == 0x02)    //d_EMVCL_CVM_REQUIRED_ONLPIN
                        {
                            CVMStr = "CVM->Online PIN";
                        } else if (rcDataAnalyze.cvmAnalysis == 0x04)    //d_EMVCL_CVM_REQUIRED_NOCVM
                        {
                            CVMStr = "CVM->No CVM Req";
                        }

                        GlobalPara.transactionResult = rcDataAnalyze.transResult;
                        Log.d(TAG, "Transaction Result: " + String.format("0x%04X", GlobalPara.transactionResult));
                        Log.d(TAG, "CVM: " + CVMStr);
                    }

                    if (GlobalPara.transactionResult == 0x0004) {
                        if (GlobalPara.isQuickChipTransaction == true && entryMode == GlobalDef.d_ENTRY_MODE_CT) {
                            Log.d(TAG, "txnCompletion for QuickChip ***********************************************");
                            //Unable go onlne
                            EMVOnlineResponseData onlineRspData = new EMVOnlineResponseData();
                            onlineRspData.version = 1;
                            onlineRspData.action = (byte) 0x03;
                            onlineRspData.authorizationCode[0] = 'Z';
                            onlineRspData.authorizationCode[1] = '3';
                            onlineRspData.issuerAuthenticationData = null;
                            onlineRspData.issuerAuthenticationDataLen = 0;
                            onlineRspData.issuerScript = null;
                            onlineRspData.issuerScriptLen = 0;
                            intRtn = emv.txnCompletion(onlineRspData);
                            ui_ShowLog("txnCompletion Rtn: " + String.format("0x%08X", intRtn));


                            ui_ShowMsg("Remove card !");
                            MyUtility.sleep(800);

                            //EditText edt = (EditText)this.mainActivity.findViewById(R.id.edtAmt);
                            EditText edt = (EditText) GlobalPara.mainActivity.findViewById(R.id.edtAmt);
                            String strAmt = edt.getText().toString();
                            byte[] finalAmount = new byte[6];

                            if (strAmt.isEmpty() == true) {
                                ui_ShowMsg("Input Amount !");
                                do {
                                    MyUtility.sleep(5000);
                                    strAmt = edt.getText().toString();
                                } while (strAmt.isEmpty() == true);
                            }
                            finalAmount = Converter.hexString2ByteArray(strAmt);

                            ui_ShowMsg("Online Processing ... \n");

                            // ATM HOST - Send actual transaction to server
                            if (atmHostService != null && atmHostService.isInitialized()) {
                                Log.d(TAG, "ATM HOST (CL): Building transaction data...");

                                // Build CastleCardData from available data
                                CastleCardData cardData = new CastleCardData();
                                cardData.setEntryMode(CastleCardData.ENTRY_MODE_CONTACTLESS);

                                // Get Track 2 from rcData if available and encrypt with DUKPT
                                if (rcData != null && rcData.track2Len > 0) {
                                    String track2Hex = Converter.byteArray2HexString(rcData.track2Data, rcData.track2Len);
                                    // Strip trailing 'F' padding from BCD-encoded track 2
                                    track2Hex = track2Hex.toUpperCase().replaceAll("F+$", "");
                                    String track2Ascii = ";" + track2Hex.replace("D", "=") + "?";

                                    Log.d(TAG, "ATM HOST (CL): Track2 raw hex: " + track2Hex);
                                    Log.d(TAG, "ATM HOST (CL): Track2 ASCII: " + track2Ascii);

                                    // Try to encrypt track 2 with DUKPT
                                    DukptEncryptedData encryptedTrack2 = encryptTrack2WithDukpt(track2Ascii);
                                    if (encryptedTrack2 != null) {
                                        // Store encrypted track 2 and KSN
                                        cardData.setEncryptedTrack2(Converter.hexString2ByteArray(encryptedTrack2.encryptedData));
                                        cardData.setTrack2KSN(Converter.hexString2ByteArray(encryptedTrack2.ksn));
                                        Log.d(TAG, "ATM HOST (CL): Track2 ENCRYPTED with DUKPT");
                                        Log.d(TAG, "  Encrypted: " + encryptedTrack2.encryptedData);
                                        Log.d(TAG, "  KSN: " + encryptedTrack2.ksn);

                                        // Also set clear track 2 for processor (if not masked)
                                        if (!track2Hex.contains("2A") && !track2Ascii.contains("*")) {
                                            cardData.setTrack2Data(track2Ascii);
                                            Log.d(TAG, "ATM HOST (CL): Track2 CLEAR also set");
                                        } else {
                                            Log.w(TAG, "ATM HOST (CL): Track2 is MASKED - using encrypted version only");
                                        }
                                    } else {
                                        // Encryption failed - try to send clear track if not masked
                                        if (!track2Hex.contains("2A") && !track2Ascii.contains("*")) {
                                            cardData.setTrack2Data(track2Ascii);
                                            Log.d(TAG, "ATM HOST (CL): Track2 set (clear, encryption failed)");
                                        } else {
                                            Log.w(TAG, "ATM HOST (CL): Track2 MASKED and encryption failed - no track2 to send!");
                                        }
                                    }
                                }

                                // Set encrypted PIN block and PIN KSN
                                if (GlobalPara.atmEncryptedPinBlock != null && !GlobalPara.atmEncryptedPinBlock.isEmpty()) {
                                    cardData.setEncryptedPinBlock(GlobalPara.atmEncryptedPinBlock);
                                    Log.d(TAG, "ATM HOST (CL): PIN block set: " + GlobalPara.atmEncryptedPinBlock);
                                    // Set PIN KSN separately for DUKPT PIN decryption
                                    if (GlobalPara.atmDukptKsn != null && !GlobalPara.atmDukptKsn.isEmpty()) {
                                        cardData.setPinBlockKSN(Converter.hexString2ByteArray(GlobalPara.atmDukptKsn));
                                        Log.d(TAG, "ATM HOST (CL): PIN KSN set: " + GlobalPara.atmDukptKsn);
                                    }
                                }

                                // Set EMV data if available (try authRequestData first, then GlobalPara.atmEmvData)
                                // Also append sensitive EMV data (5A, 57) if available
                                String emvToSendCL = "";
                                if (authRequestData != null && authRequestData.tlvLen > 0) {
                                    emvToSendCL = Converter.byteArray2HexString(authRequestData.tlvBuf, authRequestData.tlvLen);
                                    Log.d(TAG, "ATM HOST (CL): Base EMV data from authRequestData, length=" + authRequestData.tlvLen);
                                } else if (GlobalPara.atmEmvData != null && !GlobalPara.atmEmvData.isEmpty()) {
                                    emvToSendCL = GlobalPara.atmEmvData;
                                    Log.d(TAG, "ATM HOST (CL): Base EMV data from GlobalPara.atmEmvData, length=" + GlobalPara.atmEmvData.length());
                                }
                                // Append sensitive data (5A, 57) if available - for PIN translation
                                if (GlobalPara.atmSensitiveEmvData != null && !GlobalPara.atmSensitiveEmvData.isEmpty()) {
                                    emvToSendCL = emvToSendCL + GlobalPara.atmSensitiveEmvData;
                                    Log.d(TAG, "ATM HOST (CL): Appended sensitive EMV data (5A,57), total length=" + emvToSendCL.length());
                                }
                                if (!emvToSendCL.isEmpty()) {
                                    cardData.setEmvData(emvToSendCL);
                                }

                                // Calculate amount in cents
                                long amountCents = 0;
                                try {
                                    // F4 on the wire must be the REQUESTED amount only —
                                    // NOT amount+surcharge. atmTotal includes the surcharge;
                                    // atmSelectedAmount is what the customer asked for.
                                    // The host computes the cardholder charge as F4 + F6.
                                    double amtValue = Double.parseDouble(
                                        GlobalPara.atmSelectedAmount.isEmpty() ? "0" : GlobalPara.atmSelectedAmount);
                                    amountCents = (long)(amtValue * 100);
                                } catch (Exception e) {
                                    Log.e(TAG, "ATM HOST (CL): Error parsing amount: " + e.getMessage());
                                }

                                long surchargeCents = (long)(GlobalPara.atmFlatFeeAmount * 100);

                                // Send to host — loops PIN entry on "55 Incorrect PIN"
                                // (see sendAtmHostRequestWithPinRetry / PIN_RETRY_ON_INCORRECT)
                                String acctType = GlobalPara.getHyosungAccountType();
                                sendAtmHostRequestWithPinRetry(cardData, amountCents, surchargeCents, acctType, "CL");

                                // Check if transaction was approved
                                if (GlobalPara.atmHostCallSuccess) {
                                    GlobalPara.transactionResult = 0x0002; // Approved
                                    Log.d(TAG, "ATM HOST (CL): Transaction APPROVED");
                                } else {
                                    GlobalPara.transactionResult = 0x0003; // Declined
                                    Log.d(TAG, "ATM HOST (CL): Transaction DECLINED - " + GlobalPara.atmResponseMessage);
                                }
                            } else {
                                // Host service not available - DECLINE transaction
                                Log.e(TAG, "ATM HOST (CL): Host service not available - DECLINING transaction");
                                GlobalPara.transactionResult = 0x0003; // Declined
                                GlobalPara.atmResponseCode = "91";  // Issuer unavailable
                                GlobalPara.atmResponseMessage = "Host service not available";
                                GlobalPara.atmHostCallSuccess = false;
                            }
                        } else if (entryMode == GlobalDef.d_ENTRY_MODE_CT) {
                            ui_ShowMsg("Online Processing ... \n");

                            // ATM HOST - Send actual transaction to server for contact chip
                            if (atmHostService != null && atmHostService.isInitialized()) {
                                Log.d(TAG, "ATM HOST (CT): Building transaction data...");

                                CastleCardData cardData = new CastleCardData();
                                cardData.setEntryMode(CastleCardData.ENTRY_MODE_CONTACT);

                                // Get Track 2 equivalent from EMV tag 57 and encrypt with DUKPT
                                String track2AsciiCT = null;
                                try {
                                    TLVData.value = new byte[256];
                                    TLVData.tag = 0x57;
                                    TLVData.len = 256;
                                    int tagRtn = tlvUtility_ct.TLVDataGet(TLVData);
                                    if (tagRtn == 0 && TLVData.len > 0) {
                                        String track2Hex = Converter.byteArray2HexString(TLVData.value, TLVData.len);
                                        // Strip trailing 'F' padding from BCD-encoded tag 57
                                        track2Hex = track2Hex.toUpperCase().replaceAll("F+$", "");
                                        track2AsciiCT = ";" + track2Hex.replace("D", "=") + "?";
                                        Log.d(TAG, "ATM HOST (CT): Track2 from tag 57, len=" + TLVData.len);
                                        Log.d(TAG, "ATM HOST (CT): Track2 ASCII: " + track2AsciiCT);
                                    } else {
                                        // Tag 57 not available (Castle masks for PCI), check stored track 2
                                        Log.w(TAG, "ATM HOST (CT): Tag 57 not available, checking GlobalPara.atmTrack2Data");
                                        if (GlobalPara.atmTrack2Data != null && !GlobalPara.atmTrack2Data.isEmpty()) {
                                            track2AsciiCT = GlobalPara.atmTrack2Data;
                                            Log.d(TAG, "ATM HOST (CT): Using stored Track2");
                                        } else {
                                            Log.w(TAG, "ATM HOST (CT): No Track2 data available");
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "ATM HOST (CT): Error getting tag 57: " + e.getMessage());
                                }

                                // Encrypt track 2 with DUKPT if available
                                if (track2AsciiCT != null && !track2AsciiCT.isEmpty()) {
                                    Log.d(TAG, "ATM HOST (CT): Attempting DUKPT encryption of track 2");

                                    DukptEncryptedData encryptedTrack2CT = encryptTrack2WithDukpt(track2AsciiCT);
                                    if (encryptedTrack2CT != null) {
                                        // Store encrypted track 2 and KSN
                                        cardData.setEncryptedTrack2(Converter.hexString2ByteArray(encryptedTrack2CT.encryptedData));
                                        cardData.setTrack2KSN(Converter.hexString2ByteArray(encryptedTrack2CT.ksn));
                                        Log.d(TAG, "ATM HOST (CT): Track2 ENCRYPTED with DUKPT");
                                        Log.d(TAG, "  Encrypted: " + encryptedTrack2CT.encryptedData);
                                        Log.d(TAG, "  KSN: " + encryptedTrack2CT.ksn);

                                        // Also set clear track 2 if not masked
                                        if (!track2AsciiCT.contains("*")) {
                                            cardData.setTrack2Data(track2AsciiCT);
                                            Log.d(TAG, "ATM HOST (CT): Track2 CLEAR also set");
                                        } else {
                                            Log.w(TAG, "ATM HOST (CT): Track2 is MASKED - using encrypted version only");
                                        }
                                    } else {
                                        // Encryption failed - try to send clear track if not masked
                                        if (!track2AsciiCT.contains("*")) {
                                            cardData.setTrack2Data(track2AsciiCT);
                                            Log.d(TAG, "ATM HOST (CT): Track2 set (clear, encryption failed)");
                                        } else {
                                            Log.w(TAG, "ATM HOST (CT): Track2 MASKED and encryption failed - no track2 to send!");
                                        }
                                    }
                                }

                                // Set encrypted PIN block and PIN KSN
                                if (GlobalPara.atmEncryptedPinBlock != null && !GlobalPara.atmEncryptedPinBlock.isEmpty()) {
                                    cardData.setEncryptedPinBlock(GlobalPara.atmEncryptedPinBlock);
                                    Log.d(TAG, "ATM HOST (CT): PIN block set: " + GlobalPara.atmEncryptedPinBlock);
                                    // Set PIN KSN separately for DUKPT PIN decryption
                                    if (GlobalPara.atmDukptKsn != null && !GlobalPara.atmDukptKsn.isEmpty()) {
                                        cardData.setPinBlockKSN(Converter.hexString2ByteArray(GlobalPara.atmDukptKsn));
                                        Log.d(TAG, "ATM HOST (CT): PIN KSN set: " + GlobalPara.atmDukptKsn);
                                    }
                                }

                                // Set EMV data - prefer GlobalPara.atmEmvData as it contains complete data with AID tags
                                // authRequestData may be truncated/incomplete
                                // Also append sensitive EMV data (5A, 57) if available
                                String emvToSend = "";
                                if (GlobalPara.atmEmvData != null && !GlobalPara.atmEmvData.isEmpty()) {
                                    // Use stored EMV data - contains all tags including 4F, 50, 82, 84, 9F06
                                    emvToSend = GlobalPara.atmEmvData;
                                    Log.d(TAG, "ATM HOST (CT): Base EMV data from GlobalPara.atmEmvData, length=" + GlobalPara.atmEmvData.length());
                                } else if (authRequestData != null && authRequestData.tlvLen > 0) {
                                    // Fallback to authRequestData if atmEmvData not available
                                    emvToSend = Converter.byteArray2HexString(authRequestData.tlvBuf, authRequestData.tlvLen);
                                    Log.d(TAG, "ATM HOST (CT): Base EMV data from authRequestData (fallback), length=" + authRequestData.tlvLen);
                                }
                                // Append sensitive data (5A, 57) if available - for PIN translation
                                if (GlobalPara.atmSensitiveEmvData != null && !GlobalPara.atmSensitiveEmvData.isEmpty()) {
                                    emvToSend = emvToSend + GlobalPara.atmSensitiveEmvData;
                                    Log.d(TAG, "ATM HOST (CT): Appended sensitive EMV data (5A,57), total length=" + emvToSend.length());
                                }
                                if (!emvToSend.isEmpty()) {
                                    cardData.setEmvData(emvToSend);
                                }

                                // Calculate amount in cents
                                long amountCents = 0;
                                try {
                                    // F4 on the wire must be the REQUESTED amount only —
                                    // NOT amount+surcharge. atmTotal includes the surcharge;
                                    // atmSelectedAmount is what the customer asked for.
                                    // The host computes the cardholder charge as F4 + F6.
                                    double amtValue = Double.parseDouble(
                                        GlobalPara.atmSelectedAmount.isEmpty() ? "0" : GlobalPara.atmSelectedAmount);
                                    amountCents = (long)(amtValue * 100);
                                } catch (Exception e) {
                                    Log.e(TAG, "ATM HOST (CT): Error parsing amount: " + e.getMessage());
                                }

                                long surchargeCents = (long)(GlobalPara.atmFlatFeeAmount * 100);

                                // Send to host — loops PIN entry on "55 Incorrect PIN"
                                // (see sendAtmHostRequestWithPinRetry / PIN_RETRY_ON_INCORRECT)
                                String acctTypeCT = GlobalPara.getHyosungAccountType();
                                sendAtmHostRequestWithPinRetry(cardData, amountCents, surchargeCents, acctTypeCT, "CT");

                                // Complete EMV transaction with host response
                                Debugger.addSTR("Debugger", "Input response data to EMV kernel to complete transaction");
                                EMVOnlineResponseData onlineRspData = new EMVOnlineResponseData();
                                onlineRspData.version = 1;

                                if (GlobalPara.atmHostCallSuccess) {
                                    onlineRspData.action = (byte) 0x01; // Approved
                                    GlobalPara.transactionResult = 0x0002;
                                    Log.d(TAG, "ATM HOST (CT): Transaction APPROVED");
                                } else {
                                    onlineRspData.action = (byte) 0x02; // Declined
                                    GlobalPara.transactionResult = 0x0003;
                                    Log.d(TAG, "ATM HOST (CT): Transaction DECLINED - " + GlobalPara.atmResponseMessage);
                                }

                                // Set Authorization Response Code (ARC) from actual host response
                                // ARC is 2 ASCII characters - use actual response code, not hardcoded "00"
                                String arc = GlobalPara.atmResponseCode;
                                if (arc != null && arc.length() >= 2) {
                                    onlineRspData.authorizationCode[0] = (byte) arc.charAt(0);
                                    onlineRspData.authorizationCode[1] = (byte) arc.charAt(1);
                                    Log.d(TAG, "ATM HOST (CT): ARC set to '" + arc.substring(0, 2) + "' from host response");
                                } else if (GlobalPara.atmHostCallSuccess) {
                                    // Approved but no specific code - use "00"
                                    onlineRspData.authorizationCode[0] = 0x30;
                                    onlineRspData.authorizationCode[1] = 0x30;
                                    Log.d(TAG, "ATM HOST (CT): ARC defaulted to '00' (approved, no code)");
                                } else {
                                    // Declined but no specific code - use "05" (Do Not Honor)
                                    onlineRspData.authorizationCode[0] = 0x30;
                                    onlineRspData.authorizationCode[1] = 0x35;
                                    Log.d(TAG, "ATM HOST (CT): ARC defaulted to '05' (declined, no code)");
                                }

                                // Set Issuer Authentication Data (Tag 91) if provided by host
                                if (GlobalPara.atmIssuerAuthData != null && GlobalPara.atmIssuerAuthData.length > 0) {
                                    onlineRspData.issuerAuthenticationData = GlobalPara.atmIssuerAuthData;
                                    onlineRspData.issuerAuthenticationDataLen = GlobalPara.atmIssuerAuthData.length;
                                    Log.d(TAG, "ATM HOST (CT): Issuer Auth Data (Tag 91) set, len=" + GlobalPara.atmIssuerAuthData.length);
                                } else {
                                    onlineRspData.issuerAuthenticationData = null;
                                    onlineRspData.issuerAuthenticationDataLen = 0;
                                }

                                // Set Issuer Scripts (Tags 71/72) if provided by host
                                // Combine 71 and 72 into single script buffer if both present
                                byte[] combinedScripts = combineIssuerScripts(GlobalPara.atmIssuerScript71, GlobalPara.atmIssuerScript72);
                                if (combinedScripts != null && combinedScripts.length > 0) {
                                    onlineRspData.issuerScript = combinedScripts;
                                    onlineRspData.issuerScriptLen = combinedScripts.length;
                                    Log.d(TAG, "ATM HOST (CT): Issuer Scripts set, len=" + combinedScripts.length);
                                } else {
                                    onlineRspData.issuerScript = null;
                                    onlineRspData.issuerScriptLen = 0;
                                }

                                // Detailed logging for Castle dev team debugging
                                Log.d(TAG, "============ txnCompletion INPUT DATA ============");
                                Log.d(TAG, "onlineRspData.version = " + onlineRspData.version);
                                Log.d(TAG, "onlineRspData.action = " + String.format("0x%02X", onlineRspData.action) +
                                    " (" + (onlineRspData.action == 0x01 ? "Approved" : onlineRspData.action == 0x02 ? "Declined" : "Other") + ")");
                                Log.d(TAG, "onlineRspData.authorizationCode = '" +
                                    (char)onlineRspData.authorizationCode[0] + (char)onlineRspData.authorizationCode[1] + "'");
                                Log.d(TAG, "onlineRspData.issuerAuthenticationData = " +
                                    (onlineRspData.issuerAuthenticationData == null ? "null" :
                                     Converter.byteArray2HexString(onlineRspData.issuerAuthenticationData, onlineRspData.issuerAuthenticationDataLen)));
                                Log.d(TAG, "onlineRspData.issuerAuthenticationDataLen = " + onlineRspData.issuerAuthenticationDataLen);
                                Log.d(TAG, "onlineRspData.issuerScript = " +
                                    (onlineRspData.issuerScript == null ? "null" :
                                     Converter.byteArray2HexString(onlineRspData.issuerScript, onlineRspData.issuerScriptLen)));
                                Log.d(TAG, "onlineRspData.issuerScriptLen = " + onlineRspData.issuerScriptLen);
                                Log.d(TAG, "GlobalPara.atmIssuerAuthData = " +
                                    (GlobalPara.atmIssuerAuthData == null ? "null" :
                                     "len=" + GlobalPara.atmIssuerAuthData.length));
                                Log.d(TAG, "GlobalPara.atmIssuerScript71 = " +
                                    (GlobalPara.atmIssuerScript71 == null ? "null" :
                                     "len=" + GlobalPara.atmIssuerScript71.length));
                                Log.d(TAG, "GlobalPara.atmIssuerScript72 = " +
                                    (GlobalPara.atmIssuerScript72 == null ? "null" :
                                     "len=" + GlobalPara.atmIssuerScript72.length));
                                Log.d(TAG, "===================================================");

                                int completionRtn = emv.txnCompletion(onlineRspData);
                                ui_ShowLog("txnCompletion Rtn: " + String.format("0x%08X", completionRtn));
                                if (completionRtn != 0) {
                                    ui_ShowMsg("Online Response Processing Error !");
                                }
                            } else {
                                // Host service not available - DECLINE transaction
                                Log.e(TAG, "ATM HOST (CT): Host service not available - DECLINING transaction");

                                EMVOnlineResponseData onlineRspData = new EMVOnlineResponseData();
                                onlineRspData.version = 1;
                                onlineRspData.action = (byte) 0x02;  // Declined
                                onlineRspData.authorizationCode[0] = 0x39;  // '9'
                                onlineRspData.authorizationCode[1] = 0x31;  // '1' = "91" Issuer unavailable
                                onlineRspData.issuerAuthenticationData = null;
                                onlineRspData.issuerAuthenticationDataLen = 0;
                                onlineRspData.issuerScript = null;
                                onlineRspData.issuerScriptLen = 0;
                                emv.txnCompletion(onlineRspData);
                                GlobalPara.transactionResult = 0x0003;  // Declined
                                GlobalPara.atmResponseCode = "91";
                                GlobalPara.atmResponseMessage = "Host service not available";
                                GlobalPara.atmHostCallSuccess = false;
                            }

                            ui_ShowMsg("Remove card !");
                            MyUtility.sleep(2500);
                        } else {
                            // MSR or other entry mode - Host not available, DECLINE
                            Log.e(TAG, "ATM HOST (MSR): Host service not available - DECLINING transaction");
                            GlobalPara.transactionResult = 0x0003;  // Declined
                            GlobalPara.atmResponseCode = "91";
                            GlobalPara.atmResponseMessage = "Host service not available";
                            GlobalPara.atmHostCallSuccess = false;
                        }

                    }

                    if (GlobalPara.transactionResult == 0x0002) {
                        final String msg = "Approval";
                        runOnUiThread(new Runnable() {
                                          @Override
                                          public void run() {
                                              TextView textView = (TextView) findViewById(R.id.txtViewUserInfo);
                                              if (textView != null) {
                                                  textView.setTextColor(Color.rgb(0, 255, 0));
                                                  textView.setText(msg);
                                              }
                                              ui_ShowLog("user msg : " + msg);
                                          }
                                      }
                        );

                    } else {
                        final String msg = "Decline";
                        runOnUiThread(new Runnable() {
                                          @Override
                                          public void run() {
                                              TextView textView = (TextView) findViewById(R.id.txtViewUserInfo);
                                              if (textView != null) {
                                                  textView.setTextColor(Color.rgb(255, 0, 0));
                                                  textView.setText(msg);
                                              }
                                              ui_ShowLog("user msg : " + msg);
                                          }
                                      }
                        );
                    }

                    MyUtility.sleep(1500);

                    ui_ShowMsg("Welcome\n");


				/*
					try
					{
						Printer.goprintf();
					}
					catch(IOException e)
					{
						e.printStackTrace();
					}
				*/

                    // v5.3-ATM: Exit retry loop after successful transaction
                    break msrRetryLoop;
                    }  // End of msrRetryLoop while(true)

                } while (false);

                GlobalPara.clLED.startIdleLEDBehavior();

                // Check if ATM mode - navigate to receipt page
                // Include both withdrawal (amount > 0) and balance inquiry mode
                if (GlobalPara.atmMode ||
                    (GlobalPara.atmSelectedAmount != null && !"0.00".equals(GlobalPara.atmSelectedAmount)) ||
                    GlobalPara.atmBalanceInquiryMode) {
                    // ATM Mode: Mark transaction as complete and navigate to receipt
                    GlobalPara.atmTransactionComplete = true;

                    // Small delay before navigating to receipt
                    MyUtility.sleep(1000);

                    // Navigate to receipt page
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navigateToPage(GlobalDef.d_PAGE_RECEIPT);
                        }
                    });
                } else {
                    // Normal mode: Re-enable buttons
                    ui_EnableTxnButton();
                    ui_EnableGetPinButton();
                    ui_EnableManualEntry();
                    ui_EnableEncryp();
                    ui_EnableSetting();
                }

                // Transaction thread ending - reset state
                Log.d(TAG, "Transaction thread ending");
                GlobalPara.atmTransactionInProgress = false;

              } catch (Throwable t) {
                // Catch any error inside the thread's run() method (including Errors)
                Log.e(TAG, "Error in transaction thread: " + t.getMessage(), t);
                final String errorMsg = "Thread error: " + t.getClass().getSimpleName() + "\n" + t.getMessage();
                ui_ShowMsg(errorMsg);
                GlobalPara.atmTransactionInProgress = false;
                ui_EnableAllButton();
              }
            }

        };

            ui_ShowMsg("Runnable created OK\n");
            Log.d(TAG, "Runnable created successfully");

            ui_ShowMsg("Creating Thread obj...\n");
            Log.d(TAG, "Creating Thread object");
            newThread = new Thread(txnRunnable);

            // Set uncaught exception handler to catch native crashes
            newThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    Log.e(TAG, "UNCAUGHT EXCEPTION in txn thread: " + e.getClass().getName() + " - " + e.getMessage(), e);
                    final String msg = "UNCAUGHT: " + e.getClass().getSimpleName() + "\n" + e.getMessage();
                    ui_ShowMsg(msg);
                    GlobalPara.atmTransactionInProgress = false;
                }
            });

            ui_ShowMsg("Thread obj created OK\n");
            Log.d(TAG, "Thread object created");

            threadTxn = newThread;

            ui_ShowMsg("Starting thread...\n");
            Log.d(TAG, "About to call threadTxn.start()");
            threadTxn.start();
            Log.e(TAG, "threadTxn.start() returned");

            // Give thread a moment to start and show any crash
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                // ignore
            }

            // Check if thread is still alive
            if (threadTxn.isAlive()) {
                ui_ShowMsg("Thread running OK\n");
                Log.e(TAG, "Thread is alive after 500ms");
            } else {
                ui_ShowMsg("Thread DIED!\n");
                Log.e(TAG, "Thread is NOT alive after 500ms - it crashed!");
            }
            Log.d(TAG, "threadTxn.start() completed successfully");

        } catch (Throwable t) {
            // Catch any error during thread creation or start (including Errors)
            Log.e(TAG, "Error creating/starting transaction thread: " + t.getMessage(), t);
            final String errorMsg = "Start error: " + t.getClass().getName() + "\n" + t.getMessage();
            ui_ShowMsg(errorMsg);
            GlobalPara.atmTransactionInProgress = false;
        }
        return 0;
    }

    //For MSR and Contactless
    public int btnGetOnlinePin_Click(final View view) {
        Log.d(TAG, "btnGetOnliePin_Click() ***");

        final int page = GlobalDef.d_PAGE_PINPAD_EX;
        final int remainingCounter = 1;

        thGetOnlinPin = new Thread(new Runnable() {
            @Override
            public void run() {
                //Switch page
                MyUtility.switchPage(page, 500);

                //Get PIN
                final TextView textView;
                final TextView textView2;
                textView = (TextView) findViewById(R.id.textViewPinDigitEx);
                textView2 = (TextView) findViewById(R.id.textViewKeyInfo);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("5 Enter Online Pin(" + String.valueOf(remainingCounter) + ") :\n");
                    }
                });

                Log.d(TAG, "Start Get PIN");

                IKMS2Callback.Stub callback = new IKMS2Callback.Stub() {
                    StringBuilder sb = new StringBuilder();
                    byte recv;

                    @Override
                    public int testCancel() {
                        return 0;
                    }

                    @Override
                    public void onGetDigit(byte Digit) {
                        Log.d(TAG, String.format("NoDigits = %d, OnGetPINDigit.", Digit));

                        recv = Digit;
                        sb.setLength(0);
                        for (int i = 0; i < recv; i++)
                            sb.append("*");

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setText("6 Enter Online Pin(" + String.valueOf(remainingCounter) + ") :\n" + sb);
                            }
                        });
                    }

                    @Override
                    public void onGetFunctionKey(byte FunctionKey) {
                        Log.d(TAG, String.format("FunctionKey = %d, OnGetPINOtherKeys.", FunctionKey));

                        recv = FunctionKey;
                        sb.setLength(0);
                        sb.append(recv);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setText("7 Enter Online Pin(" + String.valueOf(remainingCounter) + ") :\n" + sb);
                            }
                        });
                    }
                };


                //byte CipherMethod = 0x00;	//PIN_CIPHER_METHOD_ECB
                byte Null_PIN = 0;
                int Timeout = 60;
                int First_timeout = 20;
                int MaxDigit = 12, MinDigit = 4;
                byte PinBlockType = (byte) 0x00;
                byte outblocklen = 8;

                //Show Key Info
                CTOS.CtKMS2Key key = new CTOS.CtKMS2Key();
                try {
                    key.selectKey(GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex);

                    String sKeySet = "KeySet = " + String.format("%04X", key.getKeySet());
                    String sKeyIndex = "KeyIndex = " + String.format("%04X", key.getKeyIndex());
                    String sKeyType = "KeyType = " + String.format("0x%02X", key.getKeyType());
                    String sKeyAttribute = "KeyAttribute = " + String.format("%08X", key.getKeyAttribute());

                    Log.d("get key info", sKeySet);
                    Log.d("get key info", sKeyIndex);
                    Log.d("get key info", sKeyType);
                    Log.d("get key info", sKeyAttribute);

                    GlobalPara.mainActivity.ui_ShowLog(sKeySet);
                    GlobalPara.mainActivity.ui_ShowLog(sKeyIndex);
                    GlobalPara.mainActivity.ui_ShowLog(sKeyType);
                    GlobalPara.mainActivity.ui_ShowLog(sKeyAttribute);

                    final String str;
                    str = sKeySet + ", " + sKeyIndex + "\n" + sKeyType + ", " + sKeyAttribute;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView2.setText("Key Info\n" + str);
                        }
                    });


                } catch (CTOS.CtKMS2Exception e) {
                    Log.e(TAG, "DUKPT Key startVirtualPin Fail");
                    e.showStatus();
                    Log.e(TAG, String.format("0x%X", e.getError()));
                }


                CtKMS2Dukpt dukptKey = new CtKMS2Dukpt();
                CtKMS2VirtualPINPad VirtualPINPad = new CtKMS2VirtualPINPad();


                //Set Pin control and callback
                try {
                    dukptKey.selectKey(GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex);
                    dukptKey.setCipherMethod(CtKMS2Dukpt.PIN_CIPHER_METHOD_ECB);


                    dukptKey.setPinInfo(PinBlockType, (byte) MaxDigit, (byte) MinDigit, outblocklen);
                    dukptKey.setPinControl(Null_PIN, Timeout, First_timeout);
                    dukptKey.setCallback(callback);

                } catch (Exception e) {
                    Log.e("TAG", "DUKPT key PIN info Setting Fail");
                    Log.e(TAG, e.toString());
                }

                int[][] KBDAttribute = new int[16][5];

                TextView[] tv = new TextView[16];
                int[] XY = new int[2];
                int x;
                int y;
                int w;
                int h;

                for (int i = 0; i < 16; i++) {
                    switch (i) {
                        //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                        case 0:
                            tv[i] = (TextView) findViewById(R.id.KBD0);
                            KBDAttribute[i][4] = '0';
                            break;
                        case 1:
                            tv[i] = (TextView) findViewById(R.id.KBD1);
                            KBDAttribute[i][4] = '1';
                            break;
                        case 2:
                            tv[i] = (TextView) findViewById(R.id.KBD2);
                            KBDAttribute[i][4] = '2';
                            break;
                        case 3:
                            tv[i] = (TextView) findViewById(R.id.KBD3);
                            KBDAttribute[i][4] = '3';
                            break;
                        case 4:
                            tv[i] = (TextView) findViewById(R.id.KBD4);
                            KBDAttribute[i][4] = '4';
                            break;
                        case 5:
                            tv[i] = (TextView) findViewById(R.id.KBD5);
                            KBDAttribute[i][4] = '5';
                            break;
                        case 6:
                            tv[i] = (TextView) findViewById(R.id.KBD6);
                            KBDAttribute[i][4] = '6';
                            break;
                        case 7:
                            tv[i] = (TextView) findViewById(R.id.KBD7);
                            KBDAttribute[i][4] = '7';
                            break;
                        case 8:
                            tv[i] = (TextView) findViewById(R.id.KBD8);
                            KBDAttribute[i][4] = '8';
                            break;
                        case 9:
                            tv[i] = (TextView) findViewById(R.id.KBD9);
                            KBDAttribute[i][4] = '9';
                            break;
                        case 10:
                            tv[i] = (TextView) findViewById(R.id.KBD_Enter);
                            KBDAttribute[i][4] = 'A';
                            break;
                        case 11:
                            tv[i] = (TextView) findViewById(R.id.KBD_Clear);
                            KBDAttribute[i][4] = 'R';
                            break;
                        case 12:
                            tv[i] = (TextView) findViewById(R.id.KBD_Cancel);
                            KBDAttribute[i][4] = 'C';
                            break;
                        //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                        default:
                            KBDAttribute[i][4] = 'S';
                            break;
                    }

                    if (i > 12) {
                        x = 10;
                        y = 10;
                        w = 1;
                        h = 1;
                    } else {
                        tv[i].getLocationOnScreen(XY);
                        x = XY[0];
                        y = XY[1];
                        w = tv[i].getWidth();
                        h = tv[i].getHeight();
                    }

                    KBDAttribute[i][0] = x;
                    KBDAttribute[i][1] = y;
                    KBDAttribute[i][2] = w;
                    KBDAttribute[i][3] = h;

                    Log.d("KeyValue = ", String.valueOf(KBDAttribute[i][4]));
                    Log.d("location x = ", String.valueOf(x));
                    Log.d("location y = ", String.valueOf(y));
                    Log.d("location width = ", String.valueOf(KBDAttribute[i][2]));
                    Log.d("location height = ", String.valueOf(KBDAttribute[i][3]));


                    switch (i) {
                        //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                        case 0:
                            VirtualPINPad.VKBD_0.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_0.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_0.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_0.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_0.value = (byte) '0';
                            break;
                        case 1:
                            VirtualPINPad.VKBD_1.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_1.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_1.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_1.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_1.value = (byte) '1';
                            break;
                        case 2:
                            VirtualPINPad.VKBD_2.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_2.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_2.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_2.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_2.value = (byte) '2';
                            break;
                        case 3:
                            VirtualPINPad.VKBD_3.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_3.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_3.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_3.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_3.value = (byte) '3';
                            break;
                        case 4:
                            VirtualPINPad.VKBD_4.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_4.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_4.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_4.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_4.value = (byte) '4';
                            break;
                        case 5:
                            VirtualPINPad.VKBD_5.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_5.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_5.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_5.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_5.value = (byte) '5';
                            break;
                        case 6:
                            VirtualPINPad.VKBD_6.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_6.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_6.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_6.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_6.value = (byte) '6';
                            break;
                        case 7:
                            VirtualPINPad.VKBD_7.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_7.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_7.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_7.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_7.value = (byte) '7';
                            break;
                        case 8:
                            VirtualPINPad.VKBD_8.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_8.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_8.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_8.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_8.value = (byte) '8';
                            break;
                        case 9:
                            VirtualPINPad.VKBD_9.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_9.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_9.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_9.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_9.value = (byte) '9';
                            break;
                        case 10://enter
                            VirtualPINPad.VKBD_10.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_10.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_10.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_10.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_10.value = (byte) 'A';
                            break;
                        case 11://clear
                            VirtualPINPad.VKBD_11.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_11.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_11.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_11.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_11.value = (byte) 'R';
                            break;
                        case 12://cancel
                            VirtualPINPad.VKBD_12.x = KBDAttribute[i][0];
                            VirtualPINPad.VKBD_12.y = KBDAttribute[i][1];
                            VirtualPINPad.VKBD_12.width = KBDAttribute[i][2];
                            VirtualPINPad.VKBD_12.height = KBDAttribute[i][3];
                            VirtualPINPad.VKBD_12.value = (byte) 'C';
                            break;
                        case 13:
                            VirtualPINPad.VKBD_13.x = 0;
                            VirtualPINPad.VKBD_13.y = 0;
                            VirtualPINPad.VKBD_13.value = (byte) 0x53;
                            break;
                        case 14:
                            VirtualPINPad.VKBD_14.x = 0;
                            VirtualPINPad.VKBD_14.y = 0;
                            VirtualPINPad.VKBD_14.value = (byte) 0x53;
                            break;
                        case 15:
                            VirtualPINPad.VKBD_15.x = 0;
                            VirtualPINPad.VKBD_15.y = 0;
                            VirtualPINPad.VKBD_15.value = (byte) 0x53;
                            break;

                        //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                        default:


                            break;
                    }
                }


                try {
                    dukptKey.startVirtualPin(VirtualPINPad);
                    Log.d(TAG, "dukptKey.getOutpuData(hex): " + Converter.byteArray2HexString(dukptKey.getOutpuData(), outblocklen));

                    GlobalPara.mainActivity.ui_ShowLog("Pin KSN : " + Converter.byteArray2HexString(dukptKey.getKSN(), dukptKey.getKSN().length));

                    Log.d("KSN", "Pin KSN : " + Converter.byteArray2HexString(dukptKey.getKSN(), dukptKey.getKSN().length));

                    GlobalPara.mainActivity.ui_ShowLog("Pin block : " + Converter.byteArray2HexString(dukptKey.getOutpuData(), outblocklen));
                } catch (CTOS.CtKMS2Exception e) {
                    Log.e(TAG, "DUKPT Key startVirtualPin Fail");
                    e.showStatus();
                    Log.e(TAG, String.format("0x%X", e.getError()));

                    GlobalPara.mainActivity.ui_ShowLog("startVirtualPin Rtn : " + String.format("0x%X", e.getError()));
                } catch (Exception e) {
                    Log.e(TAG, "DUKPT key startVirtualPin Fail");
                    Log.e(TAG, e.toString());
                }


                GlobalPara.mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText(" ");
                        textView2.setText(" ");
                    }
                });


                //Switch back
                runOnUiThread(new Runnable() {
                                  @Override
                                  public void run() {
                                      mViewPager.setCurrentItem(GlobalDef.d_PAGE_TRANSACTION);
                                      GlobalPara.layoutViewCreate = 1;
                                  }
                              }
                );

                do {
                    MyUtility.sleep(500);
                } while (GlobalPara.layoutViewCreate == 0);


            }

        });

        thGetOnlinPin.start();

        return 0;
    }

    // =========================================================================
    // ATM MODE: Explicit PIN Entry
    // For ATM transactions, PIN must be entered immediately after card detection,
    // before any transaction processing (not dependent on EMV CVM rules).
    // Uses CtKMS2VirtualPINPad with button positions (same as btnGetOnlinePin_Click)
    // =========================================================================

    // Flag to track if ATM PIN entry was successful
    private volatile boolean atmPinEntrySuccess = false;
    private volatile boolean atmPinEntryCancelled = false;
    private volatile boolean atmPinEntryComplete = false;
    private volatile boolean atmPinPadReady = false;
    private android.app.Dialog atmPinDialog = null;

    // For software-based PIN collection (bypasses KMS2 VirtualPINPad)
    private StringBuilder atmCollectedPin = new StringBuilder();

    /**
     * Collect PIN synchronously for use in eventOnlinePinBlockGet callback.
     * Shows software PIN pad dialog and waits for user input.
     * Package-private for access from MyEMVSPEvent.
     *
     * @return The PIN digits entered, or null if cancelled/timeout
     */
    public String collectPinForCallback() {
        Log.d(TAG, "collectPinForCallback() - Showing software PIN pad for EMV callback");

        // Reset state
        atmPinEntryComplete = false;
        atmPinEntryCancelled = false;
        atmPinPadReady = false;
        atmCollectedPin.setLength(0);

        // Show PIN pad dialog on UI thread
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showSoftwarePinPadDialog();
            }
        });

        // Wait for dialog to be ready
        int waitCount = 0;
        while (!atmPinPadReady && waitCount < 50) {
            try { Thread.sleep(50); } catch (InterruptedException e) { }
            waitCount++;
        }

        if (!atmPinPadReady) {
            Log.e(TAG, "collectPinForCallback: Dialog not ready after timeout");
            dismissAtmPinPadDialog();
            return null;
        }

        // Wait for PIN entry or cancel (60 second timeout)
        int timeout = 600;  // 60 seconds
        while (!atmPinEntryComplete && !atmPinEntryCancelled && timeout > 0) {
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            timeout--;
        }

        // Dismiss dialog
        dismissAtmPinPadDialog();

        if (atmPinEntryCancelled) {
            Log.d(TAG, "collectPinForCallback: User cancelled PIN entry");
            return null;
        }

        if (timeout <= 0) {
            Log.d(TAG, "collectPinForCallback: Timeout waiting for PIN");
            return null;
        }

        String pin = atmCollectedPin.toString();
        Log.d(TAG, "collectPinForCallback: Got PIN, length=" + pin.length());

        if (pin.length() >= 4) {
            return pin;
        } else {
            Log.w(TAG, "collectPinForCallback: PIN too short");
            return null;
        }
    }

    /**
     * Request ATM PIN entry - dispatches to DUKPT or Format 1 based on configuration.
     *
     * If DUKPT is enabled and IPEK is injected, uses SDK's VirtualPINPad for Format 0.
     * Otherwise falls back to software PIN collection with Format 1.
     *
     * @return true if PIN was successfully entered and encrypted
     */
    /**
     * True only when this APK was built as the DUKPT flavor.
     *
     * DUKPT is deprecated — MKSK is the shipping key model. This is a compile-time
     * gate so no amount of stale SharedPreferences, settings migration, or partial
     * initialization can route an MKSK build down the DUKPT PIN path.
     */
    private static boolean isDukptBuild() {
        return "DUKPT".equals(BuildConfig.KEY_MODE);
    }

    /**
     * Short card-brand label for the receipt, derived from the EMV AID (tag 84/4F).
     * Checks the network-agnostic US Common Debit AIDs first, then the RID (first
     * 5 bytes). Cards that aren't credit Visa/MC used to fall through to the alarming
     * "UNKNOWN CARD" — a normal US debit card selects the Common Debit AID
     * (A0000000980840) and now reads "DEBIT". Truly unknown apps read "CARD".
     */
    private static String cardBrandFromAid(String aidHex) {
        if (aidHex == null || aidHex.isEmpty()) return "CARD";
        String aid = aidHex.toUpperCase().replace(" ", "");
        // US Common Debit (network-agnostic debit routing)
        if (aid.startsWith("A0000000980840")) return "DEBIT";   // Visa US Common Debit
        if (aid.startsWith("A0000000042203")) return "DEBIT";   // MC US Common Debit
        String rid = aid.length() >= 10 ? aid.substring(0, 10) : aid;
        switch (rid) {
            case "A000000003": return "VISA";
            case "A000000004": return "MC";
            case "A000000025": return "AMEX";
            case "A000000152": return "DISC";
            case "A000000324": return "DISC";
            case "A000000277": return "INTERAC";
            case "A000000098": return "DEBIT";   // Visa USA debit
            default:           return "CARD";
        }
    }

    /**
     * Short card-brand label from the (masked) PAN IIN — used on the MSR path,
     * where no AID is available. Unknown ranges read "CARD".
     */
    private static String cardBrandFromPan(String pan) {
        if (pan == null || pan.isEmpty()) return "CARD";
        char c0 = pan.charAt(0);
        String p2 = pan.length() >= 2 ? pan.substring(0, 2) : "";
        if (c0 == '4') return "VISA";
        if (c0 == '5') return "MC";
        if (p2.equals("34") || p2.equals("37")) return "AMEX";
        if (c0 == '6') return "DISC";
        return "CARD";
    }

    // ── PIN retry on incorrect PIN (response code 55) ────────────────────────
    // The STD1 spec marks code 55 as "Decline, allow retry": instead of ending the
    // transaction, re-prompt the PIN and resubmit with the SAME card/EMV data and a
    // NEW sequence number + PIN block. Capped at PIN_MAX_ATTEMPTS total tries; a 75
    // (issuer PIN-tries-exceeded) or any other decline ends the transaction as today.
    // Set PIN_RETRY_ON_INCORRECT = false to restore the previous single-attempt flow.
    private static final boolean PIN_RETRY_ON_INCORRECT = true;
    private static final int PIN_MAX_ATTEMPTS = 3;

    /**
     * Sends the request to the ATM host and, when the host answers "55 Incorrect
     * PIN", loops: re-prompt PIN → new PIN block → resend. Runs on the transaction
     * thread (blocks on the response latch exactly like the code it replaces).
     * On return, GlobalPara.atmHostCallSuccess / atmResponseCode / atmResponseMessage
     * reflect the FINAL attempt.
     *
     * @param pathTag "CL" or "CT" — used only for log continuity with the old code
     */
    private void sendAtmHostRequestWithPinRetry(CastleCardData cardData, long amountCents,
            long surchargeCents, String acctType, String pathTag) {
        int attempt = 1;
        while (true) {
            atmTransactionLatch = new java.util.concurrent.CountDownLatch(1);
            // Re-set transaction listener (may have been overwritten by admin screen)
            if (atmTransactionEventListener != null) {
                atmHostService.setEventListener(atmTransactionEventListener);
                Log.d(TAG, "ATM HOST (" + pathTag + "): Re-set transaction event listener");
            }
            if (GlobalPara.atmBalanceInquiryMode) {
                Log.d(TAG, "ATM HOST (" + pathTag + "): Sending BALANCE INQUIRY, account=" + acctType
                        + " (attempt " + attempt + ")");
                atmHostService.performBalanceInquiry(cardData, acctType);
            } else {
                Log.d(TAG, "ATM HOST (" + pathTag + "): Sending WITHDRAWAL - amount=" + amountCents
                        + " cents, surcharge=" + surchargeCents + " cents, account=" + acctType
                        + " (attempt " + attempt + ")");
                atmHostService.performWithdrawal(cardData, amountCents, surchargeCents, acctType);
            }

            // Wait for response with 60 second timeout
            try {
                boolean completed = atmTransactionLatch.await(60, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    Log.e(TAG, "ATM HOST (" + pathTag + "): Transaction TIMEOUT after 60 seconds");
                    GlobalPara.atmHostCallSuccess = false;
                    GlobalPara.atmResponseMessage = "Transaction timeout";
                    return;
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "ATM HOST (" + pathTag + "): Transaction interrupted: " + e.getMessage());
                GlobalPara.atmHostCallSuccess = false;
                GlobalPara.atmResponseMessage = "Transaction interrupted";
                return;
            }

            // Anything other than a retryable incorrect-PIN decline is final.
            if (!PIN_RETRY_ON_INCORRECT
                    || GlobalPara.atmHostCallSuccess
                    || !castech.emvtxn.atm.host.HyosungProtocol.RESP_INCORRECT_PIN
                            .equals(GlobalPara.atmResponseCode)) {
                return;
            }
            if (attempt >= PIN_MAX_ATTEMPTS) {
                Log.w(TAG, "ATM HOST (" + pathTag + "): Incorrect PIN — local attempt limit reached ("
                        + PIN_MAX_ATTEMPTS + ")");
                return;
            }

            attempt++;
            int remaining = PIN_MAX_ATTEMPTS - attempt + 1;
            Log.w(TAG, "ATM HOST (" + pathTag + "): Incorrect PIN — re-prompting ("
                    + remaining + " attempt(s) remaining)");
            ui_ShowMsg("Incorrect PIN — please try again\n(" + remaining + " attempt(s) remaining)");
            MyUtility.sleep(1500);

            boolean pinOK = requestATMPin();
            if (!pinOK || GlobalPara.atmEncryptedPinBlock == null
                    || GlobalPara.atmEncryptedPinBlock.isEmpty()) {
                // Customer cancelled or PIN encryption failed — keep the 55 decline as final
                Log.w(TAG, "ATM HOST (" + pathTag + "): PIN re-entry cancelled/failed — ending transaction");
                return;
            }
            cardData.setEncryptedPinBlock(GlobalPara.atmEncryptedPinBlock);
        }
    }

    /**
     * When true, transactions are gated on host-service init + a loaded working key.
     * Set false to restore the previous behavior (transaction starts immediately,
     * even if the terminal is still booting).
     */
    private static final boolean REQUIRE_READY_BEFORE_TXN = true;
    // Auto-retry window while the working key is still downloading (~30s total).
    private static final int TXN_READY_MAX_RETRIES = 20;
    private static final long TXN_READY_RETRY_INTERVAL_MS = 1500;
    private int txnReadyRetryCount = 0;

    /**
     * True when the terminal is ready to take a transaction.
     *
     * MKSK build: requires the host service initialized AND a working key loaded
     * (the Type 88 download completed). DUKPT build: the PIN key is injected in
     * hardware — no working-key download — so only host-service init is required.
     * Fails toward "ready" only when the gate is disabled via the flag.
     */
    private boolean isTransactionReady() {
        if (!REQUIRE_READY_BEFORE_TXN) {
            return true;
        }
        if (!isAtmHostServiceReady()) {
            return false;
        }
        if (isDukptBuild()) {
            return true;  // hardware-injected PIN key, no working-key download
        }
        return atmHostService != null && atmHostService.hasWorkingKeys();
    }

    private boolean requestATMPin() {
        Log.d(TAG, "requestATMPin() - KEY_MODE=" + BuildConfig.KEY_MODE);
        Log.d(TAG, "  atmDukptEnabled: " + GlobalPara.atmDukptEnabled);
        Log.d(TAG, "  atmPinBlockFormat: " + GlobalPara.atmPinBlockFormat);

        // Try DUKPT only in a DUKPT build. The KEY_MODE gate is deliberate: stale
        // prefs, the admin settings migration, or a failed AtmHostService init used
        // to leave atmDukptEnabled=true in an MKSK build, which then encrypted the
        // PIN at C000/0000 (no key → 0x2905) instead of using MKSK at C000/0010.
        if (isDukptBuild()
                && (GlobalPara.atmDukptEnabled || "DUKPT".equals(GlobalPara.atmPinBlockFormat))) {
            Log.d(TAG, "ATM PIN: Attempting DUKPT (Format 0) - MVP APPROACH...");

            // Use Castle MVP approach - standalone DUKPT PIN collection
            // This bypasses EMV SDK's internal PIN flow that was failing with 0x1003
            boolean dukptOK = requestATMPinEntryDukptMvp();

            if (dukptOK) {
                Log.d(TAG, "ATM PIN: DUKPT successful - PIN block: " + GlobalPara.atmEncryptedPinBlock);
                Log.d(TAG, "ATM PIN: KSN: " + GlobalPara.atmDukptKsn);
                return true;
            } else {
                Log.w(TAG, "ATM PIN: DUKPT failed - falling back to Format 1");
                // Fall through to Format 1
            }
        }

        // Use Format 1 (default or fallback)
        Log.d(TAG, "ATM PIN: Using Format 1 (software PIN)...");
        return requestATMPinEntry();
    }

    /**
     * Request PIN entry using software-based collection and Format 1 PIN block.
     *
     * Format 1 PIN blocks use random padding instead of PAN XOR.
     * This allows PIN block creation without access to the clear PAN,
     * which Castle SDK masks for PCI compliance.
     *
     * The server will decrypt the Format 1 PIN block and translate it
     * to Format 0 using the PAN from the decrypted Track 2 data.
     *
     * @return true if PIN was successfully entered and encrypted
     */
    private boolean requestATMPinEntry() {
        Log.d(TAG, "requestATMPinEntry() - SOFTWARE-BASED PIN entry using FORMAT 1 (no PAN needed)");

        // Reset state
        atmPinEntrySuccess = false;
        atmPinEntryCancelled = false;
        atmPinEntryComplete = false;
        atmPinPadReady = false;
        atmCollectedPin.setLength(0);
        GlobalPara.atmEncryptedPinBlock = "";

        // Show PIN pad dialog on UI thread with button listeners
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showSoftwarePinPadDialog();
            }
        });

        // Wait for PIN pad to be shown
        int waitCount = 0;
        while (!atmPinPadReady && waitCount < 20) {
            MyUtility.sleep(100);
            waitCount++;
        }

        if (!atmPinPadReady) {
            Log.e(TAG, "ATM PIN: PIN pad not ready after waiting");
            dismissAtmPinPadDialog();
            return false;
        }

        Log.d(TAG, "ATM PIN: Software PIN pad ready, waiting for PIN entry...");

        // Wait for PIN entry to complete (Enter pressed or cancelled)
        int timeout = 600;  // 60 seconds
        while (!atmPinEntryComplete && !atmPinEntryCancelled && timeout > 0) {
            MyUtility.sleep(100);
            timeout--;
        }

        // Dismiss dialog
        dismissAtmPinPadDialog();

        // Check timeout
        if (timeout <= 0) {
            Log.d(TAG, "ATM PIN: Entry timed out");
            ui_ShowMsg("PIN Entry Timeout");
            return false;
        }

        // Check if cancelled
        if (atmPinEntryCancelled) {
            Log.d(TAG, "ATM PIN: Entry was cancelled");
            ui_ShowMsg("PIN Entry Cancelled");
            return false;
        }

        // Validate PIN length
        String pin = atmCollectedPin.toString();
        if (pin.length() < 4) {
            Log.e(TAG, "ATM PIN: PIN too short: " + pin.length() + " digits");
            ui_ShowMsg("PIN Too Short");
            return false;
        }

        Log.d(TAG, "ATM PIN: PIN collected, length=" + pin.length());

        try {
            // Get clear PAN for Format 0 PIN block
            String clearPan = GlobalPara.atmClearPan;
            if (clearPan == null || clearPan.isEmpty() || clearPan.length() < 13) {
                Log.e(TAG, "ATM PIN: No clear PAN available for Format 0. atmClearPan=" +
                    (clearPan != null ? clearPan.length() + " chars" : "null"));
                ui_ShowMsg("PAN Not Available\n\nCard read error");
                MyUtility.sleep(2000);
                return false;
            }

            Log.d(TAG, "ATM PIN: Clear PAN: " + clearPan.substring(0, 6) + "****");
            Log.d(TAG, "ATM PIN: DUKPT enabled: " + GlobalPara.atmDukptEnabled);

            // Choose encryption method — DUKPT only in a DUKPT build (see isDukptBuild)
            if (isDukptBuild() && GlobalPara.atmDukptEnabled) {
                // DUKPT encryption (requires IPEK at C001/1A)
                Log.d(TAG, "ATM PIN: Using DUKPT encryption");
                DukptEncryptedData encryptedData = encryptPinBlockWithDukpt(pin, clearPan);

                if (encryptedData != null && encryptedData.encryptedData.length() == 16) {
                    GlobalPara.atmEncryptedPinBlock = encryptedData.encryptedData;
                    GlobalPara.atmDukptKsn = encryptedData.ksn;
                    Log.d(TAG, "ATM PIN: Encrypted PIN block: " + encryptedData.encryptedData);
                    Log.d(TAG, "ATM PIN: KSN: " + encryptedData.ksn);

                    atmPinEntrySuccess = true;
                    ui_ShowMsg("PIN Accepted");
                    MyUtility.sleep(500);
                    return true;
                } else {
                    Log.e(TAG, "ATM PIN: DUKPT encryption failed");
                    ui_ShowMsg("PIN Encryption Error");
                    return false;
                }
            } else {
                // Working Key encryption (traditional ATM - requires key at C001/A1)
                Log.d(TAG, "ATM PIN: Using Working Key encryption (TMK approach)");
                String encryptedPinBlock = encryptPinBlockWithWorkingKey(pin, clearPan);

                if (encryptedPinBlock != null && encryptedPinBlock.length() == 16) {
                    GlobalPara.atmEncryptedPinBlock = encryptedPinBlock;
                    GlobalPara.atmDukptKsn = "";  // No KSN for working key
                    Log.d(TAG, "ATM PIN: Encrypted PIN block: " + encryptedPinBlock);

                    atmPinEntrySuccess = true;
                    ui_ShowMsg("PIN Accepted");
                    MyUtility.sleep(500);
                    return true;
                } else {
                    Log.e(TAG, "ATM PIN: Working Key encryption failed");
                    ui_ShowMsg("PIN Encryption Error");
                    return false;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ATM PIN: Exception: " + e.getMessage(), e);
            ui_ShowMsg("PIN Error");
            return false;
        }
    }

    /**
     * Request ATM PIN entry using DUKPT (Format 0).
     * Uses SDK's VirtualPINPad which has access to clear PAN internally.
     * Returns encrypted PIN block + KSN.
     *
     * @return true if PIN was successfully entered and encrypted with DUKPT
     */
    private boolean requestATMPinEntryDukpt() {
        Log.d(TAG, "requestATMPinEntryDukpt() - DUKPT PIN entry (Format 0 with SDK)");

        // Reset state
        atmPinEntrySuccess = false;
        atmPinEntryCancelled = false;
        atmPinEntryComplete = false;
        GlobalPara.atmEncryptedPinBlock = "";
        GlobalPara.atmDukptKsn = "";

        try {
            // Initialize KMS2 System settings (required for PIN pad - per CastlesHost sample)
            CtKMS2System kms2System = new CtKMS2System();
            kms2System.init();
            kms2System.setPINSound(true);       // Enable beep on keypress
            kms2System.setPINPADMoving(false);  // Static PIN pad position
            kms2System.setPINScrambling(false); // No key scrambling for now
            Log.d(TAG, "DUKPT: KMS2 System initialized");

            CtKMS2Dukpt dukptKey = new CtKMS2Dukpt();
            CtKMS2VirtualPINPad virtualPinPad = new CtKMS2VirtualPINPad();

            // Show PIN pad dialog and get button coordinates
            Log.d(TAG, "DUKPT: Showing PIN pad dialog...");

            // Show the PIN pad dialog on UI thread
            atmPinPadReady = false;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showAtmPinPadDialog();
                }
            });

            // Wait for dialog to be laid out
            int waitCount = 0;
            while (!atmPinPadReady && waitCount < 20) {
                Thread.sleep(50);
                waitCount++;
            }

            if (!atmPinPadReady) {
                Log.e(TAG, "DUKPT: PIN pad dialog not ready after timeout");
                return false;
            }

            Log.d(TAG, "DUKPT: PIN pad dialog ready, getting button positions...");

            // Get button positions from dialog
            if (!getAtmPinPadPositions(virtualPinPad)) {
                Log.e(TAG, "DUKPT: Failed to get button positions");
                dismissAtmPinPadDialog();
                return false;
            }

            Log.d(TAG, "DUKPT: Button positions configured successfully");

            // Get PAN from card data - required for Format 0 PIN block XOR
            // Priority: atmClearPan (from Tag 5A/DF35) > atmTrack2Data > asciiPAN
            String pan = "";

            // 1. First try atmClearPan (set from Tag 5A during chip read)
            if (GlobalPara.atmClearPan != null && !GlobalPara.atmClearPan.isEmpty()
                    && GlobalPara.atmClearPan.length() >= 13 && !GlobalPara.atmClearPan.contains("*")) {
                pan = GlobalPara.atmClearPan;
                Log.d(TAG, "DUKPT: Using CLEAR PAN from atmClearPan: " + pan.substring(0, Math.min(6, pan.length())) + "****");
            }
            // 2. Try Track2 data if it's clear (not encrypted)
            else if (GlobalPara.atmTrack2Data != null && !GlobalPara.atmTrack2Data.isEmpty()
                    && !GlobalPara.atmTrack2Data.startsWith("E:") && !GlobalPara.atmTrack2Data.contains("*")) {
                String track2 = GlobalPara.atmTrack2Data.replace(";", "").replace("?", "");
                if (track2.contains("=")) {
                    pan = track2.split("=")[0];
                    Log.d(TAG, "DUKPT: Using PAN from clear Track2: " + pan.substring(0, Math.min(6, pan.length())) + "****");
                }
            }
            // 3. Try asciiPAN as last resort (often masked)
            else if (GlobalPara.asciiPAN != null && !GlobalPara.asciiPAN.isEmpty()) {
                String cleanPan = GlobalPara.asciiPAN.replace("*", "").replace(" ", "");
                if (cleanPan.length() >= 13) {
                    pan = cleanPan;
                    Log.d(TAG, "DUKPT: Using PAN from asciiPAN (cleaned): " + pan.substring(0, Math.min(6, pan.length())) + "****");
                } else {
                    Log.w(TAG, "DUKPT: asciiPAN too short after removing masks: " + cleanPan.length() + " digits");
                }
            }

            if (pan.isEmpty() || pan.length() < 13) {
                Log.e(TAG, "DUKPT: No valid PAN available for Format 0 PIN block. atmClearPan=[" +
                    (GlobalPara.atmClearPan != null ? GlobalPara.atmClearPan.length() + " chars" : "null") +
                    "], asciiPAN=[" + (GlobalPara.asciiPAN != null ? GlobalPara.asciiPAN : "null") + "]");
                return false;
            }

            // Set the PAN as input data - CRITICAL for Format 0 PIN block!
            byte[] panBytes = pan.getBytes();
            dukptKey.setInputData(panBytes, 0, panBytes.length);
            Log.d(TAG, "DUKPT: Set PAN input data (" + panBytes.length + " bytes)");

            // Use new derived key for this transaction (not the current key)
            dukptKey.isUseCurrentKey(false);

            // Configure DUKPT key location (where IPEK is stored)
            Log.d(TAG, "DUKPT: Selecting key at " + String.format("0x%04X/0x%04X",
                    GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));
            dukptKey.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);

            // Set cipher method (ECB for PIN block encryption)
            dukptKey.setCipherMethod(CtKMS2Dukpt.PIN_CIPHER_METHOD_ECB);

            // Configure PIN entry parameters
            // PinBlockType 0x00 = Format 0 (ISO-0) - requires PAN for XOR
            byte pinBlockType = 0x00;
            byte maxDigit = 12;
            byte minDigit = 4;
            byte outBlockLen = 8;
            dukptKey.setPinInfo(pinBlockType, maxDigit, minDigit, outBlockLen);

            // PIN control: Format 0 (ANSI X9.8/ISO-4), 60 second timeout, 30 second first key timeout
            // First param is PIN block type constant, not "null pin allowed"
            // CastlesHost uses CtKMS2Dukpt.PIN_BLOCKTYPE_ANSI_X9_8_ISO_4 for Format 0
            int entryKeyTimeout = 60;  // Seconds between keystrokes
            int firstEntryKeyTimeout = 30;  // Seconds for first keystroke
            dukptKey.setPinControl(CtKMS2Dukpt.PIN_BLOCKTYPE_ANSI_X9_8_ISO_4, entryKeyTimeout, firstEntryKeyTimeout);

            // Set callback for PIN entry events
            final StringBuilder pinDigits = new StringBuilder();

            IKMS2Callback.Stub callback = new IKMS2Callback.Stub() {
                @Override
                public int testCancel() {
                    return 0;
                }

                @Override
                public void onGetDigit(byte digit) {
                    Log.d(TAG, "DUKPT: onGetDigit count=" + digit);
                    pinDigits.setLength(0);
                    for (int i = 0; i < digit; i++) {
                        pinDigits.append("*");
                    }
                    // Update the PIN pad dialog's digit display
                    runOnUiThread(() -> {
                        if (atmPinDialog != null && atmPinDialog.isShowing()) {
                            TextView txtDigits = atmPinDialog.findViewById(R.id.txtPinDigits);
                            if (txtDigits != null) {
                                txtDigits.setText(pinDigits.toString());
                            }
                        }
                    });
                }

                @Override
                public void onGetFunctionKey(byte functionKey) {
                    Log.d(TAG, "DUKPT: onGetFunctionKey=" + functionKey);
                    // Function keys: 'A'=Enter, 'C'=Cancel, 'R'=Clear
                    if (functionKey == 'C' || functionKey == 0x1B) { // Cancel
                        atmPinEntryCancelled = true;
                    }
                }
            };

            dukptKey.setCallback(callback);

            // Show message that PIN pad will appear
            runOnUiThread(() -> ui_ShowLog("Enter PIN on keypad..."));

            // The SDK will show its own PIN pad UI when startVirtualPin is called
            Log.d(TAG, "DUKPT: Starting VirtualPIN...");

            // Start the PIN entry (blocking call)
            dukptKey.startVirtualPin(virtualPinPad);

            // Get the encrypted PIN block and KSN
            byte[] encryptedBlock = dukptKey.getOutpuData();
            byte[] ksn = dukptKey.getKSN();

            if (encryptedBlock != null && encryptedBlock.length >= 8) {
                GlobalPara.atmEncryptedPinBlock = Converter.byteArray2HexString(encryptedBlock, 8);
                Log.d(TAG, "DUKPT: Encrypted PIN block: " + GlobalPara.atmEncryptedPinBlock);
            }

            if (ksn != null && ksn.length > 0) {
                GlobalPara.atmDukptKsn = Converter.byteArray2HexString(ksn, ksn.length);
                Log.d(TAG, "DUKPT: KSN: " + GlobalPara.atmDukptKsn);
            }

            // Dismiss the PIN pad dialog
            dismissAtmPinPadDialog();

            // Verify we got valid data
            if (GlobalPara.atmEncryptedPinBlock.length() == 16 && GlobalPara.atmDukptKsn.length() >= 16) {
                Log.d(TAG, "DUKPT: PIN entry successful");
                atmPinEntrySuccess = true;
                ui_ShowMsg("PIN Accepted");
                MyUtility.sleep(500);
                return true;
            } else {
                Log.e(TAG, "DUKPT: Invalid PIN block or KSN");
                Log.e(TAG, "  PIN block length: " + GlobalPara.atmEncryptedPinBlock.length());
                Log.e(TAG, "  KSN length: " + GlobalPara.atmDukptKsn.length());
                return false;
            }

        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "DUKPT: KMS2 Exception: " + String.format("0x%08X", e.getError()));
            e.showStatus();
            dismissAtmPinPadDialog();

            // Check for specific errors
            int error = e.getError();
            if (error == 0x00002000) {
                ui_ShowMsg("DUKPT Key Not Found\n\nInject IPEK first");
            } else if (error == 0x00002909) {
                ui_ShowMsg("DUKPT Key Error\n\nCheck key format");
            } else {
                ui_ShowMsg("DUKPT Error\n\n" + String.format("0x%08X", error));
            }
            MyUtility.sleep(2000);
            return false;

        } catch (Exception e) {
            Log.e(TAG, "DUKPT: Exception: " + e.getMessage(), e);
            dismissAtmPinPadDialog();
            ui_ShowMsg("DUKPT Error");
            return false;
        }
    }

    /**
     * Show key attributes for debugging - based on Castle MVP sample.
     * Logs the key type and attribute to verify key is properly configured.
     *
     * @param keyset The key set (e.g., 0xC000 or 0xC001)
     * @param keyindex The key index within the set
     */
    private void showKeyAttributes(int keyset, int keyindex) {
        Log.d(TAG, "showKeyAttributes: Checking key at " + String.format("0x%04X/0x%04X", keyset, keyindex));
        try {
            CtKMS2Key key = new CtKMS2Key();
            key.selectKey(keyset, keyindex);

            String sKeySet = "KeySet = " + String.format("0x%04X", key.getKeySet());
            String sKeyIndex = "KeyIndex = " + String.format("0x%04X", key.getKeyIndex());
            String sKeyType = "KeyType = " + String.format("0x%02X", key.getKeyType());
            String sKeyAttribute = "KeyAttribute = " + String.format("0x%08X", key.getKeyAttribute());

            Log.d(TAG, "showKeyAttributes: " + sKeySet);
            Log.d(TAG, "showKeyAttributes: " + sKeyIndex);
            Log.d(TAG, "showKeyAttributes: " + sKeyType);
            Log.d(TAG, "showKeyAttributes: " + sKeyAttribute);

            // Interpret key type
            int keyType = key.getKeyType();
            String keyTypeDesc;
            switch (keyType) {
                case 0x00: keyTypeDesc = "Plain/3DES"; break;
                case 0x01: keyTypeDesc = "DUKPT IPEK"; break;
                case 0x02: keyTypeDesc = "RSA"; break;
                case 0x10: keyTypeDesc = "AES"; break;
                default: keyTypeDesc = "Unknown"; break;
            }
            Log.d(TAG, "showKeyAttributes: KeyType description = " + keyTypeDesc);

            // Interpret key attributes
            int keyAttr = key.getKeyAttribute();
            StringBuilder attrDesc = new StringBuilder();
            if ((keyAttr & 0x00000001) != 0) attrDesc.append("PIN ");
            if ((keyAttr & 0x00000002) != 0) attrDesc.append("MAC ");
            if ((keyAttr & 0x00000004) != 0) attrDesc.append("DATA_ENC ");
            if ((keyAttr & 0x00000008) != 0) attrDesc.append("DATA_DEC ");
            if ((keyAttr & 0x00000010) != 0) attrDesc.append("DERIVE ");
            if ((keyAttr & 0x00000020) != 0) attrDesc.append("KBPK ");
            if (attrDesc.length() == 0) attrDesc.append("NONE");
            Log.d(TAG, "showKeyAttributes: Attributes = " + attrDesc.toString());

        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "showKeyAttributes: KMS2 Exception: " + String.format("0x%08X", e.getErrorCode()));
            Log.e(TAG, "showKeyAttributes: Key may not exist or wrong type");
        } catch (Exception e) {
            Log.e(TAG, "showKeyAttributes: Exception: " + e.getMessage());
        }
    }

    /**
     * Request ATM PIN entry using DUKPT - Castle MVP approach.
     * This method follows Castle's sample code exactly, bypassing the EMV SDK's internal PIN flow.
     * Uses CtKMS2Dukpt directly with CtKMS2Callback for standalone PIN collection.
     *
     * Key differences from EMV-integrated approach:
     * 1. Uses CtKMS2Callback (not IKMS2Callback.Stub)
     * 2. Uses setPAN() instead of setInputData()
     * 3. Uses 3-param setPinInfo()
     * 4. Calls showKeyAttributes() first to verify key
     *
     * @return true if PIN was successfully entered and encrypted
     */
    private boolean requestATMPinEntryDukptMvp() {
        Log.d(TAG, ">>> MVP: requestATMPinEntryDukptMvp() - Castle MVP DUKPT PIN approach");

        // Reset state
        atmPinEntrySuccess = false;
        atmPinEntryCancelled = false;
        atmPinEntryComplete = false;
        GlobalPara.atmEncryptedPinBlock = "";
        GlobalPara.atmDukptKsn = "";

        // Key location depends on protocol:
        // Triton: CFFF/0000 (TMK/Master Key) — uses CtKMS2SymmetryKey
        // Hyosung: C000/0000 (DUKPT) — uses CtKMS2Dukpt
        boolean useTritonKeys = "TRITON".equals(GlobalPara.atmProtocolType);
        int keySet = GlobalPara.atmDukptKeySet;
        int keyIndex = GlobalPara.atmDukptKeyIndex;

        Log.d(TAG, ">>> MVP: Key location: " + String.format("0x%04X/0x%04X", keySet, keyIndex) +
              " (protocol=" + GlobalPara.atmProtocolType + ", useTriton=" + useTritonKeys + ")");

        // Step 1: Show key attributes (Castle MVP does this first)
        showKeyAttributes(keySet, keyIndex);

        try {
            // Initialize KMS2 System settings
            CtKMS2System kms2System = new CtKMS2System();
            kms2System.init();
            kms2System.setPINSound(true);
            kms2System.setPINPADMoving(false);
            kms2System.setPINScrambling(false);
            Log.d(TAG, ">>> MVP: KMS2 System initialized");

            // Get PAN for Format 0 PIN block
            String pan = "";
            if (GlobalPara.atmClearPan != null && !GlobalPara.atmClearPan.isEmpty()
                    && GlobalPara.atmClearPan.length() >= 13 && !GlobalPara.atmClearPan.contains("*")) {
                pan = GlobalPara.atmClearPan;
                Log.d(TAG, ">>> MVP: Using CLEAR PAN: " + pan.substring(0, Math.min(6, pan.length())) + "****");
            } else if (GlobalPara.atmTrack2Data != null && !GlobalPara.atmTrack2Data.isEmpty()
                    && !GlobalPara.atmTrack2Data.startsWith("E:") && !GlobalPara.atmTrack2Data.contains("*")) {
                String track2 = GlobalPara.atmTrack2Data.replace(";", "").replace("?", "");
                if (track2.contains("=")) {
                    pan = track2.split("=")[0];
                    Log.d(TAG, ">>> MVP: Using PAN from Track2: " + pan.substring(0, Math.min(6, pan.length())) + "****");
                }
            }

            if (pan.isEmpty() || pan.length() < 13) {
                Log.e(TAG, ">>> MVP: No valid PAN available");
                ui_ShowMsg("No PAN for PIN");
                return false;
            }

            // Convert PAN to bytes (ASCII digits) - exactly like Castle sample
            byte[] panBytes = pan.getBytes();
            Log.d(TAG, ">>> MVP: PAN bytes length = " + panBytes.length);

            // Setup Virtual PIN Pad
            CtKMS2VirtualPINPad virtualPinPad = new CtKMS2VirtualPINPad();

            // Show PIN pad dialog on UI thread
            Log.d(TAG, ">>> MVP: Showing PIN pad dialog...");
            atmPinPadReady = false;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showAtmPinPadDialog();
                }
            });

            // Wait for dialog layout
            int waitCount = 0;
            while (!atmPinPadReady && waitCount < 20) {
                Thread.sleep(50);
                waitCount++;
            }

            if (!atmPinPadReady) {
                Log.e(TAG, ">>> MVP: PIN pad dialog not ready");
                return false;
            }

            // Get button positions
            if (!getAtmPinPadPositions(virtualPinPad)) {
                Log.e(TAG, ">>> MVP: Failed to get button positions");
                dismissAtmPinPadDialog();
                return false;
            }

            Log.d(TAG, ">>> MVP: Button positions configured");

            // Create CtKMS2Callback (Castle MVP approach - NOT IKMS2Callback.Stub)
            final StringBuilder pinDigits = new StringBuilder();
            CtKMS2Callback callback = new CtKMS2Callback() {
                @Override
                public int testCancel() {
                    return 0;  // 0 = don't cancel, non-zero = cancel
                }

                @Override
                public void onGetDigit(byte digit) {
                    Log.d(TAG, ">>> MVP: onGetDigit count=" + digit);
                    pinDigits.setLength(0);
                    for (int i = 0; i < digit; i++) {
                        pinDigits.append("*");
                    }
                    // Update UI
                    runOnUiThread(() -> {
                        if (atmPinDialog != null && atmPinDialog.isShowing()) {
                            TextView txtDigits = atmPinDialog.findViewById(R.id.txtPinDigits);
                            if (txtDigits != null) {
                                txtDigits.setText(pinDigits.toString());
                            }
                        }
                    });
                }

                @Override
                public void onGetFunctionKey(byte functionKey) {
                    Log.d(TAG, ">>> MVP: onGetFunctionKey=" + (char)functionKey + " (0x" + String.format("%02X", functionKey) + ")");
                    if (functionKey == 'C' || functionKey == 0x1B) {
                        atmPinEntryCancelled = true;
                    }
                }
            };

            // PIN encryption setup — common parameters
            byte pinBlockType = 0x00; // PIN_BLOCKTYPE_ANSI_X9_8_ISO_0 = Format 0
            byte maxDigit = 12;
            byte minDigit = 4;
            byte nullPinAllowed = 0;
            int timeout = 60;
            int firstTimeout = 30;

            Log.d(TAG, ">>> MVP: selectKey(" + String.format("0x%04X, 0x%04X", keySet, keyIndex) + ")");

            if (useTritonKeys) {
                // Triton Master/Session: Software PIN pad + working key encryption
                // No hardware KMS2 needed — uses downloaded working key
                Log.d(TAG, ">>> MVP: Triton Master/Session PIN encryption");
                Log.d(TAG, ">>> MVP: Using software PIN pad + CastleKeyManager working key");

                // Show software PIN pad and collect PIN
                runOnUiThread(() -> ui_ShowLog("Enter PIN..."));
                String pin = collectPinForCallback();

                dismissAtmPinPadDialog();

                if (pin == null || pin.isEmpty()) {
                    Log.w(TAG, ">>> MVP: Triton PIN entry cancelled or empty");
                    atmPinEntrySuccess = false;
                    atmPinEntryCancelled = true;
                    atmPinEntryComplete = true;
                    return false;
                }

                if (pin.length() < 4) {
                    Log.w(TAG, ">>> MVP: Triton PIN too short: " + pin.length());
                    atmPinEntrySuccess = false;
                    atmPinEntryComplete = true;
                    return false;
                }

                // Get clear PAN for Format 0 PIN block
                String clearPan = GlobalPara.atmClearPan;
                if (clearPan == null || clearPan.isEmpty()) {
                    clearPan = GlobalPara.asciiPAN;
                }
                Log.d(TAG, ">>> MVP: Triton PAN for PIN block: " + castech.emvtxn.util.PanMasker.maskPan(clearPan));

                // Create Format 0 (ISO 9564-1) clear PIN block
                String clearPinBlock = castech.emvtxn.atm.host.PinBlockFormatter.createFormat0PinBlock(pin, clearPan);
                Log.d(TAG, ">>> MVP: Triton clear PIN block created (Format 0)");

                // Encrypt with downloaded working key via CastleKeyManager
                if (atmHostService == null || atmHostService.getKeyManager() == null) {
                    Log.e(TAG, ">>> MVP: Triton - no key manager available");
                    atmPinEntrySuccess = false;
                    atmPinEntryComplete = true;
                    return false;
                }

                String encryptedPinHex = atmHostService.getKeyManager().encryptPinBlock(clearPinBlock);

                if (encryptedPinHex != null && encryptedPinHex.length() == 16) {
                    GlobalPara.atmEncryptedPinBlock = encryptedPinHex;
                    GlobalPara.atmDukptKsn = ""; // No KSN for Master/Session
                    Log.d(TAG, ">>> MVP: Triton PIN block encrypted successfully with working key");
                    atmPinEntrySuccess = true;
                    ui_ShowMsg("PIN Accepted");
                    MyUtility.sleep(500);
                } else {
                    Log.e(TAG, ">>> MVP: Triton PIN encryption failed - no working key loaded?");
                    atmPinEntrySuccess = false;
                }

                atmPinEntryComplete = true;
                return atmPinEntrySuccess;

            } else {
                // Hyosung: Use CtKMS2Dukpt with DUKPT at C000/0000
                Log.d(TAG, ">>> MVP: Creating CtKMS2Dukpt (Hyosung/DUKPT)...");
                CtKMS2Dukpt dukptKey = new CtKMS2Dukpt();
                dukptKey.selectKey(keySet, keyIndex);
                dukptKey.setCipherMethod(CtKMS2Dukpt.PIN_CIPHER_METHOD_ECB);
                dukptKey.setPinInfo(pinBlockType, maxDigit, minDigit);
                dukptKey.setPinControl(nullPinAllowed, timeout, firstTimeout);
                dukptKey.setCallback(callback);
                dukptKey.setPAN(panBytes);

            // Update UI
            runOnUiThread(() -> ui_ShowLog("Enter PIN on keypad..."));

            // 7. startVirtualPin - blocking call
            runOnUiThread(() -> ui_ShowLog("Enter PIN on keypad..."));
            Log.d(TAG, ">>> MVP: startVirtualPin() - waiting for PIN entry...");
            dukptKey.startVirtualPin(virtualPinPad);

            // 8. getKSN
            Log.d(TAG, ">>> MVP: getKSN()");
            byte[] ksn = dukptKey.getKSN();

            // 9. getOutputData - get encrypted PIN block
            Log.d(TAG, ">>> MVP: getOutputData()");
            byte[] encryptedBlock = dukptKey.getOutpuData();

            // Dismiss dialog
            dismissAtmPinPadDialog();

            // Process results
            if (encryptedBlock != null && encryptedBlock.length >= 8) {
                GlobalPara.atmEncryptedPinBlock = Converter.byteArray2HexString(encryptedBlock, 8);
                Log.d(TAG, ">>> MVP: Encrypted PIN block: " + GlobalPara.atmEncryptedPinBlock);
            } else {
                Log.e(TAG, ">>> MVP: encryptedBlock is null or too short");
            }

            if (ksn != null && ksn.length > 0) {
                GlobalPara.atmDukptKsn = Converter.byteArray2HexString(ksn, ksn.length);
                Log.d(TAG, ">>> MVP: KSN: " + GlobalPara.atmDukptKsn);
            } else {
                Log.e(TAG, ">>> MVP: KSN is null or empty");
            }

            // Validate DUKPT result
            if (GlobalPara.atmEncryptedPinBlock.length() == 16 && GlobalPara.atmDukptKsn.length() >= 16) {
                Log.d(TAG, ">>> MVP: PIN entry SUCCESS (DUKPT)");
                atmPinEntrySuccess = true;
                ui_ShowMsg("PIN Accepted");
                MyUtility.sleep(500);
                return true;
            } else {
                Log.e(TAG, ">>> MVP: Invalid PIN block or KSN");
                Log.e(TAG, ">>> MVP:   PIN block length: " + GlobalPara.atmEncryptedPinBlock.length());
                Log.e(TAG, ">>> MVP:   KSN length: " + GlobalPara.atmDukptKsn.length());
                ui_ShowMsg("PIN Entry Failed");
                return false;
            }
            } // end else (Hyosung/DUKPT path)

        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, ">>> MVP: KMS2 Exception: " + String.format("0x%08X", e.getErrorCode()));
            e.printStackTrace();
            dismissAtmPinPadDialog();

            int error = e.getErrorCode();
            String errorMsg;
            if (error == 0x00002000) {
                errorMsg = "DUKPT Key Not Found\nCheck key at " + String.format("0x%04X/0x%04X", keySet, keyIndex);
            } else if (error == 0x00002909) {
                errorMsg = "DUKPT Key Error\nWrong key type/attribute";
            } else if (error == 0x00002003) {
                errorMsg = "Key Attribute Error\nKey doesn't have PIN attribute";
            } else {
                errorMsg = "DUKPT Error\n" + String.format("0x%08X", error);
            }
            ui_ShowMsg(errorMsg);
            MyUtility.sleep(2000);
            return false;

        } catch (Exception e) {
            Log.e(TAG, ">>> MVP: Exception: " + e.getMessage(), e);
            dismissAtmPinPadDialog();
            ui_ShowMsg("MVP PIN Error:\n" + e.getMessage());
            return false;
        }
    }

    /**
     * Encrypts track 2 data using DUKPT.
     * Based on CastlesHost SecurityUtil.encryptDataByDukpt() pattern.
     *
     * @param track2Data The track 2 data to encrypt (ASCII string with sentinels)
     * @return DukptEncryptedData containing encrypted data and KSN, or null on failure
     */
    public DukptEncryptedData encryptTrack2WithDukpt(String track2Data) {
        // DUKPT only exists in a DUKPT build (same gate as the PIN path). In an MKSK
        // build this used to attempt C000/0000 → 0x2905 on every transaction before
        // falling back to clear track 2; skip straight to the fallback instead.
        // Callers handle null by sending clear track 2 (ARQC provides security).
        if (!isDukptBuild()) {
            Log.d(TAG, "encryptTrack2: skipped — not a DUKPT build (clear track 2 + ARQC)");
            return null;
        }
        if (track2Data == null || track2Data.isEmpty()) {
            Log.w(TAG, "DUKPT encryptTrack2: No track 2 data to encrypt");
            return null;
        }

        Log.d(TAG, "DUKPT encryptTrack2: Encrypting track 2 data, length=" + track2Data.length());

        try {
            // Convert track 2 string to bytes
            byte[] inputData = track2Data.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            // Pad to 8-byte boundary (PKCS padding)
            int paddingLen = 8 - (inputData.length % 8);
            byte[] paddedData = java.util.Arrays.copyOf(inputData, inputData.length + paddingLen);
            java.util.Arrays.fill(paddedData, inputData.length, paddedData.length, (byte) paddingLen);

            Log.d(TAG, "DUKPT encryptTrack2: Input length=" + inputData.length + ", padded length=" + paddedData.length);

            // Setup DUKPT encryption
            CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
            dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
            dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_CBC);

            // Set ICV (Initialization Vector) - all zeros for CBC
            byte[] icv = new byte[8];
            dukpt.setICV(icv, 0, 8);

            // Set input data
            dukpt.setInputData(paddedData, 0, paddedData.length);

            // Use a new derived key (not current)
            dukpt.isUseCurrentKey(false);

            // Perform encryption
            dukpt.dataEncrypt();

            // Get output
            byte[] encryptedData = dukpt.getOutpuData();
            byte[] ksn = dukpt.getKSN();

            if (encryptedData != null && ksn != null) {
                String encryptedHex = Converter.byteArray2HexString(encryptedData, encryptedData.length);
                String ksnHex = Converter.byteArray2HexString(ksn, ksn.length);

                Log.d(TAG, "DUKPT encryptTrack2: SUCCESS");
                Log.d(TAG, "  Encrypted data: " + encryptedHex);
                Log.d(TAG, "  KSN: " + ksnHex);

                return new DukptEncryptedData(encryptedHex, ksnHex);
            } else {
                Log.e(TAG, "DUKPT encryptTrack2: Encryption returned null data");
                return null;
            }

        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "DUKPT encryptTrack2: KMS2 Exception: " + String.format("0x%08X", e.getError()));
            e.showStatus();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "DUKPT encryptTrack2: Exception: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Encrypts a Format 0 PIN block using DUKPT.
     * Creates the clear PIN block from PIN and PAN, then encrypts with DUKPT.
     *
     * @param pin The clear PIN (4-12 digits)
     * @param pan The clear PAN (13-19 digits)
     * @return DukptEncryptedData containing encrypted PIN block and KSN, or null on failure
     */
    public DukptEncryptedData encryptPinBlockWithDukpt(String pin, String pan) {
        if (pin == null || pin.length() < 4) {
            Log.e(TAG, "DUKPT PIN: Invalid PIN length");
            return null;
        }
        if (pan == null || pan.length() < 13) {
            Log.e(TAG, "DUKPT PIN: Invalid PAN length: " + (pan != null ? pan.length() : "null"));
            return null;
        }

        Log.d(TAG, "DUKPT PIN: Creating Format 0 PIN block with PAN XOR");
        Log.d(TAG, "DUKPT PIN: PIN length=" + pin.length() + ", PAN=" + pan.substring(0, 6) + "****");

        try {
            // Create Format 0 clear PIN block (with PAN XOR)
            String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(pin, pan);
            Log.d(TAG, "DUKPT PIN: Clear PIN block created (Format 0)");

            // Convert to bytes for encryption
            byte[] pinBlockBytes = Converter.hexString2ByteArray(clearPinBlock);

            // Setup DUKPT encryption - use ECB for PIN blocks (not CBC)
            CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
            dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
            dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);  // ECB for PIN

            // Set input data (8 bytes for PIN block)
            dukpt.setInputData(pinBlockBytes, 0, pinBlockBytes.length);

            // Use a new derived key for this transaction
            dukpt.isUseCurrentKey(false);

            // Perform encryption
            dukpt.dataEncrypt();

            // Get output
            byte[] encryptedData = dukpt.getOutpuData();
            byte[] ksn = dukpt.getKSN();

            if (encryptedData != null && encryptedData.length >= 8 && ksn != null) {
                String encryptedHex = Converter.byteArray2HexString(encryptedData, 8);
                String ksnHex = Converter.byteArray2HexString(ksn, ksn.length);

                Log.d(TAG, "DUKPT PIN: SUCCESS");
                Log.d(TAG, "  Encrypted PIN block: " + encryptedHex);
                Log.d(TAG, "  KSN: " + ksnHex);

                return new DukptEncryptedData(encryptedHex, ksnHex);
            } else {
                Log.e(TAG, "DUKPT PIN: Encryption returned null/invalid data");
                return null;
            }

        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "DUKPT PIN: KMS2 Exception: " + String.format("0x%08X", e.getError()));
            e.showStatus();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "DUKPT PIN: Exception: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Encrypt PIN block using Working Key (traditional ATM approach).
     * Uses CastleKeyManager which handles both hardware (KMS2) and software encryption.
     * The working key is received from processor and decrypted with TMK.
     * Returns encrypted PIN block (no KSN - that's only for DUKPT).
     */
    public String encryptPinBlockWithWorkingKey(String pin, String pan) {
        if (pin == null || pin.length() < 4) {
            Log.e(TAG, "WorkingKey PIN: Invalid PIN length");
            return null;
        }
        if (pan == null || pan.length() < 13) {
            Log.e(TAG, "WorkingKey PIN: Invalid PAN length: " + (pan != null ? pan.length() : "null"));
            return null;
        }

        Log.d(TAG, "WorkingKey PIN: Creating Format 0 PIN block with Working Key");
        Log.d(TAG, "WorkingKey PIN: PIN length=" + pin.length() + ", PAN=" + pan.substring(0, 6) + "****");

        try {
            // Create Format 0 clear PIN block (with PAN XOR)
            String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(pin, pan);
            Log.d(TAG, "WorkingKey PIN: Clear PIN block created (Format 0)");

            // Use CastleKeyManager for encryption (handles software/hardware automatically)
            if (atmHostService != null && atmHostService.getKeyManager() != null) {
                CastleKeyManager keyManager = atmHostService.getKeyManager();

                if (!keyManager.isWorkingKeyLoaded()) {
                    Log.e(TAG, "WorkingKey PIN: No working key loaded in KeyManager");
                    Log.e(TAG, "WorkingKey PIN: Run Config Request first to get key from processor");
                    return null;
                }

                Log.d(TAG, "WorkingKey PIN: Using CastleKeyManager for encryption");
                String encryptedHex = keyManager.encryptPinBlock(clearPinBlock);

                if (encryptedHex != null && encryptedHex.length() == 16) {
                    Log.d(TAG, "WorkingKey PIN: SUCCESS");
                    Log.d(TAG, "  Encrypted PIN block: " + encryptedHex);
                    return encryptedHex;
                } else {
                    Log.e(TAG, "WorkingKey PIN: KeyManager encryption failed");
                    return null;
                }
            } else {
                Log.e(TAG, "WorkingKey PIN: AtmHostService or KeyManager not available");
                Log.e(TAG, "WorkingKey PIN: Ensure initializeAtmHostService() was called");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "WorkingKey PIN: Exception: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Container for DUKPT encrypted data and KSN.
     */
    public static class DukptEncryptedData {
        public final String encryptedData;  // Hex string
        public final String ksn;            // Hex string

        public DukptEncryptedData(String encryptedData, String ksn) {
            this.encryptedData = encryptedData;
            this.ksn = ksn;
        }
    }

    /**
     * Tests DUKPT PIN encryption against known test vector.
     * Test case from processor:
     * - Counter = 1 (KSN ending in 00001)
     * - PIN = 1234
     * - PAN = 4012345678909
     * - Expected encrypted PIN block = 1B9C1845EB993A7A
     *
     * @return Test result string for display
     */
    public String testDukptPinEncryption() {
        StringBuilder result = new StringBuilder();
        result.append("=== DUKPT PIN ENCRYPTION TEST ===\n\n");

        String testPin = "1234";
        String testPan = "4012345678909";
        String expectedResult = "1B9C1845EB993A7A";

        result.append("Test Vector:\n");
        result.append("  PIN: " + testPin + "\n");
        result.append("  PAN: " + testPan + "\n");
        result.append("  Expected: " + expectedResult + "\n\n");

        try {
            // Create Format 0 clear PIN block
            String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(testPin, testPan);
            result.append("Clear PIN Block (Format 0): " + clearPinBlock + "\n");

            // Verify the clear PIN block is correct
            // PIN part: 041234FFFFFFFFFF
            // PAN part: 0000123456789090 (rightmost 12 excluding check digit, padded with 0000)
            // Actually for PAN 4012345678909:
            //   Remove check digit: 401234567890
            //   Take rightmost 12: 401234567890
            //   Pad: 0000401234567890
            // XOR: 041234FFFFFFFFFF ^ 0000401234567890 = 0412749DCB98876F
            result.append("Expected clear: 0412749DCB98876F\n\n");

            // Test encryption with current DUKPT key
            result.append("DUKPT Key Location: " + String.format("0x%04X/0x%04X\n",
                    GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));

            // Setup DUKPT
            CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
            dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);

            // Try to get current KSN
            try {
                byte[] currentKsn = dukpt.getKSN();
                if (currentKsn != null) {
                    result.append("Current KSN: " + Converter.byteArray2HexString(currentKsn, currentKsn.length) + "\n");
                }
            } catch (Exception e) {
                result.append("Could not get current KSN: " + e.getMessage() + "\n");
            }

            // Encrypt using ECB (standard for PIN)
            dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);
            byte[] pinBlockBytes = Converter.hexString2ByteArray(clearPinBlock);
            dukpt.setInputData(pinBlockBytes, 0, pinBlockBytes.length);
            dukpt.isUseCurrentKey(false);
            dukpt.dataEncrypt();

            byte[] encryptedData = dukpt.getOutpuData();
            byte[] ksn = dukpt.getKSN();

            if (encryptedData != null) {
                String encryptedHex = Converter.byteArray2HexString(encryptedData, 8);
                String ksnHex = ksn != null ? Converter.byteArray2HexString(ksn, ksn.length) : "null";

                result.append("\nEncryption Result:\n");
                result.append("  Encrypted: " + encryptedHex + "\n");
                result.append("  KSN: " + ksnHex + "\n");
                result.append("  Expected: " + expectedResult + "\n");

                if (encryptedHex.equalsIgnoreCase(expectedResult)) {
                    result.append("\n*** TEST PASSED ***\n");
                } else {
                    result.append("\n*** TEST FAILED ***\n");
                    result.append("PIN encryption does not match expected result.\n");
                    result.append("Possible causes:\n");
                    result.append("  1. Wrong IPEK loaded\n");
                    result.append("  2. Using Data Key variant instead of PIN Key variant\n");
                    result.append("  3. KSN counter is not at 1\n");
                }
            } else {
                result.append("Encryption failed - null output\n");
            }

        } catch (CTOS.CtKMS2Exception e) {
            result.append("\nKMS2 Exception: " + String.format("0x%08X\n", e.getError()));
            e.showStatus();
        } catch (Exception e) {
            result.append("\nException: " + e.getMessage() + "\n");
        }

        Log.d(TAG, result.toString());
        return result.toString();
    }

    /**
     * DUKPT Key Variant Diagnostic - Tests both DATA and PIN variants
     * This helps the processor identify which DUKPT key variant Castle uses.
     *
     * Encrypts a known clear block with both variants and reports results.
     * The processor can then compare their derivation for each variant.
     */
    public String testDukptKeyVariants() {
        StringBuilder result = new StringBuilder();
        result.append("=== DUKPT KEY VARIANT TEST ===\n\n");

        String clearBlock = "041230EFFEFFF7CE";  // ISO-0 for PIN 1234 with our test PAN
        result.append("Clear PIN Block: " + clearBlock + "\n");
        result.append("(ISO-0 Format for PIN 1234)\n\n");

        try {
            // Test 1: DATA_ENCRYPT_METHOD_ECB (uses Data Key variant)
            result.append("Test 1: DATA Key Variant (ECB)\n");
            CtKMS2Dukpt dukpt1 = new CtKMS2Dukpt();
            dukpt1.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
            dukpt1.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);
            dukpt1.isUseCurrentKey(false);  // Get new key, increment counter
            byte[] clearBytes = Converter.hexString2ByteArray(clearBlock);
            dukpt1.setInputData(clearBytes, 0, clearBytes.length);
            dukpt1.dataEncrypt();

            byte[] enc1 = dukpt1.getOutpuData();
            byte[] ksn1 = dukpt1.getKSN();
            String enc1Hex = (enc1 != null) ? Converter.byteArray2HexString(enc1, 8) : "null";
            String ksn1Hex = (ksn1 != null) ? Converter.byteArray2HexString(ksn1, ksn1.length) : "null";

            result.append("  KSN:       " + ksn1Hex + "\n");
            result.append("  Encrypted: " + enc1Hex + "\n");
            result.append("  Variant:   XOR 0000000000FF00000000000000FF0000\n\n");

            // Test 2: PIN_CIPHER_METHOD_ECB (uses PIN Key variant)
            result.append("Test 2: PIN Key Variant (ECB)\n");
            CtKMS2Dukpt dukpt2 = new CtKMS2Dukpt();
            dukpt2.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
            dukpt2.setCipherMethod(CtKMS2Dukpt.PIN_CIPHER_METHOD_ECB);
            dukpt2.isUseCurrentKey(false);  // Get new key, increment counter
            dukpt2.setInputData(clearBytes, 0, clearBytes.length);
            dukpt2.dataEncrypt();

            byte[] enc2 = dukpt2.getOutpuData();
            byte[] ksn2 = dukpt2.getKSN();
            String enc2Hex = (enc2 != null) ? Converter.byteArray2HexString(enc2, 8) : "null";
            String ksn2Hex = (ksn2 != null) ? Converter.byteArray2HexString(ksn2, ksn2.length) : "null";

            result.append("  KSN:       " + ksn2Hex + "\n");
            result.append("  Encrypted: " + enc2Hex + "\n");
            result.append("  Variant:   XOR 00000000000000FF00000000000000FF\n\n");

            // Summary for processor
            result.append("=== FOR PROCESSOR ===\n");
            result.append("IPEK: [removed - see key ceremony docs]\n");
            result.append("Clear: " + clearBlock + "\n\n");
            result.append("DATA variant (" + ksn1Hex + "): " + enc1Hex + "\n");
            result.append("PIN variant  (" + ksn2Hex + "): " + enc2Hex + "\n\n");
            result.append("If your decryption of either matches the clear block,\n");
            result.append("that's the variant Castle uses.\n");

        } catch (CTOS.CtKMS2Exception e) {
            result.append("KMS2 Exception: " + String.format("0x%08X\n", e.getError()));
        } catch (Exception e) {
            result.append("Exception: " + e.getMessage() + "\n");
        }

        Log.d(TAG, result.toString());
        return result.toString();
    }

    /**
     * Scans for DUKPT keys at various locations.
     * This is a diagnostic function to find where Key_Bridge/Geobridge actually stored the DUKPT IPEK.
     * Call this from admin menu to diagnose key location issues.
     *
     * @return String report of all found DUKPT keys
     */
    public String scanDukptKeyLocations() {
        Log.d(TAG, "=== DUKPT KEY LOCATION SCAN ===");
        StringBuilder report = new StringBuilder();
        report.append("DUKPT KEY DIAGNOSTIC\n");
        report.append("====================\n\n");

        // First, do a detailed check of the configured DUKPT location
        report.append("Configured DUKPT Location:\n");
        report.append(String.format("  KeySet: 0x%04X, KeyIndex: 0x%04X\n\n",
                GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));

        // Test the configured location in detail
        report.append(testDukptKeyDetailed(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));
        report.append("\n");

        // List of possible DUKPT key locations to check
        // Format: {keySet, keyIndex, description}
        int[][] locationsToCheck = {
            // Geobridge DUKPT slots (most likely)
            {0xC001, 0x001A},  // DUKPT(1A) - CastlesHost default
            {0xC001, 0x001B},  // DUKPT(1B)
            {0xC001, 0x001C},  // DUKPT(1C)
            {0xC001, 0x0001},  // DUKPT(1)
            {0xC001, 0x0002},  // DUKPT(2)
            {0xC001, 0x0003},  // DUKPT(3)

            // Alternative key sets
            {0x0001, 0x001A},  // Alt DUKPT(1A)
            {0x0001, 0x001B},  // Alt DUKPT(1B)
            {0x0001, 0x0001},  // Alt slot 1
            {0x0001, 0x000A},  // Alt slot A

            // Zero-based locations
            {0x0000, 0x001A},
            {0x0000, 0x001B},
            {0x0000, 0x0001},
            {0x0000, 0x0002},
            {0x0000, 0x000A},

            // CFFF/C000 locations (KEK-related)
            {0xC000, 0x0000},  // KEK location
            {0xC000, 0x0001},
            {0xC000, 0x001A},
            {0xCFFF, 0x0000},
            {0xCFFF, 0x001A},

            // Other possibilities
            {0x0010, 0x001A},
            {0x0010, 0x0001},
            {0x00F1, 0x0022},  // Sample code location
            {0xC002, 0x001A},
            {0xC002, 0x00A1},
        };

        int foundCount = 0;

        for (int[] loc : locationsToCheck) {
            int keySet = loc[0];
            int keyIndex = loc[1];
            String locStr = String.format("0x%04X/0x%04X", keySet, keyIndex);

            try {
                CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
                dukpt.selectKey(keySet, keyIndex);

                // If we got here without exception, the DUKPT key exists!
                foundCount++;
                String msg = "FOUND: " + locStr;
                Log.d(TAG, msg);
                report.append("✓ " + msg + "\n");

                // Try to get more info
                try {
                    byte[] ksn = dukpt.getKSN();
                    if (ksn != null && ksn.length > 0) {
                        String ksnStr = Converter.byteArray2HexString(ksn, ksn.length);
                        report.append("  KSN: " + ksnStr + "\n");
                        Log.d(TAG, "  KSN: " + ksnStr);
                    }
                } catch (Exception e) {
                    report.append("  (KSN not available)\n");
                }

            } catch (CTOS.CtKMS2Exception e) {
                int error = e.getError();
                String errStr = String.format("0x%08X", error);
                Log.d(TAG, "  No key at " + locStr + " - Error: " + errStr);
                // Only log interesting errors (not "key not found")
                if (error != 0x00002901 && error != 0x00002000) {
                    report.append("✗ " + locStr + " - Error: " + errStr + "\n");
                }
            } catch (Exception e) {
                Log.d(TAG, "  Error at " + locStr + ": " + e.getMessage());
            }
        }

        report.append("\n");
        if (foundCount == 0) {
            report.append("NO DUKPT KEYS FOUND!\n\n");
            report.append("The terminal has no DUKPT keys at common locations.\n");
            report.append("Please verify:\n");
            report.append("1. Key_Bridge app was used (not Key_Injection)\n");
            report.append("2. Geobridge SW mode was selected\n");
            report.append("3. DUKPT(1A) slot was chosen\n");
            report.append("4. TR31 key block was valid\n");
        } else {
            report.append("Found " + foundCount + " DUKPT key(s)\n");
        }

        report.append("\n=== END SCAN ===\n");
        String result = report.toString();
        Log.d(TAG, result);
        return result;
    }

    /**
     * Tests a specific DUKPT key location in detail.
     * Checks: selectKey, getKSN, and attempts data encryption to verify usability.
     *
     * @param keySet The key set
     * @param keyIndex The key index
     * @return Detailed status report
     */
    private String testDukptKeyDetailed(int keySet, int keyIndex) {
        StringBuilder sb = new StringBuilder();
        String locStr = String.format("0x%04X/0x%04X", keySet, keyIndex);
        sb.append("Testing DUKPT key at " + locStr + ":\n");

        try {
            CtKMS2Dukpt dukpt = new CtKMS2Dukpt();

            // Step 1: Try selectKey
            sb.append("  1. selectKey(): ");
            try {
                dukpt.selectKey(keySet, keyIndex);
                sb.append("SUCCESS\n");
            } catch (CTOS.CtKMS2Exception e) {
                sb.append("FAILED - " + String.format("0x%08X", e.getError()) + "\n");
                sb.append("  >> Key does not exist or invalid location\n");
                return sb.toString();
            }

            // Step 2: Try to get KSN
            sb.append("  2. getKSN(): ");
            try {
                byte[] ksn = dukpt.getKSN();
                if (ksn != null && ksn.length > 0) {
                    String ksnStr = Converter.byteArray2HexString(ksn, ksn.length);
                    sb.append(ksnStr + "\n");

                    // Check if KSN looks valid (should be 10 bytes / 20 hex chars)
                    if (ksn.length == 10) {
                        sb.append("  >> KSN length OK (10 bytes)\n");
                    } else {
                        sb.append("  >> WARNING: KSN length is " + ksn.length + " (expected 10)\n");
                    }

                    // Check if KSN is all zeros (would indicate uninitialized)
                    boolean allZeros = true;
                    for (byte b : ksn) {
                        if (b != 0) {
                            allZeros = false;
                            break;
                        }
                    }
                    if (allZeros) {
                        sb.append("  >> WARNING: KSN is all zeros (not initialized?)\n");
                    }
                } else {
                    sb.append("EMPTY/NULL\n");
                    sb.append("  >> WARNING: No KSN data - DUKPT may not be initialized\n");
                }
            } catch (Exception e) {
                sb.append("FAILED - " + e.getMessage() + "\n");
            }

            // Step 3: Try data encryption (simpler than PIN - just encrypt 8 bytes)
            sb.append("  3. dataEncrypt(): ");
            try {
                dukpt.setCipherMethod((byte) 0x00);  // ECB mode
                byte[] testData = new byte[8]; // 8 zeros
                dukpt.setInputData(testData, 0, 8);
                dukpt.dataEncrypt();
                byte[] encrypted = dukpt.getOutpuData();
                if (encrypted != null && encrypted.length >= 8) {
                    sb.append("SUCCESS\n");
                    sb.append("  >> Encrypted output: " + Converter.byteArray2HexString(encrypted, Math.min(8, encrypted.length)) + "\n");
                    sb.append("  >> DUKPT key is USABLE for data encryption\n");

                    // Check KSN AFTER encryption - maybe it's only available after an operation
                    sb.append("  3a. getKSN() AFTER encrypt: ");
                    try {
                        byte[] ksnAfter = dukpt.getKSN();
                        if (ksnAfter != null && ksnAfter.length > 0) {
                            sb.append(Converter.byteArray2HexString(ksnAfter, ksnAfter.length) + "\n");
                        } else {
                            sb.append("EMPTY/NULL\n");
                        }
                    } catch (Exception ex) {
                        sb.append("ERROR: " + ex.getMessage() + "\n");
                    }

                    // Try getNextKSN as well
                    sb.append("  3b. getNextKSN(): ");
                    try {
                        byte[] nextKsn = dukpt.getNextKSN();
                        if (nextKsn != null && nextKsn.length > 0) {
                            sb.append(Converter.byteArray2HexString(nextKsn, nextKsn.length) + "\n");
                        } else {
                            sb.append("EMPTY/NULL\n");
                        }
                    } catch (Exception ex) {
                        sb.append("ERROR: " + ex.getMessage() + "\n");
                    }
                } else {
                    sb.append("FAILED - no output\n");
                }
            } catch (CTOS.CtKMS2Exception e) {
                int error = e.getError();
                sb.append("FAILED - " + String.format("0x%08X", error) + "\n");
                if (error == 0x00002901) {
                    sb.append("  >> Error 0x00002901: DUKPT key not properly initialized\n");
                    sb.append("  >> The key exists but DUKPT counter/state is invalid\n");
                    sb.append("  >> Re-injection of IPEK may be required\n");
                } else if (error == 0x00002000) {
                    sb.append("  >> Error 0x00002000: Key not found\n");
                } else {
                    sb.append("  >> Unknown error during encryption\n");
                }
            }

            // Step 4: Check regular key API for more info
            sb.append("  4. Regular key check: ");
            try {
                CTOS.CtKMS2Key key = new CTOS.CtKMS2Key();
                key.selectKey(keySet, keyIndex);
                sb.append(String.format("Type=0x%02X, Attr=0x%08X, Len=%d\n",
                        key.getKeyType(), key.getKeyAttribute(), key.getKeyLength()));
            } catch (CTOS.CtKMS2Exception e) {
                sb.append("N/A - " + String.format("0x%08X", e.getError()) + "\n");
            }

        } catch (Exception e) {
            sb.append("  ERROR: " + e.getMessage() + "\n");
        }

        return sb.toString();
    }

    /**
     * Show PIN pad dialog with software button listeners.
     * Buttons directly collect PIN digits instead of using VirtualPINPad.
     */
    private void showSoftwarePinPadDialog() {
        Log.d(TAG, "showSoftwarePinPadDialog()");

        atmPinDialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        atmPinDialog.setContentView(R.layout.layout_atm_pinpad);
        atmPinDialog.setCancelable(false);

        // Get references to buttons and display
        final TextView txtDigits = atmPinDialog.findViewById(R.id.txtPinDigits);

        // Digit buttons 0-9
        int[] digitButtonIds = {
            R.id.KBD0, R.id.KBD1, R.id.KBD2, R.id.KBD3, R.id.KBD4,
            R.id.KBD5, R.id.KBD6, R.id.KBD7, R.id.KBD8, R.id.KBD9
        };

        for (int i = 0; i < 10; i++) {
            final int digit = i;
            Button btn = atmPinDialog.findViewById(digitButtonIds[i]);
            if (btn != null) {
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (atmCollectedPin.length() < 12) {
                            atmCollectedPin.append(digit);
                            Log.d(TAG, "ATM PIN: Digit entered, count: " + atmCollectedPin.length());
                            updatePinDisplay(txtDigits);
                            // Play feedback
                            if (GlobalPara.audio != null) {
                                GlobalPara.audio.playOKSound();
                            }
                        }
                    }
                });
            }
        }

        // Enter button
        Button btnEnter = atmPinDialog.findViewById(R.id.KBD_Enter);
        if (btnEnter != null) {
            btnEnter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "ATM PIN: Enter pressed");
                    if (atmCollectedPin.length() >= 4) {
                        atmPinEntryComplete = true;
                        if (GlobalPara.audio != null) {
                            GlobalPara.audio.playOKSound();
                        }
                    } else {
                        ui_ShowMsg("Enter at least 4 digits");
                    }
                }
            });
        }

        // Clear button
        Button btnClear = atmPinDialog.findViewById(R.id.KBD_Clear);
        if (btnClear != null) {
            btnClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "ATM PIN: Clear pressed");
                    atmCollectedPin.setLength(0);
                    updatePinDisplay(txtDigits);
                }
            });
        }

        // Cancel button
        Button btnCancel = atmPinDialog.findViewById(R.id.KBD_Cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "ATM PIN: Cancel pressed");
                    atmPinEntryCancelled = true;
                    if (GlobalPara.audio != null) {
                        GlobalPara.audio.playCancelKeySound();
                    }
                }
            });
        }

        atmPinDialog.show();

        // Signal ready after layout
        atmPinDialog.getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "ATM PIN: Software PIN pad dialog laid out");
                atmPinPadReady = true;
            }
        });
    }

    /**
     * Update the PIN display with asterisks
     */
    private void updatePinDisplay(final TextView txtDigits) {
        if (txtDigits != null) {
            StringBuilder stars = new StringBuilder();
            for (int i = 0; i < atmCollectedPin.length(); i++) {
                stars.append("*");
            }
            txtDigits.setText(stars.toString());
        }
    }

    /**
     * Show the ATM PIN pad dialog
     */
    private void showAtmPinPadDialog() {
        Log.d(TAG, "showAtmPinPadDialog()");

        atmPinDialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        atmPinDialog.setContentView(R.layout.layout_atm_pinpad);
        atmPinDialog.setCancelable(false);
        atmPinDialog.show();

        // Wait for layout to complete then signal ready
        atmPinDialog.getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "ATM PIN pad dialog laid out");
                atmPinPadReady = true;
            }
        });
    }

    /**
     * Dismiss the ATM PIN pad dialog
     */
    private void dismissAtmPinPadDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (atmPinDialog != null && atmPinDialog.isShowing()) {
                    atmPinDialog.dismiss();
                    atmPinDialog = null;
                }
            }
        });
    }

    /**
     * Get button positions from the PIN pad dialog and populate VirtualPINPad
     */
    private boolean getAtmPinPadPositions(CtKMS2VirtualPINPad VirtualPINPad) {
        if (atmPinDialog == null) return false;

        int[] KBDIds = {
            R.id.KBD0, R.id.KBD1, R.id.KBD2, R.id.KBD3, R.id.KBD4,
            R.id.KBD5, R.id.KBD6, R.id.KBD7, R.id.KBD8, R.id.KBD9,
            R.id.KBD_Enter, R.id.KBD_Clear, R.id.KBD_Cancel
        };

        int[][] KBDAttribute = new int[13][4];  // [x, y, width, height]
        int[] location = new int[2];

        for (int i = 0; i < 13; i++) {
            Button btn = atmPinDialog.findViewById(KBDIds[i]);
            if (btn != null) {
                btn.getLocationOnScreen(location);
                KBDAttribute[i][0] = location[0];
                KBDAttribute[i][1] = location[1];
                KBDAttribute[i][2] = btn.getWidth();
                KBDAttribute[i][3] = btn.getHeight();
                Log.d(TAG, "Button " + i + ": x=" + location[0] + ", y=" + location[1] +
                          ", w=" + btn.getWidth() + ", h=" + btn.getHeight());
            } else {
                Log.e(TAG, "Button " + i + " not found!");
                return false;
            }
        }

        // Populate VirtualPINPad
        VirtualPINPad.VKBD_0.x = KBDAttribute[0][0];
        VirtualPINPad.VKBD_0.y = KBDAttribute[0][1];
        VirtualPINPad.VKBD_0.width = KBDAttribute[0][2];
        VirtualPINPad.VKBD_0.height = KBDAttribute[0][3];
        VirtualPINPad.VKBD_0.value = (byte) '0';

        VirtualPINPad.VKBD_1.x = KBDAttribute[1][0];
        VirtualPINPad.VKBD_1.y = KBDAttribute[1][1];
        VirtualPINPad.VKBD_1.width = KBDAttribute[1][2];
        VirtualPINPad.VKBD_1.height = KBDAttribute[1][3];
        VirtualPINPad.VKBD_1.value = (byte) '1';

        VirtualPINPad.VKBD_2.x = KBDAttribute[2][0];
        VirtualPINPad.VKBD_2.y = KBDAttribute[2][1];
        VirtualPINPad.VKBD_2.width = KBDAttribute[2][2];
        VirtualPINPad.VKBD_2.height = KBDAttribute[2][3];
        VirtualPINPad.VKBD_2.value = (byte) '2';

        VirtualPINPad.VKBD_3.x = KBDAttribute[3][0];
        VirtualPINPad.VKBD_3.y = KBDAttribute[3][1];
        VirtualPINPad.VKBD_3.width = KBDAttribute[3][2];
        VirtualPINPad.VKBD_3.height = KBDAttribute[3][3];
        VirtualPINPad.VKBD_3.value = (byte) '3';

        VirtualPINPad.VKBD_4.x = KBDAttribute[4][0];
        VirtualPINPad.VKBD_4.y = KBDAttribute[4][1];
        VirtualPINPad.VKBD_4.width = KBDAttribute[4][2];
        VirtualPINPad.VKBD_4.height = KBDAttribute[4][3];
        VirtualPINPad.VKBD_4.value = (byte) '4';

        VirtualPINPad.VKBD_5.x = KBDAttribute[5][0];
        VirtualPINPad.VKBD_5.y = KBDAttribute[5][1];
        VirtualPINPad.VKBD_5.width = KBDAttribute[5][2];
        VirtualPINPad.VKBD_5.height = KBDAttribute[5][3];
        VirtualPINPad.VKBD_5.value = (byte) '5';

        VirtualPINPad.VKBD_6.x = KBDAttribute[6][0];
        VirtualPINPad.VKBD_6.y = KBDAttribute[6][1];
        VirtualPINPad.VKBD_6.width = KBDAttribute[6][2];
        VirtualPINPad.VKBD_6.height = KBDAttribute[6][3];
        VirtualPINPad.VKBD_6.value = (byte) '6';

        VirtualPINPad.VKBD_7.x = KBDAttribute[7][0];
        VirtualPINPad.VKBD_7.y = KBDAttribute[7][1];
        VirtualPINPad.VKBD_7.width = KBDAttribute[7][2];
        VirtualPINPad.VKBD_7.height = KBDAttribute[7][3];
        VirtualPINPad.VKBD_7.value = (byte) '7';

        VirtualPINPad.VKBD_8.x = KBDAttribute[8][0];
        VirtualPINPad.VKBD_8.y = KBDAttribute[8][1];
        VirtualPINPad.VKBD_8.width = KBDAttribute[8][2];
        VirtualPINPad.VKBD_8.height = KBDAttribute[8][3];
        VirtualPINPad.VKBD_8.value = (byte) '8';

        VirtualPINPad.VKBD_9.x = KBDAttribute[9][0];
        VirtualPINPad.VKBD_9.y = KBDAttribute[9][1];
        VirtualPINPad.VKBD_9.width = KBDAttribute[9][2];
        VirtualPINPad.VKBD_9.height = KBDAttribute[9][3];
        VirtualPINPad.VKBD_9.value = (byte) '9';

        VirtualPINPad.VKBD_10.x = KBDAttribute[10][0];  // Enter
        VirtualPINPad.VKBD_10.y = KBDAttribute[10][1];
        VirtualPINPad.VKBD_10.width = KBDAttribute[10][2];
        VirtualPINPad.VKBD_10.height = KBDAttribute[10][3];
        VirtualPINPad.VKBD_10.value = (byte) 'A';

        VirtualPINPad.VKBD_11.x = KBDAttribute[11][0];  // Clear
        VirtualPINPad.VKBD_11.y = KBDAttribute[11][1];
        VirtualPINPad.VKBD_11.width = KBDAttribute[11][2];
        VirtualPINPad.VKBD_11.height = KBDAttribute[11][3];
        VirtualPINPad.VKBD_11.value = (byte) 'R';

        VirtualPINPad.VKBD_12.x = KBDAttribute[12][0];  // Cancel
        VirtualPINPad.VKBD_12.y = KBDAttribute[12][1];
        VirtualPINPad.VKBD_12.width = KBDAttribute[12][2];
        VirtualPINPad.VKBD_12.height = KBDAttribute[12][3];
        VirtualPINPad.VKBD_12.value = (byte) 'C';

        // Unused keys
        VirtualPINPad.VKBD_13.x = 0;
        VirtualPINPad.VKBD_13.y = 0;
        VirtualPINPad.VKBD_13.value = (byte) 0x53;
        VirtualPINPad.VKBD_14.x = 0;
        VirtualPINPad.VKBD_14.y = 0;
        VirtualPINPad.VKBD_14.value = (byte) 0x53;
        VirtualPINPad.VKBD_15.x = 0;
        VirtualPINPad.VKBD_15.y = 0;
        VirtualPINPad.VKBD_15.value = (byte) 0x53;

        return true;
    }

    public int btnManualEntry_Click(final View view) {
        Log.d(TAG, APP_TITLE + "  Ver." + APP_VERSION);
        Log.d(TAG, "btnManualEntry_Click() ***");

        threadME = new Thread(new Runnable() {
            @Override
            public void run() {

                short shRtn = (short) 0xFFFF;
                int intRtn = 0xFFFFFFFF;


                ui_DisableTxnButton();
                ui_ClearMsg();
                load_json(); //added

                myManualEntryEvent.setUIComponent(mViewPager);

                Log.d(TAG, "manual entry test start =========================================>");


                if (GlobalPara.MEInitOK == false) {
                    ui_ShowMsg("Manual Entry Initialize...\n");

                    Log.d(TAG, "initialize ***********************************************");
                    intRtn = mentry.initialize();
                    ui_ShowLog("initialize Rtn: " + String.format("0x%08X", intRtn) + "\n");
                    if (intRtn != 0) {

                    } else {
                        Log.d(TAG, "setTracksEncryptInfo***********************************************");
                        EMVSecureDataInfo meSecureInfo = new EMVSecureDataInfo();
                        //meSecureInefo.version = 1;
						/*
						meSecureInfo.keyType = (byte)1;
						meSecureInfo.cipherKeySet =  0xC002;
						meSecureInfo.cipherKeyIndex = 0x0000;

						meSecureInfo.cipherMethod = 0x01;
						meSecureInfo.checksumType = 0;
						meSecureInfo.ICVLen = 8;
						meSecureInfo.ICV = new byte[8];
						meSecureInfo.paddingMethod = 0;
						meSecureInfo.LRCIncluded = 1;
						meSecureInfo.SS_ESIncluded = 1;
						*/
                        // FIXED: Changed from version=4 to version=2 to match original sample
                        meSecureInfo.version = 2;
                        meSecureInfo.keyType = (byte) 4;
                        meSecureInfo.cipherKeySet = GlobalPara.onlinePinKeySet;
                        meSecureInfo.cipherKeyIndex = GlobalPara.onlinePinKeyIndex;


                        meSecureInfo.cipherMethod = 0x01;
                        meSecureInfo.checksumType = 0;
                        meSecureInfo.ICVLen = 8;
                        meSecureInfo.ICV = new byte[8];
                        meSecureInfo.paddingMethod = 0;
                        meSecureInfo.LRCIncluded = 1;
                        meSecureInfo.SS_ESIncluded = 1;
                        meSecureInfo.isKSNFixed = 1;
                        //meSecureInfo.msrEncFlow = 1;

                        intRtn = mentry.setEncryptInfo(meSecureInfo);
                        ui_ShowLog("setEncryptInfo Rtn: " + String.format("0x%08X", intRtn) + "\n");

                        Log.d(TAG, "setMaskChar***********************************************");
                        mentry.setMaskChar((byte) '*');

                        //mentry.maskPANDigits1To6((byte)0x3C);

                        Log.d(TAG, "registerEvent***********************************************");
                        intRtn = mentry.registerEvent(myManualEntryEvent);
                        ui_ShowLog("registerEvent Rtn: " + String.format("0x%08X", intRtn) + "\n");

                        GlobalPara.MEInitOK = true;
                    }
                }

                if (GlobalPara.MEInitOK == true) {
                    GlobalPara.layoutViewCreate = 0;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mViewPager.setCurrentItem(GlobalDef.d_PAGE_MANUAL_ENTRY);
                            GlobalPara.layoutViewCreate = 1;

                            TextView textView;

                            textView = (TextView) findViewById(R.id.textViewME_PAN);
                            textView.setVisibility(View.VISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_EXP);
                            textView.setVisibility(View.VISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_CSC);
                            textView.setVisibility(View.VISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_ZIP);
                            textView.setVisibility(View.VISIBLE);

                        }
                    });

                    do {
                        MyUtility.sleep(1500);
                    } while (GlobalPara.layoutViewCreate == 0);

                    //private static final byte VKBD_0 = '0';    	// 0x30;
                    //private static final byte VKBD_1 = '1';    	// 0x31;
                    //private static final byte VKBD_2 = '2';    	// 0x32;
                    //private static final byte VKBD_3 = '3';    	// 0x33;
                    //private static final byte VKBD_4 = '4';    	// 0x34;
                    //private static final byte VKBD_5 = '5';    	// 0x35;
                    //private static final byte VKBD_6 = '6';    	// 0x36;
                    //private static final byte VKBD_7 = '7';    	// 0x37;
                    //private static final byte VKBD_8 = '8';    	// 0x38;
                    //private static final byte VKBD_9 = '9';    	// 0x39;
                    //private static final byte VKBD_ENTER = 'A';   // 0x41;
                    //private static final byte VKBD_CLEAR = 'R';   // 0x52;
                    //private static final byte VKBD_CANCEL = 'C';  // 0x43;
                    //private static final byte VKBD_SPACE = 'S';   // 0x53;

                    //keyboard attribute is a fixed int[16][5] buffer !!
                    int[][] KBDAttribute = new int[16][5];
                    TextView[] tv = new TextView[16];
                    int[] XY = new int[2];
                    int x;
                    int y;
                    int w;
                    int h;


                    for (int i = 0; i < 16; i++) {
                        switch (i) {
                            //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                            case 0:
                                tv[i] = (TextView) findViewById(R.id.KBD0);
                                KBDAttribute[i][4] = '0';
                                break;
                            case 1:
                                tv[i] = (TextView) findViewById(R.id.KBD1);
                                KBDAttribute[i][4] = '1';
                                break;
                            case 2:
                                tv[i] = (TextView) findViewById(R.id.KBD2);
                                KBDAttribute[i][4] = '2';
                                break;
                            case 3:
                                tv[i] = (TextView) findViewById(R.id.KBD3);
                                KBDAttribute[i][4] = '3';
                                break;
                            case 4:
                                tv[i] = (TextView) findViewById(R.id.KBD4);
                                KBDAttribute[i][4] = '4';
                                break;
                            case 5:
                                tv[i] = (TextView) findViewById(R.id.KBD5);
                                KBDAttribute[i][4] = '5';
                                break;
                            case 6:
                                tv[i] = (TextView) findViewById(R.id.KBD6);
                                KBDAttribute[i][4] = '6';
                                break;
                            case 7:
                                tv[i] = (TextView) findViewById(R.id.KBD7);
                                KBDAttribute[i][4] = '7';
                                break;
                            case 8:
                                tv[i] = (TextView) findViewById(R.id.KBD8);
                                KBDAttribute[i][4] = '8';
                                break;
                            case 9:
                                tv[i] = (TextView) findViewById(R.id.KBD9);
                                KBDAttribute[i][4] = '9';
                                break;
                            case 10:
                                tv[i] = (TextView) findViewById(R.id.KBD_Cancel);
                                KBDAttribute[i][4] = 'C';
                                break;
                            case 11:
                                tv[i] = (TextView) findViewById(R.id.KBD_Clear);
                                KBDAttribute[i][4] = 'R';
                                break;
                            case 12:
                                tv[i] = (TextView) findViewById(R.id.KBD_Enter);
                                TextView txtViewEnter = (TextView) findViewById(R.id.KBD_Enter);
                                KBDAttribute[i][4] = 'A';
                                break;

                            //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                            default:
                                KBDAttribute[i][4] = 'S';
                                break;
                        }

                        if (i > 12) {
                            x = 10;
                            y = 10;
                            w = 1;
                            h = 1;
                        } else {
                            tv[i].getLocationOnScreen(XY);
                            x = XY[0];
                            y = XY[1];
                            w = tv[i].getWidth();
                            h = tv[i].getHeight();
                        }


                        KBDAttribute[i][0] = x;
                        KBDAttribute[i][1] = y;
                        KBDAttribute[i][2] = x + w;
                        KBDAttribute[i][3] = y + h;

                        Log.d("KeyValue = ", String.valueOf(KBDAttribute[i][4]));
                        Log.d("location x = ", String.valueOf(x));
                        Log.d("location y = ", String.valueOf(y));
                        Log.d("location x + width = ", String.valueOf(KBDAttribute[i][2]));
                        Log.d("location y + height = ", String.valueOf(KBDAttribute[i][3]));

                    }

                    Log.d(TAG, "flushBuffer***********************************************");
                    intRtn = mentry.flushBuffer();
                    Log.d(TAG, "flushBuffer Rtn: " + String.format("0x%08X", intRtn) + "\n");

                    final TextView textView;
                    textView = (TextView) findViewById(R.id.textViewPinDigit);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView.setText("Please Enter PAN");
                        }
                    });
                    myManualEntryEvent.clearMaskedPANStr();
                    /*
                    public final int setKeyInLenRange(byte type, byte minLen, byte maxLen);

                     type: specific the key in type
                     0x00 - KEY_IN_TYPE_PAN
                     0x01 - KEY_IN_TYPE_EXP
                     0x02 - KEY_IN_TYPE_CSC
                     0x03 - RFU
                     0x04 - KEY_IN_TYPE_ADR
                     0x05 - KEY_IN_TYPE_ZIP
                     */




                    intRtn = mentry.keyinPAN(KBDAttribute);
                    Log.d(TAG, "keyinPAN Rtn: " + String.format("0x%08X", intRtn) + "\n");


                    final EMVEDLBinInfo matchdata = new EMVEDLBinInfo();
                    if(edl == null){
                        Log.d("JORGE", "eld is null\n");
                    }
                    else{
                        Log.d("JORGE", "eld is NOT null\n");
                    }
                    edl.getWhiteListMatchedBinInfo(matchdata);

                    Log.d("JORGE","ISMATCH " + matchdata.isMatch + "\n");
                    Log.d("JORGE", "PROMPT EXP " + matchdata.expdatePrompt + "\n");

                    Log.e(TAG, "~~**************************************************************\n");
                    Log.d(TAG, "~~isMatch: " + matchdata.isMatch + "\n");
                    Log.d(TAG, "~~binLen: " + matchdata.binLen + "\n");
                    Log.d(TAG, "~~binStart: " + Converter.asciiBytesToString(matchdata.binStart) + "\n");
                    Log.d(TAG, "~~binEnd: " + Converter.asciiBytesToString(matchdata.binEnd) + "\n");
                    Log.d(TAG, "~~brandLen: " + matchdata.brandLen + "\n");
                    Log.d(TAG, "~~brand: " + Converter.asciiBytesToString(matchdata.brand) + "\n");
                    Log.d(TAG, "~~typeLen: " + matchdata.typeLen + "\n");
                    Log.d(TAG, "~~type: " + Converter.asciiBytesToString(matchdata.type) + "\n");
                    Log.d(TAG, "~~gotCipher: " + matchdata.gotCipher + "\n");
                    Log.d(TAG, "~~isCipher: " + matchdata.isCipher + "\n");
                    Log.d(TAG, "~~gotPanLen: " + matchdata.gotPanLen + "\n");
                    Log.d(TAG, "~~panLenMin: " + matchdata.panLenMin + "\n");
                    Log.d(TAG, "~~panLenMax: " + matchdata.panLenMax + "\n");
                    Log.d(TAG, "~~invalidPanLen: " + matchdata.invalidPanLen + "\n");
                    Log.d(TAG, "~~gotMaskDigit: " + matchdata.gotMaskDigit + "\n");
                    Log.d(TAG, "~~maskDigit1_BeginOfPan: " + matchdata.maskDigit1_BeginOfPan + "\n");
                    Log.d(TAG, "~~maskDigit2_EndOfPan: " + matchdata.maskDigit2_EndOfPan + "\n");
                    Log.d(TAG, "~~maskDigit2_AfterDelimiter: " + matchdata.maskDigit2_AfterDelimiter + "\n");
                    Log.e(TAG, "~~**************************************************************\n");
                    Log.d(TAG, "~~gotExpdate: " + matchdata.gotExpdate + "\n");
                    Log.d(TAG, "~~expdatePrompt: " + matchdata.expdatePrompt + "\n");
                    Log.d(TAG, "~~expdateLenMin: " + matchdata.expdateLenMin + "\n");
                    Log.d(TAG, "~~expdateLenMax: " + matchdata.expdateLenMax + "\n");
                    Log.d(TAG, "~~expdateFormatLen: " + matchdata.expdateFormatLen + "\n");
                    Log.d(TAG, "~~expdateFormat: " + Converter.asciiBytesToString(matchdata.expdateFormat) + "\n");
                    Log.d(TAG, "~~expdateLabelLen: " + matchdata.expdateLabelLen + "\n");
                    Log.d(TAG, "~~expdateLabel: " + Converter.asciiBytesToString(matchdata.expdateLabel) + "\n");
                    Log.e(TAG, "~~**************************************************************\n");
                    Log.d(TAG, "~~gotCVV: " + matchdata.gotCVV + "\n");
                    Log.d(TAG, "~~cvvPrompt: " + matchdata.cvvPrompt + "\n");
                    Log.d(TAG, "~~cvvLenMin: " + matchdata.cvvLenMin + "\n");
                    Log.d(TAG, "~~cvvLenMax: " + matchdata.cvvLenMax + "\n");
                    Log.d(TAG, "~~cvvFormatLen: " + matchdata.cvvFormatLen + "\n");
                    Log.d(TAG, "~~cvvFormat: " + Converter.asciiBytesToString(matchdata.cvvFormat) + "\n");
                    Log.d(TAG, "~~cvvLabelLen: " + matchdata.cvvLabelLen + "\n");
                    Log.d(TAG, "~~cvvLabel: " + Converter.asciiBytesToString(matchdata.cvvLabel) + "\n");
                    Log.e(TAG, "~~**************************************************************\n");
                    Log.d(TAG, "~~gotPostcode: " + matchdata.gotPostcode + "\n");
                    Log.d(TAG, "~~postcodePrompt: " + matchdata.postcodePrompt + "\n");
                    Log.d(TAG, "~~postcodeLenMin: " + matchdata.postcodeLenMin + "\n");
                    Log.d(TAG, "~~postcodeLenMax: " + matchdata.postcodeLenMax + "\n");
                    Log.d(TAG, "~~postcodeFormatLen: " + matchdata.postcodeFormatLen + "\n");
                    Log.d(TAG, "~~postcodeFormat: " + Converter.asciiBytesToString(matchdata.postcodeFormat) + "\n");
                    Log.d(TAG, "~~postcodeLabelLen: " + matchdata.postcodeLabelLen + "\n");
                    Log.d(TAG, "~~postcodeLabel: " + Converter.asciiBytesToString(matchdata.postcodeLabel) + "\n");
                    Log.e(TAG, "~~**************************************************************\n");
                    Log.d(TAG, "~~gotAddr: " + matchdata.gotAddr + "\n");
                    Log.d(TAG, "~~addrPrompt: " + matchdata.addrPrompt + "\n");
                    Log.d(TAG, "~~addrLenMin: " + matchdata.addrLenMin + "\n");
                    Log.d(TAG, "~~addrLenMax: " + matchdata.addrLenMax + "\n");
                    Log.d(TAG, "~~addrFormatLen: " + matchdata.addrFormatLen + "\n");
                    Log.d(TAG, "~~addrFormat: " + Converter.asciiBytesToString(matchdata.addrFormat) + "\n");
                    Log.d(TAG, "~~addrLabelLen: " + matchdata.addrLabelLen + "\n");
                    Log.d(TAG, "~~addrLabel: " + Converter.asciiBytesToString(matchdata.addrLabel) + "\n");
                    Log.e(TAG, "~~**************************************************************\n");

                    if (matchdata.isMatch == true) {
                        if (matchdata.brandLen > 0) {
                            Log.d(TAG, "Brand: " + Converter.asciiBytesToString(matchdata.brand) + "\n");
                        }

                        if (matchdata.typeLen > 0) {
                            Log.d(TAG, "Type: " + Converter.asciiBytesToString(matchdata.type) + "\n");
                        }

                        if (matchdata.expdatePrompt == true) {
                            GlobalPara.expdateFormat = Converter.asciiBytesToString(matchdata.expdateFormat);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textView.setText("Please Enter Expiry Data (" + GlobalPara.expdateFormat + ")");
                                }
                            });
                            myManualEntryEvent.clearMaskedEXPStr();
                            intRtn = mentry.keyinEXP(KBDAttribute);
                            Log.d(TAG, "keyinEXP Rtn: " + String.format("0x%08X", intRtn) + "\n");
                        }

                        if (matchdata.cvvPrompt == true) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textView.setText("Please Enter Card Security Code");
                                }
                            });
                            myManualEntryEvent.clearMaskedCSCStr();
                            intRtn = mentry.keyinCSC(KBDAttribute);
                            Log.d(TAG, "keyinCSC Rtn: " + String.format("0x%08X", intRtn) + "\n");
                        }

                        if (matchdata.postcodePrompt == true) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textView.setText("Please Enter ZIP Code");
                                }
                            });
                            myManualEntryEvent.clearMaskedZIPStr();
                            intRtn = mentry.keyinZIP(KBDAttribute);
                            Log.d(TAG, "keyinZIP Rtn: " + String.format("0x%08X", intRtn) + "\n");
                        }

                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setText("Please Enter Expiry Data");
                            }
                        });
                        myManualEntryEvent.clearMaskedEXPStr();
                        intRtn = mentry.keyinEXP(KBDAttribute);
                        Log.d(TAG, "keyinEXP Rtn: " + String.format("0x%08X", intRtn) + "\n");

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setText("Please Enter Card Security Code");
                            }
                        });
                        myManualEntryEvent.clearMaskedCSCStr();
                        intRtn = mentry.keyinCSC(KBDAttribute);
                        Log.d(TAG, "keyinCSC Rtn: " + String.format("0x%08X", intRtn) + "\n");

                        //add
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setText("Please Enter ZIP Code");
                            }
                        });
                        myManualEntryEvent.clearMaskedZIPStr();
                        intRtn = mentry.keyinZIP(KBDAttribute);
                        Log.d(TAG, "keyinZIP Rtn: " + String.format("0x%08X", intRtn) + "\n");
                    }

                    intRtn = mentry.keyinDone();
                    Log.d(TAG, "keyinDone Rtn: " + String.format("0x%08X", intRtn) + "\n");

                    GlobalPara.layoutViewCreate = 0;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView textView;
                            textView = (TextView) findViewById(R.id.textViewME_PAN);
                            textView.setVisibility(View.INVISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_EXP);
                            textView.setVisibility(View.INVISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_CSC);
                            textView.setVisibility(View.INVISIBLE);
                            textView = (TextView) findViewById(R.id.textViewME_ZIP);
                            textView.setVisibility(View.INVISIBLE);

                            mViewPager.setCurrentItem(GlobalDef.d_PAGE_TRANSACTION);
                            GlobalPara.layoutViewCreate = 1;

                            Button button;
                            button = (Button) findViewById(R.id.btnTransaction);
                            button.setEnabled(false);
                            button = (Button) findViewById(R.id.btnGetOnlinePin);
                            button.setEnabled(false);
                            button = (Button) findViewById(R.id.btnManualEntry);
                            button.setEnabled(false);
                            button = (Button) findViewById(R.id.btnClearMsg);
                            button.setEnabled(false);
                            button = (Button) findViewById(R.id.btnEncryp);
                            button.setEnabled(false);
                            button = (Button) findViewById(R.id.btnSetting);
                            button.setEnabled(false);
                        }
                    });

                    do {
                        MyUtility.sleep(1500);
                    } while (GlobalPara.layoutViewCreate == 0);


                    byte[] temp;
                    Log.d(TAG, "getMaskedPAN***********************************************");
                    temp = mentry.getMaskedPAN();
                    if (temp != null) {
                        byte[] maskedPAN = new byte[temp.length + 1];
                        System.arraycopy(temp, 0, maskedPAN, 0, temp.length);
                        Log.d(TAG, "Masked PAN : " + new String(maskedPAN));
                        ui_ShowLog("Masked PAN : " + new String(maskedPAN) + "\n");
                    }

                    Log.d(TAG, "getManualEntryLen***********************************************");
                    int len = mentry.getManualEntryLen();
                    Log.d(TAG, "Entry Len : " + String.valueOf(len));
                    ui_ShowLog("Entry Len : " + String.valueOf(len) + "\n");


                    Log.d(TAG, "getMaskedManualEntry***********************************************");
                    temp = mentry.getMaskedManualEntry();
                    if (temp != null) {
                        byte[] maskedEntry = new byte[temp.length + 1];
                        System.arraycopy(temp, 0, maskedEntry, 0, temp.length);
                        Log.d(TAG, "Masked Entry : " + new String(maskedEntry));
                        ui_ShowLog("Masked Entry : " + new String(maskedEntry) + "\n");
                    }

                    Log.d(TAG, "getAdditionalEntry***********************************************");
                    temp = mentry.getAdditionalEntry();
                    if (temp != null) {
                        byte[] AdditionalEntry = new byte[temp.length + 1];
                        System.arraycopy(temp, 0, AdditionalEntry, 0, temp.length);
                        Log.d(TAG, "Additional Entry : " + new String(AdditionalEntry));
                        ui_ShowLog("Additional Entry : " + new String(AdditionalEntry) + "\n");
                    } else {
                        Log.d(TAG, "Additional Entry : null");
                        ui_ShowLog("Additional Entry : null\n");
                    }

                    Log.d(TAG, "getEncryptedManualEntry***********************************************");
                    EMVManualEntryEncryptedData encData = new EMVManualEntryEncryptedData();
                    intRtn = mentry.getEncryptedManualEntry(encData);
                    Log.d(TAG, "getEncryptedManualEntry Rtn: " + String.format("0x%08X", intRtn) + "\n");
                    ui_ShowLog("getEncryptedManualEntry Rtn: " + String.format("0x%08X", intRtn) + "\n");
                    if (intRtn == 0) {
                        Log.d(TAG, "Encrypted Entry : " + Converter.byteArray2HexString(encData.encryptedData, encData.encryptedDataLen));
                        ui_ShowLog("Encrypted Entry : " + Converter.byteArray2HexString(encData.encryptedData, encData.encryptedDataLen) + "\n");
                        Log.d(TAG, "Checksum : " + Converter.byteArray2HexString(encData.checksum, encData.checksumLen));
                        ui_ShowLog("Checksum : " + Converter.byteArray2HexString(encData.checksum, encData.checksumLen) + "\n");
                        Log.d(TAG, "KSN : " + Converter.byteArray2HexString(encData.KSN, encData.KSNLen));
                        ui_ShowLog("KSN : " + Converter.byteArray2HexString(encData.KSN, encData.KSNLen) + "\n");
                        Log.d(TAG, "Encrypted Entry Len : " + encData.encryptedDataLen
                                + "\nChecksum Len : " + encData.checksumLen
                                + "\nKSN Len : " + encData.KSNLen + "\n");

                        Log.d(TAG, "additionalEncryptedData : " + Converter.byteArray2HexString(encData.additionalEncryptedData, encData.additionalEncryptedDataLen));
                        ui_ShowLog("additionalEncryptedData : " + Converter.byteArray2HexString(encData.additionalEncryptedData, encData.additionalEncryptedDataLen) + "\n");
                        Log.d(TAG, "additionalChecksum : " + Converter.byteArray2HexString(encData.additionalChecksum, encData.additionalChecksumLen));
                        ui_ShowLog("additionalChecksum : " + Converter.byteArray2HexString(encData.additionalChecksum, encData.additionalChecksumLen) + "\n");
                        Log.d(TAG, "additionalKSN : " + Converter.byteArray2HexString(encData.additionalKSN, encData.additionalKSNLen));
                        ui_ShowLog("additionalKSN : " + Converter.byteArray2HexString(encData.additionalKSN, encData.additionalKSNLen) + "\n");
                        Log.d(TAG, "additionalEncryptedDataLen : " + encData.additionalEncryptedDataLen
                                + "\nadditionalChecksumLen : " + encData.additionalChecksumLen
                                + "\nadditionalKSNLen : " + encData.additionalKSNLen + "\n");
                    }

                    Log.d(TAG, "Manual Entry end ***********************************************");

                    GlobalPara.layoutViewCreate = 0;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mViewPager.setCurrentItem(GlobalDef.d_PAGE_TRANSACTION);
                            GlobalPara.layoutViewCreate = 1;

                            Button button;
                            button = (Button) findViewById(R.id.btnTransaction);
                            button.setEnabled(true);
                            button = (Button) findViewById(R.id.btnGetOnlinePin);
                            button.setEnabled(true);
                            button = (Button) findViewById(R.id.btnManualEntry);
                            button.setEnabled(true);
                            button = (Button) findViewById(R.id.btnClearMsg);
                            button.setEnabled(true);
                            button = (Button) findViewById(R.id.btnEncryp);
                            button.setEnabled(true);
                            button = (Button) findViewById(R.id.btnSetting);
                            button.setEnabled(true);
                        }
                    });

                    while (GlobalPara.layoutViewCreate == 0) {
                        MyUtility.sleep(1500);
                    }
                }

                ui_EnableTxnButton();

            }
        });

        threadME.start();

        return 0;
    }

    public void ui_DisableAllButton() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn;
                              btn = (Button) findViewById(R.id.btnTransaction);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnGetOnlinePin);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnCancelTransaction);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnClearMsg);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnManualEntry);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnEncryp);
                              if (btn != null)
                                  btn.setEnabled(false);

                              btn = (Button) findViewById(R.id.btnSetting);
                              if (btn != null)
                                  btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableAllButton() {
        // Reset transaction in progress flag when buttons are re-enabled
        GlobalPara.atmTransactionInProgress = false;

        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              // Add null checks - buttons may not exist in current fragment
                              Button btn;
                              btn = (Button) findViewById(R.id.btnTransaction);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnGetOnlinePin);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnCancelTransaction);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnClearMsg);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnManualEntry);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnEncryp);
                              if (btn != null) btn.setEnabled(true);

                              btn = (Button) findViewById(R.id.btnSetting);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );
    }

    public void ui_DisableManualEntry() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnManualEntry);
                              if (btn != null) btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableManualEntry() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnManualEntry);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );

    }

    public void ui_DisableEncryp() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnEncryp);
                              if (btn != null) btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableEncryp() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnEncryp);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );

    }

    public void ui_DisableSetting() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnSetting);
                              if (btn != null) btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableSetting() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnSetting);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );

    }

    public void ui_DisableGetPinButton() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnGetOnlinePin);
                              if (btn != null) btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableGetPinButton() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnGetOnlinePin);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );

    }

    public void ui_DisableTxnButton() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnTransaction);
                              if (btn != null) btn.setEnabled(false);
                          }
                      }
        );
    }

    public void ui_EnableTxnButton() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              Button btn = (Button) findViewById(R.id.btnTransaction);
                              if (btn != null) btn.setEnabled(true);
                          }
                      }
        );

    }

    public void ui_ShowMsg(final String msg) {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              // Try default TextView first
                              TextView textView = (TextView) findViewById(R.id.txtViewUserInfo);
                              if (textView != null) {
                                  textView.setTextColor(Color.rgb(0, 0, 0));
                                  textView.setText(msg);
                              } else {
                                  // Fallback to ATM layout's txvStatus
                                  textView = (TextView) findViewById(R.id.txvStatus);
                                  if (textView != null) {
                                      textView.setTextColor(Color.rgb(0, 0, 0));
                                      textView.setText(msg);
                                  }
                              }

                              ui_ShowLog("user msg : " + msg);
                              //ui_ShowLog("\n");
                          }
                      }
        );
    }

    public void ui_ShowLog(final String msg) {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              TextView textView = (TextView) findViewById(R.id.edtLog);
                              if (textView != null) {
                                  textView.append(msg);
                                  textView.append("\n");
                              }
                          }
                      }
        );
    }

    public void ui_ClearMsg() {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              TextView textView = (TextView) findViewById(R.id.edtLog);
                              if (textView != null) textView.setText("");

                              textView = (TextView) findViewById(R.id.txvLog);
                              if (textView != null) textView.setText("");

                              LinearLayout Layout = (LinearLayout) findViewById(R.id.layoutLog);
                              if (Layout != null) Layout.setVisibility(View.INVISIBLE);

                              textView = (TextView) findViewById(R.id.txtViewUserInfo);
                              if (textView != null) textView.setText("Welcome\n");

                          }
                      }
        );
    }

    public void btnCancelTransaction_click(View view) {
        threadCancel = new Thread(new Runnable() {
            @Override
            public void run() {

                String cardType;
                int intRtn = 0xFFFFFFFF;

                //DisableButton();
                ui_ClearMsg();

                do {

                    intRtn = emvcl.cancelTransaction();
                    ui_ShowLog("cancelTransaction Rtn: " + String.format("0x%08X", intRtn));
                  //  if(intRtn == emvcl.d_EMVCL_NO_ERROR)
                   //     break;
                } while (false);

                //EnableButton();
                load_json();
                Log.d("JORGE", "Reloading Json");
                intRtn = edl.initialize();
                Log.d("JORGE", "edl.initialize Ret = " + String.format("0x%08X", intRtn) + "\n");

                // Check if ATM mode - navigate back to amount selection
                if (GlobalPara.atmSelectedAmount != null && !"0.00".equals(GlobalPara.atmSelectedAmount)) {
                    // ATM Mode: Reset amount and navigate to amount selection
                    GlobalPara.atmSelectedAmount = "0.00";
                    GlobalPara.atmFee = "0.00";
                    GlobalPara.atmTotal = "0.00";

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
                        }
                    });
                }
            }
        });

        threadCancel.start();
    }

	/*
    public void btnInitialize_Click(View view)
    {

    }
	//*/


    public class CTOS_Printer {
        int ret = 0;
        CtPrint Print;

        public void Init() {
            Print = new CtPrint();
        }

        /**
         * Reads the raw CtPrint hardware status.
         *
         * @return status code (0 = OK), or -1 if the printer is unavailable.
         *         Notable values: CtPrint.STATUS_NOPAPPER_ERR (3) = out of paper,
         *         STATUS_HEADTEMP_ERR (2), STATUS_PLATENRELEASE_ERR (20).
         */
        public int getStatus() {
            try {
                if (Print == null) {
                    Init();
                }
                return Print.status();
            } catch (Exception e) {
                Log.e(TAG, "Printer status read failed: " + e.getMessage());
                return -1;
            }
        }

        /**
         * True when the printer reports it is out of paper.
         * Used to warn the customer and to report honest paper state to the host.
         */
        public boolean isOutOfPaper() {
            return getStatus() == CtPrint.STATUS_NOPAPPER_ERR;
        }

        /**
         * Human-readable description of a CtPrint status code (for logs/UI).
         */
        public String getStatusText() {
            int st = getStatus();
            switch (st) {
                case 0:                                return "OK";
                case CtPrint.STATUS_NOPAPPER_ERR:      return "OUT OF PAPER";
                case CtPrint.STATUS_HEADTEMP_ERR:      return "HEAD OVERHEATED";
                case CtPrint.STATUS_PLATENRELEASE_ERR: return "PLATEN OPEN";
                case -1:                               return "UNAVAILABLE";
                default:                               return "ERROR (" + st + ")";
            }
        }

        public int goprintf() throws IOException {

            int page_len = 920;

            Print.initPage(page_len);
            String print_font;
            int print_x = 0;
            int print_y = 16;
            int Currently_high = 50;

            int print_liftx = 25;

            print_font = "SAMPLE RECEIPT";
            print_y = 32;
            print_x = (384 - print_font.length() * print_y) / 2 + 52;
            print_x += print_font.length() * 3;
            Print.drawText(print_x, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "";
            print_y = 18;
            print_x = (384 - print_font.length() * print_y) / 2 + 55;
            print_x += print_font.length() * 3;
            Print.drawText(print_x, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 16;

            print_y = 26;
            print_font = "...............................................";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 15;

            print_y = 16;
            print_font = "STORE: 0003         REGISTER: 001";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 3;

            print_font = "CASHIER: KATIE";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 3;

            print_font = "ASSOCIATE: 0000000";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_y = 26;
            print_font = "...............................................";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_y = 16;
            print_font = "CUSTOMER RECEIPT COPY";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 15;

            print_font = "";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Card Type ";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = " " + GlobalPara.cardType.toUpperCase();

            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Chech No. ";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "  90119";
            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Card No. ";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = GlobalPara.asciiPAN; //card number
            print_y = 16;
            //print_x = (384 - print_font.length()*print_y)/2+1;
            //print_x += print_font.length()*3+40;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Host/Irans. Type";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "  BANK   00 GENERAL CONDITION  SALE";
            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Batch No.";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "  379";
            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "Auth Code";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "  047364";
            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "DATE";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;
            print_font = "  " + GlobalPara.DateTime;
            print_y = 16;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 10;

            print_y = 26;
            print_font = "...............................................";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 15;

            Currently_high += print_y;
            print_font = "TOTAL AMOUNT";
            print_y = 20;
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);

            print_font = GlobalPara.strAmount;
            Print.drawText(300, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;


            print_y = 26;
            print_font = "...............................................";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y;

            print_font = "";
            print_y = 24;
            print_x = (384 - print_font.length() * print_y) / 2 + 1;
            print_x += print_font.length() * 3;
            Print.drawText(print_x, print_y + Currently_high, print_font, print_y, 1);
            Currently_high += print_y * 3 + 10;

            print_font = "Sign: _______________________";
            Print.drawText(print_liftx, print_y + Currently_high + 15, print_font, print_y);
            Currently_high += print_y - 70;


            Currently_high += (print_y * 5);
            print_y = 16;
            print_font = "I AGREE TO PAY THE ABOVE TOTAL";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 2;

            print_font = "AMOUNT, ACCORDING TO THE CARD";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y + 2;

            print_font = "ISSUER AGREEMENT";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y * 2;

            print_font = "CUSTOMER COPY";
            Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
            Currently_high += print_y * 2;


            Log.d(TAG, "Data path:" + getFilesDir());

//			Print.save("/data/user/0/castech.emvtxn/files/recipe.jpg");

            File file = new File("/data/user/0/castech.emvtxn/files/recipe.jpg");
            Bitmap panel = BitmapFactory.decodeFile(file.getAbsolutePath());
            Print.initPage(panel.getHeight() + 350);
            System.out.println("Set panel initPage is" + Integer.toString(panel.getHeight() + 300));
            Print.drawImage(panel, 0, 0);
            Print.printPage();

            return ret;
        }

        /**
         * Print text content - simple text-based printing for ATM receipts
         * @param text The text to print (use \n for line breaks)
         */
        /**
         * A "separator" line is one made up only of '=' or '-' (the full-width
         * rules on the receipt). These print at the small font so they keep
         * spanning the paper; everything else prints larger.
         */
        private boolean isSeparatorLine(String line) {
            if (line == null) {
                return false;
            }
            String t = line.trim();
            if (t.length() < 5) {
                return false;
            }
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '=' && c != '-') {
                    return false;
                }
            }
            return true;
        }

        public void printf(String text) throws IOException {
            if (Print == null) {
                Init();
            }

            // Handle null or empty text
            if (text == null || text.isEmpty()) {
                return;
            }

            // Split text by newlines
            String[] lines = text.split("\n", -1);

            // Font sizing. Text lines print LARGER for readability; the full-width
            // "====="/"-----" separators stay at the small font so they don't run
            // off the 384-dot paper (drawText clips, it does not wrap). Tune the two
            // FONT_* / LINE_* constants together — text char width grows with font,
            // so very long data lines (e.g. Transaction ID) are the width limit.
            final int FONT_TEXT       = 26;   // larger, readable body text
            final int FONT_SEPARATOR  = 16;   // keep separators full-width & compact
            final int LINE_H_TEXT     = 34;
            final int LINE_H_SEP      = 22;
            final int leftMargin      = 25;
            final boolean PRINT_TEXT_BOLD = true;  // body text bold; separators light

            // First pass: total page height (per-line, since line heights differ).
            int pageHeight = 40;  // top/bottom padding
            for (String line : lines) {
                pageHeight += isSeparatorLine(line) ? LINE_H_SEP : LINE_H_TEXT;
            }
            Print.initPage(pageHeight);

            // Second pass: draw each line. Body text prints BOLD for legibility;
            // separators stay light. 9-arg overload per SDK Print_demo example:
            // drawText(x, y, text, fontSize, widthScale, bold, angle, underline, reverse)
            int currentY = 20;
            for (String line : lines) {
                boolean sep = isSeparatorLine(line);
                if (line != null) {
                    int font = sep ? FONT_SEPARATOR : FONT_TEXT;
                    boolean bold = !sep && PRINT_TEXT_BOLD;
                    Print.drawText(leftMargin, currentY, line, font, 1, bold, (float) 0, false, false);
                }
                currentY += sep ? LINE_H_SEP : LINE_H_TEXT;
            }

            // Print the page. Kept on printPage() (the proven path) — paper-out is
            // detected up-front via getStatus()/isOutOfPaper() rather than by
            // interpreting a print return code whose contract is unverified.
            Print.printPage();
        }
    }

    public void btnHome_Click(final View view) {
        MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 0);
    }

    public void btnAPListSet_Click(final View view) {
        Log.d(TAG, "btnAPListSet_Click()\n");

        byte aidNum = 2;
        EMVApplicationPara[] aidPara = new EMVApplicationPara[aidNum];
        for (int i = 0; i < aidPara.length; i++) {
            aidPara[i] = new EMVApplicationPara();
        }

        byte[] temp = Converter.hexString2ByteArray("A0000000031010");
        Log.d(TAG, "temp.length: " + temp.length + "\n");
        System.arraycopy(temp, 0, aidPara[0].aid, 0, temp.length);
        aidPara[0].aidLen = (byte) temp.length;
        aidPara[0].asi = 0x00;

        temp = Converter.hexString2ByteArray("A0000000041010");
        System.arraycopy(temp, 0, aidPara[1].aid, 0, temp.length);
        aidPara[1].aidLen = (byte) temp.length;
        aidPara[1].asi = 0x00;

        int intRtn = emv.applicationListSet(aidNum, aidPara);
        ui_ShowtxvRtn(String.format("APListSet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("APListSet OK");
        }
    }

    public void btnTermConfigSet_Click(final View view) {
        Log.d(TAG, "btnTermConfigSet_Click()\n");

        int intRtn;
        byte tlvDataNum = 16;

        TlvData[] tlvData = new TlvData[tlvDataNum];
        for (int i = 0; i < tlvDataNum; i++) {
            tlvData[i] = new TlvData();
        }
        tlvData[0].value = new byte[256];
        tlvData[0].tag = 0x9F09;
        tlvData[0].len = 2;
        byte[] temp = Converter.hexString2ByteArray("0840");
        System.arraycopy(temp, 0, tlvData[0].value, 0, temp.length);

        tlvData[1].value = new byte[256];
        tlvData[1].tag = 0x9F33;
        tlvData[1].len = 3;
        temp = Converter.hexString2ByteArray("E0F1C8");
        System.arraycopy(temp, 0, tlvData[1].value, 0, temp.length);

        tlvData[2].value = new byte[256];
        tlvData[2].tag = 0x9F40;
        tlvData[2].len = 5;
        temp = Converter.hexString2ByteArray("F000F0A001");
        System.arraycopy(temp, 0, tlvData[2].value, 0, temp.length);

        tlvData[3].value = new byte[256];
        tlvData[3].tag = 0x9F1E;
        tlvData[3].len = 4;
        temp = Converter.hexString2ByteArray("12345678");
        System.arraycopy(temp, 0, tlvData[3].value, 0, temp.length);

        tlvData[4].value = new byte[256];
        tlvData[4].tag = 0x9F35;
        tlvData[4].len = 1;
        temp = Converter.hexString2ByteArray("22");
        System.arraycopy(temp, 0, tlvData[4].value, 0, temp.length);

        tlvData[5].value = new byte[256];
        tlvData[5].tag = 0x5F2A;
        tlvData[5].len = 2;
        temp = Converter.hexString2ByteArray("0949");
        System.arraycopy(temp, 0, tlvData[5].value, 0, temp.length);

        tlvData[6].value = new byte[256];
        tlvData[6].tag = 0x9F1A;
        tlvData[6].len = 2;
        temp = Converter.hexString2ByteArray("0792");
        System.arraycopy(temp, 0, tlvData[6].value, 0, temp.length);

        tlvData[7].value = new byte[256];
        tlvData[7].tag = 0xDFC0;
        tlvData[7].len = 15;
        temp = Converter.hexString2ByteArray("9F02065F2A029A039C0195059F3704");
        System.arraycopy(temp, 0, tlvData[7].value, 0, temp.length);

        tlvData[8].value = new byte[256];
        tlvData[8].tag = 0xDFC1;
        tlvData[8].len = 3;
        temp = Converter.hexString2ByteArray("9F3704");
        System.arraycopy(temp, 0, tlvData[8].value, 0, temp.length);

        tlvData[9].value = new byte[256];
        tlvData[9].tag = 0x9F1B;
        tlvData[9].len = 4;
        temp = Converter.hexString2ByteArray("000003E8");
        System.arraycopy(temp, 0, tlvData[9].value, 0, temp.length);

        tlvData[10].value = new byte[256];
        tlvData[10].tag = 0xDFC4;
        tlvData[10].len = 4;
        temp = Converter.hexString2ByteArray("00000005");
        System.arraycopy(temp, 0, tlvData[10].value, 0, temp.length);

        tlvData[11].value = new byte[256];
        tlvData[11].tag = 0xDFC2;
        tlvData[11].len = 1;
        temp = Converter.hexString2ByteArray("20");
        System.arraycopy(temp, 0, tlvData[11].value, 0, temp.length);

        tlvData[12].value = new byte[256];
        tlvData[12].tag = 0xDFC3;
        tlvData[12].len = 1;
        temp = Converter.hexString2ByteArray("40");
        System.arraycopy(temp, 0, tlvData[12].value, 0, temp.length);

        tlvData[13].value = new byte[256];
        tlvData[13].tag = 0xDFC6;
        tlvData[13].len = 5;
        temp = Converter.hexString2ByteArray("0000000000");
        System.arraycopy(temp, 0, tlvData[13].value, 0, temp.length);

        tlvData[14].value = new byte[256];
        tlvData[14].tag = 0xDFC7;
        tlvData[14].len = 5;
        temp = Converter.hexString2ByteArray("0000000000");
        System.arraycopy(temp, 0, tlvData[14].value, 0, temp.length);

        tlvData[15].value = new byte[256];
        tlvData[15].tag = 0xDFC8;
        tlvData[15].len = 5;
        temp = Converter.hexString2ByteArray("0000000000");
        System.arraycopy(temp, 0, tlvData[15].value, 0, temp.length);

        intRtn = emv.terminalConfigSet(tlvDataNum, tlvData);
        ui_ShowtxvRtn(String.format("TermConfigSet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("TermConfigSet OK");
        }
    }

    public void btnTermConfigDel_Click(final View view) {
        Log.d(TAG, "btnTermConfigDel_Click()\n");

        int intRtn;
        byte tagNum = 1;
        int[] tags = new int[tagNum];

        tags[0] = 0x9F09;
        intRtn = emv.terminalConfigDelete(tagNum, tags);
        ui_ShowtxvRtn(String.format("TermConfigDel Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("TermConfigDel OK");
        }
    }

    public void btnTermConfigDelAll_Click(final View view) {
        Log.d(TAG, "btnTermConfigDelAll_Click()\n");

        int intRtn = emv.terminalConfigDeleteAll();
        ui_ShowtxvRtn(String.format("TermConfigDeleteAll Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("TermConfigDeleteAll OK");
        }
    }

    public void btnAppConfigSet_Click(final View view) {
        Log.d(TAG, "btnAppConfigSet_Click()\n");

        int intRtn;
        byte[] aid = Converter.hexString2ByteArray("A0000000031010");
        byte aidLen = (byte) aid.length;
        byte tlvDataNum = 1;
        TlvData[] tlvData = new TlvData[tlvDataNum];

        for (int i = 0; i < tlvDataNum; i++) {
            tlvData[i] = new TlvData();
        }
        tlvData[0].value = new byte[256];
        tlvData[0].tag = 0x9F09;
        tlvData[0].len = 2;
        byte[] temp = Converter.hexString2ByteArray("008D");
        System.arraycopy(temp, 0, tlvData[0].value, 0, temp.length);

        intRtn = emv.appConfigSet(aid, aidLen, tlvDataNum, tlvData);
        ui_ShowtxvRtn(String.format("AppConfigSet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("AppConfigSet OK");
        }
    }

    public void btnAppConfigDel_Click(final View view) {
        Log.d(TAG, "btnAppConfigDel_Click()\n");

        int intRtn;
        byte[] aid = Converter.hexString2ByteArray("A0000000031010");
        byte aidLen = (byte) aid.length;
        byte tagNum = 1;
        int[] tags = new int[tagNum];

        tags[0] = 0x9F09;
        intRtn = emv.appConfigDelete(aid, aidLen, tagNum, tags);
        ui_ShowtxvRtn(String.format("AppConfigDel Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("AppConfigDel OK");
        }
    }

    public void btnAppConfigDelAll_Click(final View view) {
        Log.d(TAG, "btnAppConfigDelAll_Click()\n");

        int intRtn = emv.appConfigDeleteAll();
        ui_ShowtxvRtn(String.format("AppConfigDelAll Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("AppConfigDelAll OK");
        }
    }

    public void btnCapkSet_Click(final View view) {
        Log.d(TAG, "btnCapkSet_Click()\n");

        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        CAPublicKey capk = new CAPublicKey();
        capk.index = 0x57;
        capk.modulusLen = 96;
        capk.modulus = Converter.hexString2ByteArray("942B7F2BA5EA307312B63DF77C5243618ACC2002BD7ECB74D821FE7BDC78BF28F49F74190AD9B23B9713B140FFEC1FB429D93F56BDC7ADE4AC075D75532C1E590B21874C7952F29B8C0F0C1CE3AEEDC8DA25343123E71DCF86C6998E15F756E3");
        capk.exponentLen = 3;
        capk.exponent = Converter.hexString2ByteArray("010001");
        capk.hash = Converter.hexString2ByteArray("F9862BDAB2D788E622DA7F0B701C04BE97DC3631");

        intRtn = emv.capkSet(rid, capk);
        ui_ShowtxvRtn(String.format("CapkSet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CapkSet OK");
        }
    }

    public void btnCapkDel_Click(final View view) {
        Log.d(TAG, "btnCapkDel_Click()\n");

        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        byte capkIndex = 0x57;

        intRtn = emv.capkDelete(rid, capkIndex);
        ui_ShowtxvRtn(String.format("CapkDel Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CapkDel OK");
        }
    }

    public void btnCapkDelAll_Click(final View view) {
        Log.d(TAG, "btnCapkDelAll_Click()\n");

        int intRtn = emv.capkDeleteAll();
        ui_ShowtxvRtn(String.format("CapkDelAll Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CapkDelAll OK");
        }
    }

    public void btnParamSet_Click(final View view) {
        Log.d(TAG, "btnParamSet_Click()\n");
        EMVCLParameterData setparam = new EMVCLParameterData();
        EMVCLParameterData getparam = new EMVCLParameterData();

        ui_ShowtxvRtn("Get Parameter  ...");
        int itrn = emvcl.getParameter(0x0002, getparam);
        ui_ShowtxvRtn("0x0002: " + Converter.byteArray2HexString(getparam.data[0], getparam.len[0]));

        setparam.num = 2;
        setparam.len[0] = 2;
        setparam.index[0] = (byte) 0x0002;
        setparam.data[0][0] = (byte) 0x3A;
        setparam.data[0][1] = (byte) 0x99;

        setparam.len[1] = 1;
        setparam.index[1] = 0x100A;
        setparam.data[1][0] = (byte) 0x00;

        ui_ShowtxvRtn("Set Parameter  ...");
        itrn = emvcl.setParameter(setparam);
        ui_ShowtxvRtn("Set Parameter Rtn: " + String.format("0x%08X", itrn));

        ui_ShowtxvRtn("Get Parameter  ...");
        itrn = emvcl.getParameter(0x0002, getparam);
        ui_ShowtxvRtn("0x0002: " + Converter.byteArray2HexString(getparam.data[0], getparam.len[0]));

        setparam.data[0][1] = (byte) 0x98;
        ui_ShowtxvRtn("Set Parameter  ...");
        itrn = emvcl.setParameter(setparam);
        ui_ShowtxvRtn("Set Parameter Rtn: " + String.format("0x%08X", itrn));

        ui_ShowtxvRtn("Get Parameter  ...");
        itrn = emvcl.getParameter(0x0002, getparam);
        ui_ShowtxvRtn("0x0002: " + Converter.byteArray2HexString(getparam.data[0], getparam.len[0]));
    }

    public void btnParamGet_Click(final View view) {
        Log.d(TAG, "btnParamGet_Click()\n");
        EMVCLParameterData getparam = new EMVCLParameterData();
        ui_ShowtxvRtn("Get Parameter  ...");
        int itrn = emvcl.getParameter(0x0002, getparam);
        ui_ShowtxvRtn("0x0002: " + Converter.byteArray2HexString(getparam.data[0], getparam.len[0]));
    }

    public void btnAIDSet_Click(final View view) {
        Log.d(TAG, "btnAIDSet_Click()\n");

        EMVCLAidSetTagData tagData = new EMVCLAidSetTagData();
        byte action = 0x00;
        tagData.aid = Converter.hexString2ByteArray("A0000000031010");
        tagData.aidLen = 7;
        tagData.kernelId[0] = 3;
        tagData.kernelIdLen = 1;
        tagData.transactionType = 0;
        tagData.tagData = Converter.hexString2ByteArray("9F6604A00040009F3501009F33030008C89F40056F000020019F090201059F1A0208405F2A020840DF0006000000003000DF0106000000002200DF02060000000027009F1B04000007D4DF2501FFDF8F4B0100DF050101DF210101DF220101DF290101DF240101DF23819B0531026826201FDF2501FFDF8F4B0100DF0001FFDF0106000000003001DF02060000000030010831026826120000031FDF2501FFDF8F4B0100DF0001FFDF0106000000001501DF02060000000010010531026826121FDF2501FFDF8F4B0100DF0001FFDF0106000000001501DF02060000000025010531026826001FDF2501FFDF8F4B0100DF0001FFDF0106000000002001DF0206000000001501DF8F4E20FFC31DDF3000DF3100DF3200DF3300DF3400DF3500DF3600DF3700DF38005A00");
        tagData.tagDataLen = 298;

        ui_ShowtxvRtn("AID Set Tag Data  ...");
        int itrn = emvcl.aidSetTagData(action, tagData);
        if (itrn == 0) {
            ui_ShowtxvRtn("AID Set Tag Data OK");
        } else {
            ui_ShowtxvRtn("AID Set Tag Data Rtn: " + String.format("0x%08X", itrn));
        }
    }

    public void btnAIDGet_Click(final View view) {
        Log.d(TAG, "btnAIDGet_Click()\n");

        EMVCLAidGetTagData aidGetTagData = new EMVCLAidGetTagData();

        //Get Visa tag setting
        aidGetTagData.aid = Converter.hexString2ByteArray("A0000000031010");
        aidGetTagData.aidLen = 7;
        aidGetTagData.kernelId[0] = 3;
        aidGetTagData.kernelIdLen = 1;
        aidGetTagData.transactionType = 0;
        aidGetTagData.tagData = new byte[1024];
        //aidGetTagData.tagDataLen = 1024;

        ui_ShowtxvRtn("AID Get Tag Data  ...");
        int itrn = emvcl.aidGetTagData(aidGetTagData);
        if (itrn == 0) {
            ui_ShowtxvRtn("AID Get Tag Data Len: " + String.format("%d", aidGetTagData.tagDataLen));
            ui_ShowtxvRtn("Tag data: " + Converter.byteArray2HexString(aidGetTagData.tagData, aidGetTagData.tagDataLen));
        } else if (itrn == 0x80000030) {
            ui_ShowtxvRtn("AID DATA NOT FOUND");
        } else {
            ui_ShowtxvRtn("AID Get Tag Data Rtn: " + String.format("0x%08X", itrn));
        }
        Log.d(TAG, "aidGetTagData done \n");
    }

    public void btnAIDDel_Click(final View view) {
        Log.d(TAG, "btnAIDDel_Click()\n");

        EMVCLAidSetTagData tagData = new EMVCLAidSetTagData();
        byte action = 0x01;
        tagData.aid = Converter.hexString2ByteArray("A0000000031010");
        tagData.aidLen = 7;
        tagData.kernelId[0] = 3;
        tagData.kernelIdLen = 1;
        tagData.transactionType = 0;

        ui_ShowtxvRtn("AID Delete Tag Data  ...");
        int itrn = emvcl.aidSetTagData(action, tagData);
        if (itrn == 0) {
            ui_ShowtxvRtn("AID Delete Tag Data OK");
        } else {
            ui_ShowtxvRtn("AID Delete Tag Data Rtn: " + String.format("0x%08X", itrn));
        }
    }

    public void btnAIDDelAll_Click(final View view) {
        Log.d(TAG, "btnAIDDelAll_Click()\n");

        EMVCLAidSetTagData tagData = new EMVCLAidSetTagData();
        byte action = 0x02;

        ui_ShowtxvRtn("AID DeleteAll Tag Data  ...");
        int itrn = emvcl.aidSetTagData(action, tagData);
        if (itrn == 0) {
            ui_ShowtxvRtn("AID DeleteAll Tag Data OK");
        } else {
            ui_ShowtxvRtn("AID DeleteAll Tag Data Rtn: " + String.format("0x%08X", itrn));
        }
    }

    public void btnCLCapkSet_Click(final View view) {
        Log.d(TAG, "btnCLCapkSet_Click()\n");

        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        EMVCLCAPublicKey capk = new EMVCLCAPublicKey();
        capk.action = 0x00;
        capk.index = (byte) 0x95;
        capk.modulusLen = 144;
        capk.modulus = Converter.hexString2ByteArray("BE9E1FA5E9A803852999C4AB432DB28600DCD9DAB76DFAAA47355A0FE37B1508AC6BF38860D3C6C2E5B12A3CAAF2A7005A7241EBAA7771112C74CF9A0634652FBCA0E5980C54A64761EA101A114E0F0B5572ADD57D010B7C9C887E104CA4EE1272DA66D997B9A90B5A6D624AB6C57E73C8F919000EB5F684898EF8C3DBEFB330C62660BED88EA78E909AFF05F6DA627B");
        capk.exponentLen = 3;
        capk.exponent = Converter.hexString2ByteArray("010001");

        MessageDigest sha = null;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        sha.reset();
        sha.update(rid, 0, 5);
        sha.update(new byte[]{capk.index}, 0, 1);
        sha.update(capk.modulus, 0, capk.modulusLen);
        sha.update(capk.exponent, 0, capk.exponentLen);
        byte[] hashout = sha.digest();
        Log.d(TAG, "Hash(sha1) " + Converter.byteArray2HexString(hashout, 20));
        capk.hash = hashout;

        intRtn = emvcl.setCAPK(rid, capk);
        ui_ShowtxvRtn(String.format("CLCapkSet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CLCapkSet OK");
        }
    }

    public void btnCLCapkGet_Click(final View view) {
        Log.d(TAG, "btnCLCapkGet_Click()\n");

        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        byte index = (byte) 0x95;
        EMVCLCAPublicKey capk = new EMVCLCAPublicKey();

        intRtn = emvcl.getCAPK(rid, index, capk);
        ui_ShowtxvRtn(String.format("CLCapkGet Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CLCapkGet OK");
            ui_ShowtxvRtn("Modulus: " + Converter.byteArray2HexString(capk.modulus, capk.modulusLen));
        } else if (intRtn == 0x80000030) {
            ui_ShowtxvRtn("CLCapkGet DATA NOT FOUND");
        }
    }

    public void btnCLCapkDel_Click(final View view) {
        Log.d(TAG, "btnCLCapkDel_Click()\n");

        EMVCLCAPublicKey capk = new EMVCLCAPublicKey();
        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        capk.action = 0x01;
        capk.index = (byte) 0x95;

        intRtn = emvcl.setCAPK(rid, capk);
        ui_ShowtxvRtn(String.format("CLCapkDel Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CLCapkDel OK");
        }
    }

    public void btnCLCapkDelAll_Click(final View view) {
        Log.d(TAG, "btnCLCapkDelAll_Click()\n");

        EMVCLCAPublicKey capk = new EMVCLCAPublicKey();
        int intRtn;
        byte[] rid = Converter.hexString2ByteArray("A000000003");
        capk.action = 0x02;

        intRtn = emvcl.setCAPK(rid, capk);
        ui_ShowtxvRtn(String.format("CLCapkDelAll Rtn: 0x%08X", intRtn));
        if (intRtn == 0) {
            ui_ShowtxvRtn("CLCapkDelAll OK");
        }
    }

    //Jorge
    public void load_json() {

        int rtn;
        InputStream wlStream = null;
        try {
            wlStream = getApplicationContext().getAssets().open("bin.json");
            if (wlStream != null) {
                rtn = edl.setWhiteListFile(wlStream);
                if (rtn == 0) {
                    Log.d("JORGE", "bin.json was downloaded OK");
                    Log.d("TRAINING", "bin.json was downloaded OK");

                } else {
                    Log.d("JORGE", "bin.json was NOT downloaded!!!");
                }
                wlStream.close();
            }
        } catch (Exception e) {
            Log.d("JORGE", "bin.json download crashed!!");
        }

    }

    public void json_change() {
        int rtn;

        if (GlobalPara.isEMVEDLAvaliable == true) {
            if (GlobalPara.JsonFile.equals("bin.json")) {
                InputStream wlStream = null;
                try {
                    wlStream = getApplicationContext().getAssets().open("bin.json");
                    if (wlStream != null) {
                        rtn = edl.setWhiteListFile(wlStream);
                        if (rtn == 0) {
                            ui_ShowtxvRtn("setWhiteListFile OK");
                            Log.d(TAG, "bin.json was downloaded OK");
                        } else {
                            ui_ShowtxvRtn("setWhiteListFile Fail");
                            Log.d(TAG, "bin.json was NOT downloaded!!!");
                        }
                        wlStream.close();
                    }
                } catch (Exception e) {
                    ui_ShowtxvRtn("setWhiteListFile Exception Fail");
                }
            } else {
                rtn = edl.deleteWhiteListFile();
                if (rtn == 0) {
                    ui_ShowtxvRtn("deleteWhiteListFile OK");
                } else {
                    ui_ShowtxvRtn("deleteWhiteListFile Fail");
                }
            }
        }
    }

    public void ui_ShowtxvRtn(final String string) {
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {
                              TextView textView = (TextView) findViewById(R.id.txvRtn);
                              textView.append(string + "\n");
                              GlobalPara.settingRtn = textView.getText().toString();
                              textView = (TextView) findViewById(R.id.txvMinRtn);
                              textView.append("\n" + string);
                              Log.e(TAG, string);
                          }
                      }
        );
    }

    public void btnEncryp_Click(View view) {
        if (GlobalPara.isTokenOK) {
            edl.setEncMode(0x01);
            edl.setEncKey(0xC002, 0x0000);
            edl.setCipherMethod((byte) 0x01);
            edl.setICV(new byte[8], 8);
            edl.setTransportKey(new byte[]{'1', '2', '3', '4', '5', '6', '7', '8'}, 8);
            //edl.setTransportKey(Converter.hexString2ByteArray("0100000000000009"), 8);
            //edl.requestToken(4, new int[]{0xC1, 0xC1, 0xC3, 0xC4});
            edl.requestToken(GlobalPara.token_i, GlobalPara.token);
            edl.requestSensitiveEmvData(20, new int[]{0x5A, 0x5F24, 0x5F2A, 0x5F34, 0x82, 0x95, 0x9A, 0x9B, 0x9C, 0x9F02, 0x9F03, 0x9F10, 0x9F1A, 0x9F26, 0x9F27, 0x9F33, 0x9F34, 0x9F35, 0x9F36, 0x9F37});
            EMVEDLEncryptedData encData = new EMVEDLEncryptedData();
            int intRtn = edl.getEncryption(encData);
            if (intRtn == 0) {
                Log.d(TAG, "Encryption: " + Converter.byteArray2HexString(encData.encryptedData, encData.encryptedDataLen));
                Log.d(TAG, "Encryption KSN: " + Converter.byteArray2HexString(encData.KSN, encData.KSNLen));
            } else
                Log.d("Encryption Fail", String.format("Rtn: 0x%08X", intRtn));
        }
        GlobalPara.isTokenOK = false;
    }

    public void btnSetting_Click(View view) {
        MyUtility.switchPage(GlobalDef.d_PAGE_SETTING, 0);
    }

    public int txnCardAcquisition() {
        int intRtn;
        int candidateNum = 0;

        Log.d(TAG, "txnCardAcquisition ***********************************************");

        intRtn = emv.txnCardAcquisitionRead();
        if (intRtn != 0) {
            Log.d(TAG, "txnCardAcquisitionRead Fail Rtn: " + String.format("0x%08X", intRtn));
        } else {
            candidateNum = emv.txnCardAcquisitionAppNumGet();
            Log.d(TAG, "txnCardAcquisitionAppNumGet: " + candidateNum);

            EMVCardAcquisitionData[] acquisitionBuf = new EMVCardAcquisitionData[candidateNum];

            intRtn = emv.txnCardAcquisitionAppDataGet(acquisitionBuf);
            if (intRtn != 0) {
                Log.d(TAG, "txnCardAcquisitionAppDataGet Fail Rtn: " + String.format("0x%08X", intRtn));
            } else {
                for (int i = 0; i < candidateNum; i++) {
                    Log.d(TAG, "txnCardAcquisitionAppDataGet[" + i + "]: \n" +
                            "tlv: " + Converter.byteArray2HexString(acquisitionBuf[i].tlv.dataBuf, acquisitionBuf[i].tlv.dataLen) + "\n" +
                            "aid: " + Converter.byteArray2HexString(acquisitionBuf[i].aid.dataBuf, acquisitionBuf[i].aid.dataLen) + "\n" +
                            "pan: " + Converter.byteArray2HexString(acquisitionBuf[i].pan.dataBuf, acquisitionBuf[i].pan.dataLen) + "\n" +
                            "issuerCountryCode: " + Converter.byteArray2HexString(acquisitionBuf[i].issuerCountryCode.dataBuf, acquisitionBuf[i].issuerCountryCode.dataLen)
                    );
                }
            }
        }
        return intRtn;
    }

    public int preferredOrder(String sDataBuf) {
        int intRtn;
        EMVByteBufData PreferredOrder = new EMVByteBufData();

        Log.d(TAG, "preferredOrder ***********************************************");
        PreferredOrder.dataBuf = Converter.hexString2ByteArray(sDataBuf);
        PreferredOrder.dataLen = PreferredOrder.dataBuf.length;
        intRtn = emv.preferredOrderSet(PreferredOrder);
        if (intRtn != 0) {
            Log.d(TAG, "preferredOrderSet Fail Rtn: " + String.format("0x%08X", intRtn));
        } else {
            intRtn = emv.preferredOrderGet(PreferredOrder);
            if (intRtn != 0) {
                Log.d(TAG, "preferredOrderGet Fail Rtn: " + String.format("0x%08X", intRtn));
            } else {
                Log.d(TAG, "preferredOrderGet: " + Converter.byteArray2HexString(PreferredOrder.dataBuf, PreferredOrder.dataLen));
            }
        }
        return intRtn;
    }

    public int appFilteringPara(String sDataBuf) {
        int intRtn;
        EMVByteBufData AppFilteringPara = new EMVByteBufData();

        Log.d(TAG, "appFilteringPara ***********************************************");
        AppFilteringPara.dataBuf = Converter.hexString2ByteArray(sDataBuf);
        AppFilteringPara.dataLen = AppFilteringPara.dataBuf.length;
        intRtn = emv.appFilteringParaSet(AppFilteringPara);
        if (intRtn != 0) {
            Log.d(TAG, "appFilteringParaSet Fail Rtn: " + String.format("0x%08X", intRtn));
        } else {
            AppFilteringPara = null;
            EMVByteBufData AppFilteringParaGet = new EMVByteBufData();

            intRtn = emv.appFilteringParaGet(AppFilteringParaGet);
            if (intRtn != 0) {
                Log.d(TAG, "appFilteringParaGet Fail Rtn: " + String.format("0x%08X", intRtn));
            } else {
                Log.d(TAG, "appFilteringParaGet: " + Converter.byteArray2HexString(AppFilteringParaGet.dataBuf, AppFilteringParaGet.dataLen));
            }
        }
        return intRtn;
    }

    public int SetConfiguration(String fileName, boolean isUpdateFile) {
        int intRtn;
        String str;
        InputStream xmlStream;

        if (isUpdateFile == true) {
            xmlStream = null;
            try {
                xmlStream = getApplicationContext().getAssets().open(fileName);
            } catch (Exception e) {
                e.printStackTrace();
            }

            intRtn = emv.setConfiguration(fileName, xmlStream);
        } else {
            intRtn = emv.setConfiguration(fileName);
        }

        if (intRtn != 0) {
            str = "EMV setConfiguration Fail, Rtn: " + String.format("0x%08X", intRtn);
            Log.d(TAG, str);
        } else {
            Log.d(TAG, "  EMV setConfiguration OK");
            // Programmatically load US Common Debit CAPKs for RID A000000098
            loadUSCommonDebitCAPKs();
        }

        return intRtn;
    }

    /**
     * Programmatically loads CAPKs for US Common Debit (RID A000000098)
     * These are the same as VISA production keys but under the US Common Debit RID
     * Optimized: Reduced logging for faster execution
     */
    private void loadUSCommonDebitCAPKs() {
        Log.d(TAG, "Loading CAPKs for RID A000000098 and A000000003...");
        byte[] rid = Converter.hexString2ByteArray("A000000098");
        int rtn;

        // Index 05 - VISA test key (1152-bit = 144 bytes)
        CAPublicKey capk05 = new CAPublicKey();
        capk05.index = 0x05;
        capk05.modulusLen = 144;  // Fixed: was 128, actual modulus is 144 bytes
        capk05.modulus = Converter.hexString2ByteArray("BE9E1FA5E9A803852999C4AB432DB28600DCD9DAB76DFAAA47355A0FE37B1508AC6BF38860D3C6C2E5B12A3CAAF2A7005A7241EBAA7771112C74CF9A0634652FBCA0E5980C54A64761EA101A114E0F0B5572ADD57D010B7C9C887E104CA4EE1272DA66D997B9A90B5A6D624AB6C57E73C8F919000EB5F684898EF8C3DBEFB330C62660BED88EA78E909AFF05F6DA627B");
        capk05.exponentLen = 1;
        capk05.exponent = Converter.hexString2ByteArray("03");
        capk05.hash = Converter.hexString2ByteArray("EE1511CEC71020A9B90443B37B1D5F6E703030F6");
        rtn = emv.capkSet(rid, capk05);
        if (rtn != 0) Log.w(TAG, "CAPK 05 set failed: " + String.format("0x%08X", rtn));

        // Index 09 - Current VISA production key (1984-bit, most commonly used)
        CAPublicKey capk09 = new CAPublicKey();
        capk09.index = 0x09;
        capk09.modulusLen = 248;
        capk09.modulus = Converter.hexString2ByteArray("9D912248DE0A4E39C1A7DDE3F6D2588992C1A4095AFBD1824D1BA74847F2BC4926D2EFD904B4B54954CD189A54C5D1179654F8F9B0D2AB5F0357EB642FEDA95D3912C6576945FAB897E7062CAA44A4AA06B8FE6E3DBA18AF6AE3738E30429EE9BE03427C9D64F695FA8CAB4BFE376853EA34AD1D76BFCAD15908C077FFE6DC5521ECEF5D278A96E26F57359FFAEDA19434B937F1AD999DC5C41EB11935B44C18100E857F431A4A5A6BB65114F174C2D7B59FDF237D6BB1DD0916E644D709DED56481477C75D95CDD68254615F7740EC07F330AC5D67BCD75BF23D28A140826C026DBDE971A37CD3EF9B8DF644AC385010501EFC6509D7A41");
        capk09.exponentLen = 1;
        capk09.exponent = Converter.hexString2ByteArray("03");
        capk09.hash = Converter.hexString2ByteArray("1FF80A40173F52D7D27E0F26A146A1C8CCB29046");
        rtn = emv.capkSet(rid, capk09);
        if (rtn != 0) Log.w(TAG, "CAPK 09 set failed: " + String.format("0x%08X", rtn));

        // Index 08 - VISA production key (1408-bit)
        CAPublicKey capk08 = new CAPublicKey();
        capk08.index = 0x08;
        capk08.modulusLen = 176;
        capk08.modulus = Converter.hexString2ByteArray("D9FD6ED75D51D0E30664BD157023EAA1FFA871E4DA65672B863D255E81E137A51DE4F72BCC9E44ACE12127F87E263D3AF9DD9CF35CA4A7B01E907000BA85D24954C2FCA3074825DDD4C0C8F186CB020F683E02F2DEAD3969133F06F7845166ACEB57CA0FC2603445469811D293BFEFBAFAB57631B3DD91E796BF850A25012F1AE38F05AA5C4D6D03B1DC2E568612785938BBC9B3CD3A910C1DA55A5A9218ACE0F7A21287752682F15832A678D6E1ED0B");
        capk08.exponentLen = 1;
        capk08.exponent = Converter.hexString2ByteArray("03");
        capk08.hash = Converter.hexString2ByteArray("20D213126955DE205ADC2FD2822BD22DE21CF9A8");
        rtn = emv.capkSet(rid, capk08);
        if (rtn != 0) Log.w(TAG, "CAPK 08 set failed: " + String.format("0x%08X", rtn));

        // Index 07 - VISA production key (1152-bit)
        CAPublicKey capk07 = new CAPublicKey();
        capk07.index = 0x07;
        capk07.modulusLen = 144;
        capk07.modulus = Converter.hexString2ByteArray("A89F25A56FA6DA258C8CA8B40427D927B4A1EB4D7EA326BBB12F97DED70AE5E4480FC9C5E8A972177110A1CC318D06D2F8F5C4844AC5FA79A4DC470BB11ED635699C17081B90F1B984F12E92C1C529276D8AF8EC7F28492097D8CD5BECEA16FE4088F6CFAB4A1B42328A1B996F9278B0B7E3311CA5EF856C2F888474B83612A82E4E00D0CD4069A6783140433D50725F");
        capk07.exponentLen = 1;
        capk07.exponent = Converter.hexString2ByteArray("03");
        capk07.hash = Converter.hexString2ByteArray("B4BC56CC4E88324932CBC643D6898F6FE593B172");
        rtn = emv.capkSet(rid, capk07);
        if (rtn != 0) Log.w(TAG, "CAPK 07 set failed: " + String.format("0x%08X", rtn));

        // Index 92 - VISA production key (1408-bit)
        CAPublicKey capk92 = new CAPublicKey();
        capk92.index = (byte)0x92;
        capk92.modulusLen = 176;
        capk92.modulus = Converter.hexString2ByteArray("996AF56F569187D09293C14810450ED8EE3357397B18A2458EFAA92DA3B6DF6514EC060195318FD43BE9B8F0CC669E3F844057CBDDF8BDA191BB64473BC8DC9A730DB8F6B4EDE3924186FFD9B8C7735789C23A36BA0B8AF65372EB57EA5D89E7D14E9C7B6B557460F10885DA16AC923F15AF3758F0F03EBD3C5C2C949CBA306DB44E6A2C076C5F67E281D7EF56785DC4D75945E491F01918800A9E2DC66F60080566CE0DAF8D17EAD46AD8E30A247C9F");
        capk92.exponentLen = 1;
        capk92.exponent = Converter.hexString2ByteArray("03");
        capk92.hash = Converter.hexString2ByteArray("429C954A3859CEF91295F663C963E582ED6EB253");
        rtn = emv.capkSet(rid, capk92);
        if (rtn != 0) Log.w(TAG, "CAPK 92 set failed: " + String.format("0x%08X", rtn));

        // Also load for VISA RID A000000003 in case SDK looks up by certificate RID
        byte[] visaRid = Converter.hexString2ByteArray("A000000003");

        rtn = emv.capkSet(visaRid, capk05);
        if (rtn != 0) Log.w(TAG, "VISA CAPK 05 failed: " + String.format("0x%08X", rtn));
        rtn = emv.capkSet(visaRid, capk09);
        if (rtn != 0) Log.w(TAG, "VISA CAPK 09 failed: " + String.format("0x%08X", rtn));
        rtn = emv.capkSet(visaRid, capk08);
        if (rtn != 0) Log.w(TAG, "VISA CAPK 08 failed: " + String.format("0x%08X", rtn));
        rtn = emv.capkSet(visaRid, capk07);
        if (rtn != 0) Log.w(TAG, "VISA CAPK 07 failed: " + String.format("0x%08X", rtn));
        rtn = emv.capkSet(visaRid, capk92);
        if (rtn != 0) Log.w(TAG, "VISA CAPK 92 failed: " + String.format("0x%08X", rtn));

        Log.d(TAG, "CAPK loading complete (both RIDs)");
    }

    public int SetConfigurationFile(String fileName) {
        int intRtn;
        String str;
        InputStream xmlStream;

        xmlStream = null;
        try {
            xmlStream = getApplicationContext().getAssets().open(fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }

        intRtn = emv.setConfigurationFile(fileName, xmlStream);
        if (intRtn != 0) {
            str = "EMV SetConfigurationFile Fail, Rtn: " + String.format("0x%08X", intRtn);
            Log.d(TAG, str);
        } else {
            Log.d(TAG, "  EMV SetConfigurationFile OK");
        }

        return intRtn;
    }

    public int SetConfigurationEx_SelActive(String fileName, byte isSelActive) {
        int intRtn;
        String str;

        intRtn = 0;
        EMVConfigData configData = new EMVConfigData();

        configData.version = 0x0000;
        configData.configFilename = fileName;
        configData.isSelActive = isSelActive;

        intRtn = emv.setConfigurationEx(configData);
        if (intRtn != 0) {
            str = "EMV SetConfigurationEx_SelActive Fail, Rtn: " + String.format("0x%08X", intRtn);
            Log.d(TAG, str);
        } else {
            Log.d(TAG, "  EMV SetConfigurationEx_SelActive OK");
        }
        return intRtn;
    }

    public int SetConfigurationEx_SelIndex(String fileName, byte selIndex) {
        int intRtn;
        String str;

        intRtn = 0;
        EMVConfigData configData = new EMVConfigData();

        configData.version = 0x0000;
        configData.configFilename = fileName;
        configData.isSelActive = 0;
        configData.selIndex = selIndex;


        intRtn = emv.setConfigurationEx(configData);
        if (intRtn != 0) {
            str = "EMV SetConfigurationEx_SelIndex Fail, Rtn: " + String.format("0x%08X", intRtn);
            Log.d(TAG, str);
        } else {
            Log.d(TAG, "  EMV SetConfigurationEx_SelIndex OK");
        }
        return intRtn;
    }

    // =========================================================================
    // TEST FUNCTIONS FOR PAN/PIN BLOCK DEBUGGING
    // =========================================================================

    /**
     * Test PIN block creation with a known test PAN.
     * This calculates what a correct ISO-0 PIN block should look like.
     * Compare with actual SDK output to verify if clear PAN is being used.
     */
    public void testPinBlockCalculation() {
        Log.d(TAG, "=== TEST PIN BLOCK CALCULATION ===");

        // Test PAN (clear) - the actual card PAN
        String testPan = "4430410010008318";
        String testPin = "1234";

        Log.d(TAG, "Test PAN: " + testPan);
        Log.d(TAG, "Test PIN: " + testPin);

        // Calculate expected Format 0 (ISO-0) PIN block
        // Format 0: 0 | PIN length | PIN | F padding XOR 0000 | PAN[3..14]
        String pinBlock = "0" + testPin.length() + testPin;
        while (pinBlock.length() < 16) {
            pinBlock += "F";
        }
        Log.d(TAG, "PIN portion: " + pinBlock);

        // PAN block: 0000 + PAN[3..14] (rightmost 12 digits excluding check digit)
        String panBlock = "0000" + testPan.substring(3, 15);
        Log.d(TAG, "PAN portion: " + panBlock);

        // XOR them
        try {
            long pinBlockVal = Long.parseUnsignedLong(pinBlock, 16);
            long panBlockVal = Long.parseUnsignedLong(panBlock, 16);
            long clearPinBlock = pinBlockVal ^ panBlockVal;
            String expectedPinBlock = String.format("%016X", clearPinBlock);
            Log.d(TAG, "EXPECTED ISO-0 PIN block (clear, before encryption): " + expectedPinBlock);
            Log.d(TAG, "If SDK produces this value (decrypted), clear PAN is being used.");
        } catch (Exception e) {
            Log.e(TAG, "Calculation error: " + e.getMessage());
        }

        Log.d(TAG, "=== END TEST ===");
    }

    /**
     * Dump current EMV tags to log - call this after a card has been read.
     * This checks what PAN data is accessible at the current moment.
     */
    public void dumpCurrentEmvTags() {
        Log.d(TAG, "=== DUMP CURRENT EMV TAGS ===");

        try {
            // Tag 5A - Application PAN
            TlvData tag5A = new TlvData();
            tag5A.tag = 0x5A;
            tag5A.len = 20;
            tag5A.value = new byte[20];
            int rtn = emv.dataGet(tag5A);
            Log.d(TAG, "Tag 5A (PAN): rtn=" + String.format("0x%08X", rtn) + ", len=" + tag5A.len);
            if (rtn == 0 && tag5A.len > 0) {
                String hex = Converter.byteArray2HexString(tag5A.value, tag5A.len);
                Log.d(TAG, "  5A hex = " + hex);
                Log.d(TAG, "  Contains * (masked): " + hex.contains("*"));
            }

            // Tag 57 - Track 2 Equivalent Data
            TlvData tag57 = new TlvData();
            tag57.tag = 0x57;
            tag57.len = 40;
            tag57.value = new byte[40];
            rtn = emv.dataGet(tag57);
            Log.d(TAG, "Tag 57 (Track2): rtn=" + String.format("0x%08X", rtn) + ", len=" + tag57.len);
            if (rtn == 0 && tag57.len > 0) {
                String hex = Converter.byteArray2HexString(tag57.value, tag57.len);
                Log.d(TAG, "  57 hex = " + hex);
                Log.d(TAG, "  Contains * (masked): " + hex.contains("*"));
            }

            // Tag DF32 - Masked PAN (ASCII)
            TlvData tagDF32 = new TlvData();
            tagDF32.tag = 0xDF32;
            tagDF32.len = 40;
            tagDF32.value = new byte[40];
            rtn = emv.dataGet(tagDF32);
            Log.d(TAG, "Tag DF32 (Masked PAN): rtn=" + String.format("0x%08X", rtn) + ", len=" + tagDF32.len);
            if (rtn == 0 && tagDF32.len > 0) {
                String val = new String(tagDF32.value, 0, tagDF32.len);
                Log.d(TAG, "  DF32 = " + val);
            }

            // Tag DF33 - Encrypted Track 2
            TlvData tagDF33 = new TlvData();
            tagDF33.tag = 0xDF33;
            tagDF33.len = 100;
            tagDF33.value = new byte[100];
            rtn = emv.dataGet(tagDF33);
            Log.d(TAG, "Tag DF33 (Encrypted Track2): rtn=" + String.format("0x%08X", rtn) + ", len=" + tagDF33.len);
            if (rtn == 0 && tagDF33.len > 0) {
                String hex = Converter.byteArray2HexString(tagDF33.value, tagDF33.len);
                Log.d(TAG, "  DF33 hex (first 40 chars) = " + (hex.length() > 40 ? hex.substring(0, 40) + "..." : hex));
            }

            // Tag DF35 - Clear Track 2 (only available with P2PE?)
            TlvData tagDF35 = new TlvData();
            tagDF35.tag = 0xDF35;
            tagDF35.len = 40;
            tagDF35.value = new byte[40];
            rtn = emv.dataGet(tagDF35);
            Log.d(TAG, "Tag DF35 (Clear Track2): rtn=" + String.format("0x%08X", rtn) + ", len=" + tagDF35.len);
            if (rtn == 0 && tagDF35.len > 0) {
                String hex = Converter.byteArray2HexString(tagDF35.value, tagDF35.len);
                Log.d(TAG, "  DF35 hex = " + hex);
                Log.d(TAG, "  Contains * (masked): " + hex.contains("*"));
            }

            // Log what we have in GlobalPara
            Log.d(TAG, "--- GlobalPara State ---");
            Log.d(TAG, "  asciiPAN = " + GlobalPara.asciiPAN);
            Log.d(TAG, "  atmTrack2Data = " + GlobalPara.atmTrack2Data);
            Log.d(TAG, "  atmClearPan = " + GlobalPara.atmClearPan);
            Log.d(TAG, "  atmEncryptedPinBlock = " + GlobalPara.atmEncryptedPinBlock);

            Log.d(TAG, "=== END EMV TAG DUMP ===");

        } catch (Exception e) {
            Log.e(TAG, "Dump error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Verify PIN block format by comparing with known test values.
     * Call this to test if we can create correct ISO-0 PIN blocks.
     */
    public void verifyPinBlockFormat() {
        Log.d(TAG, "=== VERIFY PIN BLOCK FORMAT ===");

        // Known test values
        String testPin = "1234";
        String testPan = "4430410010008318";

        // Calculate expected ISO-0 (Format 0) clear PIN block
        // PIN Block = 0 + N + PIN + F-pad XOR 0000 + PAN[3..14]
        String pinPart = "0" + testPin.length() + testPin;
        while (pinPart.length() < 16) {
            pinPart += "F";
        }
        Log.d(TAG, "PIN part (before XOR): " + pinPart);

        String panPart = "0000" + testPan.substring(3, 15); // Last 12 digits before check digit
        Log.d(TAG, "PAN part (for XOR):    " + panPart);

        try {
            long pinVal = Long.parseUnsignedLong(pinPart, 16);
            long panVal = Long.parseUnsignedLong(panPart, 16);
            long clearBlock = pinVal ^ panVal;
            String expectedClearPinBlock = String.format("%016X", clearBlock);
            Log.d(TAG, "Expected clear PIN block (ISO-0): [masked]");

            // If we have a PIN block from the SDK, compare
            if (GlobalPara.atmEncryptedPinBlock != null && !GlobalPara.atmEncryptedPinBlock.isEmpty()) {
                Log.d(TAG, "Current SDK PIN block: " + GlobalPara.atmEncryptedPinBlock);
                Log.d(TAG, "(Note: SDK block is encrypted, can't compare directly)");
            } else {
                Log.d(TAG, "No PIN block in GlobalPara yet");
            }

        } catch (Exception e) {
            Log.e(TAG, "Calculation error: " + e.getMessage());
        }

        Log.d(TAG, "=== END PIN BLOCK VERIFY ===");
    }

    /**
     * Run EMV Cryptogram Diagnostic Test on startup.
     * Results are logged to logcat with tag "EmvCryptogramTest".
     */
    private void runEmvDiagnosticTest() {
        try {
            Log.d(TAG, "");
            Log.d(TAG, "========================================");
            Log.d(TAG, "  RUNNING EMV CRYPTOGRAM DIAGNOSTIC");
            Log.d(TAG, "========================================");
            Log.d(TAG, "");

            EmvCryptogramTest test = new EmvCryptogramTest(this);
            String results = test.runAllTests();

            // Results are already logged by the test class
            Log.d(TAG, "");
            Log.d(TAG, "========================================");
            Log.d(TAG, "  EMV DIAGNOSTIC COMPLETE - CHECK ABOVE");
            Log.d(TAG, "========================================");
            Log.d(TAG, "");
        } catch (Exception e) {
            Log.e(TAG, "EMV Diagnostic Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}