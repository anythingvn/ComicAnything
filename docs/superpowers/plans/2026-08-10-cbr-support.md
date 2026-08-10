# CBR (RAR) Comic Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local `.cbr` files actually openable — `ComicFormat.CBR` already exists and is wired through the rest of the app (scanning, search, filter chips, cover-thumbnail fallback icon), but `createPageSource` has no `CBR` branch, so tapping one always shows "This format isn't supported yet." This plan adds a `CbrPageSource` and wires it in — per [docs/superpowers/specs/2026-08-10-cbr-support-design.md](../specs/2026-08-10-cbr-support-design.md).

**Architecture:** `CbrPageSource` extracts the whole RAR archive to a per-comic temp directory once (mirroring Epic 5's already-reviewed `EpubExtractor` pattern, since RAR's possible "solid" compression means true random-access-without-full-extraction isn't guaranteed the way ZIP's central directory guarantees for CBZ), then serves pages by reading the extracted files directly off disk. It implements the existing `ComicPageSource` interface and flows through the *existing* `PageBitmapCache`/`ReaderViewModel.pageCache` machinery exactly like `PdfPageSource`/`CbzPageSource` already do — no new `ReaderViewModel` state, no new UI screen. The one integration point `createPageSource` needs (a cache directory to extract into) is threaded through as a lazily-evaluated `() -> File` parameter, so PDF/CBZ opens (which never need it) never touch `Application.cacheDir` and existing tests for those formats are unaffected.

**Tech Stack:** Kotlin, `com.github.junrar:junrar:8.1.0` (new dependency — verified via direct POM inspection: its only dependency is `org.slf4j:slf4j-api:2.0.17` at runtime scope, no Kotlin dependency at all, no conflict with this project's Kotlin Gradle Plugin 1.9.22), `android.graphics.BitmapFactory` (already used by `CbzPageSource`).

## Global Constraints

- `com.github.junrar:junrar:8.1.0` is the exact version to add — confirmed current, confirmed dependency-safe via direct POM inspection.
- The real junrar 8.1.0 API (verified via direct bytecode inspection with `javap`, not assumed from documentation, which turned out to describe a different/older method name): `com.github.junrar.Archive` — constructor `Archive(file: File)`, implements `Closeable` and `Iterable<FileHeader>`, methods `nextFileHeader(): FileHeader?` and `extractFile(header: FileHeader, out: OutputStream)`. `com.github.junrar.rarfile.FileHeader` — `fileName: String` (Kotlin property for `getFileName()`; **not** `fileNameString`, which doesn't exist on this version despite some documentation/search results claiming otherwise) and `isDirectory: Boolean`. `fileName` is verified (by direct test, not assumed) to always return `/`-separated paths regardless of the archive's origin OS, even for a WinRAR-on-Windows-produced archive whose internal storage uses `\`.
- Real CBR archives commonly wrap their pages in a subdirectory (e.g. `MyComic/page001.jpg`, not just flat `page001.jpg` at the archive root) — this is a genuine, common real-world shape, not an edge case, and extraction/listing must handle it correctly (recursive directory walk after extraction, not a single-level listing).
- The Zip-Slip path-traversal guard already reviewed and hardened for `EpubExtractor.kt` (Epic 5) must be applied to RAR extraction from the first version — canonical-path validation before writing each entry, skipping (not aborting on) any entry that would escape the extraction directory. This cannot be proven via an automated test in this environment (no available tool to construct a deliberately malicious `.cbr` — unlike ZIP, which `java.util.zip.ZipOutputStream` can encode with an arbitrary, even malicious, entry name directly; RAR has no equivalent pure-JDK writer, and legitimate archiving tools don't let you inject a `../`-laden entry name through their normal interface) — this is a real, honest testing limitation to document, not silently skip.
- Two real `.cbr` test fixtures already exist at `app/src/test/resources/sample.cbr` (flat layout: `page1.jpg`, `page2.jpg`, `page10.jpg`, `ComicInfo.xml`) and `app/src/test/resources/nested.cbr` (wrapped in a `MyComic/` subdirectory: `page1.jpg`, `page2.jpg`, `notes.txt`, plus a directory entry for `MyComic` itself) — both built with WinRAR 6.21 (RAR5 format) and already verified (via a throwaway smoke test, since discarded) to be correctly readable by junrar 8.1.0, including confirming the exact byte content of an extracted entry matches what was written. Use these fixtures as-is; do not rebuild them.
- `createPageSource`'s new `cbrCacheRoot: () -> File` parameter must only be invoked from inside the `ComicFormat.CBR` branch of its `when`, not evaluated eagerly for every call — this is what keeps PDF/CBZ opens (and every existing PDF/CBZ-only test) from needing to touch `Application.cacheDir` at all, avoiding the exact "eager Context access breaks plain-JVM tests" trap this project hit twice already (Epic 4/7's repository defaults, Epic 5's `epubCacheRoot`).
- No mocking library is used in this project — tests use real objects.
- Existing package root: `com.comicanything.reader`.

---

### Task 1: `CbrPageSource.kt` — extraction + page decode + tests

**Files:**
- Modify: `app/build.gradle.kts` (add the `junrar` dependency)
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/CbrPageSource.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/pagesource/CbrPageSourceTest.kt`
- Add (already built and verified, currently untracked in the worktree — commit as-is): `app/src/test/resources/sample.cbr`, `app/src/test/resources/nested.cbr`

**Interfaces:**
- Produces: `class CbrPageSource(file: File, extractionDir: File) : ComicPageSource` — consumed by Task 2's `createPageSource`.
- Produces: `internal fun listImagePageFilesSorted(dir: File): List<File>` — internal, not private, in case a future task needs direct test access to it (mirrors the `internal` visibility precedent from `calculateThumbnailSampleSize`/`filtered()` elsewhere in this codebase, where a file-`private` top-level function would be invisible to a separate test file).

- [ ] **Step 1: Add the `junrar` dependency**

In `app/build.gradle.kts`, add this line to the `dependencies` block, alongside the other `implementation(...)` entries (e.g. near the Play Services line):

```kotlin
    implementation("com.github.junrar:junrar:8.1.0")
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/pagesource/CbrPageSourceTest.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CbrPageSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fixture(name: String): File =
        File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test
    fun `lists and counts pages from a flat-layout CBR, excluding non-image entries`() {
        val source = CbrPageSource(fixture("sample.cbr"), tempFolder.newFolder("flat-${System.nanoTime()}"))

        // sample.cbr has page1.jpg, page2.jpg, page10.jpg, and ComicInfo.xml (non-image,
        // must be excluded) -- so pageCount must be exactly 3, not 4.
        assertEquals(3, source.pageCount)
        source.close()
    }

    @Test
    fun `pages wrapped in a subdirectory are still found via recursive listing`() {
        // nested.cbr wraps its pages in MyComic/ -- a common real-world CBR shape. A
        // single-level (non-recursive) directory listing after extraction would find zero
        // pages here; this is exactly the class of bug this plan's Global Constraints call out.
        val source = CbrPageSource(fixture("nested.cbr"), tempFolder.newFolder("nested-${System.nanoTime()}"))

        assertEquals(2, source.pageCount)
        source.close()
    }

    @Test
    fun `close deletes the extraction directory`() {
        val extractionDir = tempFolder.newFolder("cleanup-${System.nanoTime()}")
        val source = CbrPageSource(fixture("sample.cbr"), extractionDir)
        assertTrue(extractionDir.listFiles()?.isNotEmpty() == true)

        source.close()

        assertTrue(!extractionDir.exists())
    }

    @Test
    fun `re-extracting into the same directory clears any prior extraction first`() {
        val extractionDir = tempFolder.newFolder("reextract-${System.nanoTime()}")
        File(extractionDir, "stale-leftover-file.txt").writeText("should be gone after re-extraction")

        val source = CbrPageSource(fixture("sample.cbr"), extractionDir)

        assertTrue(!File(extractionDir, "stale-leftover-file.txt").exists())
        source.close()
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.CbrPageSourceTest" --no-daemon
```

Expected: FAIL — `CbrPageSource.kt` doesn't exist yet, so this won't compile.

- [ ] **Step 4: Implement `CbrPageSource.kt`**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/CbrPageSource.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val CBR_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

internal fun listImagePageFilesSorted(dir: File): List<File> {
    return dir.walkTopDown()
        .filter { file ->
            file.isFile &&
                !file.path.contains("__MACOSX") &&
                !file.name.startsWith(".") &&
                file.extension.lowercase() in CBR_IMAGE_EXTENSIONS
        }
        .sortedWith(compareBy(naturalOrderComparator()) { it.name })
        .toList()
}

class CbrPageSource(file: File, extractionDir: File) : ComicPageSource {
    private val extractionDirectory = extractionDir
    private val pageFiles: List<File>

    init {
        if (extractionDir.exists()) extractionDir.deleteRecursively()
        extractionDir.mkdirs()
        val canonicalExtractionDir = extractionDir.canonicalPath

        Archive(file).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val outFile = File(extractionDir, header.fileName)
                    val canonicalOutFile = outFile.canonicalPath
                    if (canonicalOutFile.startsWith(canonicalExtractionDir + File.separator)) {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> archive.extractFile(header, out) }
                    }
                    // else: entry's resolved path escapes the extraction directory (Zip Slip
                    // / path traversal) -- skip this one entry rather than aborting the whole
                    // extraction, matching EpubExtractor.kt's already-reviewed guard.
                }
                header = archive.nextFileHeader()
            }
        }

        pageFiles = listImagePageFilesSorted(extractionDir)
    }

    override val pageCount: Int get() = pageFiles.size

    override suspend fun getPage(page: Int): Bitmap = withContext(Dispatchers.IO) {
        try {
            val pageFile = pageFiles[page - 1]
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pageFile.absolutePath, boundsOptions)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(boundsOptions.outWidth, TARGET_WIDTH_PX)
            }
            BitmapFactory.decodeFile(pageFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("decodeFile returned null")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PageDecodeException(page, e)
        } catch (e: Exception) {
            throw PageDecodeException(page, e)
        }
    }

    override fun close() {
        extractionDirectory.deleteRecursively()
    }

    companion object {
        private const val TARGET_WIDTH_PX = 1080
    }
}
```

Note: `calculateThumbnailSampleSize` (from `ThumbnailDecoder.kt`) and `naturalOrderComparator` (from `CbzPageSource.kt`) are both already `internal`/public in the same package `com.comicanything.reader.data.pagesource` — no new import needed, matching how `CbzPageSource.kt` itself already calls `calculateThumbnailSampleSize` directly (confirmed by reading the current file before writing this plan).

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.CbrPageSourceTest" --no-daemon
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/comicanything/reader/data/pagesource/CbrPageSource.kt app/src/test/java/com/comicanything/reader/data/pagesource/CbrPageSourceTest.kt app/src/test/resources/sample.cbr app/src/test/resources/nested.cbr
git commit -m "feat: add CbrPageSource for RAR archive extraction and page decode"
```

---

### Task 2: Wire CBR into `createPageSource` and `ReaderViewModel`

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `class CbrPageSource(file: File, extractionDir: File) : ComicPageSource` (Task 1).
- Produces: `fun createPageSource(comic: ComicItem, cbrCacheRoot: () -> File): ComicPageSource` (signature change — the `cbrCacheRoot` parameter is new) — consumed by `ReaderViewModel.openComic`'s existing call site.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, inside the `ReaderViewModelTest` class (add these as new `@Test` methods; do not modify any existing test):

```kotlin
    @Test
    fun `opening a CBR comic decodes pages via the real junrar-backed pipeline`() = runTest {
        val fixtureBytes = javaClass.classLoader!!.getResourceAsStream("sample.cbr")!!.readBytes()
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbr")
        comicFile.writeBytes(fixtureBytes)
        val cbrExtractionRoot = tempFolder.newFolder("cbr-cache-${System.nanoTime()}")
        val comic = ComicItem(
            id = "cbr-comic",
            title = "Test CBR",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBR
        )
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            cbrCacheRoot = { cbrExtractionRoot }
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // sample.cbr has 3 real pages (page1.jpg, page2.jpg, page10.jpg -- ComicInfo.xml
        // excluded). This proves the real junrar extraction + CbrPageSource + PageBitmapCache
        // pipeline is genuinely wired together, not just that some mock returned a value.
        assertEquals(3, viewModel.uiState.value.totalPages)
        assertNull(viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `opening a PDF or CBZ comic never touches cbrCacheRoot`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var cbrCacheRootCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            cbrCacheRoot = { cbrCacheRootCallCount++; tempFolder.newFolder("should-not-be-used-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "cbz-comic",
            title = "Test CBZ",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // This proves cbrCacheRoot is genuinely lazy -- opening a non-CBR comic must never
        // evaluate it, which is what lets every existing PDF/CBZ test in this file keep
        // constructing ReaderViewModel without supplying a fake cbrCacheRoot at all.
        assertEquals(0, cbrCacheRootCallCount)
    }
```

These two tests need `import java.io.File` (already imported in this test file — confirm before assuming) and no other new imports.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `cbrCacheRoot` constructor parameter doesn't exist yet, `createPageSource`'s signature doesn't match.

- [ ] **Step 3: Implement the changes**

In `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`, change `createPageSource` from:

```kotlin
fun createPageSource(comic: ComicItem): ComicPageSource {
    if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(comic.format)
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(File(comic.pathOrUrl))
        ComicFormat.CBZ -> CbzPageSource(File(comic.pathOrUrl))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
```

to:

```kotlin
fun createPageSource(comic: ComicItem, cbrCacheRoot: () -> File): ComicPageSource {
    if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(comic.format)
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(File(comic.pathOrUrl))
        ComicFormat.CBZ -> CbzPageSource(File(comic.pathOrUrl))
        ComicFormat.CBR -> CbrPageSource(File(comic.pathOrUrl), File(cbrCacheRoot(), comic.id))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
```

`cbrCacheRoot()` is only called inside the `ComicFormat.CBR` branch — Kotlin's `when` only evaluates the matched branch's expression, so PDF/CBZ opens genuinely never invoke it. This is the mechanism that keeps `cbrCacheRoot` lazy per the Global Constraints.

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add a new constructor parameter alongside `epubCacheRoot` (currently the last parameter):

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub,
    private val epubCacheRoot: () -> File = { File(application.cacheDir, "epub_temp") },
    private val cbrCacheRoot: () -> File = { File(application.cacheDir, "cbr_temp") }
) : AndroidViewModel(application) {
```

And update `openComic`'s existing call site (currently `withContext(ioDispatcher) { createPageSource(comic) }`) to:

```kotlin
                withContext(ioDispatcher) { createPageSource(comic, cbrCacheRoot) }
```

Note `cbrCacheRoot` is passed as the function reference itself (not called as `cbrCacheRoot()`), so evaluation genuinely stays deferred until `createPageSource` decides whether to call it.

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS (all existing tests plus the 2 new ones).

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass, including Task 1's `CbrPageSourceTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: wire CBR into createPageSource and ReaderViewModel"
```

---

### Task 3: End-to-end manual verification on the emulator

**Files:** none (verification only), plus `PROJECT_TASKS.md`

**Interfaces:** none — this task exercises Tasks 1-2 together as a user would.

- [ ] **Step 1: Build a real multi-page test CBR file**

Build a small, realistic CBR using WinRAR's command-line tool (already confirmed present on this machine at `C:\Program Files\WinRAR\Rar.exe`), with pages wrapped in a subdirectory (the common real-world shape this plan's Global Constraints specifically call out) — do NOT just reuse the tiny unit-test fixtures verbatim, since those contain plain-text "page" content, not real images; build one with real small JPEG images this time so on-device rendering can be visually confirmed:

```bash
mkdir -p /tmp/cbr_verify/TestComic
cd /tmp/cbr_verify/TestComic
python -c "
from PIL import Image, ImageDraw
for n in [1, 2, 3, 10, 11]:
    img = Image.new('RGB', (600, 800), color=(230, 230, 230))
    draw = ImageDraw.Draw(img)
    draw.text((50, 350), f'CBR PAGE {n}', fill=(0, 0, 0))
    img.save(f'page{n}.jpg')
"
cd /tmp/cbr_verify
"/c/Program Files/WinRAR/Rar.exe" a -r test_comic.cbr TestComic
```

(Adjust the Python invocation if `Pillow` isn't already installed in this environment — `pip install Pillow` first if needed. The `[1, 2, 3, 10, 11]` page numbers deliberately test natural sort ordering the same way `sample.cbr`'s unit-test fixture does.)

- [ ] **Step 2: Install the freshly built APK and open the CBR**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" push /tmp/cbr_verify/test_comic.cbr /sdcard/Download/test_comic.cbr
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test -dns-server 8.8.8.8,8.8.4.4`, then wait for `adb shell getprop sys.boot_completed` to report `1` before proceeding. In this Git-Bash environment, prefix `adb push`/`adb shell` commands touching absolute Unix-style paths with `MSYS_NO_PATHCONV=1` if you hit `secure_mkdirs failed`/path-mangling errors, a known quirk from prior verification passes in this project.)

Tap `test_comic.cbr` in the library. Expected: a brief loading spinner (extraction takes a moment, unlike CBZ's near-instant page-1 display), then the real rendered "CBR PAGE 1" image appears — not the old "This format isn't supported yet" card. Confirm via screenshot.

- [ ] **Step 3: Verify natural page ordering and paging**

Page forward through all 5 pages. Expected: the order is 1, 2, 3, 10, 11 (natural numeric order) — not 1, 10, 11, 2, 3 (lexicographic order, which would be the bug if natural sort were broken). Confirm the scrubber correctly reads "Page N / 5" at each step. Page back to page 1.

- [ ] **Step 4: Verify reading modes and pinch-to-zoom still work for CBR**

Switch reading mode to RTL, confirm tap-to-turn direction reverses correctly (same behavior already verified for PDF/CBZ in Epic 2 — this just confirms CBR pages flow through the same `SinglePageReader` code unmodified). Switch to Webtoon mode, confirm continuous scroll works. Switch back to LTR. This step is a quick confirmation that CBR pages are indistinguishable from CBZ pages once decoded, not new-mechanism testing.

- [ ] **Step 5: Verify progress persistence and Continue Reading**

Page to page 3, wait past the 1.5s debounce, back out to the library. Expected: `test_comic.cbr` appears in "Continue Reading" showing "Page 3/5" (the real page-based label, since CBR — unlike EPUB — does populate `currentPage`/`totalPages` normally through the exact same path PDF/CBZ already use). Tap the card. Expected: reopens directly at page 3, not page 1.

- [ ] **Step 6: Verify graceful failure on a corrupt/invalid CBR**

Push a file with a `.cbr` extension that is NOT actually a valid RAR archive (`echo "not a real cbr" > fake.cbr`, then push it), tap it in the library. Expected: the same generic "Couldn't open this comic" / format-appropriate error card other formats already show for corrupt input, no crash, back button returns to the library normally. Check `adb logcat` to confirm the failure is a clean, caught exception (from junrar's `Archive` constructor or `nextFileHeader()` throwing on malformed input, caught by `openComic`'s existing generic `catch (e: Exception)` block), not an uncaught crash.

- [ ] **Step 7: Verify the extraction temp directory is cleaned up on close**

While `test_comic.cbr` is open, confirm its extraction directory exists on-device:

```bash
"$ADB" shell run-as com.comicanything.reader find cache/cbr_temp -type f
```

Back out to the library (triggering `closeComic()`). Re-run the same command. Expected: the directory (or its contents) is gone, confirming `CbrPageSource.close()`'s cleanup fired correctly through the existing `pageCache?.close()` machinery.

- [ ] **Step 8: Regression check**

Confirm nothing else broke: PDF and CBZ still open and page normally; EPUB still opens and scrolls normally (unaffected — no code shared between `CbrPageSource` and `EpubExtractor` beyond the already-reviewed Zip-Slip guard *pattern*, not shared code); the library search/format-filter chips (`CBR` is now a genuinely useful filter, not a dead option) and grid/list toggle all still work; cover thumbnails still render for PDF/CBZ while CBR correctly shows its static fallback icon (cover-thumbnail decoding was explicitly not extended to CBR by this plan); the Google Drive tab is unaffected. No `FATAL EXCEPTION`/`AndroidRuntime`/`OutOfMemoryError`/ANR entries in a full-session `adb logcat` review covering every step above.

- [ ] **Step 9: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 6 — CBR (RAR) Support`, check off all three existing bullets ("Integrate a RAR-extraction library," "Reuse the CBZ page pipeline," and the descope-decision bullet — mark the descope one as N/A/superseded since this plan chose to integrate rather than descope, following the same style Epic 5's MOBI entry used when a decision superseded an original "either/or" bullet). Add a verification note in the same style/density as the other epics' existing notes, summarizing what was tested and confirmed on `comicanything_test`, explicitly noting the one honest testing limitation (Zip-Slip guard code-reviewed but not provable via an automated malicious-fixture test, since no tool in this environment can construct one) and the wrapped-vs-flat CBR layout coverage. Update the epic's status marker from `⬜` to `✅`.

- [ ] **Step 10: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark CBR (RAR) comic support (Epic 6) complete"
```
