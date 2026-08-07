package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import java.io.File

interface ComicPageSource {
    val pageCount: Int
    suspend fun getPage(page: Int): Bitmap
    fun close()
}

class UnsupportedFormatException(format: ComicFormat) :
    Exception("Rendering not supported for format: $format")

class PageDecodeException(page: Int, cause: Throwable) :
    Exception("Failed to decode page $page", cause)

fun createPageSource(comic: ComicItem): ComicPageSource {
    if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(comic.format)
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(File(comic.pathOrUrl))
        ComicFormat.CBZ -> CbzPageSource(File(comic.pathOrUrl))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
