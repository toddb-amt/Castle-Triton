# Cashless ATM Application - Testing Guide

## Overview
This document provides a comprehensive testing guide for the Cashless ATM application developed for the Castle S1F4 PRO terminal.

## Pre-Testing Setup

### 1. Build Environment Requirements
- **Android Studio**: Giraffe 2022.3.1 Patch 4 or later
- **Java Development Kit**: JDK 8 or later
- **Castle Android SDK**: All 25 JAR files included in project libs folder
- **Hardware**: Castle S1F4 PRO terminal

### 2. Build the Application

#### Step-by-Step Build Process:
```bash
# Navigate to project directory
cd "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4"

# Clean previous builds
./gradlew clean

# Build the application
./gradlew build

# Or build debug APK
./gradlew assembleDebug
```

#### Alternative: Build in Android Studio
1. Open Android Studio
2. File → Open → Select "Emvtxn-S1F4" folder
3. Wait for Gradle sync to complete
4. Build → Clean Project
5. Build → Rebuild Project
6. Run → Run 'app' (or Shift+F10)

### 3. Deploy to Terminal
- Connect S1F4 PRO terminal via USB
- Enable USB debugging on terminal
- Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Testing Phases

### Phase 1: Basic Navigation Testing

#### Test 1.1: Initial Launch
**Objective**: Verify application launches correctly
- [ ] Launch the application
- [ ] Verify idle/transaction screen appears
- [ ] Check for any crash or error messages

**Expected Result**: Application launches successfully to idle screen

#### Test 1.2: Navigate to Amount Selection
**Objective**: Verify navigation to amount selection screen
- [ ] From idle screen, navigate to amount selection (page index 1)
- [ ] Verify "Select Withdrawal Amount" title appears
- [ ] Verify 6 preset buttons are visible ($20, $40, $60, $100, $200, $500)
- [ ] Verify custom amount button is present
- [ ] Verify fee and total displays show $0.00 initially

**Expected Result**: Amount selection screen displays correctly with all UI elements

---

### Phase 2: Amount Selection Testing

#### Test 2.1: Preset Amount Buttons
**Objective**: Verify all preset amount buttons work correctly

**Test each preset button:**
- [ ] Tap $20 button
  - Selected amount should show: $20.00
  - Fee should show: $3.00 (default flat fee)
  - Total should show: $23.00
- [ ] Tap $40 button
  - Selected amount: $40.00, Fee: $3.00, Total: $43.00
- [ ] Tap $60 button
  - Selected amount: $60.00, Fee: $3.00, Total: $63.00
- [ ] Tap $100 button
  - Selected amount: $100.00, Fee: $3.00, Total: $103.00
- [ ] Tap $200 button
  - Selected amount: $200.00, Fee: $3.00, Total: $203.00
- [ ] Tap $500 button
  - Selected amount: $500.00, Fee: $3.00, Total: $503.00

**Expected Result**: Each button correctly updates the display with amount + fee

#### Test 2.2: Custom Amount Entry
**Objective**: Verify custom amount input works correctly

- [ ] Tap "Custom Amount" button
- [ ] Dialog should appear with title "Enter Custom Amount"
- [ ] Enter valid amount: 75.00
- [ ] Tap OK
- [ ] Verify display shows: Amount: $75.00, Fee: $3.00, Total: $78.00

**Test invalid inputs:**
- [ ] Enter non-numeric value (e.g., "abc")
  - Should show error dialog: "Invalid Amount"
- [ ] Enter amount below minimum (e.g., 10.00)
  - Should show error: "Minimum withdrawal amount is $20.00"
- [ ] Enter amount above maximum (e.g., 600.00)
  - Should show error: "Maximum withdrawal amount is $500.00"

**Expected Result**: Valid amounts are accepted, invalid amounts show appropriate error messages

#### Test 2.3: Continue Button
**Objective**: Verify continue button behavior

- [ ] With no amount selected, verify Continue button is disabled
- [ ] Select any amount
- [ ] Verify Continue button becomes enabled
- [ ] Tap Continue button
- [ ] Verify navigation to transaction page (card processing screen)

**Expected Result**: Continue button only works when amount is selected, navigates to transaction page

#### Test 2.4: Cancel Button
**Objective**: Verify cancel button returns to idle

- [ ] Select any amount
- [ ] Tap Cancel button
- [ ] Verify navigation back to idle screen
- [ ] Verify amount is reset to $0.00

**Expected Result**: Cancel button returns to idle and resets selection

---

### Phase 3: Transaction Processing Testing

#### Test 3.1: EMV Contact (Chip Card)
**Objective**: Verify chip card processing works in ATM mode

**Setup:**
- [ ] Select amount (e.g., $40)
- [ ] Tap Continue to reach transaction page
- [ ] Verify screen shows "Insert, Tap, or Swipe Card"
- [ ] Verify withdrawal amount is displayed

**Test:**
- [ ] Insert chip card into reader
- [ ] Wait for card detection
- [ ] Follow PIN entry prompts if required
- [ ] Wait for transaction completion
- [ ] Verify navigation to receipt page

**Expected Result**: Chip card is read, transaction processes, navigates to receipt

**Logcat Check:**
```
Tag: Fragment_page_transaction
Look for: "onTransactionComplete", "ATM Mode", card data logs
```

#### Test 3.2: EMV Contactless (Tap/NFC)
**Objective**: Verify contactless payment works in ATM mode

**Setup:**
- [ ] Return to amount selection
- [ ] Select amount (e.g., $60)
- [ ] Tap Continue

**Test:**
- [ ] Hold contactless card near terminal
- [ ] Wait for tap detection (should hear beep, see LED)
- [ ] Wait for transaction completion
- [ ] Verify navigation to receipt page

**Expected Result**: Contactless card is detected, transaction processes, navigates to receipt

**Audio/LED Indicators:**
- [ ] Verify beep sound on card detection
- [ ] Verify LED lights indicate card detected
- [ ] Verify success/failure LED indication

#### Test 3.3: MSR (Magnetic Stripe)
**Objective**: Verify magnetic stripe reading works in ATM mode

**Setup:**
- [ ] Return to amount selection
- [ ] Select amount (e.g., $100)
- [ ] Tap Continue

**Test:**
- [ ] Swipe magnetic stripe card through reader
- [ ] Wait for card detection
- [ ] Follow any prompts
- [ ] Wait for transaction completion
- [ ] Verify navigation to receipt page

**Expected Result**: Magnetic stripe is read, transaction processes, navigates to receipt

#### Test 3.4: Transaction Cancellation
**Objective**: Verify cancel works during card processing

**Setup:**
- [ ] Select amount
- [ ] Tap Continue to transaction page

**Test:**
- [ ] Before inserting card, tap Cancel button
- [ ] Verify navigation back to amount selection
- [ ] Verify amount is reset
- [ ] Select new amount and continue
- [ ] Insert card partway
- [ ] Tap Cancel during processing
- [ ] Verify transaction is cancelled
- [ ] Verify return to amount selection

**Expected Result**: Cancel button aborts transaction and returns to amount selection

---

### Phase 4: Receipt Screen Testing

#### Test 4.1: Successful Transaction Receipt
**Objective**: Verify receipt displays correctly for approved transaction

**Prerequisites**: Complete a successful transaction (any payment method)

**Verify Receipt Display:**
- [ ] Large green checkmark (✓) appears
- [ ] "Transaction Approved" message in green
- [ ] Withdrawal amount displays correctly
- [ ] Service fee displays correctly
- [ ] Total charged displays correctly
- [ ] Card type and last 4 digits show (e.g., "VISA ****1234")
- [ ] Date/time displays correctly
- [ ] Transaction ID displays (format: TXN[timestamp])

**Expected Result**: All transaction details display accurately

#### Test 4.2: Failed Transaction Receipt
**Objective**: Verify receipt displays correctly for declined transaction

**Prerequisites**: Use a test card that will decline (if available)

**Verify Receipt Display:**
- [ ] Large red X (✗) appears
- [ ] "Transaction Declined" message in red
- [ ] Transaction details still display
- [ ] Print Receipt button is available

**Expected Result**: Failure is clearly indicated with red indicators

#### Test 4.3: Print Receipt
**Objective**: Verify receipt printing works correctly

**Test:**
- [ ] From receipt screen, tap "Print Receipt" button
- [ ] Button should change to "Printing..."
- [ ] Wait for print operation to complete
- [ ] Button should change to "Receipt Printed ✓"
- [ ] Verify physical receipt prints from terminal

**Verify Printed Receipt Contains:**
- [ ] Header: "================================"
- [ ] Title: "ATM RECEIPT" (NOT "CASHLESS ATM RECEIPT")
- [ ] Transaction status: "STATUS: APPROVED" or "STATUS: DECLINED"
- [ ] Withdrawal amount with label
- [ ] Service fee with label
- [ ] Separator line
- [ ] Total charged with label
- [ ] Card information (masked)
- [ ] Date/time
- [ ] Transaction ID
- [ ] Footer: "Thank you for using our ATM Service"

**Expected Result**: Receipt prints correctly with all information formatted properly

**If Print Fails:**
- [ ] Button shows "Print Failed - Try Again"
- [ ] Button re-enables for retry
- [ ] Check printer paper/status

#### Test 4.4: New Transaction
**Objective**: Verify "New Transaction" button works

**Test:**
- [ ] From receipt screen, tap "New Transaction" button
- [ ] Verify navigation to amount selection page
- [ ] Verify previous transaction data is cleared
- [ ] Verify amount display reset to $0.00

**Expected Result**: Returns to amount selection with clean state

#### Test 4.5: Done Button
**Objective**: Verify "Done" button returns to idle

**Test:**
- [ ] From receipt screen, tap "Done" button
- [ ] Verify navigation to idle screen
- [ ] Verify transaction data is cleared

**Expected Result**: Returns to idle screen

#### Test 4.6: Auto-Timeout
**Objective**: Verify 30-second timeout works

**Test:**
- [ ] Complete a transaction to reach receipt screen
- [ ] Do not tap any buttons
- [ ] Wait 30 seconds
- [ ] Verify automatic navigation to idle screen
- [ ] Verify transaction data is cleared

**Expected Result**: After 30 seconds of inactivity, returns to idle automatically

---

### Phase 5: Admin Settings Testing

#### Test 5.1: Access Admin Screen
**Objective**: Verify admin screen can be accessed

**Test:**
- [ ] Navigate to page index 4 (settings page)
- [ ] Verify "ATM Administration" title appears
- [ ] Verify all sections are visible:
  - Fee Configuration
  - Withdrawal Limits
  - Terminal Information
  - Diagnostics

**Expected Result**: Admin screen loads with all sections visible

#### Test 5.2: Fee Configuration - Flat Fee
**Objective**: Verify flat fee configuration works

**Test:**
- [ ] Ensure "Flat Fee" radio button is selected
- [ ] Verify flat fee input field is visible
- [ ] Change flat fee to $5.00
- [ ] Verify percentage fee input is hidden
- [ ] Tap "Save Settings"
- [ ] Verify success message: "Settings saved successfully"
- [ ] Navigate to amount selection
- [ ] Select $100
- [ ] Verify fee shows $5.00 (new flat fee)
- [ ] Verify total shows $105.00

**Expected Result**: Flat fee updates correctly and applies to transactions

#### Test 5.3: Fee Configuration - Percentage Fee
**Objective**: Verify percentage fee configuration works

**Test:**
- [ ] Return to admin screen
- [ ] Select "Percentage Fee" radio button
- [ ] Verify percentage fee input becomes visible
- [ ] Verify flat fee input becomes hidden
- [ ] Enter percentage: 2.5 (for 2.5%)
- [ ] Tap "Save Settings"
- [ ] Navigate to amount selection
- [ ] Select $100
- [ ] Verify fee shows $2.50 (2.5% of $100)
- [ ] Verify total shows $102.50
- [ ] Test with $200
- [ ] Verify fee shows $5.00 (2.5% of $200)
- [ ] Verify total shows $205.00

**Expected Result**: Percentage fee calculates correctly based on amount

#### Test 5.4: Fee Validation
**Objective**: Verify fee input validation

**Test negative fee:**
- [ ] Set flat fee to -5.00
- [ ] Tap Save Settings
- [ ] Verify error: "Flat fee cannot be negative"

**Test invalid percentage:**
- [ ] Switch to percentage fee
- [ ] Enter -10
- [ ] Tap Save Settings
- [ ] Verify error: "Percentage fee must be between 0 and 100"
- [ ] Enter 150
- [ ] Tap Save Settings
- [ ] Verify error: "Percentage fee must be between 0 and 100"

**Expected Result**: Invalid fee values are rejected with clear error messages

#### Test 5.5: Withdrawal Limits
**Objective**: Verify withdrawal limit configuration

**Test:**
- [ ] Set minimum amount to $10.00
- [ ] Set maximum amount to $300.00
- [ ] Tap "Save Settings"
- [ ] Verify success message
- [ ] Navigate to amount selection
- [ ] Try to select $500 (exceeds new max)
- [ ] Verify error: "Maximum withdrawal amount is $300.00"
- [ ] Try custom amount: $5.00 (below new min)
- [ ] Verify error: "Minimum withdrawal amount is $10.00"
- [ ] Select $200 (within limits)
- [ ] Verify selection works correctly

**Expected Result**: Withdrawal limits are enforced correctly

#### Test 5.6: Limit Validation
**Objective**: Verify limit input validation

**Test negative minimum:**
- [ ] Set minimum amount to -10
- [ ] Tap Save Settings
- [ ] Verify error: "Minimum amount cannot be negative"

**Test max < min:**
- [ ] Set minimum to $100
- [ ] Set maximum to $50
- [ ] Tap Save Settings
- [ ] Verify error: "Maximum amount must be greater than minimum amount"

**Expected Result**: Invalid limits are rejected

#### Test 5.7: Printer Diagnostic
**Objective**: Verify printer test functionality

**Test:**
- [ ] In admin screen, tap "Test Printer" button
- [ ] Button should change to "Testing..."
- [ ] Wait for test to complete
- [ ] Button should change to "Test Printer ✓"
- [ ] Verify test receipt prints with:
  - "PRINTER TEST" header
  - "This is a test print."
  - "If you can read this, your printer is working!"
- [ ] After 2 seconds, button text should reset to "Test Printer"

**If printer fails:**
- [ ] Button shows "Test Failed"
- [ ] Check printer connectivity
- [ ] Check paper loaded
- [ ] Check printer power

**Expected Result**: Printer test prints successfully

#### Test 5.8: Card Reader Diagnostic
**Objective**: Verify card reader test functionality

**Note**: Current implementation is simulated

**Test:**
- [ ] Tap "Test Card Reader" button
- [ ] Button should change to "Insert Card..."
- [ ] After 2 seconds, button should change to "Card Reader OK ✓"
- [ ] Toast message: "Card reader test simulated"
- [ ] After 2 more seconds, button resets to "Test Card Reader"

**Expected Result**: Simulated test completes successfully

**Future Enhancement**: Implement actual card reader test

#### Test 5.9: Terminal Information
**Objective**: Verify terminal info displays

**Test:**
- [ ] Check Terminal Information section
- [ ] Verify displays:
  - Terminal model
  - Version number
  - Serial number

**Note**: Currently hardcoded. Future enhancement: read from actual hardware

**Expected Result**: Terminal info is visible and readable

#### Test 5.10: Exit Admin
**Objective**: Verify exit button returns to idle

**Test:**
- [ ] Tap "Exit Admin" button
- [ ] Verify navigation to idle screen

**Expected Result**: Returns to idle screen

---

### Phase 6: End-to-End Flow Testing

#### Test 6.1: Complete Flat Fee Transaction Flow
**Objective**: Test complete user journey with flat fee

**Test:**
1. [ ] Start from idle screen
2. [ ] Navigate to amount selection
3. [ ] Select $100
4. [ ] Verify display: Amount: $100.00, Fee: $3.00, Total: $103.00
5. [ ] Tap Continue
6. [ ] Insert chip card
7. [ ] Enter PIN if prompted
8. [ ] Wait for approval
9. [ ] Verify receipt shows:
   - Green checkmark
   - "Transaction Approved"
   - Withdrawal: $100.00
   - Fee: $3.00
   - Total: $103.00
10. [ ] Tap Print Receipt
11. [ ] Verify receipt prints correctly
12. [ ] Tap Done
13. [ ] Verify return to idle

**Expected Result**: Complete flow works seamlessly from start to finish

#### Test 6.2: Complete Percentage Fee Transaction Flow
**Objective**: Test complete user journey with percentage fee

**Test:**
1. [ ] Navigate to admin screen
2. [ ] Select "Percentage Fee"
3. [ ] Set percentage to 3.0%
4. [ ] Save settings
5. [ ] Navigate to amount selection
6. [ ] Select $200
7. [ ] Verify display: Amount: $200.00, Fee: $6.00, Total: $206.00
8. [ ] Continue and complete transaction
9. [ ] Verify receipt shows correct fee ($6.00)
10. [ ] Print receipt
11. [ ] Verify printed receipt has $6.00 fee
12. [ ] Tap "New Transaction"
13. [ ] Select $100
14. [ ] Verify fee is now $3.00 (3% of $100)

**Expected Result**: Percentage fee calculates dynamically based on amount

#### Test 6.3: Contactless Transaction with Custom Amount
**Objective**: Test contactless with custom amount entry

**Test:**
1. [ ] Start from idle
2. [ ] Navigate to amount selection
3. [ ] Tap "Custom Amount"
4. [ ] Enter $85.00
5. [ ] Tap OK
6. [ ] Verify total with fee
7. [ ] Tap Continue
8. [ ] Tap contactless card
9. [ ] Wait for approval
10. [ ] Verify receipt displays $85.00 withdrawal
11. [ ] Test print function
12. [ ] Return to idle

**Expected Result**: Custom amount + contactless works correctly

#### Test 6.4: Transaction Cancellation and Retry
**Objective**: Test cancel and retry flow

**Test:**
1. [ ] Select amount
2. [ ] Tap Continue
3. [ ] Before inserting card, tap Cancel
4. [ ] Verify return to amount selection
5. [ ] Select different amount
6. [ ] Tap Continue
7. [ ] Insert card
8. [ ] Complete transaction
9. [ ] Verify receipt shows new amount (not cancelled amount)

**Expected Result**: Cancel properly resets state, retry works with new amount

#### Test 6.5: Multiple Consecutive Transactions
**Objective**: Test app stability with multiple transactions

**Test:**
1. [ ] Complete transaction #1 ($20)
2. [ ] From receipt, tap "New Transaction"
3. [ ] Complete transaction #2 ($40)
4. [ ] Tap "New Transaction"
5. [ ] Complete transaction #3 ($60)
6. [ ] Verify each transaction is independent
7. [ ] Verify no data bleed between transactions
8. [ ] Check logcat for memory leaks or errors

**Expected Result**: Multiple transactions work without issues

---

### Phase 7: LED and Audio Feedback Testing

#### Test 7.1: LED Indicators During Card Reading
**Objective**: Verify LED lights work correctly

**Test Contact Card:**
- [ ] Insert chip card
- [ ] Verify LED lights indicate card detected
- [ ] Observe LED during processing
- [ ] Verify LED indicates success/failure

**Test Contactless Card:**
- [ ] Tap card
- [ ] Verify LED indicates contactless detection
- [ ] Observe LED behavior during processing

**Expected Result**: LED indicators provide visual feedback at each stage

#### Test 7.2: Audio Feedback
**Objective**: Verify beeps work correctly

**Test:**
- [ ] Tap contactless card
- [ ] Verify beep on detection
- [ ] Listen for success/failure beep
- [ ] Test with different payment methods
- [ ] Verify appropriate audio for each action

**Expected Result**: Audio feedback helps guide user through transaction

---

### Phase 8: Error Handling Testing

#### Test 8.1: Declined Transaction
**Objective**: Verify app handles declined transactions gracefully

**Test:**
- [ ] Use a test card that will decline
- [ ] Complete transaction flow
- [ ] Verify receipt shows "Transaction Declined"
- [ ] Verify red X indicator
- [ ] Verify print receipt still works
- [ ] Tap "New Transaction"
- [ ] Verify can start new transaction

**Expected Result**: Declined transaction is handled gracefully, user can retry

#### Test 8.2: Card Removed Early
**Objective**: Test behavior when card is removed during processing

**Test:**
- [ ] Insert chip card
- [ ] Remove card before processing completes
- [ ] Verify error message
- [ ] Verify can retry transaction

**Expected Result**: Error is handled, user is prompted to retry

#### Test 8.3: Printer Out of Paper
**Objective**: Verify print error handling

**Test:**
- [ ] Remove printer paper
- [ ] Complete transaction
- [ ] Try to print receipt
- [ ] Verify error message
- [ ] Add paper back
- [ ] Retry print
- [ ] Verify receipt prints successfully

**Expected Result**: Print errors are detected and user can retry

#### Test 8.4: Network/Backend Failure
**Objective**: Test behavior when backend unavailable

**Note**: This depends on backend integration implementation

**Test:**
- [ ] Disable network/backend connection
- [ ] Attempt transaction
- [ ] Verify appropriate error message
- [ ] Verify user can cancel and retry

**Expected Result**: Network errors are handled gracefully

---

### Phase 9: Performance Testing

#### Test 9.1: Transaction Speed
**Objective**: Measure transaction processing time

**Test:**
- [ ] Start timer when card is inserted
- [ ] Complete transaction
- [ ] Record time until receipt appears
- [ ] Repeat with different payment methods

**Target Times:**
- Contact (Chip): < 10 seconds
- Contactless: < 5 seconds
- MSR: < 3 seconds

**Expected Result**: Transactions complete within acceptable timeframes

#### Test 9.2: UI Responsiveness
**Objective**: Verify UI remains responsive

**Test:**
- [ ] Rapidly tap buttons
- [ ] Switch between screens quickly
- [ ] Verify no lag or freezing
- [ ] Check buttons respond immediately

**Expected Result**: UI is smooth and responsive

#### Test 9.3: Memory Usage
**Objective**: Monitor app memory consumption

**Test:**
- [ ] Monitor memory using Android Studio Profiler
- [ ] Complete 10 consecutive transactions
- [ ] Check for memory leaks
- [ ] Verify memory usage is stable

**Expected Result**: No memory leaks, stable memory usage

---

### Phase 10: Security Testing

#### Test 10.1: PIN Pad Security
**Objective**: Verify secure PIN entry

**Test:**
- [ ] Use card requiring PIN
- [ ] Enter PIN
- [ ] Verify PIN is not displayed on screen
- [ ] Verify PIN is not logged
- [ ] Check logcat for any sensitive data exposure

**Expected Result**: PIN entry is secure, no data exposure

#### Test 10.2: Card Data Handling
**Objective**: Verify card data is handled securely

**Test:**
- [ ] Complete transaction
- [ ] Check logcat output
- [ ] Verify full PAN is not logged
- [ ] Verify only last 4 digits are stored/displayed
- [ ] Check receipt for proper masking

**Expected Result**: Card data is properly masked and secured

#### Test 10.3: Admin Access
**Objective**: Verify admin screen security

**Note**: Current implementation has no password protection

**Test:**
- [ ] Access admin screen from main navigation
- [ ] Verify anyone can access settings

**Future Enhancement**: Add password protection for admin access

**Expected Result**: Admin accessible (password protection planned for future)

---

## Issue Tracking Template

### Issue Report Format:
```
Issue ID: [AUTO-INCREMENT]
Date: [DATE]
Tester: [NAME]
Phase: [PHASE NUMBER]
Test: [TEST ID]

Description:
[Detailed description of issue]

Steps to Reproduce:
1. [Step 1]
2. [Step 2]
3. ...

Expected Result:
[What should happen]

Actual Result:
[What actually happened]

Severity: [Critical / High / Medium / Low]
Priority: [P0 / P1 / P2 / P3]

Logcat Output:
[Relevant logcat output]

Screenshots:
[Attach screenshots if applicable]
```

---

## Known Limitations

### Current Known Items:
1. **Admin Password Protection**: Not yet implemented
   - Admin screen accessible without authentication
   - Planned for future enhancement

2. **Settings Persistence**: Not saved to disk
   - Settings reset on app restart
   - Recommendation: Implement SharedPreferences storage

3. **Terminal Info**: Currently hardcoded
   - Does not read actual hardware info
   - Recommendation: Integrate with CTOS API to read serial/version

4. **Card Reader Test**: Simulated only
   - Test button shows simulated result
   - Recommendation: Implement actual hardware test

5. **No Transaction History**: Not implemented
   - No log of past transactions
   - Recommendation: Add transaction history screen

6. **No Merchant Configuration**: Hardcoded values
   - Merchant name, location not configurable
   - Recommendation: Add merchant setup in admin

---

## Testing Completion Checklist

### Pre-Deployment Sign-Off:
- [ ] All Phase 1 tests passed (Basic Navigation)
- [ ] All Phase 2 tests passed (Amount Selection)
- [ ] All Phase 3 tests passed (Transaction Processing)
- [ ] All Phase 4 tests passed (Receipt Screen)
- [ ] All Phase 5 tests passed (Admin Settings)
- [ ] All Phase 6 tests passed (End-to-End Flows)
- [ ] All Phase 7 tests passed (LED/Audio Feedback)
- [ ] All Phase 8 tests passed (Error Handling)
- [ ] All Phase 9 tests passed (Performance)
- [ ] All Phase 10 tests passed (Security)

### Additional Requirements:
- [ ] No critical or high-severity bugs outstanding
- [ ] All EMV certification tests completed
- [ ] PCI-DSS compliance verified
- [ ] User acceptance testing completed
- [ ] Documentation complete and reviewed
- [ ] Training materials prepared
- [ ] Deployment plan approved

---

## Post-Testing Recommendations

### High Priority Enhancements:
1. **Implement Settings Persistence**
   - Use SharedPreferences to save admin configuration
   - Prevents settings loss on app restart

2. **Add Admin Password Protection**
   - Require PIN or password to access admin screen
   - Prevent unauthorized fee/limit changes

3. **Transaction History**
   - Store recent transactions (last 100)
   - Allow review and reprint of receipts

4. **Network Integration**
   - Connect to backend for transaction processing
   - Real-time balance checks
   - Transaction logging to server

5. **Enhanced Error Messages**
   - More specific decline reasons
   - Network troubleshooting guidance
   - User-friendly error descriptions

### Medium Priority Enhancements:
1. **Hardware Info Integration**
   - Read actual terminal serial number
   - Display firmware version
   - Battery level indicator

2. **Receipt Customization**
   - Merchant name/logo configuration
   - Custom footer messages
   - QR code for receipt lookup

3. **Multi-Language Support**
   - English/Spanish toggle
   - Localized currency formats
   - Translated error messages

4. **Accessibility Features**
   - Larger text options
   - High contrast mode
   - Audio prompts for vision-impaired users

---

## Support Information

### Troubleshooting Resources:
- **SDK Documentation**: `/Android SDK/Documentation/`
- **Debug Guide**: "How to enable additional EMV EMVCL debug.txt"
- **Contactless Guide**: "Contactless Quick Reference Guide"

### Contact Information:
- **Castle Technical Support**: [Contact details TBD]
- **Project Lead**: [Name TBD]
- **Developer**: [Name TBD]

---

## Testing Log

### Testing Session Template:
```
Session Date: [DATE]
Tester: [NAME]
Terminal Serial: [SERIAL]
App Version: [VERSION]
Firmware Version: [VERSION]

Tests Completed:
- [List of tests completed]

Issues Found:
- [Issue ID] - [Brief description]

Notes:
[Any additional observations]
```

---

## Appendix A: Logcat Filters

### Useful Logcat Filters:
```bash
# ATM-specific logs
adb logcat | grep -E "(Fragment_page_amount|Fragment_page_receipt|Fragment_page_admin)"

# Transaction logs
adb logcat | grep "Fragment_page_transaction"

# EMV logs
adb logcat | grep "EMV"

# Printer logs
adb logcat | grep "CTOS_Printer"

# All app logs
adb logcat | grep "castech.emvtxn"
```

### Enable Verbose EMV Logging:
Refer to "How to enable additional EMV EMVCL debug.txt" in SDK documentation

---

## Appendix B: Test Cards

### Recommended Test Card Set:
1. **Visa Contact (Chip)** - Test EMV contact transactions
2. **Mastercard Contactless** - Test NFC/tap transactions
3. **Amex MSR** - Test magnetic stripe transactions
4. **Declined Test Card** - Test declined transaction handling
5. **PIN Required Card** - Test PIN entry flow

### Test Card Configuration:
- Configure test amounts for specific results
- Set up decline scenarios
- Test offline PIN vs online PIN

---

**End of Testing Guide**

*Last Updated: [Date will be added during actual testing]*
*Document Version: 1.0*
