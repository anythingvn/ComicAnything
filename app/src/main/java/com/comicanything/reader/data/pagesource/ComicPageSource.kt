package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
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

fun createPageSource(comic: ComicItem, cbrCacheRoot: () -> File, file: File): ComicPageSource {
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(file)
        ComicFormat.CBZ -> CbzPageSource(file)
        ComicFormat.CBR -> CbrPageSource(file, File(cbrCacheRoot(), comic.id))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
