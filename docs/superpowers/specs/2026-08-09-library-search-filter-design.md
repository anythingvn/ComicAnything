# Library Search, Filters & Layout — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 7: Library/Home UX Completion (sub-project 1 of 2)

## Goal

Wire up the Library and Local Files tabs' search, format filtering, and grid/list layout switcher — closing the gap between what the app's spec claims and what's actually built (`HomeScreen.kt`'s `TopAppBar` currently has no search field at all; there's no format filtering; only a fixed 2-column grid exists). Also confirm the "Continue Reading" carousel's resume-tap already opens comics at the correct persisted page, which Epic 3's merge-on-load wiring should already guarantee but has not been explicitly re-verified since.

## Scope

- **In scope:** a search field in the `TopAppBar` that filters the current tab's list live by title; format filter chips (`PDF`/`CBZ`/`CBR`/`EPUB`/`MOBI`, multi-select) on the Library and Local Files tabs; a grid/list layout toggle for the main bookshelf section; manual on-device confirmation that tapping a "Continue Reading" card resumes at the correct page.
- **Out of scope (deferred to sub-project 2):** real cover thumbnails (decoding a comic's first page for local files, or loading `coverUrl`/`thumbnailLink` via Coil for Drive comics) — a meaningfully bigger, separate technical concern involving off-thread decoding and its own caching strategy, built after this sub-project is done.
- **Explicitly not touched:** the Google Drive tab's own list (`DriveContent`) — different data source (`state.driveComics`, not `state.libraryComics`), and out of scope per this split.
- **Explicitly not persisted:** the grid/list choice and any active format filter reset to defaults (grid, no filter) on every app launch — plain in-memory Compose state, not DataStore. This is a low-stakes display preference that doesn't warrant the persistence machinery Epic 3/4's `DataStore` repositories exist for.

## Components

### State ownership

All new state — search query, search-active flag, active format filters, grid/list mode — lives as plain local Compose state (`remember { mutableStateOf(...) }`) inside `HomeScreen`, **not** in `ReaderViewModel`/`ReaderUiState`. This is pure client-side filtering/display logic over data the ViewModel has already loaded (`state.libraryComics`); it doesn't need to survive process death, doesn't drive any new data fetch, and keeping it out of the ViewModel avoids growing a class the Epic 4 final review already flagged as accumulating unrelated responsibilities (5 constructor dependencies, 24+ methods spanning permissions, scanning, Drive, page decoding, progress persistence, and now Drive connection state).

### Search bar

`HomeScreen`'s `TopAppBar` (currently `HomeScreen.kt:50-69`, just an icon + "ComicAnything" title, no actions) gains a search `IconButton` action. Tapping it flips `isSearchActive` to `true`; when active, the title `Row` is replaced by a `TextField` bound to `searchQuery`, with a leading search icon and a trailing close icon that collapses search (`isSearchActive = false`) and clears `searchQuery`. This is the standard Material3 "expand search in place" pattern — no new screen, no navigation.

```kotlin
var searchQuery by remember { mutableStateOf("") }
var isSearchActive by remember { mutableStateOf(false) }

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
                Icon(imageVector = Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(text = "ComicAnything", fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
)
```

Search applies to whichever tab (`selectedTab`) is currently showing — it filters `LibraryContent`'s or `LocalFilesContent`'s list, not a separate cross-tab results view. The Drive tab already has its own, unrelated `OutlinedTextField` for pasting a folder URL (`DriveContent`, `HomeScreen.kt:357-368`) — untouched, no interaction with this search field.

### Format filter chips

A `LazyRow` of Material3 `FilterChip`s, one per `ComicFormat` entry (`PDF`, `CBZ`, `CBR`, `EPUB`, `MOBI`), rendered at the top of `LibraryContent` and `LocalFilesContent` (below the "Continue Reading" section header, above the bookshelf grid — see layout note below). Tapping a chip toggles its format's membership in `selectedFormats: Set<ComicFormat>`; multiple chips can be selected simultaneously (a comic matches if its format is in the set, or if the set is empty — no filter applied).

`selectedFormats` is hoisted in `HomeScreen` (`var selectedFormats by remember { mutableStateOf(setOf<ComicFormat>()) }`, alongside `searchQuery`/`isSearchActive`/`isGridLayout`) — it's the single source of truth `HomeScreen` also needs to compute `.filtered()`. The chip row itself renders inside `LibraryContent`/`LocalFilesContent`, receiving the current set and a toggle callback as parameters (see the signature below), standard Compose state-hoisting:

```kotlin
// Inside LibraryContent/LocalFilesContent, using the selectedFormats/onFormatToggle parameters:
LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
    items(ComicFormat.entries) { format ->
        FilterChip(
            selected = format in selectedFormats,
            onClick = { onFormatToggle(format) },
            label = { Text(format.name) }
        )
    }
}
```

### Grid/list layout toggle

An `IconButton` (grid icon ⇄ list icon, `Icons.Default.GridView`/`Icons.AutoMirrored.Filled.ViewList`) placed next to the filter chip row, inside `LibraryContent`/`LocalFilesContent`, calling the `onToggleLayout` callback. `isGridLayout: Boolean` (default `true`) is hoisted in `HomeScreen` alongside the other new state, following the same pattern as `selectedFormats` above. Applies only to the main bookshelf section — the "Continue Reading" carousel (`HomeScreen.kt:172-234`) stays a horizontal `LazyRow` regardless of this toggle, since a horizontal carousel and a vertical grid/list are different UI shapes for a different purpose (recency-ordered highlights vs. the full collection).

Grid mode (existing): `LazyVerticalGrid(columns = GridCells.Fixed(2))` of `ComicGridCard` (`HomeScreen.kt:257-307`, unchanged).

List mode (new): `LazyColumn` of a new `ComicListRow` composable — a compact single-line row (cover-icon placeholder, title, format badge), modeled on the Drive tab's existing `ListItem` usage (`HomeScreen.kt:390-399`) for visual consistency with the rest of the app rather than inventing a new list-row style:

```kotlin
@Composable
fun ComicListRow(comic: ComicItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(comic.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Surface(shape = RoundedCornerShape(4.dp), color = Color.Black.copy(alpha = 0.8f)) {
                Text(comic.format.name, color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
    )
}
```

### Filtering logic

One small, pure, `internal` (not `private`) extension function in `HomeScreen.kt`, used by both `LibraryContent` and `LocalFilesContent`. `internal`, not `private`, specifically so the unit tests described below — living in a separate file under `app/src/test/...`, per this project's established convention — can call it directly; a Kotlin file-`private` top-level function isn't visible outside its own file, even to a test in the same module:

```kotlin
internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem> =
    filter { comic ->
        (formats.isEmpty() || comic.format in formats) &&
        (query.isBlank() || comic.title.contains(query, ignoreCase = true))
    }
```

`HomeScreen` computes `state.libraryComics.filtered(searchQuery, selectedFormats)` once and passes the result down to `LibraryContent`/`LocalFilesContent` in place of the raw `state.libraryComics` list they currently receive directly from `state`. Both the "Continue Reading" carousel and the main grid/list see the same filtered set — searching or filtering hides non-matching comics everywhere on the tab, not just the main grid, so what's visible is always fully consistent with the active search/filter.

### `LibraryContent`/`LocalFilesContent` signature changes

Both composables currently take `state: ReaderUiState` and read `state.libraryComics` directly. They change to take the already-filtered `List<ComicItem>` plus the new UI-state values they need to render (the filter chip row and the grid/list toggle both live inside these composables, not in `HomeScreen` itself, since they're tab-specific — the Drive tab doesn't have them):

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

(same shape for `LocalFilesContent`). `state` is still needed for `hasStoragePermission`/`isScanningLocal` (unaffected by this change); `comics` replaces direct reads of `state.libraryComics` for the "Continue Reading"/`inProgress` filter and the main grid/list.

### Resume-tap verification

No code change expected — `openComic(comic)` (`ReaderViewModel.kt`) already sets `currentPage = comic.currentPage` from the tapped `ComicItem`, and Epic 3's `loadLocalLibrary()` merge already writes the persisted `currentPage` back onto each scanned comic before it ever reaches a "Continue Reading" card. This item is a manual on-device confirmation step in the implementation plan (open a comic partway, back out, confirm the Continue Reading card's tap re-opens at the same page), not a design decision.

## Testing

- **`filtered()`**: real-object unit tests with plain `ComicItem` lists — no query/no filter returns everything; a query matches by title substring, case-insensitive; a format filter narrows to matching formats; query and format filter combine (AND, not OR); an empty result when nothing matches.
- **Compose UI pieces** (search field, filter chips, grid/list toggle, `ComicListRow`): not meaningfully unit-testable in this project's current setup (no instrumented Compose UI tests exist yet — that's Epic 8's backlog, not this sub-project's). Covered by manual on-device verification: search narrows the list as you type and clears correctly on close; each format chip toggles independently and multiple can combine; the grid/list toggle switches the main section's layout without affecting the Continue Reading carousel; the resume-tap check described above.
