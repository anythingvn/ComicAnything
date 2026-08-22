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
import com.comicanything.reader.data.repository.DriveApiException
import com.comicanything.reader.data.repository.DriveConnectionHint
import com.comicanything.reader.data.repository.DriveConnectionRepository
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.LocalEntry
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.ReaderSettingsRepository
import com.comicanything.reader.data.repository.ReadingProgress
import com.comicanything.reader.data.repository.ReadingProgressRepository
import com.comicanything.reader.data.repository.SavedDriveLinkRepository
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

    // Same hazard as progressRepo/connectionRepo above, but ReaderViewModel wraps its default as a
    // `() -> SavedDriveLinkRepository` supplier instead of constructing eagerly (see its doc
    // comment), so most tests never need this at all -- only ones that exercise the Jump to
    // Folder tab's saved-links path pass `savedDriveLinkRepo = { savedDriveLinkRepo }` explicitly.
    private lateinit var savedDriveLinkRepo: SavedDriveLinkRepository

    // Same lazy-supplier situation as savedDriveLinkRepo above: only tests that call
    // setReadingMode/setFilterMode/toggleAutoCrop (which persistReaderSettings() now writes
    // synchronously via runBlocking, so the hazard fires unconditionally rather than only when a
    // pending coroutine happened to be pumped) need to pass `settingsRepo = { settingsRepo }`.
    private lateinit var settingsRepo: ReaderSettingsRepository

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

        val savedLinksDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "test-saved-links-${System.nanoTime()}.preferences_pb") }
        )
        savedDriveLinkRepo = SavedDriveLinkRepository(savedLinksDataStore, ioDispatcher = Dispatchers.Unconfined)

        val settingsDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "test-settings-${System.nanoTime()}.preferences_pb") }
        )
        settingsRepo = ReaderSettingsRepository(settingsDataStore, ioDispatcher = Dispatchers.Unconfined)
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
    fun `opening a Google Drive comic resolves through the injected resolver and opens normally`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val driveFile = File(tempFolder.newFolder("drive-cache"), "cached.cbz")
        java.util.zip.ZipOutputStream(driveFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zip.write("fake-jpeg-bytes".toByteArray())
            zip.closeEntry()
        }
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> driveFile }
        )
        val comic = ComicItem(
            id = "2",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/2?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // The old behavior always set pageLoadError = "This format isn't supported yet" purely
        // because comic.source was GOOGLE_DRIVE. Now that createPageSource no longer gates on
        // source at all (resolution happens first via the injected comicFileResolver), a Drive
        // comic opens through the exact same CbzPageSource path a LOCAL CBZ would -- totalPages
        // comes from real zip-entry listing, not BitmapFactory decode, so this is a genuine
        // assertion. pageLoadError is deliberately not asserted here: openComic's success path
        // still calls loadPage(), which decodes page 1 via BitmapFactory -- unavailable in this
        // project's plain-JVM unit test environment (no Robolectric), the same accepted
        // limitation already documented for the CBR/PDF/CBZ tests elsewhere in this file.
        assertEquals(1, viewModel.uiState.value.totalPages)
    }

    @Test
    fun `a Drive download failure surfaces through pageLoadError like any other open failure`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> throw DriveApiException("Not connected to Google Drive") }
        )
        val comic = ComicItem(
            id = "3",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/3?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("Not connected to Google Drive", viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `opening a LOCAL comic never touches driveFileCache or driveAccessToken`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var driveAccessTokenCalls = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { driveAccessTokenCalls++; "should-not-be-used" }
        )
        val comic = ComicItem(
            id = "4",
            title = "Local Book",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // Proves comicFileResolver's default (calling resolveComicFile for real) genuinely takes
        // the LOCAL passthrough branch without ever invoking driveAccessToken -- matching the same
        // laziness guarantee cbrCacheRoot already has for non-CBR opens.
        assertEquals(0, driveAccessTokenCalls)
    }

    @Test
    fun `navigateDriveFolder pushes a breadcrumb and populates driveEntries on success`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub1", name = "Comics"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals(listOf("My Drive"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
        assertEquals(fakeEntries, viewModel.uiState.value.driveEntries)
        assertNull(viewModel.uiState.value.driveError)
        assertFalse(viewModel.uiState.value.isLoadingDrive)
    }

    @Test
    fun `navigateDriveFolder surfaces a DriveApiException from the fetch as driveError`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> throw DriveApiException("Couldn't reach Google Drive -- check your connection") }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals("Couldn't reach Google Drive -- check your connection", viewModel.uiState.value.driveError)
        assertFalse(viewModel.uiState.value.isLoadingDrive)
    }

    @Test
    fun `navigateDriveFolder surfaces a generic exception as driveError instead of crashing`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> throw IllegalStateException("boom") }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        // Proves fetchCurrentDriveFolder's catch was widened from `catch (e: DriveApiException)`
        // to also catch plain Exception. GoogleDriveRepository can throw more than
        // DriveApiException from deep inside response.use { } (a raw IOException from a dropped
        // connection mid-read, or a JSONException from a non-JSON response body) -- this fake uses
        // a plain IllegalStateException to prove the VM-level widening independently of the
        // repository layer's own widening: without it, this exception would propagate out of
        // viewModelScope.launch uncaught and crash the app instead of landing in driveError.
        assertEquals("boom", viewModel.uiState.value.driveError)
        assertFalse(viewModel.uiState.value.isLoadingDrive)
    }

    @Test
    fun `updateDriveAccessToken automatically retries a folder fetch that previously failed for lack of a token`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub1", name = "Comics"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            // Deliberately NOT overriding driveAccessToken here -- its default reads
            // pushedDriveAccessToken, which is exactly what updateDriveAccessToken() writes to.
            // Overriding it with a fixed lambda (like the other tests in this file do) would
            // bypass the push mechanism this test needs to exercise. fetchDriveFolderContents is
            // only ever reached once a non-null token is present (see the short-circuit in
            // fetchCurrentDriveFolder), so it's safe for this fake to unconditionally succeed.
            fetchDriveFolderContents = { _, _ -> fakeEntries }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        assertEquals("Connect your Google Drive to browse it", viewModel.uiState.value.driveError)
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())

        viewModel.updateDriveAccessToken("real-token")
        advanceUntilIdle()

        // This is the mechanism that fixes the cold-start race (Finding 6): when a real token
        // arrives while a driveError from an earlier attempt is still showing, the folder is
        // automatically re-fetched instead of leaving the user stuck on a stale error.
        assertNull(viewModel.uiState.value.driveError)
        assertEquals(fakeEntries, viewModel.uiState.value.driveEntries)
    }

    @Test
    fun `downloadDriveComic marks the comic downloading then cached`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var resolverCalls = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> resolverCalls++; File(tempFolder.root, "fake") }
        )
        val comic = ComicItem(id = "d1", title = "Book", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)

        viewModel.downloadDriveComic(comic)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.driveCachedIds.contains("d1"))
        assertTrue(viewModel.uiState.value.driveDownloadingIds.isEmpty())
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `downloadDriveComic is a no-op when already cached`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var resolverCalls = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> resolverCalls++; File(tempFolder.root, "fake") }
        )
        val comic = ComicItem(id = "d2", title = "Book", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)

        viewModel.downloadDriveComic(comic)
        advanceUntilIdle()
        viewModel.downloadDriveComic(comic)
        advanceUntilIdle()

        assertEquals(1, resolverCalls)
    }

    @Test
    fun `downloadDriveComic leaves the comic uncached when resolution fails`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> throw DriveApiException("network error") }
        )
        val comic = ComicItem(id = "d3", title = "Book", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)

        viewModel.downloadDriveComic(comic)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.driveCachedIds.contains("d3"))
        assertTrue(viewModel.uiState.value.driveDownloadingIds.isEmpty())
    }

    @Test
    fun `deleteDriveComicCache removes the cached file and its id from state`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val cache = com.comicanything.reader.data.repository.DriveFileCache(tempFolder.newFolder("drive-cache"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveFileCache = { cache },
            comicFileResolver = { comic, driveCache, _ -> driveCache().download(comic.id) { it.writeText("data") } }
        )
        val comic = ComicItem(id = "d4", title = "Book", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)
        viewModel.downloadDriveComic(comic)
        advanceUntilIdle()
        assertTrue(cache.cachedFile("d4") != null)

        viewModel.deleteDriveComicCache("d4")

        assertNull(cache.cachedFile("d4"))
        assertFalse(viewModel.uiState.value.driveCachedIds.contains("d4"))
    }

    @Test
    fun `downloadDriveFolder downloads only the comic files directly in that folder, skipping already-cached ones`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var resolverCalls = mutableListOf<String>()
        val folderEntries = listOf(
            DriveEntry.Folder(id = "sub", name = "Subfolder"),
            DriveEntry.ComicFile(ComicItem(id = "f1", title = "One", pathOrUrl = "url1", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)),
            DriveEntry.ComicFile(ComicItem(id = "f2", title = "Two", pathOrUrl = "url2", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ))
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> folderEntries },
            comicFileResolver = { comic, _, _ -> resolverCalls.add(comic.id); File(tempFolder.root, "fake") }
        )

        viewModel.downloadDriveFolder("root")
        advanceUntilIdle()

        assertEquals(listOf("f1", "f2"), resolverCalls)
        assertEquals(setOf("f1", "f2"), viewModel.uiState.value.driveCachedIds)
        assertTrue(viewModel.uiState.value.driveDownloadingFolderIds.isEmpty())
    }

    @Test
    fun `clearDriveCache empties every cached file and clears driveCachedIds`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val cache = com.comicanything.reader.data.repository.DriveFileCache(tempFolder.newFolder("drive-cache-2"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveFileCache = { cache },
            comicFileResolver = { comic, driveCache, _ -> driveCache().download(comic.id) { it.writeText("data") } }
        )
        val comicA = ComicItem(id = "c1", title = "A", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)
        val comicB = ComicItem(id = "c2", title = "B", pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)
        viewModel.downloadDriveComic(comicA)
        advanceUntilIdle()
        viewModel.downloadDriveComic(comicB)
        advanceUntilIdle()
        assertEquals(2, cache.let { c -> listOf(c.cachedFile("c1"), c.cachedFile("c2")).count { it != null } })

        viewModel.clearDriveCache()

        assertNull(cache.cachedFile("c1"))
        assertNull(cache.cachedFile("c2"))
        assertTrue(viewModel.uiState.value.driveCachedIds.isEmpty())
    }

    @Test
    fun `navigateDriveFolder without a connected token sets a not-connected error and never calls the repository`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals("Connect your Google Drive to browse it", viewModel.uiState.value.driveError)
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())
    }

    @Test
    fun `navigateDriveUp truncates breadcrumbs to the tapped level`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null } // forces a fast, deterministic driveError instead of a real network call
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.navigateDriveFolder("sub1", "Comics")
        advanceUntilIdle()
        viewModel.navigateDriveFolder("sub2", "Volume 1")
        advanceUntilIdle()
        assertEquals(listOf("My Drive", "Comics", "Volume 1"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })

        viewModel.navigateDriveUp(1)
        advanceUntilIdle()

        assertEquals(listOf("My Drive", "Comics"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
    }

    @Test
    fun `disconnectDrive clears driveEntries, breadcrumbs, and driveError`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isNotEmpty())
        assertTrue(viewModel.uiState.value.driveError != null)

        viewModel.disconnectDrive()

        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isEmpty())
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())
        assertNull(viewModel.uiState.value.driveError)
    }

    @Test
    fun `searchDriveTree populates driveSearchResults from the current folder`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeHits = listOf(
            com.comicanything.reader.data.repository.DriveSearchHit(
                DriveEntry.Folder(id = "sub1", name = "Comics"),
                parentPath = emptyList()
            )
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() },
            searchDriveFolderTree = { _, _, _ -> fakeHits }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        viewModel.searchDriveTree("com")
        advanceUntilIdle()

        assertEquals(fakeHits, viewModel.uiState.value.driveSearchResults)
        assertFalse(viewModel.uiState.value.isSearchingDriveTree)
    }

    @Test
    fun `searchDriveTree with a blank query clears any active search instead of running one`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var callCount = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() },
            searchDriveFolderTree = { _, _, _ -> callCount++; emptyList() }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.searchDriveTree("comics")
        advanceUntilIdle()
        assertEquals(1, callCount)

        viewModel.searchDriveTree("")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.driveSearchResults)
        assertEquals(1, callCount)
    }

    @Test
    fun `searchDriveTree surfaces a failed search as an error instead of reporting it as no matches`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() },
            searchDriveFolderTree = { _, _, _ -> throw DriveApiException("token expired") }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        viewModel.searchDriveTree("comics")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.driveSearchResults)
        assertEquals("token expired", viewModel.uiState.value.driveSearchError)
        assertFalse(viewModel.uiState.value.isSearchingDriveTree)
    }

    @Test
    fun `searchDriveTree with no access token surfaces an error instead of reporting it as no matches`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null },
            fetchDriveFolderContents = { _, _ -> emptyList() },
            searchDriveFolderTree = { _, _, _ -> emptyList() }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        viewModel.searchDriveTree("comics")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.driveSearchResults)
        assertEquals("Not connected to Google Drive", viewModel.uiState.value.driveSearchError)
    }

    @Test
    fun `navigateDriveToBreadcrumbs replaces the trail, fetches that folder, and clears the search`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub2", name = "Manga"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries },
            searchDriveFolderTree = { _, _, _ -> listOf(com.comicanything.reader.data.repository.DriveSearchHit(DriveEntry.Folder("sub1", "Comics"), emptyList())) }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.searchDriveTree("comics")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.driveSearchResults != null)

        viewModel.navigateDriveToBreadcrumbs(listOf(DriveBreadcrumb("root", "My Drive"), DriveBreadcrumb("sub1", "Comics")))
        advanceUntilIdle()

        assertEquals(listOf("My Drive", "Comics"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
        assertEquals(fakeEntries, viewModel.uiState.value.driveEntries)
        assertNull(viewModel.uiState.value.driveSearchResults)
    }

    @Test
    fun `navigateToLinkedFolderInJumpTab starts a fresh single-folder trail and populates jumpToEntries`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub1", name = "Comics"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries }
        )

        viewModel.navigateToLinkedFolderInJumpTab("https://drive.google.com/drive/folders/shared-id?usp=sharing")
        advanceUntilIdle()

        assertEquals(listOf("shared-id"), viewModel.uiState.value.jumpToBreadcrumbs.map { it.folderId })
        assertEquals(fakeEntries, viewModel.uiState.value.jumpToEntries)
        assertNull(viewModel.uiState.value.jumpToError)
        assertFalse(viewModel.uiState.value.isLoadingJumpTo)
    }

    @Test
    fun `jump-to-folder browsing is independent from the main Google Drive tab's breadcrumbs and entries`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val driveEntries = listOf(DriveEntry.Folder(id = "d1", name = "MyDriveFolder"))
        val jumpEntries = listOf(DriveEntry.Folder(id = "j1", name = "SharedFolder"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { folderId, _ -> if (folderId == "root") driveEntries else jumpEntries }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.navigateToLinkedFolderInJumpTab("shared-id")
        advanceUntilIdle()

        assertEquals(listOf("My Drive"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
        assertEquals(driveEntries, viewModel.uiState.value.driveEntries)
        assertEquals(listOf("shared-id"), viewModel.uiState.value.jumpToBreadcrumbs.map { it.folderId })
        assertEquals(jumpEntries, viewModel.uiState.value.jumpToEntries)
    }

    @Test
    fun `navigateJumpToFolder descends from wherever the jump tab already is, not from the main Drive tab`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val nestedEntries = listOf(DriveEntry.ComicFile(ComicItem(id = "c1", title = "vol1.cbz", pathOrUrl = "https://x/c1", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { folderId, _ -> if (folderId == "nested") nestedEntries else emptyList() }
        )

        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()
        viewModel.navigateJumpToFolder("nested", "Nested")
        advanceUntilIdle()

        assertEquals(listOf("shared-root", "Nested"), viewModel.uiState.value.jumpToBreadcrumbs.map { it.name })
        assertEquals(nestedEntries, viewModel.uiState.value.jumpToEntries)
        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isEmpty())
    }

    @Test
    fun `searchJumpToTree populates jumpToSearchResults from the jump tab's own current folder`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeHits = listOf(com.comicanything.reader.data.repository.DriveSearchHit(DriveEntry.Folder("sub1", "Comics"), emptyList()))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() },
            searchDriveFolderTree = { _, _, _ -> fakeHits }
        )
        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()

        viewModel.searchJumpToTree("com")
        advanceUntilIdle()

        assertEquals(fakeHits, viewModel.uiState.value.jumpToSearchResults)
        assertNull(viewModel.uiState.value.driveSearchResults)
        assertFalse(viewModel.uiState.value.isSearchingJumpToTree)
    }

    @Test
    fun `navigateJumpToBreadcrumbs replaces the jump tab's trail, fetches that folder, and clears its search`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub2", name = "Manga"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries },
            searchDriveFolderTree = { _, _, _ -> listOf(com.comicanything.reader.data.repository.DriveSearchHit(DriveEntry.Folder("sub1", "Comics"), emptyList())) }
        )
        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()
        viewModel.searchJumpToTree("comics")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.jumpToSearchResults != null)

        viewModel.navigateJumpToBreadcrumbs(listOf(DriveBreadcrumb("shared-root", "shared-root"), DriveBreadcrumb("sub1", "Comics")))
        advanceUntilIdle()

        assertEquals(listOf("shared-root", "Comics"), viewModel.uiState.value.jumpToBreadcrumbs.map { it.name })
        assertEquals(fakeEntries, viewModel.uiState.value.jumpToEntries)
        assertNull(viewModel.uiState.value.jumpToSearchResults)
    }

    @Test
    fun `navigateJumpToFolder without a connected token sets a not-connected jumpToError`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { null },
            fetchDriveFolderContents = { _, _ -> emptyList() }
        )

        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()

        assertEquals("Connect your Google Drive to browse it", viewModel.uiState.value.jumpToError)
        assertFalse(viewModel.uiState.value.isLoadingJumpTo)
    }

    @Test
    fun `navigateToLinkedFolderInJumpTab records the folder as a recent saved link`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() }
        )

        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()

        val saved = viewModel.uiState.value.savedDriveLinks.single()
        assertEquals("shared-root", saved.folderId)
        assertFalse(saved.isFavorite)
    }

    @Test
    fun `setJumpToFolderFavorite stars a folder with a custom name`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() }
        )
        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()

        viewModel.setJumpToFolderFavorite("shared-root", isFavorite = true, customName = "My Comics")
        advanceUntilIdle()

        val saved = viewModel.uiState.value.savedDriveLinks.single()
        assertTrue(saved.isFavorite)
        assertEquals("My Comics", saved.customName)
    }

    @Test
    fun `removeSavedDriveLink deletes it from savedDriveLinks`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> emptyList() }
        )
        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.savedDriveLinks.isNotEmpty())

        viewModel.removeSavedDriveLink("shared-root")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savedDriveLinks.isEmpty())
    }

    @Test
    fun `clearJumpToFolder resets the jump tab to its starting state without touching saved links or the main Drive tab`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val driveEntries = listOf(DriveEntry.Folder(id = "d1", name = "MyDriveFolder"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            savedDriveLinkRepo = { savedDriveLinkRepo },
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { folderId, _ -> if (folderId == "root") driveEntries else emptyList() }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.navigateToLinkedFolderInJumpTab("shared-root")
        advanceUntilIdle()
        viewModel.searchJumpToTree("anything")
        advanceUntilIdle()

        viewModel.clearJumpToFolder()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.jumpToBreadcrumbs.isEmpty())
        assertTrue(viewModel.uiState.value.jumpToEntries.isEmpty())
        assertNull(viewModel.uiState.value.jumpToSearchResults)
        // The saved-link record from the jump above must survive being cleared -- clearing is
        // just "go back to the list," not "forget this folder existed."
        assertTrue(viewModel.uiState.value.savedDriveLinks.any { it.folderId == "shared-root" })
        // And the main Google Drive tab's own breadcrumb trail must be untouched.
        assertEquals(listOf("My Drive"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
        assertEquals(driveEntries, viewModel.uiState.value.driveEntries)
    }

    @Test
    fun `navigateLocalFolder pushes a breadcrumb and lists the real directory's contents`() = runTest {
        val subfolder = tempFolder.newFolder("Comics")
        File(subfolder, "test.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)

        viewModel.navigateLocalFolder(tempFolder.root.absolutePath, "Internal Storage")
        advanceUntilIdle()

        assertEquals(listOf("Internal Storage"), viewModel.uiState.value.localBreadcrumbs.map { it.name })
        assertEquals(listOf("Comics"), viewModel.uiState.value.localEntries.filterIsInstance<LocalEntry.Folder>().map { it.name })
        assertFalse(viewModel.uiState.value.isLoadingLocalFolder)

        viewModel.navigateLocalFolder(subfolder.absolutePath, "Comics")
        advanceUntilIdle()

        assertEquals(listOf("Internal Storage", "Comics"), viewModel.uiState.value.localBreadcrumbs.map { it.name })
        assertEquals(listOf("test.cbz"), viewModel.uiState.value.localEntries.filterIsInstance<LocalEntry.ComicFile>().map { it.comic.title })
    }

    @Test
    fun `navigateLocalUp truncates local breadcrumbs to the tapped level`() = runTest {
        val comics = tempFolder.newFolder("Comics")
        val manga = File(comics, "Manga").apply { mkdir() }
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.navigateLocalFolder(tempFolder.root.absolutePath, "Internal Storage")
        advanceUntilIdle()
        viewModel.navigateLocalFolder(comics.absolutePath, "Comics")
        advanceUntilIdle()
        viewModel.navigateLocalFolder(manga.absolutePath, "Manga")
        advanceUntilIdle()
        assertEquals(listOf("Internal Storage", "Comics", "Manga"), viewModel.uiState.value.localBreadcrumbs.map { it.name })

        viewModel.navigateLocalUp(1)
        advanceUntilIdle()

        assertEquals(listOf("Internal Storage", "Comics"), viewModel.uiState.value.localBreadcrumbs.map { it.name })
    }

    @Test
    fun `searchLocalTree finds a real match several folders below the current one`() = runTest {
        val nested = tempFolder.newFolder("Comics", "Manga")
        File(nested, "solo_leveling.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.navigateLocalFolder(tempFolder.root.absolutePath, "Internal Storage")
        advanceUntilIdle()

        viewModel.searchLocalTree("leveling")
        advanceUntilIdle()

        val results = viewModel.uiState.value.localSearchResults
        assertEquals(1, results?.size)
        assertEquals("solo_leveling.cbz", (results!!.single() as LocalEntry.ComicFile).comic.title)
        assertFalse(viewModel.uiState.value.isSearchingLocalTree)
    }

    @Test
    fun `searchLocalTree with a blank query clears results instead of searching`() = runTest {
        tempFolder.newFolder("Comics")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.navigateLocalFolder(tempFolder.root.absolutePath, "Internal Storage")
        advanceUntilIdle()
        viewModel.searchLocalTree("comics")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.localSearchResults != null)

        viewModel.searchLocalTree("   ")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.localSearchResults)
    }

    @Test
    fun `navigateLocalToBreadcrumbs replaces the trail, lists that folder, and clears the search`() = runTest {
        val comics = tempFolder.newFolder("Comics")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.navigateLocalFolder(tempFolder.root.absolutePath, "Internal Storage")
        advanceUntilIdle()
        viewModel.searchLocalTree("comics")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.localSearchResults != null)

        viewModel.navigateLocalToBreadcrumbs(
            listOf(LocalBreadcrumb(tempFolder.root.absolutePath, "Internal Storage"), LocalBreadcrumb(comics.absolutePath, "Comics"))
        )
        advanceUntilIdle()

        assertEquals(listOf("Internal Storage", "Comics"), viewModel.uiState.value.localBreadcrumbs.map { it.name })
        assertNull(viewModel.uiState.value.localSearchResults)
    }

    @Test
    fun `revoking storage permission clears local browse state`() = runTest {
        val subfolder = tempFolder.newFolder("Comics")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo, connectionRepo = connectionRepo)
        viewModel.setPermissionGranted(true)
        viewModel.navigateLocalFolder(subfolder.absolutePath, "Comics")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.localBreadcrumbs.isNotEmpty())

        viewModel.setPermissionGranted(false)

        assertTrue(viewModel.uiState.value.localBreadcrumbs.isEmpty())
        assertTrue(viewModel.uiState.value.localEntries.isEmpty())
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
    fun `onDriveAuthorized clears stale browsing state from a previous account`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub1", name = "Comics"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries }
        )
        viewModel.onDriveAuthorized("first@example.com")
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isNotEmpty())
        assertTrue(viewModel.uiState.value.driveEntries.isNotEmpty())

        val versionBeforeSwitch = viewModel.uiState.value.driveConnectionVersion

        // Simulates MainActivity.switchDriveAccount(): re-authorizing with a DIFFERENT account
        // while old browsing state (a folder ID from the first account's Drive) is still around.
        viewModel.onDriveAuthorized("second@example.com")

        assertEquals("second@example.com", viewModel.uiState.value.driveAccountEmail)
        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isEmpty())
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())
        // DriveContent's auto-navigate-to-root LaunchedEffect keys on this field specifically
        // because isDriveConnected alone doesn't change value on a switch-while-connected -- see
        // the field's doc comment in ReaderUiState for why that would otherwise get the UI stuck.
        assertTrue(viewModel.uiState.value.driveConnectionVersion > versionBeforeSwitch)
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo, ioDispatcher = Dispatchers.Unconfined, settingsRepo = { settingsRepo })

        for (mode in ReadingMode.entries) {
            viewModel.setReadingMode(mode)
            assertEquals(mode, viewModel.uiState.value.readingMode)
        }
    }

    @Test
    fun `setFilterMode updates filterMode for every mode`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo, ioDispatcher = Dispatchers.Unconfined, settingsRepo = { settingsRepo })

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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo, ioDispatcher = Dispatchers.Unconfined, settingsRepo = { settingsRepo })
        assertTrue(viewModel.uiState.value.autoCropMargins)

        viewModel.toggleAutoCrop()
        assertFalse(viewModel.uiState.value.autoCropMargins)

        viewModel.toggleAutoCrop()
        assertTrue(viewModel.uiState.value.autoCropMargins)
    }

    @Test
    fun `toggleGridLayout flips isGridLayout each call`() {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo, connectionRepo = connectionRepo, ioDispatcher = Dispatchers.Unconfined, settingsRepo = { settingsRepo })
        assertTrue(viewModel.uiState.value.isGridLayout)

        viewModel.toggleGridLayout()
        assertFalse(viewModel.uiState.value.isGridLayout)

        viewModel.toggleGridLayout()
        assertTrue(viewModel.uiState.value.isGridLayout)
    }

    @Test
    fun `setReadingMode, setFilterMode, toggleAutoCrop, and toggleGridLayout persist and are restored by loadReaderSettings`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val settingsDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "test-settings-${System.nanoTime()}.preferences_pb") }
        )
        val settingsRepo = ReaderSettingsRepository(settingsDataStore, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            ioDispatcher = Dispatchers.Unconfined,
            settingsRepo = { settingsRepo }
        )

        viewModel.setReadingMode(ReadingMode.WEBTOON)
        viewModel.setFilterMode(ColorFilterMode.SEPIA)
        viewModel.toggleAutoCrop()
        viewModel.toggleGridLayout()
        advanceUntilIdle()

        // A fresh ViewModel (simulating an app restart) with the SAME underlying settingsRepo
        // should pick up exactly what the first instance persisted, not the built-in defaults.
        val restartedViewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            ioDispatcher = Dispatchers.Unconfined,
            settingsRepo = { settingsRepo }
        )
        restartedViewModel.loadReaderSettings()
        advanceUntilIdle()

        assertEquals(ReadingMode.WEBTOON, restartedViewModel.uiState.value.readingMode)
        assertEquals(ColorFilterMode.SEPIA, restartedViewModel.uiState.value.filterMode)
        assertFalse(restartedViewModel.uiState.value.autoCropMargins)
        assertFalse(restartedViewModel.uiState.value.isGridLayout)
    }
}
