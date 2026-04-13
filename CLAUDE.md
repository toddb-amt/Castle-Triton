# Cashless ATM - Castle S1F4 PRO

Cashless ATM application for Castle S1F4 PRO payment terminals. Built on Castle's Emvtxn-S1F4 sample code, integrating EMV chip/contactless/swipe card reading with ATM processor communication via Hyosung STD1 protocol.

**Parent Documentation**: See `/Users/broadbent/Documents/TFI/Castle/CLAUDE.md` for comprehensive Castle SDK docs, deployment procedures, and protocol specifications.

## Tech Stack

| Layer | Technology | Version | Purpose |
|-------|------------|---------|---------|
| Platform | Android | SDK 31 | Target for Castle S1F4 PRO hardware |
| Language | Java | 1.8 | SDK compatibility requirement |
| Build | Gradle | 8.5 | Build automation (NOT 9.0) |
| AGP | Android Gradle Plugin | 7.4.2 | Android build toolchain |
| EMV | Castle CTOS SDK | 2.0.x | 27 JAR libraries for card/PIN/printing |
| Protocol | Hyosung STD1 | - | ATM processor message format |
| UI | AndroidX + Material | 1.5.0 | Fragment-based navigation |

## Quick Start

```bash
# Prerequisites
# - Android Studio Giraffe 2022.3.1+
# - Java 8+
# - Castle S1F4 PRO terminal (for deployment)

# Build debug APK
./gradlew assembleDebug

# Clean build
./gradlew clean build

# Run unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "castech.emvtxn.atm.host.HyosungProtocolTest"
```

**CRITICAL**: Deploy to terminal using CAPGen + Loader tool, NOT ADB. See `docs/TERMINAL_UPLOAD_GUIDE.md`.

## Project Structure

```
app/
├── build.gradle                    # App config (SDK 31, multidex, Java 8)
├── libs/                           # Castle SDK JARs (27 files)
│   ├── CTOS.CtEMV_2.0.80.jar      # Chip card processing
│   ├── CTOS.CtEMVCL_2.0.49.jar    # Contactless/NFC
│   ├── CTOS.CtReader_0.0.34.jar   # Magnetic stripe
│   ├── CTOS.CtKMS2_4.0.1.jar      # Key management/DUKPT
│   ├── CTOS.CtPrint_0.0.23.jar    # Thermal printer
│   └── ...
└── src/main/
    ├── java/castech/emvtxn/
    │   ├── MainActivity.java       # Central controller (~485KB, EMV callbacks)
    │   ├── GlobalPara.java         # Singleton state (ATM config, transaction data)
    │   ├── GlobalDef.java          # Page navigation constants
    │   ├── Fragment_page_*.java    # UI fragments (6 screens)
    │   ├── atm/                    # Transaction logging
    │   │   └── host/               # ATM host communication
    │   │       ├── AtmHostService.java
    │   │       ├── HyosungMessageBuilder.java
    │   │       ├── HyosungMessageParser.java
    │   │       ├── CastleKeyManager.java
    │   │       └── ...
    │   └── test/                   # Test utilities
    ├── res/                        # Layouts, strings, drawables
    └── assets/
        ├── emv_config.xml          # EMV terminal/CAPK configuration
        └── bin.json                # Terminal config
docs/
├── HYOSUNG_ATM_MESSAGE_SPECIFICATION.md  # STD1 protocol reference
├── DUKPT_KEY_INJECTION_PROCESS.md        # Key injection steps
├── TERMINAL_UPLOAD_GUIDE.md              # Loader deployment
└── ATM_TESTING_GUIDE.md                  # 10-phase test plan
```

## Architecture

### Entry Point
`MainActivity.java` (~10,000 lines) - Central controller handling SDK initialization, EMV callbacks, transaction orchestration.

### Fragment Navigation (ViewPager)

```
d_PAGE_IDLE (0)
    ↓
d_PAGE_MAIN_MENU (1) → d_PAGE_SETTING (5) [Admin]
    ↓
d_PAGE_AMOUNT_SELECTION (2)
    ↓
d_PAGE_TRANSACTION (3) ← Card read, PIN entry, host auth
    ↓
d_PAGE_RECEIPT (4)
```

### Key Packages

**`castech.emvtxn`** - UI and EMV integration
- `MainActivity.java` - EMV callbacks: `onGetPINNotify`, `eventOnlinePinBlockGet`, `onTxnResult`
- `GlobalPara.java` - Singleton state (transaction data, ATM config, hardware refs)
- `GlobalDef.java` - Page navigation constants
- `Fragment_page_*.java` - UI fragments

**`castech.emvtxn.atm.host`** - ATM processor communication
- `AtmHostService.java` - Orchestrates host communication
- `HyosungMessageBuilder.java` / `HyosungMessageParser.java` - STD1 protocol
- `CastleKeyManager.java` - PIN encryption key management
- `EmvTagEnhancer.java` - EMV tag ordering/override for processor compatibility
- `AtmTransactionManager.java` - Transaction lifecycle management
- `ProcessorConfig.java` - Processor-specific settings

### EMV Transaction Flow

```
1. Card detected → onTxnDataGet callback
2. App selection → onAppListEx callback
3. PIN required → onGetPINNotify (sets key location)
                → eventOnlinePinBlockGet (collects PIN)
4. GENERATE AC → Card produces cryptogram (9F26, 9F27, 9F36, 9F10)
5. Online auth → AtmHostService.processTransaction() sends to processor
6. Completion  → Receipt display, printer output
```

### Critical EMV Tags

| Tag | Name | Notes |
|-----|------|-------|
| 9F33 | Terminal Capabilities | E0F1C8 = Online PIN enabled |
| 9F34 | CVM Results | 420000 = Online PIN verified |
| 95 | TVR | Byte 3: 0x04 = PIN entered, 0x80 = CVM failed |
| 9F26 | Application Cryptogram | ARQC for online authorization |

## Code Conventions

### File Naming
- Java files: `PascalCase.java` (e.g., `MainActivity.java`, `GlobalPara.java`)
- Fragment files: `Fragment_page_*.java` with underscores (legacy pattern from sample code)
- Test files: `*Test.java` suffix

### Code Naming
- Classes: `PascalCase` (e.g., `AtmHostService`, `CastleKeyManager`)
- Methods/variables: `camelCase` (e.g., `processTransaction`, `atmSelectedAmount`)
- Constants: `SCREAMING_SNAKE` or `d_` prefix (e.g., `MAX_FAILED_ATTEMPTS`, `d_PAGE_IDLE`)
- Package: `castech.emvtxn` root, `castech.emvtxn.atm.host` for host layer

### Import Order
1. Android imports (`android.*`)
2. AndroidX imports (`androidx.*`)
3. Third-party imports (`com.google.*`, `CTOS.*`)
4. Project imports (`castech.emvtxn.*`)
5. Java imports (`java.*`)

## Testing

```bash
# All tests
./gradlew test

# Protocol tests only
./gradlew test --tests "*HyosungProtocol*"

# EMV tag tests
./gradlew test --tests "*EmvTagEnhancer*"

# With detailed output
./gradlew test --info
```

**Unit tests** in `app/src/test/java/castech/emvtxn/`:

| Test File | Purpose |
|-----------|---------|
| `HyosungProtocolTest.java` | Message building/parsing |
| `EmvTagEnhancerTest.java` | EMV tag ordering for processors |
| `CAPKDataTest.java` | CAPK configuration parsing |
| `ConfigComparisonTest.java` | Config validation |

**Manual testing**: See `docs/ATM_TESTING_GUIDE.md` for 10-phase test plan.

## RESOLVED: PIN Key Attribute Issue

**Full documentation**: `PIN_CRYPTOGRAM_ISSUE.md`

### Solution (v6.0-PERF - February 2026)
SDK's internal PIN flow fails with 0x1003 due to key attribute mismatch. **Workaround implemented:**

1. **Skip SDK PIN callback** - Return immediately from `eventOnlinePinBlockGet()` in ATM mode
2. **MVP DUKPT approach** - Collect PIN post-transaction using `CtKMS2Dukpt` directly
3. **Performance fix** - PIN pad appears in ~1 second (was 30 seconds)

### Current Flow

| Step | Action | Result |
|------|--------|--------|
| 1 | Card detected | PAN displayed |
| 2 | SDK PIN callback | Skipped (return -1) |
| 3 | MVP DUKPT PIN | PIN pad appears immediately |
| 4 | Host authorization | PIN block + EMV data sent |

### Key Configuration
```java
// GlobalPara.java - DUKPT key location
public static final int atmDukptKeySet = 0x0000C000;
public static final int atmDukptKeyIndex = 0x00000000;
```

**Status**: ✅ RESOLVED - Working with MVP DUKPT approach

## Deployment

**Castle terminals do NOT use ADB.** Must use CAPGen + Loader via serial port.

### Build & Package
```bash
# 1. Build APK
./gradlew clean assembleDebug

# 2. Package into CAP file
cd /path/to/CTOS_SDK_Installed/CAPTools/bin
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/path/to/app/build/outputs/apk/debug" app-debug.apk 1 0
```

### Upload to Terminal
```bash
# 3. Copy to temp (Loader can't handle spaces in paths)
cp .../output/debug.CAP /tmp/
cp .../output/debug.mci /tmp/

# 4. Upload via serial
printf '/dev/tty.usbmodem144201\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

See `docs/TERMINAL_UPLOAD_GUIDE.md` for detailed steps.

## Configuration

### Build Requirements (app/build.gradle)
- `compileSdkVersion 31` / `targetSdkVersion 31`
- `minSdkVersion 24`
- `multiDexEnabled true` (required for 27 SDK JARs)
- Java 8 compatibility

### Memory Settings (gradle.properties)
```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

### ATM Host Settings (GlobalPara.java)
```java
atmProcessorType = "DNS";       // DNS, SWITCH_COMMERCE, EFX, etc.
atmHostAddress = "";            // Processor endpoint
atmHostPort = 8002;             // Default DNS port
atmTerminalId = "";             // Terminal identifier
atmUseTls = true;               // Use TLS/SSL
atmDukptKeySet = 0x0000C000;    // DUKPT key location
```

## Additional Resources

| Document | Purpose |
|----------|---------|
| `docs/HYOSUNG_ATM_MESSAGE_SPECIFICATION.md` | STD1 protocol reference |
| `docs/DUKPT_KEY_INJECTION_PROCESS.md` | Key injection steps |
| `docs/TERMINAL_UPLOAD_GUIDE.md` | Loader deployment guide |
| `docs/ATM_TESTING_GUIDE.md` | 10-phase manual test plan |
| `docs/CASHLESS_ATM_DEVELOPMENT.md` | Development journal |
| `docs/SDK_TRANSACTION_FLOW_ANALYSIS.md` | EMV SDK analysis |
| `PIN_CRYPTOGRAM_ISSUE.md` | Current blocking issue |
| `ATM_HOST_INTEGRATION_TODO.md` | Remaining integration tasks |

## Key Locations (Terminal)

| Location | Purpose | Status |
|----------|---------|--------|
| C000/0000 | DUKPT key for PIN encryption | Has PIN attribute, used for manual PIN |
| C001/0000 | Secondary DUKPT key | Backup |
| CFFF/0000 | TMK/KEK (Transport Master Key) | For key injection |

## Development Patterns

### Singleton State
All transaction state is managed through `GlobalPara` singleton. Call `GlobalPara.resetATMTransactionState()` before starting new transactions.

### Background Threading
All EMV operations MUST run on `threadTxn` to avoid ANR. Never call EMV SDK methods from UI thread.

### Fragment Communication
Navigate between screens using `GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_*)`.

### Error Handling
- Log all errors with class TAG prefix
- Display user messages via `AlertDialog`
- Store reversal data for failed transactions via `ReversalPersistenceManager`


## Skill Usage Guide

When working on tasks involving these technologies, invoke the corresponding skill:

| Skill | Invoke When |
|-------|-------------|
| gradle | Configures build system, dependency resolution, and APK/CAP packaging |
| android | Manages Android SDK integration, fragment navigation, and lifecycle management |
| emv | Integrates EMV chip card processing, contactless NFC, and magnetic stripe reading |
| hyosung-protocol | Implements Hyosung STD1 message framing, TLV encoding, and ATM processor communication |
| java | Handles Java 8 syntax, EMV SDK integration, and thread management patterns |
| android-fragments | Manages Fragment-based UI navigation with ViewPager and TabLayout patterns |
| dukpt | Manages DUKPT key derivation, PIN encryption, and key injection procedures |
| castle-sdk | Integrates Castle CTOS SDK libraries for EMV, printer, and hardware control |
| androidx | Implements AndroidX components, Material Design, and backward compatibility |
| tls-networking | Configures TLS/SSL connections, socket management, and processor endpoint communication |
| android-testing | Writes unit tests with JUnit and implements EMV/protocol test cases |
