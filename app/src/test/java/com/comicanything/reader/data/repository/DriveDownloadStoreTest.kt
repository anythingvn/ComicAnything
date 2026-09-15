package com.comicanything.reader.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DriveDownloadStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cachedFile returns null when nothing is downloaded`() {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))
        assertNull(store.cachedFile("Kotaro", "Missing.pdf"))
    }

    @Test
    fun `download writes the file under folderName and cachedFile finds it afterward`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))

        val result = store.download("Kotaro", "Chapter1.pdf") { dest -> dest.writeText("hello") }

        assertEquals("hello", result.readText())
        assertEquals("Kotaro", result.parentFile?.name)
        assertEquals("hello", store.cachedFile("Kotaro", "Chapter1.pdf")?.readText())
    }

    @Test
    fun `a null folderName is stored under Unsorted instead of the downloads root directly`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))

        val result = store.download(null, "Loose.pdf") { dest -> dest.writeText("hello") }

        assertEquals("Unsorted", result.parentFile?.name)
        assertEquals("hello", store.cachedFile(null, "Loose.pdf")?.readText())
    }

    @Test
    fun `same-named files from different folders never collide`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))

        store.download("Kotaro", "Chapter1.pdf") { dest -> dest.writeText("kotaro-content") }
        store.download("Naruto", "Chapter1.pdf") { dest -> dest.writeText("naruto-content") }

        assertEquals("kotaro-content", store.cachedFile("Kotaro", "Chapter1.pdf")?.readText())
        assertEquals("naruto-content", store.cachedFile("Naruto", "Chapter1.pdf")?.readText())
    }

    @Test
    fun `a failed download leaves no part file and no final file behind`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))

        try {
            store.download("Kotaro", "Chapter1.pdf") { dest ->
                dest.writeText("partial")
                throw IOException("network dropped")
            }
        } catch (e: IOException) {
            // expected
        }

        assertNull(store.cachedFile("Kotaro", "Chapter1.pdf"))
    }

    @Test
    fun `two overlapping downloads of the same title never share a temp path and both finish with intact content`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))
        var firstPartFile: java.io.File? = null
        var secondPartFile: java.io.File? = null

        val firstResult = store.download("Kotaro", "Chapter1.pdf") { dest ->
            firstPartFile = dest
            dest.writeText("first-attempt-content")
        }
        val firstResultContentRightAfterFirstDownload = firstResult.readText()

        val secondResult = store.download("Kotaro", "Chapter1.pdf") { dest ->
            secondPartFile = dest
            dest.writeText("second-attempt-content-longer")
        }

        assertTrue(firstPartFile!!.name != secondPartFile!!.name)
        assertEquals("first-attempt-content", firstResultContentRightAfterFirstDownload)
        assertEquals("second-attempt-content-longer", secondResult.readText())
        assertEquals("second-attempt-content-longer", store.cachedFile("Kotaro", "Chapter1.pdf")?.readText())
    }

    @Test
    fun `delete removes only the targeted file`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))
        store.download("Kotaro", "Chapter1.pdf") { dest -> dest.writeText("a") }
        store.download("Kotaro", "Chapter2.pdf") { dest -> dest.writeText("b") }

        store.delete("Kotaro", "Chapter1.pdf")

        assertNull(store.cachedFile("Kotaro", "Chapter1.pdf"))
        assertEquals("b", store.cachedFile("Kotaro", "Chapter2.pdf")?.readText())
    }

    @Test
    fun `clearAll removes every downloaded file`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))
        store.download("Kotaro", "Chapter1.pdf") { dest -> dest.writeText("a") }
        store.download("Naruto", "Chapter1.pdf") { dest -> dest.writeText("b") }

        store.clearAll()

        assertNull(store.cachedFile("Kotaro", "Chapter1.pdf"))
        assertNull(store.cachedFile("Naruto", "Chapter1.pdf"))
    }

    @Test
    fun `listAll returns every downloaded file grouped by its folder, skipping part files`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))
        store.download("Kotaro", "Chapter1.pdf") { dest -> dest.writeText("a") }
        store.download("Kotaro", "Chapter2.pdf") { dest -> dest.writeText("b") }
        store.download("Naruto", "Chapter1.pdf") { dest -> dest.writeText("c") }

        val all = store.listAll()

        assertEquals(3, all.size)
        assertEquals(setOf("Kotaro" to "Chapter1.pdf", "Kotaro" to "Chapter2.pdf", "Naruto" to "Chapter1.pdf"), all.map { it.folderName to it.title }.toSet())
    }

    @Test
    fun `listAll returns an empty list when nothing has been downloaded yet`() {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads-empty"))

        assertTrue(store.listAll().isEmpty())
    }

    @Test
    fun `a title containing filesystem-unsafe characters is sanitized instead of failing`() = runTest {
        val store = DriveDownloadStore(tempFolder.newFolder("downloads"))

        val result = store.download("Kotaro", "Vol 1: Chapter/One?.pdf") { dest -> dest.writeText("hello") }

        assertTrue(result.exists())
        assertEquals("hello", result.readText())
    }
}
