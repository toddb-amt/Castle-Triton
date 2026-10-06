# Java Patterns Reference

## Contents
- Threading Patterns
- Singleton State Pattern
- SDK Callback Pattern
- Utility Class Pattern
- Factory Method Pattern
- Thread Safety

---

## Threading Patterns

### Background Thread for Blocking Operations

All EMV and network operations MUST run on background threads.

```java
// MainActivity.java:2501-2506
Thread threadTxn;           // Main EMV transaction thread
Thread thGetOnlinPin;       // Online PIN entry thread
volatile boolean txnAborted = false;
volatile boolean inCardDetectionLoop = false;
```

**DO: Dedicated thread with volatile abort flag**

```java
threadTxn = new Thread(() -> {
    while (!txnAborted && inCardDetectionLoop) {
        int ret = emvcl.performTransactionEx();
        if (ret != CTOS_EMV_PENDING) {
            break;
        }
        Thread.sleep(100);
    }
});
threadTxn.start();
```

**DON'T: Call SDK on UI thread**

```java
// BAD - Will cause ANR after 5 seconds
public void onClick(View v) {
    int ret = emv.txnPerform();  // Blocks for 30+ seconds
}
```

### UI Thread Marshalling

```java
// From background thread, update UI safely
activity.runOnUiThread(() -> {
    textView.setText("Transaction Complete");
    progressBar.setVisibility(View.GONE);
});
```

### Thread Cleanup Pattern

```java
// MainActivity.java:3824-3832
private void stopTransactionThread() {
    txnAborted = true;
    if (threadTxn != null && threadTxn.isAlive()) {
        threadTxn.interrupt();
        try {
            threadTxn.join(3000);  // Wait max 3 seconds
            if (threadTxn.isAlive()) {
                Log.w(TAG, "Thread did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### CountDownLatch for Synchronization

```java
// Block until background operation completes
private volatile CountDownLatch atmTransactionLatch;

public void processTransaction() {
    atmTransactionLatch = new CountDownLatch(1);
    
    new Thread(() -> {
        try {
            doHostTransaction();
        } finally {
            atmTransactionLatch.countDown();
        }
    }).start();
    
    atmTransactionLatch.await();  // Block calling thread
}
```

---

## Singleton State Pattern

**GlobalPara.java** - All static fields, no instance creation.

```java
// GlobalPara.java
public class GlobalPara {
    public static MainActivity mainActivity;
    public static String atmSelectedAmount = "0.00";
    public static String asciiPAN;
    public static boolean atmTransactionComplete = false;
    
    // Reset between transactions
    public static void resetATMTransactionState() {
        atmSelectedAmount = "0.00";
        asciiPAN = null;
        atmTransactionComplete = false;
        // ... 60+ fields reset
    }
}
```

**DO: Call reset before new transaction**

```java
GlobalPara.resetATMTransactionState();
GlobalPara.atmSelectedAmount = amount;
navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
```

**DON'T: Forget to reset state**

```java
// BAD - Previous transaction data leaks into new transaction
navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
```

---

## SDK Callback Pattern

EMV callbacks implemented as inner classes:

```java
// MainActivity.java
private class MyEMVEvent implements IEMVEvtListener {
    @Override
    public void onTxnDataGet(byte[] tlvData) {
        // Card presented - runs on SDK thread
        parseTlvData(tlvData);
    }
    
    @Override
    public void onGetPINNotify(int pinType, int[] keySet, int[] keyIndex) {
        // PIN required - set key location for SDK
        keySet[0] = GlobalPara.onlinePinKeySet;
        keyIndex[0] = GlobalPara.onlinePinKeyIndex;
    }
    
    @Override
    public void onTxnResult(int result, byte[] tags) {
        // Transaction complete - marshal to UI thread
        activity.runOnUiThread(() -> handleResult(result));
    }
}
```

---

## Utility Class Pattern

Private constructor prevents instantiation:

```java
// HyosungProtocol.java
public final class HyosungProtocol {
    private HyosungProtocol() {}  // Prevent instantiation
    
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final String MSG_TYPE_TRANSACTION = "85";
}
```

**DO: Use final class with private constructor**

```java
public final class LrcCalculator {
    private LrcCalculator() {}
    
    public static byte calculate(byte[] data) {
        byte lrc = 0;
        for (byte b : data) {
            lrc ^= b;
        }
        return lrc;
    }
}
```

---

## Factory Method Pattern

```java
// TransactionRequest.java
public static TransactionRequest createCashWithdrawal(
        String terminalId, String track2, String pinBlock,
        long amountCents, long surchargeCents, String accountType) {
    TransactionRequest req = new TransactionRequest();
    req.setTerminalId(terminalId);
    req.setTransactionType(HyosungProtocol.OP_CASH_WITHDRAWAL + accountType + accountType);
    req.setTrack2Data(track2);
    req.setPinBlock(pinBlock);
    req.setAmountCents(amountCents);
    req.setSurchargeCents(surchargeCents);
    return req;
}
```

---

## Thread Safety

### WARNING: Shared Mutable State

**The Problem:**

```java
// BAD - Race condition on shared state
public void updateAmount(String amount) {
    GlobalPara.atmSelectedAmount = amount;
    GlobalPara.atmTotal = calculateTotal(amount);  // May see stale amount
}
```

**Why This Breaks:**
1. Non-volatile fields may be cached by CPU
2. Another thread sees inconsistent state
3. Results in wrong total displayed

**The Fix:**

```java
// Use volatile or synchronize access
public static volatile String atmSelectedAmount;

// Or use synchronized block for compound operations
public synchronized static void setAmountAndTotal(String amount) {
    atmSelectedAmount = amount;
    atmTotal = calculateTotal(amount);
}
```