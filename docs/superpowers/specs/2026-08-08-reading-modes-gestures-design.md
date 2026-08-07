# Reading Modes & Gestures — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 2: Core Page Rendering Engine (sub-project 2 of 3)

## Goal

Make the four `ReadingMode` values (`LTR`, `RTL`, `WEBTOON`, `DUAL_SPREAD`) — already selectable in the reader's Quick Settings sheet, but cosmetically inert since sub-project 1 only ever renders a single page regardless of mode — actually change how pages are laid out and navigated. Also add pinch-to-zoom for the single/dual-page modes.

## Scope

- **In scope:** RTL tap-zone reversal, Dual-page spread layout, Webtoon continuous scroll, pinch-to-zoom for LTR/RTL/Dual-spread.
- **Out of scope:**
  - Pinch-to-zoom in Webtoon mode (combining pinch with vertical scroll is a materially bigger interaction-design problem — separate future pass).
  - Auto white-margin cropping and color filters — sub-project 3.
  - Changing the `ReadingMode` enum shape (e.g. adding an RTL variant of dual-spread) — out of scope; spreads always order left-to-right as the enum is currently defined.
  - Google Drive / non-local comics, EPUB/MOBI/CBR — unaffected, still show the sub-project 1 "not yet supported" error path before any reading-mode logic runs.

## Components

### `ReaderViewModel.kt` (modify)

Add a small result type and one new method, independent of the existing `currentPageBitmap`/`setPage`/`loadPage` machinery (which stays untouched and keeps serving LTR/RTL single-page mode exactly as it does today). `Bitmap?` alone can't distinguish "still decoding" from "decode failed" once handed to `produceState` (both look like the initial `null`), so this uses an explicit tri-state result instead of a nullable:

```kotlin
sealed interface PageLoadState {
    data object Loading : PageLoadState
    data class Loaded(val bitmap: Bitmap) : PageLoadState
    data object Failed : PageLoadState
}

suspend fun loadPageBitmap(page: Int): PageLoadState {
    val cache = pageCache ?: return PageLoadState.Failed
    return try {
        PageLoadState.Loaded(cache.getPage(page))
    } catch (e: PageDecodeException) {
        PageLoadState.Failed
    }
}
```

Called directly from Compose (`produceState<PageLoadState>(initialValue = PageLoadState.Loading, ...)`) by Webtoon row items and both sides of a Dual-spread — each call is an independent cache lookup/decode, no new caching layer. Callers branch on the three states directly (`when (val s = pageState) { is Loaded -> Image(...); Failed -> ErrorPlaceholder(); Loading -> CircularProgressIndicator() }`), mirroring sub-project 1's `pageLoadError` card but scoped to one page instead of the whole screen.

### `ui/reader/ReaderScreen.kt` (modify)

The central viewport `Box` currently always renders the single-page `when { currentPageBitmap != null -> ... }` block regardless of `state.readingMode`. That block is renamed/kept as `SinglePageReader` and now only runs for `LTR`/`RTL`. A new top-level `when (state.readingMode)` in the viewport dispatches to one of four composables:

```kotlin
when (state.readingMode) {
    ReadingMode.LTR, ReadingMode.RTL -> SinglePageReader(state, viewModel)
    ReadingMode.DUAL_SPREAD -> DualPageSpreadReader(state, viewModel)
    ReadingMode.WEBTOON -> WebtoonReader(state, viewModel)
}
```

#### `SinglePageReader` (existing block, modified)

Two changes to the existing tap-zone `detectTapGestures`:

1. **RTL reversal.** The current handler is hardcoded LTR (left tap = page - 1, right tap = page + 1). Wrap the delta:
   ```kotlin
   val isRtl = state.readingMode == ReadingMode.RTL
   when {
       offset.x < width * 0.35f -> {
           val target = if (isRtl) state.currentPage + 1 else state.currentPage - 1
           if (target in 1..state.totalPages) viewModel.setPage(target)
       }
       offset.x > width * 0.65f -> {
           val target = if (isRtl) state.currentPage - 1 else state.currentPage + 1
           if (target in 1..state.totalPages) viewModel.setPage(target)
       }
       else -> viewModel.toggleControls()
   }
   ```

2. **Pinch-to-zoom.** Local Compose state, reset on page change:
   ```kotlin
   var scale by remember { mutableFloatStateOf(1f) }
   var offsetX by remember { mutableFloatStateOf(0f) }
   var offsetY by remember { mutableFloatStateOf(0f) }
   LaunchedEffect(state.currentPage) {
       scale = 1f
       offsetX = 0f
       offsetY = 0f
   }
   ```
   A second `pointerInput` block (coexisting with the tap-zone one, Compose's standard way to combine gesture detectors on the same node) handles the transform:
   ```kotlin
   Modifier.pointerInput(Unit) {
       detectTransformGestures { _, pan, zoom, _ ->
           scale = (scale * zoom).coerceIn(1f, 4f)
           offsetX += pan.x
           offsetY += pan.y
       }
   }
   ```
   Applied to the `Image` via `Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)`.

#### `DualPageSpreadReader` (new)

Spread pairing (page 1 alone, then 2-3, 4-5, ...), as a standalone pure function so it's unit-testable without any Compose/Android dependency:

```kotlin
fun spreadPagesFor(currentPage: Int, totalPages: Int): Pair<Int, Int?> {
    if (currentPage <= 1) return 1 to null
    val left = if (currentPage % 2 == 0) currentPage else currentPage - 1
    val right = (left + 1).takeIf { it <= totalPages }
    return left to right
}
```

(Placed in `ReaderScreen.kt` as a top-level function, not nested in the composable, matching the natural-sort-function precedent from sub-project 1's `CbzPageSource.kt`.)

The composable renders a `Row` with one `Image` per non-null page from `spreadPagesFor`, each independently loaded:

```kotlin
@Composable
fun DualPageSpreadReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val (leftPage, rightPage) = spreadPagesFor(state.currentPage, state.totalPages)
    Row(modifier = Modifier.fillMaxSize().pointerInput(state.currentPage) {
        detectTapGestures(onTap = { offset ->
            val width = size.width
            when {
                offset.x < width * 0.35f -> {
                    val target = (leftPage - 1).coerceAtLeast(1)
                    viewModel.setPage(target)
                }
                offset.x > width * 0.65f -> {
                    val target = (rightPage ?: leftPage) + 1
                    if (target <= state.totalPages) viewModel.setPage(target)
                }
                else -> viewModel.toggleControls()
            }
        })
    }) {
        SpreadPageSlot(page = leftPage, viewModel = viewModel, modifier = Modifier.weight(1f))
        if (rightPage != null) {
            SpreadPageSlot(page = rightPage, viewModel = viewModel, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SpreadPageSlot(page: Int, viewModel: ReaderViewModel, modifier: Modifier = Modifier) {
    val pageState by produceState<PageLoadState>(initialValue = PageLoadState.Loading, key1 = page) {
        value = viewModel.loadPageBitmap(page)
    }
    when (val s = pageState) {
        is PageLoadState.Loaded -> Image(bitmap = s.bitmap.asImageBitmap(), contentDescription = "Page $page", modifier = modifier)
        PageLoadState.Failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
        }
        PageLoadState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
```

Pinch-to-zoom applies here too (out of scope note: the design sketch above omits it from `DualPageSpreadReader` for brevity; the implementation task adds the same `graphicsLayer`/`detectTransformGestures` pattern from `SinglePageReader`, reset on `state.currentPage` change, applied to the whole `Row` rather than per-image, so both pages zoom together).

#### `WebtoonReader` (new)

```kotlin
@Composable
fun WebtoonReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
            .filterNotNull()
            .map { it + 1 } // 0-indexed LazyColumn item -> 1-indexed page
            .distinctUntilChanged()
            .collect { page -> viewModel.setPage(page) }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.totalPages) { index ->
            val page = index + 1
            val pageState by produceState<PageLoadState>(initialValue = PageLoadState.Loading, key1 = page) {
                value = viewModel.loadPageBitmap(page)
            }
            when (val s = pageState) {
                is PageLoadState.Loaded -> Image(
                    bitmap = s.bitmap.asImageBitmap(),
                    contentDescription = "Page $page",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
                PageLoadState.Failed -> Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                }
                PageLoadState.Loading -> Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
```

Tapping still needs to toggle the top/bottom control bars (the one piece of the old tap-zone behavior Webtoon keeps) — a single `pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleControls() }) }` on the `LazyColumn`'s `Modifier`, no left/right zones since there's no discrete page-turn concept.

`setPage(page)` firing on every scroll-driven page change also runs the existing `loadPage`/prefetch path from sub-project 1 (decoding into `currentPageBitmap`, which `WebtoonReader` never reads). This is redundant work — a cache hit most of the time, since `WebtoonReader`'s own `produceState` already decoded that page — but not incorrect, and avoiding it cleanly would mean splitting `setPage` into a "just update the page-tracking state" variant and today's "update state and decode" variant. That's a reasonable follow-up if profiling ever shows it matters; not worth the added surface area now.

## Testing

- `spreadPagesFor` is a pure function (no Compose/Android dependency) — unit tested directly: page 1 always stands alone as the cover, `(1, null)`, regardless of total page count; page 2 or 3 → `(2, 3)`; page 4 or 5 → `(4, 5)`; when the non-cover page count (`totalPages - 1`) is odd, the final page has no partner, e.g. `totalPages == 6` → page 6 gives `(6, null)`.
- Gesture code (`detectTapGestures`, `detectTransformGestures`), `produceState`-driven async loading, and `LazyColumn` scroll tracking are all Compose UI with no test infrastructure in this project (same constraint as sub-project 1's `ReaderScreen.kt` changes) — verified manually on the `comicanything_test` emulator: RTL tap direction, dual-spread pairing on a real multi-page comic, Webtoon scroll-to-page-sync, and pinch-to-zoom's scale/reset behavior.
- `ReaderViewModel.loadPageBitmap` returning `PageLoadState.Failed` on `PageDecodeException` vs. `PageLoadState.Loaded` on success touches real `Bitmap`/`ComicPageSource` decode paths — no JVM test (same constraint as sub-project 1), covered by the same manual verification pass.
