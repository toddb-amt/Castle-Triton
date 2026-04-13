# Java Modules Reference

## Contents
- Package Structure
- Class Responsibilities
- Import Organization
- Inner Class Pattern

---

## Package Structure

```
castech.emvtxn/                    # Main UI and EMV integration
├── MainActivity.java               # Central activity (10K+ lines)
├── GlobalPara.java                # Singleton state
├── GlobalDef.java                 # Constants
├── Fragment_page_*.java           # 6 UI fragments
├── AtmSettingsManager.java        # SharedPreferences wrapper
├── ClessLed.java                  # LED control with threading
├── ClsAudioInidcator.java        # Audio feedback
└── atm/
    ├── TransactionLog.java        # Transaction record model
    ├── TransactionLogManager.java # Log persistence
    └── host/                      # ATM processor communication
        ├── AtmHostService.java    # Orchestrator
        ├── AtmHostConnection.java # TCP/TLS socket
        ├── AtmTransactionManager.java
        ├── CastleKeyManager.java  # PIN key management
        ├── CastleCardData.java    # EMV data wrapper
        ├── HyosungProtocol.java   # Constants
        ├── HyosungMessageBuilder.java
        ├── HyosungMessageParser.java
        ├── MessageFraming.java    # STX/ETX/LRC
        ├── LrcCalculator.java
        ├── PinBlockFormatter.java
        ├── ProcessorConfig.java
        └── EmvTagEnhancer.java
```

---

## Class Responsibilities

| Component | Single Responsibility |
|-----------|----------------------|
| `MainActivity` | SDK init, EMV callbacks, fragment orchestration |
| `GlobalPara` | Global transaction state singleton |
| `AtmHostService` | High-level transaction flow |
| `AtmTransactionManager` | Request/response sequencing |
| `AtmHostConnection` | Low-level socket I/O |
| `HyosungMessageBuilder` | Pack request objects to bytes |
| `HyosungMessageParser` | Unpack bytes to response objects |
| `CastleKeyManager` | PIN encryption key storage |
| `ProcessorConfig` | Processor-specific settings |
| `EmvTagEnhancer` | EMV tag ordering for processors |

### WARNING: MainActivity God Class

**The Problem:** `MainActivity.java` is ~10,000 lines handling SDK init, callbacks, navigation, and business logic.

**Why This Is Problematic:**
1. Hard to test individual components
2. Changes risk breaking unrelated functionality
3. Difficult for new developers to navigate

**Mitigation (if refactoring):**
- Extract SDK callbacks to separate listener classes
- Move business logic to service classes
- Keep MainActivity as thin orchestrator

---

## Import Organization

Standard order for this codebase:

```java
// 1. Android framework
import android.os.Bundle;
import android.util.Log;
import android.view.View;

// 2. AndroidX
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

// 3. Third-party (Castle SDK)
import CTOS.CtEMV;
import CTOS.CtEMVCL;
import CTOS.CtKMS2Dukpt;
import CTOS.emv.*;

// 4. Project imports
import castech.emvtxn.atm.host.AtmHostService;

// 5. Java standard library
import java.util.concurrent.CountDownLatch;
```

---

## Inner Class Pattern

Large classes contain helper inner classes:

```java
// MainActivity.java inner classes
class TLVUtility { /* TLV parsing */ }
class TLVUtility_CT { /* Contact card TLV */ }
class Converter { /* Hex conversion */ }
class Debugger { /* Logging helpers */ }
class MyEMVEvent implements IEMVEvtListener { /* SDK callbacks */ }
class MyEMVCLSPEvent implements IEMVCLSPEvtListener { /* Contactless callbacks */ }
class CTOS_Printer { /* Printer wrapper */ }
```

**Accessing Inner Classes:**

```java
// Inner class referenced via outer class
MainActivity.CTOS_Printer printer;

// NOT importable - must use qualified name
import castech.emvtxn.MainActivity.CTOS_Printer;  // ERROR
```

---

## Fragment Lifecycle Integration

Fragments receive MainActivity reference:

```java
// Fragment_page_transaction.java
public Fragment_page_transaction() {
    // Required empty constructor for Android
}

public Fragment_page_transaction(MainActivity activity) {
    this.mainActivity = activity;
}

@Override
public void onDestroyView() {
    super.onDestroyView();
    // Cleanup references to avoid memory leaks
    mainActivity = null;
}
```

**DO: Null-check activity references**

```java
if (mainActivity != null && getActivity() != null) {
    mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
}
```

**DON'T: Assume activity is always available**

```java
// BAD - May crash if fragment detached
mainActivity.navigateToPage(GlobalDef.d_PAGE_RECEIPT);
```

---

## Host Communication Layer

See the **hyosung-protocol** skill for message format details.

### Service Layer Hierarchy

```
AtmHostService (orchestrates flow)
    └── AtmTransactionManager (manages state machine)
        └── AtmHostConnection (socket I/O)
            └── MessageFraming (STX/ETX/LRC)
                └── HyosungMessageBuilder/Parser
```

### Usage Pattern

```java
// AtmHostService.java - high level API
AtmHostService hostService = new AtmHostService(processorConfig);

hostService.setEventListener(new AtmEventListener() {
    @Override
    public void onTransactionComplete(TransactionResponse response) {
        GlobalPara.atmResponseCode = response.getResponseCode();
        GlobalPara.atmAuthCode = response.getAuthCode();
    }
});

TransactionRequest request = TransactionRequest.createCashWithdrawal(...);
hostService.processTransaction(request);
```