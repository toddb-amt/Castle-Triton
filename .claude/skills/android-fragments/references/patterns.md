# Fragment Patterns Reference

## Contents
- Fragment Lifecycle Management
- ViewPager Integration
- Global State Pattern
- Mode-Based View Switching
- Threading and UI Updates

## Fragment Lifecycle Management

### WARNING: Static MainActivity Reference

**The Problem:**

```java
// BAD - Static reference causes leaks on rotation
private static MainActivity mainActivity = null;

public Fragment_page_receipt(MainActivity activity) {
    mainActivity = activity;  // Never cleared
}
```

**Why This Breaks:**
1. Memory leak - Activity never garbage collected
2. Crashes after rotation - reference to destroyed activity
3. Stale references in long-running operations

**The Fix:**

This codebase uses static references intentionally because Castle terminals don't rotate. For standard Android:

```java
// GOOD - Use getActivity() when needed
public void navigateNext() {
    Activity activity = getActivity();
    if (activity instanceof MainActivity) {
        ((MainActivity) activity).navigateToPage(GlobalDef.d_PAGE_RECEIPT);
    }
}
```

### Cleanup in onDestroyView

```java
@Override
public void onDestroyView() {
    super.onDestroyView();
    
    // Dismiss dialogs to prevent window leak
    if (pinDialog != null && pinDialog.isShowing()) {
        pinDialog.dismiss();
    }
    pinDialog = null;
    
    // Remove pending callbacks
    if (view != null) {
        view.removeCallbacks(null);
    }
    
    // Clear handler
    if (autoTimeoutHandler != null) {
        autoTimeoutHandler.removeCallbacksAndMessages(null);
    }
    autoTimeoutHandler = null;
}
```

## ViewPager Integration

### SectionsPagerAdapter Setup

Located in `MainActivity.java`:

```java
public class SectionsPagerAdapter extends FragmentPagerAdapter {
    public SectionsPagerAdapter(FragmentManager fm) {
        super(fm);
    }
    
    @Override
    public Fragment getItem(int position) {
        switch (position) {
            case GlobalDef.d_PAGE_IDLE:
                return new Fragment_page_idle(mainActivity);
            case GlobalDef.d_PAGE_MAIN_MENU:
                return new Fragment_page_main_menu(mainActivity);
            case GlobalDef.d_PAGE_TRANSACTION:
                return new Fragment_page_transaction(mainActivity);
            // ... other pages
        }
        return null;
    }
    
    @Override
    public int getCount() {
        return 6;  // Total fragment count
    }
}
```

### Programmatic Navigation

```java
// In MainActivity
public void navigateToPage(int pageIndex) {
    if (mViewPager != null) {
        mViewPager.setCurrentItem(pageIndex, false);  // false = no animation
    }
    
    // Notify specific fragments about navigation
    Fragment fragment = mSectionsPagerAdapter.getItem(pageIndex);
    if (pageIndex == GlobalDef.d_PAGE_RECEIPT && fragment instanceof Fragment_page_receipt) {
        ((Fragment_page_receipt) fragment).refreshDisplay();
    }
}
```

### WARNING: ViewPager Fragment Pre-creation

**The Problem:**

```java
// BAD - Timer starts in onCreateView
@Override
public View onCreateView(...) {
    startAutoTimeout();  // Fires even when fragment not visible!
    return view;
}
```

**Why This Breaks:**
ViewPager pre-creates adjacent fragments. A 30-second timeout started in Receipt fragment will fire while user is still on Transaction fragment.

**The Fix:**

```java
// GOOD - Start timer only when visible
@Override
public void onResume() {
    super.onResume();
    if (getUserVisibleHint()) {
        startAutoTimeout();
    }
}

@Override
public void setUserVisibleHint(boolean isVisibleToUser) {
    super.setUserVisibleHint(isVisibleToUser);
    if (isVisibleToUser && isAdded()) {
        startAutoTimeout();
    } else {
        stopAutoTimeout();
    }
}
```

## Global State Pattern

### GlobalPara Singleton

All transaction state flows through `GlobalPara`:

```java
// Amount selection → Transaction → Receipt flow
GlobalPara.atmSelectedAmount = "100.00";
GlobalPara.atmFee = "3.00";
GlobalPara.atmTotal = "103.00";
GlobalPara.strAmount = "10300";  // Cents for EMV SDK
```

### State Reset Before New Transaction

```java
// In Fragment_page_main_menu.onResume()
@Override
public void onResume() {
    super.onResume();
    GlobalPara.resetATMTransactionState();  // Clear all previous transaction data
}
```

### Reset Method Pattern

```java
public static void resetATMTransactionState() {
    // Reset amounts
    atmSelectedAmount = "0.00";
    atmFee = "0.00";
    atmTotal = "0.00";
    strAmount = "0";
    
    // Reset card data
    atmTrack2Data = "";
    atmEncryptedPinBlock = "";
    atmEmvData = "";
    
    // Reset host response
    atmAuthCode = "";
    atmResponseCode = "";
    atmHostCallSuccess = false;
    
    // Reset EMV state
    appSelectedIndex = 0;
    asciiPAN = null;
}
```

## Mode-Based View Switching

### Dynamic Layout Selection

```java
private boolean isInATMMode() {
    return (GlobalPara.atmSelectedAmount != null && 
            !"0.00".equals(GlobalPara.atmSelectedAmount))
            || GlobalPara.atmBalanceInquiryMode;
}

private View createATMView(LayoutInflater inflater, ViewGroup container) {
    view = inflater.inflate(R.layout.fragment_page_transaction_atm, container, false);
    // ATM-specific UI setup
    return view;
}

private View createLegacyView(LayoutInflater inflater, ViewGroup container) {
    view = inflater.inflate(R.layout.fragment_page_transaction, container, false);
    // Legacy EMV sample UI setup
    return view;
}
```

### Runtime View Refresh

When mode changes without fragment recreation:

```java
private void refreshView() {
    if (getView() == null || getActivity() == null) return;
    
    ViewGroup container = (ViewGroup) getView();
    container.removeAllViews();
    
    LayoutInflater inflater = LayoutInflater.from(getActivity());
    View newContent;
    
    if (isATMMode) {
        newContent = inflater.inflate(R.layout.fragment_page_transaction_atm, container, false);
    } else {
        newContent = inflater.inflate(R.layout.fragment_page_transaction, container, false);
    }
    
    container.addView(newContent);
    view = newContent;
    
    // Re-initialize UI elements
    initializeComponents();
}
```

## Threading and UI Updates

### EMV Operations on Background Thread

All EMV SDK calls happen on `threadTxn`:

```java
// In MainActivity
Thread threadTxn = new Thread(new Runnable() {
    @Override
    public void run() {
        emv.txnPerform();  // Blocking EMV operation
    }
});
threadTxn.start();
```

### UI Updates from Callbacks

EMV callbacks run on background thread, must update UI on main:

```java
@Override
public void onTxnResult(byte txnResult, boolean isSignatureRequired) {
    mainActivity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
            navigateToReceipt();
        }
    });
}
```

### Fragment UI Update Pattern

```java
public void updateStatus(final String message) {
    if (getActivity() != null) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (txvStatus != null) {
                    txvStatus.setText(message);
                }
            }
        });
    }
}