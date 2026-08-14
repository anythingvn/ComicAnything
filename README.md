# ComicAnything - Native Android Comic & eBook Reader

ComicAnything is a modern, standalone Native Android comic and ebook reader app built with **Kotlin**, **Jetpack Compose**, and **Google Material 3**.

## Project Root Structure

```
ComicAnything/
├── APP_FEATURES_AND_FUNCTIONS.md   # Complete feature & function summary
├── build.gradle.kts                # Project-level Gradle build script
├── settings.gradle.kts             # Gradle settings & module registration
├── app/                            # Android Application module
│   ├── build.gradle.kts            # App dependencies (Compose, Material 3, Coil, OkHttp)
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/com/comicanything/reader/
│               ├── MainActivity.kt
│               ├── data/
│               │   ├── model/ComicItem.kt
│               │   ├── repository/GoogleDriveRepository.kt
│               │   └── repository/LocalFileRepository.kt
│               └── ui/
│                   ├── theme/Theme.kt
│                   ├── home/HomeScreen.kt
│                   └── reader/
│                       ├── ReaderScreen.kt
│                       └── ReaderViewModel.kt
```

## Quick Start

### Option A: Android Studio
1. Open this directory (`D:\Source Code\ComicAnything`) in **Android Studio**.
2. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)** to generate your private `.apk`.
3. Run the app on a connected device/emulator with the **Run** button, or install the generated APK from `app/build/outputs/apk/debug/`.

### Option B: Command line
Requires the Android SDK installed with its location set in `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME` environment variable.

```bash
# Build a debug APK
./gradlew assembleDebug

# Install it on a connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run the test suite
./gradlew test
```

A signed release build (`./gradlew assembleRelease`) requires a release keystore — see `app/build.gradle.kts` for the `local.properties` keys (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) it reads. Without them, `assembleRelease` still succeeds but produces an unsigned APK.

See [APP_FEATURES_AND_FUNCTIONS.md](APP_FEATURES_AND_FUNCTIONS.md) for full feature details.
