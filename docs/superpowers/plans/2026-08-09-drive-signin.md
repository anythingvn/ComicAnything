# Google Drive Sign-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user optionally connect their own Google account so the app can later (sub-project 2) read their Drive files — this sub-project covers only the sign-in/connect/disconnect flow and its UI state, per [docs/superpowers/specs/2026-08-09-drive-signin-design.md](../specs/2026-08-09-drive-signin-design.md).

**Architecture:** Android's Credential Manager / Google Identity Services Authorization API (`Identity.getAuthorizationClient`) requests the `drive.readonly` scope. The actual API calls live at `MainActivity` (they need an `Activity`), matching the existing storage-permission-request pattern. `ReaderViewModel` exposes plain state-transition methods that take already-resolved results (a token string, an exception, a plain disconnect signal) — no Android Identity types leak into the ViewModel or its tests. A small DataStore-backed `DriveConnectionRepository` persists only a lightweight "was connected, as this email" hint for instant/offline-friendly UI — never the access token itself, which is short-lived and always re-fetched fresh via `authorize()`.

**Tech Stack:** Kotlin, `com.google.android.gms:play-services-auth`, `androidx.datastore:datastore-preferences` (already a dependency), kotlinx-coroutines.

## Global Constraints

- Min SDK 24, target/compile SDK 34 (`app/build.gradle.kts`)
- No mocking library — tests use real objects (temp-file-backed DataStore), not mocks
- The actual `Identity.getAuthorizationClient(...)` calls and the real Google consent UI are not unit-testable in this project (no Robolectric) — they're verified manually on a real device/emulator with Play Services. Everything else (the hint repository, the ViewModel's state transitions) must be covered by real-object JVM unit tests.
- Never persist the OAuth access token itself — only the `DriveConnectionHint` (connected flag + email) may be persisted
- Do not request offline access (`requestOfflineAccess`/`serverAuthCode`) — this app has no backend server to consume it
- A silent re-check of the connection state (e.g. on `onResume`) must never itself pop the interactive Google consent UI — only an explicit user tap on "Connect Google Drive" may do that
- Existing package root: `com.comicanything.reader`

---

### Task 1: DriveConnectionHint + DriveConnectionRepository

**Files:**
- Modify: `app/build.gradle.kts` (add the `play-services-auth` dependency)
- Create: `app/src/main/java/com/comicanything/reader/data/repository/DriveConnectionRepository.kt`
- Test: `app/src/test/java/com/comicanything/reader/data/repository/DriveConnectionRepositoryTest.kt`

**Interfaces:**
- Produces: `data class DriveConnectionHint(val isConnected: Boolean, val accountEmail: String?)`, `class DriveConnectionRepository(dataStore: DataStore<Preferences>, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` with a secondary `constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)`, plus `suspend fun get(): DriveConnectionHint` and `suspend fun save(hint: DriveConnectionHint)` — consumed by Task 2's `ReaderViewModel`.

This mirrors Epic 3's `ReadingProgressRepository` shape exactly (same dual-constructor pattern, same DataStore-preferences approach), but is a separate file/class with its own preferences name — this data isn't reading progress and shouldn't share that repository's tests or corruption-handling story by accident.

- [ ] **Step 1: Add the play-services-auth dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, add this line next to the existing `implementation("androidx.datastore:datastore-preferences:1.1.1")`:

```kotlin
implementation("com.google.android.gms:play-services-auth:21.6.0")
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/repository/DriveConnectionRepositoryTest.kt`:

```kotlin
package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveConnectionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
    }

    @Test
    fun `get returns a disconnected hint when nothing has been saved`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val hint = repo.get()

        assertFalse(hint.isConnected)
        assertNull(hint.accountEmail)
    }

    @Test
    fun `save then get round-trips a connected hint`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val saved = DriveConnectionHint(isConnected = true, accountEmail = "reader@example.com")

        repo.save(saved)
        val loaded = repo.get()

        assertEquals(saved, loaded)
    }

    @Test
    fun `save then get round-trips a disconnected hint with no email`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        repo.save(DriveConnectionHint(isConnected = true, accountEmail = "reader@example.com"))
        repo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        val loaded = repo.get()

        assertFalse(loaded.isConnected)
        assertNull(loaded.accountEmail)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.DriveConnectionRepositoryTest" --no-daemon
```

Expected: FAIL — `DriveConnectionRepository`/`DriveConnectionHint` don't exist yet, so this won't compile.

- [ ] **Step 4: Implement DriveConnectionRepository**

Create `app/src/main/java/com/comicanything/reader/data/repository/DriveConnectionRepository.kt`:

```kotlin
package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

data class DriveConnectionHint(
    val isConnected: Boolean,
    val accountEmail: String?
)

private val Context.driveConnectionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_connection",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class DriveConnectionRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.driveConnectionDataStore, ioDispatcher)

    suspend fun get(): DriveConnectionHint = withContext(ioDispatcher) {
        try {
            val prefs = dataStore.data.first()
            DriveConnectionHint(
                isConnected = prefs[IS_CONNECTED_KEY] ?: false,
                accountEmail = prefs[ACCOUNT_EMAIL_KEY]
            )
        } catch (e: IOException) {
            DriveConnectionHint(isConnected = false, accountEmail = null)
        }
    }

    suspend fun save(hint: DriveConnectionHint) = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                prefs[IS_CONNECTED_KEY] = hint.isConnected
                if (hint.accountEmail != null) {
                    prefs[ACCOUNT_EMAIL_KEY] = hint.accountEmail
                } else {
                    prefs.remove(ACCOUNT_EMAIL_KEY)
                }
            }
        } catch (e: IOException) {
            // Matches ReadingProgressRepository's posture: a failed write just means this
            // hint didn't persist, not a crash. The UI already reflects the live in-memory
            // state regardless of whether the disk write succeeds.
        }
    }

    companion object {
        private val IS_CONNECTED_KEY = booleanPreferencesKey("is_connected")
        private val ACCOUNT_EMAIL_KEY = stringPreferencesKey("account_email")
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.DriveConnectionRepositoryTest" --no-daemon
```

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/comicanything/reader/data/repository/DriveConnectionRepository.kt app/src/test/java/com/comicanything/reader/data/repository/DriveConnectionRepositoryTest.kt
git commit -m "feat: add DataStore-backed DriveConnectionRepository"
```

---

### Task 2: ReaderViewModel state transitions for Drive sign-in

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `DriveConnectionRepository(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` and its `(DataStore<Preferences>, ioDispatcher)` constructor (Task 1), `DriveConnectionHint` (Task 1).
- Produces: `ReaderUiState` gains `isDriveConnected: Boolean = false` and `driveAccountEmail: String? = null`. `ReaderViewModel` gains a `connectionRepo: DriveConnectionRepository` constructor parameter (defaulting to `DriveConnectionRepository(application)`), and three new public methods: `fun loadDriveConnectionState()`, `fun onDriveAuthorized(accountEmail: String?)`, `fun onDriveAuthorizationFailed()`, `fun disconnectDrive()`. None of these take any Android Identity/Play Services types as parameters — Task 3's `MainActivity` resolves the actual authorization result down to a plain email string (or nothing) before calling into the ViewModel.

This task is pure state-plumbing — it does not touch `Identity.getAuthorizationClient` or any Play Services API at all, so everything here is unit-testable with real objects, same as Task 1.

- [ ] **Step 1: Write the failing tests**

Add these test cases to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, just before the file's final closing `}`. They need a temp-file-backed `DriveConnectionRepository`, same pattern as this file's existing `progressRepo` setup:

```kotlin
    @Test
    fun `loadDriveConnectionState reflects a previously-saved connected hint`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        connectionRepo.save(DriveConnectionHint(isConnected = true, accountEmail = "reader@example.com"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.loadDriveConnectionState()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDriveConnected)
        assertEquals("reader@example.com", viewModel.uiState.value.driveAccountEmail)
    }

    @Test
    fun `loadDriveConnectionState defaults to disconnected when nothing was saved`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.loadDriveConnectionState()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
    }

    @Test
    fun `onDriveAuthorized marks connected and persists the hint`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.onDriveAuthorized("reader@example.com")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDriveConnected)
        assertEquals("reader@example.com", viewModel.uiState.value.driveAccountEmail)
        val persisted = connectionRepo.get()
        assertTrue(persisted.isConnected)
        assertEquals("reader@example.com", persisted.accountEmail)
    }

    @Test
    fun `onDriveAuthorized with a null email still marks connected`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.onDriveAuthorized(null)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
    }

    @Test
    fun `onDriveAuthorizationFailed leaves the state disconnected`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.onDriveAuthorizationFailed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
    }

    @Test
    fun `disconnectDrive clears state and persists the disconnected hint`() = runTest {
        val connectionDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
        val connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )
        viewModel.onDriveAuthorized("reader@example.com")
        advanceUntilIdle()

        viewModel.disconnectDrive()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
        val persisted = connectionRepo.get()
        assertFalse(persisted.isConnected)
        assertNull(persisted.accountEmail)
    }
```

Add these imports to the top of the test file, alongside the existing ones: `com.comicanything.reader.data.repository.DriveConnectionHint` and `com.comicanything.reader.data.repository.DriveConnectionRepository`.

**Also required, not optional (confirmed by running the tests — see below):** the new `connectionRepo` constructor parameter's default, `DriveConnectionRepository(application)`, has the exact same unmocked-`Context.getApplicationContext()` hazard that `progressRepo`'s default already has (see the file's existing `progressRepo` comment). Every one of the file's pre-existing `ReaderViewModel(...)` constructions will still *compile* without passing `connectionRepo` (the parameter has a default), but will *fail at runtime* with a `RuntimeException` unless it's passed explicitly — this is not a hypothetical, it reproduces on every one of those tests. Fix it the same way `progressRepo` is already handled: add a `private lateinit var connectionRepo: DriveConnectionRepository` field, initialize it inside the existing `@Before fun setUpProgressRepo()` block (against its own temp-file-backed `PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined), ...)`, same as `progressRepo`'s), and add `connectionRepo = connectionRepo` to every pre-existing `ReaderViewModel(...)` call site in the file. The 6 new tests below already construct and pass their own local `connectionRepo` per-test, so they don't need this — only the pre-existing tests do.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — none of `loadDriveConnectionState`/`onDriveAuthorized`/`onDriveAuthorizationFailed`/`disconnectDrive`/`connectionRepo` exist yet, so this won't compile.

- [ ] **Step 3: Add the ReaderUiState fields and ReaderViewModel wiring**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`, add this import alongside the existing `ReadingProgressRepository` import:

```kotlin
import com.comicanything.reader.data.repository.DriveConnectionHint
import com.comicanything.reader.data.repository.DriveConnectionRepository
```

In `ReaderUiState` (currently ending with `val pageSourceGeneration: Int = 0`), add two fields:

```kotlin
data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveComics: List<ComicItem> = emptyList(),
    val activeComic: ComicItem? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 48,
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true,
    val isControlsVisible: Boolean = true,
    val isLoadingDrive: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isScanningLocal: Boolean = false,
    val currentPageBitmap: Bitmap? = null,
    val pageLoadError: String? = null,
    val isPageLoading: Boolean = false,
    val pageSourceGeneration: Int = 0,
    val isDriveConnected: Boolean = false,
    val driveAccountEmail: String? = null
)
```

Change the constructor from:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application)
) : AndroidViewModel(application) {
```

to:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application)
) : AndroidViewModel(application) {
```

Add these four methods anywhere among the other public functions (e.g. right after `fetchDriveFolder`):

```kotlin
    fun loadDriveConnectionState() {
        viewModelScope.launch {
            val hint = connectionRepo.get()
            _uiState.value = _uiState.value.copy(
                isDriveConnected = hint.isConnected,
                driveAccountEmail = hint.accountEmail
            )
        }
    }

    fun onDriveAuthorized(accountEmail: String?) {
        _uiState.value = _uiState.value.copy(isDriveConnected = true, driveAccountEmail = accountEmail)
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = true, accountEmail = accountEmail))
        }
    }

    fun onDriveAuthorizationFailed() {
        _uiState.value = _uiState.value.copy(isDriveConnected = false, driveAccountEmail = null)
    }

    fun disconnectDrive() {
        _uiState.value = _uiState.value.copy(isDriveConnected = false, driveAccountEmail = null)
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        }
    }
```

Note `onDriveAuthorizationFailed()` deliberately does **not** write to `connectionRepo` — a failed silent re-check (e.g. no network, or the grant was revoked outside the app) shouldn't overwrite a previously-saved "was connected" hint with a hard "disconnected" on every transient failure; it only affects the current in-memory session's displayed state. Only `onDriveAuthorized` and `disconnectDrive` (both driven by a definitive outcome) persist.

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS — all tests in this file, including the 6 new ones.

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: add ReaderViewModel state transitions for Drive connection"
```

---

### Task 3: MainActivity authorization flow + DriveContent UI

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/MainActivity.kt`
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `viewModel.onDriveAuthorized(accountEmail: String?)`, `viewModel.onDriveAuthorizationFailed()`, `viewModel.disconnectDrive()`, `viewModel.loadDriveConnectionState()` (Task 2). `uiState.isDriveConnected`, `uiState.driveAccountEmail` (Task 2).
- Produces: `HomeScreen`'s `DriveContent` gains an `onConnectDrive: () -> Unit` and `onDisconnectDrive: () -> Unit` parameter, threaded from `MainActivity` the same way `onRequestPermission` already is.

This task is the one genuinely un-unit-testable piece (real `Identity.getAuthorizationClient` calls, real Activity result, real Google consent UI) — it's covered by Task 4's manual on-device verification instead.

**Two distinct authorization call sites, per the Global Constraints' "never auto-pop the consent UI" rule:**

- [ ] **Step 1: Add the silent re-check on app resume**

In `app/src/main/java/com/comicanything/reader/MainActivity.kt`, add these imports:

```kotlin
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
```

(`Scope` lives in `com.google.android.gms.common.api`, not `com.google.android.gms.auth.api.identity` — confirmed by extracting `play-services-auth:21.6.0`'s actual AAR contents: that package has `AuthorizationRequest`/`AuthorizationResult`/`ClearTokenRequest`/`Identity` but no `Scope` class; `Scope` is defined in the transitive `play-services-basement` dependency instead, under the `common.api` package.)

Add a private constant for the scope, above the class:

```kotlin
private const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
```

The current `onResume()` reads:

```kotlin
    override fun onResume() {
        super.onResume()
        val granted = StoragePermissions.hasAccess(this)
        viewModel.setPermissionGranted(granted)
        if (granted) {
            viewModel.refreshLibrary()
        }
    }
```

Replace it with (adding the Drive connection load + silent re-check, without disturbing the existing storage-permission logic):

```kotlin
    override fun onResume() {
        super.onResume()
        val granted = StoragePermissions.hasAccess(this)
        viewModel.setPermissionGranted(granted)
        if (granted) {
            viewModel.refreshLibrary()
        }
        viewModel.loadDriveConnectionState()
        checkDriveAuthorizationSilently()
    }

    private fun checkDriveAuthorizationSilently() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (!result.hasResolution()) {
                    // Grant is still valid -- reconcile the optimistic hint with the live check,
                    // and remember the fresh token so disconnectDrive() (below) has something
                    // to clear -- this call, not connectDrive(), is what actually runs on every
                    // app resume, so it's the reliable place to keep lastAccessToken current.
                    //
                    // Passing null for the email: per the design spec, resolving the signed-in
                    // account's actual email address wasn't confirmed against current docs
                    // (AuthorizationResult didn't expose one in the fetched reference) and is an
                    // open question for a future task, not this one. DriveConnectionHint's
                    // accountEmail is nullable specifically so the UI degrades to a plain
                    // "Connected" state without a name -- this is that fallback in use, not a
                    // bug. Do not "fix" this by reading back whatever driveAccountEmail already
                    // happens to hold; that would just be echoing stale/absent state, not
                    // resolving a real one.
                    lastAccessToken = result.accessToken
                    viewModel.onDriveAuthorized(accountEmail = null)
                } else {
                    // Grant needs interactive re-confirmation (revoked, expired scope, etc).
                    // Per the "never auto-pop consent UI" rule, this silent check does NOT
                    // launch the resolution intent -- it just downgrades the displayed state.
                    // The user can tap "Connect" again to go through the interactive flow.
                    viewModel.onDriveAuthorizationFailed()
                }
            }
            .addOnFailureListener {
                // No network, or no prior grant at all -- leave the optimistic hint as-is
                // rather than forcing a "disconnected" flash on every transient failure.
                // (Deliberately not calling onDriveAuthorizationFailed() here, unlike the
                // hasResolution()==true branch above, since that branch is a definitive
                // "needs re-confirmation" signal and this one is not.)
            }
    }
```

- [ ] **Step 2: Add the interactive connect flow, and a locally-scoped last-token field for disconnect**

Add these two fields near the existing `requestPermissionLauncher` field — `lastAccessToken` deliberately lives here as a plain in-memory `Activity` field, not in `ReaderViewModel`/`ReaderUiState` and not persisted: the Global Constraints forbid persisting the access token, and keeping it out of the ViewModel/UI-state layer entirely means it can never leak into a `_uiState` snapshot, a test assertion, or anywhere else that token material has no business being:

```kotlin
    private var lastAccessToken: String? = null

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        try {
            val result = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(activityResult.data)
            lastAccessToken = result.accessToken
            // accountEmail = null: see the comment in checkDriveAuthorizationSilently() above --
            // resolving the real email is an open question deferred to a future task.
            viewModel.onDriveAuthorized(accountEmail = null)
            Unit
        } catch (e: ApiException) {
            Log.w("MainActivity", "Drive authorization intent failed", e)
            viewModel.onDriveAuthorizationFailed()
        }
    }
```

(This needs `import androidx.activity.result.IntentSenderRequest` alongside the other `androidx.activity.result.contract.ActivityResultContracts` import already present.)

Add the connect/disconnect trigger functions near `requestStoragePermission()`:

```kotlin
    private fun connectDrive() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent!!
                    driveAuthLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    lastAccessToken = result.accessToken
                    // accountEmail = null: see the comment in checkDriveAuthorizationSilently().
                    viewModel.onDriveAuthorized(accountEmail = null)
                }
            }
            .addOnFailureListener { e ->
                Log.w("MainActivity", "Drive authorization request failed", e)
                viewModel.onDriveAuthorizationFailed()
            }
    }

    private fun disconnectDrive() {
        // AuthorizationClient.revokeAccess(RevokeAccessRequest) exists and would fully revoke
        // the grant on Google's side, but its builder requires .setAccount(account) (confirmed:
        // there is no access-token-based alternative on that request type) -- and this flow,
        // which only performs Authorization (not a separate Credential Manager identity
        // sign-in), has no confirmed way to obtain that Account object without adding a whole
        // extra sign-in step. That's a real scope question for a future task, not something to
        // guess at here.
        //
        // What IS confirmed and usable here is
        // AuthorizationClient.clearToken(ClearTokenRequest.builder().setToken(token).build()) --
        // it takes the access token string directly (which lastAccessToken holds), and clears
        // Play Services' local cache of that grant. This makes disconnectDrive() correctly
        // "forget" the connection for THIS app and prevents the silent onResume re-check from
        // finding a still-valid cached token and reconnecting the user against their wishes --
        // but it does not remove the app from the user's Google Account "Third-party apps with
        // access" list. Note this limitation in the Task 4 verification report; it's an
        // accurate description of what v1 does, not a bug to silently paper over.
        val token = lastAccessToken
        if (token != null) {
            Identity.getAuthorizationClient(this)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .addOnSuccessListener { lastAccessToken = null }
                .addOnFailureListener { e -> Log.w("MainActivity", "Drive clearToken failed", e) }
        }
        viewModel.disconnectDrive()
    }
```

(`disconnectDrive()` here is a thin wrapper for symmetry with `connectDrive()`/`requestStoragePermission()`'s naming, matching how `HomeScreen`'s callbacks are threaded — `viewModel.disconnectDrive()` doesn't itself need an `Activity`, but keeping the call site consistent with the other Activity-triggered actions makes the `onCreate` wiring below read uniformly.)

- [ ] **Step 3: Wire the callbacks through HomeScreen into onCreate**

In `MainActivity.kt`'s `onCreate`, the current `HomeScreen(...)` call is:

```kotlin
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() }
                        )
                    }
```

Change it to:

```kotlin
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() },
                            onConnectDrive = { connectDrive() },
                            onDisconnectDrive = { disconnectDrive() }
                        )
                    }
```

- [ ] **Step 4: Thread the new callbacks through HomeScreen to DriveContent**

In `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`, find the top-level `HomeScreen` composable's signature (it currently takes `viewModel`, `onOpenComic`, `onRequestPermission`) and add the two new parameters:

```kotlin
fun HomeScreen(
    viewModel: ReaderViewModel,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
```

At `HomeScreen.kt:102`, the current Drive tab dispatch is:

```kotlin
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic)
```

Change it to:

```kotlin
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
```

- [ ] **Step 5: Add the connected/disconnected branch to DriveContent**

`DriveContent` currently reads (`HomeScreen.kt:308-367`):

```kotlin
@Composable
fun DriveContent(
    state: ReaderUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onFetch: () -> Unit,
    onOpenComic: (ComicItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Paste Google Drive Folder URL / ID") },
            trailingIcon = {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))
```

Add the two new parameters and a connected/disconnected branch above the existing text field (the existing folder-URL field and everything below it stays exactly as-is — it's out of this sub-project's scope per the design spec, and sub-project 2 will revisit it):

```kotlin
@Composable
fun DriveContent(
    state: ReaderUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onFetch: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (state.isDriveConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connected" + (state.driveAccountEmail?.let { " as $it" } ?: ""),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDisconnectDrive) {
                    Text("Disconnect")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Optional: connect your Google Drive to read comics stored there.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConnectDrive) {
                    Text("Connect")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Paste Google Drive Folder URL / ID") },
            trailingIcon = {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))
```

No new imports are needed for this step — `TextButton` (via the existing `androidx.compose.material3.*` wildcard import at `HomeScreen.kt:19`), `Row` (via `androidx.compose.foundation.layout.*` at line 5), `Alignment` (line 22), `Color` (line 25), and `sp` (line 30) are all already imported in this file.

- [ ] **Step 6: Build to confirm everything compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`. This is the only verification possible for this task without a real device — the actual authorization flow needs Task 4's manual pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/MainActivity.kt app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt
git commit -m "feat: wire Google Drive authorization flow into MainActivity and DriveContent"
```

---

### Task 4: End-to-end manual verification on the emulator

**Files:** none (verification only)

**Interfaces:** none — this task exercises Tasks 1-3 together as a user would.

**Prerequisite check:** this task requires the Google Cloud Console setup described in the design spec's "Prerequisite" section (Drive API enabled, OAuth consent screen configured with a test user, an Android-type OAuth client ID registered with this app's package name and the signing key's SHA-1 fingerprint) to already be done. If it isn't done yet, **do not attempt to fake or skip this step** — stop and report back that manual verification is blocked pending that setup, rather than marking this task complete without having actually exercised the real sign-in flow. Everything in Tasks 1-3 that CAN be verified without it (unit tests, `assembleDebug`) should still be confirmed and reported.

- [ ] **Step 1: Install the freshly built APK**

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test`. The emulator image must include Google Play Services for `Identity.getAuthorizationClient` to work — a bare AOSP image without Play Store will fail here; use a "Google APIs" or "Google Play" system image.)

- [ ] **Step 2: Verify the disconnected state on first launch**

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Navigate to the Google Drive tab. Expected: "Optional: connect your Google Drive..." message with a "Connect" button, no crash.

- [ ] **Step 3: Verify the connect flow**

Tap "Connect". Expected: Google's account-picker/consent UI appears (this is the real Google-hosted UI, not app UI). Complete the flow with a Google account that's listed as a test user in the OAuth consent screen configuration. Expected: returns to the app, the Drive tab shows a plain "Connected" (no email address — Task 3's code deliberately passes a `null` email throughout, since resolving the real account email wasn't confirmed against current docs and was deferred rather than guessed at; this is expected behavior for this sub-project, not a bug to investigate).

- [ ] **Step 4: Verify the connection survives an app kill**

```bash
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Navigate to the Drive tab again. Expected: still shows "Connected" (the silent re-check on `onResume` should reconcile this without popping any UI — confirm no consent screen appears automatically).

- [ ] **Step 5: Verify disconnect**

Tap "Disconnect". Expected: returns to the "Optional: connect..." disconnected state. Force-stop and relaunch again, navigate to the Drive tab: expected still disconnected (not reconnected).

- [ ] **Step 6: Regression check**

Confirm Epic 1/2/3 behavior is unaffected: local file scanning, permission gating, reading modes, page-progress persistence, and favorites all still work. No crashes in `adb logcat` throughout this whole verification pass.

- [ ] **Step 7: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 4`, this sub-project only covers the first of the four existing bullets (and changes its meaning from "API key" to "OAuth sign-in") — update the wording to match what was actually built, check off the sign-in portion, and leave the remaining three bullets (browsing, streaming/caching, error states) unchecked for sub-project 2. Add a verification note in the same style as Epic 2/3's notes, summarizing what was tested and confirmed on `comicanything_test`.

- [ ] **Step 8: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark Google Drive sign-in (Epic 4, sub-project 1) complete"
```
