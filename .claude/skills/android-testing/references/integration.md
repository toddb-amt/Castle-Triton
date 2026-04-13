# Integration Testing Reference

## Contents
- Instrumented vs Unit Tests
- SDK Diagnostic Testing
- On-Device Test Patterns
- Key Validation Tests
- Transaction Flow Testing

## Instrumented vs Unit Tests

| Type | Location | Runs On | Castle SDK |
|------|----------|---------|------------|
| Unit | `src/test/` | JVM | ❌ No access |
| Instrumented | `src/androidTest/` | Device/Terminal | ✅ Full access |
| Diagnostic | `src/main/.../test/` | Terminal runtime | ✅ Full access |

## SDK Diagnostic Testing

Diagnostic tests in `src/main/java/castech/emvtxn/test/` run on the terminal as part of the app:

```java
public class EmvCryptogramTest {
    private static final String TAG = "EmvCryptogramTest";
    private Context context;
    private StringBuilder results;

    public EmvCryptogramTest(Context context) {
        this.context = context;
        this.results = new StringBuilder();
    }

    public String runAllTests() {
        results.setLength(0);
        log("=== EMV Cryptogram Diagnostic Test ===");
        
        testDukptKey();
        testFixedKey();
        testMkskKey();
        testKekKey();
        testEmvSecureDataConfig();
        showAnalysis();
        
        return results.toString();
    }
}
```

## On-Device Test Patterns

### Key Existence Verification

```java
private void testDukptKey() {
    log("--- Test 1: DUKPT Key at C001/001A ---");
    
    CtKMS2Dukpt dukptKey = new CtKMS2Dukpt();
    try {
        dukptKey.selectKey(KEY_SET_C001, KEY_INDEX_001A);
        log("✅ DUKPT Key EXISTS at C001/001A");
        
        byte[] ksn = dukptKey.getKSN();
        if (ksn != null && ksn.length >= 10) {
            dukptKeyOk = true;
            log("   ✅ Key is USABLE - KSN: " + bytesToHex(ksn, ksn.length));
        }
    } catch (CtKMS2Exception e) {
        log("❌ DUKPT Key MISSING - Error: 0x" + 
            String.format("%08X", e.getError()));
    }
}
```

### Key Attribute Inspection

```java
private void testKekKey() {
    CtKMS2FixedKey kekKey = new CtKMS2FixedKey();
    try {
        kekKey.selectKey(KEY_SET_C000, KEY_INDEX_0000);
        
        int attr = kekKey.getKeyAttribute();
        log("Key Attribute: 0x" + String.format("%08X", attr));
        
        // Decode attribute flags
        if ((attr & 0x00000001) != 0) log("   - Master Key");
        if ((attr & 0x00000004) != 0) log("   - PIN Encryption");
        if ((attr & 0x00000008) != 0) log("   - MAC Calculation");
        if ((attr & 0x00000010) != 0) log("   - Data Encryption");
        if ((attr & 0x00000020) != 0) log("   - KBPK");
    } catch (CtKMS2Exception e) {
        log("❌ KEY MISSING - Error: 0x" + String.format("%08X", e.getError()));
    }
}
```

### Key Location Scanning

```java
private void testMkskKey() {
    int[][] mkskLocations = {
        {0xC001, 0x0001},  // Castle MVP
        {0xC001, 0x0010},  // Slot 10 decimal
        {0xC001, 0x000A},  // Slot 10 as 0x0A
        {0xCFFF, 0x0010},  // CFFF with slot 10
    };

    for (int[] loc : mkskLocations) {
        try {
            CtKMS2MKSK testMksk = new CtKMS2MKSK();
            testMksk.selectKey(loc[0], loc[1]);
            
            // Try encryption to verify key works
            byte[] testData = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
            testMksk.setCipherMethod(CtKMS2SymmetryKey.DATA_ENCRYPT_METHOD_ECB);
            byte[] result = testMksk.dataEncrypt();
            
            if (result != null && result.length >= 8) {
                log(String.format("✅ MKSK WORKING at %04X/%04X", loc[0], loc[1]));
                return;
            }
        } catch (CtKMS2Exception e) {
            log(String.format("   %04X/%04X: Error 0x%08X", 
                loc[0], loc[1], e.getError()));
        }
    }
}
```

## WARNING: Unit Tests Cannot Access Castle SDK

**The Problem:**

```java
// BAD - This test will fail with NoClassDefFoundError
@Test
public void testDukptKey() {
    CtKMS2Dukpt dukpt = new CtKMS2Dukpt();  // CTOS class not available
    dukpt.selectKey(0xC001, 0x001A);
}
```

**Why This Breaks:** Castle SDK JARs contain stubs that require terminal native libraries. Unit tests run on JVM without these libraries.

**The Fix:**

For SDK integration tests, create diagnostic classes in `src/main/java/.../test/` and invoke them from the app UI (e.g., admin screen):

```java
// In Fragment_page_admin_atm.java
EmvCryptogramTest test = new EmvCryptogramTest(getContext());
String results = test.runAllTests();
displayResults(results);
```

## Transaction Flow Testing

Manual testing is required for full EMV flows. See `docs/ATM_TESTING_GUIDE.md` for the 10-phase test plan covering:

1. Basic Navigation
2. Amount Selection  
3. Transaction Processing (Contact/Contactless/MSR)
4. Receipt Screen
5. Admin Settings
6. End-to-End Flows
7. LED/Audio Feedback
8. Error Handling
9. Performance
10. Security

Copy this checklist for transaction testing:
- [ ] Chip card insertion detected
- [ ] Application selection works
- [ ] PIN prompt appears
- [ ] PIN entry succeeds (or error captured)
- [ ] Cryptogram generated (9F26 present)
- [ ] Host communication succeeds
- [ ] Receipt displays correctly