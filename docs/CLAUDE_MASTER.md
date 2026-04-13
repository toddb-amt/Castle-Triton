# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Building applications for Castle POS terminals (S1F4 PRO) - specialized Android-based payment terminals with EMV capabilities. The primary development project is a Cashless ATM application built on top of Castle's Emvtxn-S1F4 sample code.

**Target Hardware**: Castle S1F4 PRO Terminal (Android-based)
**Language**: Java
**Development Environment**: Android Studio (Giraffe 2022.3.1 Patch 4+)
**Main Project**: `Android SDK/Sample Code/Emvtxn-S1F4/`

## Build Commands

### Required Configuration
- **Gradle Version**: 8.5 (⚠️ NOT 9.0-milestone-1)
- **Android Gradle Plugin**: 7.4.2
- **Build Tools**: 30.0.3
- **Compile/Target SDK**: 31
- **Min SDK**: 24

### Build & Deploy
```bash
# Navigate to project
cd "Android SDK/Sample Code/Emvtxn-S1F4"

# Clean build
./gradlew clean build

# Debug APK
./gradlew assembleDebug
```

### Deploy to Castle Terminal (Loader Tool - NOT ADB)

⚠️ **CRITICAL**: Castle S1F4 terminals do NOT use ADB for deployment. You MUST use the **Loader tool** via serial port. NEVER attempt `adb install` for Castle terminals.

**Step 1: Build the APK**
```bash
cd "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4"
./gradlew clean assembleDebug
```

**Step 2: Package APK into CAP file using CAPGen**
```bash
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin

# Use positional arguments (the -a/-o syntax does NOT work)
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/debug" \
  app-debug.apk 1 0
```
This creates:
- `app/build/outputs/apk/output/debug.CAP` - the packaged app
- `app/build/outputs/apk/output/debug.mci` - manifest file for Loader

**Step 3: Upload to terminal using Loader**
```bash
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin

# Find serial port
ls /dev/tty.usbmodem*

# IMPORTANT: Loader can't handle paths with spaces!
# Copy files to /tmp first:
cp "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.CAP" /tmp/
cp "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.mci" /tmp/

# Upload using piped interactive input (command line args don't work reliably)
printf '/dev/tty.usbmodem144201\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

**Terminal Preparation:**
- Terminal must be in download mode (Settings → System → Download Mode, or via menu)
- User may need to delete existing app on terminal before loading new version
- Serial port name varies - always check with `ls /dev/tty.usbmodem*`

**File Loading Order:**
1. Config file (UC-13764) - enables debug mode
2. Debug app (Emvtxn_Debug.CAP)

⚠️ **IMPORTANT**: Always use the **signed** config file from `config/signed/config.CAP`. The unsigned version fails with "Decap_unsuccessfully" error on terminals.

**Config Files Location:**
```
/Users/mbroadbent/Documents/Castle/ZIPZ/config/signed/config.CAP   ← USE THIS
/Users/mbroadbent/Documents/Castle/ZIPZ/config/unsigned/config.CAP ← DO NOT USE
```

**Troubleshooting:**
- If Loader times out, retry - sometimes takes 2-3 attempts
- If app won't load, delete it from terminal first, then retry
- The Loader uses the .mci file which references the .CAP file in same directory
- If config fails with "Decap_unsuccessfully", ensure you're using the signed version

### Deploy to Emulator Only (ADB)
```bash
# Only for emulator testing - NOT for Castle terminal
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use Android Studio
# Run → Run 'app'
```

### Critical Build Requirements

**1. Multidex MUST be enabled** (23+ SDK JARs exceed DEX limit):
```gradle
defaultConfig {
    multiDexEnabled true
}
dependencies {
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

**2. Memory configuration** (gradle.properties):
```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

**3. Java 8 compatibility** (app/build.gradle):
```gradle
compileOptions {
    sourceCompatibility JavaVersion.VERSION_1_8
    targetCompatibility JavaVersion.VERSION_1_8
}
```

**4. Package conflict resolution**:
```gradle
packagingOptions {
    resources {
        excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE', ...]
    }
}
```

## Architecture

### High-Level Pattern
- **Architecture**: Fragment-based Android app with TabLayout + ViewPager navigation
- **Entry Point**: Single activity (`MainActivity.java` - 5,908 lines)
- **State Management**: Global singleton pattern via `GlobalPara.java`
- **Threading**: Dedicated background thread (`threadTxn`) for EMV operations to keep UI responsive

### Core Components

**MainActivity.java** (Central Controller)
- SDK initialization (EMV, EMVCL, MSR, Printer, KMS2)
- Fragment lifecycle management via `SectionsPagerAdapter`
- Transaction orchestration (card detection → processing → completion)
- Hardware abstraction layer (Printer, Card Readers, LED, Audio)
- Background thread management for blocking EMV operations

**Fragment Navigation Structure**:
```
Main Menu (Fragment_page_main_menu.java - 67 lines)
    ↓
Amount Selection (Fragment_page_amount_selection.java - 225 lines) - ATM-specific
    ↓
Transaction Processing (Fragment_page_transaction.java - 462 lines)
    ↓
Receipt Display (Fragment_page_receipt.java - 371 lines) - ATM-specific
    ↓
Admin Config (Fragment_page_admin_atm.java - 299 lines) - ATM-specific
```

Other fragments:
- `Fragment_page_setting.java` (486 lines) - Technical EMV settings
- `Fragment_page_pinpad_ex.java` (43 lines) - PIN pad example
- `Fragment_page_manualentry.java` (41 lines) - Manual card entry

**Global State Classes**:
- `GlobalPara.java` (83 lines) - Singleton holding shared state (amount, fees, transaction data, hardware refs)
- `GlobalDef.java` (21 lines) - Navigation and entry mode constants

### Card Detection Pattern

The app simultaneously polls all three payment methods in a loop. First detection wins:

```java
do {
    // Priority 1: Contactless (best UX)
    if (isCLAvailable && emvcl.performTransactionEx() != PENDING) {
        entryMode = ENTRY_MODE_CL;
        break;
    }

    // Priority 2: MSR
    if (isMSRAvailable && msr.readTracks() == SUCCESS) {
        entryMode = ENTRY_MODE_MSR;
        break;
    }

    // Priority 3: Contact (chip)
    if (isCTAvailable && (sc.getStatus() & 0x01) == 0x01) {
        entryMode = ENTRY_MODE_CT;
        break;
    }
} while (true);
```

### Hardware Integration

**EMV Contact (Chip Cards)**:
- SDK: `CTOS.CtEMV_0.0.76.jar` (197 KB)
- Flow: Insertion → App selection → PIN → Online auth
- Classes: `CtEMV`, `EMVTxnData`, `EMVCandidateList`

**EMV Contactless (NFC/Tap)**:
- SDK: `CTOS.CtEMVCL_1.0.48.jar` (135 KB)
- LED control: `ClessLed.java` (357 lines) manages 4 LED indicators
- Classes: `CtEMVCL`, `EMVCLRcDataEx`, `EMVCLUIReqData`

**Magnetic Stripe Reader**:
- SDK: `CTOS.CtReader_0.0.31.jar` (74 KB)
- Classes: `CtEMVMSR`, `EMVMSREncryptedTracks`

**Thermal Printer**:
- SDK: `CTOS.CtPrint_0.0.22.jar` (582 KB - largest JAR)
- Integration: `MainActivity.CTOS_Printer` inner class
- Methods: `printf(String)` for text printing, `goprintf()` to flush
- Note: Reference as `MainActivity.CTOS_Printer` (inner class, not importable)

**Audio Feedback**:
- Class: `ClsAudioInidcator.java` (72 lines)
- Tones: OK, Alert, Cancel
- Assets: `res/raw/ok_tone.wav`, `alert_tone.wav`, `cancel_key_tone.wav`

**LED Indicators**:
- Class: `ClessLed.java` (357 lines)
- Modes: Normal (multi-color), Europe (all green)
- Patterns: Idle, detecting, success, failure

### Castle SDK Integration

**Total JAR Files**: 23 files in `app/libs/` directory

**Core Libraries**:
- EMV processing: `CtEMV`, `CtEMVCL`, `CtReader`
- Hardware: `CtPrint`, `CtSystem`
- Security: `CtKMS2`, `CtCrypto`, `CtCertificate`, `CtTR34`
- Configuration: `CtSettings`, `CtLoader`

**EMV Configuration**: `app/src/main/assets/bin.json` (Terminal config, app config, CAPK keys)

**SDK Initialization Pattern** (in MainActivity.onCreate):
```java
emv = new CtEMV(this);
emv.setGlobalEventListener(emvEventListener);

emvcl = new CtEMVCL(this);
emvcl.setCLEventListener(emvclEventListener);

msr = new CtEMVMSR(this);
Printer = new CTOS_Printer(this);
GlobalPara.clLED = new ClessLed(...);
GlobalPara.audio = new ClsAudioInidcator(...);
```

## Cashless ATM Application

**Status**: ✅ Development Complete - Ready for Testing (October 2025)

### Architecture Overview
Built on top of Emvtxn-S1F4 sample code by:
- Reusing existing EMV infrastructure (no reinvention)
- Adding 3 new fragments for ATM-specific UI
- Modifying transaction fragment for ATM mode detection
- Adding global state parameters for ATM configuration

### Key Files Created
- `Fragment_page_amount_selection.java` + XML layout (225 lines)
- `Fragment_page_receipt.java` + XML layout (371 lines)
- `Fragment_page_admin_atm.java` + XML layout (299 lines)

### Key Files Modified
- `MainActivity.java` - Added `navigateToPage()`, `getPrinter()`, ATM flow integration
- `GlobalPara.java` - Added 11 ATM-specific parameters (fees, limits)
- `GlobalDef.java` - Added page navigation constants
- `Fragment_page_transaction.java` - ATM mode detection and auto-start

### ATM Features
- Amount selection (preset $20-$500 + custom entry)
- Fee calculation (flat $3 or percentage-based)
- Withdrawal limits (configurable min/max)
- All three payment methods (Contact, Contactless, MSR)
- Thermal receipt printing
- LED and audio feedback
- Auto-timeout protection (30 seconds on receipt screen)
- Admin configuration screen (fee settings, limits, printer test)

### User Flow
```
Main Menu → Amount Selection → Card Processing → Receipt → (Done/New Transaction)
                                       ↓
                                   Admin Config (hidden)
```

### Documentation
- **Development Log**: `CASHLESS_ATM_DEVELOPMENT.md` (1,867 lines) - Complete development journal
- **Testing Guide**: `ATM_TESTING_GUIDE.md` (976 lines) - 10-phase testing plan with 50+ test cases

## Testing & Deployment

### Testing Strategy
Documented in `ATM_TESTING_GUIDE.md` with 10 phases:
1. Basic Navigation (UI flow)
2. Amount Selection (validation, calculation)
3. Transaction Processing (Contact/Contactless/MSR)
4. Receipt Screen (display, printing)
5. Admin Settings (configuration)
6. End-to-End Flows (complete scenarios)
7. LED/Audio Feedback (hardware)
8. Error Handling (edge cases)
9. Performance (speed, memory)
10. Security (PIN, card data)

**Estimated Testing Time**: 15-22 hours

### Prerequisites
- Castle S1F4 PRO terminal with USB debugging enabled
- USB connection to development machine
- Test cards (chip, contactless, magnetic stripe)
- Printer paper loaded

### Deployment
```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use Android Studio
Run → Run 'app'
```

### Known Limitations
- Settings not persisted to disk (reset on restart)
- Admin screen has no password protection
- Terminal info hardcoded (not read from hardware)
- No transaction history/logging
- Card reader test is simulated

## Porting to Other Terminals

To port this app to other Castle terminals (SATURN1000, SATURN7000, etc.):

1. Replace SDK JAR files in `app/libs/` with terminal-specific versions from:
   - SATURN1000: `CTOS_SDK_Installed/SATURN1000/libs/`
   - SATURN7000: `CTOS_SDK_Installed/SATURN7000/libs/`

2. In Android Studio:
   ```
   Build → Clean Project
   File → Invalidate Caches & Restart
   Build → Rebuild Project
   ```

3. Test on target hardware

4. May require terminal-specific adjustments to SDK API calls

## Network Connectivity

### Castle SDK Network APIs

**CtSettings Class** - WiFi & Ethernet Configuration:
```java
// WiFi
openWifi()
closeWifi()
setDhcpWifi(String ssid, String password, int type)
setStaticWifi(String ssid, String password, int type, String ip, String netmask, String gateway, String dns1, String dns2)
getWifiConfig()

// Ethernet
setEth(int enable)  // 0=disable, 1=enable
setDHCPEth(String proxy)
setStaticEth(String proxy, String ip, String netmask, String gateway, String dns1, String dns2)
getEthConfig()
```

**CtCradle Class** - Cradle Network Management:
```java
connect(int connectType)  // 0=wifi, 1=usb
getEthernetInfo()  // Returns EthernetConfiguration object
setEthernetInfo(EthernetConfiguration config)
close()
```

### Application-Level Networking

The Castle SDK provides **network configuration only**, not HTTP/REST APIs. For backend communication:

1. Add network permissions to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

2. Use standard Android/Java networking:
   - Standard Java Sockets: `java.net.Socket` (see `CTOS_SDK_Installed/SATURN7000/examples/KMS2sample/ClientThread.java`)
   - OkHttp (recommended): `com.squareup.okhttp3:okhttp:4.12.0`
   - Retrofit (for REST APIs): `com.squareup.retrofit2:retrofit:2.9.0`

3. Add dependencies to `app/build.gradle`:
```gradle
dependencies {
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

**Note**: Current ATM application has NO network code - it's completely offline.

## Common Build Issues

### Issue: "Cannot find symbol: module()"
**Cause**: Using Gradle 9.0-milestone-1 (unstable)
**Solution**: Downgrade to Gradle 8.5 in `gradle/wrapper/gradle-wrapper.properties`

### Issue: DEX NullPointerException (37+ errors)
**Cause**: Missing multidex support or insufficient memory
**Solution**:
- Enable `multiDexEnabled true` in `defaultConfig`
- Add `implementation 'androidx.multidex:multidex:2.0.1'`
- Increase JVM memory to 2048m in gradle.properties
- Disable R8 optimizer

### Issue: Duplicate META-INF files
**Cause**: Multiple JAR files with conflicting metadata
**Solution**: Add `packagingOptions` with excludes list (see build.gradle)

### Issue: Printer class not found
**Cause**: `CTOS_Printer` is an inner class of `MainActivity`
**Solution**: Reference as `MainActivity.CTOS_Printer` (not importable)

## Security & Compliance

### PCI-DSS Requirements for Production
- EMV certification testing required
- PCI-DSS compliance validation mandatory
- Secure PIN entry (handled by SDK)
- Card data encryption (handled by SDK)
- No plaintext PAN storage
- Security audit trail

### Current Security Features
- PIN never exposed to application layer
- Track data encrypted at swipe point
- Certificate-based authentication
- Dynamic data authentication (chip)
- Masked PAN display (last 4 digits only)

### Security Gaps (Address for Production)
- No admin password protection
- Settings not encrypted in memory
- No transaction log audit trail
- Network communication not yet implemented

## Debug Information

For troubleshooting EMV transactions:
- Enable EMV or EMVCL debug mode
- Capture logcat in verbose mode: `adb logcat -v time > debug.log`
- Reference: "How to enable additional EMV EMVCL debug.txt"
- Contactless issues: Check "Contactless Quick Reference Guide"

### ATM Crash Debug Findings
**Document**: `ATM_DEBUG_FINDINGS.md` (in Castle root folder)

Contains detailed findings from debugging transaction crashes:
- Crash pattern: First entry works, re-entry after cancel crashes
- Theories: SDK state cleanup, thread interference, native memory corruption
- Code changes made and next steps to investigate
- Test sequence for reproduction

### SDK Transaction Flow Analysis
**Document**: `SDK_TRANSACTION_FLOW_ANALYSIS.md` (in Castle root folder)

Comprehensive comparison of transaction patterns between:
- **CastleHost SampleApp** (RECOMMENDED): Singleton pattern, ThreadPool, proper init/cleanup
- **Original Emvtxn-S1F4**: Simple but less robust
- **Our Modified ATM Version**: Problems identified

Key findings:
- CastleHost uses `msr.flushTracksBuffer()` on each transaction init
- CastleHost re-initializes EMV with `emv.initialize()` per transaction
- CastleHost uses `emvcl.detectCard()` for polling, not `performTransactionEx()`
- CastleHost uses ThreadPoolManager instead of raw Thread creation
- CastleHost properly clears event handlers on destroy

## ATM Processor Integration

**Status**: 🚧 In Development (November 2025)

### Overview

Integrating with ATM processors (DNS, SwitchCommerce, EFX) using the **Hyosung/Nautilus STD1 protocol**. This replaces the need for CastlesHost TSYS Sierra SDK - we're building direct processor communication.

### Communication Flow

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│  Castle S1F4 PRO    │     │  Hyosung STD1       │     │  Nationwide ATM     │
│  Terminal           │ ──► │  Protocol           │ ──► │  Processors         │
│  (Android Hardware) │     │  (Message Format)   │     │  (DNS, EFX, etc.)   │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
         ↑                           ↑                           ↑
   Our hardware              Protocol reference            Target network
                             from ZIPZ/WINCE7
```

**Key Points:**
- **Hardware**: Castle S1F4 PRO (Android-based payment terminal)
- **Protocol**: Hyosung STD1 - the standard ATM message format expected by Nationwide processors
- **Reference**: `ZIPZ/WINCE7/` contains Hyosung ATM firmware (WinCE7 binaries) used as communication protocol reference
- **Target**: Nationwide ATM network processors

### Protocol Reference Files (ZIPZ/WINCE7)

The `ZIPZ/WINCE7/update7/Master7/` directory contains **Hyosung ATM firmware** for WinCE7 terminals. This is NOT source code, but serves as a **protocol reference** for understanding how Hyosung terminals communicate with Nationwide processors.

**Contents:**
| File Type | Examples | Purpose |
|-----------|----------|---------|
| EMV Kernels | `EmvL2Kernel.dll` - `EmvL2Kernel8.dll` | EMV L2 processing (binary) |
| PIN Module | `BSPin30.dll` (411KB) | PIN handling (binary) |
| Config Files | `CurrencyInfo4CE.ini` | Currency denominations |
| Card Parsing | `Card01.idc`, `CreditForm.idc` | Track data parsing patterns |

**Why This Matters:** Since Nationwide processors expect the Hyosung STD1 message format, we implement the same protocol on Castle hardware to ensure compatibility.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Cashless ATM Application                   │
├─────────────────────────────────────────────────────────────┤
│  Card Reading Layer (Castle EMV SDK - existing)             │
│  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌────────┐        │
│  │  CtEMV  │  │ CtEMVCL  │  │CtEMVMSR │  │CtKMS2  │        │
│  │ (Chip)  │  │(Tap/NFC) │  │ (Swipe) │  │ (PIN)  │        │
│  └─────────┘  └──────────┘  └─────────┘  └────────┘        │
├─────────────────────────────────────────────────────────────┤
│  ATM Host Communication Layer (NEW)                         │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ HyosungMessage  │  │ AtmHostConnection│                  │
│  │ Builder/Parser  │  │ (TCP/TLS)        │                  │
│  └─────────────────┘  └─────────────────┘                  │
├─────────────────────────────────────────────────────────────┤
│  Processor Endpoints                                        │
│  ┌─────┐  ┌───────────────┐  ┌─────┐                       │
│  │ DNS │  │ SwitchCommerce │  │ EFX │                       │
│  └─────┘  └───────────────┘  └─────┘                       │
└─────────────────────────────────────────────────────────────┘
```

### Hyosung STD1 Protocol

**Specification Document**: `docs/HYOSUNG_ATM_MESSAGE_SPECIFICATION.md`

#### Message Types
| Code | Type | Purpose |
|------|------|---------|
| 85 | Transaction | Withdrawal, Balance Inquiry |
| 86 | Reversal | Undo failed transactions |
| 87 | Host Totals | Settlement/reconciliation |
| 88 | Configuration | Key download, surcharge |
| 89 | Health Check | Keep-alive |

#### Message Framing
Two framing types depending on processor:

**Standard (STX/ETX)** - DNS, FIS, CDS:
```
┌─────┬─────────┬────┬─────────┬────┬─────┬─────────┬─────┬─────┐
│ STX │ Field 0 │ FS │ Field 1 │ FS │ ... │ Field N │ ETX │ LRC │
└─────┴─────────┴────┴─────────┴────┴─────┴─────────┴─────┴─────┘
```

**VISA (Length Header)** - SwitchCommerce, EFX, Cardtronics:
```
┌────────────────┬─────┬─────────┬────┬─────────┬────┬─────┬─────────┬─────┬─────┐
│ 2-byte Length  │ STX │ Field 0 │ FS │ Field 1 │ FS │ ... │ Field N │ ETX │ LRC │
└────────────────┴─────┴─────────┴────┴─────────┴────┴─────┴─────────┴─────┴─────┘
```

#### Control Characters
| Char | Hex | Description |
|------|-----|-------------|
| STX | 0x02 | Start of Text |
| ETX | 0x03 | End of Text |
| FS | 0x1C | Field Separator |
| ACK | 0x06 | Acknowledge |
| NAK | 0x15 | Negative Acknowledge |
| EOT | 0x04 | End of Transmission |

#### Transaction Request (Type 85) Fields
| Field | Name | Description |
|-------|------|-------------|
| 0 | Info Header | `H0.NNNNNN` |
| 1 | Terminal ID | 6-8 chars |
| 2 | Txn Code | `85` |
| 3 | Txn Type | `CWCACA`, `BISASA`, etc. |
| 4 | Sequence | `0001`-`9999` |
| 6 | Track 2 | Card data with sentinels |
| 8 | PIN Block | 16 hex chars (3DES encrypted) |
| 9 | Amount | Cents |
| 10 | Surcharge | Cents |
| 11 | Dispense | `1` or `0` |
| 12 | Status | Terminal status |
| 13 | EMV Data | TLV-encoded chip data |

#### Target Processors
| Processor | Framing | Port | TLS |
|-----------|---------|------|-----|
| DNS | Standard | 8002 | Yes |
| SwitchCommerce | VISA | 1440 | Yes |
| EFX | VISA | 9057 | Yes |

### Key Files (To Be Created)

**Package**: `castech.emvtxn.atm.host`

| File | Purpose |
|------|---------|
| `HyosungMessageBuilder.java` | Pack STD1 messages |
| `HyosungMessageParser.java` | Unpack STD1 messages |
| `AtmHostConnection.java` | TCP/TLS socket management |
| `PinBlockFormatter.java` | ISO 9564-1 Format 0 PIN blocks |
| `LrcCalculator.java` | LRC checksum calculation |
| `TransactionRequest.java` | Request data model |
| `TransactionResponse.java` | Response data model |
| `ProcessorConfig.java` | Processor-specific settings |

### Integration Points with Castle SDK

| Castle SDK | Used For | ATM Host Layer |
|------------|----------|----------------|
| `CtEMV` | Chip card reading | Track 2, EMV tags → Field 6, 13 |
| `CtEMVCL` | Contactless reading | Track 2, EMV tags → Field 6, 13 |
| `CtEMVMSR` | Swipe reading | Track 2 → Field 6 |
| `CtKMS2` | PIN encryption | PIN block → Field 8 |
| `CtCrypto` | Working key decryption | Key from Field 5/8 (Type 88) |

### Transaction Flow

```
1. Card Presented
   └─> Castle SDK reads card data (Track 2, EMV tags)

2. PIN Entry
   └─> Castle SDK encrypts PIN → PIN block

3. Build Request
   └─> HyosungMessageBuilder.buildTransactionRequest()
       - Field 6: Track 2 from card
       - Field 8: PIN block from KMS2
       - Field 13: EMV data from chip

4. Send to Processor
   └─> AtmHostConnection.send()
       - TLS connection
       - Apply framing (Standard or VISA)
       - Wait for response

5. Parse Response
   └─> HyosungMessageParser.parseTransactionResponse()
       - Check response code (Field 4)
       - Extract auth data (Field 5)

6. Complete Transaction
   └─> Display result, print receipt
```

### CastlesHost SDK (Reference Only)

Located in `CastlesHost_*` directories. **NOT USED** for our integration - these are for TSYS Sierra processor only. Kept for reference:
- `CastlesHost_SampleApp_TSYS-Sierra-14C/` - Sample code
- `CastlesHost_TSYS_Sierra_14C_Release_*/` - SDK + docs

## Key Management

### Key Loading Formats

**Use 3DES format, NOT TR-31.**

TR-31 format cannot be used due to Key Injection Tool limitation. See `KEY_LOADING_ISSUE.md` for full details.

**Summary:**
- Key Injection Tool V2.01 sets attribute 0x00000010 (Data Decryption)
- TR-31 unwrapping requires attribute 0x00000020 (KBPK)
- Tool has no option to set KBPK attribute
- Error 0x00002909 occurs when attempting TR-31 unwrap

**Working Solution:**
- Server sends working key encrypted with 3DES under TMK
- App decrypts using standard decryption API (works with 0x00000010 attribute)
- Store decrypted key at working key location (C001/00A1)

### Key Locations
| Location | Purpose |
|----------|---------|
| CFFF/0000 | TMK/KEK (injected via Key Injection Tool) |
| C001/00A1 | Working key for PIN encryption |

## Additional Resources

- **API Reference**: `Castles Android API Reference Manual v5.0.docx` (converted to `/tmp/castles_api.txt`)
- **SDK Examples**: `CTOS_SDK_Installed/SATURN7000/examples/` (8 sample projects)
- **Development Log**: `CASHLESS_ATM_DEVELOPMENT.md` (complete task history)
- **Testing Guide**: `ATM_TESTING_GUIDE.md` (50+ test cases)
- **ATM Protocol Spec**: `docs/HYOSUNG_ATM_MESSAGE_SPECIFICATION.md` (Hyosung STD1 protocol)
- **Protocol Reference**: `ZIPZ/WINCE7/` (Hyosung ATM firmware - communication protocol reference for Nationwide)
- **Key Loading Issue**: `KEY_LOADING_ISSUE.md` (TR-31 limitation documentation)
- **PIN/Cryptogram Issue**: `Android SDK/Sample Code/Emvtxn-S1F4/PIN_CRYPTOGRAM_ISSUE.md` (BLOCKING - SDK 0x1003 error preventing cryptogram generation)
