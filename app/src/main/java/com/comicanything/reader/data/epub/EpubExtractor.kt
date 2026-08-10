package com.comicanything.reader.data.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
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
            val canonicalExtractionDir = extractionDir.canonicalPath
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val outFile = File(extractionDir, entry.name)
                val canonicalOutFile = outFile.canonicalPath
                if (!canonicalOutFile.startsWith(canonicalExtractionDir + File.separator)) {
                    return@forEach // Skip entries that try to escape the extraction directory
                }
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

        // Named ".html" (not ".xhtml") so androidx.webkit.WebViewAssetLoader's
        // InternalStoragePathHandler -- which relies on
        // URLConnection.guessContentTypeFromName()'s built-in extension table to set the
        // response Content-Type -- serves this as "text/html". That table has no entry for
        // ".xhtml", so WebView receives a response with no usable Content-Type and falls back
        // to rendering it as raw XML (Chrome's "This XML file does not appear to have any
        // style information..." viewer) instead of styled HTML5, even though the markup
        // content is fully correct. WebView/Chromium renders XHTML-flavored markup fine under
        // an HTML content-type, so renaming the file is sufficient; no markup change needed.
        val combinedFile = File(opfDir, "__combined.html")
        writeCombinedDocument(chapterFiles, opfDir, combinedFile)

        EpubBook(extractedDir = extractionDir, combinedHtmlFile = combinedFile)
    } catch (e: Exception) {
        null
    }
}

/**
 * Builds a [Document] guarded against XXE (XML External Entity) attacks.
 *
 * NOTE: We intentionally avoid `DocumentBuilderFactory.setFeature(...)` with
 * Xerces/SAX-specific feature URIs (e.g. "disallow-doctype-decl",
 * "external-general-entities"). Those are NOT supported by Android's built-in
 * `DocumentBuilderFactory` implementation (`org.apache.harmony.xml.parsers.
 * DocumentBuilderFactoryImpl`), which throws `ParserConfigurationException` for
 * unrecognized feature URIs. That mismatch previously broke EPUB parsing on
 * every real device while passing plain-JVM unit tests (whose default factory
 * is typically Xerces-based).
 *
 * Instead we rely only on standard, implementation-agnostic JAXP APIs:
 *  - A no-op [org.xml.sax.EntityResolver] on the builder that resolves every
 *    external entity (general entities and the external DTD subset) to an
 *    empty document, so external file/network content can never be fetched or
 *    substituted into the parsed document.
 *  - `isExpandEntityReferences = false`, a plain `DocumentBuilderFactory` bean
 *    property (not a vendor feature URI). Verified on-device to be supported by
 *    Android's Harmony-based implementation.
 *
 * NOTE ON `isXIncludeAware`: we deliberately do NOT call
 * `factory.isXIncludeAware = false` here, even though it looks like another
 * "portable, non-setFeature" hardening knob. On-device testing showed Android's
 * `DocumentBuilderFactoryImpl` throws `UnsupportedOperationException: This
 * parser does not support specification "Unknown" version "0.0"` from
 * `setXIncludeAware(false)` -- i.e. it doesn't support touching this property
 * *at all*, not even to set it to its own default. `isXIncludeAware` already
 * defaults to `false` (XInclude processing is opt-in, never implicit), so
 * leaving it untouched preserves the same protection without calling an
 * unsupported setter. This is exactly the class of assumption the previous
 * `setFeature(...)` bug taught us not to make without verifying on a real
 * device -- so it was verified here, and it failed the same way.
 */
private fun newDocument(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(file)
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

    return spineHrefs
        .map { URLDecoder.decode(it, "UTF-8") }
        .map { File(opfDir, it) }
        .filter { it.exists() }
}

/**
 * Combines all spine chapter files into a single HTML document that lives in [opfDir]
 * (alongside `content.opf`) so that top-level relative references from files that
 * already sat directly in [opfDir] keep working unchanged.
 *
 * Real-world EPUBs produced by Calibre/Sigil/publisher toolchains commonly nest spine
 * files under a subdirectory (e.g. `OEBPS/Text/chapter1.xhtml`) with shared resources
 * elsewhere (e.g. `OEBPS/Styles/style.css`, `OEBPS/Images/fig.png`), referenced from the
 * chapter via paths like `../Styles/style.css`. Once such a chapter's markup is copied
 * verbatim into the combined document living in [opfDir], that relative reference would
 * resolve against the wrong base directory and silently fail to load. To avoid that, every
 * relative `href=`/`src=` attribute value and CSS `url(...)` reference in each chapter's
 * raw text is rewritten here -- resolved relative to that chapter's own directory, then
 * re-expressed relative to [opfDir] -- before any `<link>`/`<style>`/body content is
 * extracted from it. Absolute URLs (`http(s)://`, `data:`, `mailto:`), fragment-only
 * references (`#anchor`), and root-relative paths (`/...`) are left untouched.
 */
private fun writeCombinedDocument(chapterFiles: List<File>, opfDir: File, outputFile: File) {
    val headExtras = StringBuilder()
    val bodyContent = StringBuilder()
    val linkPattern = Regex("<link[^>]*rel=[\"']stylesheet[\"'][^>]*/?>", RegexOption.IGNORE_CASE)
    val stylePattern = Regex("<style[^>]*>(.*?)</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val bodyPattern = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val hrefSrcPattern = Regex("""(href|src)(\s*=\s*)(["'])([^"']*)\3""", RegexOption.IGNORE_CASE)
    val cssUrlPattern = Regex("""url\((['"]?)([^'")]+)\1\)""", RegexOption.IGNORE_CASE)

    chapterFiles.forEachIndexed { index, chapterFile ->
        val rawText = chapterFile.readText()
        val chapterRelDir = chapterFile.parentFile
            ?.relativeTo(opfDir)
            ?.path
            ?.replace(File.separatorChar, '/')
            .orEmpty()

        val rewrittenHrefs = hrefSrcPattern.replace(rawText) { match ->
            val (attr, eq, quote, value) = match.destructured
            "$attr$eq$quote${resolveRelativeToOpf(chapterRelDir, value)}$quote"
        }
        val text = cssUrlPattern.replace(rewrittenHrefs) { match ->
            val quote = match.groupValues[1]
            val value = match.groupValues[2]
            "url($quote${resolveRelativeToOpf(chapterRelDir, value)}$quote)"
        }

        linkPattern.findAll(text).forEach { match ->
            if (!headExtras.contains(match.value)) headExtras.append(match.value).append("\n")
        }
        stylePattern.findAll(text).forEach { match ->
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

/**
 * Resolves [href] -- taken from a chapter file located at [chapterRelDir] relative to
 * [opfDir] -- into a path expressed relative to [opfDir] instead. Absolute URLs, fragment
 * references, and root-relative paths pass through unchanged.
 */
private fun resolveRelativeToOpf(chapterRelDir: String, href: String): String {
    if (href.isBlank() || href.startsWith("http://") || href.startsWith("https://") ||
        href.startsWith("data:") || href.startsWith("#") || href.startsWith("/") || href.startsWith("mailto:")
    ) {
        return href
    }
    val combined = if (chapterRelDir.isEmpty()) href else "$chapterRelDir/$href"
    val stack = mutableListOf<String>()
    for (part in combined.split("/")) {
        when (part) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(part)
        }
    }
    return stack.joinToString("/")
}
