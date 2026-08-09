# Library Search, Filters & Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up the Library and Local Files tabs' search, format filtering, and grid/list layout switcher, closing the gap between what the app's spec claims and what's actually built — per [docs/superpowers/specs/2026-08-09-library-search-filter-design.md](../specs/2026-08-09-library-search-filter-design.md).

**Architecture:** All new state (search query, search-active flag, active format filters, grid/list mode) is plain local Compose state hoisted in `HomeScreen`, not `ReaderViewModel`/`ReaderUiState` — pure client-side filtering over data the ViewModel has already loaded. A small `internal` pure function (`filtered()`) does the actual filtering and is unit-tested directly; the surrounding Compose UI (search field, filter chips, grid/list toggle, new `ComicListRow`) is verified manually on-device, since this project has no instrumented Compose UI tests yet.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 (`FilterChip`, existing `TextField`/`ListItem` patterns already used elsewhere in `HomeScreen.kt`).

## Global Constraints

- Kotlin 1.9.22 (`ComicFormat.entries` requires 1.9+, already satisfied)
- No mocking library — tests use real objects (plain `ComicItem` lists), not mocks
- New state lives in `HomeScreen`, not `ReaderViewModel` — do not add search/filter/layout fields to `ReaderUiState`
- The grid/list choice and any active filter are **not** persisted — plain `remember { mutableStateOf(...) }`, no DataStore
- The Drive tab (`DriveContent`) is untouched — no search, no filter chips, no layout toggle there
- `filtered()` must be `internal`, not `private` — the unit tests need to call it from a separate test file
- Existing package root: `com.comicanything.reader`

---

### Task 1: `filtered()` extension function + tests

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt` (add the function only — no other changes in this task)
- Create: `app/src/test/java/com/comicanything/reader/ui/home/HomeScreenFilterTest.kt`

**Interfaces:**
- Produces: `internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem>` — consumed by Task 2's `HomeScreen`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/ui/home/HomeScreenFilterTest.kt`:

```kotlin
package com.comicanything.reader.ui.home

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenFilterTest {

    private fun comic(id: String, title: String, format: ComicFormat) = ComicItem(
        id = id,
        title = title,
        pathOrUrl = "/fake/$id",
        source = ComicSource.LOCAL,
        format = format
    )

    private val batman = comic("1", "Batman: Year One", ComicFormat.CBZ)
    private val watchmen = comic("2", "Watchmen", ComicFormat.PDF)
    private val sandman = comic("3", "The Sandman", ComicFormat.EPUB)
    private val library = listOf(batman, watchmen, sandman)

    @Test
    fun `no query and no format filter returns everything unchanged`() {
        val result = library.filtered(query = "", formats = emptySet())

        assertEquals(library, result)
    }

    @Test
    fun `query matches by title substring case-insensitively`() {
        val result = library.filtered(query = "bat", formats = emptySet())

        assertEquals(listOf(batman), result)
    }

    @Test
    fun `query with different casing still matches`() {
        val result = library.filtered(query = "WATCHMEN", formats = emptySet())

        assertEquals(listOf(watchmen), result)
    }

    @Test
    fun `blank query is treated as no filter`() {
        val result = library.filtered(query = "   ", formats = emptySet())

        assertEquals(library, result)
    }

    @Test
    fun `format filter narrows to matching formats only`() {
        val result = library.filtered(query = "", formats = setOf(ComicFormat.PDF))

        assertEquals(listOf(watchmen), result)
    }

    @Test
    fun `multiple selected formats are combined with OR`() {
        val result = library.filtered(query = "", formats = setOf(ComicFormat.CBZ, ComicFormat.EPUB))

        assertEquals(listOf(batman, sandman), result)
    }

    @Test
    fun `query and format filter combine with AND`() {
        val result = library.filtered(query = "sandman", formats = setOf(ComicFormat.CBZ))

        assertEquals(emptyList<ComicItem>(), result)
    }

    @Test
    fun `no matches returns an empty list`() {
        val result = library.filtered(query = "nonexistent title", formats = emptySet())

        assertEquals(emptyList<ComicItem>(), result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.home.HomeScreenFilterTest" --no-daemon
```

Expected: FAIL — `filtered()` doesn't exist yet, so this won't compile.

- [ ] **Step 3: Implement `filtered()`**

In `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`, add this function anywhere at the top level of the file (e.g. right after the imports, before `HomeScreen`'s own declaration):

```kotlin
internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem> =
    filter { comic ->
        (formats.isEmpty() || comic.format in formats) &&
            (query.isBlank() || comic.title.contains(query, ignoreCase = true))
    }
```

This needs one new import: `import com.comicanything.reader.data.model.ComicFormat` (alongside the existing `import com.comicanything.reader.data.model.ComicItem` at the top of the file).

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.home.HomeScreenFilterTest" --no-daemon
```

Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt app/src/test/java/com/comicanything/reader/ui/home/HomeScreenFilterTest.kt
git commit -m "feat: add filtered() for library search/format filtering"
```

---

### Task 2: Search bar, filter chips, grid/list toggle, and `ComicListRow`

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem>` (Task 1).
- Produces: `LibraryContent` and `LocalFilesContent` both gain five new parameters (`comics: List<ComicItem>`, `selectedFormats: Set<ComicFormat>`, `onFormatToggle: (ComicFormat) -> Unit`, `isGridLayout: Boolean`, `onToggleLayout: () -> Unit`) — no other public function in this file changes signature. New composable `ComicListRow(comic: ComicItem, onClick: () -> Unit)`.

This task is pure UI wiring with no new unit-testable logic beyond Task 1's already-covered `filtered()` — verified by `assembleDebug` plus Task 3's manual on-device pass.

- [ ] **Step 1: Add the new imports**

At the top of `HomeScreen.kt`, add these imports alongside the existing ones:

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
```

(`Icons.Default.Search`/`Icons.Default.Book`/`Icons.Default.Close`'s siblings/etc. and the file's existing unaliased `import androidx.compose.foundation.lazy.items` are already present and cover every `LazyRow`/`LazyColumn`/`LazyVerticalGrid` `items {}` call added below — Kotlin resolves `items` per-receiver (`LazyListScope` vs `LazyGridScope`), so the same import already used by the existing Continue Reading `LazyRow` and the existing bookshelf `LazyVerticalGrid` also covers the new `LazyColumn` and `FormatFilterRow`'s chip `LazyRow` with no alias needed. `Icons.AutoMirrored.Filled.ViewList` needs its own import since it lives under a different sub-package than the `Icons.Default.*` icons already used in this file. `LazyColumn` itself needs its own import since it isn't used anywhere in the file yet.)

- [ ] **Step 2: Hoist the new state and wire the search UI into `HomeScreen`'s `TopAppBar`**

The current `HomeScreen` function reads (lines 37-109):

```kotlin
fun HomeScreen(
    viewModel: ReaderViewModel,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var driveUrlInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "ComicAnything",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                    label = { Text("Library") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text("Google Drive") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local Files") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                0 -> LibraryContent(state, onOpenComic, onRequestPermission)
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
                2 -> LocalFilesContent(state, onOpenComic, onRequestPermission)
            }
        }
    }
}
```

Replace the whole function with:

```kotlin
fun HomeScreen(
    viewModel: ReaderViewModel,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var driveUrlInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFormats by remember { mutableStateOf(setOf<ComicFormat>()) }
    var isGridLayout by remember { mutableStateOf(true) }

    val filteredLibraryComics = state.libraryComics.filtered(searchQuery, selectedFormats)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search your library...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "ComicAnything",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                    }
                },
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                    label = { Text("Library") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text("Google Drive") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local Files") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                0 -> LibraryContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = selectedFormats,
                    onFormatToggle = { format ->
                        selectedFormats = if (format in selectedFormats) selectedFormats - format else selectedFormats + format
                    },
                    isGridLayout = isGridLayout,
                    onToggleLayout = { isGridLayout = !isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
                2 -> LocalFilesContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = selectedFormats,
                    onFormatToggle = { format ->
                        selectedFormats = if (format in selectedFormats) selectedFormats - format else selectedFormats + format
                    },
                    isGridLayout = isGridLayout,
                    onToggleLayout = { isGridLayout = !isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
            }
        }
    }
}
```

(`DriveContent`'s call site is untouched — same three positional/named args as before, per the Global Constraints. `filteredLibraryComics` is computed once from `state.libraryComics` and passed to both the Library and Local Files tabs, since both currently source from the same `state.libraryComics` list.)

- [ ] **Step 3: Update `LibraryContent`'s signature and body**

`LibraryContent` currently reads (lines 147-255):

```kotlin
@Composable
fun LibraryContent(
    state: ReaderUiState,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    if (state.isScanningLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val inProgress = state.libraryComics.filter { it.currentPage > 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (inProgress.isNotEmpty()) {
            Text(
                text = "⚡ CONTINUE READING",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                items(inProgress) { comic ->
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .height(180.dp)
                            .clickable { onOpenComic(comic) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = comic.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                                LinearProgressIndicator(
                                    progress = comic.progressPercentage,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Page ${comic.currentPage}/${comic.totalPages}",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "📚 MY BOOKSHELF",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.libraryComics) { comic ->
                ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
            }
        }
    }
}
```

Replace the whole function with:

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
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    if (state.isScanningLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val inProgress = comics.filter { it.currentPage > 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        FormatFilterRow(selectedFormats = selectedFormats, onFormatToggle = onFormatToggle, isGridLayout = isGridLayout, onToggleLayout = onToggleLayout)

        if (inProgress.isNotEmpty()) {
            Text(
                text = "⚡ CONTINUE READING",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                items(inProgress) { comic ->
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .height(180.dp)
                            .clickable { onOpenComic(comic) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = comic.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                                LinearProgressIndicator(
                                    progress = comic.progressPercentage,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Page ${comic.currentPage}/${comic.totalPages}",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "📚 MY BOOKSHELF",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isGridLayout) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicListRow(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        }
    }
}
```

(`inProgress` and the main grid/list now both derive from the already-filtered `comics` parameter, not `state.libraryComics` directly — this is what makes both the Continue Reading carousel and the main section respect the active search/filter, per the design spec. `LazyColumn` needs `import androidx.compose.foundation.lazy.LazyColumn` added alongside the existing `import androidx.compose.foundation.lazy.LazyRow`.)

- [ ] **Step 4: Add the shared `FormatFilterRow` composable**

Add this new composable anywhere in the file (e.g. right after `LibraryContent`, before `ComicGridCard`) — it's shared by both `LibraryContent` and `LocalFilesContent` (Step 5) to avoid duplicating the filter-chip-row-plus-layout-toggle UI twice:

```kotlin
@Composable
fun FormatFilterRow(
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(ComicFormat.entries) { format ->
                FilterChip(
                    selected = format in selectedFormats,
                    onClick = { onFormatToggle(format) },
                    label = { Text(format.name) }
                )
            }
        }
        IconButton(onClick = onToggleLayout) {
            Icon(
                imageVector = if (isGridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                contentDescription = if (isGridLayout) "Switch to list view" else "Switch to grid view",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
```

(The icon shown is the *target* layout, matching common toggle-button convention — tapping it while in grid view shows a list icon meaning "switch to list," and vice versa.)

- [ ] **Step 5: Update `LocalFilesContent`'s signature and body**

`LocalFilesContent` currently reads (lines 406-449):

```kotlin
@Composable
fun LocalFilesContent(
    state: ReaderUiState,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    if (state.isScanningLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (state.libraryComics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No comics found on this device.\nAdd PDF, CBZ, CBR, EPUB, or MOBI files to your storage.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        items(state.libraryComics) { comic ->
            ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
        }
    }
}
```

Replace the whole function with:

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
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    if (state.isScanningLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (state.libraryComics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No comics found on this device.\nAdd PDF, CBZ, CBR, EPUB, or MOBI files to your storage.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        FormatFilterRow(selectedFormats = selectedFormats, onFormatToggle = onFormatToggle, isGridLayout = isGridLayout, onToggleLayout = onToggleLayout)

        if (isGridLayout) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicListRow(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        }
    }
}
```

Note this function's empty-state check (`state.libraryComics.isEmpty()`) deliberately still reads `state.libraryComics`, not the filtered `comics` parameter — an empty *scan* result ("no comics found on this device, add some files") is a different, more fundamental message than an empty *filtered* result (all comics filtered out by an active search/chip). This task does not add a distinct "no results match your search/filter" message for the latter case — the grid/list section would just render empty (no items) below the filter row. If a future task wants that copy, it can be added without touching this task's scope; not adding it now avoids introducing a new UI string this task's own spec didn't ask for.

- [ ] **Step 6: Add the `ComicListRow` composable**

Add this new composable anywhere in the file (e.g. right after `ComicGridCard`):

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
        trailingContent = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.8f)
            ) {
                Text(
                    text = comic.format.name,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
    )
}
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

Expected: all tests across the whole project pass, including Task 1's 8 new `HomeScreenFilterTest` tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt
git commit -m "feat: wire library search, format filter chips, and grid/list toggle"
```

---

### Task 3: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1-2 together as a user would, and includes the resume-tap check called out in the design spec's scope.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`.)

- [ ] **Step 2: Verify search**

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

On the Library tab (with several local comics already present from prior epics' testing, or push a couple of differently-named test files first if the library is empty), tap the search icon in the top bar. Expected: the title area is replaced by a text field. Type a partial title. Expected: the bookshelf grid narrows to matching comics live, as you type, case-insensitively. Tap the close icon. Expected: search collapses back to the app title, and the full list returns.

- [ ] **Step 3: Verify format filter chips**

With search closed, note the format chips row (PDF/CBZ/CBR/EPUB/MOBI) above the bookshelf. Tap one chip (e.g. "PDF"). Expected: only PDF comics remain visible, the chip shows a selected state. Tap a second chip (e.g. "CBZ") while the first is still selected. Expected: both PDF and CBZ comics are now shown (multi-select, OR logic) — not just one or the other. Deselect both. Expected: the full list returns.

- [ ] **Step 4: Verify the grid/list toggle**

Tap the layout toggle icon next to the filter chips. Expected: the main bookshelf section switches from the 2-column grid to a single-column list of compact rows (title, format badge, book icon) — but the "Continue Reading" carousel above it (if any comics are in progress) stays a horizontal row, unaffected. Toggle back. Expected: returns to grid.

- [ ] **Step 5: Repeat Steps 2-4 on the Local Files tab**

Switch to the Local Files tab. Expected: the same filter chip row, layout toggle, and search behavior (search icon is shared/global in the top bar, so it applies to whichever tab is currently selected) all work identically here.

- [ ] **Step 6: Verify the Continue Reading resume-tap**

Open a comic from the bookshelf, page forward a few pages, then use the back button to return to the Library tab. Expected: the comic now appears in a "Continue Reading" card showing the correct page/progress (this should already work from Epic 3, but confirm here). Tap that Continue Reading card. Expected: the reader opens directly to the same page you left off on, not page 1.

- [ ] **Step 7: Regression check**

Confirm nothing else broke: local file scanning, permission gating, opening a comic and reading normally, the Drive tab (unaffected by this epic, should look and behave exactly as before), and favorites/persistence from Epic 3. No crashes in `adb logcat` throughout this whole verification pass.

- [ ] **Step 8: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 7`, check off the search bar, format filter chips, and grid/list layout switcher bullets, and the "Confirm Continue Reading carousel resume tap..." bullet. Leave the real-cover-thumbnails bullet unchecked (sub-project 2). Add a verification note in the same style as the other epics' notes, summarizing what was tested and confirmed on `comicanything_test`.

- [ ] **Step 9: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark library search/filter/layout (Epic 7, sub-project 1) complete"
```
