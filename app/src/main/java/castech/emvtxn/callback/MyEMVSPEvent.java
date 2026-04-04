package castech.emvtxn.callback;

import android.app.Activity;
import android.binder.aidl.IKMS2Callback;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.viewpager.widget.ViewPager;

import CTOS.CtEMV;
import CTOS.CtKMS2Dukpt;
import CTOS.CtKMS2FixedKey;
import CTOS.CtKMS2VirtualPINPad;
import CTOS.emv.CAPublicKey;
import CTOS.emv.EMVAppListExData;
import CTOS.emv.EMVOnlinePinData;
import CTOS.emv.TlvData;

import castech.emvtxn.ClsListViewAdapter;
import castech.emvtxn.ClsScreenBroadcastReceiver;
import castech.emvtxn.GlobalDef;
import castech.emvtxn.GlobalPara;
import castech.emvtxn.MainActivity;
import castech.emvtxn.MyUtility;
import castech.emvtxn.R;
import castech.emvtxn.atm.host.PinBlockFormatter;
import castech.emvtxn.atm.host.CastleKeyManager;
import castech.emvtxn.util.Converter;

public class MyEMVSPEvent implements CtEMV.IEventShowVirtualPINEx, CtEMV.IEventGetPINDone, CtEMV.IEventOnlinePinBlockGet, CtEMV.IEventPINBypass, CtEMV.IEventAppListEx, CtEMV.IEventCAPKGet {
    private static Activity activity;
    private static ViewPager mViewPager;
    private static CtEMV emv;
    private final String TAG = GlobalPara.tag;
    private MainActivity mainActivity;

    public int setVirtualPINUIComponent(Activity activity, ViewPager mViewPager) {
        this.activity = activity;
        //this.layout = layout;
        this.mViewPager = mViewPager;

        return 0;
    }


    //for Virtual PIN EX (customize Virtual PIN)
    //return a fixed int[16][5] buffer as keyboard attribute
    public int[][] eventShowVirtualPINEx() {
        Log.d(TAG, "eventShowVirtualPINEx trigger-->");

        final int page = GlobalDef.d_PAGE_PINPAD_EX;

        //Switch Page
        MyUtility.switchPage(page, 500);

        //keyboard attribute is a fixed int[16][5] buffer !!
        int[][] KBDAttribute = new int[16][5];
        TextView[] tv = new TextView[16];
        int[] XY = new int[2];
        int x;
        int y;
        int w;
        int h;


        for (int i = 0; i < 16; i++) {
            switch (i) {
                //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                case 0:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD0);
                    KBDAttribute[i][4] = '0';
                    break;
                case 1:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD1);
                    KBDAttribute[i][4] = '1';
                    break;
                case 2:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD2);
                    KBDAttribute[i][4] = '2';
                    break;
                case 3:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD3);
                    KBDAttribute[i][4] = '3';
                    break;
                case 4:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD4);
                    KBDAttribute[i][4] = '4';
                    break;
                case 5:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD5);
                    KBDAttribute[i][4] = '5';
                    break;
                case 6:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD6);
                    KBDAttribute[i][4] = '6';
                    break;
                case 7:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD7);
                    KBDAttribute[i][4] = '7';
                    break;
                case 8:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD8);
                    KBDAttribute[i][4] = '8';
                    break;
                case 9:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD9);
                    KBDAttribute[i][4] = '9';
                    break;
                case 10:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Cancel);
                    KBDAttribute[i][4] = 'C';
                    break;
                case 11:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Clear);
                    KBDAttribute[i][4] = 'R';
                    break;
                case 12:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Enter);
                    KBDAttribute[i][4] = 'A';
                    break;

                //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                default:
                    KBDAttribute[i][4] = 'S';
                    break;
            }

            if (i > 12 || tv[i] == null) {
                // Use default values if view doesn't exist (ATM mode) or for unused keys
                x = 10;
                y = 10;
                w = 1;
                h = 1;
            } else {
                tv[i].getLocationOnScreen(XY);
                x = XY[0];
                y = XY[1];
                w = tv[i].getWidth();
                h = tv[i].getHeight();
            }


            KBDAttribute[i][0] = x;
            KBDAttribute[i][1] = y;
            KBDAttribute[i][2] = x + w;
            KBDAttribute[i][3] = y + h;

            Log.d("KeyValue = ", String.valueOf(KBDAttribute[i][4]));
            Log.d("location x = ", String.valueOf(x));
            Log.d("location y = ", String.valueOf(y));
            Log.d("location x + width = ", String.valueOf(KBDAttribute[i][2]));
            Log.d("location y + height = ", String.valueOf(KBDAttribute[i][3]));
        }

        if (GlobalPara.scrnBrdcstRecver != null) {
            GlobalPara.scrnBrdcstRecver.setVirtualPINStatus(ClsScreenBroadcastReceiver.d_VPIN_IS_PERFORMING);
        }
        return KBDAttribute;
    }


    public int eventGetPINDone() {
        Log.d(TAG, "eventGetPINDone trigger-->");

        final int page = GlobalDef.d_PAGE_TRANSACTION;

        MyUtility.switchPage(page, 500);

        if (GlobalPara.scrnBrdcstRecver != null) {
            GlobalPara.scrnBrdcstRecver.setVirtualPINStatus(ClsScreenBroadcastReceiver.d_VPIN_IS_NOT_PERFORMING);
        }
        return 0;
    }

    public byte eventPINBypass() {
        Log.d(TAG, "eventPINBypass trigger-->");

        final String TAG;

        TAG = GlobalPara.tag;

        // ATM MODE: Don't bypass - let user enter PIN through internal PIN pad
        // The PIN block will be created by the SDK using our DUKPT key
        if (GlobalPara.atmMode) {
            Log.d(TAG, "eventPINBypass: ATM MODE - NOT bypassing, user must enter PIN");
            return 0x01;  // 0x01 = Don't bypass, continue with PIN entry
        }

        GlobalPara.pinBypassActionOK = false;
        GlobalPara.pinBypassActionIndex = 0;

        ClsListViewAdapter myAdapter = new ClsListViewAdapter(this.activity);
        myAdapter.addItem("Option 1", "Bypass PIN\n");
        myAdapter.addItem("Option 2", "Don't bypass PIN, back to get PIN process\n");
        myAdapter.addItem("Option 3", "Don't bypass PIN, trigger eventShowVirtualPINEx() again \nthen back to get PIN process\n");

        View view = this.activity.getLayoutInflater().inflate(R.layout.list_view_layout, null);
        ListView lv = (ListView) view.findViewById(R.id.id_ListView);
        lv.setAdapter(myAdapter.getAdapter());
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(final AdapterView<?> adapterView, View view, final int pos, long id) {
                Log.d(TAG, "eventPINBypass onItemClick : " + String.format("%d", pos));
                GlobalPara.pinBypassActionIndex = (byte) (pos);
                GlobalPara.pinBypassActionOK = true;
                GlobalPara.alertDialog.dismiss();
            }
        });

        final AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this.activity);
        builder.setTitle("PIN Bypass ?");
        builder.setIcon(R.drawable.arrow);
        builder.setView(view);
        this.activity.runOnUiThread(new Runnable() {
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
        } while (GlobalPara.pinBypassActionOK == false);

        //Return value :
        //0x00 - Bypass PIN
        //0x01 - Don't bypass PIN, back to get PIN process
        //0x02 - Don't bypass PIN, trigger eventShowVirtualPINEx() again then back to get PIN process
        return GlobalPara.pinBypassActionIndex;
    }

    public int eventOnlinePinBlockGet(EMVOnlinePinData onlinePinData) {
        Log.d(TAG, "eventOnlinePinBlockGet() ***");

        // Check if we're in ATM mode
        boolean isATMMode = (GlobalPara.atmSelectedAmount != null && !"0.00".equals(GlobalPara.atmSelectedAmount))
                || GlobalPara.atmBalanceInquiryMode;

        // v6.0-PERF: In ATM mode, SKIP the SDK PIN flow to avoid 30s timeout
        // The SDK PIN flow fails with 0x1003 (wrong key attribute), but only AFTER
        // startVirtualPin() times out waiting for PIN entry. This causes a 20-30 second delay.
        // Instead, return immediately and let post-transaction MVP PIN collection handle it.
        if (isATMMode && GlobalPara.atmDukptEnabled) {
            Log.d(TAG, ">>> v6.0-PERF: ATM DUKPT mode - SKIPPING SDK PIN callback (avoids 30s timeout)");
            Log.d(TAG, ">>> PIN will be collected post-transaction via MVP DUKPT approach");
            onlinePinData.isOnlinePinRquired = false;  // Signal that PIN wasn't collected here
            return -1;  // Return non-zero to indicate PIN was not collected
        }

        final int page = GlobalDef.d_PAGE_PINPAD_EX;

        // ALWAYS switch to PIN pad page - VirtualPINPad needs real button coordinates
        // The PIN pad page has the KBD0-KBD9 buttons that VirtualPINPad intercepts
        MyUtility.switchPage(page, 500);
        Log.d(TAG, "Switched to PIN pad page (ATM mode=" + isATMMode + ")");

        //Get PIN
        final TextView textView;
        final TextView textView2;
        textView = (TextView) this.activity.findViewById(R.id.textViewPinDigitEx);
        textView2 = (TextView) this.activity.findViewById(R.id.textViewKeyInfo);

        // Only update UI if the view exists (not in ATM mode)
        if (textView != null) {
            this.activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textView.setText("2 Enter Online Pin(" + String.valueOf(GlobalPara.remainingCounter) + ") :\n");
                }
            });
        } else {
            Log.d(TAG, "ATM Mode: PIN pad view not available, using KMS2 secure PIN overlay");
        }

        Log.d(TAG, "Start Get PIN");

        IKMS2Callback.Stub callback = new IKMS2Callback.Stub() {
            StringBuffer sb = new StringBuffer();
            byte recv;

            @Override
            public int testCancel() {
                return 0;
            }

            @Override
            public void onGetDigit(byte Digit) {
                Log.d(TAG, String.format("NoDigits = %d, OnGetPINDigit.", Digit));

                recv = Digit;
                sb.setLength(0);
                for (int i = 0; i < recv; i++)
                    sb.append("*");

                if (textView != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView.setText("3 Enter Online Pin(" + String.valueOf(GlobalPara.remainingCounter) + ") :\n" + sb);
                        }
                    });
                }
            }

            @Override
            public void onGetFunctionKey(byte FunctionKey) {
                Log.d(TAG, String.format("FunctionKey = %d, OnGetPINOtherKeys.", FunctionKey));

                recv = FunctionKey;
                sb.setLength(0);
                sb.append(recv);
                if (textView != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            textView.setText("4 Enter Online Pin(" + String.valueOf(GlobalPara.remainingCounter) + ") :\n" + sb);
                        }
                    });
                }
            }
        };

        //byte CipherMethod = 0x00;	//PIN_CIPHER_METHOD_ECB
        byte Null_PIN = 0;
        int Timeout = 60;
        int First_timeout = 20;
        int MaxDigit = 12, MinDigit = 4;
        byte PinBlockType = (byte) 0x00;
        byte outblocklen = 8;

        //Show Key Info
        CTOS.CtKMS2Key key = new CTOS.CtKMS2Key();
        try {
            key.selectKey(GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex);

            String sKeySet = "KeySet = " + String.format("%04X", key.getKeySet());
            String sKeyIndex = "KeyIndex = " + String.format("%04X", key.getKeyIndex());
            String sKeyType = "KeyType = " + String.format("0x%02X", key.getKeyType());
            String sKeyAttribute = "KeyAttribute = " + String.format("%08X", key.getKeyAttribute());

            Log.d("get key info", sKeySet);
            Log.d("get key info", sKeyIndex);
            Log.d("get key info", sKeyType);
            Log.d("get key info", sKeyAttribute);

            GlobalPara.mainActivity.ui_ShowLog(sKeySet);
            GlobalPara.mainActivity.ui_ShowLog(sKeyIndex);
            GlobalPara.mainActivity.ui_ShowLog(sKeyType);
            GlobalPara.mainActivity.ui_ShowLog(sKeyAttribute);

            final String str;
            str = sKeySet + ", " + sKeyIndex + "\n" + sKeyType + ", " + sKeyAttribute;

            if (textView2 != null) {
                this.activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textView2.setText("Key Info\n" + str);
                    }
                });
            }
        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "DUKPT Key startVirtualPin Fail");
            e.showStatus();
            Log.e(TAG, String.format("0x%X", e.getError()));
        }


        // ATM Mode with DUKPT: Use DUKPT key at C001/001A
        // Legacy Mode: Use FixedKey (static key) at C001/00A1
        CtKMS2VirtualPINPad VirtualPINPad = new CtKMS2VirtualPINPad();
        CtKMS2Dukpt dukptKey = null;
        CtKMS2FixedKey fixedKey = null;

        boolean useATMDukpt = isATMMode && GlobalPara.atmDukptEnabled;

        MainActivity mainAct = GlobalPara.mainActivity;

        // ========================================================================
        // ATM MODE with DUKPT: DISABLED - Use standard VirtualPINPad instead
        // The MVP approach with dataEncrypt() bypasses SDK's PIN flow and causes
        // cryptogram tags (9F26, 9F27, 9F36, 9F10) to not be generated
        // ========================================================================
        if (false && useATMDukpt && mainAct != null) {  // DISABLED - use standard VirtualPINPad below
            Log.d(TAG, "========== ATM DUKPT PIN CALLBACK (MVP approach) ==========");
            Log.d(TAG, "Using software PIN pad + CtKMS2Dukpt.getEncryptData()");

            // STEP 1: Read PAN from EMV kernel
            String clearPan = GlobalPara.atmClearPan;
            Log.d(TAG, "  Existing atmClearPan: " + (clearPan != null ? clearPan.length() + " chars" : "null"));

            if (clearPan == null || clearPan.length() < 13) {
                Log.d(TAG, "STEP 1: Reading PAN from EMV kernel (Tag 57/5A)...");

                // Try Tag 57 (Track 2 Equivalent Data) first
                TlvData tag57 = new TlvData();
                tag57.tag = (short) 0x57;
                tag57.len = 40;
                tag57.value = new byte[40];
                int tag57Rtn = mainAct.emv.dataGet(tag57);
                if (tag57Rtn == 0 && tag57.len > 0) {
                    String track2Hex = Converter.byteArray2HexString(tag57.value, tag57.len);
                    Log.d(TAG, "  Tag 57 raw: " + track2Hex);
                    int sepIdx = track2Hex.toUpperCase().indexOf("D");
                    if (sepIdx > 0) {
                        String panFromTrack2 = track2Hex.substring(0, sepIdx);
                        if (panFromTrack2.length() >= 13 && !panFromTrack2.contains("*")) {
                            clearPan = panFromTrack2;
                            GlobalPara.atmClearPan = clearPan;
                            Log.d(TAG, "  Clear PAN from Tag 57: " + clearPan.substring(0, 6) + "****");
                        }
                    }
                }

                // Fallback to Tag 5A (PAN)
                if (clearPan == null || clearPan.length() < 13) {
                    TlvData tag5a = new TlvData();
                    tag5a.tag = (short) 0x5A;
                    tag5a.len = 20;
                    tag5a.value = new byte[20];
                    int tag5aRtn = mainAct.emv.dataGet(tag5a);
                    if (tag5aRtn == 0 && tag5a.len > 0) {
                        String panHex = Converter.byteArray2HexString(tag5a.value, tag5a.len);
                        panHex = panHex.toUpperCase().replaceAll("F+$", "");
                        if (panHex.length() >= 13 && !panHex.contains("*")) {
                            clearPan = panHex;
                            GlobalPara.atmClearPan = clearPan;
                            Log.d(TAG, "  Clear PAN from Tag 5A: " + clearPan.substring(0, 6) + "****");
                        }
                    }
                }
            }

            if (clearPan == null || clearPan.length() < 13) {
                Log.e(TAG, "STEP 1 FAILED: Could not read PAN from EMV kernel");
                onlinePinData.isOnlinePinRquired = false;
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return -1;
            }

            // STEP 2: Show software PIN dialog and collect PIN
            Log.d(TAG, "STEP 2: Showing software PIN dialog...");
            try {
                String clearPin = mainAct.collectPinForCallback();

                if (clearPin == null || clearPin.length() < 4) {
                    Log.e(TAG, "STEP 2 FAILED: PIN cancelled or too short");
                    onlinePinData.isOnlinePinRquired = false;
                    MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                    return -1;
                }
                Log.d(TAG, "  PIN collected, length=" + clearPin.length());

                // STEP 3: Build Format 0 (ISO 9564-1) clear PIN block
                Log.d(TAG, "STEP 3: Building Format 0 PIN block...");

                // PIN block = 0 | PIN_length | PIN_digits | F_padding
                StringBuilder pinPart = new StringBuilder();
                pinPart.append("0");
                pinPart.append(clearPin.length());
                pinPart.append(clearPin);
                while (pinPart.length() < 16) {
                    pinPart.append("F");
                }

                // PAN block = 0000 | rightmost 12 PAN digits (excluding check digit)
                String panRight12 = clearPan.substring(clearPan.length() - 13, clearPan.length() - 1);
                String panBlock = "0000" + panRight12;

                // XOR PIN part with PAN part
                byte[] pinBytes = Converter.hexString2ByteArray(pinPart.toString());
                byte[] panBytes = Converter.hexString2ByteArray(panBlock);
                byte[] clearPinBlock = new byte[8];
                for (int i = 0; i < 8; i++) {
                    clearPinBlock[i] = (byte) (pinBytes[i] ^ panBytes[i]);
                }
                String clearPinBlockHex = Converter.byteArray2HexString(clearPinBlock, 8);
                Log.d(TAG, "  Clear PIN block (Format 0): " + clearPinBlockHex.substring(0, 4) + "****");

                // STEP 4: Encrypt with DUKPT
                Log.d(TAG, "STEP 4: Encrypting with DUKPT at " +
                      String.format("0x%04X/0x%04X", GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));

                CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
                dukpt.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
                dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);  // ECB for PIN blocks
                dukpt.setInputData(clearPinBlock, 0, clearPinBlock.length);
                dukpt.isUseCurrentKey(false);  // Get new derived key

                // Perform encryption
                dukpt.dataEncrypt();

                // Get results
                byte[] encryptedPinBlock = dukpt.getOutpuData();
                byte[] ksn = dukpt.getKSN();
                String encryptedPinHex = Converter.byteArray2HexString(encryptedPinBlock, 8);
                String ksnHex = Converter.byteArray2HexString(ksn, ksn.length);

                Log.d(TAG, "  Encrypted PIN block: " + encryptedPinHex);
                Log.d(TAG, "  KSN: " + ksnHex);

                // STEP 5: Return to EMV kernel
                Log.d(TAG, "STEP 5: Returning encrypted PIN to EMV kernel...");

                onlinePinData.pin = encryptedPinBlock;
                onlinePinData.pinLen = 8;
                onlinePinData.version = 1;
                onlinePinData.isOnlinePinRquired = true;

                // Store for ATM host communication
                GlobalPara.atmEncryptedPinBlock = encryptedPinHex;
                GlobalPara.atmDukptKsn = ksnHex;
                GlobalPara.atmPinCollectedPostTransaction = false;  // PIN collected during EMV flow

                Log.d(TAG, "========== ATM DUKPT PIN SUCCESS ==========");
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return 0;  // Success - EMV kernel will continue with CVM complete

            } catch (Exception e) {
                Log.e(TAG, "ATM DUKPT PIN FAILED: " + e.getMessage());
                e.printStackTrace();
                onlinePinData.isOnlinePinRquired = false;
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return -1;
            }
        }

        // ATM MODE: Use MKSK hardware key at C000/0010 (FutureX slot 10)
        // v5.6: MKSK key provides proper encryption attribute, avoiding SDK error 0x1003

        // Check if ATM mode - we use MKSK encryption
        if (isATMMode && !useATMDukpt) {
            Log.d(TAG, "========== ATM PIN CALLBACK ENTRY (v5.6 MKSK) ==========");
            Log.d(TAG, "Checking MKSK PIN prerequisites...");

            boolean hasMainAct = mainAct != null;
            boolean hasHostService = hasMainAct && mainAct.atmHostService != null;
            boolean hasKeyManager = hasHostService && mainAct.atmHostService.getKeyManager() != null;
            boolean hasMkskKey = hasKeyManager && mainAct.atmHostService.getKeyManager().isMkskAtC001Available();
            boolean hasWorkingKey = hasKeyManager && mainAct.atmHostService.getKeyManager().isWorkingKeyLoaded();

            Log.d(TAG, "  hasMainAct=" + hasMainAct);
            Log.d(TAG, "  hasHostService=" + hasHostService);
            Log.d(TAG, "  hasKeyManager=" + hasKeyManager);
            Log.d(TAG, "  hasMkskKey=" + hasMkskKey);
            Log.d(TAG, "  hasWorkingKey=" + hasWorkingKey);

            if (hasKeyManager) {
                String kcv = mainAct.atmHostService.getKeyManager().getWorkingKeyCheckValue();
                Log.d(TAG, "  workingKeyKCV=" + (kcv != null ? kcv : "null"));
            }

            // v5.6: Allow if MKSK key exists OR software key loaded
            if (!hasMkskKey && !hasWorkingKey) {
                Log.e(TAG, "ATM MODE: Cannot encrypt PIN - no MKSK key and no software key!");
                Log.e(TAG, "ATM MODE: Inject key via FutureX tab in Key Injection Tool");
                onlinePinData.isOnlinePinRquired = false;
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return -1;
            }

            Log.d(TAG, "Prerequisites met - proceeding with MKSK/software PIN");
        }

        // v5.6: Check for MKSK OR software key availability
        if (isATMMode && !useATMDukpt && mainAct != null && mainAct.atmHostService != null &&
            mainAct.atmHostService.getKeyManager() != null &&
            (mainAct.atmHostService.getKeyManager().isMkskAtC001Available() ||
             mainAct.atmHostService.getKeyManager().isWorkingKeyLoaded())) {

            Log.d(TAG, "=== ATM MODE: Using MKSK PIN encryption (v5.6) ===");
            Log.d(TAG, "STEP 1: Reading PAN from EMV kernel...");

            // CRITICAL: Read PAN from EMV kernel FIRST (before PIN collection)
            // The PAN must be available before we can create Format 0 PIN block
            String clearPan = GlobalPara.atmClearPan;
            Log.d(TAG, "  Existing atmClearPan: " + (clearPan != null ? clearPan.length() + " chars" : "null"));
            if (clearPan == null || clearPan.length() < 13) {
                Log.d(TAG, "ATM PIN: Reading PAN from EMV kernel (Tag 57/5A)...");

                // Try Tag 57 (Track 2 Equivalent Data) first
                TlvData tag57 = new TlvData();
                tag57.tag = (short) 0x57;
                tag57.len = 40;
                tag57.value = new byte[40];
                int tag57Rtn = mainAct.emv.dataGet(tag57);
                if (tag57Rtn == 0 && tag57.len > 0) {
                    String track2Hex = Converter.byteArray2HexString(tag57.value, tag57.len);
                    Log.d(TAG, "ATM PIN: Tag 57 raw: " + track2Hex);
                    int sepIdx = track2Hex.toUpperCase().indexOf("D");
                    if (sepIdx > 0) {
                        String panFromTrack2 = track2Hex.substring(0, sepIdx);
                        if (panFromTrack2.length() >= 13 && !panFromTrack2.contains("*")) {
                            clearPan = panFromTrack2;
                            GlobalPara.atmClearPan = clearPan;
                            Log.d(TAG, "ATM PIN: Got PAN from Tag 57: " + clearPan.substring(0, 6) + "****");
                        }
                    }
                }

                // If Tag 57 didn't work, try Tag 5A (PAN)
                if (clearPan == null || clearPan.length() < 13) {
                    TlvData tag5a = new TlvData();
                    tag5a.tag = (short) 0x5A;
                    tag5a.len = 20;
                    tag5a.value = new byte[20];
                    int tag5aRtn = mainAct.emv.dataGet(tag5a);
                    if (tag5aRtn == 0 && tag5a.len > 0) {
                        String panHex = Converter.byteArray2HexString(tag5a.value, tag5a.len);
                        panHex = panHex.toUpperCase().replaceAll("F+$", "");
                        if (panHex.length() >= 13 && !panHex.contains("*")) {
                            clearPan = panHex;
                            GlobalPara.atmClearPan = clearPan;
                            Log.d(TAG, "ATM PIN: Got PAN from Tag 5A: " + clearPan.substring(0, 6) + "****");
                        }
                    }
                }

                if (clearPan == null || clearPan.length() < 13) {
                    Log.e(TAG, "ATM PIN: CRITICAL - Could not read PAN from EMV kernel!");
                }
            } else {
                Log.d(TAG, "ATM PIN: Using existing PAN: " + clearPan.substring(0, 6) + "****");
            }

            Log.d(TAG, "STEP 2: Collecting PIN via software PIN pad...");
            Log.d(TAG, "  clearPan length: " + (clearPan != null ? clearPan.length() : 0));

            // Collect PIN using software PIN pad
            String pin = mainAct.collectPinForCallback();
            Log.d(TAG, "STEP 3: PIN collection result: " + (pin != null ? pin.length() + " digits" : "null/cancelled"));

            if (pin != null && pin.length() >= 4) {
                if (clearPan != null && clearPan.length() >= 13) {
                    try {
                        Log.d(TAG, "STEP 4: Creating Format 0 PIN block...");
                        // Create Format 0 PIN block
                        String clearPinBlock = PinBlockFormatter.createFormat0PinBlock(pin, clearPan);
                        Log.d(TAG, "  PIN block created: " + (clearPinBlock != null ? clearPinBlock.length() + " chars" : "null"));

                        Log.d(TAG, "STEP 5: Encrypting PIN with MKSK at C000/0010...");
                        // v5.6: Use MKSK hardware key at C000/0010 (FutureX slot 10)
                        String encryptedPinHex = mainAct.atmHostService.getKeyManager().encryptPinWithMkskAtC001(clearPinBlock);
                        Log.d(TAG, "  MKSK encrypted result: " + (encryptedPinHex != null ? encryptedPinHex.length() + " chars" : "null"));

                        // Fallback to software if MKSK fails
                        if (encryptedPinHex == null) {
                            Log.w(TAG, "STEP 5b: MKSK failed, trying software encryption...");
                            encryptedPinHex = mainAct.atmHostService.getKeyManager().encryptPinBlock(clearPinBlock);
                            Log.d(TAG, "  Software encrypted result: " + (encryptedPinHex != null ? encryptedPinHex.length() + " chars" : "null"));
                        }

                        if (encryptedPinHex != null && encryptedPinHex.length() == 16) {
                            byte[] encryptedPin = Converter.hexString2ByteArray(encryptedPinHex);

                            onlinePinData.pin = encryptedPin;
                            onlinePinData.pinLen = 8;
                            onlinePinData.version = 1;
                            onlinePinData.isOnlinePinRquired = true;

                            // Store for transaction
                            GlobalPara.atmEncryptedPinBlock = encryptedPinHex;
                            GlobalPara.atmPinCollectedPostTransaction = false;

                            Log.d(TAG, "========== ATM PIN SUCCESS ==========");
                            Log.d(TAG, "  Encrypted PIN block: " + encryptedPinHex);
                            Log.d(TAG, "  Returning 0 to EMV kernel");

                            // Switch back and return - EMV kernel will continue with valid PIN
                            MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                            return 0;
                        } else {
                            Log.e(TAG, "STEP 5 FAILED: Encryption returned invalid result");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "STEP 4/5 FAILED: Exception: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    Log.e(TAG, "STEP 3 FAILED: No valid PAN (length=" + (clearPan != null ? clearPan.length() : 0) + ")");
                }
            } else {
                Log.e(TAG, "STEP 2 FAILED: PIN null or too short");
            }

            // ATM MODE: Software PIN path failed - DON'T fall through to KMS2
            Log.e(TAG, "========== ATM PIN FAILED ==========");
            Log.e(TAG, "Returning -1 to EMV kernel (no KMS2 fallback)");
            onlinePinData.isOnlinePinRquired = false;
            MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
            return -1;
        }

        //Set Pin control and callback
        try {
            if (useATMDukpt) {
                // ATM Mode: Use DUKPT for PIN encryption (Format 0 with DUKPT PIN key variant)
                Log.d(TAG, "ATM Mode: Using DUKPT for PIN encryption at " +
                      String.format("0x%04X/0x%04X", GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex));

                dukptKey = new CtKMS2Dukpt();
                dukptKey.selectKey(GlobalPara.atmDukptKeySet, GlobalPara.atmDukptKeyIndex);
                dukptKey.setCipherMethod(CtKMS2Dukpt.PIN_CIPHER_METHOD_ECB);
                dukptKey.isUseCurrentKey(false);  // Get new derived key

                // PinBlockType 0x00 = ISO-0 (Format 0) - requires PAN for XOR
                dukptKey.setPinInfo(PinBlockType, (byte) MaxDigit, (byte) MinDigit, outblocklen);
                dukptKey.setPinControl(CtKMS2Dukpt.PIN_BLOCKTYPE_ANSI_X9_8_ISO_4, Timeout, First_timeout);
                dukptKey.setCallback(callback);

            } else {
                // Legacy Mode: Use FixedKey (static key)
                Log.d(TAG, "Legacy Mode: Using FixedKey for PIN encryption at " +
                      String.format("0x%04X/0x%04X", GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex));

                fixedKey = new CtKMS2FixedKey();
                fixedKey.selectKey(GlobalPara.onlinePinKeySet, GlobalPara.onlinePinKeyIndex);
                fixedKey.setCipherMethod(CtKMS2FixedKey.PIN_CIPHER_METHOD_ECB);

                // PinBlockType 0x00 = ISO-0 (Format 0) - requires PAN for XOR
                // SDK automatically uses card's internal clear PAN
                fixedKey.setPinInfo(PinBlockType, (byte) MaxDigit, (byte) MinDigit, outblocklen);
                fixedKey.setPinControl(Null_PIN, Timeout, First_timeout);
                fixedKey.setCallback(callback);
            }

        } catch (Exception e) {
            Log.e(TAG, "PIN key setup failed: " + e.toString());
        }

        int[][] KBDAttribute = new int[16][5];

        TextView[] tv = new TextView[16];
        int[] XY = new int[2];
        int x;
        int y;
        int w;
        int h;

        for (int i = 0; i < 16; i++) {
            switch (i) {
                //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                case 0:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD0);
                    KBDAttribute[i][4] = '0';
                    break;
                case 1:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD1);
                    KBDAttribute[i][4] = '1';
                    break;
                case 2:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD2);
                    KBDAttribute[i][4] = '2';
                    break;
                case 3:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD3);
                    KBDAttribute[i][4] = '3';
                    break;
                case 4:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD4);
                    KBDAttribute[i][4] = '4';
                    break;
                case 5:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD5);
                    KBDAttribute[i][4] = '5';
                    break;
                case 6:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD6);
                    KBDAttribute[i][4] = '6';
                    break;
                case 7:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD7);
                    KBDAttribute[i][4] = '7';
                    break;
                case 8:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD8);
                    KBDAttribute[i][4] = '8';
                    break;
                case 9:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD9);
                    KBDAttribute[i][4] = '9';
                    break;
                case 10:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Enter);
                    KBDAttribute[i][4] = 'A';
                    break;
                case 11:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Clear);
                    KBDAttribute[i][4] = 'R';
                    break;
                case 12:
                    tv[i] = (TextView) this.activity.findViewById(R.id.KBD_Cancel);
                    KBDAttribute[i][4] = 'C';
                    break;
                //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                default:
                    KBDAttribute[i][4] = 'S';
                    break;
            }

            if (i > 12 || tv[i] == null) {
                // Use default values if view doesn't exist (ATM mode) or for unused keys
                x = 10;
                y = 10;
                w = 1;
                h = 1;
            } else {
                tv[i].getLocationOnScreen(XY);
                x = XY[0];
                y = XY[1];
                w = tv[i].getWidth();
                h = tv[i].getHeight();
            }

            KBDAttribute[i][0] = x;
            KBDAttribute[i][1] = y;
            KBDAttribute[i][2] = w;
            KBDAttribute[i][3] = h;

            Log.d("KeyValue = ", String.valueOf(KBDAttribute[i][4]));
            Log.d("location x = ", String.valueOf(x));
            Log.d("location y = ", String.valueOf(y));
            Log.d("location width = ", String.valueOf(KBDAttribute[i][2]));
            Log.d("location height = ", String.valueOf(KBDAttribute[i][3]));

            switch (i) {
                //set key value for key borad 0 ~ 9, enter, cancel, clear(backspace)
                case 0:
                    VirtualPINPad.VKBD_0.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_0.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_0.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_0.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_0.value = (byte) '0';
                    break;
                case 1:
                    VirtualPINPad.VKBD_1.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_1.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_1.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_1.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_1.value = (byte) '1';
                    break;
                case 2:
                    VirtualPINPad.VKBD_2.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_2.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_2.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_2.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_2.value = (byte) '2';
                    break;
                case 3:
                    VirtualPINPad.VKBD_3.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_3.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_3.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_3.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_3.value = (byte) '3';
                    break;
                case 4:
                    VirtualPINPad.VKBD_4.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_4.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_4.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_4.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_4.value = (byte) '4';
                    break;
                case 5:
                    VirtualPINPad.VKBD_5.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_5.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_5.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_5.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_5.value = (byte) '5';
                    break;
                case 6:
                    VirtualPINPad.VKBD_6.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_6.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_6.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_6.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_6.value = (byte) '6';
                    break;
                case 7:
                    VirtualPINPad.VKBD_7.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_7.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_7.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_7.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_7.value = (byte) '7';
                    break;
                case 8:
                    VirtualPINPad.VKBD_8.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_8.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_8.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_8.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_8.value = (byte) '8';
                    break;
                case 9:
                    VirtualPINPad.VKBD_9.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_9.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_9.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_9.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_9.value = (byte) '9';
                    break;
                case 10://enter
                    VirtualPINPad.VKBD_10.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_10.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_10.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_10.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_10.value = (byte) 'A';
                    break;
                case 11://clear
                    VirtualPINPad.VKBD_11.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_11.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_11.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_11.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_11.value = (byte) 'R';
                    break;
                case 12://cancel
                    VirtualPINPad.VKBD_12.x = KBDAttribute[i][0];
                    VirtualPINPad.VKBD_12.y = KBDAttribute[i][1];
                    VirtualPINPad.VKBD_12.width = KBDAttribute[i][2];
                    VirtualPINPad.VKBD_12.height = KBDAttribute[i][3];
                    VirtualPINPad.VKBD_12.value = (byte) 'C';
                    break;
                case 13:
                    VirtualPINPad.VKBD_13.x = 0;
                    VirtualPINPad.VKBD_13.y = 0;
                    VirtualPINPad.VKBD_13.value = (byte) 0x53;
                    break;
                case 14:
                    VirtualPINPad.VKBD_14.x = 0;
                    VirtualPINPad.VKBD_14.y = 0;
                    VirtualPINPad.VKBD_14.value = (byte) 0x53;
                    break;
                case 15:
                    VirtualPINPad.VKBD_15.x = 0;
                    VirtualPINPad.VKBD_15.y = 0;
                    VirtualPINPad.VKBD_15.value = (byte) 0x53;
                    break;

                //set the key value to 'S' for key board not in above(key borad 0 ~ 9, enter, cancel, clear)
                default:
                    break;
            }
        }


        try {
            if (useATMDukpt && dukptKey != null) {
                // ATM Mode: Use DUKPT
                dukptKey.startVirtualPin(VirtualPINPad);
                byte[] pinBlock = dukptKey.getOutpuData();
                byte[] ksn = dukptKey.getKSN();

                Log.d(TAG, "ATM DUKPT PIN block (hex): " + Converter.byteArray2HexString(pinBlock, outblocklen));
                Log.d(TAG, "ATM DUKPT KSN (hex): " + Converter.byteArray2HexString(ksn, ksn.length));

                onlinePinData.pin = pinBlock;
                onlinePinData.pinLen = outblocklen;
                onlinePinData.version = 1;
                onlinePinData.isOnlinePinRquired = true;

                // Store for ATM host communication
                GlobalPara.atmEncryptedPinBlock = Converter.byteArray2HexString(pinBlock, outblocklen);
                GlobalPara.atmDukptKsn = Converter.byteArray2HexString(ksn, ksn.length);

                GlobalPara.mainActivity.ui_ShowLog("DUKPT PIN block: " + GlobalPara.atmEncryptedPinBlock);
                GlobalPara.mainActivity.ui_ShowLog("DUKPT KSN: " + GlobalPara.atmDukptKsn);

            } else if (fixedKey != null) {
                // Legacy Mode: Use FixedKey
                fixedKey.startVirtualPin(VirtualPINPad);
                Log.d(TAG, "FixedKey ISO-0 PIN block (hex): " + Converter.byteArray2HexString(fixedKey.getOutpuData(), outblocklen));

                onlinePinData.pin = fixedKey.getOutpuData();
                onlinePinData.pinLen = outblocklen;
                onlinePinData.version = 1;
                onlinePinData.isOnlinePinRquired = true;

                // No KSN for FixedKey (static key, not DUKPT)
                GlobalPara.mainActivity.ui_ShowLog("ISO-0 Pin block (FixedKey): " + Converter.byteArray2HexString(fixedKey.getOutpuData(), outblocklen));

                // Store encrypted PIN block for ATM host communication
                GlobalPara.atmEncryptedPinBlock = Converter.byteArray2HexString(fixedKey.getOutpuData(), outblocklen);
                Log.d(TAG, "ATM: Stored ISO-0 PIN block (FixedKey): " + GlobalPara.atmEncryptedPinBlock);
            }
        } catch (CTOS.CtKMS2Exception e) {
            Log.e(TAG, "startVirtualPin Fail: " + String.format("0x%X", e.getError()));
            e.showStatus();
            GlobalPara.mainActivity.ui_ShowLog("startVirtualPin Rtn : " + String.format("0x%X", e.getError()));

            // ATM MODE: KMS2 failed (likely 0x1003 - no key at slot)
            // With software PIN fix (reading PAN directly), we should NOT reach here in ATM mode
            // If we do reach here, return error to cancel transaction properly
            if (GlobalPara.atmMode) {
                Log.e(TAG, "ATM MODE: KMS2 fallback reached - software PIN path should have handled this!");
                Log.e(TAG, "ATM MODE: Returning PIN bypass to avoid placeholder PIN issue");
                onlinePinData.isOnlinePinRquired = false;
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return -1;  // Return error - don't fall through to return 0
            }
        } catch (Exception e) {
            Log.e(TAG, "startVirtualPin Fail: " + e.toString());

            // ATM MODE: Same handling - don't provide placeholder PIN
            if (GlobalPara.atmMode) {
                Log.e(TAG, "ATM MODE: PIN exception in KMS2 fallback");
                onlinePinData.isOnlinePinRquired = false;
                MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);
                return -1;  // Return error - don't fall through to return 0
            }
        }

        GlobalPara.mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.setText(" ");
                textView2.setText(" ");
            }
        });


        //Switch back
        MyUtility.switchPage(GlobalDef.d_PAGE_TRANSACTION, 500);


        return 0;
    }

    public int eventAppListEx(EMVAppListExData appListExData) {
        final String TAG;

        TAG = GlobalPara.tag;
        GlobalPara.appListOK = false;
        GlobalPara.appSelectedIndex = 0;

        Log.d(TAG, "eventAppListEx trigger-->");

        ClsListViewAdapter myAdapter = new ClsListViewAdapter(this.mainActivity);
        for (int i = 0; i < appListExData.appNum; i++) {
            String appLabel = new String(appListExData.appInfo[i].appLabel);
            myAdapter.addItem(appLabel, String.format("App %d\n", i + 1));
            Log.d(TAG, "appLabel: " + appLabel);
            Log.d(TAG, "aid: " + Converter.byteArray2HexString(appListExData.appInfo[i].aid, appListExData.appInfo[i].aidLen));
        }

        //Range of appListExData.appSelectedIndex value is 0 to (appListExData.appNum -1)
        appListExData.appSelectedIndex = 0;
        //appListExData.appSelectedIndex = GlobalPara.appSelectedIndex;

        return 0;
    }

    /**
     * IEventCAPKGet callback - SDK calls this to request CAPK during chip transaction
     * Returns true if CAPK found and populated, false otherwise
     */
    @Override
    public boolean eventCapkGet(byte[] rid, CAPublicKey capk) {
        String ridHex = Converter.byteArray2HexString(rid, 5);
        Log.d(TAG, ">>> eventCapkGet called for RID: " + ridHex + ", index: " + String.format("0x%02X", capk.index));

        // Check if this is US Common Debit (A000000098) or VISA (A000000003)
        if (!ridHex.equalsIgnoreCase("A000000098") && !ridHex.equalsIgnoreCase("A000000003")) {
            Log.w(TAG, ">>> eventCapkGet: Unknown RID " + ridHex);
            return false;
        }

        // Populate CAPK based on index requested
        switch (capk.index) {
            case 0x05:
                // VISA test key (1152-bit)
                capk.modulusLen = 144;
                capk.modulus = Converter.hexString2ByteArray("BE9E1FA5E9A803852999C4AB432DB28600DCD9DAB76DFAAA47355A0FE37B1508AC6BF38860D3C6C2E5B12A3CAAF2A7005A7241EBAA7771112C74CF9A0634652FBCA0E5980C54A64761EA101A114E0F0B5572ADD57D010B7C9C887E104CA4EE1272DA66D997B9A90B5A6D624AB6C57E73C8F919000EB5F684898EF8C3DBEFB330C62660BED88EA78E909AFF05F6DA627B");
                capk.exponentLen = 1;
                capk.exponent = Converter.hexString2ByteArray("03");
                capk.hash = Converter.hexString2ByteArray("EE1511CEC71020A9B90443B37B1D5F6E703030F6");
                Log.d(TAG, ">>> eventCapkGet: Returning CAPK 05 (test key, 1152-bit)");
                return true;

            case 0x07:
                // VISA production key (1152-bit)
                capk.modulusLen = 144;
                capk.modulus = Converter.hexString2ByteArray("A89F25A56FA6DA258C8CA8B40427D927B4A1EB4D7EA326BBB12F97DED70AE5E4480FC9C5E8A972177110A1CC318D06D2F8F5C4844AC5FA79A4DC470BB11ED635699C17081B90F1B984F12E92C1C529276D8AF8EC7F28492097D8CD5BECEA16FE4088F6CFAB4A1B42328A1B996F9278B0B7E3311CA5EF856C2F888474B83612A82E4E00D0CD4069A6783140433D50725F");
                capk.exponentLen = 1;
                capk.exponent = Converter.hexString2ByteArray("03");
                capk.hash = Converter.hexString2ByteArray("B4BC56CC4E88324932CBC643D6898F6FE593B172");
                Log.d(TAG, ">>> eventCapkGet: Returning CAPK 07 (production, 1152-bit)");
                return true;

            case 0x08:
                // VISA production key (1408-bit)
                capk.modulusLen = 176;
                capk.modulus = Converter.hexString2ByteArray("D9FD6ED75D51D0E30664BD157023EAA1FFA871E4DA65672B863D255E81E137A51DE4F72BCC9E44ACE12127F87E263D3AF9DD9CF35CA4A7B01E907000BA85D24954C2FCA3074825DDD4C0C8F186CB020F683E02F2DEAD3969133F06F7845166ACEB57CA0FC2603445469811D293BFEFBAFAB57631B3DD91E796BF850A25012F1AE38F05AA5C4D6D03B1DC2E568612785938BBC9B3CD3A910C1DA55A5A9218ACE0F7A21287752682F15832A678D6E1ED0B");
                capk.exponentLen = 1;
                capk.exponent = Converter.hexString2ByteArray("03");
                capk.hash = Converter.hexString2ByteArray("20D213126955DE205ADC2FD2822BD22DE21CF9A8");
                Log.d(TAG, ">>> eventCapkGet: Returning CAPK 08 (production, 1408-bit)");
                return true;

            case 0x09:
                // VISA production key (1984-bit) - most commonly used
                capk.modulusLen = 248;
                capk.modulus = Converter.hexString2ByteArray("9D912248DE0A4E39C1A7DDE3F6D2588992C1A4095AFBD1824D1BA74847F2BC4926D2EFD904B4B54954CD189A54C5D1179654F8F9B0D2AB5F0357EB642FEDA95D3912C6576945FAB897E7062CAA44A4AA06B8FE6E3DBA18AF6AE3738E30429EE9BE03427C9D64F695FA8CAB4BFE376853EA34AD1D76BFCAD15908C077FFE6DC5521ECEF5D278A96E26F57359FFAEDA19434B937F1AD999DC5C41EB11935B44C18100E857F431A4A5A6BB65114F174C2D7B59FDF237D6BB1DD0916E644D709DED56481477C75D95CDD68254615F7740EC07F330AC5D67BCD75BF23D28A140826C026DBDE971A37CD3EF9B8DF644AC385010501EFC6509D7A41");
                capk.exponentLen = 1;
                capk.exponent = Converter.hexString2ByteArray("03");
                capk.hash = Converter.hexString2ByteArray("1FF80A40173F52D7D27E0F26A146A1C8CCB29046");
                Log.d(TAG, ">>> eventCapkGet: Returning CAPK 09 (production, 1984-bit)");
                return true;

            case (byte)0x92:
                // VISA production key (1408-bit)
                capk.modulusLen = 176;
                capk.modulus = Converter.hexString2ByteArray("996AF56F569187D09293C14810450ED8EE3357397B18A2458EFAA92DA3B6DF6514EC060195318FD43BE9B8F0CC669E3F844057CBDDF8BDA191BB64473BC8DC9A730DB8F6B4EDE3924186FFD9B8C7735789C23A36BA0B8AF65372EB57EA5D89E7D14E9C7B6B557460F10885DA16AC923F15AF3758F0F03EBD3C5C2C949CBA306DB44E6A2C076C5F67E281D7EF56785DC4D75945E491F01918800A9E2DC66F60080566CE0DAF8D17EAD46AD8E30A247C9F");
                capk.exponentLen = 1;
                capk.exponent = Converter.hexString2ByteArray("03");
                capk.hash = Converter.hexString2ByteArray("429C954A3859CEF91295F663C963E582ED6EB253");
                Log.d(TAG, ">>> eventCapkGet: Returning CAPK 92 (production, 1408-bit)");
                return true;

            default:
                Log.w(TAG, ">>> eventCapkGet: Unknown CAPK index " + String.format("0x%02X", capk.index) + " for RID " + ridHex);
                return false;
        }
    }

}
