# SDK Transaction Flow Analysis

## Document Purpose
Comparison of transaction flow patterns between CastleHost SampleApp, Original Emvtxn-S1F4, and our modified ATM version to identify proper SDK usage patterns.

---

## 1. CastleHost SampleApp Architecture (RECOMMENDED PATTERN)

### 1.1 SDK Instance Management - SINGLETON PATTERN
```java
// EmvclInstance.java - Enum singleton
public enum EmvclInstance {
    INSTANCE;

    private CtEMVCL emvcl;

    EmvclInstance() {
        emvcl = new CtEMVCL();  // Created ONCE
    }

    public CtEMVCL getInstance() {
        return emvcl;
    }
}

// EmvInstance.java - Same pattern
public enum EmvInstance {
    INSTANCE;

    private final CtEMV emv;

    EmvInstance() {
        emv = new CtEMV();  // Created ONCE
    }

    public CtEMV getInstance() {
        return emv;
    }
}
```

**Key Point**: SDK objects (CtEMV, CtEMVCL, CtEMVMSR) are created ONCE at app startup and reused for all transactions.

### 1.2 Transaction Initialization Pattern
```java
// PollCard.init() - Called at START of each transaction
public void init() {
    emvmsr.flushTracksBuffer();          // CRITICAL: Clear MSR buffer
    EmvInstance.INSTANCE.emvTxnResult = 0;  // Reset transaction result
    setDf78Value();
    setEmvclDf79Value();
    setEmvDf79Value();
    initEMV();
    initEMVCL();
}

// EmvInstance.init() - Re-initialize EMV for each transaction
public void init() {
    emv.initialize(new MyEmvEvent());    // Re-register event handler
    emv.enablePINSound();
    emv.setConfiguration(...);           // Reload config
    setSecureInfo();
}
```

### 1.3 Thread Pool Pattern (NOT raw Thread)
```java
// Uses ThreadPoolManager instead of new Thread()
ThreadPoolManager.INSTANCE.getSingleExecutor().submit(detectTask);

// Card detection as a Runnable task
private final Runnable detectTask = () -> {
    EmvInstance.INSTANCE.setEmvEvent(new MyEmvEvent());
    do {
        // Contactless check
        if (emvcl.detectCard() == 0) {
            requestBean.setPosEntryMode(PosEntryModel.EMVCL);
            readContactLessCard();
        }

        // MSR check
        int intRtn = emvmsr.readTracks();
        if (intRtn != CtEMVMSR.d_EMVMSR_ERR_NO_SWIPE) {
            requestBean.setPosEntryMode(PosEntryModel.MSR);
            readMsrCard();
        }

        // Contact check
        sc.status(0);
        int status = sc.getStatus();
        if ((status & 0x01) == 0x01) {
            requestBean.setPosEntryMode(PosEntryModel.EMV);
            readContactCard();
        }

        ThreadPoolManager.INSTANCE.sleep(100);  // 100ms between polls
    } while (detecting);
};
```

### 1.4 Cleanup Pattern
```java
// PollCard.onDestroy() - Called when leaving transaction
public void onDestroy() {
    setDetectCardFlag(false);            // Stop detection loop
    EmvInstance.INSTANCE.onDestory();    // Clear event handlers
}

// EmvInstance.onDestory() - Clear event reference
public void onDestory() {
    this.pollCardEMVEvent = null;
}
```

### 1.5 Fragment Lifecycle Integration
```java
// PollCardFragment.java
@Override
public void onStart() {
    super.onStart();
    ThreadPoolManager.INSTANCE.getMultipleExecutor().submit(showIdleLedTask);
    pollCard = new PollCard(this);
    ThreadPoolManager.INSTANCE.getSingleExecutor().submit(init);
    pollCard.readCard();
}

@Override
public void onDestroyView() {
    super.onDestroyView();
    pollCard.onDestroy();  // CRITICAL: Clean up on view destroy
}

// Cancel button handler
@Override
public void onClick(View v) {
    pollCard.setDetectCardFlag(false);   // Stop detection
    Navigation.findNavController(view).popBackStack(...);
}
```

### 1.6 Contactless Transaction Flow
```java
private void readContactLessCard() {
    EMVCLActData actData = new EMVCLActData();
    EMVCLRcDataEx rcData = new EMVCLRcDataEx();

    // Set up transaction data (amount, etc.)
    actData.transactionData = new byte[128];
    // ... populate actData ...

    // Initialize transaction
    int result = emvcl.initTransactionEx(actData.tagNum, actData.transactionData, actData.transactionDataLen);

    // Perform transaction (single call, NOT in loop)
    result = emvcl.performTransactionEx(rcData);

    if (result != CtEMVCL.d_EMVCL_RC_DATA) {
        cardView.onReadCardFailed("Tap card failed");
        return;
    }

    // Analyze result
    result = emvcl.analyzeTransactionEx(rcData, rcDataAnalyze);
    // ... process results ...
}
```

---

## 2. Original Emvtxn-S1F4 Pattern

### 2.1 Thread Creation (Simpler but less robust)
```java
public int btnTransaction_Click(final View view) {
    Log.d(TAG, "btnTransaction_Click() ***");

    // Direct thread creation - no state checking
    threadTxn = new Thread(new Runnable() {
        @Override
        public void run() {
            // ... transaction code ...
        }
    });
    threadTxn.start();
    return 0;
}
```

### 2.2 Card Detection Loop (Original)
```java
do {
    if (isCLAvaliable == true) {
        intRtn = emvcl.performTransactionEx(rcData);
        if (intRtn != 0x80000020) {  // Not PENDING
            entryMode = GlobalDef.d_ENTRY_MODE_CL;
            break;
        }
    }

    if (isMSRAvaliable == true) {
        intRtn = msr.readTracks();
        if (intRtn == 0 || (intRtn != CtEMVMSR.d_EMVMSR_ERR_NO_SWIPE)) {
            entryMode = GlobalDef.d_ENTRY_MODE_MSR;
            break;
        }
    }

    if (isCTAvaliable == true) {
        sc.status(0);
        int status = sc.getStatus();
        if ((status & 0x01) == 0x01) {
            entryMode = GlobalDef.d_ENTRY_MODE_CT;
            break;
        }
    }

    // NOTE: No sleep() between iterations!
} while (true);
```

**Key Differences from CastleHost:**
1. No sleep between polling cycles
2. No detecting flag to break loop
3. No cleanup on cancel
4. Uses performTransactionEx() for detection (different from detectCard())

---

## 3. Our Modified ATM Version - Problems Identified

### 3.1 Issues Found

| Issue | Our Code | CastleHost Pattern |
|-------|----------|-------------------|
| Thread creation | New Thread each time | ThreadPoolManager singleton |
| SDK cleanup | Missing proper cleanup | emvmsr.flushTracksBuffer(), emv.initialize() |
| Event handlers | Not cleared on cancel | Set to null in onDestroy() |
| Detection loop | No proper exit | `detecting` flag checked each iteration |
| State tracking | Added but complex | Simple flag pattern |

### 3.2 Likely Crash Causes

1. **Previous transaction thread not fully terminated**
   - We interrupt but may not wait for completion
   - SDK may be in inconsistent state

2. **SDK not re-initialized between transactions**
   - CastleHost calls `emv.initialize()` per transaction
   - We don't re-initialize

3. **MSR buffer not flushed**
   - CastleHost calls `emvmsr.flushTracksBuffer()` on init
   - We don't flush

4. **Event handlers not cleared**
   - CastleHost sets event handlers to null on destroy
   - We leave them dangling

5. **performTransactionEx() called without proper init**
   - CastleHost separates `detectCard()` from `performTransactionEx()`
   - Original and our code use performTransactionEx() for polling

---

## 4. Recommended Fixes

### 4.1 Add SDK Singleton Pattern
```java
// Create EmvSingleton.java
public enum EmvSingleton {
    INSTANCE;

    private CtEMV emv;
    private CtEMVCL emvcl;
    private CtEMVMSR msr;

    EmvSingleton() {
        emv = new CtEMV();
        emvcl = new CtEMVCL();
        msr = new CtEMVMSR();
    }

    public void initForTransaction() {
        msr.flushTracksBuffer();
        // Re-register event handlers
    }

    public void cleanup() {
        // Clear event handlers
    }
}
```

### 4.2 Fix Transaction Start
```java
public int btnTransaction_Click(final View view) {
    // 1. Clean up any previous state
    if (threadTxn != null && threadTxn.isAlive()) {
        // Wait for thread to finish
        try {
            threadTxn.join(1000);
        } catch (InterruptedException e) {}
    }

    // 2. Flush MSR buffer (CRITICAL)
    msr.flushTracksBuffer();

    // 3. Re-initialize SDK for new transaction
    emv.initialize(emvEvent);

    // 4. Start new transaction
    threadTxn = new Thread(...);
    threadTxn.start();
}
```

### 4.3 Fix Card Detection Loop
```java
// Add detectCard() instead of performTransactionEx() for polling
do {
    if (txnAborted) break;

    if (isCLAvaliable) {
        // Use detectCard() for polling (like CastleHost)
        if (emvcl.detectCard() == 0) {
            // Card detected, NOW call performTransactionEx()
            entryMode = GlobalDef.d_ENTRY_MODE_CL;
            break;
        }
    }

    // ... MSR and Contact checks ...

    Thread.sleep(100);  // Add sleep like CastleHost
} while (!txnAborted);
```

### 4.4 Fix Cleanup on Cancel
```java
public void abortTransaction() {
    txnAborted = true;

    // Cancel SDK operations
    emvcl.cancelTransaction();

    // Wait for thread
    if (threadTxn != null && threadTxn.isAlive()) {
        threadTxn.interrupt();
        try {
            threadTxn.join(1000);
        } catch (InterruptedException e) {}
    }

    // Flush buffers
    msr.flushTracksBuffer();

    // Clear event handlers
    // emvEvent = null;  // or set to dummy handler

    threadTxn = null;
    GlobalPara.atmTransactionInProgress = false;
}
```

---

## 5. Key API Differences Discovered

### 5.1 Contactless Detection Methods
| Method | Purpose | When to Use |
|--------|---------|-------------|
| `detectCard()` | Check if card present | Polling loop |
| `performTransactionEx()` | Start transaction | After card detected |
| `initTransactionEx()` | Initialize for transaction | Before performTransactionEx |
| `analyzeTransactionEx()` | Analyze result | After performTransactionEx |
| `cancelTransaction()` | Abort transaction | On cancel/error |

### 5.2 MSR Methods
| Method | Purpose |
|--------|---------|
| `flushTracksBuffer()` | Clear any pending track data |
| `readTracks()` | Check for swipe |
| `getMaskedTracks()` | Get track data |

### 5.3 EMV Contact Methods
| Method | Purpose |
|--------|---------|
| `initialize()` | Set up EMV with event handler |
| `txnAppSelect()` | Select application |
| `txnPerform()` | Perform transaction |
| `txnCompletion()` | Complete transaction |

---

## 6. Next Steps

1. **Simplify our code** - Remove complex try-catch, state tracking
2. **Add proper init** - Call `msr.flushTracksBuffer()` and `emv.initialize()` per transaction
3. **Use detectCard()** - For contactless polling instead of performTransactionEx()
4. **Add sleep to loop** - 100ms between polls
5. **Fix cleanup** - Properly clear handlers and wait for thread on cancel
6. **Consider ThreadPool** - Instead of creating new Thread each time

---

## 7. File References

| File | Location | Description |
|------|----------|-------------|
| CastleHost PollCard.java | `CastlesHost_SampleApp_TSYS-Sierra-14C/SampleApp/app/src/main/java/com/castles/sample2/presenter/PollCard.java` | Transaction presenter |
| CastleHost EmvInstance.java | `CastlesHost_SampleApp_TSYS-Sierra-14C/SampleApp/app/src/main/java/com/castles/sample2/emv/EmvInstance.java` | EMV singleton |
| CastleHost EmvclInstance.java | `CastlesHost_SampleApp_TSYS-Sierra-14C/SampleApp/app/src/main/java/com/castles/sample2/emv/EmvclInstance.java` | EMVCL singleton |
| CastleHost PollCardFragment.java | `CastlesHost_SampleApp_TSYS-Sierra-14C/SampleApp/app/src/main/java/com/castles/sample2/ui/transaction/PollCardFragment.java` | UI fragment |
| Original MainActivity.java | `/tmp/original_MainActivity.java` | Extracted from Emvtxn-S1F4.zip |
| Our MainActivity.java | `Android SDK/Sample Code/Emvtxn-S1F4/app/src/main/java/castech/emvtxn/MainActivity.java` | Current modified version |
