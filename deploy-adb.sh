#!/bin/bash
#
# deploy-adb.sh - Deploy Cashless ATM app via ADB (for emulator/development)
#
# Usage: ./deploy-adb.sh [--clean]
#   --clean   Force clean build before deploy
#

set -e

# Configuration
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB_PATH="$HOME/Library/Android/sdk/platform-tools/adb"
JAVA_HOME_17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================"
echo "  Cashless ATM - ADB Deployment"
echo "============================================"
echo ""

# Check for ADB
if [ ! -f "$ADB_PATH" ]; then
    # Try system PATH
    if command -v adb &> /dev/null; then
        ADB_PATH="adb"
    else
        echo -e "${RED}ERROR: ADB not found at $ADB_PATH${NC}"
        echo "Please install Android SDK platform-tools"
        exit 1
    fi
fi

# Check for Java 17 (required for Gradle 8.5 + AGP 7.4.2)
if [ -d "$JAVA_HOME_17" ]; then
    export JAVA_HOME="$JAVA_HOME_17"
    echo -e "${GREEN}Using Java 17:${NC} $JAVA_HOME"
else
    echo -e "${YELLOW}WARNING: Java 17 not found at $JAVA_HOME_17${NC}"
    echo "Attempting build with system Java..."
fi

# Check for connected devices
echo ""
echo "Checking for connected devices..."
DEVICES=$("$ADB_PATH" devices | grep -v "List" | grep -v "^$" | wc -l | tr -d ' ')
if [ "$DEVICES" -eq 0 ]; then
    echo -e "${RED}ERROR: No devices connected${NC}"
    echo "Please connect a device or start an emulator"
    exit 1
fi
echo -e "${GREEN}Found $DEVICES device(s)${NC}"
"$ADB_PATH" devices | grep -v "List" | grep -v "^$"
echo ""

# Build APK
cd "$PROJECT_DIR"

if [ "$1" == "--clean" ]; then
    echo "Performing clean build..."
    ./gradlew clean
fi

echo "Building debug APK..."
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}ERROR: APK not found at $APK_PATH${NC}"
    exit 1
fi

echo -e "${GREEN}APK built successfully${NC}"
echo ""

# Install APK
echo "Installing APK via ADB..."
"$ADB_PATH" install -r "$APK_PATH"

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Deployment Complete!${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "APK installed: $APK_PATH"
echo ""
