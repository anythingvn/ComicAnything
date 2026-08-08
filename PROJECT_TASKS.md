# ComicAnything - Project Task Tracker

Epic → task breakdown of the work remaining to take ComicAnything from its
current UI scaffold to the app described in
[APP_FEATURES_AND_FUNCTIONS.md](APP_FEATURES_AND_FUNCTIONS.md).

**Status legend:** ⬜ not started · 🟨 partial / needs rework · ✅ done

**Current state (as of 2026-08-07):** Compose/Material 3 shell exists
(Home tabs, Reader chrome, ViewModel, both repositories). The reader does
not render actual pages yet, and there is no persistence layer. Epic 0
(build system) is now verified working via a clean-room Docker build.
Ordering below is dependency order, not just priority — do Epic 1 before
anything that assumes the app can read files on a real device.

---

## Epic 0 — Build System Foundations ✅
*Verified 2026-08-07 via a clean JDK17 + Android SDK 34 Docker container (no Android Studio, no host-installed SDK) — `./gradlew assembleDebug` → `BUILD SUCCESSFUL`, produced `app/build/outputs/apk/debug/app-debug.apk` (16.6MB).*

- [x] Add Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`) so the project builds from the CLI, not just inside Android Studio — pulled from the official Gradle 8.4.0 release (matches AGP 8.2.2)
- [x] Add `app/proguard-rules.pro` — referenced by `app/build.gradle.kts:27-28` (`release` build type) but the file doesn't exist; release builds will fail to configure
- [x] Add launcher icon resources (`res/mipmap-*/ic_launcher.png` + round variant) — `AndroidManifest.xml:17-19` references `@mipmap/ic_launcher`/`ic_launcher_round` but no `mipmap` resource directory existed; generated for mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi (placeholder art — revisit in Epic 9 branding pass)
- [x] Add `gradle.properties` with `android.useAndroidX=true` — **discovered during verification, not in the original list.** The project has AndroidX dependencies but no `gradle.properties` at all; build failed with `Configuration :app:debugRuntimeClasspath contains AndroidX dependencies, but android.useAndroidX property is not enabled` until this was added
- [x] Run `./gradlew assembleDebug` and confirm a clean build succeeds end-to-end

## Epic 1 — Runtime Permissions & Local Storage Wiring ✅
*Blocking for Epic 2/7 on-device: without granted permissions, local scanning shows a "Grant Access" prompt instead of the library.*
*Verified 2026-08-07 end-to-end on the `comicanything_test` emulator: fresh install, Library and Local Files tabs both show the "Storage access needed" card pre-grant, granting `MANAGE_EXTERNAL_STORAGE` via `appops` makes a real pushed file (`test.pdf`) appear tagged `PDF` on both tabs, and revoking the permission clears the library and brings the permission card back on both tabs. No crashes or errors in logcat during the run.*

- [x] Add a runtime permission request flow in `MainActivity.kt` (API 30+: `MANAGE_EXTERNAL_STORAGE` via Settings redirect; API 24-29: `READ_EXTERNAL_STORAGE` via standard runtime dialog; dropped the unused `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` permissions from the manifest since they don't cover PDF/CBZ/EPUB/MOBI)
- [x] Add a "permission denied" / "grant access" state to the Local Files tab UI instead of failing silently
- [x] Make `LocalFileRepository.scanStorageDirectories` scan more than the single hardcoded `/storage/emulated/0/Download` path (spec calls out `/sdcard/Download/` and `/storage/emulated/0/` broadly) — recurse or let the user pick additional folders
- [x] Replace the static placeholder in `LocalFilesContent` ([HomeScreen.kt:312-320](app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt:312-320)) with a real list/grid bound to scan results, matching the Library tab's card style

## Epic 2 — Core Page Rendering Engine ✅ (done for now — 2 items deliberately deferred)
*Page-rendering-core sub-project verified 2026-08-07 end-to-end on the `comicanything_test` emulator: built a real 5-page PDF and an 11-page CBZ (natural-sort test names `page1.jpg`...`page11.jpg`) with Python/Pillow, pushed them to `/sdcard/Download/`, and confirmed in the reader — the PDF opens showing the real rendered first page (not a placeholder) with the scrubber correctly reading "Page 1 / 5"; the CBZ pages forward in correct natural order (page9 → page10 → page11, not page1 → page10); paging all 11 CBZ pages forward then back to page 1 (exercising the 5-page LRU cache's eviction/re-decode past its capacity) produced no crash and page 1 still rendered correctly on return, confirmed via `adb logcat` showing no `FATAL EXCEPTION`/`AndroidRuntime` traces; and opening a `sample.epub` (unsupported format) showed the "This format isn't supported yet" error card with no crash, with the back button correctly returning to the library. Reading modes, pinch-to-zoom, color filters, and auto-crop remain unimplemented (separate sub-projects).*
*Reading-modes-and-gestures sub-project verified 2026-08-08 end-to-end on the `comicanything_test` emulator with a fresh 14-page labeled CBZ: RTL tap reversal confirmed both directions (right=backward, left=forward) and confirmed flipping back correctly when switching back to LTR; Dual-Spread pairing confirmed page 1 stands alone as a cover, then 2-3, then 4-5 (not 3-4), and back down again; Webtoon confirmed as one continuous scroll with scroll-driven "Page X / Y" tracking that correctly re-syncs as you scroll (dragging the bottom scrubber to a distant page updates the counter but does not itself scroll the list — a documented gap, not a hard requirement per the plan); the regression pass (LTR tap-to-turn, center-tap bar toggle, back button, unsupported-format error card) all passed with no crashes in `adb logcat`. Pinch-to-zoom's pan-handling code path (shared by LTR/RTL and Dual-Spread) was exercised via a single-finger drag with no crash, but the zoom/scale gesture itself could not be driven through `adb input` (no multi-touch pinch primitive) — that part is code-reviewed only, not independently verified on this pass.*
*Decided 2026-08-08: color filter modes and auto white-margin cropping (the two items below) are deliberately deferred to a future pass rather than a blocking part of Epic 2 — the epic is being treated as functionally complete without them for now.*

- [x] PDF page rendering using `android.graphics.pdf.PdfRenderer` (native API, no new dependency) — render page N to a `Bitmap` on a background thread
- [x] CBZ page extraction via `java.util.zip.ZipFile`/`ZipInputStream`, sorted naturally by filename, decoded to `Bitmap`
- [x] Page prefetch/LRU bitmap cache so paging forward/back doesn't re-decode every tap
- [x] Replace the placeholder `Card` in `ReaderScreen.kt:75-109` with the real rendered page (Coil `AsyncImage` or raw `Image(bitmap=...)`)
- [x] Make reading modes act on real pages: LTR/RTL page order, Webtoon continuous vertical scroll (`LazyColumn` of pages), Dual-page spread (two `Bitmap`s side by side)
- [x] Pinch-to-zoom gesture on the real page image
- [ ] **Deferred:** Apply color filter modes (Sepia/Night/AMOLED/High-Contrast) as a `ColorMatrix`/`BlendMode` over the real page instead of just tinting an empty background
- [ ] **Deferred:** Implement auto white-margin cropping: detect near-white border pixels on a decoded `Bitmap` and crop before display (currently just a UI toggle with no effect)

## Epic 3 — Local Persistence Layer ✅
*Spec claims "all reading history, bookmarks, progress saved on-device using DataStore" — this now exists; state survives process death.*
*Verified 2026-08-08 end-to-end on the `comicanything_test` emulator: opened a real 11-page CBZ, paged forward to page 4, waited past the 1.5s debounce window, force-stopped and relaunched — the Library's "Continue Reading" card correctly showed "sample.cbz, Page 4/11" (the real page count, fixing the long-standing "Page N/1" bug). Reopened, paged to page 5, and immediately (well under 1s, before the debounce would fire) tapped back and force-stopped — page 5 still survived on relaunch, confirming `closeComic`'s immediate flush beats the debounce in a real quick-exit scenario. Tapped the favorite/bookmark icon, confirmed via the raw persisted DataStore file (`run-as` + `cat files/datastore/reading_progress.preferences_pb`) that `isFavorite` flipped to `true` and was written to disk immediately; force-stopped and relaunched, and the bookmark icon correctly rendered filled/amber on the fresh cold start. No crashes in `adb logcat` across the whole session. Regression pass (library scanning, permission gating, single-page reading mode, back navigation) all still working.*
*Found during verification (pre-existing, not introduced by this epic): the favorite icon does not visually update within the same live reader session when tapped — `toggleFavorite` mutates `ComicItem.isFavorite` in place without emitting a new `_uiState` value, so Compose has no signal to recompose the icon. The underlying toggle and persistence are both correct (confirmed via the raw DataStore file and via a fresh relaunch rendering it correctly), but a user tapping the button mid-session sees no immediate feedback. This bug already existed in the original `ReaderScreen.kt:134`-style direct mutation before Epic 3 — worth a small follow-up fix (route the favorite flag through `_uiState` or wrap `ComicItem` fields in Compose state) but not blocking, since the actual persistence requirement this epic set out to build works correctly.*

- [x] Add `androidx.datastore:datastore-preferences` dependency
- [x] Persist per-comic reading state (`currentPage`, `progressPercentage`, `lastReadTimestamp`) keyed by comic id
- [x] Persist favorites/bookmarks (currently `ComicItem.isFavorite` is toggled in `ReaderScreen.kt:134` but never saved)
- [x] On app launch, merge persisted state into freshly-scanned `ComicItem`s (scan gives you files; DataStore gives you progress) instead of relying on in-memory demo data
- [x] Verify progress survives an app kill + relaunch

## Epic 4 — Google Drive Integration Completion 🟨
*Repository has real Drive REST v3 querying, but it's unreachable from the UI.*

- [ ] Add an API key input (settings screen or inline field) — `HomeScreen.kt:99` calls `viewModel.fetchDriveFolder(driveUrlInput)` with **no** `apiKey` argument, so `GoogleDriveRepository` always falls through to demo data (`GoogleDriveRepository.kt:78-91`)
- [ ] Persist the API key locally (DataStore, from Epic 3) so the user enters it once
- [ ] Implement on-demand streaming/caching of the actual Drive file bytes (currently only a `webContentLink` URL is stored — no download, no local cache, no offline read path)
- [ ] Add error/empty states for an invalid folder ID, network failure, or missing/invalid API key (currently any failure just silently falls back to the one sample PDF)

## Epic 5 — EPUB & MOBI Support ⬜
*Formats declared in the enum and spec, zero implementation.*

- [ ] Choose and integrate an EPUB parsing/rendering approach (e.g. a WebView-based reflow renderer, or a library such as Readium)
- [ ] Build a reflowable text reader screen distinct from the paged-image `ReaderScreen` (EPUB isn't page-image based)
- [ ] Decide MOBI scope: integrate a MOBI parser, or explicitly descope it from the spec/enum if not pursuing — don't leave it silently broken

## Epic 6 — CBR (RAR) Support ⬜
- [ ] Integrate a RAR-extraction library (e.g. `junrar`) — no zip-like stdlib option exists for RAR on Android
- [ ] Reuse the CBZ page pipeline from Epic 2 once pages are extracted to a temp dir
- [ ] If RAR licensing/size isn't worth it, formally descope CBR from `ComicFormat` and the spec instead of leaving a dead enum value

## Epic 7 — Library/Home UX Completion 🟨
- [ ] Wire up the search bar (spec's TopAppBar claims search; `HomeScreen.kt` topBar has no search field at all)
- [ ] Add format filter chips (`[PDF]`, `[CBZ]`, `[EPUB]`) to filter the bookshelf grid
- [ ] Add Grid vs List layout switcher (spec claims it; only grid exists)
- [ ] Real cover thumbnails: render the first page (via Epic 2's PDF/CBZ pipeline) or load `coverUrl`/Drive `thumbnailLink` through Coil, replacing the static book icon in both grid and carousel cards
- [ ] Confirm "Continue Reading" carousel resume tap opens the reader at the correct persisted page (depends on Epic 3)

## Epic 8 — Testing & Quality ⬜
- [x] Unit tests for `LocalFileRepository` (format detection, recursive scan, empty/nonexistent-dir handling) — done in Epic 1
- [ ] Unit tests for `GoogleDriveRepository` (folder-ID extraction, JSON parsing, fallback behavior)
- [ ] Unit tests for `ReaderViewModel` state transitions (`setPage` clamping, mode/filter toggles) — permission-grant/revoke/refresh transitions already covered in Epic 1; this item now covers the remaining reader-state methods
- [ ] Instrumented Compose UI test for reader tap-zone navigation (left/right/center regions)
- [ ] Manual QA pass on a physical device or emulator covering: local scan with real files, Drive folder with a real API key, all 4 reading modes, all 5 color filters

## Epic 9 — Release Prep ⬜
- [ ] Finalize app icon/branding assets (depends on Epic 0's launcher icon task)
- [ ] Decide versioning strategy (currently hardcoded `versionCode = 1`, `versionName = "1.0.0"` in `app/build.gradle.kts:14-15`)
- [ ] Configure signed release build (keystore, signing config) — release build type currently has no signing config at all
- [ ] Verify the README's "Build & Install" steps work end-to-end on a clean checkout
