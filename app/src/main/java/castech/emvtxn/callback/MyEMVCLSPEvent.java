package castech.emvtxn.callback;

import android.util.Log;

import CTOS.CtEMVCL;
import CTOS.emvcl.EMVCLUIReqData;

import castech.emvtxn.GlobalPara;
import castech.emvtxn.MyUtility;

public class MyEMVCLSPEvent implements CtEMVCL.IEventEMVCLLEDPicShow, CtEMVCL.IEventEMVCLAudioIndication, CtEMVCL.IEventEMVCLShowMessage {
    public int eventEMVCLLEDPicShow(byte bIndex, byte bOnOff) {
        if (GlobalPara.clLED != null) {
            GlobalPara.clLED.eventEMVCLLEDPicShow(bIndex, bOnOff);
        }
        return 0;
    }

    public int eventEMVCLAudioIndication(short tone) {
        if (GlobalPara.audio != null) {
            if (tone == 0x01) {
                GlobalPara.audio.playOKSound();
            } else {
                GlobalPara.audio.playAlertSound();
            }
        }
        Log.d("text", "tone value:=" + String.valueOf(tone));
        return 0;
    }

    public int eventEMVCLShowMessage(byte[] KernelId, byte KernelIdLen, final EMVCLUIReqData UIReqData) {
        Thread th;

        th = new Thread(new Runnable() {
            @Override
            public void run() {
                switch (UIReqData.messageIdentifier) {
                    case (byte) 0x03:
                        GlobalPara.mainActivity.ui_ShowMsg("Transaction Approved");
                        break;

                    case (byte) 0x07:
                        GlobalPara.mainActivity.ui_ShowMsg("Transaction Declined");
                        break;

                    case (byte) 0x09:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Enter PIN:");
                        break;

                    case (byte) 0x0F:
                        GlobalPara.mainActivity.ui_ShowMsg("Processing Error");
                        break;

                    case (byte) 0x10:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Remove Card");
                        break;

                    case (byte) 0x14:
                        GlobalPara.mainActivity.ui_ShowMsg("Welcome");
                        break;

                    case (byte) 0x15:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Present card");
                        break;

                    case (byte) 0x16:
                        GlobalPara.mainActivity.ui_ShowMsg("Processing ...");
                        break;

                    case (byte) 0x17:
                        GlobalPara.mainActivity.ui_ShowMsg("Card read OK");
                        break;

                    case (byte) 0x18:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Insert or swipe card");
                        break;

                    case (byte) 0x19:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Present One card only");
                        break;

                    case (byte) 0x1A:
                        GlobalPara.mainActivity.ui_ShowMsg("Transaction Approved, please sign");
                        break;

                    case (byte) 0x1B:
                        GlobalPara.mainActivity.ui_ShowMsg("Authorising, please wait");
                        break;

                    case (byte) 0x1C:
                        GlobalPara.mainActivity.ui_ShowMsg("Error, Please try other card");
                        break;

                    case (byte) 0x1D:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Insert Card");
                        break;

                    case (byte) 0x1E:
                        //message clear command
                        GlobalPara.mainActivity.ui_ShowMsg("                                        ");
                        break;

                    case (byte) 0x20:
                        GlobalPara.mainActivity.ui_ShowMsg("Please refer to device for Instruction");
                        break;

                    case (byte) 0x21:
                        GlobalPara.mainActivity.ui_ShowMsg("Please Try again");
                        break;

                    case (byte) 0xA0:
                        GlobalPara.mainActivity.ui_ShowMsg("No card present");
                        break;

                    case (byte) 0xA1:
                        GlobalPara.mainActivity.ui_ShowMsg("Card read Failure");
                        break;

                    case (byte) 0xA2:
                        GlobalPara.mainActivity.ui_ShowMsg("Application Not Supported");
                        break;

                    default:
                        GlobalPara.mainActivity.ui_ShowMsg("None define Message");
                        break;
                }
            }
        });

        th.start();

        if (UIReqData.messageIdentifier == (byte) 0x15 || UIReqData.messageIdentifier == (byte) 0x16)    //"present card", "processing" do not delay
        {

        } else if (UIReqData.messageIdentifier == (byte) 0x17)        //"Read Card OK"
        {
            MyUtility.sleep(800);
        } else {
            MyUtility.sleep(700);
        }

        Log.d("text", "eventEMVCLShowMessage AP");
        return 0;
    }
}
