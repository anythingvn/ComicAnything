package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import com.comicanything.reader.data.model.ComicFormat

interface ComicPageSource {
    val pageCount: Int
    suspend fun getPage(page: Int): Bitmap
    fun close()
}

class UnsupportedFormatException(format: ComicFormat) :
    Exception("Rendering not supported for format: $format")

class PageDecodeException(page: Int, cause: Throwable) :
    Exception("Failed to decode page $page", cause)
