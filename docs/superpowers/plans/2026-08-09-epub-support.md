# EPUB Reading Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local `.epub` files actually readable — extract and parse the EPUB, render it as one continuously-scrollable WebView, track/persist scroll-based progress, and apply the existing color-filter modes as a real CSS theme — per [docs/superpowers/specs/2026-08-09-epub-support-design.md](../specs/2026-08-09-epub-support-design.md).

**Architecture:** A new `com.comicanything.reader.data.epub` package does pure extraction/parsing (zip extract, `container.xml`/OPF DOM parsing via the JDK's `javax.xml.parsers`, combined-document generation) with no Android-framework dependency, so it's fully real-object unit-testable. `ReaderViewModel` gains an EPUB-specific open/close path parallel to (not replacing) the existing PDF/CBZ path, using the exact same injectable-suspend-function testability pattern already established for `thumbnailDecoder`. A new `EpubReaderScreen` composable (not a new `ReadingMode` inside the existing `ReaderScreen`) embeds a single `WebView` via `AndroidView`, using `androidx.webkit`'s `WebViewAssetLoader`/`WebViewClientCompat` to serve the extracted files, native `WebView.contentHeight`/scroll-listener APIs (no JS) for progress tracking, and `evaluateJavascript` only for the color-theme swap.

**Tech Stack:** Kotlin, Jetpack Compose, `javax.xml.parsers`/`org.w3c.dom` (JDK, no new dependency), `java.util.zip.ZipFile` (already used elsewhere), `androidx.webkit:webkit:1.16.0` (new dependency — verified current stable as of this plan's writing, `minSdk 24` requirement exactly matches this project's existing floor).

## Global Constraints

- `androidx.webkit:webkit:1.16.0` is the exact version to add — confirmed current stable, confirmed `minSdk 24` compatible (this project's `minSdk` is already 24, no floor change needed).
- All EPUB XML parsing (`container.xml`, the OPF) must use `javax.xml.parsers.DocumentBuilderFactory`/`org.w3c.dom`, NOT any `android.util.Xml`-based API — this is what makes the parsing logic real-object unit-testable on plain JVM (no Robolectric configured in this project, confirmed during the cover-thumbnails sub-project).
- `DocumentBuilderFactory` instances used for parsing must set `isNamespaceAware = true` and element lookups must use `getElementsByTagNameNS("*", <localName>)` (namespace-agnostic wildcard), not `getElementsByTagName(<localName>)` — real-world EPUB OPF/container files commonly declare a default XML namespace without a prefix, and a namespace-unaware lookup silently returns zero matches against such files.
- MOBI support and any table-of-contents/chapter-jump UI are explicitly out of scope for this plan (see the design spec's Scope section).
- No mocking library is used in this project — tests use real objects only.
- Existing package root: `com.comicanything.reader`.

---

### Task 1: EPUB extraction & parsing (`EpubExtractor.kt`)

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/epub/EpubExtractor.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/epub/EpubExtractorTest.kt`

**Interfaces:**
- Produces: `data class EpubBook(val extractedDir: File, val combinedHtmlFile: File)` and `suspend fun extractEpub(epubFile: File, extractionDir: File): EpubBook?` — consumed by Task 2's `ReaderViewModel` via an injectable `epubExtractor: suspend (File, File) -> EpubBook?` constructor parameter.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/epub/EpubExtractorTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.epub.EpubExtractorTest" --no-daemon
```

Expected: FAIL — `EpubExtractor.kt` doesn't exist yet, so this won't compile.

- [ ] **Step 3: Implement `EpubExtractor.kt`**

Create `app/src/main/java/com/comicanything/reader/data/epub/EpubExtractor.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.epub.EpubExtractorTest" --no-daemon
```

Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/epub/EpubExtractor.kt app/src/test/java/com/comicanything/reader/data/epub/EpubExtractorTest.kt
git commit -m "feat: add EPUB extraction and container/OPF parsing"
```

---

### Task 2: `ReaderViewModel` — EPUB open/close path + scroll-progress persistence

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `suspend fun extractEpub(epubFile: File, extractionDir: File): EpubBook?` (Task 1), via an injectable constructor parameter defaulting to it.
- Produces: `ReaderUiState.epubBook: EpubBook?` and `fun ReaderViewModel.setEpubScrollProgress(percentage: Float)` — consumed by Task 3's `EpubReaderScreen`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, inside the `ReaderViewModelTest` class (add these as new `@Test` methods; do not modify any existing test). These need two new imports at the top of the file: `com.comicanything.reader.data.epub.EpubBook` and `java.io.File` (the latter is already imported — confirm before adding a duplicate):

```kotlin
    @Test
    fun `opening an EPUB comic uses the injected extractor and populates epubBook`() = runTest {
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.xhtml").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook }
        )
        val comic = ComicItem(
            id = "epub-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/path.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals(fakeBook, viewModel.uiState.value.epubBook)
        assertNull(viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `opening an EPUB comic sets an error when extraction fails`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> null }
        )
        val comic = ComicItem(
            id = "bad-epub",
            title = "Corrupt EPUB",
            pathOrUrl = "/fake/corrupt.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("Couldn't open this EPUB file", viewModel.uiState.value.pageLoadError)
        assertNull(viewModel.uiState.value.epubBook)
    }

    @Test
    fun `closeComic deletes the extracted EPUB directory`() = runTest {
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.xhtml").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook }
        )
        val comic = ComicItem(
            id = "epub-comic-2",
            title = "Test EPUB",
            pathOrUrl = "/fake/path2.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        viewModel.openComic(comic)
        advanceUntilIdle()
        assertTrue(extractedDir.exists())

        viewModel.closeComic()
        advanceUntilIdle()

        assertTrue(!extractedDir.exists())
        assertNull(viewModel.uiState.value.epubBook)
    }

    @Test
    fun `setEpubScrollProgress updates progressPercentage and persists after the debounce window`() = runTest {
        val progressDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "progress-${System.nanoTime()}.preferences_pb") }
        )
        val progressRepo = ReadingProgressRepository(progressDataStore, ioDispatcher = Dispatchers.Unconfined)
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.xhtml").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val comic = ComicItem(
            id = "scroll-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/scroll.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook }
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setEpubScrollProgress(0.42f)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0.42f, progressRepo.getAll()["scroll-comic"]?.progressPercentage)
    }

    @Test
    fun `setEpubScrollProgress clamps values outside 0 to 1`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val extractedDir = tempFolder.newFolder("epub-extracted-${System.nanoTime()}")
        val combinedFile = File(extractedDir, "__combined.xhtml").apply { writeText("<html></html>") }
        val fakeBook = EpubBook(extractedDir = extractedDir, combinedHtmlFile = combinedFile)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            epubExtractor = { _, _ -> fakeBook }
        )
        val comic = ComicItem(
            id = "clamp-comic",
            title = "Test EPUB",
            pathOrUrl = "/fake/clamp.epub",
            source = ComicSource.LOCAL,
            format = ComicFormat.EPUB
        )
        viewModel.openComic(comic)
        advanceUntilIdle()

        viewModel.setEpubScrollProgress(1.5f)

        assertEquals(1f, viewModel.uiState.value.activeComic?.progressPercentage)
    }
```

Note: unlike the thumbnail-decode tests (which can't construct a real `Bitmap` under this project's plain-JVM tests), `EpubBook` holds only plain `File` references, so the "successful open" case IS fully real-object testable here — a genuine testability advantage of this design over the thumbnail case.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `ReaderUiState.epubBook`, the `epubExtractor` constructor parameter, and `setEpubScrollProgress` don't exist yet.

- [ ] **Step 3: Implement the `ReaderViewModel` changes**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`:

Add these imports alongside the existing ones:

```kotlin
import com.comicanything.reader.data.epub.EpubBook
import com.comicanything.reader.data.epub.extractEpub
import com.comicanything.reader.data.model.ComicFormat
import java.io.File
```

Add `epubBook: EpubBook? = null` to `ReaderUiState` (currently lines 35-54), alongside the other nullable content fields:

```kotlin
data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveComics: List<ComicItem> = emptyList(),
    val activeComic: ComicItem? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 48,
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true,
    val isControlsVisible: Boolean = true,
    val isLoadingDrive: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isScanningLocal: Boolean = false,
    val currentPageBitmap: Bitmap? = null,
    val pageLoadError: String? = null,
    val isPageLoading: Boolean = false,
    val pageSourceGeneration: Int = 0,
    val isDriveConnected: Boolean = false,
    val driveAccountEmail: String? = null,
    val epubBook: EpubBook? = null
)
```

Add the new constructor parameter (currently lines 68-76), keeping every existing parameter exactly as-is:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub
) : AndroidViewModel(application) {
```

Add a new mutable field alongside the existing `pageCache`/`debounceJob` fields (currently lines 81-87):

```kotlin
    private var epubExtractedDir: File? = null
```

Change `openComic` (currently lines 177-224) to branch to a new EPUB path at the very top:

```kotlin
    fun openComic(comic: ComicItem) {
        if (comic.format == ComicFormat.EPUB) {
            openEpubComic(comic)
            return
        }
        val previousCache = pageCache
        pageCache = null
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            currentPage = comic.currentPage,
            totalPages = if (comic.totalPages > 0) comic.totalPages else 48,
            isControlsVisible = true,
            currentPageBitmap = null,
            pageLoadError = null,
            epubBook = null,
            pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
        )
        viewModelScope.launch {
            withContext(NonCancellable) {
                previousCache?.close()
            }
            val source = try {
                withContext(ioDispatcher) { createPageSource(comic) }
            } catch (e: UnsupportedFormatException) {
                _uiState.value = _uiState.value.copy(pageLoadError = "This format isn't supported yet")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Failed to open comic")
                return@launch
            }
            val cache = PageBitmapCache(source)
            val pageCount = cache.pageCount
            if (pageCount <= 0) {
                cache.close()
                _uiState.value = _uiState.value.copy(pageLoadError = "This comic has no readable pages")
                return@launch
            }
            pageCache = cache
            val resumePage = _uiState.value.currentPage.coerceIn(1, pageCount)
            _uiState.value = _uiState.value.copy(
                totalPages = pageCount,
                currentPage = resumePage,
                pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
            )
            comic.totalPages = pageCount
            comic.currentPage = resumePage
            comic.progressPercentage = resumePage.toFloat() / pageCount.toFloat()
            persistProgress(comic)
            loadPage(cache, resumePage)
        }
    }

    private fun openEpubComic(comic: ComicItem) {
        val previousExtractedDir = epubExtractedDir
        epubExtractedDir = null
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            isControlsVisible = true,
            pageLoadError = null,
            epubBook = null,
            currentPageBitmap = null,
            pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
        )
        viewModelScope.launch {
            withContext(NonCancellable) {
                previousExtractedDir?.let { runCatching { it.deleteRecursively() } }
            }
            val extractionDir = File(getApplication<Application>().cacheDir, "epub_temp/${comic.id}")
            val book = withContext(ioDispatcher) { epubExtractor(File(comic.pathOrUrl), extractionDir) }
            if (book == null) {
                _uiState.value = _uiState.value.copy(pageLoadError = "Couldn't open this EPUB file")
                return@launch
            }
            epubExtractedDir = book.extractedDir
            _uiState.value = _uiState.value.copy(epubBook = book)
        }
    }
```

Change `closeComic` (currently lines 226-233) to also clear `epubBook`:

```kotlin
    fun closeComic() {
        flushAndTeardown()
        _uiState.value = _uiState.value.copy(
            activeComic = null,
            currentPageBitmap = null,
            pageLoadError = null,
            epubBook = null
        )
    }
```

Change `flushAndTeardown` (currently lines 254-268) to also clean up the extracted EPUB directory:

```kotlin
    private fun flushAndTeardown() {
        val cacheToClose = pageCache
        val extractedDirToClean = epubExtractedDir
        val comicToFlush = _uiState.value.activeComic
        pageCache = null
        epubExtractedDir = null
        if (comicToFlush != null) {
            persistProgress(comicToFlush)
        } else {
            debounceJob?.cancel()
        }
        if (cacheToClose != null) {
            viewModelScope.launch(NonCancellable) {
                cacheToClose.close()
            }
        }
        if (extractedDirToClean != null) {
            viewModelScope.launch(NonCancellable) {
                runCatching { extractedDirToClean.deleteRecursively() }
            }
        }
    }
```

Add `setEpubScrollProgress` anywhere among the other public methods (e.g. right after `setCurrentPageIndicator`):

```kotlin
    fun setEpubScrollProgress(percentage: Float) {
        val clamped = percentage.coerceIn(0f, 1f)
        _uiState.value.activeComic?.let { comic ->
            comic.progressPercentage = clamped
            comic.lastReadTimestamp = System.currentTimeMillis()
            schedulePersist(comic)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS (all existing tests plus the 5 new ones).

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass, including Task 1's `EpubExtractorTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: add EPUB open/close path and scroll-progress persistence to ReaderViewModel"
```

---

### Task 3: `EpubReaderScreen` — WebView rendering, progress tracking, theming, dispatch wiring

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/ui/reader/EpubReaderScreen.kt`
- Modify: `app/src/main/java/com/comicanything/reader/MainActivity.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `ReaderUiState.epubBook: EpubBook?` and `fun ReaderViewModel.setEpubScrollProgress(percentage: Float)` (Task 2).
- Produces: `@Composable fun EpubReaderScreen(comic: ComicItem, viewModel: ReaderViewModel, onBack: () -> Unit)`, wired into `MainActivity`'s screen dispatch.

This task is UI/WebView wiring with no new unit-testable logic beyond Task 1/2's already-covered extraction/cache logic — verified by `assembleDebug` compiling cleanly plus Task 4's manual on-device pass (real `WebView` rendering, scroll, and JS injection require a real Android runtime and cannot be unit-tested in this project's setup).

- [ ] **Step 1: Add the `androidx.webkit` dependency**

In `app/build.gradle.kts`, add this line to the `dependencies` block, alongside the other `implementation(...)` entries (e.g. near the Coil line):

```kotlin
    implementation("androidx.webkit:webkit:1.16.0")
```

- [ ] **Step 2: Create `EpubReaderScreen.kt`**

Create `app/src/main/java/com/comicanything/reader/ui/reader/EpubReaderScreen.kt`:

```kotlin
package com.comicanything.reader.ui.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    comic: ComicItem,
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val bgColor = when (state.filterMode) {
        ColorFilterMode.SEPIA -> Color(0xFFFBF0D9)
        ColorFilterMode.NIGHT -> Color(0xFF1E1E1E)
        ColorFilterMode.AMOLED_BLACK -> Color.Black
        ColorFilterMode.HIGH_CONTRAST -> Color.Black
        ColorFilterMode.ORIGINAL -> Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        val book = state.epubBook
        when {
            book != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val extractedDir = book.extractedDir
                        val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler(
                                "/epub/",
                                WebViewAssetLoader.InternalStoragePathHandler(ctx, extractedDir)
                            )
                            .build()
                        val relativePath = book.combinedHtmlFile.relativeTo(extractedDir).path
                            .replace(File.separatorChar, '/')
                        val targetUrl = "https://appassets.androidplatform.net/epub/$relativePath"
                        val resumePercentage = comic.progressPercentage
                        val density = ctx.resources.displayMetrics.density

                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClientCompat() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    url: String
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(Uri.parse(url))

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    view.post {
                                        val contentHeightPx = (view.contentHeight * density).toInt()
                                        val maxScroll = contentHeightPx - view.height
                                        if (maxScroll > 0 && resumePercentage > 0f) {
                                            view.scrollTo(0, (maxScroll * resumePercentage).toInt())
                                        }
                                        view.evaluateJavascript(
                                            buildThemeJs(viewModel.uiState.value.filterMode),
                                            null
                                        )
                                    }
                                }
                            }
                            setOnScrollChangeListener { view, _, scrollY, _, _ ->
                                val webView = view as WebView
                                val contentHeightPx = (webView.contentHeight * density).toInt()
                                val maxScroll = contentHeightPx - webView.height
                                if (maxScroll > 0) {
                                    viewModel.setEpubScrollProgress(scrollY.toFloat() / maxScroll.toFloat())
                                }
                            }
                            loadUrl(targetUrl)
                        }
                    },
                    update = { webView ->
                        webView.evaluateJavascript(buildThemeJs(state.filterMode), null)
                    }
                )
            }
            state.pageLoadError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.pageLoadError!!,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        AnimatedVisibility(
            visible = state.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Text(
                        comic.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(comic) }) {
                        Icon(
                            imageVector = if (comic.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = if (comic.isFavorite) MaterialTheme.colorScheme.secondary else Color.White
                        )
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                )
            )
        }

        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = Color(0xFF1E1E1E)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Reader Quick Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Theme", color = Color.Gray, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        ColorFilterMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.filterMode == mode,
                                onClick = { viewModel.setFilterMode(mode) },
                                label = { Text(mode.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildThemeJs(mode: ColorFilterMode): String {
    val (bg, fg) = when (mode) {
        ColorFilterMode.SEPIA -> "#FBF0D9" to "#3b2f1e"
        ColorFilterMode.NIGHT -> "#1E1E1E" to "#dddddd"
        ColorFilterMode.AMOLED_BLACK -> "#000000" to "#ffffff"
        ColorFilterMode.HIGH_CONTRAST -> "#000000" to "#ffffff"
        ColorFilterMode.ORIGINAL -> "#ffffff" to "#000000"
    }
    return """
        (function() {
            var style = document.getElementById('reader-theme');
            if (style) { style.innerHTML = 'body { background-color: $bg !important; color: $fg !important; }'; }
        })();
    """.trimIndent()
}
```

- [ ] **Step 3: Wire `EpubReaderScreen` into `MainActivity`'s dispatch**

In `app/src/main/java/com/comicanything/reader/MainActivity.kt`, add these imports:

```kotlin
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.ui.reader.EpubReaderScreen
```

Change the current dispatch block:

```kotlin
                    if (state.activeComic != null) {
                        ReaderScreen(
                            comic = state.activeComic!!,
                            viewModel = viewModel,
                            onBack = { viewModel.closeComic() }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            homeScreenState = homeScreenState,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() },
                            onConnectDrive = { connectDrive() },
                            onDisconnectDrive = { disconnectDrive() }
                        )
                    }
```

to:

```kotlin
                    val activeComic = state.activeComic
                    when {
                        activeComic == null -> HomeScreen(
                            viewModel = viewModel,
                            homeScreenState = homeScreenState,
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() },
                            onConnectDrive = { connectDrive() },
                            onDisconnectDrive = { disconnectDrive() }
                        )
                        activeComic.format == ComicFormat.EPUB -> EpubReaderScreen(
                            comic = activeComic,
                            viewModel = viewModel,
                            onBack = { viewModel.closeComic() }
                        )
                        else -> ReaderScreen(
                            comic = activeComic,
                            viewModel = viewModel,
                            onBack = { viewModel.closeComic() }
                        )
                    }
```

- [ ] **Step 4: Build to confirm everything compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`. If the new `androidx.webkit` dependency fails to resolve, double check the exact version string in `app/build.gradle.kts` against what's actually available on Maven Central at implementation time — this plan's version was verified current as of this plan's writing, but re-verify before assuming a resolution failure is a typo rather than a since-yanked/renamed artifact.

- [ ] **Step 5: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass, including Task 1's and Task 2's new tests.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/comicanything/reader/ui/reader/EpubReaderScreen.kt app/src/main/java/com/comicanything/reader/MainActivity.kt
git commit -m "feat: render EPUBs in a WebView-based continuous-scroll reader screen"
```

---

### Task 4: End-to-end manual verification on the emulator

**Files:** none (verification only), plus `PROJECT_TASKS.md`

**Interfaces:** none — this task exercises Tasks 1-3 together as a user would.

- [ ] **Step 1: Build a real test EPUB file**

Construct a small, valid, multi-chapter EPUB using Python (matching how prior sub-projects built test PDFs/CBZs). Run this from any shell with Python 3 available:

```python
import zipfile

with zipfile.ZipFile("test_book.epub", "w") as z:
    z.writestr("mimetype", "application/epub+zip")
    z.writestr("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>""")
    z.writestr("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <manifest>
        <item id="style" href="style.css" media-type="text/css"/>
        <item id="chap1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
        <item id="chap2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
        <item id="chap3" href="chapter3.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="chap1"/>
        <itemref idref="chap2"/>
        <itemref idref="chap3"/>
    </spine>
</package>""")
    z.writestr("OEBPS/style.css", "body { font-family: serif; line-height: 1.6; } h1 { color: #333; }")
    for n, title in [(1, "The Beginning"), (2, "The Middle"), (3, "The End")]:
        paragraphs = "\n".join(f"<p>This is paragraph {p} of chapter {n}. " + ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 8) + "</p>" for p in range(1, 21))
        z.writestr(f"OEBPS/chapter{n}.xhtml", f"""<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter {n}</title><link rel="stylesheet" href="style.css"/></head>
<body><h1>Chapter {n}: {title}</h1>{paragraphs}</body>
</html>""")

print("Built test_book.epub")
```

Push it to the emulator's library folder:

```bash
export ANDROID_HOME="/c/Android/sdk"
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" push test_book.epub /sdcard/Download/test_book.epub
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test -dns-server 8.8.8.8,8.8.4.4`, then wait for `adb shell getprop sys.boot_completed` to report `1` before proceeding.)

- [ ] **Step 2: Install the freshly built APK and open the EPUB**

```bash
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

Tap `test_book.epub` in the library. Expected: a brief loading spinner, then real chapter text is visible ("Chapter 1: The Beginning" followed by paragraph text), not the old "This format isn't supported yet" card. Confirm via screenshot.

- [ ] **Step 3: Verify continuous scroll across chapters**

Scroll down through the book. Expected: content flows continuously from Chapter 1 into Chapter 2 into Chapter 3 with no separate per-chapter screens or page breaks, confirming the combined-document approach. Confirm the CSS from `style.css` is actually applied (serif font, styled `h1` — compare visually against what a totally unstyled HTML fallback would look like).

- [ ] **Step 4: Verify color-filter theming**

Open the quick-settings sheet (top-right icon) and tap through each of the 5 theme options (Original, Sepia, Night, AMOLED Black, High Contrast). Expected: background and text color visibly change to match each mode (matching the color mapping in the design spec), the change happens without a visible page reload/flash, and — critically — the current scroll position is preserved across each theme change (scroll partway down first, then verify theme switches don't jump back to the top).

- [ ] **Step 5: Verify scroll-progress persistence and resume**

Scroll to roughly the middle of the book, wait ~2 seconds (past the debounce window), then tap the back arrow to return to the library. Expected: the comic's "Continue Reading" carousel card (or list entry) shows a percentage-based progress indicator, not a fabricated "Page X/Y". Reopen the same EPUB. Expected: the WebView loads and then auto-scrolls to approximately the same position you left off at (not back to the top).

- [ ] **Step 6: Verify the favorite/bookmark toggle**

Tap the bookmark icon in the top bar. Expected: same visual toggle behavior as the existing paged-image reader (icon fill state changes); back out and reopen to confirm it persisted (this reuses the exact same `toggleFavorite`/persistence mechanism already verified in Epic 3, so this is a quick confirmation, not new-mechanism testing).

- [ ] **Step 7: Verify graceful failure on a corrupt/invalid EPUB**

Push a file with a `.epub` extension that is NOT actually a valid zip (e.g. `echo "not a real epub" > fake.epub`, then `adb push fake.epub /sdcard/Download/fake.epub`), rescan/relaunch, and tap it. Expected: the same generic error card pattern as the other formats' failure paths ("Couldn't open this EPUB file"), no crash, back button still returns to the library normally.

- [ ] **Step 8: Regression check**

Confirm nothing else broke: opening a PDF and a CBZ still work exactly as before (paged-image `ReaderScreen`, unaffected by this plan's changes); the library search/format-filter/grid-list-toggle from Epic 7 still work, including that `EPUB` is now a real, useful filter option; cover thumbnails still render for PDF/CBZ (EPUB still correctly shows its static fallback icon, since cover-thumbnail decoding was explicitly not extended to EPUB by this plan); the Google Drive tab is unaffected. No `FATAL EXCEPTION`/`AndroidRuntime`/`OutOfMemoryError`/ANR entries in a full-session `adb logcat` review covering every step above.

- [ ] **Step 9: Update PROJECT_TASKS.md**

In [PROJECT_TASKS.md](../../../PROJECT_TASKS.md), under `## Epic 5 — EPUB & MOBI Support`, check off "Choose and integrate an EPUB parsing/rendering approach" and "Build a reflowable text reader screen distinct from the paged-image `ReaderScreen`". Leave "Decide MOBI scope" unchecked (explicitly deferred to a separate follow-up brainstorm per this session's scope-split decision). Add a verification note in the same style/density as the other epics' existing notes, summarizing what was tested and confirmed on `comicanything_test`. Update the epic's status marker from `⬜` to `🟨` (partial — MOBI still undecided).

- [ ] **Step 10: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark EPUB reading support (Epic 5, sub-project 1) complete"
```
