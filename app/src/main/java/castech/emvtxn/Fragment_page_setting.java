package castech.emvtxn;


import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * A simple {@link Fragment} subclass.
 */
public class Fragment_page_setting extends Fragment
{
    private static MainActivity mainActivity = null;
    //private static Button btnTransaction = null;
    //private static Button btnClearMsg = null;
    private View view;
    private static Button btnHome = null;
    private static Button btnAPListSet = null;
    private static Button btnTermConfigSet = null;
    private static Button btnTermConfigDel = null;
    private static Button btnTermConfigDelAll = null;
    private static Button btnAppConfigSet = null;
    private static Button btnAppConfigDel = null;
    private static Button btnAppConfigDelAll = null;
    private static Button btnCapkSet = null;
    private static Button btnCapkDel = null;
    private static Button btnCapkDelAll = null;
    private static Button btnJsonwlSet = null;
    private static Button btnJsonwlDel = null;
    private static Button btnParamSet = null;
    private static Button btnParamGet = null;
    private static Button btnAIDSet = null;
    private static Button btnAIDGet = null;
    private static Button btnAIDDel = null;
    private static Button btnAIDDelAll = null;
    private static Button btnCloseRtn = null;
    private static Button btnMinRtn = null;
    private static Button btnCLCapkSet = null;
    private static Button btnCLCapkGet = null;
    private static Button btnCLCapkDel = null;
    private static Button btnCLCapkDelAll = null;
    private static LinearLayout layoutRtn = null;
    private static LinearLayout layoutBackground = null;
    private static TextView txvRtn = null;
    private static TextView txvMinRtn = null;


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        try {
        //return super.onCreateView(inflater, container, savedInstanceState);
        view = inflater.inflate(R.layout.fragment_page_setting, container, false);

        this.txvRtn = (TextView)view.findViewById(R.id.txvRtn);
        this.txvRtn.setMovementMethod(new ScrollingMovementMethod());

        this.txvMinRtn = (TextView)view.findViewById(R.id.txvMinRtn);
        this.txvMinRtn.setMovementMethod(new ScrollingMovementMethod());


        this.layoutBackground = (LinearLayout)view.findViewById(R.id.layoutBackground);
        this.layoutBackground.getBackground().setAlpha(100);
        this.layoutBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutRtn = (LinearLayout)view.findViewById(R.id.layoutRtn);
                layoutRtn.setVisibility(View.INVISIBLE);
                layoutBackground.setVisibility(View.INVISIBLE);
            }
        });

        this.txvMinRtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutRtn = (LinearLayout)view.findViewById(R.id.layoutRtn);
                layoutRtn.setVisibility(View.VISIBLE);
                layoutBackground.setVisibility(View.VISIBLE);
            }
        });

        this.btnCloseRtn = (Button)view.findViewById(R.id.btnCloseRtn);
        this.btnCloseRtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                txvRtn.setText("");
                GlobalPara.settingRtn ="";
                txvMinRtn.setText("");
                layoutRtn = (LinearLayout)view.findViewById(R.id.layoutRtn);
                layoutRtn.setVisibility(View.INVISIBLE);
                layoutBackground.setVisibility(View.INVISIBLE);
            }
        });

        this.btnMinRtn = (Button)view.findViewById(R.id.btnMinRtn);
        this.btnMinRtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutRtn = (LinearLayout)view.findViewById(R.id.layoutRtn);
                layoutRtn.setVisibility(View.INVISIBLE);
                layoutBackground.setVisibility(View.INVISIBLE);
            }
        });

        this.btnAPListSet = (Button)view.findViewById(R.id.btnAPListSet);
        this.btnAPListSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAPListSet_Click(v);
            }
        });

        this.btnHome = (Button)view.findViewById(R.id.btnHome);
        this.btnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnHome_Click(v);
            }
        });

        this.btnTermConfigSet = (Button)view.findViewById(R.id.btnTermConfigSet);
        this.btnTermConfigSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTermConfigSet_Click(v);
            }
        });

        this.btnTermConfigDel = (Button)view.findViewById(R.id.btnTermConfigDel);
        this.btnTermConfigDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTermConfigDel_Click(v);
            }
        });

        this.btnTermConfigDelAll = (Button)view.findViewById(R.id.btnTermConfigDelAll);
        this.btnTermConfigDelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTermConfigDelAll_Click(v);
            }
        });

        this.btnAppConfigSet = (Button)view.findViewById(R.id.btnAppConfigSet);
        this.btnAppConfigSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAppConfigSet_Click(v);
            }
        });

        this.btnAppConfigDel = (Button)view.findViewById(R.id.btnAppConfigDel);
        this.btnAppConfigDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAppConfigDel_Click(v);
            }
        });

        this.btnAppConfigDelAll = (Button)view.findViewById(R.id.btnAppConfigDelAll);
        this.btnAppConfigDelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAppConfigDelAll_Click(v);
            }
        });

        this.btnCapkSet = (Button)view.findViewById(R.id.btnCapkSet);
        this.btnCapkSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCapkSet_Click(v);
            }
        });

        this.btnCapkDel = (Button)view.findViewById(R.id.btnCapkDel);
        this.btnCapkDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCapkDel_Click(v);
            }
        });

        this.btnCapkDelAll = (Button)view.findViewById(R.id.btnCapkDelAll);
        this.btnCapkDelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCapkDelAll_Click(v);
            }
        });

        this.btnParamSet = (Button)view.findViewById(R.id.btnParamSet);
        this.btnParamSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnParamSet_Click(v);
            }
        });

        this.btnParamGet = (Button)view.findViewById(R.id.btnParamGet);
        this.btnParamGet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnParamGet_Click(v);
            }
        });

        this.btnAIDSet = (Button)view.findViewById(R.id.btnAIDSet);
        this.btnAIDSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAIDSet_Click(v);
            }
        });

        this.btnAIDGet = (Button)view.findViewById(R.id.btnAIDGet);
        this.btnAIDGet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAIDGet_Click(v);
            }
        });

        this.btnAIDDel = (Button)view.findViewById(R.id.btnAIDDel);
        this.btnAIDDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAIDDel_Click(v);
            }
        });

        this.btnAIDDelAll = (Button)view.findViewById(R.id.btnAIDDelAll);
        this.btnAIDDelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAIDDelAll_Click(v);
            }
        });

        this.btnJsonwlSet = (Button)view.findViewById(R.id.btnJsonwlSet);
        this.btnJsonwlSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GlobalPara.JsonFile = "bin.json";
                json_change();
            }
        });

        this.btnCLCapkSet = (Button)view.findViewById(R.id.btnCLCapkSet);
        this.btnCLCapkSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCLCapkSet_Click(v);
            }
        });

        this.btnCLCapkGet = (Button)view.findViewById(R.id.btnCLCapkGet);
        this.btnCLCapkGet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCLCapkGet_Click(v);
            }
        });

        this.btnCLCapkDel = (Button)view.findViewById(R.id.btnCLCapkDel);
        this.btnCLCapkDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCLCapkDel_Click(v);
            }
        });

        this.btnCLCapkDelAll = (Button)view.findViewById(R.id.btnCLCapkDelAll);
        this.btnCLCapkDelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnCLCapkDelAll_Click(v);
            }
        });

        this.btnJsonwlDel = (Button)view.findViewById(R.id.btnJsonwlDel);
        this.btnJsonwlDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GlobalPara.JsonFile = "no json wl files";
                json_change();
            }
        });

        return view;
        } catch (Exception e) {
            // Return gray view on error
            android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
            fallback.setBackgroundColor(0xFF607D8B); // Blue-gray = setting error
            return fallback;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!GlobalPara.settingRtn.equals(""))
        {
            txvRtn.setText(GlobalPara.settingRtn);
            txvMinRtn.setText(GlobalPara.settingRtn);
        }
    }


    public View toView()
    {
        return view;
    }

    public Fragment_page_setting()
    {

    }

    public Fragment_page_setting(MainActivity activity)
    {
        //Log.d("Fragment_page_transaction", "Fragment_page_transaction()-->");

        if(this.mainActivity == null)
        {
            this.mainActivity = activity;
        }
    }

    private int btnHome_Click(final View view)
    {
        this.mainActivity.btnHome_Click(view);

        return 0;
    }

    private int btnAPListSet_Click(final View view)
    {
        this.mainActivity.btnAPListSet_Click(view);

        return 0;
    }

    private int btnTermConfigSet_Click(final View view)
    {
        this.mainActivity.btnTermConfigSet_Click(view);

        return 0;
    }

    private int btnTermConfigDel_Click(final View view)
    {
        this.mainActivity.btnTermConfigDel_Click(view);

        return 0;
    }

    private int btnTermConfigDelAll_Click(final View view)
    {
        this.mainActivity.btnTermConfigDelAll_Click(view);

        return 0;
    }

    private int btnAppConfigSet_Click(final View view)
    {
        this.mainActivity.btnAppConfigSet_Click(view);

        return 0;
    }

    private int btnAppConfigDel_Click(final View view)
    {
        this.mainActivity.btnAppConfigDel_Click(view);

        return 0;
    }

    private int btnAppConfigDelAll_Click(final View view)
    {
        this.mainActivity.btnAppConfigDelAll_Click(view);

        return 0;
    }

    private int btnCapkSet_Click(final View view)
    {
        this.mainActivity.btnCapkSet_Click(view);

        return 0;
    }

    private int btnCapkDel_Click(final View view)
    {
        this.mainActivity.btnCapkDel_Click(view);

        return 0;
    }

    private int btnCapkDelAll_Click(final View view)
    {
        this.mainActivity.btnCapkDelAll_Click(view);

        return 0;
    }

    private int btnParamSet_Click(final View view)
    {
        this.mainActivity.btnParamSet_Click(view);

        return 0;
    }

    private int btnParamGet_Click(final View view)
    {
        this.mainActivity.btnParamGet_Click(view);

        return 0;
    }

    private int btnAIDSet_Click(final View view)
    {
        this.mainActivity.btnAIDSet_Click(view);

        return 0;
    }

    private int btnAIDGet_Click(final View view)
    {
        this.mainActivity.btnAIDGet_Click(view);

        return 0;
    }

    private int btnAIDDel_Click(final View view)
    {
        this.mainActivity.btnAIDDel_Click(view);

        return 0;
    }

    private int btnAIDDelAll_Click(final View view)
    {
        this.mainActivity.btnAIDDelAll_Click(view);

        return 0;
    }

    private int btnCLCapkSet_Click(final View view)
    {
        this.mainActivity.btnCLCapkSet_Click(view);

        return 0;
    }

    private int btnCLCapkGet_Click(final View view)
    {
        this.mainActivity.btnCLCapkGet_Click(view);

        return 0;
    }

    private int btnCLCapkDel_Click(final View view)
    {
        this.mainActivity.btnCLCapkDel_Click(view);

        return 0;
    }

    private int btnCLCapkDelAll_Click(final View view)
    {
        this.mainActivity.btnCLCapkDelAll_Click(view);

        return 0;
    }

    private int json_change()
    {
        this.mainActivity.json_change();

        return 0;
    }
}
