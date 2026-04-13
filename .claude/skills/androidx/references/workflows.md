# AndroidX Workflows Reference

## Contents
- Adding New Fragment
- Fragment-to-Fragment Communication
- Adding AndroidX Dependencies
- Testing Fragment Lifecycle

---

## Adding New Fragment

### Workflow Checklist

Copy this checklist and track progress:
- [ ] Step 1: Create layout XML in `res/layout/fragment_page_newname.xml`
- [ ] Step 2: Create Fragment class extending `androidx.fragment.app.Fragment`
- [ ] Step 3: Add page constant to `GlobalDef.java`
- [ ] Step 4: Register in `SectionsPagerAdapter.getItem()`
- [ ] Step 5: Update `SectionsPagerAdapter.getCount()` and `getPageTitle()`
- [ ] Step 6: Build and verify navigation works

### Step 1: Create Layout

```xml
<!-- res/layout/fragment_page_newname.xml -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/id_page_newname"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/txvTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="New Page"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
        
</androidx.constraintlayout.widget.ConstraintLayout>
```

### Step 2: Create Fragment Class

```java
package castech.emvtxn;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class Fragment_page_newname extends Fragment {
    private static final String TAG = "Fragment_page_newname";
    private MainActivity mainActivity;
    private View view;

    public Fragment_page_newname(MainActivity activity) {
        this.mainActivity = activity;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_page_newname, container, false);
        initializeComponents();
        return view;
    }

    private void initializeComponents() {
        // findViewById and setup listeners
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data when visible
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Cleanup handlers, listeners
    }
}
```

### Step 3-5: Register in Adapter

```java
// GlobalDef.java - add constant
public static final int d_PAGE_NEWNAME = 6;

// MainActivity.SectionsPagerAdapter.getItem()
case 6:
    fragment = new Fragment_page_newname(this.activity);
    break;

// MainActivity.SectionsPagerAdapter.getCount()
return 7;  // Was 6

// MainActivity.SectionsPagerAdapter.getPageTitle()
case GlobalDef.d_PAGE_NEWNAME:
    return "New Name";
```

---

## Fragment-to-Fragment Communication

### Pattern: Via MainActivity Reference

```java
// In Fragment_page_amount_selection
public void selectAmount(double amount) {
    GlobalPara.atmSelectedAmount = String.format("%.2f", amount);
    GlobalPara.atmFee = calculateFee(amount);
    GlobalPara.atmTotal = calculateTotal(amount);
    
    if (mainActivity != null) {
        mainActivity.navigateToPage(GlobalDef.d_PAGE_TRANSACTION);
    }
}
```

### Pattern: Refresh Adjacent Fragment

```java
// In MainActivity.navigateToPage()
if (pageIndex == GlobalDef.d_PAGE_RECEIPT) {
    Fragment fragment = mSectionsPagerAdapter.getCachedFragment(pageIndex);
    if (fragment instanceof Fragment_page_receipt) {
        ((Fragment_page_receipt) fragment).refreshDisplay();
    }
}
```

### Pattern: Global State Reset

```java
// In Fragment_page_receipt when returning to main menu
private void returnToMainMenu() {
    GlobalPara.resetATMTransactionState();  // Clear shared state
    if (mainActivity != null) {
        mainActivity.navigateToPage(GlobalDef.d_PAGE_MAIN_MENU);
    }
}
```

---

## Adding AndroidX Dependencies

### Standard Workflow

1. Add dependency to `app/build.gradle`:

```gradle
dependencies {
    // Core AndroidX
    implementation 'androidx.appcompat:appcompat:1.4.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'com.google.android.material:material:1.5.0'
    
    // Required for 27+ SDK JARs
    implementation 'androidx.multidex:multidex:2.0.1'
    
    // Add new dependency
    implementation 'androidx.recyclerview:recyclerview:1.2.1'
}
```

2. Sync and rebuild:

```bash
./gradlew clean build
```

3. Validate build:
   - If build fails, check for dependency conflicts
   - Repeat step 2 until build passes

### WARNING: MultiDex Requirement

This project REQUIRES MultiDex due to 27 Castle SDK JARs exceeding the 64K method limit.

**gradle.properties (required):**
```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

**app/build.gradle (required):**
```gradle
android {
    defaultConfig {
        multiDexEnabled true
    }
}

dependencies {
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

---

## Testing Fragment Lifecycle

### Manual Test Sequence

1. **Launch app** → Verify main menu displays
2. **Navigate to Amount Selection** → Verify amounts display
3. **Navigate to Transaction** → Verify card detection starts
4. **Press back/cancel** → Verify return to menu without crash
5. **Repeat navigation** → Verify no memory leaks

### Common Lifecycle Issues

| Issue | Symptom | Fix |
|-------|---------|-----|
| Handler leak | App crash on rapid navigation | Null handler in onDestroyView |
| View reference leak | OutOfMemoryError | Clear view refs in onDestroyView |
| Dialog on destroyed | WindowManager BadTokenException | Check isAdded() before show() |
| Stale data | Wrong values displayed | Refresh in setMenuVisibility |

### Fragment Visibility Validation

```java
// Add logging to verify lifecycle
@Override
public void onResume() {
    super.onResume();
    Log.d(TAG, "onResume");
}

@Override
public void setMenuVisibility(boolean menuVisible) {
    super.setMenuVisibility(menuVisible);
    Log.d(TAG, "setMenuVisibility: " + menuVisible);
}

@Override
public void onDestroyView() {
    Log.d(TAG, "onDestroyView");
    super.onDestroyView();
}
```

Then verify in logcat during navigation:
```bash
adb logcat -s Fragment_page_receipt:D
```

---

## ViewPager Swipe Disable Pattern

This codebase disables swipe to prevent interrupting EMV card detection:

```java
// In MainActivity.onCreate()
mViewPager.setOnTouchListener((v, event) -> true);  // Consume all touches
```

To re-enable for specific pages:
```java
mViewPager.setOnTouchListener((v, event) -> {
    int currentPage = mViewPager.getCurrentItem();
    // Allow swipe only on receipt page
    return currentPage != GlobalDef.d_PAGE_RECEIPT;
});
```

---

## Related Skills

- See the **android-fragments** skill for EMV callback integration
- See the **gradle** skill for build configuration and MultiDex setup
- See the **java** skill for threading patterns used with Handlers