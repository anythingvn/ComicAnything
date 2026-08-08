# Local Persistence Layer — Design

**Epic:** [PROJECT_TASKS.md](../../../PROJECT_TASKS.md) — Epic 3: Local Persistence Layer

## Goal

Make reading progress, real page counts, and favorites survive an app kill/relaunch. Today all of this lives only in-memory on `ComicItem` objects held by `ReaderViewModel`/`HomeScreen`'s state — a process death loses everything, and the "Continue Reading" card has shown an incorrect total page count since Epic 1 (e.g. "Page 9/1") because nothing ever writes the real page count, discovered once a comic is opened, back onto the persisted/scanned record.

## Scope

- **In scope:** persisting `currentPage`, `totalPages`, `progressPercentage`, `lastReadTimestamp`, `isFavorite` per comic (keyed by `ComicItem.id`), merging persisted state into freshly-scanned comics on launch, fixing the favorite-toggle path so it actually persists, fixing the stale-`totalPages` bug as part of this work.
- **Out of scope:** Google Drive comics (their `id`s are stable per Drive file, so the same mechanism would work, but Drive comics don't even download/cache bytes yet — Epic 4 — so there's nothing meaningful to persist progress against yet). Bookmarking a specific in-page position beyond the page number. Any cloud sync — this is on-device only, matching the app's "100% private & offline-first" design.

## Components

### `data/repository/ReadingProgressRepository.kt` (new)

```kotlin
data class ReadingProgress(
    val currentPage: Int,
    val totalPages: Int,
    val progressPercentage: Float,
    val lastReadTimestamp: Long,
    val isFavorite: Boolean
)

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_progress")

class ReadingProgressRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun getAll(): Map<String, ReadingProgress> = withContext(ioDispatcher) {
        val json = context.readingProgressDataStore.data.first()[PROGRESS_KEY] ?: return@withContext emptyMap()
        val type = object : TypeToken<Map<String, ReadingProgress>>() {}.type
        try {
            Gson().fromJson<Map<String, ReadingProgress>>(json, type) ?: emptyMap()
        } catch (e: JsonSyntaxException) {
            emptyMap()
        }
    }

    suspend fun save(comicId: String, progress: ReadingProgress) = withContext(ioDispatcher) {
        context.readingProgressDataStore.edit { prefs ->
            val current = prefs[PROGRESS_KEY]?.let {
                val type = object : TypeToken<Map<String, ReadingProgress>>() {}.type
                try {
                    Gson().fromJson<Map<String, ReadingProgress>>(it, type) ?: emptyMap()
                } catch (e: JsonSyntaxException) {
                    emptyMap()
                }
            } ?: emptyMap()
            val updated = current + (comicId to progress)
            prefs[PROGRESS_KEY] = Gson().toJson(updated)
        }
    }

    companion object {
        private val PROGRESS_KEY = stringPreferencesKey("reading_progress_json")
    }
}
```

The whole `Map<String, ReadingProgress>` is stored as one Gson-serialized JSON string under a single `Preferences` key, rather than one DataStore key per comic per field. Simpler, avoids key-explosion, and cheap to (de)serialize at personal-library scale (dozens to low hundreds of comics). Gson is already a project dependency (`app/build.gradle.kts`) — no new serialization library needed. A corrupt or missing value degrades to an empty map rather than crashing or throwing — this repository never surfaces a persistence failure to the UI; a failed read/write just means that comic's progress isn't available/saved, not a user-visible error state (matches this project's existing error-handling posture elsewhere).

**Testability:** this is fully unit-testable in a plain JVM test. `androidx.datastore:datastore-preferences`'s `PreferenceDataStoreFactory.create(scope = ..., produceFile = { File(tempDir, "test.preferences_pb") })` constructs a real, working DataStore backed by a temp file with no Android framework/Robolectric dependency — the same "real object, no mocks" testing style already used throughout this project (`TemporaryFolder`-backed `ZipFile`s in Epic 2, etc.).

### `ComicItem.kt` (modify)

`totalPages` and `lastReadTimestamp` change from `val` to `var`, matching the existing `var currentPage`/`var progressPercentage`/`var isFavorite`:

```kotlin
data class ComicItem(
    val id: String,
    val title: String,
    val pathOrUrl: String,
    val source: ComicSource,
    val format: ComicFormat,
    val coverUrl: String? = null,
    var totalPages: Int = 1,
    var currentPage: Int = 1,
    var progressPercentage: Float = 0f,
    var lastReadTimestamp: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,
    val folderName: String? = null
)
```

This is what lets `openComic` write the real page count back onto the object once it's known, fixing the "Page 9/1" bug — today `totalPages` is fixed at `1` (`LocalFileRepository`'s scan default) for the lifetime of the object.

### `ui/reader/ReaderViewModel.kt` (modify)

**`ReaderViewModel` becomes `AndroidViewModel`:**

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application)
) : AndroidViewModel(application) {
```

`MainActivity`'s `private val viewModel: ReaderViewModel by viewModels()` is unaffected — `by viewModels()` recognizes the `AndroidViewModel(Application)` shape via `AndroidViewModelFactory` and supplies the `Application` automatically, the same way `@JvmOverloads` already lets it construct today's plain-`ViewModel()` version with zero args.

**Merge persisted state on load**, in `loadLocalLibrary()`:

```kotlin
    fun loadLocalLibrary() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningLocal = true)
            val scanned = localRepo.scanStorageDirectories()
            val persisted = progressRepo.getAll()
            val merged = scanned.map { comic ->
                persisted[comic.id]?.let { progress ->
                    comic.apply {
                        currentPage = progress.currentPage
                        totalPages = progress.totalPages
                        progressPercentage = progress.progressPercentage
                        lastReadTimestamp = progress.lastReadTimestamp
                        isFavorite = progress.isFavorite
                    }
                } ?: comic
            }
            _uiState.value = _uiState.value.copy(libraryComics = merged, isScanningLocal = false)
        }
    }
```

**Persist the real page count immediately once known**, in `openComic`. The current code (as of Epic 2's fix wave) is:

```kotlin
            pageCache = cache
            _uiState.value = _uiState.value.copy(
                totalPages = pageCount,
                pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
            )
            loadPage(cache, _uiState.value.currentPage.coerceIn(1, pageCount))
```

Insert the page-count write and persist call right after the `_uiState.value.copy(...)` block, before `loadPage(...)`:

```kotlin
            pageCache = cache
            _uiState.value = _uiState.value.copy(
                totalPages = pageCount,
                pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
            )
            comic.totalPages = pageCount
            persistProgress(comic)
            loadPage(cache, _uiState.value.currentPage.coerceIn(1, pageCount))
```

(`persistProgress` is a new private helper, below.)

**Debounced persistence for page-progress writes.** A new private `Job?` field tracks the in-flight debounce. Both the immediate and debounced write paths share one private helper that builds the `ReadingProgress` record, so the field mapping exists in exactly one place:

```kotlin
    private var persistJob: Job? = null

    private fun ComicItem.toReadingProgress() = ReadingProgress(
        currentPage = currentPage,
        totalPages = totalPages,
        progressPercentage = progressPercentage,
        lastReadTimestamp = lastReadTimestamp,
        isFavorite = isFavorite
    )

    private fun persistProgress(comic: ComicItem) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            progressRepo.save(comic.id, comic.toReadingProgress())
        }
    }

    private fun schedulePersist(comic: ComicItem) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(1_500)
            progressRepo.save(comic.id, comic.toReadingProgress())
        }
    }
```

(`persistProgress` writes immediately — used for the page-count fix above and for favorite toggles. `schedulePersist` debounces — cancels any pending write and starts a fresh 1.5s delay, so a burst of `setCurrentPageIndicator` calls during a fast Webtoon scroll collapses into a single write after the burst settles.)

**Wire `schedulePersist` into `setCurrentPageIndicator`** (called by both `setPage` and Webtoon's scroll tracker, so this covers every page-change path with one addition):

```kotlin
    fun setCurrentPageIndicator(page: Int): Int {
        val clamped = page.coerceIn(1, _uiState.value.totalPages)
        _uiState.value = _uiState.value.copy(currentPage = clamped)
        _uiState.value.activeComic?.let { comic ->
            comic.currentPage = clamped
            comic.progressPercentage = clamped.toFloat() / _uiState.value.totalPages.toFloat()
            comic.lastReadTimestamp = System.currentTimeMillis()
            schedulePersist(comic)
        }
        return clamped
    }
```

**Flush immediately (no debounce) on `closeComic()` and `onCleared()`** — both already have access to the comic/cache being torn down:

```kotlin
    fun closeComic() {
        val cacheToClose = pageCache
        val comicToFlush = _uiState.value.activeComic
        pageCache = null
        _uiState.value = _uiState.value.copy(
            activeComic = null,
            currentPageBitmap = null,
            pageLoadError = null
        )
        if (comicToFlush != null) {
            persistProgress(comicToFlush)
        } else {
            persistJob?.cancel()
        }
        if (cacheToClose != null) {
            viewModelScope.launch(NonCancellable) {
                cacheToClose.close()
            }
        }
    }
```

(`persistProgress` already cancels any pending debounced job before launching its own immediate write, so there's no separate cancel needed when there's a comic to flush — only the no-active-comic branch needs its own explicit cancel, as defensive cleanup for a case that shouldn't normally be reachable.)

`onCleared()` gets the analogous treatment, same pattern as `closeComic()` above:

```kotlin
    override fun onCleared() {
        super.onCleared()
        val cacheToClose = pageCache
        val comicToFlush = _uiState.value.activeComic
        pageCache = null
        if (comicToFlush != null) {
            persistProgress(comicToFlush)
        } else {
            persistJob?.cancel()
        }
        if (cacheToClose != null) {
            viewModelScope.launch(NonCancellable) {
                cacheToClose.close()
            }
        }
    }
```

**`toggleFavorite` — new method, and `ReaderScreen.kt`'s favorite button routes through it instead of mutating `comic.isFavorite` directly:**

```kotlin
    fun toggleFavorite(comic: ComicItem) {
        comic.isFavorite = !comic.isFavorite
        persistProgress(comic)
    }
```

In `ReaderScreen.kt`, `IconButton(onClick = { comic.isFavorite = !comic.isFavorite })` becomes `IconButton(onClick = { viewModel.toggleFavorite(comic) })`.

## Testing

- `ReadingProgressRepository`: real unit tests against a temp-file-backed `DataStore<Preferences>` — save then `getAll()` round-trips correctly, multiple comics don't clobber each other, a corrupt/missing value degrades to an empty map rather than throwing.
- `ReaderViewModel.loadLocalLibrary()`'s merge logic: constructor-inject a `ReadingProgressRepository` pointed at a temp DataStore pre-populated with a known `ReadingProgress` record, confirm the scanned `ComicItem`'s fields get overwritten correctly (and that a comic with no persisted record keeps its scan defaults).
- Debounce behavior: `runTest`'s virtual time (`advanceTimeBy`/`advanceUntilIdle`) can verify `schedulePersist` doesn't write before 1.5s of no further calls, and that a rapid sequence of calls (simulating fast scroll) results in exactly one write, not one per call.
- `closeComic()`/`onCleared()` flush-immediately behavior: verify a write happens synchronously (within the same `advanceUntilIdle()`) even if called before the 1.5s debounce window would have elapsed on its own.
