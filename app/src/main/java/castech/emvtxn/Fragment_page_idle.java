package castech.emvtxn;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * IDLE/Welcome Screen Fragment - Minimal test version
 */
public class Fragment_page_idle extends Fragment {

    private static MainActivity mainActivity = null;

    public Fragment_page_idle() {
    }

    public Fragment_page_idle(MainActivity activity) {
        mainActivity = activity;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            // Create a simple view programmatically - no layout inflation
            android.content.Context ctx = getContext();
            if (ctx == null) {
                ctx = getActivity();
            }
            if (ctx == null) {
                // Last resort - return empty view
                return new FrameLayout(inflater.getContext());
            }

            FrameLayout view = new FrameLayout(ctx);
            view.setBackgroundColor(0xFF1565C0); // Blue background

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mainActivity != null) {
                        mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
                    }
                }
            });

            return view;
        } catch (Exception e) {
            // Return cyan view on error - different color for debugging
            FrameLayout fallback = new FrameLayout(inflater.getContext());
            fallback.setBackgroundColor(0xFF00BCD4); // Cyan = idle error
            return fallback;
        }
    }
}
