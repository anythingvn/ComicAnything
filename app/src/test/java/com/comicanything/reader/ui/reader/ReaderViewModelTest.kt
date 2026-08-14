package com.comicanything.reader.ui.reader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.epub.EpubBook
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.model.ReadingMode
import com.comicanything.reader.data.repository.DriveConnectionHint
import com.comicanything.reader.data.repository.DriveConnectionRepository
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.ReadingProgress
import com.comicanything.reader.data.repository.ReadingProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val fakeApplication = Application()

    // ReaderViewModel's default `progressRepo` argument constructs a ReadingProgressRepository
    // against the real Context.readingProgressDataStore delegate, which calls
    // Context.getApplicationContext() -- an Android stub-jar method that throws "not mocked" in
    // plain JVM unit tests (no Robolectric). So every ReaderViewModel(...) below passes this
    // temp-file-backed instance explicitly instead of relying on the default, matching the
    // pattern already used in ReadingProgressRepositoryTest.
    private lateinit var progressRepo: ReadingProgressRepository

    // Same hazard as progressRepo above: ReaderViewModel's default `connectionRepo` argument
    // constructs a DriveConnectionRepository against the real Context.driveConnectionDataStore
    // delegate, which also calls Context.getApplicationContext() and throws "not mocked" in
    // plain JVM unit tests. Every ReaderViewModel(...) below passes this temp-file-backed
    // instance explicitly instead of relying on the default.
    private lateinit var connectionRepo: DriveConnectionRepository

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUpProgressRepo() {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "test-progress-${System.nanoTime()}.preferences_pb") }
        )
        progressRepo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val connectionDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "test-connection-${System.nanoTime()}.preferences_pb") }
        )
        connectionRepo = DriveConnectionRepository(connectionDataStore, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun `granting permission after being denied triggers a library load`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.libraryComics.size)
        assertTrue(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `revoking permission clears the library`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)
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
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        File(tempFolder.root, "new_comic.pdf").writeText("fake")
        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
    }

    @Test
    fun `refreshLibrary re-scans and picks up newly added files when permission is granted`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())

        File(tempFolder.root, "new_comic.pdf").writeText("fake")
        viewModel.refreshLibrary()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.libraryComics.size)
    }

    @Test
    fun `refreshLibrary is a no-op when permission has never been granted`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        viewModel.refreshLibrary()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
        assertFalse(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `closeComic clears the active comic`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(
            id = "1",
            title = "Test Comic",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF
        )
        viewModel.openComic(comic)
        advanceUntilIdle()
        assertEquals(comic, viewModel.uiState.value.activeComic)

        viewModel.closeComic()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeComic)
    }

    @Test
    fun `opening a Google Drive comic sets an error even for a supported format`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(
            id = "2",
            title = "Drive Book",
            pathOrUrl = "https://drive.google.com/fake.pdf",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("This format isn't supported yet", viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `loadPageBitmap returns Failed when no comic is open`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)

        val result = viewModel.loadPageBitmap(1)

        assertEquals(PageLoadState.Failed, result)
    }

    @Test
    fun `pageSourceGeneration increments each time a comic successfully opens`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbz")
        java.util.zip.ZipOutputStream(comicFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        val comic = ComicItem(
            id = "1",
            title = "Test",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )
        val initialGeneration = viewModel.uiState.value.pageSourceGeneration

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageSourceGeneration > initialGeneration)
    }

    @Test
    fun `loadLocalLibrary merges persisted progress into scanned comics`() = runTest {
        val comicFile = File(tempFolder.newFolder("Comics"), "batman.cbz")
        comicFile.writeText("fake")
        val comicId = comicFile.absolutePath.hashCode().toString()

        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
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
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = localRepo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        viewModel.setPermissionGranted(true)
        advanceUntilIdle()

        val comic = viewModel.uiState.value.libraryComics.single()
        assertEquals(1, comic.currentPage)
        assertEquals(1, comic.totalPages)
        assertFalse(comic.isFavorite)
    }

    @Test
    fun `opening a comic persists its real page count immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbz")
        java.util.zip.ZipOutputStream(comicFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("page2.jpg"))
            zos.write(byteArrayOf(4, 5, 6))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("page3.jpg"))
            zos.write(byteArrayOf(7, 8, 9))
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
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        val saved = progressRepo.getAll()["test-comic"]
        assertEquals(3, saved?.totalPages)
    }

    @Test
    fun `setCurrentPageIndicator debounces persistence, only writing after 1500ms of no further changes`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
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
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
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

        // One tick short of the debounce window since the last change: still nothing written.
        // (500 already elapsed above, so 999 more brings the total since setCurrentPageIndicator(4) to 1_499ms.)
        // Note: deliberately no advanceUntilIdle() here -- that would run tasks regardless of
        // their scheduled time and defeat the point of stopping just short of the boundary.
        advanceTimeBy(999)
        assertNull(progressRepo.getAll()["debounce-comic"]?.let { if (it.currentPage == 4) it else null })

        // The final millisecond crosses the exact 1_500ms boundary -- now it must have written.
        // Use runCurrent() (not advanceUntilIdle()) here: advanceUntilIdle() would jump the
        // virtual clock forward to whatever time the next task is scheduled for, no matter how
        // far out, so it would still catch a job scheduled for e.g. 2_000ms and mask an upward
        // drift in the debounce delay. runCurrent() only runs tasks already due at the current
        // virtual time, so it proves the job was scheduled to fire by exactly 1_500ms -- not
        // merely that it eventually fires at some later time.
        advanceTimeBy(1)
        runCurrent()

        assertEquals(4, progressRepo.getAll()["debounce-comic"]?.currentPage)
    }

    @Test
    fun `closeComic flushes pending debounced progress immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
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
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setCurrentPageIndicator(9)
        // Deliberately do NOT advance past the 1.5s debounce window.

        viewModel.closeComic()
        advanceUntilIdle()

        assertEquals(9, progressRepo.getAll()["close-comic"]?.currentPage)
    }

    @Test
    fun `onCleared flushes pending debounced progress immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val comic = ComicItem(
            id = "cleared-comic",
            title = "Test",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF,
            totalPages = 10
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setCurrentPageIndicator(9)
        // Deliberately do NOT advance past the 1.5s debounce window before tearing down --
        // this reproduces the real system-back-press path, where the process is torn down via
        // Activity finish -> ViewModel.clear() -> onCleared(), with no closeComic() call.
        //
        // ViewModel.clear() is package-private (androidx.lifecycle), so it can't be invoked
        // directly from this test; clearForTest() only exposes onCleared() itself. To still
        // exercise the real hazard -- viewModelScope's backing Job already being cancelled by
        // the time onCleared() runs -- cancel it explicitly first, mirroring what
        // ViewModel.clear() does internally before it calls onCleared().
        viewModel.viewModelScope.cancel()
        viewModel.clearForTest()
        advanceUntilIdle()

        assertEquals(9, progressRepo.getAll()["cleared-comic"]?.currentPage)
    }

    @Test
    fun `toggleFavorite flips isFavorite and persists immediately`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
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
            progressRepo = progressRepo,
            connectionRepo = connectionRepo
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
    fun `onDriveAuthorizationFailed leaves the state disconnected without clobbering a persisted connected hint`() = runTest {
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

        viewModel.onDriveAuthorizationFailed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
        val persisted = connectionRepo.get()
        assertTrue(persisted.isConnected)
        assertEquals("reader@example.com", persisted.accountEmail)
    }

    @Test
    fun `onDriveSilentCheckSucceeded does not reconnect after an explicit disconnect`() = runTest {
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

        // Simulates checkDriveAuthorizationSilently() succeeding on the next app resume, even though
        // Play Services' underlying grant is technically still valid (clearToken() never revoked it
        // server-side) -- this must NOT silently re-establish the connected state the user just left.
        viewModel.onDriveSilentCheckSucceeded()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDriveConnected)
        assertNull(viewModel.uiState.value.driveAccountEmail)
        val persisted = connectionRepo.get()
        assertFalse(persisted.isConnected)
    }

    @Test
    fun `onDriveSilentCheckSucceeded confirms an already-connected state`() = runTest {
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

        viewModel.onDriveSilentCheckSucceeded()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDriveConnected)
        assertEquals("reader@example.com", viewModel.uiState.value.driveAccountEmail)
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

    @Test
    fun `loadCoverThumbnail returns Unavailable when the decoder finds nothing`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { null }
        )
        val comic = ComicItem(
            id = "epub-comic",
            title = "Unsupported",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        val result = viewModel.loadCoverThumbnail(comic)

        assertEquals(CoverLoadState.Unavailable, result)
    }

    @Test
    fun `loadCoverThumbnail only invokes the decoder once per comic, caching the result`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var decodeCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { decodeCallCount++; null }
        )
        val comic = ComicItem(
            id = "cached-comic",
            title = "Test",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.loadCoverThumbnail(comic)
        viewModel.loadCoverThumbnail(comic)
        viewModel.loadCoverThumbnail(comic)

        assertEquals(1, decodeCallCount)
    }

    @Test
    fun `loadCoverThumbnail decodes independently per distinct comic id`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var decodeCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            thumbnailDecoder = { decodeCallCount++; null }
        )
        val comicA = ComicItem(id = "a", title = "A", pathOrUrl = "/fake/a.cbz", source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        val comicB = ComicItem(id = "b", title = "B", pathOrUrl = "/fake/b.cbz", source = ComicSource.LOCAL, format = ComicFormat.CBZ)

        viewModel.loadCoverThumbnail(comicA)
        viewModel.loadCoverThumbnail(comicB)
        viewModel.loadCoverThumbnail(comicA)

        assertEquals(2, decodeCallCount)
    }

    @Test
    fun `opening an EPUB comic uses the injected extractor and populates epubBook`() = runTest {
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "epub-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals(fakeBook, viewModel.uiState.value.epubBook)
        assertNull(viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `opening an EPUB comic sets an error when extraction fails`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> null },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "bad-epub",
            title = "Corrupt EPUB",
            pathOrUrl = "/fake/corrupt.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("Couldn't open this EPUB file", viewModel.uiState.value.pageLoadError)
        assertNull(viewModel.uiState.value.epubBook)
    }

    @Test
    fun `closeComic deletes the extracted EPUB directory`() = runTest {
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "epub-comic-2",
            title = "Test EPUB",
            pathOrUrl = "/fake/path2.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        viewModel.openComic(comic)
        advanceUntilIdle()
        assertTrue(extractedDir.exists())

        viewModel.closeComic()
        advanceUntilIdle()

        assertTrue(!extractedDir.exists())
        assertNull(viewModel.uiState.value.epubBook)
    }

    @Test
    fun `setEpubScrollProgress updates progressPercentage and persists after the debounce window`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val comic = ComicItem(
            id = "scroll-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/scroll.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setEpubScrollProgress(0.42f)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0.42f, progressRepo.getAll()["scroll-comic"]?.progressPercentage)
    }

    @Test
    fun `setEpubScrollProgress clamps values outside 0 to 1`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "clamp-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/clamp.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setEpubScrollProgress(1.5f)

        assertEquals(1f, viewModel.uiState.value.activeComic?.progressPercentage)
    }

    @Test
    fun `opening an EPUB comic closes a previously active CBZ page cache`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbz")
        java.util.zip.ZipOutputStream(comicFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val cbzComic = ComicItem(
            id = "cbz-then-epub",
            title = "Test CBZ",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )
        val epubComic = ComicItem(
            id = "epub-after-cbz",
            title = "Test EPUB",
            pathOrUrl = "/fake/after-cbz.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(cbzComic)
        advanceUntilIdle()
        // Sanity check: the CBZ's PageBitmapCache is backed by a real, still-open java.util.zip.ZipFile
        // on comicFile. On this JDK/Windows, an open ZipFile holds an OS-level handle that blocks
        // deletion of the underlying file -- confirmed empirically before writing this test. If this
        // assertion itself fails, the CBZ never really opened and the rest of the test proves nothing.
        assertFalse(comicFile.delete())
        // Recreate the file the failed delete attempt above didn't actually remove (delete() returning
        // false leaves it untouched, but be explicit rather than relying on that).
        assertTrue(comicFile.exists())

        viewModel.openComic(epubComic)
        advanceUntilIdle()

        // The EPUB opened successfully despite not calling closeComic() first...
        assertEquals(fakeBook, viewModel.uiState.value.epubBook)
        // ...and, critically, the previous CBZ's PageBitmapCache was actually closed (not merely
        // dropped) as part of that switch: ZipFile.close() released the OS-level handle, so the file
        // can now be deleted. Before the cross-format fix, opening an EPUB never looked at pageCache
        // at all, so this delete would still have failed here.
        assertTrue(comicFile.delete())
    }

    @Test
    fun `opening a CBZ comic deletes a previously active EPUB's extracted directory`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.html").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val comicFile = File(tempFolder.newFolder("Comics"), "after-epub.cbz")
        java.util.zip.ZipOutputStream(comicFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook },
            epubCacheRoot = { tempFolder.newFolder("epub-cache-${System.nanoTime()}") }
        )
        val epubComic = ComicItem(
            id = "epub-then-cbz",
            title = "Test EPUB",
            pathOrUrl = "/fake/before-cbz.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        val cbzComic = ComicItem(
            id = "cbz-after-epub",
            title = "Test CBZ",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )
        viewModel.openComic(epubComic)
        advanceUntilIdle()
        assertTrue(extractedDir.exists())

        viewModel.openComic(cbzComic)
        advanceUntilIdle()

        // The EPUB's extracted directory must be cleaned up as part of switching to the CBZ, even
        // though closeComic() was never called -- before the cross-format fix, openComic's non-EPUB
        // path never looked at epubExtractedDir at all, so this directory would have leaked until the
        // next EPUB open or an explicit close.
        assertTrue(!extractedDir.exists())
        assertNull(viewModel.uiState.value.epubBook)
    }

    @Test
    fun `opening a CBR comic decodes pages via the real junrar-backed pipeline`() = runTest {
        val fixtureBytes = javaClass.classLoader!!.getResourceAsStream("sample.cbr")!!.readBytes()
        val comicFile = File(tempFolder.newFolder("Comics"), "test.cbr")
        comicFile.writeBytes(fixtureBytes)
        val cbrExtractionRoot = tempFolder.newFolder("cbr-cache-${System.nanoTime()}")
        val comic = ComicItem(
            id = "cbr-comic",
            title = "Test CBR",
            pathOrUrl = comicFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBR
        )
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            cbrCacheRoot = { cbrExtractionRoot }
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // sample.cbr has 3 real pages (page1.jpg, page2.jpg, page10.jpg -- ComicInfo.xml
        // excluded). This proves the real junrar extraction + CbrPageSource + PageBitmapCache
        // pipeline is genuinely wired together, not just that some mock returned a value.
        // (totalPages comes from pageCount, which never touches BitmapFactory -- pageLoadError
        // is deliberately not asserted here since BitmapFactory decode isn't testable in this
        // project's plain-JVM unit test environment, the same accepted limitation CBZ/PDF page
        // decode already has: no Robolectric, so android.graphics.BitmapFactory always fails.)
        assertEquals(3, viewModel.uiState.value.totalPages)
    }

    @Test
    fun `opening a PDF or CBZ comic never touches cbrCacheRoot`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var cbrCacheRootCallCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            cbrCacheRoot = { cbrCacheRootCallCount++; tempFolder.newFolder("should-not-be-used-${System.nanoTime()}") }
        )
        val comic = ComicItem(
            id = "cbz-comic",
            title = "Test CBZ",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // This proves cbrCacheRoot is genuinely lazy -- opening a non-CBR comic must never
        // evaluate it, which is what lets every existing PDF/CBZ test in this file keep
        // constructing ReaderViewModel without supplying a fake cbrCacheRoot at all.
        assertEquals(0, cbrCacheRootCallCount)
    }

    private fun buildCbz(pageCount: Int): File {
        val zipFile = tempFolder.newFile("state-transition-${System.nanoTime()}.cbz")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (i in 1..pageCount) {
                zip.putNextEntry(java.util.zip.ZipEntry("page$i.jpg"))
                zip.write("fake-jpeg-bytes-$i".toByteArray())
                zip.closeEntry()
            }
        }
        return zipFile
    }

    @Test
    fun `setPage clamps below 1 to page 1`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(id = "1", title = "Test", pathOrUrl = buildCbz(3).absolutePath, source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        viewModel.openComic(comic)
        advanceUntilIdle()

        val clamped = viewModel.setCurrentPageIndicator(-5)

        assertEquals(1, clamped)
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `setPage clamps above totalPages to the last page`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(id = "1", title = "Test", pathOrUrl = buildCbz(3).absolutePath, source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        viewModel.openComic(comic)
        advanceUntilIdle()

        val clamped = viewModel.setCurrentPageIndicator(99)

        assertEquals(3, clamped)
        assertEquals(3, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `setPage accepts an in-range value unchanged`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(id = "1", title = "Test", pathOrUrl = buildCbz(3).absolutePath, source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        viewModel.openComic(comic)
        advanceUntilIdle()

        val clamped = viewModel.setCurrentPageIndicator(2)

        assertEquals(2, clamped)
        assertEquals(2, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `setCurrentPageIndicator updates the active comic's progress fields`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        val comic = ComicItem(id = "1", title = "Test", pathOrUrl = buildCbz(4).absolutePath, source = ComicSource.LOCAL, format = ComicFormat.CBZ)
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setCurrentPageIndicator(2)

        assertEquals(2, comic.currentPage)
        assertEquals(0.5f, comic.progressPercentage, 0.001f)
    }

    @Test
    fun `setReadingMode updates readingMode for every mode`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        for (mode in ReadingMode.entries) {
            viewModel.setReadingMode(mode)
            assertEquals(mode, viewModel.uiState.value.readingMode)
        }
    }

    @Test
    fun `setFilterMode updates filterMode for every mode`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)

        for (mode in ColorFilterMode.entries) {
            viewModel.setFilterMode(mode)
            assertEquals(mode, viewModel.uiState.value.filterMode)
        }
    }

    @Test
    fun `toggleControls flips isControlsVisible each call`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)
        assertTrue(viewModel.uiState.value.isControlsVisible)

        viewModel.toggleControls()
        assertFalse(viewModel.uiState.value.isControlsVisible)

        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.isControlsVisible)
    }

    @Test
    fun `toggleAutoCrop flips autoCropMargins each call`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo)
        assertTrue(viewModel.uiState.value.autoCropMargins)

        viewModel.toggleAutoCrop()
        assertFalse(viewModel.uiState.value.autoCropMargins)

        viewModel.toggleAutoCrop()
        assertTrue(viewModel.uiState.value.autoCropMargins)
    }
}
