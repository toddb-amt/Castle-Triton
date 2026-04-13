---
name: gradle
description: |
  Configures build system, dependency resolution, and APK/CAP packaging for Castle S1F4 PRO Android terminal.
  Use when: modifying build configuration, adding dependencies, fixing DEX/multidex issues, adjusting memory settings, or preparing APK for CAPGen packaging.
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Gradle Skill

Android Gradle build configuration for Castle payment terminal development. This project uses Gradle 8.5 with AGP 7.4.2, targeting SDK 31 with Java 8 compatibility. The build must handle 27 Castle SDK JARs, requiring multidex and careful memory configuration.

## Quick Start

### Build Commands

```bash
# Standard debug build
./gradlew assembleDebug

# Clean build (required after SDK JAR changes)
./gradlew clean build

# Run unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "castech.emvtxn.atm.host.HyosungProtocolTest"
```

### Project Structure

| File | Purpose |
|------|---------|
| `build.gradle` (root) | Project-level config, Gradle plugin version |
| `app/build.gradle` | App config, SDK versions, dependencies |
| `gradle.properties` | JVM memory, build flags |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle version (8.5) |

## Key Configuration

### WARNING: Gradle Version Lock

**NEVER use Gradle 9.0**. The project requires exactly Gradle 8.5:

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
```

Gradle 9.0-milestone-1 causes "Cannot find symbol: module()" errors.

### Memory Settings

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.enableDexingArtifactTransform=false
```

### SDK Configuration

```groovy
// app/build.gradle
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
```

## Common Patterns

### Adding Local JAR Dependencies

Castle SDK JARs live in `app/libs/`:

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation 'androidx.multidex:multidex:2.0.1'
}
```

### Resolving Package Conflicts

```groovy
android {
    packagingOptions {
        resources {
            excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE', 
                        'META-INF/LICENSE.txt', 'META-INF/NOTICE',
                        'META-INF/NOTICE.txt']
        }
    }
}
```

## See Also

- [patterns](references/patterns.md) - Build configuration patterns
- [workflows](references/workflows.md) - Build and deploy workflows

## Related Skills

- See the **android** skill for Android-specific configuration
- See the **java** skill for Java 8 compatibility requirements
- See the **castle-sdk** skill for SDK JAR integration