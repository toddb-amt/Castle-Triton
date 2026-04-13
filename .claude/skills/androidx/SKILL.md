---
name: androidx
description: |
  Implements AndroidX components, Material Design, and backward compatibility for Castle S1F4 PRO payment terminal.
  Use when: modifying Fragment lifecycle, ViewPager navigation, Material Design components, or adding AndroidX dependencies
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# AndroidX Skill

Fragment-based Android application using AndroidX libraries for the Castle S1F4 PRO terminal. Uses ViewPager + TabLayout for navigation, ConstraintLayout for layouts, and AppCompat for backward compatibility. No Jetpack Compose, ViewModel, or Navigation Component—state managed via `GlobalPara` singleton.

## Quick Start

### Fragment Lifecycle Pattern

```java
public class Fragment_page_receipt extends Fragment {
    private Handler autoTimeoutHandler = new Handler();
    private View view;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_page_receipt, container, false);
        if (autoTimeoutHandler == null) {
            autoTimeoutHandler = new Handler();  // Recreate after onDestroyView
        }
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (autoTimeoutHandler != null) {
            autoTimeoutHandler.removeCallbacks(autoTimeoutRunnable);
        }
        autoTimeoutHandler = null;  // Prevent memory leaks
    }
}
```

### Navigate Between Fragments

```java
// From any fragment with MainActivity reference
if (mainActivity != null) {
    mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
}

// Page constants in GlobalDef.java
public static final int d_PAGE_MAIN_MENU = 1;
public static final int d_PAGE_AMOUNT_SELECTION = 2;
public static final int d_PAGE_TRANSACTION = 3;
public static final int d_PAGE_RECEIPT = 4;
public static final int d_PAGE_SETTING = 5;
```

## Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| AppCompatActivity | `MainActivity` | Entry point with Toolbar |
| FragmentPagerAdapter | `SectionsPagerAdapter` | Fragment caching via SparseArray |
| ViewPager | `mViewPager` | Swipe disabled for EMV focus |
| CoordinatorLayout | `activity_main.xml` | Material Design coordination |
| ConstraintLayout | All fragment layouts | Flexible UI |
| Handler | Fragments | Timeouts, async callbacks |

## Common Patterns

### Update UI from Background Thread

```java
new Thread(() -> {
    // Background work
    printer.printReceipt();
    
    // Update UI on main thread
    getActivity().runOnUiThread(() -> {
        Toast.makeText(requireContext(), "Printed", Toast.LENGTH_SHORT).show();
    });
}).start();
```

### Handle ViewPager Visibility

```java
@Override
public void setMenuVisibility(boolean menuVisible) {
    super.setMenuVisibility(menuVisible);
    if (menuVisible && !isAuthenticated && rootView != null) {
        showPinDialog();  // Only when user navigates TO this page
    }
}
```

## Dependencies (app/build.gradle)

```gradle
implementation 'androidx.appcompat:appcompat:1.4.1'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
implementation 'com.google.android.material:material:1.5.0'
implementation 'androidx.multidex:multidex:2.0.1'  // Required for 27 SDK JARs
implementation 'androidx.legacy:legacy-support-v4:1.0.0'
```

## See Also

- [patterns](references/patterns.md) - Fragment lifecycle, ViewPager, dialogs
- [workflows](references/workflows.md) - Navigation flow, adding fragments

## Related Skills

- See the **android-fragments** skill for Castle SDK integration patterns
- See the **java** skill for threading and singleton patterns
- See the **gradle** skill for dependency configuration and MultiDex