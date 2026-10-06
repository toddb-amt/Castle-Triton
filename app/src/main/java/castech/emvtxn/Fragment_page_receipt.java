package castech.emvtxn;

import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

public class Fragment_page_receipt extends Fragment {

    private static final String TAG = "Fragment_page_receipt";
    private static MainActivity mainActivity = null;

    // UI Components
    private TextView txvResultIcon;
    private TextView txvResultMessage;
    private TextView txvWithdrawalAmount;
    private TextView txvFeeAmount;
    private TextView txvTotalAmount;
    private TextView txvCardInfo;
    private TextView txvDateTime;
    private TextView txvTransactionId;
    private TextView txvAuthCodeLabel;
    private TextView txvAuthCode;
    private TextView txvRefNumberLabel;
    private TextView txvRefNumber;
    private TextView txvResponseMessage;
    private Button btnPrintReceipt;
    private Button btnNewTransaction;
    private Button btnDone;

    // Decline reason box (prominent display)
    private View layoutDeclineReason;
    private TextView txvDeclineReasonLabel;
    private TextView txvDeclineReasonText;
    private TextView txvDeclineCode;

    private View view;
    private DecimalFormat currencyFormat = new DecimalFormat("$0.00");
    private Handler autoTimeoutHandler = new Handler();
    private Runnable autoTimeoutRunnable;
    private static final int AUTO_TIMEOUT_MS = 30000; // 30 seconds

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_page_receipt, container, false);

        // Reinitialize handler (may have been nulled in onDestroyView)
        if (autoTimeoutHandler == null) {
            autoTimeoutHandler = new Handler();
        }

        // Initialize UI components
        initializeComponents();

        // Set up button listeners
        setupButtonListeners();

        // Only render results if there's actual transaction data. Otherwise the
        // adjacent ViewPager pre-creation of this fragment will auto-print an
        // empty receipt (e.g. when the user clicks Balance Inquiry, which
        // navigates to TRANSACTION and pre-loads RECEIPT).
        if (hasTransactionDataToDisplay()) {
            displayTransactionResults();
        } else {
            Log.d(TAG, "onCreateView: no transaction data — skipping display/auto-print");
        }

        // Don't start timeout in onCreateView - only in onResume when visible
        // This prevents timeout from firing when ViewPager pre-creates adjacent fragments

        return view;
    }

    public Fragment_page_receipt() {
        // Required empty public constructor
    }

    public Fragment_page_receipt(MainActivity activity) {
        if (this.mainActivity == null) {
            this.mainActivity = activity;
        }
    }

    private void initializeComponents() {
        // Result display
        txvResultIcon = view.findViewById(R.id.txvResultIcon);
        txvResultMessage = view.findViewById(R.id.txvResultMessage);

        // Amount displays
        txvWithdrawalAmount = view.findViewById(R.id.txvWithdrawalAmount);
        txvFeeAmount = view.findViewById(R.id.txvFeeAmount);
        txvTotalAmount = view.findViewById(R.id.txvTotalAmount);

        // Transaction details
        txvCardInfo = view.findViewById(R.id.txvCardInfo);
        txvDateTime = view.findViewById(R.id.txvDateTime);
        txvTransactionId = view.findViewById(R.id.txvTransactionId);

        // Host response fields
        txvAuthCodeLabel = view.findViewById(R.id.txvAuthCodeLabel);
        txvAuthCode = view.findViewById(R.id.txvAuthCode);
        txvRefNumberLabel = view.findViewById(R.id.txvRefNumberLabel);
        txvRefNumber = view.findViewById(R.id.txvRefNumber);
        txvResponseMessage = view.findViewById(R.id.txvResponseMessage);

        // Buttons
        btnPrintReceipt = view.findViewById(R.id.btnPrintReceipt);
        btnNewTransaction = view.findViewById(R.id.btnNewTransaction);
        btnDone = view.findViewById(R.id.btnDone);

        // Decline reason box
        layoutDeclineReason = view.findViewById(R.id.layoutDeclineReason);
        txvDeclineReasonLabel = view.findViewById(R.id.txvDeclineReasonLabel);
        txvDeclineReasonText = view.findViewById(R.id.txvDeclineReasonText);
        txvDeclineCode = view.findViewById(R.id.txvDeclineCode);
    }

    private void setupButtonListeners() {
        // Print Receipt button
        btnPrintReceipt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Print Receipt clicked");
                stopAutoTimeout();
                printReceipt();
                startAutoTimeout();
            }
        });

        // New Transaction button
        btnNewTransaction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "New Transaction clicked");
                stopAutoTimeout();
                resetATMParameters();
                if (mainActivity != null) {
                    Log.d(TAG, "Navigating to amount selection");
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
                } else {
                    Log.e(TAG, "mainActivity is null!");
                }
            }
        });

        // Done button
        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Done clicked");
                stopAutoTimeout();
                resetATMParameters();
                if (mainActivity != null) {
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_IDLE);
                }
            }
        });
    }

    private void displayTransactionResults() {
        Log.d(TAG, "displayTransactionResults() called");
        Log.d(TAG, "  transactionResult=" + GlobalPara.transactionResult);
        Log.d(TAG, "  atmHostCallSuccess=" + GlobalPara.atmHostCallSuccess);
        Log.d(TAG, "  atmResponseCode=" + GlobalPara.atmResponseCode);
        Log.d(TAG, "  atmResponseMessage=" + GlobalPara.atmResponseMessage);
        Log.d(TAG, "  atmBalanceInquiryMode=" + GlobalPara.atmBalanceInquiryMode);
        Log.d(TAG, "  atmAccountBalance=" + GlobalPara.atmAccountBalance);
        Log.d(TAG, "  atmAvailableBalance=" + GlobalPara.atmAvailableBalance);

        // Determine transaction success/failure
        boolean isSuccess = isTransactionSuccessful();
        boolean isBalanceInquiry = GlobalPara.atmBalanceInquiryMode;
        Log.d(TAG, "  isSuccess=" + isSuccess + ", isBalanceInquiry=" + isBalanceInquiry);

        // Update result icon and message
        if (isSuccess) {
            txvResultIcon.setText("✓");
            txvResultIcon.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            if (isBalanceInquiry) {
                txvResultMessage.setText("Balance Inquiry Approved");
            } else {
                txvResultMessage.setText("Transaction Approved");
            }
            txvResultMessage.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            // Hide decline reason box on success
            if (layoutDeclineReason != null) {
                layoutDeclineReason.setVisibility(View.GONE);
            }
            // Hide response message on success (unless there's a display message)
            if (GlobalPara.atmResponseMessage != null && !GlobalPara.atmResponseMessage.isEmpty()
                    && !"APPROVED".equalsIgnoreCase(GlobalPara.atmResponseMessage)) {
                txvResponseMessage.setText(GlobalPara.atmResponseMessage);
                txvResponseMessage.setVisibility(View.VISIBLE);
            } else {
                txvResponseMessage.setVisibility(View.GONE);
            }
        } else {
            txvResultIcon.setText("✗");
            txvResultIcon.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            if (isBalanceInquiry) {
                txvResultMessage.setText("Balance Inquiry Failed");
            } else {
                txvResultMessage.setText("Transaction Failed");
            }
            txvResultMessage.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            // Show prominent decline reason box
            displayDeclineReason();
        }

        // Display amounts - different for balance inquiry vs withdrawal
        if (isBalanceInquiry && isSuccess) {
            // For balance inquiry, show the account balances
            double accountBalance = GlobalPara.atmAccountBalance / 100.0;
            double availableBalance = GlobalPara.atmAvailableBalance / 100.0;

            // Repurpose the amount labels for balance display
            // Find labels and update text if they exist
            View withdrawalLabel = view.findViewById(R.id.txvWithdrawalLabel);
            View feeLabel = view.findViewById(R.id.txvFeeLabel);
            View totalLabel = view.findViewById(R.id.txvTotalLabel);

            if (withdrawalLabel instanceof TextView) {
                ((TextView) withdrawalLabel).setText("Account Balance:");
            }
            txvWithdrawalAmount.setText(currencyFormat.format(accountBalance));

            if (feeLabel instanceof TextView) {
                ((TextView) feeLabel).setText("Available Balance:");
            }
            txvFeeAmount.setText(currencyFormat.format(availableBalance));

            // Hide total row for balance inquiry
            if (totalLabel != null) {
                totalLabel.setVisibility(View.GONE);
            }
            txvTotalAmount.setVisibility(View.GONE);
        } else {
            // Standard withdrawal display
            txvWithdrawalAmount.setText(currencyFormat.format(parseAmount(GlobalPara.atmSelectedAmount)));
            txvFeeAmount.setText(currencyFormat.format(parseAmount(GlobalPara.atmFee)));
            txvTotalAmount.setText(currencyFormat.format(parseAmount(GlobalPara.atmTotal)));
        }

        // Display card info
        String cardInfo = getCardInfo();
        txvCardInfo.setText(cardInfo);

        // Display date/time
        String dateTime = GlobalPara.DateTime;
        if (dateTime == null || dateTime.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            dateTime = sdf.format(new Date());
        }
        txvDateTime.setText(dateTime);

        // Generate and display transaction ID
        String transactionId = generateTransactionId();
        GlobalPara.atmTransactionId = transactionId;
        txvTransactionId.setText(transactionId);

        // Display host response data
        displayHostResponseData(isSuccess);

        // Update the on-screen reversal status line. Refreshed whenever
        // refreshDisplay() is called by MainActivity's onProgress hook.
        updateReversalStatusDisplay();

        // Auto-print the receipt on first display (approve or decline) — BUT
        // hold off while a reversal drain is actively running. The receipt
        // should reflect the final outcome (including reversal info) rather
        // than printing mid-flight and then surprising the customer.
        if (!autoPrintTriggered && !GlobalPara.atmReversalInProgress) {
            autoPrintTriggered = true;
            // Use post() to ensure UI is fully laid out before kicking off the print
            if (view != null) {
                view.post(() -> {
                    Log.d(TAG, "Auto-printing receipt on display");
                    printReceipt();
                });
            }
        } else if (GlobalPara.atmReversalInProgress) {
            Log.d(TAG, "Auto-print deferred — reversal drain in progress");
        }
    }

    /**
     * Updates (or hides) the on-screen reversal status line based on the
     * current {@link GlobalPara#atmReversalStatus}. Safe to call on any
     * thread — posts to the UI thread internally.
     */
    private void updateReversalStatusDisplay() {
        if (view == null) return;
        final String status = GlobalPara.atmReversalStatus;
        if (status == null || status.isEmpty()) {
            return;  // nothing to show — leave the label hidden
        }
        // Use the existing response-message slot to show reversal status without
        // adding a new XML widget. Color it amber/orange so customers notice.
        if (mainActivity != null) {
            mainActivity.runOnUiThread(() -> {
                if (txvResponseMessage != null) {
                    txvResponseMessage.setText("Reversal: " + status);
                    txvResponseMessage.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                    txvResponseMessage.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    /** Tracks whether auto-print has already fired for this receipt display. */
    private boolean autoPrintTriggered = false;

    /**
     * Displays the decline reason from the host response.
     * Uses the prominent decline reason box for visibility.
     * Always shows a message for declines - uses fallback if no specific reason available.
     */
    private void displayDeclineReason() {
        String responseMsg = GlobalPara.atmResponseMessage;
        String responseCode = GlobalPara.atmResponseCode;

        Log.d(TAG, "displayDeclineReason: responseMsg=" + responseMsg + ", responseCode=" + responseCode);

        // Show the prominent decline reason box
        if (layoutDeclineReason != null) {
            layoutDeclineReason.setVisibility(View.VISIBLE);
        }

        // Determine the reason text to display
        String reasonText;
        if (responseMsg != null && !responseMsg.isEmpty()) {
            reasonText = responseMsg.toUpperCase();
            Log.d(TAG, "Showing decline message: " + responseMsg);
        } else if (responseCode != null && !responseCode.isEmpty()) {
            // Look up a friendly message for common response codes
            reasonText = getResponseCodeDescription(responseCode);
            Log.d(TAG, "Showing decline from code: " + responseCode + " -> " + reasonText);
        } else {
            reasonText = "TRANSACTION COULD NOT BE COMPLETED";
            Log.d(TAG, "Showing fallback decline message");
        }

        // Set the reason text in the prominent box
        if (txvDeclineReasonText != null) {
            txvDeclineReasonText.setText(reasonText);
        }

        // Show the response code if available
        if (txvDeclineCode != null) {
            if (responseCode != null && !responseCode.isEmpty()) {
                txvDeclineCode.setText("Response Code: " + responseCode);
                txvDeclineCode.setVisibility(View.VISIBLE);
            } else {
                txvDeclineCode.setVisibility(View.GONE);
            }
        }

        // Also set the smaller response message for the summary card (hidden but kept for compatibility)
        if (txvResponseMessage != null) {
            txvResponseMessage.setText(reasonText);
            txvResponseMessage.setVisibility(View.GONE);  // Hidden - using prominent box instead
        }
    }

    /**
     * Returns a friendly description for common ISO 8583 response codes.
     */
    private String getResponseCodeDescription(String code) {
        if (code == null) return "DECLINED";

        switch (code) {
            case "00": return "APPROVED";
            case "01": return "REFER TO CARD ISSUER";
            case "03": return "INVALID MERCHANT";
            case "04": return "PICK UP CARD";
            case "05": return "DO NOT HONOR";
            case "06": return "ERROR";
            case "07": return "PICK UP CARD - SPECIAL";
            case "12": return "INVALID TRANSACTION";
            case "13": return "INVALID AMOUNT";
            case "14": return "INVALID CARD NUMBER";
            case "30": return "FORMAT ERROR";
            case "41": return "LOST CARD - PICK UP";
            case "43": return "STOLEN CARD - PICK UP";
            case "51": return "INSUFFICIENT FUNDS";
            case "54": return "EXPIRED CARD";
            case "55": return "INCORRECT PIN";
            case "57": return "TRANSACTION NOT PERMITTED";
            case "58": return "TRANSACTION NOT ALLOWED";
            case "61": return "EXCEEDS WITHDRAWAL LIMIT";
            case "62": return "RESTRICTED CARD";
            case "63": return "SECURITY VIOLATION";
            case "65": return "EXCEEDS WITHDRAWAL FREQUENCY";
            case "75": return "PIN TRIES EXCEEDED";
            case "76": return "INVALID ACCOUNT";
            case "78": return "NO ACCOUNT";
            case "80": return "INVALID DATE";
            case "82": return "CVV VALIDATION ERROR";
            case "83": return "CANNOT VERIFY PIN";
            case "85": return "NO REASON TO DECLINE";
            case "86": return "CANNOT VERIFY PIN";
            case "89": return "INVALID TERMINAL ID";
            case "91": return "ISSUER UNAVAILABLE";
            case "92": return "ROUTING ERROR";
            case "94": return "DUPLICATE TRANSACTION";
            case "96": return "SYSTEM MALFUNCTION";
            default: return "DECLINED (CODE: " + code + ")";
        }
    }

    /**
     * Displays authorization code and reference number from host response.
     */
    private void displayHostResponseData(boolean isSuccess) {
        // Display authorization code if available (typically only on approval)
        String authCode = GlobalPara.atmAuthCode;
        if (authCode != null && !authCode.isEmpty()) {
            txvAuthCode.setText(authCode);
            txvAuthCodeLabel.setVisibility(View.VISIBLE);
            txvAuthCode.setVisibility(View.VISIBLE);
            Log.d(TAG, "Displaying auth code: " + authCode);
        } else {
            txvAuthCodeLabel.setVisibility(View.GONE);
            txvAuthCode.setVisibility(View.GONE);
        }

        // Display reference number if available
        String refNumber = GlobalPara.atmReferenceNumber;
        if (refNumber != null && !refNumber.isEmpty()) {
            txvRefNumber.setText(refNumber);
            txvRefNumberLabel.setVisibility(View.VISIBLE);
            txvRefNumber.setVisibility(View.VISIBLE);
            Log.d(TAG, "Displaying ref number: " + refNumber);
        } else {
            txvRefNumberLabel.setVisibility(View.GONE);
            txvRefNumber.setVisibility(View.GONE);
        }

        // Log balance info if available (not displayed on screen, but useful for debugging)
        if (GlobalPara.atmAccountBalance > 0 || GlobalPara.atmAvailableBalance > 0) {
            Log.d(TAG, "Account balance: " + GlobalPara.atmAccountBalance +
                       ", Available balance: " + GlobalPara.atmAvailableBalance);
        }
    }

    private boolean isTransactionSuccessful() {
        // Check transaction result
        // 0x0002 = Approved, 0x0003 = Declined, 0x0004 = Approved (alternative code)
        short result = GlobalPara.transactionResult;
        boolean emvApproved = (result == 0x0002 || result == 0x0004);

        // Also check host approval - "00" is approved, "10" is partial approval
        // Note: atmHostCallSuccess should be true if onTransactionApproved callback was triggered
        boolean hostApproved = GlobalPara.atmHostCallSuccess &&
                               ("00".equals(GlobalPara.atmResponseCode) || "10".equals(GlobalPara.atmResponseCode));

        Log.d(TAG, "isTransactionSuccessful: transactionResult=0x" + String.format("%04X", result) +
              ", emvApproved=" + emvApproved +
              ", atmHostCallSuccess=" + GlobalPara.atmHostCallSuccess +
              ", atmResponseCode=[" + GlobalPara.atmResponseCode + "]" +
              ", hostApproved=" + hostApproved);
        return emvApproved || hostApproved;
    }

    private String getCardInfo() {
        String cardType = GlobalPara.cardType;
        String pan = GlobalPara.asciiPAN;

        if (pan != null && !pan.isEmpty()) {
            // Extract last 4 digits
            if (pan.length() >= 4) {
                GlobalPara.atmLastFourDigits = pan.substring(pan.length() - 4);
                return (cardType != null ? cardType + " " : "") + "****" + GlobalPara.atmLastFourDigits;
            } else {
                return pan;
            }
        }

        return "****";
    }

    private String generateTransactionId() {
        // Generate a simple transaction ID (timestamp-based)
        long timestamp = System.currentTimeMillis();
        String id = "TXN" + timestamp;
        return id;
    }

    private double parseAmount(String amountStr) {
        try {
            if (amountStr == null || amountStr.isEmpty()) {
                return 0.0;
            }
            return Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void printReceipt() {
        Log.d(TAG, "Printing receipt...");

        // Disable print button temporarily while the printer is busy
        if (btnPrintReceipt != null) {
            btnPrintReceipt.setEnabled(false);
            btnPrintReceipt.setText("Printing...");
        }

        // Run print in separate thread to avoid blocking UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean printSuccess = printReceiptContent();

                    // Update UI on main thread — re-enable for duplicate print on both
                    // success and failure paths (customer / merchant may want a copy).
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (btnPrintReceipt != null) {
                                    if (printSuccess) {
                                        btnPrintReceipt.setText("Print Another Receipt");
                                    } else if (GlobalPara.atmPrinterOutOfPaper) {
                                        // Out of paper: the receipt shown on screen IS the
                                        // receipt. Retrying will not help, so say so plainly.
                                        btnPrintReceipt.setText("Out of Paper — Receipt On Screen");
                                    } else {
                                        btnPrintReceipt.setText("Print Failed — Tap to Retry");
                                    }
                                    btnPrintReceipt.setEnabled(true);
                                }
                                if (!printSuccess && GlobalPara.atmPrinterOutOfPaper
                                        && getContext() != null) {
                                    Toast.makeText(getContext(),
                                            "Out of paper — your receipt is shown on screen. Please note your transaction details.",
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Print error: " + e.getMessage());
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (btnPrintReceipt != null) {
                                    btnPrintReceipt.setText("Print Failed — Tap to Retry");
                                    btnPrintReceipt.setEnabled(true);
                                }
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private boolean printReceiptContent() {
        try {
            if (mainActivity == null) {
                Log.e(TAG, "MainActivity is null, cannot access printer");
                return false;
            }

            // Format receipt content
            StringBuilder receipt = new StringBuilder();
            boolean isBalanceInquiry = GlobalPara.atmBalanceInquiryMode;
            boolean isSuccess = isTransactionSuccessful();

            receipt.append("\n");
            receipt.append("================================\n");
            if (isBalanceInquiry) {
                receipt.append("      BALANCE INQUIRY           \n");
            } else {
                receipt.append("         ATM RECEIPT            \n");
            }
            receipt.append("================================\n");
            receipt.append("\n");

            // Transaction result
            if (isSuccess) {
                receipt.append("   STATUS: APPROVED\n");
            } else {
                receipt.append("   STATUS: DECLINED\n");
                // Print decline reason if available
                String responseMsg = GlobalPara.atmResponseMessage;
                if (responseMsg != null && !responseMsg.isEmpty()) {
                    receipt.append("   Reason: ").append(responseMsg).append("\n");
                } else if (GlobalPara.atmResponseCode != null && !GlobalPara.atmResponseCode.isEmpty()) {
                    receipt.append("   Code: ").append(GlobalPara.atmResponseCode).append("\n");
                }
            }
            receipt.append("\n");

            // Transaction details - different for balance inquiry vs withdrawal
            if (isBalanceInquiry && isSuccess) {
                // For balance inquiry, print the account balances
                double accountBalance = GlobalPara.atmAccountBalance / 100.0;
                double availableBalance = GlobalPara.atmAvailableBalance / 100.0;

                receipt.append("Account Balance:   ").append(currencyFormat.format(accountBalance)).append("\n");
                receipt.append("Available Balance: ").append(currencyFormat.format(availableBalance)).append("\n");
                receipt.append("\n");
            } else {
                // Standard withdrawal receipt
                receipt.append("Withdrawal Amount: ").append(currencyFormat.format(parseAmount(GlobalPara.atmSelectedAmount))).append("\n");
                receipt.append("Service Fee:       ").append(currencyFormat.format(parseAmount(GlobalPara.atmFee))).append("\n");
                receipt.append("--------------------------------\n");
                receipt.append("Total Charged:     ").append(currencyFormat.format(parseAmount(GlobalPara.atmTotal))).append("\n");
                receipt.append("\n");
            }

            // Card and transaction info
            String cardInfo = getCardInfo();
            receipt.append("Card: ").append(cardInfo != null ? cardInfo : "****").append("\n");

            String dateTime = txvDateTime != null && txvDateTime.getText() != null ?
                              txvDateTime.getText().toString() : "";
            receipt.append("Date/Time: ").append(dateTime).append("\n");

            String txnId = GlobalPara.atmTransactionId != null ? GlobalPara.atmTransactionId : "";
            receipt.append("TransID: ").append(txnId).append("\n");

            // Print authorization code if available
            String authCode = GlobalPara.atmAuthCode;
            if (authCode != null && !authCode.isEmpty()) {
                receipt.append("Auth Code: ").append(authCode).append("\n");
            }

            // Print reference number if available
            String refNumber = GlobalPara.atmReferenceNumber;
            if (refNumber != null && !refNumber.isEmpty()) {
                receipt.append("Ref #: ").append(refNumber).append("\n");
            }

            receipt.append("\n");

            // Reversal section — append only if a reversal was attempted for this txn.
            // Tells the customer "your transaction failed AND we sent the reversal so
            // your card was not charged" (or pending status if reversal didn't complete).
            String reversalStatus = GlobalPara.atmReversalStatus;
            if (reversalStatus != null && !reversalStatus.isEmpty()) {
                receipt.append("--------------------------------\n");
                receipt.append("           REVERSAL             \n");
                receipt.append("--------------------------------\n");
                if (GlobalPara.atmReversalSent) {
                    receipt.append("Status: SENT TO PROCESSOR\n");
                    receipt.append("Card NOT charged.\n");
                } else if (GlobalPara.atmReversalInProgress) {
                    receipt.append("Status: IN PROGRESS\n");
                } else {
                    receipt.append("Status: PENDING\n");
                    receipt.append("Please contact merchant.\n");
                }
                receipt.append("Detail: ").append(reversalStatus).append("\n");
                receipt.append("\n");
            }

            receipt.append("================================\n");
            receipt.append("     Thank you for using our    \n");
            receipt.append("           ATM Service          \n");
            receipt.append("================================\n");
            receipt.append("\n\n\n");

            // Log the receipt content
            Log.d(TAG, "Receipt content:\n" + receipt.toString());

            // Print the receipt using the printer
            try {
                MainActivity.CTOS_Printer printer = mainActivity.getPrinter();
                Log.d(TAG, "getPrinter() returned: " + (printer != null ? "valid printer" : "NULL"));
                if (printer != null) {
                    // Check paper BEFORE printing so an empty roll is reported as
                    // "out of paper" rather than a generic print failure. Also keeps
                    // GlobalPara (and therefore the host status field) honest.
                    boolean outOfPaper = printer.isOutOfPaper();
                    GlobalPara.atmPrinterOutOfPaper = outOfPaper;
                    if (outOfPaper) {
                        Log.e(TAG, "Receipt NOT printed - printer is OUT OF PAPER");
                        return false;
                    }
                    Log.d(TAG, "Calling printer.printf() with " + receipt.length() + " chars");
                    // printf() is self-contained: initPage + drawText + printPage.
                    // Do NOT call goprintf() — that's a legacy SAMPLE RECEIPT demo, not a flush.
                    printer.printf(receipt.toString());
                    Log.d(TAG, "Receipt printed successfully");
                    return true;
                } else {
                    Log.e(TAG, "Printer is null - may be running on emulator or printer not initialized");
                    return false;
                }
            } catch (Exception printEx) {
                Log.e(TAG, "Printer error: " + printEx.getMessage(), printEx);
                return false;  // Show error to user instead of hiding it
            }

        } catch (Exception e) {
            Log.e(TAG, "Error printing receipt: " + e.getMessage());
            return false;
        }
    }

    private void startAutoTimeout() {
        autoTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                // Only navigate if this fragment is actually visible (user is on receipt page)
                // This prevents timeout from hijacking navigation when user is on admin or other pages
                if (!isVisible() || !getUserVisibleHint()) {
                    Log.d(TAG, "Auto-timeout skipped - fragment not visible");
                    return;
                }
                Log.d(TAG, "Auto-timeout triggered, returning to idle");
                resetATMParameters();
                if (mainActivity != null) {
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_IDLE);
                }
            }
        };
        autoTimeoutHandler.postDelayed(autoTimeoutRunnable, AUTO_TIMEOUT_MS);
    }

    private void stopAutoTimeout() {
        if (autoTimeoutRunnable != null && autoTimeoutHandler != null) {
            autoTimeoutHandler.removeCallbacks(autoTimeoutRunnable);
        }
    }

    private void resetATMParameters() {
        Log.d(TAG, "resetATMParameters called");
        // Reset auto-print flag so next transaction's receipt auto-prints
        autoPrintTriggered = false;
        // Reset all ATM transaction parameters
        GlobalPara.atmSelectedAmount = "0.00";
        GlobalPara.atmFee = "0.00";
        GlobalPara.atmTotal = "0.00";
        GlobalPara.atmTransactionComplete = false;
        // Clear EMV transaction result so onResume doesn't think old data is still valid
        // and auto-print the previous receipt on the next transaction.
        GlobalPara.transactionResult = 0;
        GlobalPara.atmTransactionId = "";
        GlobalPara.atmLastFourDigits = "";
        GlobalPara.atmBalanceInquiryMode = false; // Important: reset balance inquiry flag

        // Reset host response data
        GlobalPara.atmAuthCode = "";
        GlobalPara.atmReferenceNumber = "";
        GlobalPara.atmResponseCode = "";
        GlobalPara.atmResponseMessage = "";
        GlobalPara.atmAccountBalance = 0;
        GlobalPara.atmAvailableBalance = 0;

        // Reset transaction state
        GlobalPara.atmTrack2Data = "";
        GlobalPara.atmEncryptedPinBlock = "";
        GlobalPara.atmEmvData = "";
        GlobalPara.atmEntryMode = 0;
        GlobalPara.atmHostCallInProgress = false;
        GlobalPara.atmHostCallSuccess = false;
        GlobalPara.atmNeedsReversal = false;
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: stopping timeout");
        stopAutoTimeout();
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: stopping timeout");
        stopAutoTimeout();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView: stopping timeout and cleaning up");
        stopAutoTimeout();
        autoTimeoutRunnable = null;
        autoTimeoutHandler = null;
        view = null;
    }

    /**
     * Returns true if the receipt fragment should render results (and auto-print).
     * Used to suppress display when ViewPager pre-creates the fragment with no data.
     */
    private boolean hasTransactionDataToDisplay() {
        return GlobalPara.atmTransactionComplete ||
               GlobalPara.transactionResult != 0 ||
               (GlobalPara.atmResponseCode != null && !GlobalPara.atmResponseCode.isEmpty()) ||
               (GlobalPara.atmResponseMessage != null && !GlobalPara.atmResponseMessage.isEmpty());
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean hasTransactionData = hasTransactionDataToDisplay();

        if (hasTransactionData) {
            Log.d(TAG, "onResume: Transaction data found, refreshing display");
            Log.d(TAG, "  atmTransactionComplete=" + GlobalPara.atmTransactionComplete);
            Log.d(TAG, "  transactionResult=" + GlobalPara.transactionResult);
            Log.d(TAG, "  atmResponseCode=" + GlobalPara.atmResponseCode);
            Log.d(TAG, "  atmResponseMessage=" + GlobalPara.atmResponseMessage);
            displayTransactionResults();
            startAutoTimeout();
        } else {
            Log.d(TAG, "onResume: No transaction data to display");
        }
    }

    public View toView() {
        return view;
    }

    /**
     * Public method to refresh the display when navigated to via ViewPager.
     * Called from MainActivity.navigateToPage() since ViewPager doesn't trigger
     * onResume() for adjacent fragments that are already in resumed state.
     */
    public void refreshDisplay() {
        Log.d(TAG, "refreshDisplay() called from navigation");
        if (view != null) {
            displayTransactionResults();
            startAutoTimeout();
        } else {
            Log.w(TAG, "refreshDisplay() called but view is null - will display on onCreateView");
        }
    }
}
