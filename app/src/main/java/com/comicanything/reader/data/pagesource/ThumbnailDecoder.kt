package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private const val THUMBNAIL_TARGET_WIDTH_PX = 240

suspend fun decodeThumbnail(comic: ComicItem): Bitmap? {
    if (comic.source != ComicSource.LOCAL) return null
    return when (comic.format) {
        ComicFormat.PDF -> decodePdfThumbnail(File(comic.pathOrUrl))
        ComicFormat.CBZ -> decodeCbzThumbnail(File(comic.pathOrUrl))
        else -> null
    }
}

private suspend fun decodePdfThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@withContext null
                renderer.openPage(0).use { page ->
                    val scale = THUMBNAIL_TARGET_WIDTH_PX.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(
                        THUMBNAIL_TARGET_WIDTH_PX,
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

private suspend fun decodeCbzThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
    try {
        ZipFile(file).use { zipFile ->
            val pageEntries = listImagePagesSorted(zipFile)
            if (pageEntries.isEmpty()) return@withContext null
            val entry = pageEntries[0]
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(boundsOptions.outWidth, THUMBNAIL_TARGET_WIDTH_PX)
            }
            val sampledBitmap = zipFile.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null
            if (sampledBitmap.width <= THUMBNAIL_TARGET_WIDTH_PX) {
                sampledBitmap
            } else {
                val scale = THUMBNAIL_TARGET_WIDTH_PX.toFloat() / sampledBitmap.width
                val scaledHeight = (sampledBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaledBitmap = Bitmap.createScaledBitmap(sampledBitmap, THUMBNAIL_TARGET_WIDTH_PX, scaledHeight, true)
                if (scaledBitmap !== sampledBitmap) sampledBitmap.recycle()
                scaledBitmap
            }
        }
    } catch (e: Exception) {
        null
    }
}

internal fun calculateThumbnailSampleSize(actualWidth: Int, targetWidth: Int): Int {
    var sampleSize = 1
    if (actualWidth > targetWidth) {
        val halfWidth = actualWidth / 2
        while (halfWidth / sampleSize >= targetWidth) {
            sampleSize *= 2
        }
    }
    return sampleSize
}
