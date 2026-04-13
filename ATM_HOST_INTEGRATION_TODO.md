# ATM Host Integration - Development TODO

This document tracks the remaining development tasks for completing the ATM host integration on the Castle S1F4 PRO terminal.

## Completed Work

### Phase 1: Message Layer (21 files)
- [x] Hyosung STD1 protocol implementation
- [x] Message types 85, 86, 87, 88, 89
- [x] Standard framing (STX/ETX) for DNS
- [x] VISA framing (2-byte length) for Switch Commerce/EFX
- [x] Field definitions and builders
- [x] Message parser

### Phase 2: Host Communication Infrastructure
- [x] `AtmHostConnection.java` - TCP/TLS socket communication
- [x] `PinBlockFormatter.java` - ISO 9564-1 Format 0 PIN blocks
- [x] `CastleCardData.java` - Card data container
- [x] `CastleKeyManager.java` - PIN encryption key handling
- [x] `AtmTransactionManager.java` - Transaction orchestration
- [x] `AtmHostService.java` - Main entry point API

### Phase 3: MainActivity Integration
- [x] Added ATM host imports and member variables
- [x] Added `initializeAtmHostService()` method
- [x] Added `atmEventListener` implementation
- [x] Added `buildCardDataFromTransaction()` helper
- [x] Added `performAtmHostTransaction()` with sync wait
- [x] Replaced simulated online processing with real host calls
- [x] Added PIN block storage in `eventOnlinePinBlockGet()`
- [x] Added automatic host service init after SDK init
- [x] Updated `GlobalPara.java` with host configuration fields
- [x] Updated `CastleCardData.java` with entry mode constants

---

## TODO Items

### 1. Admin UI for Host Configuration
**Priority: HIGH** | **Status: COMPLETED**

Add host configuration fields to the admin screen (`Fragment_page_admin_atm.java`):

- [x] Add "Host Settings" section header
- [x] Add Host Address text input field
- [x] Add Port Number text input field
- [x] Add Terminal ID text input field
- [x] Add Processor Type dropdown (DNS / Switch Commerce / EFX)
- [x] Add "Save Settings" button (integrated with existing)
- [x] Add "Test Connection" button
- [x] Add "Download Keys" button
- [x] Wire up fields to GlobalPara values
- [x] Update corresponding layout XML

**Files modified:**
- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
- `app/src/main/res/layout/fragment_page_admin_atm.xml`
- `app/src/main/java/castech/emvtxn/MainActivity.java` (added getAtmHostService(), made initializeAtmHostService() public)

---

### 2. Key Download Integration
**Priority: HIGH** | **Status: COMPLETED**

Implement working key download functionality:

- [x] Add "Download Keys" button in admin screen
- [x] Wire button to call `atmHostService.downloadKeys()`
- [x] Display key download progress
- [x] Show status on success/failure
- [ ] Show KCV (Key Check Value) on success (future enhancement)
- [ ] Consider auto-download on first connection (future enhancement)

**Files modified:**
- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
- `app/src/main/res/layout/fragment_page_admin_atm.xml`

---

### 3. MSR Track Data Capture
**Priority: HIGH** | **Status: COMPLETED**

Ensure MSR swipe data is captured for ATM transactions:

- [x] Locate MSR data handling in MainActivity
- [x] Verify track 2 data is captured during swipe
- [x] Store track 2 in `GlobalPara.atmTrack2Data`
- [x] Store last 4 digits for receipt display
- [x] Update `buildCardDataFromTransaction()` to use GlobalPara fallback

**Files modified:**
- `app/src/main/java/castech/emvtxn/MainActivity.java`
  - Added encrypted track 2 storage in MSR handling (around line 3587)
  - Added last 4 digits capture for receipt (around line 3544)
  - Updated `buildCardDataFromTransaction()` to fallback to `GlobalPara.atmTrack2Data`

---

### 4. Receipt Enhancement
**Priority: MEDIUM** | **Status: COMPLETED**

Update receipt screen to show host response data:

- [x] Display authorization code (`GlobalPara.atmAuthCode`)
- [x] Display reference number (`GlobalPara.atmReferenceNumber`)
- [x] Display response message on decline
- [x] Update printed receipt to include these fields
- [x] Reset host response data when transaction ends

**Files modified:**
- `app/src/main/java/castech/emvtxn/Fragment_page_receipt.java`
  - Added UI component declarations for auth code, ref number, response message
  - Added `displayDeclineReason()` method
  - Added `displayHostResponseData()` method
  - Updated `printReceiptContent()` to include auth code, ref number, and decline reason
  - Updated `resetATMParameters()` to clear all host response fields
- `app/src/main/res/layout/fragment_page_receipt.xml`
  - Added auth code label and value TextViews (hidden by default)
  - Added reference number label and value TextViews (hidden by default)
  - Added response message TextView for decline reason

---

### 5. Error Handling UI
**Priority: MEDIUM** | **Status: COMPLETED**

Improve user feedback for error scenarios:

- [x] Display meaningful error messages on host failure
- [x] Show "Transaction Declined" with reason from host
- [x] Handle timeout gracefully with user feedback
- [x] Add retry option where appropriate (connection errors, timeouts)
- [x] Add LED/audio feedback for errors
- [x] Add success feedback (audio + status message)
- [x] Add response code descriptions (51 common codes)

**Files modified:**
- `app/src/main/java/castech/emvtxn/MainActivity.java`
  - Added `showAtmErrorDialog()` - generic error dialog with optional retry
  - Added `showAtmDeclinedDialog()` - transaction declined dialog with reason
  - Added `showAtmConnectionErrorDialog()` - connection error with retry
  - Added `showAtmTimeoutDialog()` - timeout error with retry
  - Added `playAtmErrorFeedback()` - plays alert sound + flashes LEDs
  - Added `playAtmSuccessFeedback()` - plays OK sound
  - Added `showAtmErrorLedPattern()` - flashes all LEDs 3 times
  - Added `updateAtmStatusMessage()` - updates transaction screen status
  - Added `getResponseCodeDescription()` - human-readable response codes
  - Updated `atmEventListener.onError()` - triggers error feedback
  - Updated `atmEventListener.onTransactionDeclined()` - triggers error feedback
  - Updated `atmEventListener.onTransactionApproved()` - triggers success feedback
  - Updated `performAtmHostTransaction()` timeout handling - triggers error feedback

---

### 6. Reversal Handling
**Priority: MEDIUM** | **Status: COMPLETED**

Implement transaction reversal functionality:

- [x] Add manual reversal option in admin screen
- [x] Persist pending reversals to survive app restart
- [x] Implement reversal queue processing on startup
- [x] Add reversal status display
- [x] Clear reversals option with confirmation dialog

**Files created:**
- `app/src/main/java/castech/emvtxn/atm/host/ReversalPersistenceManager.java`
  - `PendingReversal` inner class with JSON serialization
  - `CompletedReversal` inner class for history tracking
  - Store/load pending reversals via SharedPreferences
  - History of completed reversals (max 50)
  - Helper methods for status updates and transaction ID generation

**Files modified:**
- `app/src/main/java/castech/emvtxn/atm/host/AtmHostService.java`
  - Added `ReversalPersistenceManager` integration
  - Added `storePendingReversal()` method
  - Added `getPendingReversalCount()`, `hasPendingReversals()`, `getPendingReversals()`
  - Added `processPendingReversals()` with callback interface
  - Added `sendReversalSync()` for synchronous reversal processing
  - Added `ReversalProcessingCallback` interface

- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
  - Added reversal status display (`txvReversalStatus`)
  - Added process reversals button (`btnProcessReversals`)
  - Added clear reversals button (`btnClearReversals`, hidden by default)
  - Added `updateReversalStatus()`, `processReversals()`, `clearReversals()` methods
  - Long-press on process button reveals clear button

- `app/src/main/res/layout/fragment_page_admin_atm.xml`
  - Added "Reversal Management" section
  - Added pending reversals count display
  - Added process and clear buttons

---

### 7. Balance Inquiry Feature
**Priority: LOW** | **Status: COMPLETED**

Add balance inquiry functionality:

- [x] Add "Check Balance" option to amount selection screen
- [x] Create balance inquiry flow
- [x] Wire up `atmHostService.performBalanceInquiry()`
- [x] Create balance display dialog
- [x] Option to print balance receipt

**Files modified:**
- `app/src/main/java/castech/emvtxn/Fragment_page_amount_selection.java`
  - Added `btnCheckBalance` button reference
  - Added `startBalanceInquiry()` method to set balance inquiry mode and navigate to transaction
- `app/src/main/res/layout/fragment_page_amount_selection.xml`
  - Added "Check Balance" button (green, next to Custom Amount)
- `app/src/main/java/castech/emvtxn/GlobalPara.java`
  - Added `atmBalanceInquiryMode` flag
- `app/src/main/java/castech/emvtxn/MainActivity.java`
  - Added `performAtmBalanceInquiry()` method for synchronous balance inquiry
  - Added `showBalanceResultDialog()` to display account and available balance
  - Added `printBalanceReceipt()` to print balance on thermal printer
  - Updated contactless, contact chip, and MSR transaction flows to check for balance inquiry mode
  - Balance inquiry mode calls `atmHostService.performBalanceInquiry()` instead of withdrawal

---

### 8. Settings Persistence
**Priority: LOW** | **Status: COMPLETED**

Persist configuration across app restarts:

- [x] Save host configuration to EncryptedSharedPreferences
- [x] Save fee settings to SharedPreferences
- [x] Load settings on app startup
- [x] Encrypt sensitive data (host address, terminal ID, processor type)
- [x] Working keys already secured via Castle KMS2 hardware

**Files created:**
- `app/src/main/java/castech/emvtxn/AtmSettingsManager.java`
  - Uses `EncryptedSharedPreferences` (AES256-GCM) for sensitive host config
  - Uses regular `SharedPreferences` for fee settings
  - `loadSettings()` - loads all settings into GlobalPara
  - `saveSettings()` - persists all GlobalPara settings to storage
  - `saveHostSettings()` / `saveFeeSettings()` - partial saves
  - Fallback to regular prefs if encryption unavailable

**Files modified:**
- `app/build.gradle`
  - Added `androidx.security:security-crypto:1.0.0` dependency
- `app/src/main/java/castech/emvtxn/MainActivity.java`
  - Added `AtmSettingsManager` member variable
  - Added `initializeAtmSettingsManager()` method called before host service init
  - Added `getAtmSettingsManager()` accessor
- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
  - Added `persistSettingsToStorage()` method
  - Updated `saveSettings()` to persist to encrypted storage after validation

**Security Notes:**
- Host address, port, terminal ID, processor type → Encrypted storage (AES256-GCM)
- Fee settings, limits → Regular SharedPreferences (non-sensitive)
- Working keys → Castle KMS2 hardware secure storage (unchanged)

---

### 9. Transaction Logging
**Priority: LOW** | **Status: COMPLETED**

Implement transaction audit trail:

- [x] Create transaction log data model
- [x] Log all ATM transactions with timestamps
- [x] Store in SQLite database
- [x] Add "View Transaction History" in admin
- [ ] Consider log upload/export functionality (future enhancement)

**Files created:**
- `app/src/main/java/castech/emvtxn/atm/TransactionLog.java`
  - Data model with transaction types (WITHDRAWAL, BALANCE_INQUIRY, REVERSAL)
  - Result types (APPROVED, DECLINED, TIMEOUT, ERROR, CANCELLED)
  - Entry modes (CONTACT, CONTACTLESS, MSR)
  - Factory methods and formatting helpers

- `app/src/main/java/castech/emvtxn/atm/TransactionLogManager.java`
  - SQLite database storage (atm_transactions.db)
  - CRUD operations (save, update, query)
  - Query by date range, result type
  - Statistics: total count, approved/declined counts, total amount, fees collected
  - Old transaction cleanup support

**Files modified:**
- `app/src/main/java/castech/emvtxn/MainActivity.java`
  - Added TransactionLogManager initialization
  - Logs created at start of performAtmHostTransaction() and performAtmBalanceInquiry()
  - Log updated with results in atmEventListener callbacks (onTransactionApproved, onTransactionDeclined, onError, onBalanceReceived)
  - Added getTransactionLogManager() accessor

- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
  - Added Transaction History section with statistics display
  - View Transaction History button shows scrollable dialog with recent transactions
  - Print Summary option prints transaction statistics
  - Clear History option (hidden, shown on long-press)

- `app/src/main/res/layout/fragment_page_admin_atm.xml`
  - Added Transaction History section UI elements

---

### 10. Test Mode
**Priority: LOW** | **Status: NOT STARTED**

Add testing capability without real host:

- [ ] Add "Test Mode" toggle in admin screen
- [ ] Implement simulated host responses
- [ ] Configure test scenarios (approve, decline, timeout)
- [ ] Useful for development and demo purposes

**Files to modify:**
- `app/src/main/java/castech/emvtxn/Fragment_page_admin_atm.java`
- `app/src/main/java/castech/emvtxn/atm/host/AtmHostService.java`

---

## Architecture Notes

### Current Integration Flow

```
ATM Transaction Flow:
┌─────────────────┐
│ Amount Selection│
└────────┬────────┘
         │
┌────────▼────────┐
│ Card Detection  │ (Contact/Contactless/MSR)
└────────┬────────┘
         │
┌────────▼────────┐
│ PIN Entry       │ (if required)
└────────┬────────┘
         │
┌────────▼────────────────────────────────────┐
│ Online Processing                           │
│  ┌─────────────────────────────────────┐   │
│  │ buildCardDataFromTransaction()      │   │
│  │  - Collects track2, EMV, PIN block  │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │ performAtmHostTransaction()         │   │
│  │  - Calls atmHostService             │   │
│  │  - Waits for response (30s timeout) │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │ atmEventListener callbacks          │   │
│  │  - Updates GlobalPara with response │   │
│  │  - Signals completion               │   │
│  └─────────────────────────────────────┘   │
└────────┬────────────────────────────────────┘
         │
┌────────▼────────┐
│ Receipt Screen  │
└─────────────────┘
```

### Key Files

| File | Purpose |
|------|---------|
| `MainActivity.java` | Main transaction flow, ATM host integration |
| `GlobalPara.java` | Global state including ATM host config |
| `AtmHostService.java` | Entry point for host communication |
| `AtmTransactionManager.java` | Transaction orchestration |
| `AtmHostConnection.java` | TCP/TLS socket handling |
| `HyosungMessageBuilder.java` | Message construction |
| `HyosungMessageParser.java` | Response parsing |
| `CastleCardData.java` | Card data container |
| `CastleKeyManager.java` | Key management |

---

## Testing Checklist

Before deployment, test the following scenarios:

### Connection Tests
- [ ] Connect to DNS processor
- [ ] Connect to Switch Commerce processor
- [ ] Connect to EFX processor
- [ ] Handle connection timeout
- [ ] Handle connection refused

### Transaction Tests
- [ ] Contactless tap transaction
- [ ] Contact chip transaction
- [ ] MSR swipe transaction
- [ ] Transaction approval
- [ ] Transaction decline
- [ ] Transaction timeout
- [ ] Partial approval (if supported)

### Key Management Tests
- [ ] Initial key download
- [ ] Key sync after error
- [ ] Key check value verification

### Error Recovery Tests
- [ ] Network disconnect during transaction
- [ ] App crash recovery
- [ ] Reversal processing

---

*Last Updated: November 30, 2025*
*Items 1-9 COMPLETED*
