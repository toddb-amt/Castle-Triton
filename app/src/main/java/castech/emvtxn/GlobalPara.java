package castech.emvtxn;

import android.content.IntentFilter;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

public class GlobalPara
{
	public static MainActivity mainActivity;
	public static boolean appListOK = false;

	public static short appSelectedIndex = 0;
	public static byte appSelectedConfirmOK = 0;
	public static byte layoutViewCreate = 0;
	public static byte useSeparateSelectAPI = 1;

	public static boolean pinBypassActionOK = false;
	public static byte pinBypassActionIndex = 0;

	public static String asciiPAN;
    public static String cardType;
	public static String DateTime;
	public static String JsonFile = "bin.json";
	public static String expdateFormat = "";
	public static String settingRtn = "";

	public static int[] token;
	public static int token_i;
	public static boolean isTokenOK = false;

	public static byte useVirtualPinEx = 1;

	protected static boolean isContactInterfaceAvaliable = false;
	protected static boolean isContactlessInterfaceAvaliable = false;
	protected static boolean isMSRInterfaceAvaliable = false;
	protected static boolean isEMVEDLAvaliable = true;

	public static boolean isQuickChipTransaction = false;

	public static boolean isInitThreadFinish = false;

	//For UI resource
	public static ClsAudioInidcator audio;
	public static AlertDialog alertDialog;

	public static EditText edtamount;
	public static String strAmount = "0.00";

	public static short transactionResult;
	public static boolean isNeedSignature;

	public static int remainingCounter;
	// Online PIN key location - Use C000/0000 where our DUKPT key is injected
	// Castle support confirmed: modify to match your injected key location
	// Same key used for both card data encryption and online PIN (like their sample)
	public static final int onlinePinKeySet = 0x0000C000;
	public static final int onlinePinKeyIndex = 0x00000000;

	public static String tag = "TAG";
	public static ClessLed clLED;
	public static boolean isEuropeUIType = true;
	//public static boolean isEuropeUIType = false;

	public static boolean MEInitOK = false;

	public static boolean isScrnBrdcstRecverReg = false;
	public static ClsScreenBroadcastReceiver scrnBrdcstRecver = null;
	public static IntentFilter scrnIntentFilter = null;

	// ATM Mode flag - when true, forces online-only and PIN required
	public static boolean atmMode = true;

	// ATM-specific parameters
	public static String atmSelectedAmount = "0.00";
	public static String atmFee = "0.00";
	public static String atmTotal = "0.00";
	public static boolean atmTransactionComplete = false;
	public static String atmTransactionId = "";
	public static String atmLastFourDigits = "";

	// Account type selection - used in Processing Code Field 3
	// Values match ISO 8583 account type codes
	public static final int ATM_ACCOUNT_DEFAULT = 0;   // 00 - Default/Unspecified
	public static final int ATM_ACCOUNT_SAVINGS = 10;  // 10 - Savings
	public static final int ATM_ACCOUNT_CHECKING = 20; // 20 - Checking
	public static final int ATM_ACCOUNT_CREDIT = 30;   // 30 - Credit
	public static int atmAccountType = ATM_ACCOUNT_CHECKING; // Default to Checking

	/**
	 * Converts atmAccountType to Hyosung protocol account code.
	 * Maps: CHECKING(20) -> "CA", SAVINGS(10) -> "SA", CREDIT(30) -> "CR"
	 * @return Hyosung account type code (CA, SA, or CR)
	 */
	public static String getHyosungAccountType() {
		switch (atmAccountType) {
			case ATM_ACCOUNT_SAVINGS:
				return "SA";
			case ATM_ACCOUNT_CREDIT:
				return "CR";
			case ATM_ACCOUNT_CHECKING:
			default:
				return "CA";
		}
	}

	/**
	 * Gets a human-readable account type name for display.
	 * @return Account type name (Checking, Savings, or Credit)
	 */
	public static String getAccountTypeName() {
		switch (atmAccountType) {
			case ATM_ACCOUNT_SAVINGS:
				return "Savings";
			case ATM_ACCOUNT_CREDIT:
				return "Credit";
			case ATM_ACCOUNT_CHECKING:
			default:
				return "Checking";
		}
	}

	// Fee configuration
	public static boolean atmUseFlatFee = true;
	public static double atmFlatFeeAmount = 3.00;
	public static double atmPercentageFee = 0.0;

	// Withdrawal limits
	public static double atmMinAmount = 20.00;
	public static double atmMaxAmount = 500.00;

	// =========================================================================
	// ATM PIN Encryption Configuration
	// =========================================================================

	// PIN block format: "FORMAT0" (ISO 9564-1 Format 0)
	// Using DUKPT for PIN encryption - processor needs matching BDK
	public static String atmPinBlockFormat = "FORMAT0";  // Format 0 with DUKPT

	// DUKPT settings - ENABLED for PIN encryption
	// Key material is managed via Key Injection Tool / KeyBRIDGE HSM
	// See DUKPT_KEY_REFERENCE.md for key ceremony documentation
	public static boolean atmDukptEnabled = true;  // DUKPT ENABLED for PIN
	public static int atmDukptKeySet = 0x0000C000;    // DUKPT key set - C000 has PIN attribute, C001 only has DECRYPT
	public static int atmDukptKeyIndex = 0x00000000;  // DUKPT key index
	public static String atmDukptKsn = "";            // KSN captured after PIN encryption
	public static String atmEncryptedTrack2 = "";     // Encrypted Track2 from DF33

	// =========================================================================
	// Kiosk Mode Configuration
	// =========================================================================
	public static boolean atmKioskMode = true;         // Lock app as default, disable nav buttons
	public static boolean atmDisableNavButtons = true;  // Disable Home/Back/Search
	public static boolean atmAutoStartOnBoot = true;    // Set as default app (launches on boot)
	public static boolean atmScreenAlwaysOn = true;     // Prevent screen timeout
	public static boolean atmAutoReboot = false;        // Auto reboot daily
	public static int atmAutoRebootHour = 3;            // Reboot hour (24h format)
	public static int atmAutoRebootMinute = 0;          // Reboot minute

	// ATM Host Configuration
	// =========================================================================

	// Processor selection: "DNS", "SWITCH_COMMERCE", "EFX", "CARDTRONICS", etc.
	public static String atmProcessorType = "DNS";

	// Host connection settings
	public static String atmHostAddress = "";  // e.g., "atm.processor.com"
	public static int atmHostPort = 8002;      // Default DNS port
	public static String atmTerminalId = "";   // e.g., "TERM001"
	public static boolean atmUseTls = true;    // Use TLS/SSL for connection

	// Current transaction data (captured during card read)
	public static String atmTrack2Data = "";
	public static String atmEncryptedPinBlock = "";
	public static String atmEmvData = "";
	public static String atmSensitiveEmvData = "";  // Separate 5A/57 data (clear PAN for PIN translation)
	public static int atmEntryMode = 0;  // 0=unknown, 1=contact, 2=contactless, 3=MSR
	public static String atmClearPan = "";  // Clear PAN from server (for PIN block creation)

	// PIN collected after transaction flag (for No-CVM cryptogram approach)
	// When true, PIN was collected AFTER Generate AC (cryptogram already exists)
	// This is needed because SDK can't do internal PIN with DUKPT (error 0x00001003)
	public static boolean atmPinCollectedPostTransaction = false;

	// Host response data
	public static String atmAuthCode = "";
	public static String atmReferenceNumber = "";
	public static String atmAuthDate = "";
	public static String atmAuthTime = "";
	public static String atmResponseCode = "";
	public static String atmResponseMessage = "";
	public static long atmAccountBalance = 0;
	public static long atmAvailableBalance = 0;

	// EMV host response data for txnCompletion (tags 91, 71, 72)
	public static byte[] atmIssuerAuthData = null;      // Tag 91 - Issuer Authentication Data
	public static byte[] atmIssuerScript71 = null;      // Tag 71 - Issuer Script Template 1
	public static byte[] atmIssuerScript72 = null;      // Tag 72 - Issuer Script Template 2

	// Transaction state
	public static boolean atmHostCallInProgress = false;
	public static boolean atmHostCallSuccess = false;
	public static boolean atmNeedsReversal = false;

	// Balance inquiry mode
	public static boolean atmBalanceInquiryMode = false;

	// Transaction in progress flag - prevents starting new transaction while one is active
	public static boolean atmTransactionInProgress = false;

	/**
	 * Reset all ATM transaction state.
	 * Call this before starting a new transaction or when returning to main menu.
	 */
	public static void resetATMTransactionState() {
		android.util.Log.d("GlobalPara", "resetATMTransactionState() called");

		// Reset amount selection
		atmSelectedAmount = "0.00";
		atmFee = "0.00";
		atmTotal = "0.00";
		strAmount = "0";

		// Reset transaction mode
		atmBalanceInquiryMode = false;
		atmTransactionComplete = false;
		atmTransactionInProgress = false;
		atmAccountType = ATM_ACCOUNT_CHECKING; // Default to Checking

		// Reset card data
		atmTrack2Data = "";
		atmEncryptedPinBlock = "";
		atmEmvData = "";
		atmSensitiveEmvData = "";
		atmEntryMode = 0;
		atmLastFourDigits = "";
		atmTransactionId = "";
		atmClearPan = "";
		atmDukptKsn = "";
		atmEncryptedTrack2 = "";
		atmPinCollectedPostTransaction = false;

		// Reset host response
		atmAuthCode = "";
		atmReferenceNumber = "";
		atmAuthDate = "";
		atmAuthTime = "";
		atmResponseCode = "";
		atmResponseMessage = "";
		atmAccountBalance = 0;
		atmAvailableBalance = 0;
		atmIssuerAuthData = null;
		atmIssuerScript71 = null;
		atmIssuerScript72 = null;

		// Reset host call state
		atmHostCallInProgress = false;
		atmHostCallSuccess = false;
		atmNeedsReversal = false;

		// Reset EMV-related state
		isQuickChipTransaction = false;
		pinBypassActionOK = false;
		pinBypassActionIndex = 0;
		appSelectedIndex = 0;
		appSelectedConfirmOK = 0;
		asciiPAN = null;
		cardType = null;

		android.util.Log.d("GlobalPara", "ATM transaction state reset complete");
	}

	/**
	 * Partial reset - only resets host response but keeps amount/mode.
	 * Call this when retrying a transaction after an error.
	 */
	public static void resetATMHostResponse() {
		android.util.Log.d("GlobalPara", "resetATMHostResponse() called");

		// Reset card data for retry
		atmTrack2Data = "";
		atmEncryptedPinBlock = "";
		atmEmvData = "";
		atmSensitiveEmvData = "";
		atmEntryMode = 0;
		atmLastFourDigits = "";
		atmDukptKsn = "";
		atmEncryptedTrack2 = "";

		// Reset host response
		atmAuthCode = "";
		atmReferenceNumber = "";
		atmResponseCode = "";
		atmResponseMessage = "";

		// Reset host call state
		atmHostCallInProgress = false;
		atmHostCallSuccess = false;
		atmTransactionComplete = false;
		atmTransactionInProgress = false;
	}
}