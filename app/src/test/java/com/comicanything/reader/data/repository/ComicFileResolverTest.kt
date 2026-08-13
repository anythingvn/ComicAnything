package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicFileResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a LOCAL comic resolves to its path directly with no cache or network involved`() = runTest {
        val localFile = tempFolder.newFile("book.pdf")
        val comic = ComicItem(
            id = "1",
            title = "Local Book",
            pathOrUrl = localFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF
        )
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        var downloadCalls = 0

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> downloadCalls++ },
            accessToken = { "should-not-be-used" }
        )

        assertEquals(localFile.absolutePath, resolved.absolutePath)
        assertEquals(0, downloadCalls)
    }

    @Test
    fun `a cached Drive comic resolves from the cache with no network call`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        cache.download("drive-1") { dest -> dest.writeText("cached bytes") }
        val comic = ComicItem(
            id = "drive-1",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-1?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )
        var downloadCalls = 0

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> downloadCalls++ },
            accessToken = { "irrelevant-since-cached" }
        )

        assertEquals("cached bytes", resolved.readText())
        assertEquals(0, downloadCalls)
    }

    @Test
    fun `a cache-miss Drive comic downloads via the injected function and returns the result`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        val comic = ComicItem(
            id = "drive-2",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-2?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.CBZ
        )
        var capturedFileId: String? = null
        var capturedToken: String? = null

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { fileId, destination, token ->
                capturedFileId = fileId
                capturedToken = token
                destination.writeText("downloaded bytes")
            },
            accessToken = { "real-token" }
        )

        assertEquals("drive-2", capturedFileId)
        assertEquals("real-token", capturedToken)
        assertEquals("downloaded bytes", resolved.readText())
        assertEquals("downloaded bytes", cache.cachedFile("drive-2")?.readText())
    }

    @Test(expected = DriveApiException::class)
    fun `a cache-miss Drive comic with no access token fails fast without attempting a download`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        val comic = ComicItem(
            id = "drive-3",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-3?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> throw AssertionError("should not be called") },
            accessToken = { null }
        )
    }
}
