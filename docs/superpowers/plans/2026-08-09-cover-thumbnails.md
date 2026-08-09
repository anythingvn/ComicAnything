# Real Cover Thumbnails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static book-icon placeholder in the Library/Local Files grid, list, and "Continue Reading" carousel with a real thumbnail decoded from each local PDF/CBZ comic's first page — per [docs/superpowers/specs/2026-08-09-cover-thumbnails-design.md](../specs/2026-08-09-cover-thumbnails-design.md).

**Architecture:** A new lightweight, thumbnail-sized decode path (`ThumbnailDecoder.kt`) separate from the full-page-resolution `PdfPageSource`/`CbzPageSource` used by the reader. `ReaderViewModel` gains a `CoverLoadState` sealed interface and `loadCoverThumbnail(comic)` suspend function backed by an in-memory LRU cache, mirroring the exact `PageLoadState`/`loadPageBitmap` pattern Epic 2 already established. `HomeScreen.kt`'s three cover-showing composables (`ComicGridCard`, `ComicListRow`, the inline Continue Reading carousel card) call it via `produceState`, the same idiom `DualPageSpreadReader`/`WebtoonReader` already use for full pages.

**Tech Stack:** Kotlin, Jetpack Compose, `android.graphics.pdf.PdfRenderer`, `android.graphics.BitmapFactory`, `java.util.zip.ZipFile` (all already dependencies — no new libraries).

## Global Constraints

- Google Drive cover thumbnails (`coverUrl`/`thumbnailLink` via Coil) are explicitly OUT OF SCOPE for this plan — Drive folder browsing has no real data path yet (see spec). Only local PDF/CBZ get real thumbnails; everything else (Drive comics, EPUB/CBR/MOBI locals) keeps the existing static book icon.
- Thumbnail cache is in-memory only, for the current app process — no disk persistence, no new cache-invalidation logic.
- Thumbnail decode targets `THUMBNAIL_TARGET_WIDTH_PX = 240` — much smaller than the reader's full-page `TARGET_WIDTH_PX = 1080` — and must not reuse `createPageSource`/`PdfPageSource`/`CbzPageSource` (those keep their underlying resource open for multi-page reading and decode at full display resolution; a thumbnail is a one-shot decode of page 1 only, and the resource must close immediately after).
- `Bitmap`/`BitmapFactory`/`PdfRenderer` are real Android-framework classes with no Robolectric configured in this project (confirmed: no `robolectric` dependency in `app/build.gradle.kts`) — any call into them throws `RuntimeException: Method ... not mocked` under plain JVM unit tests. This is why `PdfPageSource`/`CbzPageSource.getPage()` have no existing unit tests today (only `CbzPageSourceTest.kt`'s pure sort-logic tests exist), and why this plan's tests are scoped to logic that doesn't touch those classes.
- No mocking library is used in this project — tests use real objects. Where a fake is needed (Task 2's injectable decoder), it's a plain Kotlin lambda, not a mock framework.
- Existing package root: `com.comicanything.reader`.

---

### Task 1: `ThumbnailDecoder.kt` — decode logic + pure-logic tests

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/pagesource/ThumbnailDecoder.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/pagesource/ThumbnailDecoderTest.kt`

**Interfaces:**
- Produces: `suspend fun decodeThumbnail(comic: ComicItem): Bitmap?` — consumed by Task 2's `ReaderViewModel`.
- Produces: `internal fun calculateThumbnailSampleSize(actualWidth: Int, targetWidth: Int): Int` — pure helper, `internal` (not `private`) specifically so the test file can call it directly, same reasoning as `HomeScreen.kt`'s `filtered()` extension from the previous plan (a file-`private` top-level function isn't visible outside its own file, even to a test in the same module).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/pagesource/ThumbnailDecoderTest.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailDecoderTest {

    private fun comic(format: ComicFormat, source: ComicSource = ComicSource.LOCAL) = ComicItem(
        id = "1",
        title = "Test Comic",
        pathOrUrl = "/fake/path.${format.name.lowercase()}",
        source = source,
        format = format
    )

    @Test
    fun `returns null for a Google Drive comic without touching the filesystem`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.PDF, source = ComicSource.GOOGLE_DRIVE))

        assertNull(result)
    }

    @Test
    fun `returns null for an EPUB comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.EPUB))

        assertNull(result)
    }

    @Test
    fun `returns null for a CBR comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.CBR))

        assertNull(result)
    }

    @Test
    fun `returns null for a MOBI comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.MOBI))

        assertNull(result)
    }

    @Test
    fun `sample size is 1 when the actual width is already at or below the target`() {
        assertEquals(1, calculateThumbnailSampleSize(actualWidth = 240, targetWidth = 240))
        assertEquals(1, calculateThumbnailSampleSize(actualWidth = 100, targetWidth = 240))
    }

    @Test
    fun `sample size doubles until half the actual width would drop below the target`() {
        // actualWidth=1000, targetWidth=240: halfWidth=500; 500/1>=240, 500/2=250>=240, 500/4=125<240 -> sampleSize=4
        assertEquals(4, calculateThumbnailSampleSize(actualWidth = 1000, targetWidth = 240))
    }

    @Test
    fun `sample size is 2 for a modestly oversized image`() {
        // actualWidth=600, targetWidth=240: halfWidth=300; 300/1>=240, 300/2=150<240 -> sampleSize=2
        assertEquals(2, calculateThumbnailSampleSize(actualWidth = 600, targetWidth = 240))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.ThumbnailDecoderTest" --no-daemon
```

Expected: FAIL — `ThumbnailDecoder.kt` doesn't exist yet, so this won't compile (`decodeThumbnail`/`calculateThumbnailSampleSize` unresolved references).

- [ ] **Step 3: Implement `ThumbnailDecoder.kt`**

Create `app/src/main/java/com/comicanything/reader/data/pagesource/ThumbnailDecoder.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private const val THUMBNAIL_TARGET_WIDTH_PX = 240

suspend fun decodeThumbnail(comic: ComicItem): Bitmap? {
    if (comic.source != ComicSource.LOCAL) return null
    return when (comic.format) {
        ComicFormat.PDF -> decodePdfThumbnail(File(comic.pathOrUrl))
        ComicFormat.CBZ -> decodeCbzThumbnail(File(comic.pathOrUrl))
        else -> null
    }
}

private suspend fun decodePdfThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@withContext null
                renderer.openPage(0).use { page ->
                    val scale = THUMBNAIL_TARGET_WIDTH_PX.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(
                        THUMBNAIL_TARGET_WIDTH_PX,
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

private suspend fun decodeCbzThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
    try {
        ZipFile(file).use { zipFile ->
            val pageEntries = listImagePagesSorted(zipFile)
            if (pageEntries.isEmpty()) return@withContext null
            val entry = pageEntries[0]
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(boundsOptions.outWidth, THUMBNAIL_TARGET_WIDTH_PX)
            }
            zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        null
    }
}

internal fun calculateThumbnailSampleSize(actualWidth: Int, targetWidth: Int): Int {
    var sampleSize = 1
    if (actualWidth > targetWidth) {
        val halfWidth = actualWidth / 2
        while (halfWidth / sampleSize >= targetWidth) {
            sampleSize *= 2
        }
    }
    return sampleSize
}
```

`decodeCbzThumbnail` reuses `listImagePagesSorted` from `CbzPageSource.kt` (same package, already non-`private`) rather than duplicating the natural-sort/image-extension-filtering logic. Both `decodePdfThumbnail`/`decodeCbzThumbnail` catch `Exception` broadly and return `null` — a missing/corrupt/undecodable thumbnail is an expected, routine outcome the UI already has a defined fallback for (the static book icon), not an error needing separate handling. `OutOfMemoryError` is deliberately NOT caught (it's an `Error`, not an `Exception`, so this `catch` block doesn't touch it) — an OOM decoding a 240px-wide thumbnail would indicate a much deeper problem than this feature should silently paper over.

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.pagesource.ThumbnailDecoderTest" --no-daemon
```

Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/ThumbnailDecoder.kt app/src/test/java/com/comicanything/reader/data/pagesource/ThumbnailDecoderTest.kt
git commit -m "feat: add lightweight thumbnail decoder for local PDF/CBZ covers"
```

---

### Task 2: `ReaderViewModel` — `CoverLoadState` + `loadCoverThumbnail` + cache

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `suspend fun decodeThumbnail(comic: ComicItem): Bitmap?` (Task 1), via an injectable constructor parameter defaulting to it.
- Produces: `sealed interface CoverLoadState { Loading, Loaded(bitmap: Bitmap), Unavailable }` and `suspend fun ReaderViewModel.loadCoverThumbnail(comic: ComicItem): CoverLoadState` — consumed by Task 3's `HomeScreen.kt`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, inside the `ReaderViewModelTest` class (add these as new `@Test` methods; do not modify any existing test):

```kotlin
    @Test
    fun `loadCoverThumbnail returns Unavailable when the decoder finds nothing`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { null }
        )
        val comic = ComicItem(
            id = "epub-comic",
            title = "Unsupported",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        val result = viewModel.loadCoverThumbnail(comic)

        assertEquals(CoverLoadState.Unavailable, result)
    }

    @Test
    fun `loadCoverThumbnail only invokes the decoder once per comic, caching the result`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var decodeCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { decodeCallCount++; null }
        )
        val comic = ComicItem(
            id = "cached-comic",
            title = "Test",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.loadCoverThumbnail(comic)
        viewModel.loadCoverThumbnail(comic)
        viewModel.loadCoverThumbnail(comic)

        assertEquals(1, decodeCallCount)
    }

    @Test
    fun `loadCoverThumbnail decodes independently per distinct comic id`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var decodeCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { decodeCallCount++; null }
        )
        val comicA = ComicItem(id = "a", title = "A", pathOrUrl = "/fake/a.cbz", source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        val comicB = ComicItem(id = "b", title = "B", pathOrUrl = "/fake/b.cbz", source = ComicSource.LOCAL, format = ComicFormat.CBZ)

        viewModel.loadCoverThumbnail(comicA)
        viewModel.loadCoverThumbnail(comicB)
        viewModel.loadCoverThumbnail(comicA)

        assertEquals(2, decodeCallCount)
    }
```

The `Loaded(bitmap)` happy path is NOT covered by a `ReaderViewModelTest` case — constructing a real `android.graphics.Bitmap` under this project's plain-JVM unit tests (no Robolectric) throws `RuntimeException: Method ... not mocked` the same way calling `BitmapFactory.decodeStream` would, so there's no way to produce a real `Bitmap` instance to hand back from a fake `thumbnailDecoder` lambda. This mirrors the existing `loadPageBitmap returns Failed when no comic is open` test, which covers only the guard-clause path for the same reason — the `Loaded` case is exercised by Task 4's manual on-device verification instead.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `CoverLoadState`, `thumbnailDecoder` constructor parameter, and `loadCoverThumbnail` don't exist yet.

- [ ] **Step 3: Implement the `ReaderViewModel` changes**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`:

Add this import alongside the existing `com.comicanything.reader.data.pagesource.*` imports:

```kotlin
import com.comicanything.reader.data.pagesource.decodeThumbnail
```

Add this sealed interface right after the existing `PageLoadState` (around line 57):

```kotlin
sealed interface CoverLoadState {
    data object Loading : CoverLoadState
    data class Loaded(val bitmap: Bitmap) : CoverLoadState
    data object Unavailable : CoverLoadState
}
```

Add `kotlinx.coroutines.sync.Mutex` and `kotlinx.coroutines.sync.withLock` to the imports (not already imported in this file):

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

Change the constructor (currently lines 59-66) to add the new `thumbnailDecoder` parameter, keeping every existing parameter exactly as-is:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail
) : AndroidViewModel(application) {
```

Add the cache fields alongside the existing `pageCache`/`debounceJob` fields (currently lines 71-72):

```kotlin
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap?>(THUMBNAIL_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) =
            size > THUMBNAIL_CACHE_SIZE
    }
    private val thumbnailMutex = Mutex()
```

Add the `loadCoverThumbnail` function anywhere among the other `suspend fun`s (e.g. right after the existing `loadPageBitmap`, around line 282):

```kotlin
    suspend fun loadCoverThumbnail(comic: ComicItem): CoverLoadState = thumbnailMutex.withLock {
        if (thumbnailCache.containsKey(comic.id)) {
            val cached = thumbnailCache.getValue(comic.id)
            return@withLock if (cached != null) CoverLoadState.Loaded(cached) else CoverLoadState.Unavailable
        }
        val bitmap = thumbnailDecoder(comic)
        thumbnailCache[comic.id] = bitmap
        if (bitmap != null) CoverLoadState.Loaded(bitmap) else CoverLoadState.Unavailable
    }
```

Add the cache-size constant in the existing `companion object` (create one if it doesn't already exist — check the current file; if it doesn't have one, add it as a new top-level block inside the class, e.g. right before the final closing brace):

```kotlin
    companion object {
        private const val THUMBNAIL_CACHE_SIZE = 60
    }
```

`thumbnailCache` stores `null` for comics that failed/aren't decodable (not just successful decodes), so `containsKey` distinguishes "not yet looked up" (triggers a decode) from "looked up, nothing available" (returns `Unavailable` immediately without re-decoding) — this is what makes the "only invokes the decoder once per comic" test pass. 60 is a deliberately generous cap: a phone-sized grid at 2 columns shows roughly 8-12 cards on screen at once, and 60 comfortably covers a full scroll session's worth of distinct comics without holding an unbounded amount of decoded bitmap memory for a very large library.

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS (all existing tests plus the 3 new ones, 3 new = pass).

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass, including Task 1's `ThumbnailDecoderTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: add CoverLoadState and loadCoverThumbnail with in-memory LRU cache"
```

---

### Task 3: `HomeScreen.kt` — `ComicCoverThumbnail` + wire into grid/list/carousel

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `suspend fun ReaderViewModel.loadCoverThumbnail(comic: ComicItem): CoverLoadState` (Task 2).
- Produces: new composable `ComicCoverThumbnail(comic: ComicItem, viewModel: ReaderViewModel, modifier: Modifier, iconSize: Dp, iconTint: Color)`. `LibraryContent` and `LocalFilesContent` both gain a new required `viewModel: ReaderViewModel` parameter; `ComicGridCard` and `ComicListRow` both gain a new required `viewModel: ReaderViewModel` parameter. No other public function signature in this file changes.

This task is pure UI wiring with no new unit-testable logic beyond Task 1/2's already-covered decode/cache logic — verified by `assembleDebug` compiling cleanly plus Task 4's manual on-device pass.

- [ ] **Step 1: Add the new imports**

At the top of `HomeScreen.kt`, add these imports alongside the existing ones:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.comicanything.reader.ui.reader.CoverLoadState
```

(`androidx.compose.material3.*` already covers everything else needed; `androidx.compose.runtime.*` already covers `produceState`.)

- [ ] **Step 2: Add the `ComicCoverThumbnail` composable**

Add this new composable anywhere at the top level of `HomeScreen.kt` (e.g. right after the `PermissionRequiredCard` composable, before `LibraryContent`):

```kotlin
@Composable
fun ComicCoverThumbnail(
    comic: ComicItem,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 64.dp,
    iconTint: Color = Color.White.copy(alpha = 0.3f)
) {
    val coverState by produceState<CoverLoadState>(initialValue = CoverLoadState.Loading, key1 = comic.id) {
        value = viewModel.loadCoverThumbnail(comic)
    }
    when (val state = coverState) {
        is CoverLoadState.Loaded -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        CoverLoadState.Loading, CoverLoadState.Unavailable -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
```

- [ ] **Step 3: Thread `viewModel` through `LibraryContent` and wire the carousel + `ComicGridCard`/`ComicListRow` calls**

`LibraryContent`'s current signature (per the previous plan's Task 2) is:

```kotlin
@Composable
fun LibraryContent(
    state: ReaderUiState,
    comics: List<ComicItem>,
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
)
```

Add `viewModel: ReaderViewModel` as a new parameter (placement doesn't matter functionally; add it right after `state` for readability):

```kotlin
@Composable
fun LibraryContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    comics: List<ComicItem>,
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
)
```

Inside `LibraryContent`'s Continue Reading carousel `Card`, the current icon block reads:

```kotlin
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF2C2C2C)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
```

Replace it with:

```kotlin
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF2C2C2C)),
                                contentAlignment = Alignment.Center
                            ) {
                                ComicCoverThumbnail(
                                    comic = comic,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    iconSize = 48.dp,
                                    iconTint = Color.White.copy(alpha = 0.5f)
                                )
                            }
```

Further down in `LibraryContent`, the two `ComicGridCard`/`ComicListRow` call sites:

```kotlin
                items(comics) { comic ->
                    ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
                }
```

and

```kotlin
                items(comics) { comic ->
                    ComicListRow(comic = comic, onClick = { onOpenComic(comic) })
                }
```

become:

```kotlin
                items(comics) { comic ->
                    ComicGridCard(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                }
```

and

```kotlin
                items(comics) { comic ->
                    ComicListRow(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                }
```

respectively (there are two such pairs of call sites in the file — one inside `LibraryContent`, one inside `LocalFilesContent`; this step covers `LibraryContent`'s pair, Step 5 below covers `LocalFilesContent`'s).

- [ ] **Step 4: Update `ComicGridCard` and `ComicListRow`**

`ComicGridCard`'s current signature and icon block:

```kotlin
@Composable
fun ComicGridCard(comic: ComicItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .height(200.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E2638)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Surface(
```

Change the function signature and replace the `Icon` with `ComicCoverThumbnail`, keeping the `Surface` format badge that follows it completely untouched:

```kotlin
@Composable
fun ComicGridCard(comic: ComicItem, viewModel: ReaderViewModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .height(200.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E2638)),
                contentAlignment = Alignment.Center
            ) {
                ComicCoverThumbnail(
                    comic = comic,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                    iconSize = 64.dp,
                    iconTint = Color.White.copy(alpha = 0.3f)
                )
                Surface(
```

(everything from `Surface(` onward — the format badge overlay and the title `Text` below the `Box` — stays exactly as it is today, not reproduced here for brevity; only the function signature line and the `Icon(...)` block inside the first `Box` change.)

`ComicListRow`'s current signature and leading icon:

```kotlin
@Composable
fun ComicListRow(comic: ComicItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = comic.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
```

Change to:

```kotlin
@Composable
fun ComicListRow(comic: ComicItem, viewModel: ReaderViewModel, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = comic.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            ComicCoverThumbnail(
                comic = comic,
                viewModel = viewModel,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                iconSize = 20.dp,
                iconTint = MaterialTheme.colorScheme.primary
            )
        },
```

(everything from `trailingContent` onward — the format badge — stays exactly as it is today.)

- [ ] **Step 5: Thread `viewModel` through `LocalFilesContent` and its `ComicGridCard`/`ComicListRow` calls**

`LocalFilesContent`'s current signature (per the previous plan's Task 2):

```kotlin
@Composable
fun LocalFilesContent(
    state: ReaderUiState,
    comics: List<ComicItem>,
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
)
```

Add `viewModel: ReaderViewModel` the same way as `LibraryContent`:

```kotlin
@Composable
fun LocalFilesContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    comics: List<ComicItem>,
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
)
```

Update its two `ComicGridCard`/`ComicListRow` call sites the same way as Step 3:

```kotlin
                items(comics) { comic ->
                    ComicGridCard(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                }
```

and

```kotlin
                items(comics) { comic ->
                    ComicListRow(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                }
```

- [ ] **Step 6: Update `HomeScreen`'s call sites for `LibraryContent`/`LocalFilesContent`**

In `HomeScreen`'s `when (homeScreenState.selectedTab)` block, the current two call sites:

```kotlin
                0 -> LibraryContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = homeScreenState.selectedFormats,
                    onFormatToggle = { format ->
                        homeScreenState.selectedFormats = if (format in homeScreenState.selectedFormats) homeScreenState.selectedFormats - format else homeScreenState.selectedFormats + format
                    },
                    isGridLayout = homeScreenState.isGridLayout,
                    onToggleLayout = { homeScreenState.isGridLayout = !homeScreenState.isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
                2 -> LocalFilesContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = homeScreenState.selectedFormats,
                    onFormatToggle = { format ->
                        homeScreenState.selectedFormats = if (format in homeScreenState.selectedFormats) homeScreenState.selectedFormats - format else homeScreenState.selectedFormats + format
                    },
                    isGridLayout = homeScreenState.isGridLayout,
                    onToggleLayout = { homeScreenState.isGridLayout = !homeScreenState.isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
```

Add `viewModel = viewModel` to both `LibraryContent(...)` and `LocalFilesContent(...)` calls (the `DriveContent(...)` call in between is untouched — it already receives `viewModel` indirectly via its own `onFetch` lambda closure, no signature change needed there):

```kotlin
                0 -> LibraryContent(
                    state = state,
                    viewModel = viewModel,
                    comics = filteredLibraryComics,
                    selectedFormats = homeScreenState.selectedFormats,
                    onFormatToggle = { format ->
                        homeScreenState.selectedFormats = if (format in homeScreenState.selectedFormats) homeScreenState.selectedFormats - format else homeScreenState.selectedFormats + format
                    },
                    isGridLayout = homeScreenState.isGridLayout,
                    onToggleLayout = { homeScreenState.isGridLayout = !homeScreenState.isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
                2 -> LocalFilesContent(
                    state = state,
                    viewModel = viewModel,
                    comics = filteredLibraryComics,
                    selectedFormats = homeScreenState.selectedFormats,
                    onFormatToggle = { format ->
                        homeScreenState.selectedFormats = if (format in homeScreenState.selectedFormats) homeScreenState.selectedFormats - format else homeScreenState.selectedFormats + format
                    },
                    isGridLayout = homeScreenState.isGridLayout,
                    onToggleLayout = { homeScreenState.isGridLayout = !homeScreenState.isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
```

- [ ] **Step 7: Build to confirm everything compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass, including Task 1's and Task 2's new tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt
git commit -m "feat: render real cover thumbnails in grid, list, and carousel cards"
```

---

### Task 4: End-to-end manual verification on the emulator

**Files:** none (verification only), plus `PROJECT_TASKS.md`

**Interfaces:** none — this task exercises Tasks 1-3 together as a user would.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test -dns-server 8.8.8.8,8.8.4.4`, then wait for `adb shell getprop sys.boot_completed` to report `1` before installing.)

- [ ] **Step 2: Verify real thumbnails render for local PDF and CBZ**

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

On the Library tab (grid layout, the default), confirm each PDF and CBZ comic card shows its actual first-page content (not the generic book icon) — take a screenshot and visually confirm real page content is visible (e.g. actual comic/document imagery, not a flat gray/dark box with a centered icon glyph). Confirm the format badge overlay (top-right corner, e.g. "PDF"/"CBZ") is still visible on top of the thumbnail.

- [ ] **Step 3: Verify the fallback icon for unsupported formats**

Confirm any EPUB comic in the library still shows the static book icon (no crash, no blank/broken image). If a CBR or MOBI test file is available, confirm the same; if not, this is acceptable to skip since the code path (`decodeThumbnail`'s `else -> null` branch) is identical for all three formats and already covered by Task 1's unit tests.

- [ ] **Step 4: Verify the list layout and Continue Reading carousel**

Tap the grid/list toggle (from the previous plan's Epic 7 sub-project 1 work) to switch to list mode. Confirm each `ComicListRow` shows a small rounded-square thumbnail crop (not the old plain bookmark-colored icon) for PDF/CBZ comics, and the fallback glyph for anything else. Toggle back to grid.

Open a comic partway (page forward a few pages), back out to the Library tab, and confirm the "Continue Reading" carousel card also shows the real thumbnail, not the icon.

- [ ] **Step 5: Verify the Local Files tab**

Switch to the Local Files tab and repeat Steps 2-4's checks there — same comics, same expected thumbnail/fallback behavior, confirming `LocalFilesContent`'s wiring works identically to `LibraryContent`'s.

- [ ] **Step 6: Verify caching avoids a re-decode flash on scroll-back**

Scroll the grid/list down past the visible comics and back up. Confirm the thumbnails that were already decoded reappear instantly (no visible icon-then-image flash the second time), consistent with `loadCoverThumbnail`'s cache.

- [ ] **Step 7: Regression check**

Confirm nothing else broke: search/format-filter/grid-list-toggle from the previous plan still work correctly with thumbnails now showing; opening and reading a comic normally still works; the Google Drive tab is unaffected (still shows its own UI, no thumbnails attempted there since Drive comics always resolve to the fallback icon via `decodeThumbnail`'s `comic.source != LOCAL` guard); favorites/persistence from Epic 3 still work. No crashes, ANRs, or `OutOfMemoryError` in `adb logcat` throughout this whole pass — pay particular attention to memory behavior while scrolling repeatedly through the full test library, since thumbnail decoding is the main new memory consumer introduced by this plan.

- [ ] **Step 8: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 7`, check off the "Real cover thumbnails" bullet. Add a verification note in the same style as the epic's existing notes (see the sub-project 1 note already there), summarizing what was tested and confirmed on `comicanything_test`, and noting explicitly that Google Drive thumbnails remain out of scope pending real Drive folder data (Epic 4 sub-project 2). Since this is the last remaining item in Epic 7, also update the epic's status marker from `🟨` to `✅` in the section heading if every other bullet in the section is already checked off (confirm by reading the current file — do not assume).

- [ ] **Step 9: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark real cover thumbnails (Epic 7, sub-project 2) complete"
```
