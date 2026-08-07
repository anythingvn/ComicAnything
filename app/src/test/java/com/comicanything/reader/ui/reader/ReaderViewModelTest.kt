package com.comicanything.reader.ui.reader

import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.repository.LocalFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `granting permission after being denied triggers a library load`() = runTest {
        File(tempFolder.newFolder("Comics"), "batman.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
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
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
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
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(localRepo = repo)
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
        val viewModel = ReaderViewModel(localRepo = repo)

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
        val viewModel = ReaderViewModel(localRepo = repo)

        viewModel.refreshLibrary()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.libraryComics.isEmpty())
        assertFalse(viewModel.uiState.value.hasStoragePermission)
    }

    @Test
    fun `closeComic clears the active comic`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(localRepo = repo)
        val comic = ComicItem(
            id = "1",
            title = "Test Comic",
            pathOrUrl = "/fake/path.pdf",
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF
        )
        viewModel.openComic(comic)
        assertEquals(comic, viewModel.uiState.value.activeComic)

        viewModel.closeComic()

        assertNull(viewModel.uiState.value.activeComic)
    }
}
