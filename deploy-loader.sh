#!/bin/bash
#
# deploy-loader.sh - Deploy Cashless ATM app via Loader (for Castle S1F4 PRO terminal)
#
# Usage: ./deploy-loader.sh [--clean] [--port /dev/tty.usbmodemXXXXXX]
#   --clean              Force clean build before deploy
#   --port <port>        Specify serial port (auto-detects if not provided)
#
# IMPORTANT: Castle terminals do NOT use ADB. Must use CAPGen + Loader via serial port.
#

set -e

# Configuration
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DIR="$PROJECT_DIR/app/build/outputs/apk/debug"
OUTPUT_DIR="$PROJECT_DIR/app/build/outputs/apk/output"
CAPTOOLS_DIR="/Users/broadbent/Documents/TFI/Castle/CTOS_SDK_Installed/CAPTools/bin"
JAVA_HOME_17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

# CAPGen parameters
TERMINAL_TYPE="SATURN1000"
APP_NAME="Emvtxn_Debug"
APP_VERSION="0100"
VENDOR="Castech"
APP_ID="41"
APK_FILENAME="app-debug.apk"
COMPRESS="1"
ENCRYPT="0"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo "============================================"
echo "  Cashless ATM - Loader Deployment"
echo "  (Castle S1F4 PRO Terminal)"
echo "============================================"
echo ""

# Parse arguments
CLEAN_BUILD=false
SERIAL_PORT=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --port)
            SERIAL_PORT="$2"
            shift 2
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Check for CAPTools
if [ ! -d "$CAPTOOLS_DIR" ]; then
    echo -e "${RED}ERROR: CAPTools not found at $CAPTOOLS_DIR${NC}"
    echo "Please verify CTOS SDK installation"
    exit 1
fi

# Check for Java 17 (required for Gradle 8.5 + AGP 7.4.2)
if [ -d "$JAVA_HOME_17" ]; then
    export JAVA_HOME="$JAVA_HOME_17"
    echo -e "${GREEN}Using Java 17:${NC} $JAVA_HOME"
else
    echo -e "${YELLOW}WARNING: Java 17 not found at $JAVA_HOME_17${NC}"
    echo "Attempting build with system Java..."
fi

# Auto-detect serial port if not specified
if [ -z "$SERIAL_PORT" ]; then
    echo ""
    echo "Detecting serial port..."
    DETECTED_PORTS=$(ls /dev/tty.usbmodem* 2>/dev/null || true)

    if [ -z "$DETECTED_PORTS" ]; then
        echo -e "${RED}ERROR: No USB modem ports found${NC}"
        echo ""
        echo "Please ensure:"
        echo "  1. Castle terminal is connected via USB"
        echo "  2. Terminal is in Download Mode (Settings → System → Download Mode)"
        echo ""
        echo "Available serial ports:"
        ls /dev/tty.usb* 2>/dev/null || echo "  (none)"
        exit 1
    fi

    # Use first detected port
    SERIAL_PORT=$(echo "$DETECTED_PORTS" | head -1)
    echo -e "${GREEN}Detected port:${NC} $SERIAL_PORT"
fi

# Verify serial port exists
if [ ! -e "$SERIAL_PORT" ]; then
    echo -e "${RED}ERROR: Serial port not found: $SERIAL_PORT${NC}"
    exit 1
fi

echo ""

# Step 1: Build APK
echo -e "${CYAN}Step 1: Building APK...${NC}"
cd "$PROJECT_DIR"

if [ "$CLEAN_BUILD" = true ]; then
    echo "Performing clean build..."
    ./gradlew clean
fi

./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}ERROR: APK not found at $APK_PATH${NC}"
    exit 1
fi

echo -e "${GREEN}APK built successfully${NC}"
echo ""

# Step 2: Create output directory
mkdir -p "$OUTPUT_DIR"

# Step 3: Package APK into CAP file using CAPGen
echo -e "${CYAN}Step 2: Packaging APK into CAP file...${NC}"
cd "$CAPTOOLS_DIR"

# CAPGen uses positional arguments (the -a/-o syntax does NOT work)
# Usage: CAPGen <terminal_type> <app_name> <version> <vendor> <app_id> <apk_dir> <apk_file> <compress> <encrypt>
DYLD_LIBRARY_PATH="." ./CAPGen "$TERMINAL_TYPE" "$APP_NAME" "$APP_VERSION" "$VENDOR" "$APP_ID" \
    "$APK_DIR" "$APK_FILENAME" "$COMPRESS" "$ENCRYPT"

# Move CAP and MCI files to output directory
if [ -f "$APK_DIR/debug.CAP" ]; then
    mv "$APK_DIR/debug.CAP" "$OUTPUT_DIR/"
fi
if [ -f "$APK_DIR/debug.mci" ]; then
    mv "$APK_DIR/debug.mci" "$OUTPUT_DIR/"
fi

if [ ! -f "$OUTPUT_DIR/debug.CAP" ]; then
    echo -e "${RED}ERROR: CAP file not created${NC}"
    exit 1
fi

echo -e "${GREEN}CAP file created:${NC} $OUTPUT_DIR/debug.CAP"
echo ""

# Step 4: Copy files to /tmp (Loader can't handle paths with spaces)
echo -e "${CYAN}Step 3: Preparing files for Loader...${NC}"
cp "$OUTPUT_DIR/debug.CAP" /tmp/
cp "$OUTPUT_DIR/debug.mci" /tmp/

echo "Files copied to /tmp:"
ls -la /tmp/debug.CAP /tmp/debug.mci
echo ""

# Step 5: Upload to terminal using Loader
echo -e "${CYAN}Step 4: Uploading to terminal via Loader...${NC}"
echo ""
echo -e "${YELLOW}IMPORTANT: Ensure terminal is in Download Mode${NC}"
echo "  (Settings → System → Download Mode)"
echo ""
echo "Using serial port: $SERIAL_PORT"
echo "Uploading /tmp/debug.mci..."
echo ""

cd "$CAPTOOLS_DIR"

# Loader requires interactive input - pipe the port and mci file path
printf '%s\n/tmp/debug.mci\n' "$SERIAL_PORT" | DYLD_LIBRARY_PATH="." ./Loader

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Deployment Complete!${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "CAP file: $OUTPUT_DIR/debug.CAP"
echo "Serial port: $SERIAL_PORT"
echo ""
echo -e "${YELLOW}NOTE: If upload timed out, retry - sometimes takes 2-3 attempts${NC}"
echo ""
