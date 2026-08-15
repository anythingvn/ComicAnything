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

    @Test
    fun `searchTree matches a comic file title several folders below the search root`() = runTest {
        val nested = tempFolder.newFolder("Truyen tranh", "001 - Doi Bong")
        File(nested, "vol01.cbz").writeText("fake")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree(tempFolder.root.absolutePath, "vol01")

        assertEquals(1, result.size)
        val hit = result.single() as LocalEntry.ComicFile
        assertEquals("vol01.cbz", hit.comic.title)
        assertEquals(File(nested, "vol01.cbz").absolutePath, hit.comic.pathOrUrl)
    }

    @Test
    fun `searchTree matches a folder name without requiring its contents to match`() = runTest {
        tempFolder.newFolder("Truyen tranh", "Doi Bong Thanh Dong")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree(tempFolder.root.absolutePath, "thanh dong")

        assertEquals(1, result.size)
        assertEquals("Doi Bong Thanh Dong", (result.single() as LocalEntry.Folder).name)
    }

    @Test
    fun `searchTree is case-insensitive and matches by substring`() = runTest {
        tempFolder.newFile("Solo_Leveling.cbz")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree(tempFolder.root.absolutePath, "leveling")

        assertEquals(1, result.size)
    }

    @Test
    fun `searchTree does not match the search root folder itself`() = runTest {
        val root = tempFolder.newFolder("MyComics")
        File(root, "book.pdf").writeText("fake")
        val repo = LocalFileRepository(rootPath = root.absolutePath)

        val result = repo.searchTree(root.absolutePath, "MyComics")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchTree with a blank query returns no results`() = runTest {
        tempFolder.newFile("book.pdf")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree(tempFolder.root.absolutePath, "   ")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchTree ignores files with unsupported extensions`() = runTest {
        tempFolder.newFile("readme_notes.txt")
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree(tempFolder.root.absolutePath, "readme")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchTree of nonexistent root returns empty list`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath)

        val result = repo.searchTree("${tempFolder.root.absolutePath}/does-not-exist", "book")

        assertTrue(result.isEmpty())
    }
}
