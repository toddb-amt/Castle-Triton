package castech.emvtxn;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

public class Fragment_page_main_menu extends Fragment {
    private static final String TAG = "MainMenu";

    private static MainActivity mainActivity = null;
    private Button btnWithdrawal;
    private Button btnBalanceInquiry;
    private Button btnHostTotals;
    private View btnAdmin;

    // Admin access: requires 5 taps within 3 seconds
    private int adminTapCount = 0;
    private long lastAdminTapTime = 0;
    private static final int ADMIN_TAP_THRESHOLD = 5;
    private static final long ADMIN_TAP_TIMEOUT_MS = 3000;

    // Constructor that receives MainActivity reference
    public Fragment_page_main_menu(MainActivity activity) {
        mainActivity = activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        try {
            View view = inflater.inflate(R.layout.fragment_page_main_menu, container, false);

            // Wire up Withdrawal button
            btnWithdrawal = view.findViewById(R.id.btn_withdrawal);
            btnWithdrawal.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Set ATM withdrawal mode and navigate to amount selection
                    GlobalPara.atmBalanceInquiryMode = false;
                    if (mainActivity != null) {
                        mainActivity.navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
                    }
                }
            });

            // Wire up Balance Inquiry button
            btnBalanceInquiry = view.findViewById(R.id.btn_balance_inquiry);
            btnBalanceInquiry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Set balance inquiry mode and navigate to transaction
                    GlobalPara.atmBalanceInquiryMode = true;
                    GlobalPara.atmSelectedAmount = "0.00";
                    GlobalPara.atmFee = "0.00";
                    GlobalPara.atmTotal = "0.00";
                    GlobalPara.strAmount = "0"; // Amount in cents for EMV SDK
                    GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING; // Default to Checking
                    if (mainActivity != null) {
                        mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
                    }
                }
            });

            // Wire up hidden Admin button (5 taps in top-right corner)
            btnAdmin = view.findViewById(R.id.btn_admin);
            if (btnAdmin != null) {
                btnAdmin.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        handleAdminTap();
                    }
                });
            }

            return view;
        } catch (Exception e) {
            // Fallback to simple view on error
            android.util.Log.e(TAG, "Error creating main menu: " + e.getMessage());
            android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
            fallback.setBackgroundColor(0xFFFF5722); // Orange = main menu error
            return fallback;
        }
    }

    /**
     * Handle admin button tap - requires 5 taps within 3 seconds
     * This is a standard ATM admin access pattern
     */
    private void handleAdminTap() {
        long currentTime = System.currentTimeMillis();

        // Reset counter if too much time has passed
        if (currentTime - lastAdminTapTime > ADMIN_TAP_TIMEOUT_MS) {
            adminTapCount = 0;
        }

        adminTapCount++;
        lastAdminTapTime = currentTime;

        android.util.Log.d(TAG, "Admin tap: " + adminTapCount + "/" + ADMIN_TAP_THRESHOLD);

        if (adminTapCount >= ADMIN_TAP_THRESHOLD) {
            adminTapCount = 0;
            // Navigate to admin screen (PIN protection is in the fragment)
            if (mainActivity != null) {
                android.util.Log.d(TAG, "Opening admin screen...");
                mainActivity.navigateToPage(GlobalDef.d_PAGE_SETTING);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reset ATM transaction state when returning to main menu
        // This ensures clean state for next transaction
        GlobalPara.resetATMTransactionState();
        android.util.Log.d("MainMenu", "onResume - ATM state reset");
    }
}
