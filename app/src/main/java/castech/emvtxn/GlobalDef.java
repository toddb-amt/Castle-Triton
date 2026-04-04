package castech.emvtxn;

public class GlobalDef
{
	// Page navigation constants - ATM flow (6 pages total)
	public static final int d_PAGE_IDLE             = 0;
	public static final int d_PAGE_MAIN_MENU        = 1;
	public static final int d_PAGE_AMOUNT_SELECTION = 2;
	public static final int d_PAGE_TRANSACTION      = 3;
	public static final int d_PAGE_RECEIPT          = 4;
	public static final int d_PAGE_SETTING          = 5;  // Admin screen

	// Account Type page - DISABLED for now (was causing ANR issues)
	// Re-enable after core flow is stable
	// public static final int d_PAGE_ACCOUNT_TYPE     = 3;

	// Legacy page constants - redirected to TRANSACTION for SDK compatibility
	// These are used by SDK event handlers for PIN entry display
	public static final int d_PAGE_PINPAD_EX        = d_PAGE_TRANSACTION;
	public static final int d_PAGE_MANUAL_ENTRY     = d_PAGE_TRANSACTION;

	// Card entry mode constants
	public static final byte d_ENTRY_MODE_CT		= 0x01;
	public static final byte d_ENTRY_MODE_MSR		= 0x02;
	public static final byte d_ENTRY_MODE_CL 		= 0x03;


}
