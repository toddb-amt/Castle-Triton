# Triton Standard Refactor — Implementation Plan

## Goals

1. Replace Hyosung STD1 protocol with Triton Standard (TSCD 5.22)
2. Fix all critical code review findings (PCI/security issues)
3. Break up 10,783-line MainActivity into clean components
4. Add proper thread safety
5. Keep all Castle SDK integration, UI, and hardware support working

---

## Phase 1: Fix Critical Security Issues (Before Any Refactor)
**Priority**: HIGHEST — These apply to both protocols
**Estimated effort**: 1 day

### 1.1 Remove key material from source code
- [ ] Remove BDK/IPEK from GlobalPara.java comments (C2)
- [ ] Audit all files for hardcoded keys

### 1.2 Fix PAN/Track2 logging
- [ ] Mask PAN in all Log.d() calls — first 6 + last 4 only (C3)
- [ ] Remove debug Track 2 log statements in AtmTransactionManager (C5)
- [ ] Sanitize TX/RX message logging in AtmHostConnection (C4)
- [ ] Remove card data from reversal SharedPreferences storage (C6)

### 1.3 Fix TLS certificate validation
- [ ] Replace trustAllCerts with proper certificate validation (C1)
- [ ] Add development mode flag for testing with self-signed certs
- [ ] Production mode MUST validate certificates

---

## Phase 2: Extract Components from MainActivity (Architecture)
**Priority**: HIGH — Makes all future work easier
**Estimated effort**: 2 days

The current MainActivity.java is 10,783 lines with 9 inner/sibling classes. Break it into clean components:

### 2.1 Extract inner classes to their own files
- [ ] `Converter.java` — hex/byte conversion utilities
- [ ] `MyUtility.java` → `NavigationManager.java` — page switching, thread sleep
- [ ] `TLVUtility.java` — TLV parsing for contactless
- [ ] `TLVUtility_CT.java` — TLV parsing for contact
- [ ] `MyEMVEvent.java` → `EmvContactCallback.java` — contact chip EMV callbacks
- [ ] `MyEMVSPEvent.java` → `EmvPinCallback.java` — PIN entry callbacks
- [ ] `MyEMVCLSPEvent.java` → `EmvContactlessCallback.java` — contactless callbacks
- [ ] `MyManualEntryEvent.java` → `ManualEntryCallback.java`
- [ ] `Debugger.java` — debug helper (keep or remove)

### 2.2 Extract MainActivity responsibilities
- [ ] `SdkInitializer.java` — Castle SDK initialization (EMV, EMVCL, MSR, Printer, KMS2)
- [ ] `CardDetectionManager.java` — Multi-reader polling loop (contact/contactless/MSR)
- [ ] `TransactionOrchestrator.java` — Transaction flow coordination
- [ ] `PrinterManager.java` — Receipt printing (extract CTOS_Printer inner class)
- [ ] `KioskManager.java` — Kiosk mode setup (setDefaultApp, setNavigation, etc.)

### 2.3 Clean up GlobalPara
- [ ] `TransactionState.java` — Mutable transaction data (volatile/synchronized)
- [ ] `AppConfig.java` — Static configuration (fees, limits, processor settings)
- [ ] Keep GlobalPara as thin facade if needed for backward compat

---

## Phase 3: Implement Triton Protocol Layer
**Priority**: CORE — The main objective
**Estimated effort**: 3 days

### 3.1 Create Triton protocol classes (new)
- [ ] `TritonProtocol.java` — Constants, control chars, FID codes
- [ ] `TritonMessageBuilder.java` — Build all Triton message types
- [ ] `TritonMessageParser.java` — Parse all Triton response types
- [ ] `TritonHandshake.java` — ENQ/ACK handshake before request

### 3.2 Triton message types to implement

| Triton Code | Purpose | Hyosung Equivalent |
|------------|---------|-------------------|
| 00 | Withdrawal (checking) | Type 85 + CWCACA |
| 10 | Withdrawal (savings) | Type 85 + CWSASA |
| 30 | Balance inquiry (checking) | Type 85 + BISASA |
| 31 | Balance inquiry (savings) | Type 85 + BISASA |
| 29 | Reversal | Type 86 |
| 50/51 | Host Totals | Type 87 |
| 60 | Configuration Download | Type 88 |

### 3.3 Triton FID (Field ID) support for Miscellaneous fields
- [ ] FID `ud` — EMV tagged data block
- [ ] FID `uh` — EMV untagged data block
- [ ] FID `!` — Surcharge amount (from Config Response)
- [ ] FID `~` — PIN Working Key (from Config Response)
- [ ] FID `w` — Max withdrawal amount
- [ ] FID `h` — Heartbeat interval
- [ ] FID `t` — Time synchronization
- [ ] FID `d` — Programmable messages (welcome/marketing/exit)
- [ ] FID `c` — Day close time
- [ ] FID `p` — Receipt text (from Transaction Response)
- [ ] FID `b` — Available balance
- [ ] FID `k` — Issuer fee/credit
- [ ] FID `n` — Reversal reason code
- [ ] FID `S` — DUKPT KSN
- [ ] FID `#` — 12-digit sequence number
- [ ] FID `e` — Extended amount (12-digit)

### 3.4 Create protocol abstraction interface
- [ ] `AtmProtocol.java` (interface)
  ```java
  public interface AtmProtocol {
      byte[] buildTransactionRequest(TransactionRequest request);
      TransactionResponse parseTransactionResponse(byte[] message);
      byte[] buildReversalRequest(ReversalRequest request);
      byte[] buildHostTotalsRequest(...);
      byte[] buildConfigDownloadRequest(...);
      byte[] buildAck();
      byte[] buildNak();
      byte calculateLrc(byte[] data);
  }
  ```
- [ ] `TritonProtocolImpl.java` — Triton Standard implementation
- [ ] `HyosungProtocolImpl.java` — Wrap existing Hyosung code (keep as option)

### 3.5 Update ProcessorConfig
- [ ] Add `ProtocolType` enum (TRITON_STANDARD, HYOSUNG_STD1)
- [ ] Remove framing variants (Triton is always STX/ETX/LRC)
- [ ] Add Triton-specific fields (Communications Identifier, Terminal Identifier type)
- [ ] Update factory methods (forDns, forCds, etc.) with protocol type

---

## Phase 4: Update Host Connection Layer
**Priority**: HIGH
**Estimated effort**: 1 day

### 4.1 Update AtmHostConnection
- [ ] Add ENQ/ACK handshake support (Triton requires ENQ before request)
- [ ] Fix stream race condition (W6) — synchronize or use local references
- [ ] Fix socket close thread accumulation (W7) — use single executor
- [ ] Propagate handshake errors (W9)
- [ ] Protocol-agnostic send/receive (works with both Triton and Hyosung)

### 4.2 Update AtmTransactionManager
- [ ] Use AtmProtocol interface instead of direct HyosungMessageBuilder
- [ ] Fix transactionInProgress race condition (W2) — use AtomicBoolean
- [ ] Fix sequence number wrap race (W3) — use getAndUpdate
- [ ] Ensure heartbeat scheduler shutdown in onDestroy (W11)

---

## Phase 5: Update Transaction Flow Integration
**Priority**: HIGH
**Estimated effort**: 1 day

### 5.1 Map Castle SDK data to Triton fields
- [ ] Track 2 → Triton Track 2 field (same format — no sentinels)
- [ ] PIN Block → Triton PIN Block field (same — ANSI X9.8)
- [ ] EMV Tags → FID 'ud' in Miscellaneous field (TLV encoded)
- [ ] Amount → Amount 1 (8 numeric, cents, zero-padded)
- [ ] Surcharge → Amount 2 (8 numeric, cents, zero-padded)
- [ ] DUKPT KSN → FID 'S' in Miscellaneous field

### 5.2 Map Triton response to app state
- [ ] Response Code (3 numeric, 000=approved) → display result
- [ ] Authorization Number (8 numeric) → receipt
- [ ] Transaction Date/Time (MMDDYY/HHMMSS) → receipt
- [ ] Balance (Amount 1) → display
- [ ] Surcharge (Amount 2) → receipt
- [ ] Working Key (FID '~' in Config Response) → key storage
- [ ] Receipt Text (FID 'p') → printer

### 5.3 Configuration Download (Code 60)
- [ ] Request config on startup
- [ ] Parse and apply: working key, surcharge, max withdrawal, heartbeat interval
- [ ] Store programmable messages (FID 'd') for welcome/marketing screens

---

## Phase 6: Fix Remaining Code Review Issues
**Priority**: MEDIUM
**Estimated effort**: 1 day

### 6.1 Thread safety
- [ ] Make GlobalPara/TransactionState fields volatile (W1)
- [ ] Add timeout to busy-wait loops (W8)

### 6.2 Admin improvements
- [ ] Force PIN change on first use (W4)
- [ ] Use SecureRandom for PIN salt (W5)
- [ ] Fix hashPin() fallback — fail instead of plaintext (S7)

### 6.3 Minor improvements
- [ ] Replace StringBuffer with StringBuilder (S2)
- [ ] Replace deprecated ProgressDialog (S8)
- [ ] Don't log any portion of clear PIN blocks (S1)

---

## Phase 7: Testing & Validation
**Priority**: HIGH
**Estimated effort**: 2 days

### 7.1 Unit tests
- [ ] `TritonMessageBuilderTest.java` — Build/verify all message types
- [ ] `TritonMessageParserTest.java` — Parse/verify all response types
- [ ] `TritonProtocolTest.java` — LRC calculation, ENQ/ACK handshake
- [ ] `TransactionStateTest.java` — Thread safety verification

### 7.2 Integration tests
- [ ] Build and deploy to emulator — verify no crashes
- [ ] Build and deploy to Castle terminal — verify SDK init
- [ ] Test card reading (all 3 methods) → verify Triton message output
- [ ] Test config download → verify key/surcharge/limit applied
- [ ] Test reversal flow
- [ ] Test host totals / batch close

### 7.3 Protocol verification
- [ ] Capture Triton message bytes and compare against spec
- [ ] Verify LRC calculation matches spec examples
- [ ] Test with MUX (if MUX supports Triton) or mock server

---

## Phase Summary

| Phase | Description | Effort | Dependencies |
|-------|------------|--------|-------------|
| 1 | Fix critical security issues | 1 day | None |
| 2 | Extract components from MainActivity | 2 days | None |
| 3 | Implement Triton protocol layer | 3 days | Phase 2 |
| 4 | Update host connection layer | 1 day | Phase 3 |
| 5 | Update transaction flow integration | 1 day | Phase 3, 4 |
| 6 | Fix remaining code review issues | 1 day | Phase 2 |
| 7 | Testing & validation | 2 days | All |

**Total estimated effort: ~11 days**

---

## File Changes Summary

### NEW Files (Triton Protocol)
```
atm/host/triton/
├── TritonProtocol.java          — Constants, FID codes, control chars
├── TritonMessageBuilder.java    — Build all message types
├── TritonMessageParser.java     — Parse all response types
├── TritonHandshake.java         — ENQ/ACK handshake
└── TritonFidCodes.java          — FID code registry
```

### NEW Files (Extracted from MainActivity)
```
castech/emvtxn/
├── SdkInitializer.java          — Castle SDK init
├── CardDetectionManager.java    — Multi-reader polling
├── TransactionOrchestrator.java — Flow coordination
├── PrinterManager.java          — Receipt printing
├── KioskManager.java            — Kiosk mode
├── NavigationManager.java       — Page switching
├── TransactionState.java        — Thread-safe transaction state
├── AppConfig.java               — Static configuration
├── callback/
│   ├── EmvContactCallback.java  — Chip card callbacks
│   ├── EmvPinCallback.java      — PIN entry callbacks
│   ├── EmvContactlessCallback.java — Contactless callbacks
│   └── ManualEntryCallback.java
└── util/
    ├── Converter.java           — Hex/byte conversion
    ├── TlvParser.java           — TLV parsing (merged)
    └── PanMasker.java           — PCI-compliant PAN masking
```

### MODIFIED Files
```
MainActivity.java               — Dramatically reduced (delegate to extracted classes)
GlobalPara.java                 — Thinned out (replaced by TransactionState + AppConfig)
AtmHostConnection.java          — Add ENQ/ACK, fix thread safety
AtmTransactionManager.java      — Use AtmProtocol interface, fix race conditions
ProcessorConfig.java            — Add ProtocolType, Triton configs
Fragment_page_admin_atm.java    — Fix PIN security
```

### KEPT AS-IS (No Changes)
```
Fragment_page_amount_selection.java  — UI unchanged
Fragment_page_main_menu.java         — UI unchanged
Fragment_page_receipt.java           — UI unchanged
ClessLed.java                       — Hardware control unchanged
ClsAudioInidcator.java             — Audio unchanged
LrcCalculator.java                  — LRC algorithm identical
PinBlockFormatter.java              — ANSI X9.8 unchanged
CastleCardData.java                 — Card data extraction unchanged
TransactionLog.java                 — Data model unchanged
TransactionLogManager.java          — SQLite unchanged
EmvTagEnhancer.java                 — EMV tag handling unchanged
All layout XML files                — UI unchanged
All drawable/resource files         — UI unchanged
Castle SDK JARs (27 files)          — Hardware SDK unchanged
```

### DEPRECATED (Keep but mark deprecated)
```
HyosungMessageBuilder.java     — Keep for backward compat
HyosungMessageParser.java      — Keep for backward compat  
HyosungProtocol.java           — Keep for backward compat
MessageFraming.java            — No longer needed (Triton = single framing)
```

---

## Architecture Diagram (After Refactor)

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│                   (Thin orchestrator ~2000 lines)                │
├──────────┬──────────┬──────────┬──────────┬────────────────────┤
│  SDK     │  Card    │  Trans   │  Printer │  Kiosk             │
│  Init    │  Detect  │  Orch    │  Mgr     │  Mgr               │
├──────────┴──────────┴──────────┴──────────┴────────────────────┤
│              Callback Layer (EMV/PIN/Contactless)               │
├────────────────────────────────────────────────────────────────┤
│                    AtmTransactionManager                        │
│                  (Protocol-agnostic flow)                        │
├────────────────────────────────────────────────────────────────┤
│                    AtmProtocol (Interface)                       │
│              ┌──────────────┬──────────────┐                    │
│              │   Triton     │   Hyosung    │                    │
│              │   Protocol   │   Protocol   │                    │
│              │   (PRIMARY)  │  (LEGACY)    │                    │
│              └──────────────┴──────────────┘                    │
├────────────────────────────────────────────────────────────────┤
│                    AtmHostConnection                            │
│              (TLS socket, ENQ/ACK, send/receive)                │
├────────────────────────────────────────────────────────────────┤
│                    Castle CTOS SDK (27 JARs)                    │
│         EMV | EMVCL | MSR | KMS2 | Printer | Settings          │
└────────────────────────────────────────────────────────────────┘
```

---

## Execution Order

We'll tackle this in commit-sized chunks:

1. **Phase 1** → Commit: "Fix critical PCI/security issues"
2. **Phase 2** → Commit per extraction: "Extract EmvContactCallback from MainActivity", etc.
3. **Phase 3** → Commit: "Implement Triton Standard protocol layer"
4. **Phase 4** → Commit: "Update host connection for Triton ENQ/ACK"
5. **Phase 5** → Commit: "Integrate Triton protocol with transaction flow"
6. **Phase 6** → Commit: "Fix thread safety and admin security"
7. **Phase 7** → Commit: "Add Triton protocol tests"
8. **Final** → Push to `triton-refactor` branch, test on terminal

---

*Created: April 4, 2026*
*Branch: triton-refactor*
*Repo: https://github.com/toddb-amt/Castle-Triton*
