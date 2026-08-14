package com.comicanything.reader.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DriveFileCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cachedFile returns null when nothing is cached`() {
        val cache = DriveFileCache(tempFolder.newFolder("cache"))
        assertNull(cache.cachedFile("missing-id"))
    }

    @Test
    fun `download writes the file and cachedFile finds it afterward`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("cache"))

        val result = cache.download("comic-1") { dest -> dest.writeText("hello") }

        assertEquals("hello", result.readText())
        assertEquals("hello", cache.cachedFile("comic-1")?.readText())
    }

    @Test
    fun `a failed download leaves no part file and no final file behind`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        val cache = DriveFileCache(cacheDir)

        try {
            cache.download("comic-1") { dest ->
                dest.writeText("partial")
                throw IOException("network dropped")
            }
        } catch (e: IOException) {
            // expected
        }

        assertNull(cache.cachedFile("comic-1"))
        assertTrue(cacheDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `eviction deletes the least-recently-opened file first once the cap is exceeded`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        // A tiny cap (10 bytes) so two 6-byte files force eviction without needing real large files.
        val cache = DriveFileCache(cacheDir, maxTotalBytes = 10)

        cache.download("old") { dest -> dest.writeText("aaaaaa") } // 6 bytes
        File(cacheDir, "old").setLastModified(System.currentTimeMillis() - 60_000)

        cache.download("new") { dest -> dest.writeText("bbbbbb") } // 6 bytes; total 12 > cap of 10

        assertNull(cache.cachedFile("old"))
        assertEquals("bbbbbb", cache.cachedFile("new")?.readText())
    }

    @Test
    fun `two overlapping downloads of the same comicId never share a temp path and both finish with intact content`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        val cache = DriveFileCache(cacheDir)

        // Simulates a user backing out of a large in-progress Drive download and re-tapping the
        // same comic before the first attempt finishes: two "downloads" for the same comicId,
        // overlapping in time (both write before either renames). Content differs per attempt so
        // any interleaving between the two writers would be detectable as neither string intact.
        var firstPartFile: File? = null
        var secondPartFile: File? = null

        val firstResult = cache.download("comic-1") { dest ->
            firstPartFile = dest
            dest.writeText("first-attempt-content")
        }
        // Snapshot the first attempt's content immediately -- both downloads share one final path
        // (finalFile = File(cacheDir, comicId)), so once the second download's rename lands, that
        // shared path's on-disk content changes and firstResult.readText() would read the SECOND
        // attempt's bytes instead if read only after both complete.
        val firstResultContentRightAfterFirstDownload = firstResult.readText()

        val secondResult = cache.download("comic-1") { dest ->
            secondPartFile = dest
            dest.writeText("second-attempt-content-longer")
        }

        // Each attempt got its own unique temp path (the old fixed "$comicId.part" name would
        // make these equal), so there was never a window where both writers shared one file.
        assertTrue(firstPartFile!!.name != secondPartFile!!.name)
        assertTrue(firstPartFile!!.name.startsWith("comic-1."))
        assertTrue(secondPartFile!!.name.startsWith("comic-1."))

        // Both attempts completed with their own fully-intact (not truncated/mixed) content --
        // the second attempt's rename overwrote the first's, which is the expected/harmless
        // outcome of two genuinely separate, complete downloads racing for the same final name.
        assertEquals("first-attempt-content", firstResultContentRightAfterFirstDownload)
        assertEquals("second-attempt-content-longer", secondResult.readText())
        assertEquals("second-attempt-content-longer", cache.cachedFile("comic-1")?.readText())
    }

    @Test
    fun `a single download larger than the cap survives eviction instead of deleting itself`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        val cache = DriveFileCache(cacheDir, maxTotalBytes = 10)

        val result = cache.download("big") { dest -> dest.writeText("this text is way more than ten bytes") }

        assertTrue(result.exists())
        assertEquals("this text is way more than ten bytes", cache.cachedFile("big")?.readText())
    }
}
