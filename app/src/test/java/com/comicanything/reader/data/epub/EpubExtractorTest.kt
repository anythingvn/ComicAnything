package com.comicanything.reader.data.epub

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildEpub(entries: Map<String, String>): File {
        val epubFile = tempFolder.newFile("test-${System.nanoTime()}.epub")
        ZipOutputStream(epubFile.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return epubFile
    }

    private val containerXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
        </container>
    """.trimIndent()

    private val opfXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest>
                <item id="chap1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="chap2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine>
                <itemref idref="chap1"/>
                <itemref idref="chap2"/>
            </spine>
        </package>
    """.trimIndent()

    @Test
    fun `extracts and combines chapters in spine order`() = runTest {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfXml,
                "OEBPS/chapter1.xhtml" to "<html><body><p>Chapter One</p></body></html>",
                "OEBPS/chapter2.xhtml" to "<html><body><p>Chapter Two</p></body></html>"
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertTrue(book != null)
        val combined = book!!.combinedHtmlFile.readText()
        assertTrue(combined.indexOf("Chapter One") < combined.indexOf("Chapter Two"))
        assertTrue(combined.contains("epub-chapter-0"))
        assertTrue(combined.contains("epub-chapter-1"))
    }

    @Test
    fun `combined file lives alongside the chapters so relative links still resolve`() = runTest {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfXml,
                "OEBPS/chapter1.xhtml" to "<html><body><p>One</p></body></html>",
                "OEBPS/chapter2.xhtml" to "<html><body><p>Two</p></body></html>"
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertEquals(File(extractionDir, "OEBPS"), book!!.combinedHtmlFile.parentFile)
    }

    @Test
    fun `returns null when container xml is missing`() = runTest {
        val epub = buildEpub(mapOf("OEBPS/content.opf" to opfXml))
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertNull(book)
    }

    @Test
    fun `returns null when the spine is empty`() = runTest {
        val emptyOpf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <manifest/>
                <spine/>
            </package>
        """.trimIndent()
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to emptyOpf
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertNull(book)
    }

    @Test
    fun `returns null for a corrupt non-zip file`() = runTest {
        val notAZip = tempFolder.newFile("not-a-zip-${System.nanoTime()}.epub")
        notAZip.writeText("this is not a zip file")
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(notAZip, extractionDir)

        assertNull(book)
    }

    @Test
    fun `merges duplicate stylesheet references from multiple chapters into one`() = runTest {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfXml,
                "OEBPS/chapter1.xhtml" to """<html><head><link rel="stylesheet" href="style.css"/></head><body><p>One</p></body></html>""",
                "OEBPS/chapter2.xhtml" to """<html><head><link rel="stylesheet" href="style.css"/></head><body><p>Two</p></body></html>"""
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        val combined = book!!.combinedHtmlFile.readText()
        val occurrences = Regex("href=\"style.css\"").findAll(combined).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `re-extracting the same directory clears any prior extraction first`() = runTest {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfXml,
                "OEBPS/chapter1.xhtml" to "<html><body><p>One</p></body></html>",
                "OEBPS/chapter2.xhtml" to "<html><body><p>Two</p></body></html>"
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")
        File(extractionDir, "stale-leftover-file.txt").writeText("should be gone after re-extraction")

        val book = extractEpub(epub, extractionDir)

        assertTrue(book != null)
        assertTrue(!File(extractionDir, "stale-leftover-file.txt").exists())
    }

    @Test
    fun `rejects zip entries with path traversal attempts`() = runTest {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml,
                "OEBPS/content.opf" to opfXml,
                "OEBPS/chapter1.xhtml" to "<html><body><p>One</p></body></html>",
                "OEBPS/chapter2.xhtml" to "<html><body><p>Two</p></body></html>",
                "../evil.txt" to "malicious content"
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertTrue(book != null)
        val evilFile = File(extractionDir.parentFile, "evil.txt")
        assertTrue(!evilFile.exists())
    }

    @Test
    fun `rejects XML with DOCTYPE declarations containing external entities`() = runTest {
        val maliciousContainer = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE container [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to maliciousContainer,
                "OEBPS/content.opf" to opfXml
            )
        )
        val extractionDir = tempFolder.newFolder("extract-${System.nanoTime()}")

        val book = extractEpub(epub, extractionDir)

        assertNull(book)
    }
}
