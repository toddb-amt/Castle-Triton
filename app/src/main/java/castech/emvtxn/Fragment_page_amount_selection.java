package castech.emvtxn;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

/**
 * The amount screen. AMT-04 (6.2.20, partner feedback after the Jushi go-live): entry first. The
 * amount is typed from the cents on an on-screen keypad ({@link CentsEntry}: 2, 1, 3, 5, 2 reads
 * $0.02, $0.21, $2.13, $21.35, $213.52, no decimal point), the presets are small shortcuts that drop
 * their amount into the same field, and Continue works the same from either path. One layout for
 * every merchant; a merchant without tips simply never sees the tip screen.
 *
 * <p>Nothing rounds here (owner's rule, 2026-10-22): the field holds what was typed, the sale. The
 * breakdown built on Continue rounds the withdrawal up to the step (AMT-01) unless the figure came
 * from a preset untouched (review C1: a preset is charged exactly). With tips off the
 * {@link EntryPreview} line shows the withdrawal, fee and total live; with tips on it is blank and
 * the card screen shows them after the tip.
 */
public class Fragment_page_amount_selection extends Fragment {

    private static final String TAG = "AmountSelection";

    private static MainActivity mainActivity = null;

    /** The six preset chips, in layout order = AmountPresets.PRESET_CENTS order. */
    private final Button[] presetButtons = new Button[AmountPresets.PRESET_CENTS.length];
    private Button btnCheckBalance, btnCancel, btnContinue;
    private TextView txvEnteredAmount, txvEntryPreview;

    private View view;

    private final CentsEntry entry = new CentsEntry();
    /** True while the field holds a preset untouched: charged exactly, never rounded (C1). */
    private boolean fromPreset = false;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        try {
            view = inflater.inflate(R.layout.fragment_page_amount_selection, container, false);
            initializeComponents();
            setupButtonListeners();
            render();
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
        int[] presetIds = {R.id.btnPreset0, R.id.btnPreset1, R.id.btnPreset2,
                           R.id.btnPreset3, R.id.btnPreset4, R.id.btnPreset5};
        for (int i = 0; i < presetButtons.length; i++) {
            presetButtons[i] = view.findViewById(presetIds[i]);
            if (presetButtons[i] != null) {
                presetButtons[i].setText("$" + (AmountPresets.PRESET_CENTS[i] / 100));
            }
        }
        btnCheckBalance = view.findViewById(R.id.btnCheckBalance);
        btnCancel = view.findViewById(R.id.btnCancel);
        btnContinue = view.findViewById(R.id.btnContinue);
        txvEnteredAmount = view.findViewById(R.id.txvEnteredAmount);
        txvEntryPreview = view.findViewById(R.id.txvEntryPreview);
    }

    private void setupButtonListeners() {
        // Keypad: digits shift in from the cents
        int[] keyIds = {R.id.btnKey0, R.id.btnKey1, R.id.btnKey2, R.id.btnKey3, R.id.btnKey4,
                        R.id.btnKey5, R.id.btnKey6, R.id.btnKey7, R.id.btnKey8, R.id.btnKey9};
        for (int d = 0; d < keyIds.length; d++) {
            final int digit = d;
            View key = view.findViewById(keyIds[d]);
            if (key != null) key.setOnClickListener(v -> onDigit(digit));
        }
        View back = view.findViewById(R.id.btnKeyBack);
        if (back != null) back.setOnClickListener(v -> onBackspace());
        View clear = view.findViewById(R.id.btnKeyClear);
        if (clear != null) clear.setOnClickListener(v -> onClear());

        // The terminal's physical keys, when they reach us as key events: digits, backspace, enter
        view.setFocusableInTouchMode(true);
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = digitFor(keyCode);
            if (d >= 0) { onDigit(d); return true; }
            if (keyCode == KeyEvent.KEYCODE_DEL) { onBackspace(); return true; }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (btnContinue != null && btnContinue.isEnabled()) btnContinue.performClick();
                return true;
            }
            return false;
        });

        // Presets: a shortcut into the same field (exact amounts; never rounded)
        for (int i = 0; i < presetButtons.length; i++) {
            final long cents = AmountPresets.PRESET_CENTS[i];
            if (presetButtons[i] != null) {
                presetButtons[i].setOnClickListener(v -> onPreset(cents));
            }
        }
        applyPresetLimits();

        btnCheckBalance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startBalanceInquiry(); }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetSelection();
                if (mainActivity != null) {
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_IDLE);
                }
            }
        });

        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onContinue(); }
        });
    }

    private static int digitFor(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private void onDigit(int d) {
        if (entry.digit(d)) {
            fromPreset = false;
            render();
        }
    }

    private void onBackspace() {
        entry.backspace();
        fromPreset = false;
        render();
    }

    private void onClear() {
        entry.clear();
        fromPreset = false;
        render();
    }

    private void onPreset(long cents) {
        entry.set(cents);
        fromPreset = true;
        render();
    }

    /** The field, the preview line and the Continue button, from the entry. */
    private void render() {
        if (txvEnteredAmount == null) return;
        txvEnteredAmount.setText(entry.text());
        String preview = EntryPreview.text(entry.cents(), Money.toCents(GlobalPara.atmMinAmount),
                GlobalPara.atmTipsEnabled, fromPreset,
                GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
        if (txvEntryPreview != null) txvEntryPreview.setText(preview == null ? "" : preview);
        if (btnContinue != null) btnContinue.setEnabled(!entry.isEmpty());
    }

    private void onContinue() {
        long saleCents = entry.cents();
        if (saleCents <= 0) return;
        long minCents = Money.toCents(GlobalPara.atmMinAmount);
        long maxCents = Money.toCents(GlobalPara.atmMaxAmount);

        // AMT-03: one breakdown; every string and the chip amount (9F02 = total in cents) from the SAME
        // integers. The sale is what was typed; the withdrawal is that rounded up to the step (AMT-01),
        // unless the figure is a preset untouched (C1). The difference is recorded as cash back for the
        // journal, the portal and the TNDR-01 block.
        AmountBreakdown amounts;
        try {
            amounts = AmountBreakdown.of(saleCents, 0L, minCents, !fromPreset,
                    GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
        } catch (IllegalArgumentException bad) {
            // Fee configuration that cannot be charged (review I4). Never crash the kiosk.
            android.util.Log.e(TAG, "Cannot build amounts: " + bad.getMessage());
            showErrorDialog("Configuration error", "This terminal's fee settings are invalid. Please contact support.");
            return;
        }

        // Limits apply to the withdrawal, as before (a typed $5 under a $10 step is a $10 withdrawal)
        if (amounts.withdrawal < minCents) {
            showErrorDialog("Amount too low", "Minimum withdrawal amount is " + CentsEntry.format(minCents));
            return;
        }
        if (amounts.withdrawal > maxCents) {
            showErrorDialog("Amount too high", "Maximum withdrawal amount is " + CentsEntry.format(maxCents));
            return;
        }

        GlobalPara.applyAmounts(amounts);
        android.util.Log.d(TAG, "Continue: " + amounts + (fromPreset ? " (preset)" : " (typed)")
                + ", balanceInquiry=" + GlobalPara.atmBalanceInquiryMode);

        // The tip screen when it applies (TIP-01, 6.2.14), else the transaction page
        GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING;
        if (mainActivity != null) {
            boolean tip = TipQuote.offer(GlobalPara.atmTipsEnabled, false, amounts, GlobalPara.atmTipPresets,
                    minCents, maxCents, GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
            mainActivity.navigateToPage(tip ? GlobalDef.d_PAGE_TIP : GlobalDef.d_PAGE_TRANSACTION);
        } else {
            android.util.Log.e(TAG, "mainActivity is null!");
        }
    }

    /**
     * Greys out presets outside the configured min/max (a $10 chip under a $20 minimum would only
     * ever produce "Amount too low"). Called on setup and whenever the screen is shown, since the
     * limits can change via CasHUB.
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

    private void showErrorDialog(String title, String message) {
        if (getContext() == null) return;
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

    private void resetSelection() {
        entry.clear();
        fromPreset = false;
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyPresetLimits();   // limits may have changed via CasHUB since setup
        render();              // tips or fees may have changed too: the preview line follows
        if (view != null) view.requestFocus();
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
        GlobalPara.atmBalanceInquiryMode = true;
        // Clear any previous amount selection (balance inquiry has no amount) — breakdown included (M7)
        GlobalPara.clearAmounts();
        GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING;
        if (mainActivity != null) {
            mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
        }
    }

    public View toView() {
        return view;
    }
}
