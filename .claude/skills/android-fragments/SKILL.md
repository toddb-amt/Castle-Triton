---
name: android-fragments
description: |
  Manages Android SDK integration, fragment navigation, and lifecycle management for Castle S1F4 PRO payment terminals.
  Use when: modifying UI fragments, handling lifecycle events, integrating with Castle SDK, or managing ViewPager navigation
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Android Fragments Skill

Fragment-based navigation for Castle S1F4 PRO payment terminals using ViewPager with TabLayout. This app uses a single-activity architecture with 6 fragments managing ATM transaction flow. All EMV operations run on a background thread (`threadTxn`) while UI updates happen on the main thread.

## Quick Start

### Fragment Navigation

```java
// Navigate between screens via GlobalPara singleton
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);

// Page constants defined in GlobalDef.java
static final int d_PAGE_IDLE             = 0;
static final int d_PAGE_MAIN_MENU        = 1;
static final int d_PAGE_AMOUNT_SELECTION = 2;
static final int d_PAGE_TRANSACTION      = 3;
static final int d_PAGE_RECEIPT          = 4;
static final int d_PAGE_SETTING          = 5;
```

### Fragment Constructor Pattern

```java
// REQUIRED: Pass MainActivity reference for SDK access
public Fragment_page_transaction(MainActivity activity) {
    if (this.mainActivity == null) {
        this.mainActivity = activity;
    }
}

// Also provide empty constructor for Android system
public Fragment_page_transaction() {
}
```

## Key Concepts

| Concept | Usage | Example |
|---------|-------|---------|
| Navigation | Via GlobalPara singleton | `GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_*)` |
| State | GlobalPara singleton holds all transaction state | `GlobalPara.atmSelectedAmount = "100.00"` |
| Threading | EMV on threadTxn, UI on main | `mainActivity.runOnUiThread(...)` |
| Mode Detection | Check ATM flags before inflate | `isInATMMode()` checks `atmSelectedAmount` and `atmBalanceInquiryMode` |
| LED Control | Via GlobalPara.clLED | `GlobalPara.clLED.setImgView(...)` |

## Common Patterns

### Conditional Layout Inflation

**When:** Same fragment needs different layouts based on mode (ATM vs legacy)

```java
@Override
public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    isATMMode = isInATMMode();
    
    if (isATMMode) {
        return createATMView(inflater, container);
    } else {
        return createLegacyView(inflater, container);
    }
}
```

### Auto-Start Transaction on Page Entry

**When:** Transaction fragment should begin card polling automatically

```java
@Override
public void onResume() {
    super.onResume();
    if (GlobalPara.isInitThreadFinish && isATMMode) {
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (btnTransaction != null) {
                    btnTransaction.performClick();
                }
            }
        }, 500);
    }
}
```

## See Also

- [patterns](references/patterns.md) - Fragment lifecycle, ViewPager, state management
- [workflows](references/workflows.md) - Adding screens, EMV callbacks, navigation

## Related Skills

For Java patterns and conventions, see the **java** skill. For Gradle build configuration, see the **gradle** skill. For EMV SDK integration callbacks, see the **emv** skill.