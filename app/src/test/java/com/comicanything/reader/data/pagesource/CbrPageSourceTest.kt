package com.comicanything.reader.data.pagesource

import com.github.junrar.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CbrPageSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fixture(name: String): File =
        File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test
    fun `lists and counts pages from a flat-layout CBR, excluding non-image entries`() {
        val source = CbrPageSource(fixture("sample.cbr"), tempFolder.newFolder("flat-${System.nanoTime()}"))

        // sample.cbr has page1.jpg, page2.jpg, page10.jpg, and ComicInfo.xml (non-image,
        // must be excluded) -- so pageCount must be exactly 3, not 4.
        assertEquals(3, source.pageCount)
        source.close()
    }

    @Test
    fun `pages wrapped in a subdirectory are still found via recursive listing`() {
        // nested.cbr wraps its pages in MyComic/ -- a common real-world CBR shape. A
        // single-level (non-recursive) directory listing after extraction would find zero
        // pages here; this is exactly the class of bug this plan's Global Constraints call out.
        val source = CbrPageSource(fixture("nested.cbr"), tempFolder.newFolder("nested-${System.nanoTime()}"))

        assertEquals(2, source.pageCount)
        source.close()
    }

    @Test
    fun `close deletes the extraction directory`() {
        val extractionDir = tempFolder.newFolder("cleanup-${System.nanoTime()}")
        val source = CbrPageSource(fixture("sample.cbr"), extractionDir)
        assertTrue(extractionDir.listFiles()?.isNotEmpty() == true)

        source.close()

        assertTrue(!extractionDir.exists())
    }

    @Test
    fun `re-extracting into the same directory clears any prior extraction first`() {
        val extractionDir = tempFolder.newFolder("reextract-${System.nanoTime()}")
        File(extractionDir, "stale-leftover-file.txt").writeText("should be gone after re-extraction")

        val source = CbrPageSource(fixture("sample.cbr"), extractionDir)

        assertTrue(!File(extractionDir, "stale-leftover-file.txt").exists())
        source.close()
    }

    @Test
    fun `pages in multiple subdirectories sort by full relative path, not basename`() {
        // multi_subdir.cbr contains Ch01/page1.jpg, Ch01/page2.jpg, Ch02/page1.jpg,
        // Ch02/page2.jpg. Sorting by bare filename alone (ignoring directory) would
        // interleave chapters as Ch01/page1, Ch02/page1, Ch01/page2, Ch02/page2 --
        // this test asserts the real, observable per-chapter order is preserved instead.
        val extractionDir = tempFolder.newFolder("multi-subdir-${System.nanoTime()}")

        Archive(fixture("multi_subdir.cbr")).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val outFile = File(extractionDir, header.fileName)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> archive.extractFile(header, out) }
                }
                header = archive.nextFileHeader()
            }
        }

        val ordered = listImagePageFilesSorted(extractionDir)
            .map { it.toRelativeString(extractionDir).replace(File.separatorChar, '/') }

        assertEquals(
            listOf("Ch01/page1.jpg", "Ch01/page2.jpg", "Ch02/page1.jpg", "Ch02/page2.jpg"),
            ordered,
        )
    }
}
