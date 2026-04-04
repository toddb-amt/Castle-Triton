package castech.emvtxn;

public class GlobalDef
{
	// Page navigation constants - ATM flow (6 pages total)
	static final int d_PAGE_IDLE             = 0;
	static final int d_PAGE_MAIN_MENU        = 1;
	static final int d_PAGE_AMOUNT_SELECTION = 2;
	static final int d_PAGE_TRANSACTION      = 3;
	static final int d_PAGE_RECEIPT          = 4;
	static final int d_PAGE_SETTING          = 5;  // Admin screen

	// Account Type page - DISABLED for now (was causing ANR issues)
	// Re-enable after core flow is stable
	// static final int d_PAGE_ACCOUNT_TYPE     = 3;

	// Legacy page constants - redirected to TRANSACTION for SDK compatibility
	// These are used by SDK event handlers for PIN entry display
	static final int d_PAGE_PINPAD_EX        = d_PAGE_TRANSACTION;
	static final int d_PAGE_MANUAL_ENTRY     = d_PAGE_TRANSACTION;

	// Card entry mode constants
	static final byte d_ENTRY_MODE_CT		= 0x01;
	static final byte d_ENTRY_MODE_MSR		= 0x02;
	static final byte d_ENTRY_MODE_CL 		= 0x03;


}
