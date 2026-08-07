# 📱 ComicAnything - App Features & Functions Specification

**ComicAnything** is a lightweight, privacy-focused, native Android comic and ebook reader app built using **Kotlin**, **Jetpack Compose**, and Google's official **Material 3 Design System**.

It is designed specifically as a private, standalone app (compiling to an `.apk`) with **no user login required** and **no cloud user database**. It seamlessly connects to your local device storage and your private or shared Google Drive folders.

---

## 🌟 Core Features & Highlights

### 1. 🔒 100% Private & Offline-First (No Login Required)
- **Zero Registration**: Open the app and start reading immediately—no email, password, or account creation.
- **Local Persistence**: All reading history, page bookmarks, reading progress percentages, and favorite shelves are saved on-device using Android DataStore / Local Storage.

---

### 2. ☁️ Google Drive Direct Folder Integration
- **Link Specific Folders**: Paste any Google Drive shared folder link or folder ID (e.g. `https://drive.google.com/drive/folders/1ABC...`).
- **REST API v3 Querying**: Indexes PDFs, CBZ archives, EPUBs, and MOBI files directly inside the linked Google Drive directory.
- **On-Demand Streaming & Caching**: Stream comic PDF pages directly over HTTP or cache files locally for offline reading without manual downloading.

---

### 3. 📂 Device Storage Scanner
- **Auto Scanning**: Automatically detects comic and ebook files in your `/sdcard/Download/` and `/storage/emulated/0/` directories.
- **Multi-Format Support**:
  - 📄 **PDF**: Native PDF rendering with hardware acceleration.
  - 📦 **CBZ & CBR**: Comic Book Zip/Rar archive extraction.
  - 📚 **EPUB & MOBI**: Digital ebook reflowable format support.

---

### 4. 🎨 Google Material 3 Native UI/UX Design

- **Material You Dynamic Colors**: Adapts to Android system colors, Dark Mode, and **AMOLED True Black**.
- **TopAppBar**: Features app title, search bar, format filters, and layout switcher (Grid vs List view).
- **NavigationBar (Bottom Bar)** with 3 main tabs:
  1. 📚 **Library Tab**:
     - ⚡ **Continue Reading Carousel**: Top horizontal scrollbar showing recently read comics with cover thumbnail, progress bar, and 1-tap "Resume".
     - 📖 **My Bookshelf Grid**: Dynamic 2x2 grid displaying book covers, title, and format badges (`[PDF]`, `[CBZ]`, `[EPUB]`).
  2. ☁️ **Google Drive Tab**: Folder connector input box, folder index, and remote comic list.
  3. 📁 **Local Files Tab**: Device file hierarchy scanner.

---

### 5. 📖 Immersive Full-Screen Comic Reader Canvas

- **Full-Screen Immersion**: Hides Android status bar and gesture navigation bars while reading. Tap the center of the screen to reveal top and bottom control overlays.
- **4 Reading Direction Modes**:
  - 📖 **LTR (Left-to-Right)**: Standard Western comic page turn.
  - 📖 **RTL (Right-to-Left)**: Japanese Manga page turn direction.
  - 📜 **Webtoon Mode**: Continuous vertical infinite scroll for Korean webtoons/manhwa.
  - 📖 **Dual-Page Spread**: Side-by-side page layout for tablets and landscape mode.
- **✂️ Auto White-Margin Cropping**: Automatically crops out white/blank borders around comic page scans to maximize screen space.
- **Gesture Control System**:
  - **Tap Left 35%**: Previous page.
  - **Tap Right 35%**: Next page.
  - **Tap Center 30%**: Toggle top header & bottom scrubber overlay controls.
  - **Pinch-to-Zoom**: Smooth vector zoom into comic panels.
- **Color Modes & Theme Filters**:
  - Day Light Mode
  - Warm Sepia
  - Night Mode
  - AMOLED True Black
  - High Contrast Mode
- **Bottom Scrubber & Slider**: Interactive page slider with page counter (`Page 14 / 48`) and completion percentage badge (`29%`).
- **Quick Reader Settings**: Modal BottomSheet for quick adjustments to reading mode, auto-crop, and color filters on the fly.

---

## 🛠️ Technical Architecture & Technology Stack

| Layer | Component / Library | Purpose |
| :--- | :--- | :--- |
| **Language** | **Kotlin 1.9** | Official modern language for Android |
| **UI Framework** | **Jetpack Compose + Material 3** | Declarative, high-performance Android UI |
| **Architecture** | **MVVM + Clean Architecture** | `ViewModel`, `StateFlow`, `Coroutines` |
| **Networking** | **OkHttp 4** | Fetching Google Drive REST API v3 payloads |
| **Image Engine** | **Coil for Compose** | Asynchronous cover thumbnail caching |
| **Build Tool** | **Gradle 8.2 (Kotlin DSL)** | APK compilation (`.apk`) |
| **Target SDK** | **Android 14 (API 34)** | Min SDK 24 (Android 7.0+) |

---

## 🚀 How to Build & Install the APK

1. Open **Android Studio**.
2. Click **Open** and select the root directory: `D:\Source Code\ComicAnything`.
3. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)** in the top menu bar.
4. Locate your output APK in `app/build/outputs/apk/debug/app-debug.apk`.
5. Install directly on any Android device!
