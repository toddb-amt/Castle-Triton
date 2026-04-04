package castech.emvtxn;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.text.InputType;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;

import java.text.DecimalFormat;

public class Fragment_page_amount_selection extends Fragment {

    private static MainActivity mainActivity = null;

    // UI Components
    private Button btn20, btn40, btn60, btn100, btn200, btn500;
    private Button btnCustomAmount, btnCheckBalance;
    private Button btnCancel, btnContinue;
    private TextView txvSelectedAmount, txvFee, txvTotal;

    private View view;

    // Selected amount tracking
    private double selectedAmount = 0.0;
    private DecimalFormat currencyFormat = new DecimalFormat("$0.00");

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        try {
            view = inflater.inflate(R.layout.fragment_page_amount_selection, container, false);

            // Initialize UI components
            initializeComponents();

            // Set up button click listeners
            setupButtonListeners();

            // Initialize display
            updateDisplay();

            return view;
        } catch (Exception e) {
            android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
            fallback.setBackgroundColor(0xFFFF9800); // Orange = error
            return fallback;
        }
    }

    public Fragment_page_amount_selection() {
        // Required empty public constructor
    }

    public Fragment_page_amount_selection(MainActivity activity) {
        if (this.mainActivity == null) {
            this.mainActivity = activity;
        }
    }

    private void initializeComponents() {
        // Amount preset buttons
        btn20 = view.findViewById(R.id.btn20);
        btn40 = view.findViewById(R.id.btn40);
        btn60 = view.findViewById(R.id.btn60);
        btn100 = view.findViewById(R.id.btn100);
        btn200 = view.findViewById(R.id.btn200);
        btn500 = view.findViewById(R.id.btn500);

        // Special buttons
        btnCustomAmount = view.findViewById(R.id.btnCustomAmount);
        btnCheckBalance = view.findViewById(R.id.btnCheckBalance);
        btnCancel = view.findViewById(R.id.btnCancel);
        btnContinue = view.findViewById(R.id.btnContinue);

        // Display text views
        txvSelectedAmount = view.findViewById(R.id.txvSelectedAmount);
        txvFee = view.findViewById(R.id.txvFee);
        txvTotal = view.findViewById(R.id.txvTotal);
    }

    private void setupButtonListeners() {
        // Preset amount buttons
        btn20.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(20.0); }
        });
        btn40.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(40.0); }
        });
        btn60.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(60.0); }
        });
        btn100.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(100.0); }
        });
        btn200.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(200.0); }
        });
        btn500.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectAmount(500.0); }
        });

        // Custom amount button
        btnCustomAmount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showCustomAmountDialog(); }
        });

        // Check Balance button
        btnCheckBalance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startBalanceInquiry(); }
        });

        // Cancel button
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Reset and return to idle screen
                resetSelection();
                if (mainActivity != null) {
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_IDLE);
                }
            }
        });

        // Continue button
        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedAmount > 0) {
                    // Store the selected amount and fee in global parameters
                    GlobalPara.atmSelectedAmount = String.format("%.2f", selectedAmount);
                    GlobalPara.atmFee = String.format("%.2f", calculateFee(selectedAmount));
                    GlobalPara.atmTotal = String.format("%.2f", selectedAmount + calculateFee(selectedAmount));
                    // Convert total to cents for EMV SDK (no decimals)
                    int totalCents = (int) ((selectedAmount + calculateFee(selectedAmount)) * 100);
                    GlobalPara.strAmount = String.valueOf(totalCents);

                    android.util.Log.d("AmountSelection", "Continue clicked - amount=" + GlobalPara.atmSelectedAmount +
                        ", balanceInquiry=" + GlobalPara.atmBalanceInquiryMode);

                    // Navigate to transaction (account type defaults to Checking)
                    GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING;
                    if (mainActivity != null) {
                        mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
                    } else {
                        android.util.Log.e("AmountSelection", "mainActivity is null!");
                    }
                }
            }
        });
    }

    private void selectAmount(double amount) {
        // Validate amount against limits
        if (amount < GlobalPara.atmMinAmount) {
            showErrorDialog("Amount too low",
                "Minimum withdrawal amount is " + currencyFormat.format(GlobalPara.atmMinAmount));
            return;
        }

        if (amount > GlobalPara.atmMaxAmount) {
            showErrorDialog("Amount too high",
                "Maximum withdrawal amount is " + currencyFormat.format(GlobalPara.atmMaxAmount));
            return;
        }

        selectedAmount = amount;
        updateDisplay();
    }

    private void showCustomAmountDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Enter Custom Amount");

        // Set up the input
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Enter amount");
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String amountStr = input.getText().toString();
                if (!amountStr.isEmpty()) {
                    try {
                        double amount = Double.parseDouble(amountStr);
                        selectAmount(amount);
                    } catch (NumberFormatException e) {
                        showErrorDialog("Invalid Amount", "Please enter a valid number");
                    }
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void showErrorDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private double calculateFee(double amount) {
        if (GlobalPara.atmUseFlatFee) {
            return GlobalPara.atmFlatFeeAmount;
        } else {
            return amount * (GlobalPara.atmPercentageFee / 100.0);
        }
    }

    private void updateDisplay() {
        // Update selected amount display
        txvSelectedAmount.setText(currencyFormat.format(selectedAmount));

        // Calculate and display fee
        double fee = calculateFee(selectedAmount);
        txvFee.setText(currencyFormat.format(fee));

        // Calculate and display total
        double total = selectedAmount + fee;
        txvTotal.setText(currencyFormat.format(total));

        // Enable/disable continue button based on selection
        btnContinue.setEnabled(selectedAmount > 0);
    }

    private void resetSelection() {
        selectedAmount = 0.0;
        updateDisplay();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Only reset if user is actually viewing this page
        // Don't reset here - it interferes with Balance Inquiry mode
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        // Reset only when user navigates TO this screen
        try {
            if (isVisibleToUser && isAdded() && getActivity() != null) {
                resetSelection();
                GlobalPara.atmBalanceInquiryMode = false;
            }
        } catch (Exception e) {
            // Ignore - fragment not ready
        }
    }

    /**
     * Starts a balance inquiry transaction.
     * Sets the balance inquiry flag and navigates to the transaction page.
     */
    private void startBalanceInquiry() {
        // Set balance inquiry mode
        GlobalPara.atmBalanceInquiryMode = true;

        // Clear any previous amount selection (balance inquiry has no amount)
        GlobalPara.atmSelectedAmount = "0.00";
        GlobalPara.atmFee = "0.00";
        GlobalPara.atmTotal = "0.00";
        GlobalPara.strAmount = "0.00";

        // Navigate to transaction (account type defaults to Checking)
        GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING;
        if (mainActivity != null) {
            mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
        }
    }

    public View toView() {
        return view;
    }
}
