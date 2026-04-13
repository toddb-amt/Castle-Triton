# Castle Terminal Upload Guide

This document describes how to build and upload APK files to Castle S1F4 PRO terminals using the Loader tool.

## Prerequisites

- Castle S1F4 PRO terminal connected via USB
- Terminal in download mode
- macOS with the CAPTools installed at: `/Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin/`

## Step 1: Build the APK

```bash
cd "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4"
./gradlew clean assembleDebug
```

The APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Step 2: Package APK into CAP File

Navigate to the CAPTools directory and run CAPGen:

```bash
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin

DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn 0100 Castles 41 \
  "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/debug" \
  app-debug.apk 1 0
```

### CAPGen Parameters Explained:
| Parameter | Value | Description |
|-----------|-------|-------------|
| Product | SATURN1000 | Terminal type (S1F4 uses SATURN1000) |
| AppName | Emvtxn | Application name (20 chars max) |
| AppVer | 0100 | Version in BCD format (01.00) |
| CompanyName | Castles | Company name (20 chars max) |
| AppType | 41 | APK application type |
| SourceFolder | (path) | Folder containing the APK |
| ExeFile | app-debug.apk | APK filename |
| DefaultSelect | 1 | Default selected (1=yes, 0=no) |
| GenerationMode | 0 | Evaluation mode (0=eval, 1=keycard) |

This generates two files in an `output` subfolder:
- `debug.CAP` - The packaged application
- `debug.mci` - Manifest file for Loader

## Step 3: Find the Terminal Serial Port

```bash
ls /dev/tty.usbmodem*
```

Typical output: `/dev/tty.usbmodem144201`

The port name may vary depending on the USB port used.

## Step 4: Upload to Terminal

The Loader tool runs interactively. Use echo to pipe inputs:

```bash
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin

# Copy files to a path without spaces (Loader has issues with spaces)
cp "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.mci" ~/Desktop/debug.mci

# Upload via Loader
echo -e "/dev/tty.usbmodem144201\n/Users/mbroadbent/Desktop/debug.mci" | \
  DYLD_LIBRARY_PATH="." ./Loader
```

### Expected Output:
```
Welcome to ULD Loader VR1.00
Please input port full name :
ex. /dev/cu.usbmodem1811

Please input MCI/MMCI file name :
Start DownLoad
-------------------2047744/5516093 B-------------------4095744/5516093 B-------------
ULDCAP_Download ret = 0000, spend : 61054 msec
```

- `ret = 0000` indicates success
- Upload typically takes 60-90 seconds for a ~5MB APK

## Quick One-Liner

For fast rebuilds and uploads:

```bash
cd "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4" && \
./gradlew assembleDebug && \
cd /Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin && \
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn 0100 Castles 41 \
  "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/debug" \
  app-debug.apk 1 0 && \
cp "/Users/mbroadbent/Documents/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.mci" ~/Desktop/debug.mci && \
echo -e "/dev/tty.usbmodem144201\n/Users/mbroadbent/Desktop/debug.mci" | \
  DYLD_LIBRARY_PATH="." ./Loader
```

## Troubleshooting

### "No USB modem devices found"
- Ensure terminal is connected via USB
- Put terminal in download mode
- Try a different USB port

### "is incorrect, Please confirm"
- Path contains spaces - copy files to a simple path like `~/Desktop/`
- Ensure the .mci file exists

### Upload hangs or fails
- Check USB connection
- Restart terminal in download mode
- Try a different USB cable

### App crashes on terminal
- Check for Java 8 lambdas (`->`) in source code - terminal may not support them
- Use anonymous inner classes instead of lambdas
- Check logcat if available

## Important Notes

1. **Do NOT use ADB** - Castle terminals use Loader, not ADB
2. **DYLD_LIBRARY_PATH** is required on macOS for the tools to find their libraries
3. **Spaces in paths** cause issues with Loader - copy files to simple paths
4. The terminal may need to be rebooted after upload to run the new app

## File Locations Summary

| File | Location |
|------|----------|
| CAPGen | `/Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin/CAPGen` |
| Loader | `/Users/mbroadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin/Loader` |
| APK Output | `app/build/outputs/apk/debug/app-debug.apk` |
| CAP Output | `app/build/outputs/apk/output/debug.CAP` |
| MCI Output | `app/build/outputs/apk/output/debug.mci` |
