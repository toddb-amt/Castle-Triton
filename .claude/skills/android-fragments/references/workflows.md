# Fragment Workflows Reference

## Contents
- Adding a New Fragment
- Fragment-to-Fragment Communication
- Handling EMV Callbacks
- Timeout and Auto-Navigation
- Error Handling Patterns

## Adding a New Fragment

Copy this checklist and track progress:
- [ ] Step 1: Create layout XML in `res/layout/`
- [ ] Step 2: Create Fragment class extending `Fragment`
- [ ] Step 3: Add page constant to `GlobalDef.java`
- [ ] Step 4: Register in `SectionsPagerAdapter.getItem()`
- [ ] Step 5: Update `getCount()` return value
- [ ] Step 6: Test navigation with `navigateToPage()`

### Step 1: Layout XML

```xml
<!-- res/layout/fragment_page_custom.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">
    
    <TextView
        android:id="@+id/txvTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Custom Screen" />
        
    <Button
        android:id="@+id/btnContinue"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Continue" />
</LinearLayout>
```

### Step 2: Fragment Class

```java
package castech.emvtxn;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.fragment.app.Fragment;

public class Fragment_page_custom extends Fragment {
    private static MainActivity mainActivity = null;
    private View view;
    
    public Fragment_page_custom() {}
    
    public Fragment_page_custom(MainActivity activity) {
        if (this.mainActivity == null) {
            this.mainActivity = activity;
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_page_custom, container, false);
        
        Button btnContinue = view.findViewById(R.id.btnContinue);
        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mainActivity != null) {
                    mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
                }
            }
        });
        
        return view;
    }
}
```

### Step 3: Add Constant

```java
// GlobalDef.java
static final int d_PAGE_CUSTOM = 6;  // Next available index
```

### Step 4-5: Register Fragment

```java
// MainActivity.java - SectionsPagerAdapter
@Override
public Fragment getItem(int position) {
    switch (position) {
        // ... existing cases ...
        case GlobalDef.d_PAGE_CUSTOM:
            return new Fragment_page_custom(mainActivity);
    }
    return null;
}

@Override
public int getCount() {
    return 7;  // Increment count
}
```

## Fragment-to-Fragment Communication

### Via GlobalPara State

```java
// Source fragment: set data
GlobalPara.atmSelectedAmount = "100.00";
GlobalPara.atmAccountType = GlobalPara.ATM_ACCOUNT_CHECKING;
mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);

// Destination fragment: read data
@Override
public void onResume() {
    super.onResume();
    String amount = GlobalPara.atmSelectedAmount;
    updateDisplay(amount);
}
```

### Direct Fragment Method Call

For immediate updates when destination fragment exists:

```java
// MainActivity.navigateToPage()
public void navigateToPage(int pageIndex) {
    mViewPager.setCurrentItem(pageIndex, false);
    
    // Notify fragment directly
    Fragment fragment = getSupportFragmentManager().findFragmentByTag("android:switcher:" + R.id.container + ":" + pageIndex);
    if (fragment instanceof Fragment_page_receipt) {
        ((Fragment_page_receipt) fragment).refreshDisplay();
    }
}
```

## Handling EMV Callbacks

EMV SDK callbacks occur during `txnPerform()`. Fragments receive updates via GlobalPara.

### Callback Flow

```
MainActivity.threadTxn
    └── emv.txnPerform()
        └── MyEMVEvent.onTxnResult()
            └── GlobalPara.transactionResult = result
            └── runOnUiThread → navigateToPage(RECEIPT)
```

### Fragment Response to Callback

```java
// Fragment_page_receipt.java
@Override
public void onResume() {
    super.onResume();
    
    boolean hasTransactionData = GlobalPara.atmTransactionComplete ||
                                 GlobalPara.transactionResult != 0;
    if (hasTransactionData) {
        displayTransactionResults();
        startAutoTimeout();
    }
}

private void displayTransactionResults() {
    boolean isSuccess = (GlobalPara.transactionResult == 0x0002 ||
                         GlobalPara.transactionResult == 0x0004);
    
    if (isSuccess) {
        txvResultIcon.setText("✓");
        txvResultMessage.setText("Transaction Approved");
    } else {
        txvResultIcon.setText("✗");
        txvResultMessage.setText("Transaction Failed");
    }
}
```

## Timeout and Auto-Navigation

### Auto-Timeout Pattern

Receipt screen returns to idle after 30 seconds:

```java
private Handler autoTimeoutHandler = new Handler();
private Runnable autoTimeoutRunnable;
private static final int AUTO_TIMEOUT_MS = 30000;

private void startAutoTimeout() {
    autoTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // Guard: only navigate if visible
            if (!isVisible() || !getUserVisibleHint()) {
                return;
            }
            resetATMParameters();
            mainActivity.navigateToPage(GlobalDef.d_PAGE_IDLE);
        }
    };
    autoTimeoutHandler.postDelayed(autoTimeoutRunnable, AUTO_TIMEOUT_MS);
}

private void stopAutoTimeout() {
    if (autoTimeoutRunnable != null && autoTimeoutHandler != null) {
        autoTimeoutHandler.removeCallbacks(autoTimeoutRunnable);
    }
}

@Override
public void onPause() {
    super.onPause();
    stopAutoTimeout();  // Prevent timeout firing when not visible
}
```

### User Action Resets Timeout

```java
btnPrintReceipt.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        stopAutoTimeout();   // Reset timer
        printReceipt();
        startAutoTimeout();  // Restart timer
    }
});
```

## Error Handling Patterns

### Fallback View on Inflation Error

```java
@Override
public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    try {
        view = inflater.inflate(R.layout.fragment_page_amount_selection, container, false);
        initializeComponents();
        return view;
    } catch (Exception e) {
        android.util.Log.e(TAG, "Error creating view: " + e.getMessage());
        // Return colored fallback for debugging
        android.widget.FrameLayout fallback = new android.widget.FrameLayout(inflater.getContext());
        fallback.setBackgroundColor(0xFFFF9800);  // Orange = error
        return fallback;
    }
}
```

### Null-Safe UI Updates

```java
public void updateInstruction(String instruction) {
    if (txvInstruction != null) {
        txvInstruction.setText(instruction);
    }
}

public void showProgress(boolean show) {
    if (progressBar != null) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
```

### Transaction Cancel with Cleanup

```java
private void cancelTransaction() {
    Log.d(TAG, "cancelTransaction() called");
    
    // Full reset of ATM state
    GlobalPara.resetATMTransactionState();
    
    // Abort any in-progress EMV transaction
    if (mainActivity != null) {
        try {
            mainActivity.abortTransaction();
        } catch (Exception e) {
            Log.e(TAG, "Error aborting transaction: " + e.getMessage());
        }
    }
    
    // Navigate back
    if (mainActivity != null) {
        mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
    }
}
```

### Dialog Lifecycle Safety

```java
@Override
public void onDestroyView() {
    super.onDestroyView();
    
    // Prevent "Activity has leaked window" crash
    if (pinDialog != null && pinDialog.isShowing()) {
        pinDialog.dismiss();
    }
    pinDialog = null;
}