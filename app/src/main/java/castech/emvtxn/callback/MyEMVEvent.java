package castech.emvtxn.callback;

import android.content.DialogInterface;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.viewpager.widget.ViewPager;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import CTOS.CtEMV;
import CTOS.emv.EMVAppListExData;
import CTOS.emv.EMVEvent;
import CTOS.emv.EMVGetPINFuncPara;
import CTOS.emv.EMVTxnData;

import castech.emvtxn.ClsListViewAdapter;
import castech.emvtxn.Debugger;
import castech.emvtxn.GlobalDef;
import castech.emvtxn.GlobalPara;
import castech.emvtxn.MainActivity;
import castech.emvtxn.MyUtility;
import castech.emvtxn.R;
import castech.emvtxn.util.Converter;

public class MyEMVEvent extends EMVEvent {
    private Converter convert = new Converter();
    private byte pinType;
    //private int remainingCounter;
    private MainActivity mainActivity;
    private CtEMV emv;

    private static ViewPager mViewPager;


    public int setUIComponent(ViewPager mViewPager) {
        this.mViewPager = mViewPager;

        return 0;
    }

    public MyEMVEvent(MainActivity InContext) {
        this.mainActivity = InContext;
    }

    public MyEMVEvent(MainActivity InContext, CtEMV emv) {
        this.mainActivity = InContext;
        this.emv = emv;
        if (CtEMV.d_EMVAPLIB_ERR_ONLY_1_AP_NO_FALLBACK == 9) {
            int x = 1;
        }
    }

    //Out	EMVTxnData
    @Override
    public int onTxnDataGet(EMVTxnData txnData) {
        final String TAG = GlobalPara.tag;

        Log.d(TAG, "onTxnDataGet trigger-->");

        byte[] amount;
        byte[] amountOther;
        String strDate;
        String strTime;
        SimpleDateFormat sdf;
        Date dt = new Date();

        sdf = new SimpleDateFormat("yyMMdd");
        strDate = sdf.format(dt);
        sdf = new SimpleDateFormat("HHmmss");
        strTime = sdf.format(dt);

        txnData.version = 3;    //version should be set to 3

        EditText edt = (EditText) this.mainActivity.findViewById(R.id.edtAmt);
        String strAmt = edt.getText().toString();

        if (GlobalPara.isQuickChipTransaction == true) {
            if (strAmt.isEmpty() == true) {
                Debugger.addSTR(TAG, "amount is not yet known, using pre-determined amount");
                strAmt = "200";
            }
        }

        strAmt = convert.amtPadding(strAmt);
        amount = convert.hexString2ByteArray(strAmt);
        System.arraycopy(amount, 0, txnData.amount, 0, 6);                //amount is 6-byte array

        Debugger.addHEX(TAG, "Input amount", txnData.amount, 0, 6);

        txnData.posEntryMode = 0x00;
        txnData.txnType = 0x00;

        Log.d(TAG, "Date" + strDate);
        Log.d(TAG, "Time" + strTime);

        System.arraycopy(strDate.getBytes(Charset.forName("UTF-8")), 0, txnData.txnDate, 0, 6);
        System.arraycopy(strTime.getBytes(Charset.forName("UTF-8")), 0, txnData.txnTime, 0, 6);

        return 0;
    }

    //In	appListExData.appNum;
    //In	appListExData.appInfo[];
    //Out	appListExData.appSelectedIndex;
    @Override
    public int onAppListEx(EMVAppListExData appListExData) {
        final String TAG;

        TAG = GlobalPara.tag;
        GlobalPara.appListOK = false;
        GlobalPara.appSelectedIndex = 0;

        Log.d(TAG, "onAppListEx trigger-->");

        ClsListViewAdapter myAdapter = new ClsListViewAdapter(this.mainActivity);
        for (int i = 0; i < appListExData.appNum; i++) {
            String appLabel = new String(appListExData.appInfo[i].appLabel);
            myAdapter.addItem(appLabel, String.format("App %d\n", i + 1));
            Log.d(TAG, appLabel);
        }

        View view = this.mainActivity.getLayoutInflater().inflate(R.layout.list_view_layout, null);
        ListView lv = (ListView) view.findViewById(R.id.id_ListView);
        lv.setAdapter(myAdapter.getAdapter());
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(final AdapterView<?> adapterView, View view, final int pos, long id) {
                Log.d(TAG, "onAppListEx onItemClick : " + String.format("%d", pos));
                GlobalPara.appSelectedIndex = (byte) (pos);
                GlobalPara.appListOK = true;
                GlobalPara.alertDialog.dismiss();
            }
        });


        final AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this.mainActivity);
        builder.setTitle("Please Select One App to Execute");
        builder.setIcon(R.drawable.arrow);
        builder.setView(view);
        this.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog ad = builder.create();
                ad.getWindow().setBackgroundDrawableResource(R.drawable.alert_dialog_shape);
                ad.setCancelable(false);
                ad.setCanceledOnTouchOutside(false);
                ad.show();
                GlobalPara.alertDialog = ad;
            }
        });

        do {
            MyUtility.sleep(500);
        } while (GlobalPara.appListOK == false);

        //Range of appListExData.appSelectedIndex value is 0 to (appListExData.appNum -1)
        appListExData.appSelectedIndex = GlobalPara.appSelectedIndex;

        return 0;
    }

    //In	isRequiredByCard
    //In	appLabel
    //In	appLabelLen
    @Override
    public int onAppSelectedConfirm(boolean isRequiredByCard, byte[] appLabel, byte appLabelLen) {
        short shRtn = (short) 0xFFFF;
        final String TAG;

        TAG = GlobalPara.tag;
        GlobalPara.appSelectedConfirmOK = 0;

        Log.d(TAG, "onAppSelectedConfirm trigger-->");
        Log.d(TAG, "isRequiredByCard" + String.valueOf(isRequiredByCard));


        final AlertDialog.Builder builder = new AlertDialog.Builder(this.mainActivity);
        builder.setTitle("Please Confirm to Execute Following App");
        builder.setIcon(R.drawable.arrow);
        builder.setMessage(new String(appLabel));

        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                GlobalPara.appSelectedConfirmOK = 1;
            }

        });
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                GlobalPara.appSelectedConfirmOK = 2;
            }

        });


        this.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog ad;
                ad = builder.create();
                ad.setCancelable(false);
                ad.setCanceledOnTouchOutside(false);
                ad.show();
            }
        });

        do {
            MyUtility.sleep(500);
        } while (GlobalPara.appSelectedConfirmOK == 0);


        if (GlobalPara.appSelectedConfirmOK == 1) {
            return 0;
        }

        return 1;
    }

    //IN	type
    //IN	remainingCounter
    //OUT	onlinePinPara
    @Override
    public int onGetPINNotify(byte type, final int remainingCounter, EMVGetPINFuncPara getPinPara) {
        short shRtn = (short) 0xFFFF;
        final String TAG;

        TAG = GlobalPara.tag;

        Log.d(TAG, "onGetPinNotify trigger--> type=" + type + ", remainingCounter=" + remainingCounter);

        getPinPara.version = 1;
        getPinPara.timeout = 60;
        getPinPara.maxPINDigitLength = 8;
        getPinPara.minPINDigitLength = 4;

        // ORIGINAL CASTLE SAMPLE PATTERN:
        // - Offline PIN (type=1): internal PIN pad (isInternalPINPad=1)
        // - Online PIN (type=0): external PIN pad (isInternalPINPad=0)
        //   -> SDK will call eventOnlinePinBlockGet callback
        // - Key location is NOT SET here (original has it commented out)
        //   -> eventOnlinePinBlockGet uses GlobalPara.onlinePinKeySet/Index

        if (type == 1) {
            // Offline PIN - use internal PIN pad
            getPinPara.isInternalPINPad = 1;
            Log.d(TAG, "onGetPINNotify: Offline PIN (type=1), internal PIN pad");
        } else {
            // Online PIN - use EXTERNAL PIN pad -> triggers eventOnlinePinBlockGet
            getPinPara.isInternalPINPad = 0;
            Log.d(TAG, "onGetPINNotify: Online PIN (type=0), EXTERNAL -> eventOnlinePinBlockGet");
        }

        // Set PIN encryption key to C000/0000 where our DUKPT key is injected
        // Castle support: "modify the source code for of the places you injected (C000, 0000)"
        getPinPara.onlinePINCipherKeySet = GlobalPara.onlinePinKeySet;    // 0xC000
        getPinPara.onlinePINCipherKeyIndex = GlobalPara.onlinePinKeyIndex; // 0x0000
        Log.d(TAG, "onGetPINNotify: Key set to C000/0000 (our DUKPT key location)");

        this.pinType = type;
        GlobalPara.remainingCounter = remainingCounter;

        // MUST return 0 - returning 1 causes SDK error 0x1003
        return 0;
    }

    //IN	digitsNum
    @Override
    public void onShowPINDigit(byte digitsNum) {
        final String TAG;
        final int page;

        TAG = GlobalPara.tag;
        Log.d(TAG, "onShowPINDigit trigger-->");

        page = GlobalDef.d_PAGE_PINPAD_EX;

        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 300);
        }

        byte[] pinMaskStr = new byte[32];
        Arrays.fill(pinMaskStr, 0, digitsNum, (byte) '*');
        final String str = new String(pinMaskStr);

        this.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView;
                textView = (TextView) mainActivity.findViewById(R.id.textViewPinDigitEx);

                if (pinType == 0) {
                    textView.setText("1 Enter Online Pin(" + String.valueOf(GlobalPara.remainingCounter) + ") :\n" + str + "\n");
                } else {
                    textView.setText("Enter Offline Pin(" + String.valueOf(GlobalPara.remainingCounter) + ") :\n" + str + "\n");
                }

            }
        });
        Log.d(TAG, "onShowPINDigit returning Num Digits = " + digitsNum);
        return;
    }

    public void onShowPINBypass() {
        final String TAG;
        final int page;

        TAG = GlobalPara.tag;
        Log.d(TAG, "onShowPINBypass trigger-->");

        page = GlobalDef.d_PAGE_TRANSACTION;

        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 500);
        }

        this.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mainActivity.ui_ShowLog("Pin Bypass !");
            }
        });


        return;
    }


    //In	txnResult
    //In	isSignatureRequired
    @Override
    public void onTxnResult(byte txnResult, boolean isSignatureRequired) {
        final String TAG;

        TAG = GlobalPara.tag;
        Log.d(TAG, "onTxnResult trigger-->");

        GlobalPara.isNeedSignature = isSignatureRequired;

        switch (txnResult) {
            case (byte) 0x01:
                GlobalPara.transactionResult = 0x0002;
                break;
            case (byte) 0x02:
                GlobalPara.transactionResult = 0x0003;
                break;
            case (byte) 0x03:
                GlobalPara.transactionResult = 0x0004;
                break;

            default:
                GlobalPara.transactionResult = 0x00FF;
                break;
        }
    }

}
