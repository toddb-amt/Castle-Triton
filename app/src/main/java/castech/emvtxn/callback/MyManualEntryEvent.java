package castech.emvtxn.callback;

import android.util.Log;
import android.widget.TextView;

import androidx.viewpager.widget.ViewPager;

import CTOS.emv.EMVManualEntryEvent;

import castech.emvtxn.GlobalDef;
import castech.emvtxn.MainActivity;
import castech.emvtxn.MyUtility;
import castech.emvtxn.R;

public class MyManualEntryEvent extends EMVManualEntryEvent {
    private MainActivity mainActivity;
    private static ViewPager mViewPager;


    public int setUIComponent(ViewPager mViewPager) {
        this.mViewPager = mViewPager;

        return 0;
    }

    public MyManualEntryEvent(MainActivity InContext) {
        this.mainActivity = InContext;
    }

    private String maskedPAN = "PAN : ";
    private String maskedEXP = "EXP : ";
    private String maskedCSC = "CSC : ";
    //private String maskedADR = "ADR : ";
    private String maskedZIP = "ZIP : ";

    public void clearMaskedPANStr() {
        maskedPAN = "PAN : ";
    }

    public void clearMaskedEXPStr() {
        maskedEXP = "EXP : ";
    }

    public void clearMaskedCSCStr() {
        maskedCSC = "CSC : ";
    }

    //public void clearMaskedADRStr(){maskedADR = "ADR : ";}
    public void clearMaskedZIPStr() {
        maskedZIP = "ZIP : ";
    }


    public void onShowPAN(byte digitChar) {  //Jorge
        Log.d("EMV_AP Event", "onShowPAN trigger-->");

        if (Thread.interrupted() || digitChar == 'C') {
            // We've been interrupted!!
            int page = GlobalDef.d_PAGE_TRANSACTION;
            MyUtility.switchPage(page, 0); //Do some clean up of screen.
            return;
        }
        final int page;
        page = GlobalDef.d_PAGE_MANUAL_ENTRY;


        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 0);
        }

        byte[] temp = new byte[2];
        temp[0] = digitChar;

        if (digitChar == (byte) 0x08)    //if digitChar equals to 0x08, means clear all input(in this case is PAN)
        {
            clearMaskedPANStr();
        } else {
            maskedPAN = maskedPAN + new String(temp, 0, 1);
        }


        final String str = maskedPAN;
        this.mainActivity.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                TextView textView;
                                                textView = (TextView) mainActivity.findViewById(R.id.textViewME_PAN);

                                                textView.setText(str);


                                            }
                                        }
        );


        return;

    }

    public void onShowEXP(byte digitChar) {
        Log.d("EMV_AP Event", "onShowEXP trigger-->");

        final int page;
        page = GlobalDef.d_PAGE_MANUAL_ENTRY;


        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 0);
        }

        byte[] temp = new byte[2];
        temp[0] = digitChar;

        if (digitChar == (byte) 0x08)    //if digitChar equals to 0x08, means clear all input(in this case is EXP)
        {
            clearMaskedEXPStr();
        } else {
            maskedEXP = maskedEXP + new String(temp, 0, 1);
        }


        final String str = maskedEXP;
        this.mainActivity.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                TextView textView;
                                                textView = (TextView) mainActivity.findViewById(R.id.textViewME_EXP);

                                                textView.setText(str);


                                            }
                                        }
        );

        return;
    }

    public void onShowCSC(byte digitChar) {
        Log.d("EMV_AP Event", "onShowCSC trigger-->");

        final int page = GlobalDef.d_PAGE_MANUAL_ENTRY;


        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 0);
        }

        byte[] temp = new byte[2];
        temp[0] = digitChar;

        if (digitChar == (byte) 0x08)//if digitChar equals to 0x08, means clear all input(in this case is CSC)
        {
            clearMaskedCSCStr();
        } else {
            maskedCSC = maskedCSC + new String(temp, 0, 1);
        }


        final String str = maskedCSC;
        this.mainActivity.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                TextView textView;
                                                textView = (TextView) mainActivity.findViewById(R.id.textViewME_CSC);

                                                textView.setText(str);


                                            }
                                        }
        );

        return;

    }

    public void onShowADR(byte digitChar) {
        Log.d("EMV_AP Event", "onShowADR trigger-->");
/*
		final int page = GlobalDef.d_PAGE_MANUAL_ENTRY;

		if(mViewPager.getCurrentItem() != page)
		{
			MyUtility.switchPage(page, 1500);
		}

		byte[] temp = new byte[2];
		temp[0] = digitChar;

		if(digitChar == (byte)0x08)//if digitChar equals to 0x08, means clear all input(in this case is CSC)
		{
			clearMaskedCSCStr();
		}
		else
		{
			maskedCSC = maskedCSC + new String(temp, 0, 1);
		}


		final String str = maskedCSC;
		this.mainActivity.runOnUiThread(new Runnable()
										{
											@Override
											public void run()
											{
												TextView textView;
												textView = (TextView) mainActivity.findViewById(R.id.textViewME_CSC);

												textView.setText(str);


											}
										}
		);
*/
        return;

    }

    public void onShowZIP(byte digitChar) {
        Log.d("EMV_AP Event", "onShowZIP trigger-->");

        final int page = GlobalDef.d_PAGE_MANUAL_ENTRY;

        if (mViewPager.getCurrentItem() != page) {
            MyUtility.switchPage(page, 0);
        }

        byte[] temp = new byte[2];
        temp[0] = digitChar;

        if (digitChar == (byte) 0x08)//if digitChar equals to 0x08, means clear all input(in this case is CSC)
        {
            clearMaskedZIPStr();
        } else {
            maskedZIP = maskedZIP + new String(temp, 0, 1);
        }


        final String str = maskedZIP;
        this.mainActivity.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                TextView textView;
                                                textView = (TextView) mainActivity.findViewById(R.id.textViewME_ZIP);

                                                textView.setText(str);


                                            }
                                        }
        );

        return;

    }
}
