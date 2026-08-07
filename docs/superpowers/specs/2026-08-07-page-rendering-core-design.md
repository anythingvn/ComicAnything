# Comic Page Rendering Core — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 2: Core Page Rendering Engine (sub-project 1 of 3)

## Goal

Make the reader actually render comic pages. Today `ReaderScreen` shows a
static placeholder card with a book icon and "Page X of Y" text — no PDF
or CBZ page has ever been decoded or displayed. This sub-project replaces
that placeholder with real, cached, on-demand page rendering for local
PDF and CBZ files. It's the foundation the rest of Epic 2 (reading modes,
gestures, filters/crop — separate sub-projects) builds on.

## Scope

- **In scope:** local PDF and CBZ files only (`ComicItem.source == LOCAL`).
  PDF via `android.graphics.pdf.PdfRenderer` (native API). CBZ via
  `java.util.zip.ZipFile`.
- **Out of scope:**
  - Google Drive items — they don't download/cache bytes yet (Epic 4).
    Opening a Drive comic in this pass shows the same "not yet supported"
    error path as an unsupported format.
  - EPUB, MOBI, CBR — no decoders exist for these formats. Opening one
    shows an inline error, not a crash.
  - Reading modes acting on multiple pages at once (Webtoon scroll,
    dual-page spread), pinch-to-zoom, color filters, auto-crop — all
    separate sub-projects that consume this one's output.

## Components

### `data/pagesource/ComicPageSource.kt` (new)

```kotlin
interface ComicPageSource {
    val pageCount: Int
    suspend fun getPage(page: Int): Bitmap  // 1-indexed
    fun close()
}

class UnsupportedFormatException(format: ComicFormat) :
    Exception("Rendering not supported for format: $format")

class PageDecodeException(page: Int, cause: Throwable) :
    Exception("Failed to decode page $page", cause)
```

`getPage` throws `PageDecodeException` on a per-page failure (corrupt
entry, encrypted PDF, etc.) rather than returning a nullable/sealed
result — matches this codebase's existing exception-based style (e.g.
`GoogleDriveRepository` catches and logs rather than modeling errors as
data). Callers (the cache, then the ViewModel) catch it and surface a
per-page error state; the source and the rest of the comic stay usable.

### `data/pagesource/PdfPageSource.kt` (new)

```kotlin
class PdfPageSource(private val file: File) : ComicPageSource {
    private val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fileDescriptor)
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

`PdfRenderer` is not safe for concurrent page access from multiple
threads — the `Mutex` serializes every `getPage` call (including ones
issued concurrently by prefetch). Page size comes from the PDF in
points; we scale to a fixed `TARGET_WIDTH_PX` (1080px, a common device
width) preserving aspect ratio, rather than rendering at the PDF's native
point-resolution (usually far too low for a phone screen).

### `data/pagesource/CbzPageSource.kt` (new)

```kotlin
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
        } catch (e: Exception) {
            throw PageDecodeException(page, e)
        }
    }

    override fun close() {
        zipFile.close()
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

fun listImagePagesSorted(zipFile: ZipFile): List<ZipEntry> {
    return zipFile.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
        .sortedWith(compareBy(naturalOrderComparator()) { it.name })
        .toList()
}

fun naturalOrderComparator(): Comparator<String> = Comparator { a, b ->
    // Splits each name into alternating runs of digits/non-digits and compares
    // digit runs numerically, so "page2.jpg" sorts before "page10.jpg".
    val ax = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val bx = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ax.size, bx.size)) {
        val (x, y) = ax[i] to bx[i]
        val cmp = if (x.first().isDigit() && y.first().isDigit()) {
            x.toLong().compareTo(y.toLong())
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return@Comparator cmp
    }
    ax.size.compareTo(bx.size)
}
```

`listImagePagesSorted` and `naturalOrderComparator` are free functions,
not methods on `CbzPageSource` — deliberately, so they're testable in a
plain JVM unit test against a real `ZipFile`/`ZipEntry` (both are
standard `java.util.zip` classes, no Android framework dependency),
without needing `BitmapFactory` or Robolectric.

### `data/pagesource/PageBitmapCache.kt` (new)

```kotlin
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
                    // Prefetch failures are silent — the page shows its
                    // error state if/when the user actually navigates to it.
                }
            }
    }

    fun close() = source.close()
}
```

A plain `LinkedHashMap` in access-order mode with `removeEldestEntry` is
Java's standard LRU idiom — no need for Android's `LruCache` (which is
sized in "generic units," awkward for a fixed page count) or a new
dependency.

### `ui/reader/ReaderViewModel.kt` (modify)

- `ReaderUiState` gains:
  ```kotlin
  val currentPageBitmap: Bitmap? = null,
  val pageLoadError: String? = null,
  val isPageLoading: Boolean = false
  ```
- New private instance field: `private var pageCache: PageBitmapCache? = null`
  (not part of `ReaderUiState` — a `Bitmap`-holding cache is Compose
  state-instability the UI doesn't need direct access to; the UI only
  ever sees the single current-page `Bitmap` via `ReaderUiState`).
- `openComic(comic: ComicItem)`: after setting `activeComic`, builds the
  page source by format:
  ```kotlin
  val source = if (comic.source == ComicSource.LOCAL) {
      when (comic.format) {
          ComicFormat.PDF -> PdfPageSource(File(comic.pathOrUrl))
          ComicFormat.CBZ -> CbzPageSource(File(comic.pathOrUrl))
          else -> null
      }
  } else null
  ```
  If `source` is null (unsupported format, or a non-local comic), set
  `pageLoadError = "This format isn't supported yet"`, leave `pageCache`
  as `null`, and skip page decoding entirely. Otherwise set `pageCache =
  PageBitmapCache(source)`, update `totalPages = pageCache.pageCount`,
  and call the same page-loading routine `setPage` uses.
- `setPage(page: Int)`: clamps as today. If `pageCache == null` (the
  unsupported-format case), return immediately — `pageLoadError` is
  already set and stays showing; there's nothing to decode. Otherwise
  launch a coroutine that sets `isPageLoading = true`, calls
  `pageCache.getPage(page)`, and on success sets `currentPageBitmap`
  (clearing `pageLoadError`); on `PageDecodeException` sets
  `pageLoadError` (clearing `currentPageBitmap`). Either way clears
  `isPageLoading`, then calls `pageCache.prefetch(listOf(page - 1, page + 1))`.
- `closeComic()`: calls `pageCache?.close()`, sets `pageCache = null`,
  and clears `activeComic`/`currentPageBitmap`/`pageLoadError` in the
  same state update.

### `ui/reader/ReaderScreen.kt` (modify)

Replace the placeholder `Card` block (currently just an `Icon` + `Text`)
with three states driven by the new `ReaderUiState` fields:
- `state.currentPageBitmap != null` → `Image(bitmap = ...asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())`
- `state.pageLoadError != null` → an error card (icon + the message)
- otherwise (`isPageLoading`) → a `CircularProgressIndicator`

The existing tap-zone gesture detection, top/bottom bar overlays, and
quick-settings sheet are unchanged — this sub-project only replaces what
renders inside the central viewport `Box`.

## Testing

- Unit test `listImagePagesSorted` / `naturalOrderComparator` against a
  real in-memory or temp-file `ZipFile` (entries named to exercise
  natural ordering: `page1.jpg`, `page2.jpg`, `page10.jpg`, plus a
  non-image entry like `ComicInfo.xml` that must be filtered out).
- `PdfPageSource`, `CbzPageSource`'s `getPage`, and `PageBitmapCache`'s
  eviction behavior all touch real `Bitmap`/`PdfRenderer` — no
  Robolectric in this project, so these are **not** unit tested; verified
  manually on the `comicanything_test` emulator (open a real PDF, open a
  real CBZ, page forward/back past the cache size, confirm no crash and
  correct pages shown; open an EPUB/CBR/MOBI item and confirm the inline
  error state instead of a crash).
- The `openComic`/`setPage` state transitions for the *unsupported-format*
  error path (`pageLoadError` set, `pageCache` stays null, no crash) do
  **not** require a real `Bitmap` and can be unit tested directly against
  `ReaderViewModel` today — open a comic with `ComicFormat.EPUB` or
  `source = ComicSource.GOOGLE_DRIVE` and assert `pageLoadError` is set.
  The success-path transitions (`currentPageBitmap` populated from a real
  decode) require an actual `Bitmap`, which can't be constructed in a
  plain JVM test without Robolectric — those stay manual/instrumented,
  same as the sources above. This is an existing constraint of the
  project's test setup, not a gap introduced by this design.
