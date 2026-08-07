package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfPageSource(file: File) : ComicPageSource {
    private val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fileDescriptor)
    private val mutex = Mutex()

    override val pageCount: Int get() = renderer.pageCount

    override suspend fun getPage(page: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                renderer.openPage(page - 1).use { pdfPage ->
                    val scale = TARGET_WIDTH_PX.toFloat() / pdfPage.width
                    val bitmap = Bitmap.createBitmap(
                        TARGET_WIDTH_PX,
                        (pdfPage.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PageDecodeException(page, e)
            }
        }
    }

    override fun close() {
        renderer.close()
        fileDescriptor.close()
    }

    companion object {
        private const val TARGET_WIDTH_PX = 1080
    }
}
