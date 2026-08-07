# Runtime Storage Permissions & Local Files Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give ComicAnything real, permission-gated access to comic/ebook files on the device (Epic 1 of [PROJECT_TASKS.md](../../../PROJECT_TASKS.md)), replacing the silent demo-data fallback with an honest UI driven by actual permission and scan state.

**Architecture:** `MainActivity` requests `MANAGE_EXTERNAL_STORAGE` (API 30+, via Settings redirect) or `READ_EXTERNAL_STORAGE` (API 24–29, standard dialog) through a new `StoragePermissions` utility, and reports grant state into `ReaderViewModel`. The ViewModel only triggers a recursive `LocalFileRepository` scan once permission is confirmed, and clears the library if permission is revoked. `HomeScreen`'s Library and Local Files tabs show a "Grant Access" card until permission is granted, and the Local Files tab is wired to real scan results instead of static placeholder text.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX ViewModel/StateFlow, JUnit4 + kotlinx-coroutines-test for unit tests.

## Global Constraints

- Min SDK 24, target/compile SDK 34 (`app/build.gradle.kts`)
- Kotlin 1.9.22, no new production dependencies beyond what's already declared
- No mocking library in the project — tests use real objects (temp directories, constructor-injected repositories), not mocks
- Existing package root: `com.comicanything.reader`

---

### Task 1: Recursive, permission-honest local file scanning

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/data/repository/LocalFileRepository.kt`
- Modify: `app/build.gradle.kts` (add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")`)
- Test: `app/src/test/java/com/comicanything/reader/data/repository/LocalFileRepositoryTest.kt`

**Interfaces:**
- Produces: `class LocalFileRepository(rootPath: String = LocalFileRepository.DEFAULT_ROOT)` with `suspend fun scanStorageDirectories(): List<ComicItem>` (no parameters — root is fixed at construction). `LocalFileRepository.DEFAULT_ROOT` is a public companion `const val String = "/storage/emulated/0"`.

- [ ] **Step 1: Add the coroutines-test dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, add this line next to the existing `testImplementation("junit:junit:4.13.2")`:

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/repository/LocalFileRepositoryTest.kt`:

```kotlin
package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalFileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `scan finds files in nested subfolders`() = runTest {
        val nested = tempFolder.newFolder("Comics", "Manga")
        File(nested, "solo_leveling.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.scanStorageDirectories()

        assertEquals(1, result.size)
        assertEquals("solo_leveling.cbz", result[0].title)
        assertEquals(ComicFormat.CBZ, result[0].format)
    }

    @Test
    fun `scan ignores files with unsupported extensions`() = runTest {
        tempFolder.newFile("notes.txt")
        tempFolder.newFile("comic.pdf")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.scanStorageDirectories()

        assertEquals(1, result.size)
        assertEquals(ComicFormat.PDF, result[0].format)
    }

    @Test
    fun `scan of empty directory returns empty list`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.scanStorageDirectories()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `scan of nonexistent directory returns empty list`() = runTest {
        val repo = LocalFileRepository(rootPath = "${tempFolder.root.absolutePath}/does-not-exist")

        val result = repo.scanStorageDirectories()

        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run (PowerShell, from repo root):

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.LocalFileRepositoryTest" --no-daemon
```

Expected: FAIL — `LocalFileRepository` has no `rootPath` constructor parameter yet and `scanStorageDirectories()` still requires a `directoryPath` argument, so this won't compile.

- [ ] **Step 4: Rewrite the implementation**

Replace the full contents of `app/src/main/java/com/comicanything/reader/data/repository/LocalFileRepository.kt`:

```kotlin
package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalFileRepository(
    private val rootPath: String = DEFAULT_ROOT
) {

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0"
        private const val MAX_SCAN_DEPTH = 8
    }

    suspend fun scanStorageDirectories(): List<ComicItem> = withContext(Dispatchers.IO) {
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .mapNotNull { file -> formatFor(file.name)?.let { format -> file to format } }
            .distinctBy { (file, _) -> file.absolutePath }
            .map { (file, format) ->
                ComicItem(
                    id = file.absolutePath.hashCode().toString(),
                    title = file.name,
                    pathOrUrl = file.absolutePath,
                    source = ComicSource.LOCAL,
                    format = format,
                    totalPages = 1
                )
            }
            .toList()
    }

    private fun formatFor(name: String): ComicFormat? = when {
        name.endsWith(".pdf", ignoreCase = true) -> ComicFormat.PDF
        name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
        name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
        name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
        name.endsWith(".mobi", ignoreCase = true) -> ComicFormat.MOBI
        else -> null
    }
}
```

This removes the hardcoded demo-item fallback entirely and the old single-level `directoryPath: String?` parameter.

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.LocalFileRepositoryTest" --no-daemon
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/repository/LocalFileRepository.kt app/src/test/java/com/comicanything/reader/data/repository/LocalFileRepositoryTest.kt app/build.gradle.kts
git commit -m "feat: recursive local file scanning, drop demo-data fallback"
```

---

### Task 2: Permission-gated library loading in ReaderViewModel

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Create: `app/src/test/java/com/comicanything/reader/MainDispatcherRule.kt`
- Test: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalFileRepository(rootPath: String = ...)` and `suspend fun scanStorageDirectories(): List<ComicItem>` from Task 1.
- Produces: `ReaderUiState.hasStoragePermission: Boolean` (default `false`). `ReaderViewModel` primary constructor becomes `@JvmOverloads constructor(private val localRepo: LocalFileRepository = LocalFileRepository(), private val driveRepo: GoogleDriveRepository = GoogleDriveRepository())`. New method `fun setPermissionGranted(granted: Boolean)`.

- [ ] **Step 1: Write the MainDispatcherRule test helper**

`viewModelScope` dispatches onto `Dispatchers.Main` by default, which doesn't exist in a plain JVM unit test. Create `app/src/test/java/com/comicanything/reader/MainDispatcherRule.kt`:

```kotlin
package com.comicanything.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.repository.LocalFileRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `granting permission after being denied triggers a library load`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)
        val viewModel = ReaderViewModel(localRepo = repo)

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.libraryComics.size)
        assertTrue(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `revoking permission clears the library`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)
        val viewModel = ReaderViewModel(localRepo = repo)
        viewModel.setPermissionGranted(true)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.libraryComics.size)

        viewModel.setPermissionGranted(false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
        assertFalse(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `granting permission when already granted does not reload`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)
        val viewModel = ReaderViewModel(localRepo = repo)
        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        File(tempFolder.root, "new_comic.pdf").writeText("fake")
        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `ReaderViewModel` has no `localRepo` constructor parameter, no `setPermissionGranted`, and `ReaderUiState` has no `hasStoragePermission`, so this won't compile.

- [ ] **Step 4: Implement the changes**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, update `ReaderUiState` to add the new field (insert after `val isLoadingDrive: Boolean = false`):

```kotlin
    val isLoadingDrive: Boolean = false,
    val hasStoragePermission: Boolean = false
)
```

Replace the class declaration and `init` block:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    fun setPermissionGranted(granted: Boolean) {
        val wasGranted = _uiState.value.hasStoragePermission
        _uiState.value = _uiState.value.copy(hasStoragePermission = granted)
        if (granted && !wasGranted) {
            loadLocalLibrary()
        } else if (!granted && wasGranted) {
            _uiState.value = _uiState.value.copy(libraryComics = emptyList())
        }
    }

    fun loadLocalLibrary() {
        viewModelScope.launch {
            val items = localRepo.scanStorageDirectories()
            _uiState.value = _uiState.value.copy(libraryComics = items)
        }
    }
```

Remove the old `private val localRepo = LocalFileRepository()`, `private val driveRepo = GoogleDriveRepository()`, and `init { loadLocalLibrary() }` lines — they're replaced by the constructor parameters and `setPermissionGranted` above. Everything below `loadLocalLibrary()` (`fetchDriveFolder`, `openComic`, `setPage`, `toggleControls`, `setReadingMode`, `setFilterMode`, `toggleAutoCrop`) stays exactly as-is.

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/MainDispatcherRule.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: gate local library loading on storage permission state"
```

---

### Task 3: StoragePermissions utility and MainActivity wiring

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/util/StoragePermissions.kt`
- Modify: `app/src/main/java/com/comicanything/reader/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ReaderViewModel.setPermissionGranted(granted: Boolean)` from Task 2.
- Produces: `StoragePermissions.hasAccess(context: Context): Boolean`, `StoragePermissions.manageStorageSettingsIntent(context: Context): Intent`, `StoragePermissions.manageStorageSettingsFallbackIntent(): Intent` — consumed by Task 4's `onRequestPermission` callback wiring (already wired here in `MainActivity`, just noting the callback shape: `() -> Unit`, threaded into `HomeScreen(... onRequestPermission: () -> Unit)`).

No automated test for this task: `Environment.isExternalStorageManager()` and `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` are Android framework calls with no JVM equivalent, and the project has no Robolectric dependency. Per the design spec, this is verified manually on the emulator in Task 5. Steps below are implement-then-manually-verify rather than TDD.

- [ ] **Step 1: Remove unused media permissions from the manifest**

In `app/src/main/AndroidManifest.xml`, delete these two lines (they only cover MediaStore images/videos, not PDF/CBZ/EPUB/MOBI, and add nothing once `MANAGE_EXTERNAL_STORAGE` is requested):

```xml
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

- [ ] **Step 2: Create the StoragePermissions utility**

Create `app/src/main/java/com/comicanything/reader/util/StoragePermissions.kt`:

```kotlin
package com.comicanything.reader.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object StoragePermissions {

    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun manageStorageSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun manageStorageSettingsFallbackIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
```

- [ ] **Step 3: Wire MainActivity**

Replace the full contents of `app/src/main/java/com/comicanything/reader/MainActivity.kt`:

```kotlin
package com.comicanything.reader

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.ui.home.HomeScreen
import com.comicanything.reader.ui.reader.ReaderScreen
import com.comicanything.reader.ui.reader.ReaderViewModel
import com.comicanything.reader.ui.theme.ComicAnythingTheme
import com.comicanything.reader.util.StoragePermissions

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPermissionGranted(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComicAnythingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    if (state.activeComic != null) {
                        ReaderScreen(
                            comic = state.activeComic!!,
                            viewModel = viewModel,
                            onBack = { viewModel.openComic(null as ComicItem? ?: return@ReaderScreen) }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionGranted(StoragePermissions.hasAccess(this))
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = StoragePermissions.manageStorageSettingsIntent(this)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(StoragePermissions.manageStorageSettingsFallbackIntent())
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
```

`onResume()` runs right after `onCreate()` on first launch (standard Android lifecycle), so this both performs the initial permission check and detects the user returning from the Settings redirect — no separate `LaunchedEffect` needed.

This will not compile standalone yet — `HomeScreen` doesn't accept `onRequestPermission` until Task 4. That's expected; Task 4 completes the build.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/comicanything/reader/util/StoragePermissions.kt app/src/main/java/com/comicanything/reader/MainActivity.kt
git commit -m "feat: request MANAGE_EXTERNAL_STORAGE/READ_EXTERNAL_STORAGE at runtime"
```

---

### Task 4: Permission-gated Home UI and real Local Files tab

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `ReaderUiState.hasStoragePermission` and `ReaderUiState.libraryComics` (Task 2), `onRequestPermission: () -> Unit` threaded from `MainActivity` (Task 3).
- Produces: `PermissionRequiredCard(onRequestPermission: () -> Unit)` and `ComicGridCard(comic: ComicItem, onClick: () -> Unit)` composables, reused by both `LibraryContent` and `LocalFilesContent`.

No automated test for this task — it's a Compose UI layout change with no existing Compose UI test infrastructure wired up in this project (that's Epic 8 scope). Verified manually in Task 5.

- [ ] **Step 1: Add the shared PermissionRequiredCard composable**

In `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`, add these imports alongside the existing ones:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
```

Add this composable (place it after the `HomeScreen` function, before `LibraryContent`):

```kotlin
@Composable
fun PermissionRequiredCard(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Storage access needed",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ComicAnything needs access to your device storage to find PDF, CBZ, CBR, EPUB, and MOBI files.",
            color = Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Access")
        }
    }
}
```

- [ ] **Step 2: Extract ComicGridCard and thread onRequestPermission through HomeScreen/LibraryContent**

Change the `HomeScreen` function signature to accept the new callback:

```kotlin
@Composable
fun HomeScreen(
    viewModel: ReaderViewModel,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
) {
```

Update the `when (selectedTab)` block inside `HomeScreen`:

```kotlin
            when (selectedTab) {
                0 -> LibraryContent(state, onOpenComic, onRequestPermission)
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic)
                2 -> LocalFilesContent(state, onOpenComic, onRequestPermission)
            }
```

Update `LibraryContent`'s signature and add the permission gate at the top:

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

    val inProgress = state.libraryComics.filter { it.currentPage > 1 }
```

Inside `LibraryContent`'s "My Bookshelf" `LazyVerticalGrid`, replace the whole inline `Card { ... }` block (the `items(state.libraryComics) { comic -> Card(...) { ... } }` body) with:

```kotlin
            items(state.libraryComics) { comic ->
                ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
            }
```

Add the extracted composable (place it after `LibraryContent`, before `DriveContent`):

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
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
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
            }
            Text(
                text = comic.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
```

- [ ] **Step 3: Replace the LocalFilesContent stub with a real grid**

Replace the entire `LocalFilesContent` function (currently just a static `Box` with placeholder text) with:

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

- [ ] **Step 4: Build to confirm everything compiles together**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`. This is the point where Task 3's `MainActivity` (which references `onRequestPermission` on `HomeScreen`) and this task's updated `HomeScreen` signature finally align.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt
git commit -m "feat: gate Home tabs on storage permission, wire Local Files to real scan results"
```

---

### Task 5: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1–4 together as a user would.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "D:/Source Code/ComicAnything/app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running, start it first: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`.)

- [ ] **Step 2: Confirm the permission-required state**

Launch the app (`adb shell am start -n com.comicanything.reader/.MainActivity`). Expected: Library tab shows the "Storage access needed" card with a "Grant Access" button, not the old demo comics. Switch to the Local Files tab — same card should appear there too.

- [ ] **Step 3: Grant access and confirm real files appear**

Tap "Grant Access" — this should open the system "All files access" settings screen for the app. Toggle it on and return to the app (or drive it via adb):

```bash
"$ADB" shell appops set com.comicanything.reader MANAGE_EXTERNAL_STORAGE allow
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" push "D:/Source Code/ComicAnything/README.md" /sdcard/Download/test.pdf
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Expected: after granting, the Library and Local Files tabs no longer show the permission card. `test.pdf` (pushed above as a stand-in comic file) appears in the Local Files grid tagged `PDF`.

- [ ] **Step 4: Confirm revocation clears the library**

```bash
"$ADB" shell appops set com.comicanything.reader MANAGE_EXTERNAL_STORAGE deny
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Expected: the permission-required card reappears on both tabs; `test.pdf` is no longer shown.

- [ ] **Step 5: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), check off Epic 1's four tasks and change its status marker from 🟨 to ✅, noting it was verified end-to-end on `comicanything_test`.

- [ ] **Step 6: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark Epic 1 (runtime storage permissions) complete"
```
