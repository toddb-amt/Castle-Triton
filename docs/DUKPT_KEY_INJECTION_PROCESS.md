# DUKPT Key Injection Process for Castle S1F4 PRO

## Overview

This document describes the complete process for injecting DUKPT IPEK keys into Castle S1F4 PRO terminals using the Key Injection Tool V2.01.

**Important**: DUKPT injection uses the **Key_Bridge** app on the terminal, NOT the Key_Injection app or download mode.

---

## Prerequisites

- Windows PC (or Parallels/VM on Mac)
- Castle Key Injection Tool V2.01
- USB connection to terminal
- Terminal with Key_Bridge and Key_Injection apps installed

---

## Complete Process Overview

```
1. Factory Reset (if needed)
2. Inject Initial KEK at C000/0000 (via Key_Injection app)
3. Generate TR31 Key Block (in Key Injection Tool)
4. Inject DUKPT IPEK (via Key_Bridge app)
5. Load Config + ADB Tool (via Loader from Mac)
6. Install ATM App (via ADB)
```

---

## Step 1: Factory Reset (If Needed)

If re-injecting keys, perform factory reset first:

**On Terminal:**
1. Open **SystemPanel** app
2. Enter password `00000000` for both First and Second password
3. Select **Factory Reset**
4. Confirm and wait for terminal to reboot

---

## Step 2: Inject Initial KEK

The KEK (Key Encryption Key) must be injected first at **C000/0000**. This key will be used to unwrap the TR31 key block.

### On Terminal:
1. Launch **Key_Injection** app
2. Wait for screen showing **"Waiting for command"**

### On Windows (Key Injection Tool):
1. Click **Refresh** and select COM port
2. Go to **"Initial Key (KEK)"** tab
3. Enter:
   - **Key Set**: `C000`
   - **Key Index**: `0000`
   - **Use Components**: Unchecked
   - **Key Value**: `0123456789ABCDEFFEDCBA9876543210`
4. Click **Check Value** - verify ends in `D7B4`
5. Click **Inject**
6. Log should show: `The KEK(KeySet : C000, KeyIndex : 0000) Loaded Successfully`

---

## Step 3: Generate TR31 Key Block

The IPEK must be wrapped in a TR31 key block using the same KEK.

### On Windows (Key Injection Tool):
1. Go to **"Generate Key Block"** tab
2. Enter:
   - **KEK Com #1**: `0123456789ABCDEFFEDCBA9876543210` (same as injected KEK)
   - **Working Key**: `6AC292FAA1315B4D858AB3A3D7D5933A` (the IPEK)
   - **KSN**: `FFFF9876543210E00000`
   - **Working Key Type**: `3DES-DUKPT`
   - **Key Designation**: `IPEK`
3. Click **Generate**
4. Copy the generated TR31 key block from the log window

Example TR31 block:
```
B0104B1TX00N0100KS18FFFF9876543210E000005FADFB6F7189125ACE10CA0DF7C553FA5179643877EBB237DFBB3844407913DC
```

---

## Step 4: Inject DUKPT IPEK via Key_Bridge

### On Terminal:
1. Exit Key_Injection app
2. Launch **Key_Bridge** app
3. Select **"Geobridge SW"**
4. Wait for screen showing **"READY FOR KI"**

### On Windows (Key Injection Tool):
1. Click **Refresh** and select COM port
2. Should show: `Get Device Info Success`
3. Go to **"WK with Geobridge"** tab
4. Select **DUKPT(1A)** from Key Index dropdown
5. Paste the TR31 key block from Step 3
6. Click **Inject**
7. Log should show: `Key-1 Load_KEY Success Check Vaule:af8c07`

The KCV `af8c07` confirms the IPEK was loaded correctly.

---

## Step 5: Load Config + ADB Tool (Mac)

After key injection, load the config file and ADB tool to enable debug mode.

### 5a: Load Config File

**On Terminal:**
1. Exit Key_Bridge app
2. Enter **Download Mode** (Settings → System → Download Mode)

**On Mac:**
```bash
# Copy config to /tmp
cp "/Users/mbroadbent/Documents/Castle/ZIPZ/config/signed/config.CAP" /tmp/
cp "/Users/mbroadbent/Documents/Castle/ZIPZ/config/signed/config.mci" /tmp/

# Find serial port
ls /dev/tty.usbmodem*

# Load config
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin
printf '/dev/tty.usbmodem144201\n/tmp/config.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

Should show: `ULDCAP_Download ret = 0000`

### 5b: Load ADB Tool

**On Terminal:**
1. Exit download mode (reboot)
2. Re-enter **Download Mode**

**On Mac:**
```bash
# Copy ADB tool to /tmp
cp "/Users/mbroadbent/Documents/Castle/ZIPZ/DebugApp/Debug App Android 13 S1F4PRO/signed/S1F4PRO_adbtool.CAP" /tmp/
cp "/Users/mbroadbent/Documents/Castle/ZIPZ/DebugApp/Debug App Android 13 S1F4PRO/signed/S1F4PRO_adbtool.mci" /tmp/

# Load ADB tool
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin
printf '/dev/tty.usbmodem144201\n/tmp/S1F4PRO_adbtool.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

Should show: `ULDCAP_Download ret = 0000`

---

## Step 6: Install ATM App via ADB

**On Terminal:**
1. Exit download mode
2. Launch the **ADB Tool** app (or it may auto-enable ADB)

**On Mac:**
```bash
# Verify terminal is connected
~/Library/Android/sdk/platform-tools/adb devices -l

# Install app
~/Library/Android/sdk/platform-tools/adb -s <DEVICE_ID> install -r \
  "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/debug/app-debug.apk"
```

---

## Test Keys Reference

| Key | Value | KCV |
|-----|-------|-----|
| Test KEK | `0123456789ABCDEFFEDCBA9876543210` | `08D7B4` |
| Test BDK | `0123456789ABCDEFFEDCBA9876543210` | `08D7B4` |
| Test IPEK | `6AC292FAA1315B4D858AB3A3D7D5933A` | `AF8C07` |
| Test KSN | `FFFF9876543210E00000` | - |

---

## Key Locations

| Purpose | Key Set | Key Index | Injected Via |
|---------|---------|-----------|--------------|
| KEK (for TR31 unwrap) | C000 | 0000 | Key_Injection app |
| DUKPT IPEK | DUKPT(1A) | - | Key_Bridge app |

---

## App DUKPT Configuration

The ATM app must be configured to use DUKPT. In `GlobalPara.java`:

```java
public static String atmPinBlockFormat = "DUKPT";
public static boolean atmDukptEnabled = true;
public static int atmDukptKeySet = 0x0000C001;    // DUKPT(1A) slot - Key Set C001
public static int atmDukptKeyIndex = 0x0000001A;  // DUKPT(1A) slot - Key Index 1A (26 decimal)
```

**DUKPT Slot Mapping:**
The Key Injection Tool's DUKPT slots map to SDK key locations as follows:
- **DUKPT(1A)** → Key Set: C001, Key Index: 001A (26 decimal)
- **DUKPT(1B)** → Key Set: C001, Key Index: 001B (27 decimal)
- **DUKPT(2)** → Key Set: C001, Key Index: 0002 (likely)

This was confirmed by examining the CastlesHost SampleApp source code.

---

## Troubleshooting

### "Get Device Info Fail"
- Terminal is in wrong mode
- For KEK injection: Use **Key_Injection** app (shows "Waiting for command")
- For DUKPT injection: Use **Key_Bridge** app → Geobridge SW (shows "READY FOR KI")

### Error b004
- Initial KEK not injected at C000/0000
- Or TR31 key block was generated with different KEK
- Solution: Re-inject KEK, regenerate TR31 block with same KEK

### Error 0x00002901 (in app)
- DUKPT key not found at expected location
- **SOLUTION**: DUKPT(1A) maps to Key Set **C001**, Key Index **001A** (26 decimal)
- Common incorrect values: C002/00A1, 0001/000A, 0000/0001
- Confirmed mapping from CastlesHost SampleApp source code (PollCardFragment.java)

### KCV Mismatch
- Key value entered incorrectly
- Verify 32 hex characters, no spaces

---

## Process Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    FACTORY RESET                             │
│                   (if re-injecting)                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              INJECT KEK at C000/0000                         │
│                                                              │
│  Terminal: Key_Injection app → "Waiting for command"         │
│  PC Tool:  Initial Key (KEK) tab → Inject                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              GENERATE TR31 KEY BLOCK                         │
│                                                              │
│  PC Tool:  Generate Key Block tab                            │
│            KEK + IPEK + KSN → TR31 block                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              INJECT DUKPT IPEK                               │
│                                                              │
│  Terminal: Key_Bridge app → Geobridge SW → "READY FOR KI"    │
│  PC Tool:  WK with Geobridge tab → DUKPT(1A) → Inject        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              LOAD CONFIG FILE                                │
│                                                              │
│  Terminal: Download Mode                                     │
│  Mac:      Loader → config.mci                               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              LOAD ADB TOOL                                   │
│                                                              │
│  Terminal: Exit download, re-enter Download Mode             │
│  Mac:      Loader → S1F4PRO_adbtool.mci                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              INSTALL APP via ADB                             │
│                                                              │
│  Terminal: Exit download mode, ADB enabled                   │
│  Mac:      adb install app-debug.apk                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    TEST TRANSACTION                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Date
December 22, 2025
