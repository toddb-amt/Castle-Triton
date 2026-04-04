package castech.emvtxn;

import androidx.viewpager.widget.ViewPager;

public class MyUtility {
    static final String MyUtilTAG = "EMV_AP_MyUtil";

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static MainActivity activity = null;
    static ViewPager viewPager = null;
    static GlobalPara glblPara = null;

    public static void setViewPager(MainActivity a, ViewPager v, GlobalPara g) {
        activity = a;
        viewPager = v;
        glblPara = g;
    }

    public static void switchPage(final int pageIndex, long ms) {
        if (activity == null) {
            Debugger.addSTR(MyUtilTAG, "Activity null !");
            return;
        }
        if (viewPager == null) {
            Debugger.addSTR(MyUtilTAG, "ViewPager null !");
            return;
        }
        if (glblPara == null) {
            Debugger.addSTR(MyUtilTAG, "GlobalPara null !");
            return;
        }

        glblPara.layoutViewCreate = 0;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewPager.setCurrentItem(pageIndex, false);
                glblPara.layoutViewCreate = 1;
            }
        });

        // Wait for page switch with timeout (max 10 seconds)
        int maxWait = 20; // 20 x 500ms = 10 seconds
        int waited = 0;
        do {
            sleep(ms);
            waited++;
        } while (glblPara.layoutViewCreate == 0 && waited < maxWait);

        if (waited >= maxWait) {
            Debugger.addSTR(MyUtilTAG, "Page switch timeout!");
        }
    }
}
