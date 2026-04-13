# Gradle Patterns Reference

## Contents
- Build Configuration Patterns
- Dependency Management
- DEX and Multidex
- Common Build Errors

## Build Configuration Patterns

### Correct app/build.gradle Structure

```groovy
apply plugin: 'com.android.application'

android {
    compileSdkVersion 31
    buildToolsVersion "30.0.3"

    defaultConfig {
        applicationId "castech.emvtxn"
        minSdkVersion 24
        targetSdkVersion 31
        versionCode 1
        versionName "1.0"
        multiDexEnabled true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    packagingOptions {
        resources {
            excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE']
        }
    }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation 'androidx.multidex:multidex:2.0.1'
    implementation 'androidx.appcompat:appcompat:1.4.1'
    implementation 'com.google.android.material:material:1.5.0'
}
```

### WARNING: Missing Multidex

**The Problem:**

```groovy
// BAD - Omitting multidex with 27 SDK JARs
defaultConfig {
    applicationId "castech.emvtxn"
    // multiDexEnabled not set
}
```

**Why This Breaks:**
1. DEX file limit is 65,536 methods
2. 27 Castle SDK JARs exceed this limit
3. Build fails with NullPointerException or method count errors

**The Fix:**

```groovy
// GOOD - Enable multidex
defaultConfig {
    applicationId "castech.emvtxn"
    multiDexEnabled true
}

dependencies {
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

## Dependency Management

### Local JAR Files

Castle SDK JARs must be in `app/libs/`:

```groovy
dependencies {
    // All JARs in libs folder
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    
    // Or specific JARs
    implementation files('libs/CTOS.CtEMV_2.0.80.jar')
    implementation files('libs/CTOS.CtKMS2_4.0.1.jar')
}
```

### Adding Network Libraries

For host communication, add OkHttp:

```groovy
dependencies {
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

### WARNING: Version Conflicts

**The Problem:**

```groovy
// BAD - Mixing incompatible AndroidX versions
implementation 'androidx.appcompat:appcompat:1.6.0'
implementation 'androidx.core:core:1.3.0'  // Too old
```

**Why This Breaks:**
1. AndroidX libraries have version interdependencies
2. Mismatched versions cause runtime crashes
3. Gradle may silently resolve to unexpected versions

**The Fix:**

```groovy
// GOOD - Use compatible versions
implementation 'androidx.appcompat:appcompat:1.4.1'
implementation 'androidx.core:core:1.7.0'
```

## DEX and Multidex

### Memory Configuration

```properties
# gradle.properties - REQUIRED settings
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

### WARNING: Insufficient Memory

**The Problem:**

```properties
# BAD - Default memory insufficient for large SDK
org.gradle.jvmargs=-Xmx512m
```

**Why This Breaks:**
1. DEX merging requires significant heap
2. 27 SDK JARs need processing memory
3. Build fails with OutOfMemoryError

**The Fix:**

```properties
# GOOD - Adequate memory allocation
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

## Common Build Errors

### "Cannot find symbol: module()"

**Cause:** Using Gradle 9.0-milestone-1

**Fix:** Downgrade to Gradle 8.5:

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
```

### Duplicate META-INF Files

**Cause:** Multiple JARs with conflicting metadata

**Fix:** Add packaging excludes:

```groovy
packagingOptions {
    resources {
        excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE',
                    'META-INF/LICENSE.txt', 'META-INF/NOTICE',
                    'META-INF/NOTICE.txt', 'META-INF/*.SF',
                    'META-INF/*.DSA', 'META-INF/*.RSA']
    }
}
```

### DEX NullPointerException

**Cause:** Missing multidex or insufficient memory

**Fix:** Enable both:

```groovy
// app/build.gradle
defaultConfig {
    multiDexEnabled true
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

## Related Skills

- See the **android** skill for Android manifest configuration
- See the **castle-sdk** skill for SDK JAR details
- See the **android-testing** skill for test configuration