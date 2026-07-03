# ProGuard Mapping Files Guide

## Overview
This project uses ProGuard/R8 code shrinking and obfuscation for release builds. When crashes occur, stack traces contain obfuscated class/method names. Mapping files allow deobfuscation of these stack traces.

## Configuration

### ProGuard Settings (`app/build.gradle.kts`)
```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        ndk {
            debugSymbolLevel = "FULL"  // Full debug symbols for native crashes
        }
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

### Line Number Preservation (`app/proguard-rules.pro`)
```proguard
-keepattributes SourceFile,LineNumberTable
```

## Mapping File Locations

After a release build, mapping files are generated at:
```
app/build/outputs/mapping/release/
```

Files included:
- `mapping.txt` - Main mapping file (obfuscated -> original names)
- `dump.txt` - Class structure dump
- `seeds.txt` - List of non-obfuscated classes and members
- `usage.txt` - List of removed classes and members

## Upload to Google Play Console

### Step 1: Build the Release AAB
```bash
./gradlew bundleRelease
```

### Step 2: Upload Mapping Files

1. Go to [Google Play Console](https://play.google.com/console/)
2. Select your app
3. Navigate to **Release** -> **Setup** -> **App integrity**
4. Under **ProGuard and R8 configuration**, click **Upload mapping files**
5. Upload the `mapping.txt` file from `app/build/outputs/mapping/release/`

### Alternative: Upload via Play Developer API
```bash
# Using the Play Developer API or Fastlane supply
```

### Step 3: Upload Native Debug Symbols (if applicable)

The `debugSymbolLevel = "FULL"` setting generates full debug symbols for native code crashes:
- Native debug symbols are packaged with the AAB
- Google Play automatically processes them for deobfuscation

## Automated Upload with Gradle Plugin

Add the Google Play Gradle Plugin for automatic mapping file upload:

```kotlin
// Top-level build.gradle.kts
plugins {
    id("com.android.application") version "9.2.1"
    id("com.google.android.gms.oss-licenses-plugin") version "0.10.6"
}
```

In `app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    id("com.google.android.play.app-bundle-validation") // Optional validation
}

// Add Google Play Gradle Plugin for automatic upload
dependencies {
    // Use the Play Plugin for automatic mapping upload
}
```

## Viewing Deobfuscated Crashes

After uploading mapping files:
1. Go to **Release** -> **Crashes** (or **ANRs**)
2. Crashes will automatically be deobfuscated using the uploaded mapping files
3. Stack traces will show original class/method names

## Best Practices

1. **Version Control**: Consider committing mapping files for each version to version control
   - Create a `mapping/` directory in your project root
   - Add to `.gitignore` with versioned subdirectories: `mapping/*/`

2. **Backup**: Always backup mapping files before building new versions

3. **Keep Important Classes**: Add rules to prevent obfuscation of:
   - Models/DTOs used for serialization
   - Reflection-based classes
   - Parcelable classes

## Example ProGuard Rules for This Project

Add to `app/proguard-rules.pro`:
```proguard
# Keep YouTube model classes for JSON deserialization
-keep class com.turbolego.songguesser.** { *; }

# Keep Parcelable classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Retrofit/OkHttp model classes
-keep class com.turbolego.songguesser.** { *; }
```

## Troubleshooting

### "No mapping file found" Error
- Ensure `isMinifyEnabled = true` in release build type
- Check that the build completed successfully
- Verify mapping files exist in `app/build/outputs/mapping/release/`

### Stack Traces Still Obfuscated
- Verify mapping file was uploaded to Play Console
- Check that the crash is from the same app version as the mapping file
- Ensure the mapping file matches the exact build that was released

### Native Crash Symbols Not Found
- `debugSymbolLevel = "FULL"` generates `.sym.so` files
- These are automatically included in the AAB
- Verify the AAB contains the symbol files