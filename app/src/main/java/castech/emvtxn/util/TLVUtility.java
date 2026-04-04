package castech.emvtxn.util;

import android.util.Log;

import java.util.Arrays;

import CTOS.emv.TlvData;

import castech.emvtxn.Debugger;

public class TLVUtility {
    String TAG = "TLVUtility";

    public int intTLVDataBaseLen;
    public byte TLVDataBase[] = new byte[4096];

    public int TLVDataGet(TlvData tlv) {
        TlvData tempData = new TlvData();
        int[] lens = new int[3];
        int curTagLen = 0;
        int curLenLen = 0;
        int curValueLen = 0;
        int i = 0;
        int intRtn;

        tlv.len = 0;
        tempData.value = new byte[1024];

        while (i < intTLVDataBaseLen) {
            lens[0] = lens[1] = lens[2] = 0;

            intRtn = getTLV(TLVDataBase, i, tempData, lens, false);
            if (intRtn != 0) {
                return 1;
            }

            curTagLen = lens[0];
            curLenLen = lens[1];
            curValueLen = lens[2];
            if (tlv.tag == tempData.tag) {
                System.arraycopy(tempData.value, 0, tlv.value, 0, tempData.len);
                tlv.len = tempData.len;
                return 0;
            }
            i += curValueLen + curTagLen + curLenLen;
        }

        return 1;
    }

    public void TLVDataParse(byte[] buffer, int len) {
        TlvData tempTlvData = new TlvData();
        int[] lens = new int[3];
        int curTagLen = 0;
        int curLenLen = 0;
        int curValueLen = 0;
        int i = 0;
        int intRtn;

        tempTlvData.value = new byte[1024];

        while (i < len) {
            Arrays.fill(tempTlvData.value, (byte) 0);
            lens[0] = lens[1] = lens[2] = 0;

            intRtn = getTLV(buffer, i, tempTlvData, lens, true);
            if (intRtn != 0) {
                return;
            }

            curTagLen = lens[0];
            curLenLen = lens[1];
            curValueLen = lens[2];
            i += (curValueLen + curTagLen + curLenLen);

            TLVDataAdd(tempTlvData);
        }
    }

    public int getTLV(byte[] buffer, int index, TlvData tlv, int[] lens, boolean isShowlog) {
        int tagLen = 0;
        int lengthLen = 0;

        if ((buffer[index] & 0x0000001F) == 0x0000001F) {
            if ((buffer[index + 1] & 0x00000080) == 0x00000080) {
                if ((buffer[index + 2] & 0x00000080) == 0x00000080) {
                    //4-byte tag not support
                    Debugger.addHEX(TAG, "4-byte tag not support : ", buffer, index, 4);
                    return 1;
                } else {
                    //3 bytes tag
                    tlv.tag = ((buffer[index] & 0x000000FF) << 16) | ((buffer[index + 1] & 0x000000FF) << 8) | ((buffer[index + 2] & 0x000000FF));
                    tagLen = 3;
                }
            } else {
                //2 bytes tag
                tlv.tag = ((buffer[index] & 0x000000FF) << 8) | ((buffer[index + 1] & 0x000000FF));
                tagLen = 2;
            }
        } else {
            //1 byte tag
            tlv.tag = (buffer[index] & 0x000000FF);
            tagLen = 1;
        }
        if (isShowlog == true) {
            Log.d(TAG, "Tag : " + String.format("0x%08X", tlv.tag));
        }
        index += tagLen;
        lens[0] = tagLen;

        lengthLen = 1;
        if ((buffer[index] & 0x00000080) == 0x00000080) {
            lengthLen += (buffer[index] & 0x0000007F);

            if (lengthLen == 2) {
                tlv.len = (buffer[index + 1] & 0x000000FF);
            } else if (lengthLen == 3) {
                tlv.len = ((buffer[index + 1] & 0x000000FF) << 8) | ((buffer[index + 2] & 0x000000FF));
            } else {
                //4-byte length not support
                Debugger.addHEX(TAG, "4-byte len not support : ", buffer, index, 4);
                return 1;
            }
        } else {
            tlv.len = (buffer[index] & 0x000000FF);
        }
        if (isShowlog == true) {
            Debugger.addHEX(TAG, String.format("Len(Dec) : %d, Len(TLV) : ", tlv.len), buffer, index, lengthLen);
        }
        index += lengthLen;
        lens[1] = lengthLen;

        System.arraycopy(buffer, index, tlv.value, 0, tlv.len);
        lens[2] = tlv.len;

        return 0;
    }

    public int TLVDataAdd(TlvData tlv) {
        TlvData tempTlvData = new TlvData();
        int[] lens = new int[3];
        int curTagLen = 0;
        int curLenLen = 0;
        int curValueLen = 0;
        int nextTag_i = 0;
        byte temp[] = new byte[10];
        int i = 0;
        int j = 0;
        int intRtn;

        tempTlvData.value = new byte[1024];

        while (i < intTLVDataBaseLen) {
            lens[0] = lens[1] = lens[2] = 0;

            intRtn = getTLV(TLVDataBase, i, tempTlvData, lens, false);
            if (intRtn != 0) {
                return 1;
            }

            curTagLen = lens[0];
            curLenLen = lens[1];
            curValueLen = lens[2];

            nextTag_i = (i + curTagLen + curLenLen + curValueLen);

            if (tlv.tag == tempTlvData.tag) {
                System.arraycopy(TLVDataBase, nextTag_i, TLVDataBase, i, (intTLVDataBaseLen - nextTag_i));
                intTLVDataBaseLen -= (curTagLen + curLenLen + curValueLen);
                break;
            }

            i = nextTag_i;
        }
        //TLVDataRemove(tlv.tag);

        //tag
        if ((tlv.tag & 0xFF000000) != 0) {
            //4-byte tag not support
            Log.d(TAG, "4-byte tag not support2 : " + String.format("0x%08X", tlv.tag));
            return 1;
        } else {
            if ((tlv.tag & 0x00FF0000) != 0) {
                temp[j++] = (byte) ((tlv.tag & 0x00FF0000) >> 16);
            }

            if ((tlv.tag & 0x0000FF00) != 0) {
                temp[j++] = (byte) ((tlv.tag & 0x0000FF00) >> 8);
            }

            temp[j++] = (byte) (tlv.tag & 0x000000FF);
        }

        //len
        //temp[j++] = (byte)tlv.len;
        if (tlv.len < 0) {
            //length < 0
            Log.d(TAG, "len < 0 : " + String.format("0x%d", tlv.len));
            return 1;
        } else if (tlv.len > 0x0000FFFF) {
            //4-byte length not support
            Log.d(TAG, "4-byte len not support2 : " + String.format("0x%d", tlv.len));
            return 1;
        } else if (tlv.len > 0x000000FF) {
            temp[j++] = (byte) 0x82;
            temp[j++] = (byte) ((tlv.len & 0x0000FF00) >> 8);
            temp[j++] = (byte) ((tlv.len & 0x000000FF));
        } else if (tlv.len > 0x0000007F) {
            temp[j++] = (byte) 0x81;
            temp[j++] = (byte) ((tlv.len & 0x000000FF));
        } else {
            temp[j++] = (byte) ((tlv.len & 0x000000FF));
        }

        System.arraycopy(temp, 0, TLVDataBase, intTLVDataBaseLen, j);
        intTLVDataBaseLen += j;

        System.arraycopy(tlv.value, 0, TLVDataBase, intTLVDataBaseLen, tlv.len);
        intTLVDataBaseLen += tlv.len;

        return 0;
    }

    public void TLVDataRemove(int tag) {
        TlvData temptlvData = new TlvData();
        int[] lens = new int[3];
        int i = 0;
        int nextTag_i = 0;
        int curTagLen = 0;
        int curLenLen = 0;
        int curValueLen = 0;
        int intRtn;

        temptlvData.value = new byte[1024];

        while (i < intTLVDataBaseLen) {
            lens[0] = lens[1] = lens[2] = 0;

            intRtn = getTLV(TLVDataBase, i, temptlvData, lens, false);
            if (intRtn != 0) {
                return;
            }

            curTagLen = lens[0];
            curLenLen = lens[1];
            curValueLen = lens[2];

            nextTag_i = (i + curTagLen + curLenLen + curValueLen);

            if (tag == temptlvData.tag) {
                System.arraycopy(TLVDataBase, nextTag_i, TLVDataBase, i, (intTLVDataBaseLen - nextTag_i));
                intTLVDataBaseLen -= (curTagLen + curLenLen + curValueLen);
                break;
            }

            i = nextTag_i;
        }
    }


    public void TLVDataClear() {
        intTLVDataBaseLen = 0;
        Arrays.fill(TLVDataBase, (byte) 0x00);
    }
}
