package com.comicanything.reader.ui.reader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.ReadingProgress
import com.comicanything.reader.data.repository.ReadingProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
    }

    @Test
    fun `granting permission after being denied triggers a library load`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo)

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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo)
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo)
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo)

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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, progressRepo = progressRepo)

        viewModel.refreshLibrary()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
        assertFalse(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `closeComic clears the active comic`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo)
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
    fun `opening a comic with an unsupported format sets an error and does not crash`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo)
        val comic = ComicItem(
            id = "1",
            title = "Unsupported Book",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("This format isn't supported yet", viewModel.uiState.value.pageLoadError)
        assertNull(viewModel.uiState.value.currentPageBitmap)
    }

    @Test
    fun `opening a Google Drive comic sets an error even for a supported format`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo)
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo)

        val result = viewModel.loadPageBitmap(1)

        assertEquals(PageLoadState.Failed, result)
    }

    @Test
    fun `pageSourceGeneration increments each time a comic successfully opens`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = repo, ioDispatcher = Dispatchers.Unconfined, progressRepo = progressRepo)
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
        val viewModel = ReaderViewModel(application = fakeApplication, localRepo = localRepo, progressRepo = progressRepo)

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
}
