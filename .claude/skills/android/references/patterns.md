# Android Patterns Reference

## Contents
- Fragment Navigation Patterns
- State Management with GlobalPara
- Background Threading for EMV
- Lifecycle Management
- Anti-Patterns

## Fragment Navigation Patterns

### ViewPager with TabLayout

MainActivity uses `SectionsPagerAdapter` to manage fragments:

```java
// MainActivity.java - Fragment adapter setup
public class SectionsPagerAdapter extends FragmentPagerAdapter {
    @Override
    public Fragment getItem(int position) {
        switch (position) {
            case GlobalDef.d_PAGE_IDLE: return new Fragment_page_idle();
            case GlobalDef.d_PAGE_MAIN_MENU: return new Fragment_page_main_menu();
            case GlobalDef.d_PAGE_TRANSACTION: return new Fragment_page_transaction();
            // ...
        }
    }
}
```

### Programmatic Navigation

```java
// GOOD - Navigate via GlobalPara reference
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);

// MainActivity.navigateToPage implementation
public void navigateToPage(int pageIndex) {
    runOnUiThread(() -> {
        viewPager.setCurrentItem(pageIndex, true);
    });
}
```

## State Management with GlobalPara

### Transaction State Fields

```java
// GlobalPara.java - ATM transaction state
public static long atmSelectedAmount = 0;
public static long atmFeeAmount = 0;
public static String atmPanMasked = "";
public static String atmCardholderName = "";
public static boolean atmTransactionComplete = false;
public static String atmAuthCode = "";
```

### Reset State Pattern

```java
// GOOD - Always reset before new transaction
public static void resetATMTransactionState() {
    atmSelectedAmount = 0;
    atmFeeAmount = 0;
    atmPanMasked = "";
    atmCardholderName = "";
    atmTransactionComplete = false;
    atmAuthCode = "";
    atmErrorMessage = "";
}

// Call before starting transaction flow
GlobalPara.resetATMTransactionState();
```

### WARNING: Stale State Bug

**The Problem:**

```java
// BAD - Not resetting state causes previous transaction data to appear
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
// Receipt shows previous customer's PAN!
```

**Why This Breaks:**
1. GlobalPara is a true singleton - persists across transactions
2. Previous transaction data leaks into new transaction
3. PCI compliance violation - displaying wrong customer data

**The Fix:**

```java
// GOOD - Reset before every new transaction
GlobalPara.resetATMTransactionState();
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
```

## Background Threading for EMV

### Thread Pattern

```java
// MainActivity.java - EMV operations on background thread
private Thread threadTxn;

private void startTransaction() {
    threadTxn = new Thread(() -> {
        // EMV operations block - NEVER on UI thread
        int result = emv.txnPerform();
        
        // Update UI on main thread
        runOnUiThread(() -> handleTransactionResult(result));
    });
    threadTxn.start();
}
```

### WARNING: EMV on UI Thread

**The Problem:**

```java
// BAD - Blocks UI, causes ANR (Application Not Responding)
public void onButtonClick(View v) {
    int result = emv.txnPerform();  // BLOCKS for 30+ seconds
    showResult(result);
}
```

**Why This Breaks:**
1. EMV operations wait for card/PIN entry - can take 30+ seconds
2. Android kills apps that block UI thread > 5 seconds
3. User sees frozen screen, then crash

**The Fix:**

```java
// GOOD - Background thread for blocking operations
public void onButtonClick(View v) {
    showProgress();
    new Thread(() -> {
        int result = emv.txnPerform();
        runOnUiThread(() -> {
            hideProgress();
            showResult(result);
        });
    }).start();
}
```

## Lifecycle Management

### Fragment Visibility Handling

```java
// Fragment_page_transaction.java
@Override
public void setUserVisibleHint(boolean isVisibleToUser) {
    super.setUserVisibleHint(isVisibleToUser);
    if (isVisibleToUser && isResumed()) {
        // Fragment became visible - start card detection
        startCardDetection();
    } else {
        // Fragment hidden - cleanup
        stopCardDetection();
    }
}
```

### Activity Destroy Cleanup

```java
// MainActivity.java - Release SDK resources
@Override
protected void onDestroy() {
    if (emv != null) {
        emv.close();
    }
    if (emvcl != null) {
        emvcl.close();
    }
    if (msr != null) {
        msr.close();
    }
    super.onDestroy();
}
```

### WARNING: Missing SDK Cleanup

**The Problem:**

```java
// BAD - SDK resources not released
@Override
protected void onDestroy() {
    super.onDestroy();
    // EMV SDK still holding resources!
}
```

**Why This Breaks:**
1. Native memory leaks accumulate
2. Next app launch may fail to initialize SDK
3. Terminal requires reboot to recover

**The Fix:**

```java
// GOOD - Always close SDK objects
@Override
protected void onDestroy() {
    try {
        if (emv != null) emv.close();
        if (emvcl != null) emvcl.close();
        if (msr != null) msr.close();
        if (Printer != null) Printer.close();
    } catch (Exception e) {
        Log.e(TAG, "SDK cleanup error", e);
    }
    super.onDestroy();
}
```

## Anti-Patterns

### WARNING: Direct Fragment Instantiation

**The Problem:**

```java
// BAD - Creates fragment outside ViewPager management
Fragment_page_receipt receipt = new Fragment_page_receipt();
getSupportFragmentManager().beginTransaction()
    .replace(R.id.container, receipt)
    .commit();
```

**Why This Breaks:**
1. ViewPager loses track of fragment
2. Back button behavior breaks
3. State restoration fails

**The Fix:**

```java
// GOOD - Navigate via ViewPager
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);