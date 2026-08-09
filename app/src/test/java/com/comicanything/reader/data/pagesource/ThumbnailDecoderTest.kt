package com.comicanything.reader.data.pagesource

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailDecoderTest {

    private fun comic(format: ComicFormat, source: ComicSource = ComicSource.LOCAL) = ComicItem(
        id = "1",
        title = "Test Comic",
        pathOrUrl = "/fake/path.${format.name.lowercase()}",
        source = source,
        format = format
    )

    @Test
    fun `returns null for a Google Drive comic without touching the filesystem`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.PDF, source = ComicSource.GOOGLE_DRIVE))

        assertNull(result)
    }

    @Test
    fun `returns null for an EPUB comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.EPUB))

        assertNull(result)
    }

    @Test
    fun `returns null for a CBR comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.CBR))

        assertNull(result)
    }

    @Test
    fun `returns null for a MOBI comic`() = runTest {
        val result = decodeThumbnail(comic(ComicFormat.MOBI))

        assertNull(result)
    }

    @Test
    fun `sample size is 1 when the actual width is already at or below the target`() {
        assertEquals(1, calculateThumbnailSampleSize(actualWidth = 240, targetWidth = 240))
        assertEquals(1, calculateThumbnailSampleSize(actualWidth = 100, targetWidth = 240))
    }

    @Test
    fun `sample size doubles until half the actual width would drop below the target`() {
        // actualWidth=1000, targetWidth=240: halfWidth=500; 500/1>=240, 500/2=250>=240, 500/4=125<240 -> sampleSize=4
        assertEquals(4, calculateThumbnailSampleSize(actualWidth = 1000, targetWidth = 240))
    }

    @Test
    fun `sample size is 2 for a modestly oversized image`() {
        // actualWidth=600, targetWidth=240: halfWidth=300; 300/1>=240, 300/2=150<240 -> sampleSize=2
        assertEquals(2, calculateThumbnailSampleSize(actualWidth = 600, targetWidth = 240))
    }
}
