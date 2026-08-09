package com.comicanything.reader.data.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubBook(
    val extractedDir: File,
    val combinedHtmlFile: File
)

suspend fun extractEpub(epubFile: File, extractionDir: File): EpubBook? = withContext(Dispatchers.IO) {
    try {
        if (extractionDir.exists()) extractionDir.deleteRecursively()
        extractionDir.mkdirs()

        ZipFile(epubFile).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val outFile = File(extractionDir, entry.name)
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val containerFile = File(extractionDir, "META-INF/container.xml")
        if (!containerFile.exists()) return@withContext null
        val opfPath = parseContainerForOpfPath(containerFile) ?: return@withContext null
        val opfFile = File(extractionDir, opfPath)
        if (!opfFile.exists()) return@withContext null
        val opfDir = opfFile.parentFile ?: extractionDir

        val chapterFiles = parseOpfForSpineFiles(opfFile, opfDir)
        if (chapterFiles.isEmpty()) return@withContext null

        val combinedFile = File(opfDir, "__combined.xhtml")
        writeCombinedDocument(chapterFiles, combinedFile)

        EpubBook(extractedDir = extractionDir, combinedHtmlFile = combinedFile)
    } catch (e: Exception) {
        null
    }
}

private fun newDocument(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    return factory.newDocumentBuilder().parse(file)
}

private fun parseContainerForOpfPath(containerFile: File): String? {
    val doc = newDocument(containerFile)
    val rootFiles = doc.getElementsByTagNameNS("*", "rootfile")
    if (rootFiles.length == 0) return null
    val element = rootFiles.item(0) as Element
    return element.getAttribute("full-path").takeIf { it.isNotBlank() }
}

private fun parseOpfForSpineFiles(opfFile: File, opfDir: File): List<File> {
    val doc = newDocument(opfFile)

    val manifest = mutableMapOf<String, String>()
    val items = doc.getElementsByTagNameNS("*", "item")
    for (i in 0 until items.length) {
        val item = items.item(i) as Element
        val id = item.getAttribute("id")
        val href = item.getAttribute("href")
        if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
    }

    val itemRefs = doc.getElementsByTagNameNS("*", "itemref")
    val spineHrefs = mutableListOf<String>()
    for (i in 0 until itemRefs.length) {
        val itemRef = itemRefs.item(i) as Element
        val idRef = itemRef.getAttribute("idref")
        manifest[idRef]?.let { spineHrefs.add(it) }
    }

    return spineHrefs.map { File(opfDir, it) }.filter { it.exists() }
}

private fun writeCombinedDocument(chapterFiles: List<File>, outputFile: File) {
    val headExtras = StringBuilder()
    val bodyContent = StringBuilder()
    val linkPattern = Regex("<link[^>]*rel=[\"']stylesheet[\"'][^>]*/?>", RegexOption.IGNORE_CASE)
    val bodyPattern = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    chapterFiles.forEachIndexed { index, chapterFile ->
        val text = chapterFile.readText()

        linkPattern.findAll(text).forEach { match ->
            if (!headExtras.contains(match.value)) headExtras.append(match.value).append("\n")
        }

        val innerBody = bodyPattern.find(text)?.groupValues?.get(1) ?: text
        bodyContent.append("<div id=\"epub-chapter-$index\">\n").append(innerBody).append("\n</div>\n")
    }

    val combinedHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            $headExtras
            <style id="reader-theme"></style>
        </head>
        <body>
        $bodyContent
        </body>
        </html>
    """.trimIndent()

    outputFile.writeText(combinedHtml)
}
