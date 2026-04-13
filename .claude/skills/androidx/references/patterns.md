# AndroidX Patterns Reference

## Contents
- Fragment Lifecycle Management
- ViewPager Navigation
- Handler and Threading
- AlertDialog Patterns
- Anti-Patterns

---

## Fragment Lifecycle Management

### Proper Handler Cleanup

```java
// GOOD - Null handler in onDestroyView, recreate in onCreateView
private Handler autoTimeoutHandler = new Handler();

@Override
public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    if (autoTimeoutHandler == null) {
        autoTimeoutHandler = new Handler();
    }
    return inflater.inflate(R.layout.fragment_page_receipt, container, false);
}

@Override
public void onDestroyView() {
    super.onDestroyView();
    if (autoTimeoutHandler != null) {
        autoTimeoutHandler.removeCallbacks(autoTimeoutRunnable);
    }
    autoTimeoutHandler = null;
}
```

### WARNING: Handler Memory Leaks

**The Problem:**

```java
// BAD - Handler never cleaned up
private Handler handler = new Handler();

@Override
public void onResume() {
    handler.postDelayed(myRunnable, 30000);  // Posted but never removed
}
// onDestroyView never removes callback → memory leak
```

**Why This Breaks:**
1. Fragment destroyed but Handler holds reference to Activity
2. Runnable executes on destroyed fragment → crash
3. Memory leak accumulates with each fragment recreation

**The Fix:**

```java
// GOOD - Always pair post with removal
@Override
public void onPause() {
    super.onPause();
    if (handler != null) {
        handler.removeCallbacks(myRunnable);
    }
}
```

---

## ViewPager Navigation

### SectionsPagerAdapter with Caching

```java
public class SectionsPagerAdapter extends FragmentPagerAdapter {
    private SparseArray<Fragment> map = new SparseArray<>();
    private MainActivity activity;

    @Override
    public Fragment getItem(int position) {
        Fragment cached = map.get(position);
        if (cached != null) return cached;
        
        Fragment fragment = createFragment(position);
        if (fragment != null) {
            map.put(position, fragment);
        }
        return fragment;
    }
    
    public Fragment getCachedFragment(int position) {
        return map.get(position);
    }
}
```

### Programmatic Navigation

```java
// In MainActivity
public void navigateToPage(int pageIndex) {
    if (mViewPager != null && pageIndex >= 0 && pageIndex < mSectionsPagerAdapter.getCount()) {
        // Manual refresh for adjacent fragments (ViewPager doesn't trigger onResume)
        if (pageIndex == GlobalDef.d_PAGE_RECEIPT) {
            Fragment fragment = mSectionsPagerAdapter.getCachedFragment(pageIndex);
            if (fragment instanceof Fragment_page_receipt) {
                ((Fragment_page_receipt) fragment).refreshDisplay();
            }
        }
        mViewPager.setCurrentItem(pageIndex, false);
    }
}
```

### WARNING: ViewPager Adjacent Fragment Lifecycle

**The Problem:**

```java
// BAD - Assuming onResume fires when navigating to adjacent page
@Override
public void onResume() {
    super.onResume();
    loadData();  // May not be called when swiping from adjacent page
}
```

**Why This Breaks:**
1. ViewPager pre-creates adjacent fragments (offscreenPageLimit)
2. Navigating from page 2 to page 3 doesn't trigger page 3's onResume
3. Fragment displays stale data

**The Fix:**

```java
// GOOD - Use setMenuVisibility for actual visibility
@Override
public void setMenuVisibility(boolean menuVisible) {
    super.setMenuVisibility(menuVisible);
    if (menuVisible && isResumed()) {
        loadData();
    }
}
```

---

## Handler and Threading

### Background Thread with UI Update

```java
// GOOD - Proper threading pattern
new Thread(() -> {
    try {
        // Background work (SDK calls, network, printing)
        printer.printReceipt();
        
        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), "Printed", Toast.LENGTH_SHORT).show();
            });
        }
    } catch (Exception e) {
        Log.e(TAG, "Print error", e);
    }
}).start();
```

### WARNING: UI Operations on Background Thread

**The Problem:**

```java
// BAD - Updating UI from background thread
new Thread(() -> {
    txvStatus.setText("Loading...");  // CalledFromWrongThreadException
    String result = fetchData();
    txvStatus.setText(result);        // Crashes
}).start();
```

**Why This Breaks:**
1. Android UI toolkit is not thread-safe
2. Only main thread can touch UI elements
3. Crashes with `CalledFromWrongThreadException`

**The Fix:**

```java
// GOOD - Use runOnUiThread or Handler
new Thread(() -> {
    String result = fetchData();
    getActivity().runOnUiThread(() -> {
        txvStatus.setText(result);
    });
}).start();
```

---

## AlertDialog Patterns

### PIN Entry Dialog

```java
private void showPinDialog() {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle("Administrator PIN Required");
    
    final EditText input = new EditText(requireContext());
    input.setInputType(InputType.TYPE_NUMBER_PASSWORD);
    input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(6) });
    builder.setView(input);
    
    builder.setPositiveButton("OK", (dialog, which) -> {
        String pin = input.getText().toString();
        if (validatePin(pin)) {
            isAuthenticated = true;
            setContentVisible(true);
        } else {
            showIncorrectPinMessage();
        }
    });
    
    builder.setNegativeButton("Cancel", (dialog, which) -> {
        dialog.cancel();
        mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
    });
    
    builder.setCancelable(false);
    builder.show();
}
```

### EMV Callback Dialog (from MainActivity)

```java
// Must run on UI thread since EMV callback is on threadTxn
this.runOnUiThread(() -> {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("Confirm Application");
    builder.setMessage(appLabel);
    builder.setPositiveButton("Yes", (arg0, arg1) -> GlobalPara.appSelectedConfirmOK = 1);
    builder.setNegativeButton("No", (arg0, arg1) -> GlobalPara.appSelectedConfirmOK = 2);
    
    AlertDialog ad = builder.create();
    ad.setCancelable(false);
    ad.setCanceledOnTouchOutside(false);
    ad.show();
});
```

---

## Anti-Patterns Summary

| Anti-Pattern | Problem | Fix |
|--------------|---------|-----|
| Handler without cleanup | Memory leak, crash on destroyed fragment | Null in onDestroyView |
| UI from background thread | CalledFromWrongThreadException | Use runOnUiThread |
| Assuming onResume in ViewPager | Stale data on adjacent pages | Use setMenuVisibility |
| findViewById in loops | Performance overhead | Cache view references |
| Static Fragment references | Memory leak | Use WeakReference or null |
| Direct Activity access | Null crash after rotation | Use requireActivity() with null check |