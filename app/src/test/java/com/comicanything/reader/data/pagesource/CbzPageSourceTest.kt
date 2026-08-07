package com.comicanything.reader.data.pagesource

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CbzPageSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildCbz(vararg entryNames: String): ZipFile {
        val zipFile = tempFolder.newFile("test-${System.nanoTime()}.cbz")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            entryNames.forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        return ZipFile(zipFile)
    }

    @Test
    fun `sorts pages in natural numeric order, not lexicographic`() {
        val zip = buildCbz("page10.jpg", "page2.jpg", "page1.jpg")

        val pages = listImagePagesSorted(zip)

        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), pages.map { it.name })
        zip.close()
    }

    @Test
    fun `filters out non-image entries`() {
        val zip = buildCbz("page1.jpg", "ComicInfo.xml", "page2.png", "thumbs.db")

        val pages = listImagePagesSorted(zip)

        assertEquals(listOf("page1.jpg", "page2.png"), pages.map { it.name })
        zip.close()
    }

    @Test
    fun `supports jpg, jpeg, png, webp, gif extensions case-insensitively`() {
        val zip = buildCbz("a.JPG", "b.jpeg", "c.PNG", "d.webp", "e.GIF")

        val pages = listImagePagesSorted(zip)

        assertEquals(5, pages.size)
        zip.close()
    }

    @Test
    fun `excludes macOS AppleDouble resource-fork entries`() {
        val zip = buildCbz("page1.jpg", "__MACOSX/._page1.jpg", "page2.jpg", "__MACOSX/._page2.jpg", ".DS_Store")

        val pages = listImagePagesSorted(zip)

        assertEquals(listOf("page1.jpg", "page2.jpg"), pages.map { it.name })
        zip.close()
    }
}
