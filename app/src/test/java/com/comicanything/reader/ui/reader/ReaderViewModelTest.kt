package com.comicanything.reader.ui.reader

import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.repository.LocalFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
