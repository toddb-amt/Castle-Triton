# Gradle Workflows Reference

## Contents
- Build Workflow
- Clean Build Workflow
- Dependency Update Workflow
- APK to CAP Packaging Workflow
- Troubleshooting Workflow

## Build Workflow

### Standard Debug Build

```bash
# Navigate to project root
cd "/Users/broadbent/Documents/TFI/Castle/Android SDK/Sample Code/Emvtxn-S1F4"

# Build debug APK
./gradlew assembleDebug

# APK location
ls -la app/build/outputs/apk/debug/app-debug.apk
```

### Build with Tests

```bash
# Build and run all tests
./gradlew build

# Build without tests (faster)
./gradlew assembleDebug -x test
```

## Clean Build Workflow

**When to use:** After modifying SDK JARs, changing build config, or fixing strange errors.

Copy this checklist and track progress:
- [ ] Step 1: Clean Gradle cache
- [ ] Step 2: Clean build directories
- [ ] Step 3: Rebuild project
- [ ] Step 4: Verify APK generated

```bash
# Step 1: Clean Gradle cache
./gradlew clean

# Step 2: Remove build directories (if clean fails)
rm -rf app/build
rm -rf build
rm -rf .gradle

# Step 3: Rebuild
./gradlew assembleDebug

# Step 4: Verify
ls -la app/build/outputs/apk/debug/app-debug.apk
```

## Dependency Update Workflow

### Adding a New Dependency

1. Edit `app/build.gradle`:

```groovy
dependencies {
    // Add new dependency
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

2. Sync and build:

```bash
./gradlew --refresh-dependencies build
```

3. If validation fails, check for conflicts:

```bash
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

### Adding Castle SDK JAR

1. Copy JAR to `app/libs/`:

```bash
cp /path/to/CTOS.NewLibrary_1.0.0.jar app/libs/
```

2. Verify inclusion:

```bash
ls -la app/libs/
```

3. Clean build (required for new JARs):

```bash
./gradlew clean assembleDebug
```

## APK to CAP Packaging Workflow

**CRITICAL:** Castle terminals use CAP files, not direct APK install.

Copy this checklist and track progress:
- [ ] Step 1: Build debug APK
- [ ] Step 2: Run CAPGen to create CAP file
- [ ] Step 3: Copy files to /tmp (path spaces issue)
- [ ] Step 4: Upload via Loader tool

### Step 1: Build APK

```bash
cd "/Users/broadbent/Documents/TFI/Castle/Android SDK/Sample Code/Emvtxn-S1F4"
./gradlew clean assembleDebug
```

### Step 2: Create CAP File

```bash
cd /Users/broadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin

DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/Users/broadbent/Documents/TFI/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/debug" \
  app-debug.apk 1 0
```

### Step 3: Copy to /tmp

```bash
# Loader cannot handle paths with spaces
cp "/Users/broadbent/Documents/TFI/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.CAP" /tmp/
cp "/Users/broadbent/Documents/TFI/Castle/Android SDK/Sample Code/Emvtxn-S1F4/app/build/outputs/apk/output/debug.mci" /tmp/
```

### Step 4: Upload to Terminal

```bash
# Find serial port
ls /dev/tty.usbmodem*

# Upload via Loader
cd /Users/broadbent/Documents/Castle/CTOS_SDK_Installed/CAPTools/bin
printf '/dev/tty.usbmodem144201\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

## Troubleshooting Workflow

### Build Failure Debug

```bash
# Run with stack trace
./gradlew assembleDebug --stacktrace

# Run with debug output
./gradlew assembleDebug --debug

# Run with info output (less verbose)
./gradlew assembleDebug --info
```

### Dependency Tree Analysis

```bash
# Show all dependencies
./gradlew app:dependencies

# Show specific configuration
./gradlew app:dependencies --configuration debugRuntimeClasspath

# Find dependency conflicts
./gradlew app:dependencies | grep -A 5 "CONFLICT"
```

### Cache Issues

If build behaves unexpectedly:

```bash
# Clear Gradle cache
./gradlew clean
rm -rf ~/.gradle/caches/

# Clear project cache
rm -rf .gradle/
rm -rf app/build/
rm -rf build/

# Rebuild
./gradlew assembleDebug
```

### Validation Loop

1. Make build.gradle changes
2. Validate: `./gradlew assembleDebug`
3. If validation fails, check error output and fix
4. Repeat step 2 until build succeeds
5. Only proceed to CAP packaging when build passes

## Related Skills

- See the **castle-sdk** skill for SDK JAR management
- See the **android** skill for manifest and resource configuration
- See the **android-testing** skill for test execution