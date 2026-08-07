# Reading Modes & Gestures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the reader's four `ReadingMode` values actually change page layout and navigation (Epic 2, sub-project 2 of 3, per [PROJECT_TASKS.md](../../../PROJECT_TASKS.md)), and add pinch-to-zoom to the single/dual-page modes.

**Architecture:** `ReaderViewModel` gains a `loadPageBitmap(page): PageLoadState` accessor independent of the existing single-page `currentPageBitmap` flow. `ReaderScreen`'s single always-on placeholder-replacement block is extracted into `SinglePageReader.kt` (gains RTL tap reversal + pinch-zoom), and two new composables — `DualPageSpreadReader.kt` and `WebtoonReader.kt` — are built alongside it using the new per-page accessor. `ReaderScreen.kt` then dispatches to one of the three based on `state.readingMode`.

**Tech Stack:** Kotlin, Jetpack Compose (`detectTransformGestures`, `graphicsLayer`, `LazyColumn`, `produceState`, `snapshotFlow`), kotlinx-coroutines.

## Global Constraints

- Min SDK 24, target/compile SDK 34 (`app/build.gradle.kts`)
- Kotlin 1.9.22, no new production dependencies
- No mocking library — tests use real objects, not mocks
- Existing package root: `com.comicanything.reader`
- Google Drive comics, EPUB/MOBI/CBR, and non-local comics are unaffected — they never reach reading-mode logic (they hit the existing `pageLoadError` "not yet supported" path from sub-project 1 first, since `pageCache` is never assigned for them)
- Compose UI/gesture code (all of `ReaderScreen.kt` and the three new composable files) has no automated test in this project — verified manually on the `comicanything_test` emulator (Task 6). Only pure, non-Compose functions (`spreadPagesFor`, the guard-clause branch of `loadPageBitmap`) get unit tests.

---

### Task 1: PageLoadState and loadPageBitmap in ReaderViewModel

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Produces: `sealed interface PageLoadState { data object Loading; data class Loaded(val bitmap: Bitmap); data object Failed }` and `suspend fun ReaderViewModel.loadPageBitmap(page: Int): PageLoadState` — consumed by Tasks 3 and 4's composables via `produceState`.

- [ ] **Step 1: Write the failing test**

Add this test case to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the file's final closing `}`:

```kotlin
    @Test
    fun `loadPageBitmap returns Failed when no comic is open`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(localRepo = repo, ioDispatcher = Dispatchers.Unconfined)

        val result = viewModel.loadPageBitmap(1)

        assertEquals(PageLoadState.Failed, result)
    }
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `PageLoadState` and `loadPageBitmap` don't exist yet, so this won't compile.

- [ ] **Step 3: Implement PageLoadState and loadPageBitmap**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add this right after the `ReaderUiState` data class's closing `)`, before `class ReaderViewModel`:

```kotlin
sealed interface PageLoadState {
    data object Loading : PageLoadState
    data class Loaded(val bitmap: Bitmap) : PageLoadState
    data object Failed : PageLoadState
}
```

Add this method to `ReaderViewModel`, anywhere among the other public functions (e.g. right after `loadPage`):

```kotlin
    suspend fun loadPageBitmap(page: Int): PageLoadState {
        val cache = pageCache ?: return PageLoadState.Failed
        return try {
            PageLoadState.Loaded(cache.getPage(page))
        } catch (e: PageDecodeException) {
            PageLoadState.Failed
        }
    }
```

No new imports needed — `Bitmap` and `PageDecodeException` are already imported in this file.

- [ ] **Step 4: Run test to verify it passes**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the new one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: add PageLoadState and per-page async bitmap loading"
```

---

### Task 2: SinglePageReader — extract with RTL reversal and pinch-to-zoom

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/ui/reader/SinglePageReader.kt`

**Interfaces:**
- Consumes: `ReaderUiState.currentPageBitmap/pageLoadError/currentPage/totalPages/readingMode/autoCropMargins` (existing), `ReaderViewModel.setPage`/`toggleControls` (existing).
- Produces: `@Composable fun SinglePageReader(comic: ComicItem, state: ReaderUiState, viewModel: ReaderViewModel)` — consumed by Task 5's dispatch in `ReaderScreen.kt`.

This task only creates the file — it is not wired into `ReaderScreen.kt` yet (that's Task 5, once all three mode composables exist). An unreferenced public composable compiles fine; `assembleDebug` succeeding is this task's verification. No automated test (Compose UI/gestures).

- [ ] **Step 1: Create SinglePageReader.kt**

Create `app/src/main/java/com/comicanything/reader/ui/reader/SinglePageReader.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ReadingMode

@Composable
fun SinglePageReader(
    comic: ComicItem,
    state: ReaderUiState,
    viewModel: ReaderViewModel
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.readingMode) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width
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
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            state.currentPageBitmap != null -> {
                val bitmap = state.currentPageBitmap!!
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "${comic.title}, page ${state.currentPage}",
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.85f)
                        .padding(if (state.autoCropMargins) 0.dp else 16.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            state.pageLoadError != null -> {
                val error = state.pageLoadError!!
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
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
    }
}
```

(`val bitmap = state.currentPageBitmap!!` / `val error = state.pageLoadError!!` — reusing the exact non-null-capture pattern sub-project 1 established for this same content, needed because `state` further up its call chain originates from a `by collectAsState()` delegate that blocks Kotlin's smart-cast.)

- [ ] **Step 2: Build to confirm it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/SinglePageReader.kt
git commit -m "feat: extract SinglePageReader with RTL tap reversal and pinch-to-zoom"
```

---

### Task 3: DualPageSpreadReader

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/ui/reader/DualPageSpreadReader.kt`
- Test: `app/src/test/java/com/comicanything/reader/ui/reader/DualPageSpreadReaderTest.kt`

**Interfaces:**
- Consumes: `ReaderUiState.currentPage/totalPages` (existing), `ReaderViewModel.loadPageBitmap`/`setPage`/`toggleControls` (Task 1 and existing).
- Produces: `fun spreadPagesFor(currentPage: Int, totalPages: Int): Pair<Int, Int?>` (standalone, top-level — unit tested), `@Composable fun DualPageSpreadReader(state: ReaderUiState, viewModel: ReaderViewModel)` — consumed by Task 5's dispatch.

`spreadPagesFor` is pure Kotlin (no Compose/Android dependency) and gets a real unit test, following TDD. The composable itself has no automated test (Compose UI) — building successfully is this task's verification for that part.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/comicanything/reader/ui/reader/DualPageSpreadReaderTest.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DualPageSpreadReaderTest {

    @Test
    fun `page 1 always stands alone as the cover`() {
        assertEquals(1 to null, spreadPagesFor(currentPage = 1, totalPages = 10))
    }

    @Test
    fun `page 1 alone when the comic has only one page`() {
        assertEquals(1 to null, spreadPagesFor(currentPage = 1, totalPages = 1))
    }

    @Test
    fun `pages 2 and 3 pair together`() {
        assertEquals(2 to 3, spreadPagesFor(currentPage = 2, totalPages = 10))
        assertEquals(2 to 3, spreadPagesFor(currentPage = 3, totalPages = 10))
    }

    @Test
    fun `pages 4 and 5 pair together`() {
        assertEquals(4 to 5, spreadPagesFor(currentPage = 4, totalPages = 10))
        assertEquals(4 to 5, spreadPagesFor(currentPage = 5, totalPages = 10))
    }

    @Test
    fun `an odd-length comic's final page has no partner`() {
        assertEquals(6 to null, spreadPagesFor(currentPage = 6, totalPages = 6))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.DualPageSpreadReaderTest" --no-daemon
```

Expected: FAIL — `spreadPagesFor` doesn't exist yet, so this won't compile.

- [ ] **Step 3: Implement DualPageSpreadReader.kt**

Create `app/src/main/java/com/comicanything/reader/ui/reader/DualPageSpreadReader.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

fun spreadPagesFor(currentPage: Int, totalPages: Int): Pair<Int, Int?> {
    if (currentPage <= 1) return 1 to null
    val left = if (currentPage % 2 == 0) currentPage else currentPage - 1
    val right = (left + 1).takeIf { it <= totalPages }
    return left to right
}

@Composable
fun DualPageSpreadReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val (leftPage, rightPage) = spreadPagesFor(state.currentPage, state.totalPages)

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .pointerInput(state.currentPage) {
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
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
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
        is PageLoadState.Loaded -> Image(
            bitmap = s.bitmap.asImageBitmap(),
            contentDescription = "Page $page",
            modifier = modifier
        )
        PageLoadState.Failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
        }
        PageLoadState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.DualPageSpreadReaderTest" --no-daemon
```

Expected: PASS (5 tests).

- [ ] **Step 5: Build to confirm the whole file compiles**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/DualPageSpreadReader.kt app/src/test/java/com/comicanything/reader/ui/reader/DualPageSpreadReaderTest.kt
git commit -m "feat: add dual-page spread reading mode with print-comic page pairing"
```

---

### Task 4: WebtoonReader

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/ui/reader/WebtoonReader.kt`

**Interfaces:**
- Consumes: `ReaderUiState.totalPages` (existing), `ReaderViewModel.loadPageBitmap`/`setPage`/`toggleControls` (Task 1 and existing).
- Produces: `@Composable fun WebtoonReader(state: ReaderUiState, viewModel: ReaderViewModel)` — consumed by Task 5's dispatch.

No automated test (Compose UI, `LazyColumn` scroll behavior). `assembleDebug` succeeding is this task's verification.

- [ ] **Step 1: Create WebtoonReader.kt**

Create `app/src/main/java/com/comicanything/reader/ui/reader/WebtoonReader.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

@Composable
fun WebtoonReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
            .filterNotNull()
            .map { it + 1 }
            .distinctUntilChanged()
            .collect { page -> viewModel.setPage(page) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { viewModel.toggleControls() })
            }
    ) {
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
                PageLoadState.Failed -> Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                }
                PageLoadState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
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
git add app/src/main/java/com/comicanything/reader/ui/reader/WebtoonReader.kt
git commit -m "feat: add Webtoon continuous-scroll reading mode with scroll-driven page tracking"
```

---

### Task 5: Wire reading-mode dispatch into ReaderScreen

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`

**Interfaces:**
- Consumes: `SinglePageReader` (Task 2), `DualPageSpreadReader` (Task 3), `WebtoonReader` (Task 4) — all in the same package, no import needed.

No automated test (Compose UI). A successful `assembleDebug` is this task's verification — it's also the point where all three new composables actually get exercised by the compiler as real call sites for the first time.

- [ ] **Step 1: Replace the placeholder-rendering block with the reading-mode dispatch**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`, replace the entire "Canvas Interactive Reader Viewport" block — the inner `Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(...) }, contentAlignment = Alignment.Center) { when { ... } }`, currently sitting directly inside the outer `Box(fillMaxSize + background(bgColor))` — with:

```kotlin
        // Canvas Interactive Reader Viewport
        when (state.readingMode) {
            ReadingMode.LTR, ReadingMode.RTL -> SinglePageReader(comic, state, viewModel)
            ReadingMode.DUAL_SPREAD -> DualPageSpreadReader(state, viewModel)
            ReadingMode.WEBTOON -> WebtoonReader(state, viewModel)
        }
```

This is a straight replacement — the "Top Bar Overlay", "Bottom Scrubber Bar Overlay", and "Quick Settings Bottom Sheet" blocks that follow it in the file are unchanged.

- [ ] **Step 2: Remove now-unused imports**

The old inline block was the only place in this file using these — remove all of them from the top of `ReaderScreen.kt`:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
```

Leave every other existing import in the file untouched — `Color`, `FontWeight`, `dp`, `sp`, `ReadingMode`, `ColorFilterMode`, etc. are all still used elsewhere in `ReaderScreen.kt` (the top bar, bottom scrubber, and quick-settings sheet).

- [ ] **Step 3: Build to confirm it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`. If it fails on an unused-import warning being treated as an error, or on a genuinely still-used import you removed by mistake, restore that specific import — don't remove more than the 5 listed above.

- [ ] **Step 4: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests pass — this task doesn't change any tested logic, but confirms nothing else broke.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt
git commit -m "feat: dispatch reader viewport to the active reading mode"
```

---

### Task 6: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1-5 together as a user would.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`. If `MANAGE_EXTERNAL_STORAGE` isn't already granted from a prior session: `"$ADB" shell appops set com.comicanything.reader MANAGE_EXTERNAL_STORAGE allow`.)

Use a real multi-page (10+) local PDF or CBZ already on the device from sub-project 1's verification, or generate a fresh one with Python + Pillow the same way sub-project 1's Task 6 did.

- [ ] **Step 2: Verify RTL tap reversal**

Open a comic, open Quick Settings (top-right tune icon), select **RTL**. Tap the right 35% of the screen — expect the page to go *backward* (toward page 1). Tap the left 35% — expect the page to go *forward*. Confirm this is the opposite of LTR's behavior (switch back to LTR in Quick Settings and confirm the directions flip back).

- [ ] **Step 3: Verify Dual-page spread pairing and navigation**

Switch to **Dual-Spread**. Confirm: on page 1, only a single page shows (the "cover" case), not two. Tap right — confirm it advances to a spread showing pages 2 and 3 side by side. Continue tapping right — confirm the next spread is 4-5, not 3-4. Tap left from the 4-5 spread — confirm it returns to 2-3, and tapping left again returns to the standalone page 1.

- [ ] **Step 4: Verify Webtoon continuous scroll and scroll-driven page tracking**

Switch to **Webtoon**. Confirm pages render as a continuous vertical list (no discrete "turn"). Scroll down through several pages and open the bottom scrubber — confirm the "Page X / Y" counter and percentage update to roughly match what's visible, not stuck at the page you entered Webtoon mode on. Drag the scrubber slider to a distant page — confirm the list scrolls to roughly that position (a `LazyColumn` driven by `setPage` re-triggering the `snapshotFlow` collector, or at minimum no crash — exact scroll-to-position snapping behavior is a nice-to-have, not a hard requirement of this task; note in your findings if it doesn't scroll and whether that's acceptable).

- [ ] **Step 5: Verify pinch-to-zoom in LTR/RTL and Dual-Spread**

In LTR mode, pinch to zoom in on the current page — confirm the image scales up (up to roughly 4x) and pans with a two-finger drag. Navigate to the next page — confirm the zoom resets to 1x (doesn't carry over). Repeat a quick zoom check in Dual-Spread mode (both pages should scale together as one unit, since they're zoomed via the shared `Row`). Confirm Webtoon mode does NOT respond to pinch (out of scope for this plan) — scrolling should work normally there.

- [ ] **Step 6: Regression check — confirm sub-project 1 behavior is untouched**

In LTR mode (the default), confirm: tapping left/right still turns pages normally, tapping center still toggles the top/bottom bars, the back button still returns to the library, and opening an unsupported-format comic (EPUB/CBR/MOBI, or one with zero readable pages) still shows the inline error card instead of a crash — Tasks 1-5 didn't touch any of this, but it's cheap to confirm nothing regressed.

- [ ] **Step 7: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 2`, check off:
- Make reading modes act on real pages: LTR/RTL page order, Webtoon continuous vertical scroll, Dual-page spread
- Pinch-to-zoom gesture on the real page image

Leave the remaining two Epic 2 items unchecked (color filters, auto-crop — sub-project 3). Epic 2 stays `🟨` (still partial — one sub-project left). Add a short italicized verification note underneath the heading, in the same style as the sub-project 1 note already there, summarizing what was tested and confirmed on `comicanything_test`.

- [ ] **Step 8: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark reading-modes-gestures portion of Epic 2 complete"
```
