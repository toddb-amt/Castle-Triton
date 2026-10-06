package castech.emvtxn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import CTOS.CtPrint;

public class PrinterManager {
    private static final String TAG = "PrinterManager";

    int ret = 0;
    CtPrint Print;
    Context context;

    public PrinterManager(Context context) {
        this.context = context;
    }

    public void Init() {
        Print = new CtPrint();
    }

    public int goprintf() throws IOException {

        int page_len = 920;

        Print.initPage(page_len);
        String print_font;
        int print_x = 0;
        int print_y = 16;
        int Currently_high = 50;

        int print_liftx = 25;

        print_font = "SAMPLE RECEIPT";
        print_y = 32;
        print_x = (384 - print_font.length() * print_y) / 2 + 52;
        print_x += print_font.length() * 3;
        Print.drawText(print_x, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "";
        print_y = 18;
        print_x = (384 - print_font.length() * print_y) / 2 + 55;
        print_x += print_font.length() * 3;
        Print.drawText(print_x, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 16;

        print_y = 26;
        print_font = "...............................................";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 15;

        print_y = 16;
        print_font = "STORE: 0003         REGISTER: 001";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 3;

        print_font = "CASHIER: KATIE";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 3;

        print_font = "ASSOCIATE: 0000000";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_y = 26;
        print_font = "...............................................";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_y = 16;
        print_font = "CUSTOMER RECEIPT COPY";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 15;

        print_font = "";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Card Type ";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = " " + GlobalPara.cardType.toUpperCase();

        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Chech No. ";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "  90119";
        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Card No. ";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = GlobalPara.asciiPAN; //card number
        print_y = 16;
        //print_x = (384 - print_font.length()*print_y)/2+1;
        //print_x += print_font.length()*3+40;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Host/Irans. Type";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "  BANK   00 GENERAL CONDITION  SALE";
        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Batch No.";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "  379";
        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "Auth Code";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "  047364";
        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "DATE";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;
        print_font = "  " + GlobalPara.DateTime;
        print_y = 16;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 10;

        print_y = 26;
        print_font = "...............................................";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 15;

        Currently_high += print_y;
        print_font = "TOTAL AMOUNT";
        print_y = 20;
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);

        print_font = GlobalPara.strAmount;
        Print.drawText(300, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;


        print_y = 26;
        print_font = "...............................................";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y;

        print_font = "";
        print_y = 24;
        print_x = (384 - print_font.length() * print_y) / 2 + 1;
        print_x += print_font.length() * 3;
        Print.drawText(print_x, print_y + Currently_high, print_font, print_y, 1);
        Currently_high += print_y * 3 + 10;

        print_font = "Sign: _______________________";
        Print.drawText(print_liftx, print_y + Currently_high + 15, print_font, print_y);
        Currently_high += print_y - 70;


        Currently_high += (print_y * 5);
        print_y = 16;
        print_font = "I AGREE TO PAY THE ABOVE TOTAL";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 2;

        print_font = "AMOUNT, ACCORDING TO THE CARD";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y + 2;

        print_font = "ISSUER AGREEMENT";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y * 2;

        print_font = "CUSTOMER COPY";
        Print.drawText(print_liftx, print_y + Currently_high, print_font, print_y);
        Currently_high += print_y * 2;


        Log.d(TAG, "Data path:" + context.getFilesDir());

//			Print.save("/data/user/0/castech.emvtxn/files/recipe.jpg");

        File file = new File("/data/user/0/castech.emvtxn/files/recipe.jpg");
        Bitmap panel = BitmapFactory.decodeFile(file.getAbsolutePath());
        Print.initPage(panel.getHeight() + 350);
        System.out.println("Set panel initPage is" + Integer.toString(panel.getHeight() + 300));
        Print.drawImage(panel, 0, 0);
        Print.printPage();

        return ret;
    }

    /**
     * Print text content - simple text-based printing for ATM receipts
     * @param text The text to print (use \n for line breaks)
     */
    public void printf(String text) throws IOException {
        if (Print == null) {
            Init();
        }

        // Handle null or empty text
        if (text == null || text.isEmpty()) {
            return;
        }

        // Split text by newlines
        String[] lines = text.split("\n", -1);

        // Calculate page height based on number of lines
        int lineHeight = 20;
        int pageHeight = lines.length * lineHeight + 100;

        Print.initPage(pageHeight);

        int currentY = 20;
        int leftMargin = 25;
        int fontSize = 16;

        // Print each line
        for (String line : lines) {
            // Print even empty lines (to preserve spacing), but skip null
            if (line != null) {
                Print.drawText(leftMargin, currentY, line, fontSize);
            }
            currentY += lineHeight;
        }

        // Print the page
        Print.printPage();
    }
}
