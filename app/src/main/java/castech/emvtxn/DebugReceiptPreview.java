package castech.emvtxn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DEBUG BUILDS ONLY (6.2.20): opens the approval screen with a staged result so the layout can be
 * looked at without paying for a transaction. Registered by MainActivity only when
 * {@code BuildConfig.DEBUG}; the release APK has no receiver and ignores the broadcast.
 *
 * <pre>
 *   adb shell am broadcast -a castech.emvtxn.DEBUG_PREVIEW_RECEIPT --ei sale 550 --ei tip 55
 *   adb shell am broadcast -a castech.emvtxn.DEBUG_PREVIEW_RECEIPT --ez approved false --es code 76
 *   extras: sale (cents, 550) · tip (cents, 0; ignored when tips are off) · approved (true) · code ("00" / "76") · msg (host text)
 *           · register (false: a POS sale hides the tender block) · preset (false: true = no rounding)
 * </pre>
 * Side effects are confined to the screen: the breakdown is set without the ATM-mode mirrors, so the
 * card page (the pager's neighbour) has nothing to auto-start and the reader stays off;
 * {@code GlobalPara.atmPreview} suppresses the auto-print and is a second guard on that auto-start,
 * nothing is journaled (the journal is written by the transaction thread, which never runs here),
 * nothing is pushed, no sequence number moves. Test identifiers only (PAN 4111 1111 1111 1111).
 */
public final class DebugReceiptPreview {

    private static final String TAG = "DebugReceiptPreview";
    public static final String ACTION = "castech.emvtxn.DEBUG_PREVIEW_RECEIPT";

    private static BroadcastReceiver receiver;

    private DebugReceiptPreview() {}

    public static void register(final MainActivity activity) {
        if (!BuildConfig.DEBUG || receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    stage(activity, intent);
                } catch (Throwable t) {
                    Log.e(TAG, "preview failed: " + t.getMessage(), t);
                }
            }
        };
        activity.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION));
        Log.i(TAG, "debug receipt preview armed: am broadcast -a " + ACTION);
    }

    public static void unregister(MainActivity activity) {
        if (receiver == null) return;
        try { activity.getApplicationContext().unregisterReceiver(receiver); } catch (Throwable ignore) {}
        receiver = null;
    }

    private static void stage(MainActivity activity, Intent in) {
        if (GlobalPara.atmTransactionInProgress) { Log.w(TAG, "transaction in progress — preview refused"); return; }
        long sale = in.getIntExtra("sale", 550);
        // No tip unless asked for, and never when tips are off on this terminal: the preview must show
        // what a real sale here could show (owner 2026-10-22: a default tip looked like the terminal remembering one)
        long tip = in.getIntExtra("tip", 0);
        if (tip > 0 && !GlobalPara.atmTipsEnabled) { Log.i(TAG, "tips are off on this terminal: preview tip ignored"); tip = 0; }
        boolean approved = in.getBooleanExtra("approved", true);
        boolean register = in.getBooleanExtra("register", false);
        boolean preset = in.getBooleanExtra("preset", false);
        String code = in.getStringExtra("code");
        if (code == null) code = approved ? "00" : "76";

        GlobalPara.resetATMTransactionState();
        AmountBreakdown a = AmountBreakdown.of(sale, tip, Money.toCents(GlobalPara.atmMinAmount), !preset,
                GlobalPara.atmUseFlatFee, GlobalPara.atmFlatFeeAmount, GlobalPara.atmPercentageFee);
        // NOT applyAmounts(): that would set atmSelectedAmount and put the card page in ATM mode, whose
        // auto-start armed the reader on the bench (2026-10-22). The approval screen reads the breakdown.
        GlobalPara.atmAmounts = a;
        GlobalPara.atmRegisterSale = register;
        GlobalPara.asciiPAN = "4111111111111111";
        GlobalPara.cardType = "VISA";
        GlobalPara.atmEntryMode = 2;
        GlobalPara.DateTime = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(new Date());
        GlobalPara.atmResponseCode = code;
        if (approved) {
            GlobalPara.transactionResult = 0x0002;
            GlobalPara.atmHostCallSuccess = true;
            GlobalPara.atmResponseMessage = "APPROVED";
            GlobalPara.atmAuthCode = "123456";
            GlobalPara.atmReferenceNumber = "000012345678";
        } else {
            GlobalPara.transactionResult = 0x0003;
            GlobalPara.atmHostCallSuccess = false;
            String reason = DeclineReason.text(in.getStringExtra("msg"), code);
            GlobalPara.atmResponseMessage = reason != null ? reason : "";
        }
        GlobalPara.atmTransactionComplete = true;
        GlobalPara.atmPreview = true;
        Log.i(TAG, "preview: " + (approved ? "APPROVED" : "DECLINED " + code) + " " + a + (register ? " (register)" : ""));
        activity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
    }
}
