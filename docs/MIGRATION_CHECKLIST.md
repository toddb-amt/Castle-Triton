# Castle Project Migration Checklist

This document describes everything needed to move the Castle Cashless ATM project to a new computer.

## Quick Summary

The entire `/Users/mbroadbent/Documents/Castle/` folder (~4.5GB) should be copied to the new machine. The git repository only contains the application source code, not the SDK, tools, firmware, or documentation.

---

## 1. What to Copy

### Required - Copy Entire Folder
```
/Users/mbroadbent/Documents/Castle/
```

| Folder/File | Size | Purpose |
|-------------|------|---------|
| `Android SDK/Sample Code/Emvtxn-S1F4/` | ~50MB | **Main project (git repo)** |
| `CTOS_SDK_Installed/` | ~200MB | Castle SDK, CAPTools, Loader |
| `docs/` | ~100KB | Protocol specs, sample code |
| `ZIPZ/` | ~2.5GB | SDKs, firmware zips, config files |
| `Firmware/` | ~1GB | S1F4 PRO firmware |
| `*.md` files (20 files) | ~300KB | Development documentation |
| `CastlesHost_*` folders | ~100MB | TSYS Sierra SDK (reference only) |

### Git Repository Location
```
/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/
```
- Remote: `https://github.com/toddb-amt/Castle.git`
- Branch: `main`

---

## 2. Documentation Files (Not in Git)

These 20 markdown files are in the Castle root folder and contain critical development notes:

| File | Purpose |
|------|---------|
| `CLAUDE.md` | **Master documentation** - SDK reference, build commands, architecture |
| `ATM_TESTING_GUIDE.md` | 50+ test cases for QA |
| `CASHLESS_ATM_DEVELOPMENT.md` | Complete development journal |
| `HYOSUNG_ATM_MESSAGE_SPECIFICATION.md` | STD1 protocol spec (in docs/) |
| `KEY_INJECTION_TOOL_GUIDE.md` | DUKPT key injection procedures |
| `DUKPT_KEY_INJECTION_PROCESS.md` | Step-by-step key ceremony |
| `DUKPT_KEY_CEREMONY.md` | Key ceremony details |
| `TERMINAL_UPLOAD_GUIDE.md` | CAPGen + Loader deployment |
| `PIN_CRYPTOGRAM_ISSUE.md` | Current blocking issue documentation |
| `SDK_TRANSACTION_FLOW_ANALYSIS.md` | EMV flow comparison |
| `ATM_DEBUG_FINDINGS.md` | Crash debugging notes |
| `EMV_PIN_ERROR_0x00001003_FIX.md` | PIN error investigation |
| `EMV_CRYPTOGRAM_MISSING_SOLUTION.md` | Cryptogram generation issue |
| `KEY_LOADING_ISSUE.md` | TR-31 vs 3DES key format |
| `PIN_KEY_ATTRIBUTE_ISSUE.md` | Key attribute problem |
| `AID_PRIORITY_FIX.md` | Card application selection |
| `CASTLE_SUPPORT_TICKET_0x1003.md` | Support escalation |
| `CATM-Workorder-001.md` | Work order details |
| `SESSION_RESUME*.md` | Session context for Claude |

---

## 3. SDK & Tools

### CTOS SDK Installation
```
/Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/
├── CAPTools/bin/          # CAPGen and Loader tools
│   ├── CAPGen             # Packages APK into CAP file
│   └── Loader             # Uploads to terminal via serial
├── SATURN1000/libs/       # SDK JARs for SATURN1000
└── SATURN7000/libs/       # SDK JARs for SATURN7000 (reference)
```

### Config Files (for Terminal Debug Mode)
```
/Users/mbroadbent/Documents/Castle/ZIPZ/config/signed/config.CAP  ← USE THIS
/Users/mbroadbent/Documents/Castle/ZIPZ/config/unsigned/          ← DO NOT USE
```
**Important**: Always use the signed config file. Unsigned fails with "Decap_unsuccessfully".

---

## 4. New Machine Setup

### Prerequisites
1. **macOS** (tested on Darwin 24.6.0)
2. **Android Studio** Giraffe 2022.3.1 Patch 4 or later
3. **Java 8** (for Castle SDK compatibility)
4. **USB drivers** for Castle terminal serial port

### Android SDK Path
The project expects Android SDK at:
```
/Users/<username>/Library/Android/sdk
```

Update `local.properties` after copying:
```properties
sdk.dir=/Users/<NEW_USERNAME>/Library/Android/sdk
```

### Gradle Configuration
- Gradle version: **8.5** (NOT 9.0)
- Android Gradle Plugin: **7.4.2**

Already configured in:
- `gradle/wrapper/gradle-wrapper.properties`
- `build.gradle`

---

## 5. After Copying

### Step 1: Update local.properties
```bash
cd "/path/to/Castle/Android SDK/Sample Code/Emvtxn-S1F4"
echo "sdk.dir=/Users/$(whoami)/Library/Android/sdk" > local.properties
```

### Step 2: Clone from Git (Alternative to Copy)
If you prefer a fresh clone:
```bash
git clone https://github.com/toddb-amt/Castle.git
```
**Note**: This only gets the source code, not SDK/docs/tools.

### Step 3: Verify Build
```bash
cd "/path/to/Castle/Android SDK/Sample Code/Emvtxn-S1F4"
./gradlew clean assembleDebug
```

### Step 4: Configure Git Remote (if needed)
```bash
git remote set-url origin https://github.com/toddb-amt/Castle.git
```

---

## 6. Sensitive Files

### Contains Production Keys
```
/Users/mbroadbent/Documents/Castle/docs/PRODUCTION_DUKPT_KEYS.md
```
- BDK, IPEK, KSN values for terminal GH111001
- **Keep secure** - do not commit to public repos

### GitHub Authentication
Create a new Personal Access Token on new machine:
1. Go to https://github.com/settings/tokens
2. Create Classic token with `repo` scope
3. Use for git push operations

---

## 7. Terminal Connection

### Serial Port
```bash
ls /dev/tty.usbmodem*
```
Port name varies by machine (e.g., `/dev/tty.usbmodem144201`).

### Deploy to Terminal
```bash
# 1. Build
./gradlew assembleDebug

# 2. Package into CAP
cd /path/to/CTOS_SDK_Installed/CAPTools/bin
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/path/to/apk/output/directory" app-debug.apk 1 0

# 3. Copy files (Loader can't handle spaces in paths)
cp "/path/to/output/debug.CAP" /tmp/
cp "/path/to/output/debug.mci" /tmp/

# 4. Upload via Loader
printf '/dev/tty.usbmodemXXXXX\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

---

## 8. Current Project Status (February 2026)

### Completed
- Cashless ATM UI (amount selection, receipt, admin)
- EMV card reading (contact, contactless, MSR)
- Hyosung STD1 protocol implementation
- Host totals support
- DUKPT PIN encryption

### Blocking Issue
- **PIN Key Attribute**: SDK error 0x1003 during internal PIN flow
- See: `PIN_CRYPTOGRAM_ISSUE.md`
- Pending: Castle support escalation

### Next Steps
- Resolve PIN key attribute issue with Castle
- End-to-end testing with live processor
- PCI compliance review

---

## 9. File Size Summary

```
Total Castle folder: ~4.5 GB
├── ZIPZ/                    ~2.5 GB (SDK zips, firmware)
├── Firmware/                ~1.0 GB (S1F4 firmware)
├── CastlesHost_*/           ~100 MB (TSYS SDK reference)
├── CTOS_SDK_Installed/      ~200 MB (active SDK)
├── Android SDK/             ~50 MB (project source)
└── Documentation            ~500 KB (markdown files)
```

---

## 10. Contact & Resources

- **GitHub Repo**: https://github.com/toddb-amt/Castle
- **Castle Support**: (for SDK/terminal issues)
- **Protocol Reference**: `docs/HYOSUNG_ATM_MESSAGE_SPECIFICATION.md`

---

*Last updated: February 4, 2026*
