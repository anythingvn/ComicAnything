# Local Persistence Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make reading progress, real page counts, and favorites survive an app kill/relaunch (Epic 3, per [PROJECT_TASKS.md](../../../PROJECT_TASKS.md)), fixing the long-standing wrong-total-page-count bug on the Continue Reading card along the way.

**Architecture:** A new `ReadingProgressRepository` backed by `androidx.datastore:datastore-preferences` stores one Gson-serialized JSON blob (comic id → `ReadingProgress`). `ReaderViewModel` becomes an `AndroidViewModel` so it can construct the repository with an `Application` context, merges persisted state into freshly-scanned comics on launch, and persists page-progress changes with a 1.5s debounce (flushed immediately on `closeComic`/`onCleared`) so Webtoon's fast scroll doesn't hammer disk I/O.

**Tech Stack:** Kotlin, `androidx.datastore:datastore-preferences`, Gson (already a dependency), kotlinx-coroutines.

## Global Constraints

- Min SDK 24, target/compile SDK 34 (`app/build.gradle.kts`)
- Kotlin 1.9.22, one new production dependency: `androidx.datastore:datastore-preferences:1.0.0`
- No mocking library — tests use real objects (temp-file-backed DataStore, real `ComicItem`s), not mocks
- Existing package root: `com.comicanything.reader`
- Google Drive comics are out of scope (Epic 4 hasn't given them anything to persist progress against yet)
- `MainActivity`'s `private val viewModel: ReaderViewModel by viewModels()` must keep working unchanged after `ReaderViewModel` becomes `AndroidViewModel` — verified by a successful `assembleDebug`, not just unit tests

---

### Task 1: ReadingProgressRepository

**Files:**
- Modify: `app/build.gradle.kts` (add the DataStore dependency)
- Create: `app/src/main/java/com/comicanything/reader/data/repository/ReadingProgressRepository.kt`
- Test: `app/src/test/java/com/comicanything/reader/data/repository/ReadingProgressRepositoryTest.kt`

**Interfaces:**
- Produces: `data class ReadingProgress(currentPage: Int, totalPages: Int, progressPercentage: Float, lastReadTimestamp: Long, isFavorite: Boolean)`, `class ReadingProgressRepository(dataStore: DataStore<Preferences>, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` with a secondary `constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)`, plus `suspend fun getAll(): Map<String, ReadingProgress>` and `suspend fun save(comicId: String, progress: ReadingProgress)` — consumed by Task 2's `ReaderViewModel` via the `Context` constructor, and by later tasks' tests via the `DataStore` constructor.

- [ ] **Step 1: Add the DataStore dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, add this line next to the existing `implementation("com.google.code.gson:gson:2.10.1")`:

```kotlin
implementation("androidx.datastore:datastore-preferences:1.0.0")
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/repository/ReadingProgressRepositoryTest.kt`:

```kotlin
package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingProgressRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "test-${System.nanoTime()}.preferences_pb") }
        )
    }

    @Test
    fun `getAll returns empty map when nothing has been saved`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val result = repo.getAll()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `save then getAll round-trips a single comic's progress`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val progress = ReadingProgress(
            currentPage = 5,
            totalPages = 20,
            progressPercentage = 0.25f,
            lastReadTimestamp = 1700000000000L,
            isFavorite = true
        )

        repo.save("comic-1", progress)
        val result = repo.getAll()

        assertEquals(progress, result["comic-1"])
    }

    @Test
    fun `saving a second comic does not clobber the first`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val progressA = ReadingProgress(1, 10, 0.1f, 1000L, false)
        val progressB = ReadingProgress(2, 20, 0.1f, 2000L, true)

        repo.save("comic-a", progressA)
        repo.save("comic-b", progressB)
        val result = repo.getAll()

        assertEquals(2, result.size)
        assertEquals(progressA, result["comic-a"])
        assertEquals(progressB, result["comic-b"])
    }

    @Test
    fun `saving the same comic id again overwrites its previous progress`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.save("comic-1", ReadingProgress(1, 10, 0.1f, 1000L, false))
        repo.save("comic-1", ReadingProgress(5, 10, 0.5f, 2000L, true))

        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals(ReadingProgress(5, 10, 0.5f, 2000L, true), result["comic-1"])
    }
}
```

Note this test file constructs `ReadingProgressRepository` with a `DataStore<Preferences>` directly (not a `Context`) — see Step 3 below for why the production constructor differs slightly from this.

- [ ] **Step 3: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.ReadingProgressRepositoryTest" --no-daemon
```

Expected: FAIL — `ReadingProgressRepository`/`ReadingProgress` don't exist yet, so this won't compile.

- [ ] **Step 4: Implement ReadingProgressRepository**

Create `app/src/main/java/com/comicanything/reader/data/repository/ReadingProgressRepository.kt`:

```kotlin
package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ReadingProgress(
    val currentPage: Int,
    val totalPages: Int,
    val progressPercentage: Float,
    val lastReadTimestamp: Long,
    val isFavorite: Boolean
)

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_progress")

class ReadingProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Production callers (ReaderViewModel) construct against a real Context; tests construct
    // directly against a temp-file-backed DataStore (see ReadingProgressRepositoryTest) without
    // needing a real Android Context in a plain JVM test. Context and DataStore<Preferences> are
    // distinct erased types, so this doesn't clash with the primary constructor above.
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.readingProgressDataStore, ioDispatcher)

    suspend fun getAll(): Map<String, ReadingProgress> = withContext(ioDispatcher) {
        val json = dataStore.data.first()[PROGRESS_KEY] ?: return@withContext emptyMap()
        decode(json)
    }

    suspend fun save(comicId: String, progress: ReadingProgress) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = prefs[PROGRESS_KEY]?.let { decode(it) } ?: emptyMap()
            val updated = current + (comicId to progress)
            prefs[PROGRESS_KEY] = Gson().toJson(updated)
        }
    }

    private fun decode(json: String): Map<String, ReadingProgress> {
        val type = object : TypeToken<Map<String, ReadingProgress>>() {}.type
        return try {
            Gson().fromJson<Map<String, ReadingProgress>>(json, type) ?: emptyMap()
        } catch (e: JsonSyntaxException) {
            emptyMap()
        }
    }

    companion object {
        private val PROGRESS_KEY = stringPreferencesKey("reading_progress_json")
    }
}
```

This has two constructors — the primary `(DataStore<Preferences>, ioDispatcher)`, used directly by tests against a temp-file-backed `DataStore` with no dependency on a real Android `Context`, and a secondary `(Context, ioDispatcher)` for production use (used by `ReaderViewModel`) that resolves the real on-device DataStore via the `readingProgressDataStore` extension property and delegates to the primary constructor. `getAll`/`save` behave identically either way.

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.ReadingProgressRepositoryTest" --no-daemon
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/comicanything/reader/data/repository/ReadingProgressRepository.kt app/src/test/java/com/comicanything/reader/data/repository/ReadingProgressRepositoryTest.kt
git commit -m "feat: add DataStore-backed ReadingProgressRepository"
```

---

### Task 2: ReaderViewModel becomes AndroidViewModel, ComicItem gains mutable totalPages/lastReadTimestamp

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/data/model/ComicItem.kt`
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingProgressRepository(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` (Task 1).
- Produces: `ComicItem.totalPages`/`.lastReadTimestamp` become `var`. `ReaderViewModel` constructor gains `application: Application` (first parameter) and `progressRepo: ReadingProgressRepository = ReadingProgressRepository(application)` (last parameter), and the class now extends `AndroidViewModel(application)` instead of `ViewModel()`. Every existing public method signature is unchanged.

This task is purely plumbing — no new behavior yet (that's Tasks 3-5). Its job is to get the `Application`/`progressRepo` wiring in place and prove `by viewModels()` still works, without touching what any method actually does.

- [ ] **Step 1: Make ComicItem's fields mutable**

In `app/src/main/java/com/comicanything/reader/data/model/ComicItem.kt`, change:

```kotlin
    val totalPages: Int = 1,
    var currentPage: Int = 1,
    var progressPercentage: Float = 0f,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
```

to:

```kotlin
    var totalPages: Int = 1,
    var currentPage: Int = 1,
    var progressPercentage: Float = 0f,
    var lastReadTimestamp: Long = System.currentTimeMillis(),
```

(Only `val` → `var` on these two lines. Everything else in the file — `id`, `title`, `pathOrUrl`, `source`, `format`, `coverUrl`, `isFavorite`, `folderName` — is unchanged.)

- [ ] **Step 2: Convert ReaderViewModel to AndroidViewModel**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add these imports alongside the existing ones:

```kotlin
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.comicanything.reader.data.repository.ReadingProgressRepository
```

Remove the now-unused `import androidx.lifecycle.ViewModel` (it's replaced by `AndroidViewModel`, which is imported above).

Change the class declaration from:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
```

to:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application)
) : AndroidViewModel(application) {
```

`progressRepo` isn't used by any method yet in this task — that starts in Task 3. Leaving it as an unused-for-now `private val` is fine; Kotlin won't warn about an unused constructor property the way it would an unused local variable.

- [ ] **Step 3: Update every test that constructs ReaderViewModel**

`app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt` has 10 test cases, all of which construct `ReaderViewModel(localRepo = repo, ...)` using named arguments — none currently pass a positional first argument, so none of them break from the new required `application` parameter being *positionally* first; they all need `application = ...` added to their argument lists. Since none of these tests run on a real Android device (plain JVM unit tests, no Robolectric) and this project has no mocking library (Global Constraint), construct a real `Application` instance directly — its no-arg constructor doesn't touch any of the Android SDK stub-jar methods that throw "not mocked" at test time, so this works cleanly:

At the top of the file, alongside the other imports, add:

```kotlin
import android.app.Application
```

Add this field near the top of the test class (right after the class declaration, before the `@get:Rule` lines):

```kotlin
class ReaderViewModelTest {

    private val fakeApplication = Application()
```

Nothing in this task's tests calls into `Application`'s Android-framework behavior beyond construction, since `progressRepo` isn't exercised by any existing test yet (Task 1's constructor variant that takes a `DataStore` directly, not a `Context`, is what Task 3+'s new tests will use — see Task 3).

Update every `ReaderViewModel(...)` construction in the file to add `application = fakeApplication` to its argument list. For example:

```kotlin
val viewModel = ReaderViewModel(localRepo = repo)
```

becomes:

```kotlin
val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo)
```

Apply this same one-argument addition to all 10 `ReaderViewModel(...)` call sites in the file — every one just needs `application = fakeApplication` added, nothing else about any test's logic changes.

- [ ] **Step 4: Run the full test suite**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: PASS — all tests, including Task 1's new repository tests and the 10 (now `application`-updated) `ReaderViewModelTest` cases.

- [ ] **Step 5: Build to confirm `by viewModels()` still works**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`. This is the real test of the `AndroidViewModel` conversion — `MainActivity.kt`'s `private val viewModel: ReaderViewModel by viewModels()` (unmodified by this task) must still compile and, at runtime, be able to reflectively construct `ReaderViewModel` with just an `Application` via `@JvmOverloads`. A successful compile here doesn't prove the runtime reflection works (that needs the emulator, in Task 6), but a failed compile would definitively prove something is wrong, so this is a necessary (not sufficient) check at this stage.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/model/ComicItem.kt app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: convert ReaderViewModel to AndroidViewModel, inject ReadingProgressRepository"
```

---

### Task 3: Merge persisted progress into scanned comics on load

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingProgressRepository(dataStore: DataStore<Preferences>, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` (Task 1's test-friendly constructor), `ReadingProgressRepository.getAll()` (Task 1).
- Produces: `loadLocalLibrary()`'s merge behavior — no new public method, this changes an existing function's body.

- [ ] **Step 1: Write the failing test**

Add this test case to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the file's final closing `}`. It needs a temp-file-backed `DataStore` (same pattern as Task 1's `ReadingProgressRepositoryTest`) pre-populated with a known `ReadingProgress` record:

```kotlin
    @Test
    fun `loadLocalLibrary merges persisted progress into scanned comics`() = runTest {
        val comicFile = File(tempFolder.newFolder("Comics"), "batman.cbz")
        comicFile.writeText("fake")
        val comicId = comicFile.absolutePath.hashCode().toString()

        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        progressRepo.save(
            comicId,
            ReadingProgress(
                currentPage = 7,
                totalPages = 30,
                progressPercentage = 0.23f,
                lastReadTimestamp = 1234567890L,
                isFavorite = true
            )
        )

        val localRepo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = localRepo,
            progressRepo = progressRepo
        )

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        val merged = viewModel.uiState.value.libraryComics.single()
        assertEquals(comicId, merged.id)
        assertEquals(7, merged.currentPage)
        assertEquals(30, merged.totalPages)
        assertEquals(0.23f, merged.progressPercentage)
        assertEquals(1234567890L, merged.lastReadTimestamp)
        assertTrue(merged.isFavorite)
    }

    @Test
    fun `loadLocalLibrary leaves scan defaults untouched for a comic with no persisted progress`() = runTest {
        File(tempFolder.newFolder("Comics"), "new_comic.cbz").writeText("fake")
        val localRepo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = localRepo)

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        val comic = viewModel.uiState.value.libraryComics.single()
        assertEquals(1, comic.currentPage)
        assertEquals(1, comic.totalPages)
        assertFalse(comic.isFavorite)
    }
```

`ComicItem.id` is derived from `file.absolutePath.hashCode().toString()` in `LocalFileRepository` — the test computes the same hash from the same file path to use as the lookup key, matching production behavior exactly rather than hardcoding an arbitrary id string that wouldn't actually match.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `loadLocalLibrary` doesn't merge persisted progress yet, so `merged.currentPage` etc. won't match; the "no persisted progress" test should already pass (nothing to merge), but the first new test fails on the assertions.

- [ ] **Step 3: Implement the merge**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, replace the existing `loadLocalLibrary` function:

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

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the 2 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: merge persisted reading progress into scanned comics on load"
```

---

### Task 4: Debounced page-progress persistence with immediate flush on close

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingProgressRepository.save(comicId, progress)` (Task 1).
- Produces: `ReaderViewModel` gains private `persistProgress(comic)` (immediate write) and `schedulePersist(comic)` (1.5s-debounced write) helpers, both used internally. `openComic` now writes `comic.totalPages` and calls `persistProgress` once the real page count is known. `setCurrentPageIndicator` calls `schedulePersist` on every page change. `closeComic`/`onCleared` flush any pending write immediately. No new public methods — all changes are to existing function bodies plus two new private helpers.

- [ ] **Step 1: Write the failing tests**

Add these test cases to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the file's final closing `}`:

```kotlin
    @Test
    fun `opening a comic persists its real page count immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbz")
        java.util.zip.ZipOutputStream(comicFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        val comic = ComicItem(
            id = "test-comic",
            title = "Test",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        val saved = progressRepo.getAll()["test-comic"]
        assertEquals(1, saved?.totalPages)
    }

    @Test
    fun `setCurrentPageIndicator debounces persistence, only writing after 1500ms of no further changes`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "debounce-comic",
            title = "Test",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF,
            totalPages = 10
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        // Rapid page changes, simulating a fast scroll -- none should individually persist yet.
        viewModel.setCurrentPageIndicator(2)
        advanceTimeBy(500)
        viewModel.setCurrentPageIndicator(3)
        advanceTimeBy(500)
        viewModel.setCurrentPageIndicator(4)
        advanceTimeBy(500)

        assertNull(progressRepo.getAll()["debounce-comic"]?.let { if (it.currentPage == 4) it else null })

        // Let the debounce window elapse with no further changes.
        advanceTimeBy(1_500)
        advanceUntilIdle()

        assertEquals(4, progressRepo.getAll()["debounce-comic"]?.currentPage)
    }

    @Test
    fun `closeComic flushes pending debounced progress immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "close-comic",
            title = "Test",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF,
            totalPages = 10
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setCurrentPageIndicator(9)
        // Deliberately do NOT advance past the 1.5s debounce window.

        viewModel.closeComic()
        advanceUntilIdle()

        assertEquals(9, progressRepo.getAll()["close-comic"]?.currentPage)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — no persistence wiring exists yet, so nothing gets saved and these assertions fail.

- [ ] **Step 3: Implement the persistence helpers and wire them in**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add these imports alongside the existing ones:

```kotlin
import com.comicanything.reader.data.repository.ReadingProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
```

Add a new private field, right after `private var pageCache: PageBitmapCache? = null`:

```kotlin
    private var persistJob: Job? = null
```

Add these three private helpers anywhere among the other private/public functions (e.g. right after `loadPage`):

```kotlin
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

In `openComic`, the current tail end reads:

```kotlin
            pageCache = cache
            _uiState.value = _uiState.value.copy(
                totalPages = pageCount,
                pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
            )
            loadPage(cache, _uiState.value.currentPage.coerceIn(1, pageCount))
```

Insert the page-count write and persist call after the `.copy(...)` block, before `loadPage(...)`:

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

Replace `setCurrentPageIndicator`:

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

Replace `closeComic`:

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

Replace `onCleared`:

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

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the 3 new ones.

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: debounce page-progress persistence, flush immediately on close"
```

---

### Task 5: toggleFavorite

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `persistProgress(comic)` (Task 4, private helper — this task calls it from within the same class).
- Produces: `fun toggleFavorite(comic: ComicItem)` — consumed by `ReaderScreen.kt`'s favorite `IconButton`.

- [ ] **Step 1: Write the failing test**

Add this test case to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the file's final closing `}`:

```kotlin
    @Test
    fun `toggleFavorite flips isFavorite and persists immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "fav-comic",
            title = "Test",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo
        )

        viewModel.toggleFavorite(comic)
        advanceUntilIdle()

        assertTrue(comic.isFavorite)
        assertEquals(true, progressRepo.getAll()["fav-comic"]?.isFavorite)

        viewModel.toggleFavorite(comic)
        advanceUntilIdle()

        assertFalse(comic.isFavorite)
        assertEquals(false, progressRepo.getAll()["fav-comic"]?.isFavorite)
    }
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `toggleFavorite` doesn't exist yet, so this won't compile.

- [ ] **Step 3: Implement toggleFavorite and wire it into ReaderScreen**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add this method anywhere among the other public functions (e.g. right after `closeComic`):

```kotlin
    fun toggleFavorite(comic: ComicItem) {
        comic.isFavorite = !comic.isFavorite
        persistProgress(comic)
    }
```

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt`, find:

```kotlin
                    IconButton(onClick = { comic.isFavorite = !comic.isFavorite }) {
```

and replace it with:

```kotlin
                    IconButton(onClick = { viewModel.toggleFavorite(comic) }) {
```

(This is the only change in `ReaderScreen.kt` — everything else in that `IconButton` block, and the rest of the file, is unchanged.)

- [ ] **Step 4: Run test to verify it passes**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the new one.

- [ ] **Step 5: Build to confirm ReaderScreen.kt still compiles**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/main/java/com/comicanything/reader/ui/reader/ReaderScreen.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: add toggleFavorite, route the reader's favorite button through it"
```

---

### Task 6: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1-5 together as a user would, and is the first real confirmation that `by viewModels()` correctly constructs the now-`AndroidViewModel` `ReaderViewModel` at runtime (Task 2's build-only check couldn't prove this).

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`. Grant storage access if not already: `"$ADB" shell appops set com.comicanything.reader MANAGE_EXTERNAL_STORAGE allow`.)

- [ ] **Step 2: Confirm the app launches at all**

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Expected: the Library screen appears, no crash. This is the real-device proof that `by viewModels()` successfully constructs the `AndroidViewModel`-based `ReaderViewModel` via reflection — if the `@JvmOverloads` constructor shape were wrong, this is where it would surface as a runtime crash, not a compile error.

- [ ] **Step 3: Verify page count and progress persist across a kill**

Open a real multi-page comic (reuse one already on the device from Epic 2's verification, or push a fresh one). Page forward a few times — note the exact page shown. Force-stop the app entirely (not just backgrounding):

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Open the same comic again. Expected: it resumes at the page you were on (not page 1), and the Library's "Continue Reading" card shows the correct total page count (not "Page N / 1" — this is the bug this epic set out to fix).

- [ ] **Step 4: Verify the debounce doesn't lose progress on a real quick exit**

Open a comic, page forward once, and *immediately* (within ~1 second, faster than the 1.5s debounce window) tap the back button to return to the library. Force-stop and relaunch as in Step 3. Expected: the page you moved to is still remembered — proving `closeComic`'s immediate flush actually beats the debounce window in a real scenario, not just in the virtual-time unit test.

- [ ] **Step 5: Verify favorites persist**

Open a comic, tap the bookmark/favorite icon in the top bar. Force-stop and relaunch. Expected: the comic still shows as favorited (bookmark icon filled) when reopened.

- [ ] **Step 6: Regression check**

Confirm Epic 1/2 behavior is unaffected: local file scanning still works, permission gating still works, all four reading modes still work, pinch-to-zoom still works, no crashes in `adb logcat` throughout this whole verification pass.

- [ ] **Step 7: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 3`, check off all four items:
- Add `androidx.datastore:datastore-preferences` dependency
- Persist per-comic reading state (`currentPage`, `progressPercentage`, `lastReadTimestamp`) keyed by comic id
- Persist favorites/bookmarks
- On app launch, merge persisted state into freshly-scanned `ComicItem`s
- Verify progress survives an app kill + relaunch

Change Epic 3's status marker from `⬜` to `✅`. Add a short italicized verification note underneath the heading, in the same style as Epic 2's notes, summarizing what was tested and confirmed on `comicanything_test` — including that this epic also fixed the pre-existing wrong-total-page-count bug on the Continue Reading card.

- [ ] **Step 8: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark Epic 3 (local persistence layer) complete"
```
