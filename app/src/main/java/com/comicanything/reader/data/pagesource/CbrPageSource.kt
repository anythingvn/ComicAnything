package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val CBR_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

internal fun listImagePageFilesSorted(dir: File): List<File> {
    return dir.walkTopDown()
        .filter { file ->
            file.isFile &&
                !file.path.contains("__MACOSX") &&
                !file.name.startsWith(".") &&
                file.extension.lowercase() in CBR_IMAGE_EXTENSIONS
        }
        .sortedWith(compareBy(naturalOrderComparator()) { it.toRelativeString(dir).replace(File.separatorChar, '/') })
        .toList()
}

class CbrPageSource(file: File, extractionDir: File) : ComicPageSource {
    private val extractionDirectory = extractionDir
    private val pageFiles: List<File>

    init {
        if (extractionDir.exists()) extractionDir.deleteRecursively()
        extractionDir.mkdirs()
        val canonicalExtractionDir = extractionDir.canonicalPath

        try {
            Archive(file).use { archive ->
                var header = archive.nextFileHeader()
                while (header != null) {
                    if (!header.isDirectory) {
                        val outFile = File(extractionDir, header.fileName)
                        val canonicalOutFile = outFile.canonicalPath
                        if (canonicalOutFile.startsWith(canonicalExtractionDir + File.separator)) {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> archive.extractFile(header, out) }
                        }
                        // else: entry's resolved path escapes the extraction directory (Zip Slip
                        // / path traversal) -- skip this one entry rather than aborting the whole
                        // extraction, matching EpubExtractor.kt's already-reviewed guard.
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: Throwable) {
            extractionDir.deleteRecursively()
            throw e
        }

        pageFiles = listImagePageFilesSorted(extractionDir)
    }

    override val pageCount: Int get() = pageFiles.size

    override suspend fun getPage(page: Int): Bitmap = withContext(Dispatchers.IO) {
        try {
            val pageFile = pageFiles[page - 1]
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pageFile.absolutePath, boundsOptions)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(boundsOptions.outWidth, TARGET_WIDTH_PX)
            }
            BitmapFactory.decodeFile(pageFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("decodeFile returned null")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PageDecodeException(page, e)
        } catch (e: Exception) {
            throw PageDecodeException(page, e)
        }
    }

    override fun close() {
        extractionDirectory.deleteRecursively()
    }

    companion object {
        private const val TARGET_WIDTH_PX = 1080
    }
}
