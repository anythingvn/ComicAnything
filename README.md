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
1. Open this directory (`D:\Source Code\ComicAnything`) in **Android Studio**.
2. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)** to generate your private `.apk`.
3. See [APP_FEATURES_AND_FUNCTIONS.md](APP_FEATURES_AND_FUNCTIONS.md) for full details.
