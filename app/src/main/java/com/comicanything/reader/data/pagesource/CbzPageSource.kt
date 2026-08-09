package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

fun listImagePagesSorted(zipFile: ZipFile): List<ZipEntry> {
    return zipFile.entries().asSequence()
        .filter { entry ->
            !entry.isDirectory &&
                !entry.name.startsWith("__MACOSX/") &&
                !entry.name.substringAfterLast('/').startsWith(".") &&
                entry.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }
        .sortedWith(compareBy(naturalOrderComparator()) { it.name })
        .toList()
}

fun naturalOrderComparator(): Comparator<String> = Comparator { a, b ->
    val ax = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val bx = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ax.size, bx.size)) {
        val x = ax[i]
        val y = bx[i]
        val cmp = if (x.first().isDigit() && y.first().isDigit()) {
            x.toLong().compareTo(y.toLong())
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return@Comparator cmp
    }
    ax.size.compareTo(bx.size)
}

class CbzPageSource(file: File) : ComicPageSource {
    private val zipFile = ZipFile(file)
    private val pageEntries: List<ZipEntry> = listImagePagesSorted(zipFile)

    override val pageCount: Int get() = pageEntries.size

    override suspend fun getPage(page: Int): Bitmap = withContext(Dispatchers.IO) {
        try {
            val entry = pageEntries[page - 1]
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(boundsOptions.outWidth, TARGET_WIDTH_PX)
            }
            zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
                    ?: throw IllegalStateException("decodeStream returned null")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PageDecodeException(page, e)
        } catch (e: Exception) {
            throw PageDecodeException(page, e)
        }
    }

    override fun close() {
        zipFile.close()
    }

    companion object {
        private const val TARGET_WIDTH_PX = 1080
    }
}
