package castech.emvtxn;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The tip screen (TIP-01, 6.2.14; spec section 4). Shown between the amount and the card phase in
 * both flows, never on a balance inquiry, only when {@link TipQuote#offer} says so.
 *
 * <p>Owns nothing but the choice: it reads the no-tip breakdown from {@code GlobalPara.atmAmounts},
 * writes the chosen breakdown back with {@code GlobalPara.applyAmounts}, and navigates on. The
 * 30 s idle timer chooses No Tip (T8). Cancel cancels the transaction: walk-up back to the menu,
 * a register sale answered {@code user_cancelled} through the armed POS slot (T8).
 *
 * <p>LIFE-02 lesson: the timer must never fire into the card phase — it is cancelled on every exit
 * (choice, cancel, onPause, onDestroyView, and {@link #onHidden()} from MainActivity.navigateToPage),
 * and a {@code decided} flag makes every path one-shot.
 */
public class Fragment_page_tip extends Fragment {

    private static final String TAG = "Fragment_Tip";
    static final long IDLE_MILLIS = 30_000L;

    private MainActivity mainActivity;
    private View view;
    private TextView txvSale;
    private final Button[] pctButtons = new Button[3];
    private final TextView[] pctReasons = new TextView[3];
    private Button btnCustom, btnNoTip, btnCancel;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            Log.d(TAG, "30 s idle: No Tip");
            choose(quote != null ? quote.noTip : null);
        }
    };
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);

    private TipQuote quote;
    private boolean decided;
    private AlertDialog openDialog;

    public Fragment_page_tip() {}
    public Fragment_page_tip(MainActivity activity) { this.mainActivity = activity; }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_page_tip, container, false);
        txvSale = view.findViewById(R.id.txvTipSale);
        pctButtons[0] = view.findViewById(R.id.btnTip10);
        pctButtons[1] = view.findViewById(R.id.btnTip15);
        pctButtons[2] = view.findViewById(R.id.btnTip20);
        pctReasons[0] = view.findViewById(R.id.txvTip10Reason);
        pctReasons[1] = view.findViewById(R.id.txvTip15Reason);
        pctReasons[2] = view.findViewById(R.id.txvTip20Reason);
        btnCustom = view.findViewById(R.id.btnTipCustom);
        btnNoTip = view.findViewById(R.id.btnNoTip);
        btnCancel = view.findViewById(R.id.btnTipCancel);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pctButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (quote != null) choose(quote.options.get(idx).amounts); }
            });
        }
        btnNoTip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (quote != null) choose(quote.noTip); }
        });
        btnCustom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCustomTipDialog(); }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel(); }
        });
        if (quote != null) render();   // shown before the view existed (first visit): catch up
        return view;
    }

    /** Called by MainActivity.navigateToPage when this page becomes current (the pager does not resume it). */
    public void onShown() {
        decided = false;
        AmountBreakdown noTip = GlobalPara.atmAmounts;
        if (noTip == null || noTip.sale <= 0) {
            // Nothing to tip on — never stay here. Callers check TipQuote.offer first; this is the belt.
            Log.w(TAG, "shown without a sale — going straight to the card");
            goToCard();
            return;
        }
        quote = TipQuote.of(noTip, Money.toCents(GlobalPara.atmMinAmount), Money.toCents(GlobalPara.atmMaxAmount),
                GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
        render();
        restartTimer();
    }

    /** Called by MainActivity.navigateToPage when leaving this page by any route. */
    public void onHidden() { stopTimer(); }

    @Override public void onResume() { super.onResume(); if (quote != null && !decided) restartTimer(); }
    @Override public void onPause() { stopTimer(); super.onPause(); }
    @Override public void onDestroyView() { stopTimer(); super.onDestroyView(); }

    private void render() {
        if (txvSale == null || quote == null) return;
        txvSale.setText("Sale  " + currency.format(quote.noTip.sale / 100.0));
        for (int i = 0; i < 3; i++) {
            TipQuote.Option o = quote.options.get(i);
            pctButtons[i].setText(o.percent + "%\n" + currency.format(o.tipCents / 100.0));
            pctButtons[i].setEnabled(o.enabled);
            pctButtons[i].setAlpha(o.enabled ? 1f : 0.4f);
            pctReasons[i].setText(o.reasonIfDisabled);
            pctReasons[i].setVisibility(o.enabled ? View.GONE : View.VISIBLE);
        }
        btnCustom.setEnabled(quote.customLimitCents > 0);
        btnCustom.setAlpha(quote.customLimitCents > 0 ? 1f : 0.4f);
    }

    private void restartTimer() { handler.removeCallbacks(idle); handler.postDelayed(idle, IDLE_MILLIS); }

    private void stopTimer() {
        handler.removeCallbacks(idle);
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        openDialog = null;
    }

    private void choose(AmountBreakdown amounts) {
        if (decided) return;              // one-shot: a late timer or a double tap must not navigate twice
        decided = true;
        stopTimer();
        if (amounts == null) amounts = GlobalPara.atmAmounts;
        GlobalPara.applyAmounts(amounts);
        Log.d(TAG, "tip chosen: " + amounts);
        goToCard();
    }

    private void goToCard() {
        if (mainActivity != null) mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
    }

    private void cancel() {
        if (decided) return;
        decided = true;
        stopTimer();
        boolean register = castech.emvtxn.pos.PosTransactionObserver.isArmed();
        if (register) {
            // Answer the waiting register and free the single POS slot (exactly-once; no-op if answered)
            castech.emvtxn.pos.PosTransactionObserver.notifyDeclined("user_cancelled", "cancelled at terminal", false);
        }
        GlobalPara.resetATMTransactionState();
        Log.d(TAG, "tip screen cancelled (" + (register ? "register" : "walk-up") + ")");
        if (mainActivity != null) mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
    }

    private void showCustomTipDialog() {
        if (quote == null || getContext() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Tip amount");
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Enter tip (up to " + currency.format(quote.customLimitCents / 100.0) + ")");
        builder.setView(input);
        builder.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                openDialog = null;
                String s = input.getText().toString().trim();
                long cents;
                try { cents = Money.toCents(Double.parseDouble(s)); }
                catch (NumberFormatException e) { showMessage("Invalid amount", "Please enter a valid amount."); return; }
                final TipQuote.Custom c = quote.custom(cents);
                if (TipQuote.TOO_SMALL.equals(c.failure)) { showMessage("Tip too small", "The minimum tip is $0.01."); return; }
                if (TipQuote.OVER_LIMIT.equals(c.failure)) {
                    showMessage("Over the limit", "The maximum tip on this sale is " + currency.format(c.maxTipCents / 100.0)
                            + " (withdrawal limit " + currency.format(quote.maxWithdrawalCents / 100.0) + ").");
                    return;
                }
                if (c.exceedsSale) {
                    // T7: a tip larger than the sale is asked about once
                    AlertDialog.Builder ask = new AlertDialog.Builder(getContext());
                    ask.setMessage("Tip " + currency.format(c.amounts.tip / 100.0) + " on a "
                            + currency.format(c.amounts.sale / 100.0) + " sale?");
                    ask.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d2, int w2) { openDialog = null; choose(c.amounts); }
                    });
                    ask.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d2, int w2) { openDialog = null; }
                    });
                    openDialog = ask.show();
                    return;
                }
                choose(c.amounts);
            }
        });
        builder.setNegativeButton("Back", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { openDialog = null; d.cancel(); }
        });
        // The 30 s timer keeps running (spec section 7): on expiry stopTimer() dismisses this and No Tip is chosen
        openDialog = builder.show();
    }

    private void showMessage(String title, String message) {
        if (getContext() == null) return;
        openDialog = new AlertDialog.Builder(getContext()).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }
}
