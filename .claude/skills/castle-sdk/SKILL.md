---
name: castle-sdk
description: |
  Integrates Castle CTOS SDK libraries for EMV, printer, and hardware control on S1F4 PRO terminals.
  Use when: initializing SDK components, handling EMV callbacks, managing keys, controlling LED/audio/printer, or processing card transactions
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Castle SDK Skill

Castle CTOS SDK is a proprietary JAR-based SDK for S1F4 PRO Android payment terminals. It provides 27 libraries covering EMV chip/contactless/swipe, thermal printing, key management (KMS2/DUKPT), and hardware control. The SDK uses event-driven callbacks for transaction flow and requires Java 8 compatibility.

## Quick Start

### SDK Initialization

```java
// Safe lazy initialization pattern - ONLY on real hardware
private void initializeCastleSdk() {
    if (sdkInitialized || isRunningOnEmulator) return;
    
    try {
        emv = new CtEMV();
        emv.setGlobalEventListener(new MyEMVEvent(this, emv));
        
        emvcl = new CtEMVCL();
        emvcl.setCLEventListener(new MyEMVCLSPEvent());
        
        msr = new CtEMVMSR();
        sdkInitialized = true;
    } catch (Exception e) {
        Log.e(TAG, "SDK init failed: " + e.getMessage());
    }
}
```

### EMV PIN Callback

```java
@Override
public int onGetPINNotify(EMVGetPINFuncPara pinPara) {
    // Set key location where DUKPT was injected
    GlobalPara.onlinePinKeySet = 0x0000C000;
    GlobalPara.onlinePinKeyIndex = 0x00000000;
    return 0;  // Continue transaction
}
```

### Printer Control

```java
// Printer is inner class - reference as MainActivity.CTOS_Printer
MainActivity.CTOS_Printer printer = activity.getPrinter();
printer.printf("RECEIPT\n");
printer.printf("|<|CENTERED TEXT|>|\n");
printer.goprintf();  // Flush and print
```

## Key Concepts

| Concept | Usage | Location |
|---------|-------|----------|
| DUKPT Key | PIN encryption | `0xC000/0x0000` |
| TMK/KEK | Key unwrapping | `0xCFFF/0x0000` |
| LED Status | Card detection feedback | `ClessLed.java` |
| EMV Callbacks | Transaction events | `MyEMVEvent`, `MyEMVSPEvent` |

## Critical Error Codes

| Code | Hex | Meaning | Solution |
|------|-----|---------|----------|
| 4099 | 0x1003 | PIN key wrong attribute | Key Injection Tool set DECRYPT, SDK needs PIN |
| 10503 | 0x2907 | Key not found | Inject key via Key Injection Tool |
| 10505 | 0x2909 | TR-31 unwrap failed | KBPK missing or wrong attribute |

## Build Requirements

```gradle
android {
    multiDexEnabled true  // REQUIRED for 27 SDK JARs
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation fileTree(include: ['*.jar'], dir: 'libs')
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

## See Also

- [patterns](references/patterns.md) - SDK initialization, callbacks, hardware control
- [workflows](references/workflows.md) - Transaction flow, deployment, key injection

## Related Skills

- See the **java** skill for Java 8 patterns and threading
- See the **android-fragments** skill for UI navigation
- See the **emv** skill for EMV tag handling and cryptogram generation
- See the **gradle** skill for build configuration and multidex
- See the **dukpt** skill for PIN encryption key management