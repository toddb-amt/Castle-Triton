---
name: android
description: |
  Manages Android SDK integration, fragment navigation, and lifecycle management for Castle S1F4 PRO payment terminals.
  Use when: modifying UI fragments, handling lifecycle events, integrating with Castle SDK, or managing ViewPager navigation
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Android Skill

Payment terminal Android application built on Castle's Emvtxn-S1F4 sample code. Uses Fragment-based navigation via ViewPager with TabLayout, singleton state management through `GlobalPara`, and background threading for EMV operations. Target SDK 31, Java 8, Gradle 8.5.

## Quick Start

### Fragment Navigation

```java
// Navigate between screens via GlobalPara singleton
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);

// Page constants in GlobalDef.java
public static final int d_PAGE_IDLE = 0;
public static final int d_PAGE_MAIN_MENU = 1;
public static final int d_PAGE_AMOUNT_SELECTION = 2;
public static final int d_PAGE_TRANSACTION = 3;
public static final int d_PAGE_RECEIPT = 4;
public static final int d_PAGE_SETTING = 5;
```

### Background Threading for EMV

```java
// NEVER call EMV SDK from UI thread - causes ANR
threadTxn = new Thread(() -> {
    int result = emv.txnPerform();
    runOnUiThread(() -> updateUI(result));
});
threadTxn.start();
```

### Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew clean build            # Clean build
./gradlew test                   # Run unit tests
./gradlew test --tests "*Test*"  # Run specific tests
```

## Key Concepts

| Concept | Usage | Example |
|---------|-------|---------|
| GlobalPara | Singleton state for transaction data | `GlobalPara.atmSelectedAmount` |
| GlobalDef | Navigation constants | `GlobalDef.d_PAGE_RECEIPT` |
| threadTxn | Background thread for EMV ops | `threadTxn.start()` |
| ViewPager | Fragment container | `SectionsPagerAdapter` |
| MainActivity | Central controller with EMV callbacks | `onTxnResult()` |

## Common Patterns

### Reset State Before Transaction

**When:** Starting a new transaction

```java
// In Fragment_page_main_menu.java before navigation
GlobalPara.resetATMTransactionState();
GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_AMOUNT_SELECTION);
```

### Access Hardware from Fragment

**When:** Printing receipts, controlling LEDs

```java
// Printer access via MainActivity inner class
MainActivity.CTOS_Printer printer = GlobalPara.mainActivity.getPrinter();
printer.printf("Amount: $" + GlobalPara.atmSelectedAmount);
printer.goprintf();

// LED control via GlobalPara
GlobalPara.clLED.showSuccess();
GlobalPara.audio.playOkTone();
```

### Fragment Lifecycle with EMV State

**When:** Handling fragment visibility changes

```java
@Override
public void onResume() {
    super.onResume();
    if (GlobalPara.atmTransactionComplete) {
        GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
    }
}
```

## See Also

- [patterns](references/patterns.md) - Fragment patterns, state management, threading
- [workflows](references/workflows.md) - Build, deploy, test workflows

## Related Skills

- See the **java** skill for Java 8 conventions
- See the **gradle** skill for build configuration
- See the **castle-sdk** skill for EMV/hardware integration
- See the **android-fragments** skill for fragment lifecycle details
- See the **android-testing** skill for unit test patterns