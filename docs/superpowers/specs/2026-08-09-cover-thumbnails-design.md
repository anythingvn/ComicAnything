# Real Cover Thumbnails — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 7: Library/Home UX Completion (sub-project 2 of 2)

## Goal

Replace the static book-icon placeholder shown for every comic in the Library/Local Files grid, list, and "Continue Reading" carousel with a real thumbnail decoded from the comic's own first page, for locally stored PDF and CBZ files.

## Scope

- **In scope:** decoding page 1 of a local PDF or CBZ into a small thumbnail `Bitmap`, caching it in memory for the life of the app process, and displaying it in place of the static book icon in `ComicGridCard`, `ComicListRow`, and the "Continue Reading" carousel card (all in `HomeScreen.kt`).
- **Explicitly out of scope (deferred):** Google Drive cover thumbnails (`coverUrl`/`thumbnailLink` via Coil). Drive folder browsing currently never receives an API key from the UI (`HomeScreen.kt`'s `onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }` never passes one), so `GoogleDriveRepository.fetchFolderContents` always falls through to its single hardcoded demo item with no real `thumbnailLink` — there is no real data to point a Drive-thumbnail path at yet, and no way to verify it on-device. Revisit once Epic 4 sub-project 2 (real Drive streaming) lands.
- **Explicitly out of scope:** EPUB, CBR, and MOBI comics — none of these are decodable by the existing page-source pipeline (`createPageSource` throws `UnsupportedFormatException` for them today), so they keep showing the static book icon. No new decode support is being added for these formats by this sub-project.
- **Explicitly out of scope:** disk-persisted thumbnail cache. Thumbnails are cached in memory only, for the current app process — a decision made explicitly to match the existing `PageBitmapCache` pattern from Epic 2 and avoid new disk I/O, cache-size management, and invalidation logic. Every cold app start re-decodes each visible comic's thumbnail once; after that first decode it's served from memory for the rest of the session.

## Components

### Thumbnail decoding

A new lightweight decode path, not a reuse of `createPageSource(comic).getPage(1)`. The existing `PdfPageSource`/`CbzPageSource` (`app/src/main/java/com/comicanything/reader/data/pagesource/`) target full-page display resolution (`TARGET_WIDTH_PX = 1080`) and keep their underlying resource (`PdfRenderer`/`ZipFile`) open for repeated multi-page access during reading — both wrong for a one-shot ~200px-wide grid thumbnail that's decoded once and discarded. Decoding a full 1080px page just to shrink it down would waste memory and time on every comic in a large library.

New file `app/src/main/java/com/comicanything/reader/data/pagesource/ThumbnailDecoder.kt`:

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

private fun calculateThumbnailSampleSize(actualWidth: Int, targetWidth: Int): Int {
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

`decodeCbzThumbnail` reuses `listImagePagesSorted` (already `internal`/public in `CbzPageSource.kt`, same package) rather than duplicating the natural-sort/image-extension-filtering logic. Both functions return `null` on any failure (corrupt file, zero pages, decode error, `OutOfMemoryError` is NOT caught here — an OOM on a 240px thumbnail decode would indicate a much deeper problem than this feature should silently swallow) rather than throwing, since a missing thumbnail is an expected, routine outcome (unsupported format, corrupt file) that the UI already has a defined fallback for — the static book icon — not an error state that needs surfacing.

### Cache + ViewModel integration

Mirrors the exact pattern Epic 2's reading modes already established for full pages (`PageLoadState` / `loadPageBitmap` in `ReaderViewModel.kt`).

In `ReaderViewModel.kt`, alongside the existing `PageLoadState`:

```kotlin
sealed interface CoverLoadState {
    data object Loading : CoverLoadState
    data class Loaded(val bitmap: Bitmap) : CoverLoadState
    data object Unavailable : CoverLoadState
}
```

A small in-memory LRU cache, same shape as `PageBitmapCache` but keyed by `comic.id` instead of page number, and holding a fixed cap of decoded thumbnails (bounded so a long scroll through a large library doesn't grow unbounded):

```kotlin
private val thumbnailCache = object : LinkedHashMap<String, Bitmap?>(THUMBNAIL_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) =
        size > THUMBNAIL_CACHE_SIZE
}
private val thumbnailMutex = Mutex()

suspend fun loadCoverThumbnail(comic: ComicItem): CoverLoadState = thumbnailMutex.withLock {
    if (thumbnailCache.containsKey(comic.id)) {
        val cached = thumbnailCache[comic.id]
        return@withLock if (cached != null) CoverLoadState.Loaded(cached) else CoverLoadState.Unavailable
    }
    val bitmap = decodeThumbnail(comic)
    thumbnailCache[comic.id] = bitmap
    if (bitmap != null) CoverLoadState.Loaded(bitmap) else CoverLoadState.Unavailable
}

companion object {
    private const val THUMBNAIL_CACHE_SIZE = 60
}
```

The cache stores `null` for comics that failed/aren't decodable (not just successful decodes) so a repeat visit to an EPUB/CBR/MOBI card doesn't re-attempt a decode that's already known to return nothing — `containsKey` distinguishes "not yet looked up" from "looked up, no thumbnail available." 60 is a deliberately generous cap — a phone-sized grid at 2 columns shows roughly 8-12 cards on screen at once; 60 comfortably covers a full scroll session's worth of distinct comics without holding an unbounded amount of decoded bitmap memory for a very large library.

### UI integration

A new shared composable in `HomeScreen.kt`, replacing the static `Icon(Icons.Default.Book, ...)` at all three call sites:

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

`Loading` and `Unavailable` render identically (the existing static icon) — there's no separate loading spinner or error glyph, since decode is fast (a small local file read, not a network call) and a flash-then-settle icon-to-image transition is the expected, acceptable UX; a distinct loading state would be over-engineering for a sub-200ms local decode.

`iconSize`/`iconTint` are exposed as parameters, not hardcoded, because the three call sites currently style their fallback icon differently and that styling should carry over unchanged — only the "is there a real image available" behavior is new, not the existing fallback look:
- **`ComicGridCard`**: `iconSize = 64.dp`, `iconTint = Color.White.copy(alpha = 0.3f)` (the defaults above) — matches its current icon exactly.
- **Carousel card** (inline in `LibraryContent`): `iconSize = 48.dp`, `iconTint = Color.White.copy(alpha = 0.5f)` — matches its current icon exactly.
- **`ComicListRow`**: this one needs more than a size/tint override. Its current icon is a small, bare `leadingContent` glyph tinted `MaterialTheme.colorScheme.primary`, with no surrounding colored box (unlike the grid/carousel cards, which frame their icon inside a large tinted `Box`) — a raw, un-clipped `Image` dropped into that slot would look visually rough next to the rest of the app's rounded UI. Wrap it: `ComicCoverThumbnail(comic = comic, viewModel = viewModel, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), iconSize = 20.dp, iconTint = MaterialTheme.colorScheme.primary)` — a small rounded-square cover swatch when a thumbnail is available, falling back to the same primary-tinted glyph (just resized to fit the new 40.dp slot) when it isn't.

Each of the three call sites swaps its `Box { Icon(...) }` (or, for `ComicListRow`, its bare `leadingContent` `Icon(...)`) for the appropriate `ComicCoverThumbnail(...)` call above, keeping the existing format-badge `Surface` overlay (on `ComicGridCard`) and surrounding `Card`/`ListItem` structure otherwise untouched — this is a like-for-like replacement of the icon content, not a card-layout redesign.

This requires threading `viewModel: ReaderViewModel` down through the call chain, since none of `LibraryContent`, `LocalFilesContent`, `ComicGridCard`, or `ComicListRow` currently receive it (only `HomeScreen` does today):
- `ComicGridCard` and `ComicListRow` both already take `comic: ComicItem`; both gain one new required parameter, `viewModel: ReaderViewModel`.
- `LibraryContent` and `LocalFilesContent` **also** each gain a new required `viewModel: ReaderViewModel` parameter — neither currently has it. `HomeScreen` already holds `viewModel` and passes it into both at their existing call sites (`HomeScreen.kt`'s `when (homeScreenState.selectedTab)` block), alongside their existing `state`/`comics`/etc. arguments. `LibraryContent` then passes `viewModel` through to its inline Continue Reading carousel card's new `ComicCoverThumbnail` call, and to each `ComicGridCard`/`ComicListRow` call in its own bookshelf section; `LocalFilesContent` does the same for its own `ComicGridCard`/`ComicListRow` calls.

## Testing

- **`ThumbnailDecoder`**: real-file unit tests are impractical here in the same style as `HomeScreenFilterTest` (no mocks) because `android.graphics.pdf.PdfRenderer`/`android.graphics.BitmapFactory` are Android-framework classes unavailable under plain JVM unit tests in this project's existing test setup (consistent with why `PdfPageSource`/`CbzPageSource` also have no JVM unit tests today — check `app/src/test/` to confirm no existing tests cover them before writing the implementation plan). Covered instead by manual on-device verification: a local PDF and a local CBZ both show their real first-page thumbnail (not the static icon) in the grid, list, and carousel; an EPUB/CBR/MOBI (or corrupt file, if one is easy to construct) falls back cleanly to the static icon with no crash; scrolling the grid away and back doesn't cause a visible re-decode flash (cache hit); no memory-related crash or ANR scrolling through the full test library repeatedly.
- **`ReaderViewModel.loadCoverThumbnail`/cache behavior**: this is plain Kotlin logic (cache hit/miss bookkeeping) independent of the Android-only decode call, so it's a plausible candidate for a real-object unit test if the implementation plan can isolate the cache logic from `decodeThumbnail` itself (e.g. by injecting a fake decode function) — evaluate this during plan-writing rather than committing to it here, since forcing an injection seam only for testability would be scope creep if the plan's implementer finds it awkward to thread through cleanly.
