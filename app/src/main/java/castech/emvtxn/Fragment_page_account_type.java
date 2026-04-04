package castech.emvtxn;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

/**
 * Account Type Selection Fragment
 * Allows user to select Checking, Savings, or Credit account
 * for both Withdrawal and Balance Inquiry transactions.
 */
public class Fragment_page_account_type extends Fragment {

    private static final String TAG = "AccountType";

    private TextView txvTransactionType;
    private Button btnChecking;
    private Button btnSavings;
    private Button btnCredit;
    private Button btnCancel;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_page_account_type, container, false);

        // Initialize views
        txvTransactionType = view.findViewById(R.id.txvTransactionType);
        btnChecking = view.findViewById(R.id.btnChecking);
        btnSavings = view.findViewById(R.id.btnSavings);
        btnCredit = view.findViewById(R.id.btnCredit);
        btnCancel = view.findViewById(R.id.btnCancel);

        // Update transaction type display
        if (GlobalPara.atmBalanceInquiryMode) {
            txvTransactionType.setText("BALANCE INQUIRY");
        } else {
            String amount = GlobalPara.atmSelectedAmount;
            txvTransactionType.setText("WITHDRAWAL: $" + amount);
        }

        // Set up button click listeners
        btnChecking.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAccountType(GlobalPara.ATM_ACCOUNT_CHECKING);
            }
        });

        btnSavings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAccountType(GlobalPara.ATM_ACCOUNT_SAVINGS);
            }
        });

        btnCredit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAccountType(GlobalPara.ATM_ACCOUNT_CREDIT);
            }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelTransaction();
            }
        });

        Log.d(TAG, "Account Type selection page loaded");
        return view;
    }

    private void selectAccountType(int accountType) {
        GlobalPara.atmAccountType = accountType;

        String accountName;
        switch (accountType) {
            case GlobalPara.ATM_ACCOUNT_SAVINGS:
                accountName = "Savings";
                break;
            case GlobalPara.ATM_ACCOUNT_CREDIT:
                accountName = "Credit";
                break;
            case GlobalPara.ATM_ACCOUNT_CHECKING:
            default:
                accountName = "Checking";
                break;
        }

        Log.d(TAG, "Account type selected: " + accountName + " (" + accountType + ")");

        // Navigate to transaction page for card read
        if (GlobalPara.mainActivity != null) {
            GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
        }
    }

    private void cancelTransaction() {
        Log.d(TAG, "Transaction cancelled from account type selection");

        // Reset transaction state
        GlobalPara.resetATMTransactionState();

        // Navigate back to main menu
        if (GlobalPara.mainActivity != null) {
            GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
        }
    }
}
