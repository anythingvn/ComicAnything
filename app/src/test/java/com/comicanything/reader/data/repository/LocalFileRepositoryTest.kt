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

    @Test
    fun `listDirectory returns subfolders and comic files, not nested contents`() = runTest {
        val manga = tempFolder.newFolder("Manga")
        tempFolder.newFolder("DC")
        File(manga, "solo_leveling.cbz").writeText("fake")
        tempFolder.newFile("top_level.pdf")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.listDirectory(tempFolder.root.absolutePath)

        val folders = result.filterIsInstance<LocalEntry.Folder>().map { it.name }
        val files = result.filterIsInstance<LocalEntry.ComicFile>().map { it.comic.title }
        assertEquals(listOf("DC", "Manga"), folders)
        assertEquals(listOf("top_level.pdf"), files)
    }

    @Test
    fun `listDirectory skips hidden folders and unsupported files`() = runTest {
        tempFolder.newFolder(".thumbnails")
        tempFolder.newFolder("Comics")
        tempFolder.newFile("notes.txt")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.listDirectory(tempFolder.root.absolutePath)

        assertEquals(listOf(LocalEntry.Folder(File(tempFolder.root, "Comics").absolutePath, "Comics")), result)
    }

    @Test
    fun `listDirectory of nonexistent path returns empty list`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.listDirectory("${tempFolder.root.absolutePath}/does-not-exist")

        assertTrue(result.isEmpty())
    }
}
