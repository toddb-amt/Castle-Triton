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
    /** The six preset buttons, in layout order = AmountPresets.PRESET_CENTS order. */
    private final Button[] presetButtons = new Button[AmountPresets.PRESET_CENTS.length];
    private Button btnCustomAmount, btnCheckBalance;
    private Button btnCancel, btnContinue;
    private TextView txvSelectedAmount, txvFee, txvTotal;

    private View view;

    // Selected amount tracking
    private double selectedAmount = 0.0;
    /** AMT-03: what the customer typed before AMT-01 rounding; equals selectedAmount for presets. */
    private double enteredAmount = 0.0;
    /** True when the selection came from the custom-amount dialog (the only path that rounds). */
    private boolean customEntry = false;
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
        // Amount preset buttons — labelled from AmountPresets so the list lives in one place
        int[] presetIds = {R.id.btnPreset0, R.id.btnPreset1, R.id.btnPreset2,
                           R.id.btnPreset3, R.id.btnPreset4, R.id.btnPreset5};
        for (int i = 0; i < presetButtons.length; i++) {
            presetButtons[i] = view.findViewById(presetIds[i]);
            if (presetButtons[i] != null) {
                presetButtons[i].setText(currencyFormat.format(AmountPresets.PRESET_CENTS[i] / 100.0)
                        .replace(".00", ""));
            }
        }

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
        // Preset amount buttons (exact amounts; never rounded)
        for (int i = 0; i < presetButtons.length; i++) {
            final double amount = AmountPresets.PRESET_CENTS[i] / 100.0;
            if (presetButtons[i] != null) {
                presetButtons[i].setOnClickListener(v -> selectAmount(amount));
            }
        }
        applyPresetLimits();

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
                    // AMT-03: one breakdown; every string and the chip amount from the SAME
                    // integers. The sale is what the customer typed (enteredAmount); the
                    // withdrawal is that rounded up to the step (AMT-01) — identical to the
                    // selectedAmount the dialog already rounded, so charges are unchanged,
                    // and the difference is recorded as cash back for the journal and the
                    // portal. ONLY a custom entry rounds: a preset is charged exactly as its
                    // button says even when min_amount does not divide it ($60 with a $25
                    // minimum stays $60; review C1) — exactly as since 6.2.8.
                    long saleCents = Money.toCents(enteredAmount > 0 ? enteredAmount : selectedAmount);
                    AmountBreakdown amounts;
                    try {
                        amounts = AmountBreakdown.of(saleCents, 0L,
                                Money.toCents(GlobalPara.atmMinAmount), customEntry,
                                GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
                    } catch (IllegalArgumentException bad) {
                        // Fee configuration that cannot be charged (review I4). Never crash the kiosk.
                        android.util.Log.e("AmountSelection", "Cannot build amounts: " + bad.getMessage());
                        showErrorDialog("Configuration error", "This terminal's fee settings are invalid. Please contact support.");
                        return;
                    }
                    GlobalPara.atmAmounts = amounts;
                    GlobalPara.atmSelectedAmount = Money.dollars(amounts.withdrawal);
                    GlobalPara.atmFee = Money.dollars(amounts.fee);
                    GlobalPara.atmTotal = Money.dollars(amounts.total);
                    // Chip amount (9F02) = total in cents, no decimals
                    GlobalPara.strAmount = amounts.chipAmountCents();

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

    /**
     * Greys out presets outside the configured min/max (a $10 button under a $20 minimum
     * would only ever produce "Amount too low"). Called on setup and whenever the screen
     * is shown, since the limits can change via CasHUB.
     */
    private void applyPresetLimits() {
        long minCents = Math.round(GlobalPara.atmMinAmount * 100.0);
        long maxCents = Math.round(GlobalPara.atmMaxAmount * 100.0);
        for (int i = 0; i < presetButtons.length; i++) {
            Button b = presetButtons[i];
            if (b == null) continue;
            boolean offered = AmountPresets.isOffered(AmountPresets.PRESET_CENTS[i], minCents, maxCents);
            b.setEnabled(offered);
            b.setAlpha(offered ? 1f : 0.35f);
        }
    }

    /** A preset: charged exactly as the button says, never rounded. */
    private void selectAmount(double amount) {
        selectAmount(amount, amount, false);
    }

    /**
     * @param amount  the withdrawal candidate (a preset, or a custom amount already rounded to the step)
     * @param entered what the customer actually asked for (AMT-03: recorded as the sale)
     * @param custom  true from the custom-amount dialog — the only selection that rounds to the step
     */
    private void selectAmount(double amount, double entered, boolean custom) {
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
        enteredAmount = entered > 0 ? entered : amount;
        customEntry = custom;
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
                        double entered = Double.parseDouble(amountStr);
                        // The minimum is the step: round UP to its next multiple
                        // (min $10: $12.50 → $20, $5 → $10). The rounded amount is
                        // what the screen shows and what goes to the host; the
                        // customer still has to press Continue. Presets are exact
                        // and never go through this.
                        double amount = AmountRounding.roundUpToStep(entered, GlobalPara.atmMinAmount);
                        if (amount != entered) {
                            android.util.Log.d("AmountSelection", "Custom amount " + entered
                                + " rounded up to " + amount + " (step " + GlobalPara.atmMinAmount + ")");
                        }
                        if (amount != entered && getContext() != null) {
                            android.widget.Toast.makeText(getContext(),
                                "Rounded up to " + currencyFormat.format(amount)
                                    + " (withdrawals in " + currencyFormat.format(GlobalPara.atmMinAmount) + " steps)",
                                android.widget.Toast.LENGTH_LONG).show();
                        }
                        selectAmount(amount, entered, true);
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

    /** Fee in dollars for display — derived from the rounded cents so the screen, the receipt and the wire agree. */
    private double calculateFee(double amount) {
        return Money.feeCents(Money.toCents(amount), GlobalPara.atmUseFlatFee,
                GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee) / 100.0;
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
        enteredAmount = 0.0;
        customEntry = false;
        updateDisplay();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyPresetLimits();   // limits may have changed via CasHUB since setup
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
        GlobalPara.strAmount = "0";   // chip amount in cents — "0.00" is not a valid cents string

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
