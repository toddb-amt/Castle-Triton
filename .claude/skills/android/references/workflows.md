# Android Workflows Reference

## Contents
- Build and Deploy Workflow
- Testing Workflow
- Debug Workflow
- Fragment Development Workflow

## Build and Deploy Workflow

### Build Debug APK

```bash
# Navigate to project
cd "Android SDK/Sample Code/Emvtxn-S1F4"

# Clean and build
./gradlew clean assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Deploy to Castle Terminal

**WARNING:** Castle terminals do NOT use ADB. Must use CAPGen + Loader.

Copy this checklist and track progress:
- [ ] Step 1: Build debug APK with `./gradlew clean assembleDebug`
- [ ] Step 2: Package into CAP file with CAPGen
- [ ] Step 3: Copy CAP/MCI to /tmp (Loader can't handle spaces)
- [ ] Step 4: Connect terminal via USB, enter download mode
- [ ] Step 5: Upload via Loader tool

```bash
# Package APK into CAP
cd /path/to/CTOS_SDK_Installed/CAPTools/bin
DYLD_LIBRARY_PATH="." ./CAPGen SATURN1000 Emvtxn_Debug 0100 Castech 41 \
  "/path/to/apk/debug" app-debug.apk 1 0

# Copy files (Loader fails with paths containing spaces)
cp .../output/debug.CAP /tmp/
cp .../output/debug.mci /tmp/

# Find serial port
ls /dev/tty.usbmodem*

# Upload to terminal
printf '/dev/tty.usbmodem144201\n/tmp/debug.mci\n' | DYLD_LIBRARY_PATH="." ./Loader
```

See the **castle-sdk** skill for detailed deployment documentation.

## Testing Workflow

### Run All Unit Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
# Protocol tests
./gradlew test --tests "castech.emvtxn.atm.host.HyosungProtocolTest"

# EMV tag tests
./gradlew test --tests "*EmvTagEnhancer*"

# With verbose output
./gradlew test --info
```

### Test Validation Loop

1. Make code changes
2. Run tests: `./gradlew test`
3. If tests fail, fix issues and repeat step 2
4. Only proceed when all tests pass

### Manual Testing on Terminal

Copy this checklist and track progress:
- [ ] Deploy APK to terminal
- [ ] Test idle screen displays correctly
- [ ] Test main menu navigation
- [ ] Test amount selection (preset and custom)
- [ ] Test card detection (chip, tap, swipe)
- [ ] Test PIN entry
- [ ] Test receipt display and printing
- [ ] Test admin settings access

See `docs/ATM_TESTING_GUIDE.md` for complete 10-phase test plan.

## Debug Workflow

### Enable Verbose Logging

```bash
# Capture all logs from terminal
adb logcat -v time > debug.log

# Filter by app
adb logcat -v time *:S castech.emvtxn:V > app.log
```

### Common Debug Points

```java
// Add TAG to every class
private static final String TAG = "Fragment_page_transaction";

// Log state transitions
Log.d(TAG, "Transaction state: amount=" + GlobalPara.atmSelectedAmount);
Log.d(TAG, "EMV result code: 0x" + Integer.toHexString(result));
```

### EMV Debug Mode

Enable additional EMV logging per Castle documentation:
- Reference: "How to enable additional EMV EMVCL debug.txt"
- Captures low-level SDK operations
- Required for diagnosing cryptogram issues

## Fragment Development Workflow

### Create New Fragment

Copy this checklist and track progress:
- [ ] Step 1: Create layout XML in `res/layout/fragment_page_*.xml`
- [ ] Step 2: Create Java class extending Fragment
- [ ] Step 3: Add page constant to GlobalDef.java
- [ ] Step 4: Register in SectionsPagerAdapter
- [ ] Step 5: Update getCount() if needed

### Fragment Template

```java
// Fragment_page_example.java
public class Fragment_page_example extends Fragment {
    private static final String TAG = "Fragment_page_example";
    private View rootView;
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_page_example, container, false);
        initViews();
        return rootView;
    }
    
    private void initViews() {
        Button btnNext = rootView.findViewById(R.id.btn_next);
        btnNext.setOnClickListener(v -> {
            GlobalPara.mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
        });
    }
    
    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser && isResumed()) {
            onBecameVisible();
        }
    }
    
    private void onBecameVisible() {
        Log.d(TAG, "Fragment visible");
        // Initialize or refresh data
    }
}
```

### Register Fragment in Adapter

```java
// MainActivity.java - SectionsPagerAdapter
@Override
public Fragment getItem(int position) {
    switch (position) {
        // Existing cases...
        case GlobalDef.d_PAGE_EXAMPLE:
            return new Fragment_page_example();
    }
}

@Override
public int getCount() {
    return 7;  // Increment for new fragment
}
```

### Add Page Constant

```java
// GlobalDef.java
public static final int d_PAGE_EXAMPLE = 6;
```

## Build Configuration Verification

### Required gradle.properties

```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

### Required app/build.gradle Settings

```gradle
android {
    compileSdkVersion 31
    
    defaultConfig {
        minSdkVersion 24
        targetSdkVersion 31
        multiDexEnabled true  // REQUIRED for 27 SDK JARs
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

See the **gradle** skill for complete build configuration details.