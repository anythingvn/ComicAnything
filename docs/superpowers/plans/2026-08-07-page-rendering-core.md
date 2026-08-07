# Comic Page Rendering Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the reader's static placeholder card with real, cached, on-demand rendering of local PDF and CBZ comic pages (Epic 2, sub-project 1 of 3, per [PROJECT_TASKS.md](../../../PROJECT_TASKS.md)).

**Architecture:** A new `ComicPageSource` interface with `PdfPageSource` (`android.graphics.pdf.PdfRenderer`) and `CbzPageSource` (`java.util.zip.ZipFile`) implementations, wrapped by a fixed-size LRU `PageBitmapCache` with neighbor prefetch. `ReaderViewModel` builds a page source via a `createPageSource` factory when a comic opens, decodes pages into new `ReaderUiState` fields, and `ReaderScreen` renders the real bitmap (or a loading/error state) instead of the placeholder.

**Tech Stack:** Kotlin, Jetpack Compose, `android.graphics.pdf.PdfRenderer`, `java.util.zip`, kotlinx-coroutines.

## Global Constraints

- Min SDK 24, target/compile SDK 34 (`app/build.gradle.kts`)
- Kotlin 1.9.22, no new production dependencies — `PdfRenderer` and `java.util.zip` are both part of the Android/JDK standard library already available to this project
- No mocking library — tests use real objects (temp files/zips, constructor-injected fakes), not mocks
- Existing package root: `com.comicanything.reader`
- Local files only in this plan (`ComicItem.source == ComicSource.LOCAL`); Google Drive comics and unsupported formats (EPUB/MOBI/CBR) show an inline "not yet supported" error, never a crash
- `Bitmap`/`PdfRenderer`/`ZipFile`-touching code has no JVM unit test in this project (no Robolectric) — verified manually on the `comicanything_test` emulator instead. Pure-Kotlin logic (natural sort, ViewModel state transitions that don't require a real `Bitmap`) IS unit tested.

---

### Task 1: ComicPageSource interface, exceptions, and CbzPageSource

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/CbzPageSource.kt`
- Test: `app/src/test/java/com/comicanything/reader/data/pagesource/CbzPageSourceTest.kt`

**Interfaces:**
- Produces: `interface ComicPageSource { val pageCount: Int; suspend fun getPage(page: Int): Bitmap; fun close() }`, `class UnsupportedFormatException(format: ComicFormat) : Exception(...)`, `class PageDecodeException(page: Int, cause: Throwable) : Exception(...)`, `class CbzPageSource(file: File) : ComicPageSource`, `fun listImagePagesSorted(zipFile: ZipFile): List<ZipEntry>` (public, standalone — needed directly by Task 1's tests).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/pagesource/CbzPageSourceTest.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CbzPageSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildCbz(vararg entryNames: String): ZipFile {
        val zipFile = tempFolder.newFile("test-${System.nanoTime()}.cbz")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            entryNames.forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        return ZipFile(zipFile)
    }

    @Test
    fun `sorts pages in natural numeric order, not lexicographic`() {
        val zip = buildCbz("page10.jpg", "page2.jpg", "page1.jpg")

        val pages = listImagePagesSorted(zip)

        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), pages.map { it.name })
        zip.close()
    }

    @Test
    fun `filters out non-image entries`() {
        val zip = buildCbz("page1.jpg", "ComicInfo.xml", "page2.png", "thumbs.db")

        val pages = listImagePagesSorted(zip)

        assertEquals(listOf("page1.jpg", "page2.png"), pages.map { it.name })
        zip.close()
    }

    @Test
    fun `supports jpg, jpeg, png, webp, gif extensions case-insensitively`() {
        val zip = buildCbz("a.JPG", "b.jpeg", "c.PNG", "d.webp", "e.GIF")

        val pages = listImagePagesSorted(zip)

        assertEquals(5, pages.size)
        zip.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.CbzPageSourceTest" --no-daemon
```

Expected: FAIL — `listImagePagesSorted` doesn't exist yet, so this won't compile.

- [ ] **Step 3: Create the interface and exceptions**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import com.comicanything.reader.data.model.ComicFormat

interface ComicPageSource {
    val pageCount: Int
    suspend fun getPage(page: Int): Bitmap
    fun close()
}

class UnsupportedFormatException(format: ComicFormat) :
    Exception("Rendering not supported for format: $format")

class PageDecodeException(page: Int, cause: Throwable) :
    Exception("Failed to decode page $page", cause)
```

- [ ] **Step 4: Implement CbzPageSource**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/CbzPageSource.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

fun listImagePagesSorted(zipFile: ZipFile): List<ZipEntry> {
    return zipFile.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
        .sortedWith(compareBy(naturalOrderComparator()) { it.name })
        .toList()
}

fun naturalOrderComparator(): Comparator<String> = Comparator { a, b ->
    val ax = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val bx = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ax.size, bx.size)) {
        val x = ax[i]
        val y = bx[i]
        val cmp = if (x.first().isDigit() && y.first().isDigit()) {
            x.toLong().compareTo(y.toLong())
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return@Comparator cmp
    }
    ax.size.compareTo(bx.size)
}

class CbzPageSource(file: File) : ComicPageSource {
    private val zipFile = ZipFile(file)
    private val pageEntries: List<ZipEntry> = listImagePagesSorted(zipFile)

    override val pageCount: Int get() = pageEntries.size

    override suspend fun getPage(page: Int): Bitmap = withContext(Dispatchers.IO) {
        try {
            zipFile.getInputStream(pageEntries[page - 1]).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("decodeStream returned null")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PageDecodeException(page, e)
        }
    }

    override fun close() {
        zipFile.close()
    }
}
```

(`CancellationException` is a subtype of `Exception` — it must be rethrown before the generic catch, or cancelling the coroutine reading a page gets silently rewrapped as a decode error instead of propagating as a cancellation. Add `import kotlinx.coroutines.CancellationException`.)

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.CbzPageSourceTest" --no-daemon
```

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt app/src/main/java/com/comicanything/reader/data/pagesource/CbzPageSource.kt app/src/test/java/com/comicanything/reader/data/pagesource/CbzPageSourceTest.kt
git commit -m "feat: add ComicPageSource interface and CBZ page decoding"
```

---

### Task 2: PdfPageSource and the createPageSource factory

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/PdfPageSource.kt`
- Modify: `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`

**Interfaces:**
- Consumes: `ComicPageSource`, `UnsupportedFormatException`, `PageDecodeException` (Task 1), `CbzPageSource(file: File)` (Task 1), `ComicItem`/`ComicFormat`/`ComicSource` (existing model).
- Produces: `class PdfPageSource(file: File) : ComicPageSource`, `fun createPageSource(comic: ComicItem): ComicPageSource` (throws `UnsupportedFormatException` for non-local comics or unsupported formats).

No automated test for `PdfPageSource` itself — `PdfRenderer`/`Bitmap` are real Android framework classes with no JVM/Robolectric setup in this project (see Global Constraints). Verified manually in Task 6. `createPageSource`'s dispatch logic (which format maps to which source, and which cases throw) is exercised indirectly by Task 4's `ReaderViewModel` tests, so it doesn't need its own separate test here.

- [ ] **Step 1: Implement PdfPageSource**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/PdfPageSource.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfPageSource(file: File) : ComicPageSource {
    private val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fileDescriptor)
    } catch (e: Exception) {
        fileDescriptor.close()
        throw e
    }
    private val mutex = Mutex()

    override val pageCount: Int get() = renderer.pageCount

    override suspend fun getPage(page: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                renderer.openPage(page - 1).use { pdfPage ->
                    val scale = TARGET_WIDTH_PX.toFloat() / pdfPage.width
                    val bitmap = Bitmap.createBitmap(
                        TARGET_WIDTH_PX,
                        (pdfPage.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PageDecodeException(page, e)
            }
        }
    }

    override fun close() {
        renderer.close()
        fileDescriptor.close()
    }

    companion object {
        private const val TARGET_WIDTH_PX = 1080
    }
}
```

`PdfRenderer.Page` implements `Closeable` (API 24+), so `.use { }` closes it correctly even if `render` throws. The `Mutex` serializes every call — `PdfRenderer` is not safe for concurrent page access from multiple coroutines (e.g. a prefetch call overlapping the main page-load call).

- [ ] **Step 2: Add the createPageSource factory**

Append to `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt` (after the existing exception classes):

```kotlin
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import java.io.File

fun createPageSource(comic: ComicItem): ComicPageSource {
    if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(comic.format)
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(File(comic.pathOrUrl))
        ComicFormat.CBZ -> CbzPageSource(File(comic.pathOrUrl))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
```

(Add the two new imports to the top of the file alongside the existing `android.graphics.Bitmap` and `com.comicanything.reader.data.model.ComicFormat` imports — don't duplicate `ComicFormat` if it's already imported.)

- [ ] **Step 3: Build to confirm it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/PdfPageSource.kt app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt
git commit -m "feat: add PDF page decoding and page-source factory"
```

---

### Task 3: PageBitmapCache

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/PageBitmapCache.kt`

**Interfaces:**
- Consumes: `ComicPageSource`, `PageDecodeException` (Task 1).
- Produces: `class PageBitmapCache(source: ComicPageSource, maxCachedPages: Int = 5)` with `val pageCount: Int`, `suspend fun getPage(page: Int): Bitmap`, `suspend fun prefetch(pages: List<Int>)`, `fun close()`.

No automated test — caching real `Bitmap` values has the same Robolectric constraint as Task 1/2. Verified manually in Task 6 (page forward/back past the 5-page cache limit, confirm no crash and correct pages).

- [ ] **Step 1: Implement PageBitmapCache**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/PageBitmapCache.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PageBitmapCache(
    private val source: ComicPageSource,
    private val maxCachedPages: Int = 5
) {
    private val cache = object : LinkedHashMap<Int, Bitmap>(maxCachedPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) =
            size > maxCachedPages
    }
    private val mutex = Mutex()

    val pageCount: Int get() = source.pageCount

    suspend fun getPage(page: Int): Bitmap = mutex.withLock {
        cache[page] ?: source.getPage(page).also { cache[page] = it }
    }

    suspend fun prefetch(pages: List<Int>) {
        pages.filter { it in 1..pageCount && it !in cache }
            .forEach { page ->
                try {
                    getPage(page)
                } catch (e: PageDecodeException) {
                    // Prefetch failures are silent -- the page shows its
                    // error state if/when the user actually navigates to it.
                }
            }
    }

    fun close() = source.close()
}
```

- [ ] **Step 2: Build to confirm it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/PageBitmapCache.kt
git commit -m "feat: add fixed-size LRU page bitmap cache with prefetch"
```

---

### Task 4: Wire page rendering into ReaderViewModel

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `createPageSource(comic: ComicItem): ComicPageSource` (throws `UnsupportedFormatException`), `PageBitmapCache(source: ComicPageSource, maxCachedPages: Int = 5)`, `PageDecodeException` (Tasks 1-3).
- Produces: `ReaderUiState` gains `currentPageBitmap: Bitmap?`, `pageLoadError: String?`, `isPageLoading: Boolean`. `ReaderViewModel.openComic`/`setPage`/`closeComic` keep their existing public signatures — only their bodies change. Later tasks (Task 5, `ReaderScreen`) read these three new state fields.

The current `ReaderViewModel.openComic()` and `setPage()` do synchronous, non-suspending state updates. Opening a `PdfPageSource`/`CbzPageSource` does real file I/O (opening a file descriptor, reading a zip's central directory) that must not block the calling thread (Compose's main thread, when the user taps a comic card) — so `openComic` now launches a coroutine internally, same pattern as `loadLocalLibrary()` already uses elsewhere in this file.

- [ ] **Step 1: Write the failing tests**

Add these test cases to the end of `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the final closing `}`:

```kotlin
    @Test
    fun `opening a comic with an unsupported format sets an error and does not crash`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(localRepo = repo, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "1",
            title = "Unsupported Book",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("This format isn't supported yet", viewModel.uiState.value.pageLoadError)
        assertNull(viewModel.uiState.value.currentPageBitmap)
    }

    @Test
    fun `opening a Google Drive comic sets an error even for a supported format`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(localRepo = repo, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "2",
            title = "Drive Book",
            pathOrUrl = "https://drive.google.com/fake.pdf",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("This format isn't supported yet", viewModel.uiState.value.pageLoadError)
    }
```

`ReaderViewModel` now takes a third constructor parameter, `ioDispatcher` (added below) — pass `Dispatchers.Unconfined` in tests for the same reason `LocalFileRepository` does: `openComic` dispatches page-source creation onto this dispatcher, and a real `Dispatchers.IO` hop is a genuine async boundary `advanceUntilIdle()` can race past.

Add `assertNull` to the existing `org.junit.Assert.*` imports at the top of the file if it isn't already imported (check first — Task 2 of the Epic 1 plan already added `assertNull` for the `closeComic` test, so it's likely already there).

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `ReaderUiState` has no `pageLoadError`/`currentPageBitmap` fields yet, so this won't compile.

- [ ] **Step 3: Implement the ReaderViewModel changes**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add these imports alongside the existing ones — `Dispatchers` and `withContext` are new to this file (the existing `loadLocalLibrary`/`fetchDriveFolder` functions dispatch to IO internally inside their repositories, not in the ViewModel itself, so neither is currently imported here):

```kotlin
import android.graphics.Bitmap
import com.comicanything.reader.data.pagesource.PageBitmapCache
import com.comicanything.reader.data.pagesource.PageDecodeException
import com.comicanything.reader.data.pagesource.UnsupportedFormatException
import com.comicanything.reader.data.pagesource.createPageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Add three fields to `ReaderUiState` (after `val isScanningLocal: Boolean = false`):

```kotlin
    val isScanningLocal: Boolean = false,
    val currentPageBitmap: Bitmap? = null,
    val pageLoadError: String? = null,
    val isPageLoading: Boolean = false
)
```

Add a third constructor parameter to `ReaderViewModel`, matching the existing `@JvmOverloads`-defaulted style — this is what makes the two new tests below able to substitute a test-friendly dispatcher, the same way `LocalFileRepository`'s `ioDispatcher` parameter already does:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
```

Add a private field to `ReaderViewModel`, right after the `uiState` declaration:

```kotlin
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pageCache: PageBitmapCache? = null
```

Replace the existing `openComic` function:

```kotlin
    fun openComic(comic: ComicItem) {
        pageCache?.close()
        pageCache = null
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            currentPage = comic.currentPage,
            totalPages = if (comic.totalPages > 0) comic.totalPages else 48,
            isControlsVisible = true,
            currentPageBitmap = null,
            pageLoadError = null
        )
        viewModelScope.launch {
            val source = try {
                withContext(ioDispatcher) { createPageSource(comic) }
            } catch (e: UnsupportedFormatException) {
                _uiState.value = _uiState.value.copy(pageLoadError = "This format isn't supported yet")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Failed to open comic")
                return@launch
            }
            val cache = PageBitmapCache(source)
            pageCache = cache
            _uiState.value = _uiState.value.copy(totalPages = cache.pageCount)
            loadPage(cache, _uiState.value.currentPage)
        }
    }
```

Replace the existing `closeComic` function:

```kotlin
    fun closeComic() {
        pageCache?.close()
        pageCache = null
        _uiState.value = _uiState.value.copy(
            activeComic = null,
            currentPageBitmap = null,
            pageLoadError = null
        )
    }
```

Replace the existing `setPage` function:

```kotlin
    fun setPage(page: Int) {
        val clamped = page.coerceIn(1, _uiState.value.totalPages)
        _uiState.value = _uiState.value.copy(currentPage = clamped)

        _uiState.value.activeComic?.let { comic ->
            comic.currentPage = clamped
            comic.progressPercentage = clamped.toFloat() / _uiState.value.totalPages.toFloat()
        }

        val cache = pageCache ?: return
        viewModelScope.launch {
            loadPage(cache, clamped)
        }
    }
```

Add a new private helper, right after `setPage`:

```kotlin
    private suspend fun loadPage(cache: PageBitmapCache, page: Int) {
        _uiState.value = _uiState.value.copy(isPageLoading = true)
        try {
            val bitmap = cache.getPage(page)
            _uiState.value = _uiState.value.copy(
                currentPageBitmap = bitmap,
                pageLoadError = null,
                isPageLoading = false
            )
        } catch (e: PageDecodeException) {
            _uiState.value = _uiState.value.copy(
                currentPageBitmap = null,
                pageLoadError = e.message,
                isPageLoading = false
            )
        }
        cache.prefetch(listOf(page - 1, page + 1))
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the 2 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: wire page source creation and bitmap decoding into ReaderViewModel"
```

---

### Task 5: Render real pages in ReaderScreen

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`

**Interfaces:**
- Consumes: `ReaderUiState.currentPageBitmap: Bitmap?`, `.pageLoadError: String?`, `.isPageLoading: Boolean` (Task 4).

No automated test — Compose UI change with no test infra wired up in this project (Epic 8 scope, same as Epic 1's Task 4). Verified manually in Task 6.

- [ ] **Step 1: Add the new imports**

Add these imports to `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`, alongside the existing ones:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.graphics.asImageBitmap
```

(No `android.graphics.Bitmap` import needed — Kotlin smart-casts `state.currentPageBitmap` from `Bitmap?` to `Bitmap` inside the `!= null` branch below without the type being named explicitly in this file.)

- [ ] **Step 2: Replace the placeholder Card with real page rendering**

Replace the entire `Card { ... }` block inside the "Canvas Interactive Reader Viewport" `Box` (currently lines 75-109 — the block starting `Card(modifier = Modifier.fillMaxWidth(0.9f)...` and ending with its matching closing `}`) with:

```kotlin
            when {
                state.currentPageBitmap != null -> {
                    Image(
                        bitmap = state.currentPageBitmap.asImageBitmap(),
                        contentDescription = "${comic.title}, page ${state.currentPage}",
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .fillMaxHeight(0.85f)
                            .padding(if (state.autoCropMargins) 0.dp else 16.dp)
                    )
                }
                state.pageLoadError != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.pageLoadError,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
```

This removes the old book-icon placeholder entirely (including its unused `MenuBook` icon usage) — the reading-mode/auto-crop debug text (`"Mode: ${state.readingMode.name}..."`) is intentionally dropped too, since that was placeholder-only content describing a page that didn't actually exist yet.

- [ ] **Step 3: Remove the now-unused MenuBook import**

`Icons.Default.MenuBook` is no longer referenced anywhere in this file — remove `import androidx.compose.material.icons.filled.MenuBook` from the top of the file.

- [ ] **Step 4: Build to confirm it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt
git commit -m "feat: render real comic pages, loading, and error states in ReaderScreen"
```

---

### Task 6: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1-5 together as a user would.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "D:/Source Code/ComicAnything/app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`.)

- [ ] **Step 2: Verify PDF rendering**

Push a real multi-page PDF to the device's scanned folder and open it in the reader:

```bash
"$ADB" push "some-local-multi-page.pdf" /sdcard/Download/sample.pdf
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Expected: tapping the comic in the Library/Local Files tab shows the real rendered first page (not a placeholder icon), the bottom scrubber's page count matches the PDF's actual page count (not the old hardcoded 48), and tapping left/right actually shows different rendered pages.

- [ ] **Step 3: Verify CBZ rendering and natural sort**

Build a small CBZ with out-of-order-lexicographic page names (`page1.jpg`, `page2.jpg`, ..., `page10.jpg`, `page11.jpg`) and push it:

```bash
"$ADB" push "sample-with-11-pages.cbz" /sdcard/Download/sample.cbz
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Expected: pages appear in the correct natural order (page10/page11 after page9, not immediately after page1) when paging through.

- [ ] **Step 4: Verify the cache doesn't break navigation past its 5-page limit**

Using the 11-page CBZ from Step 3, page forward through all 11 pages, then back to page 1. Expected: no crash, no stuck/blank page, every page renders correctly regardless of how many times the 5-page cache has evicted and re-decoded.

- [ ] **Step 5: Verify the unsupported-format error path**

Open a comic with an EPUB, MOBI, or CBR extension (or a Google Drive item, once one is reachable) from the library.

Expected: the reader opens showing the "This format isn't supported yet" error card (grey error icon + message), not a crash, and the back button still works to return to the library.

- [ ] **Step 6: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under Epic 2, check off:
- PDF page rendering
- CBZ page extraction
- Page prefetch/LRU bitmap cache
- Replace the placeholder Card with the real rendered page

Leave the remaining Epic 2 items (reading modes on real pages, pinch-to-zoom, color filters, auto-crop) unchecked — those are sub-projects 2 and 3, not part of this plan. Change Epic 2's status marker from ⬜ to 🟨 (partial) and add a note referencing this plan.

- [ ] **Step 7: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark page-rendering-core portion of Epic 2 complete"
```
