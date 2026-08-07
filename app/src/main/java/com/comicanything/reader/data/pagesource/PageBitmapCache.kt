package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PageBitmapCache(
    private val source: ComicPageSource,
    private val maxCachedPages: Int = 5
) {
    private val cache = object : LinkedHashMap<Int, Bitmap>(maxCachedPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) =
            size > maxCachedPages
    }
    private val mutex = Mutex()

    val pageCount: Int get() = source.pageCount

    suspend fun getPage(page: Int): Bitmap = mutex.withLock {
        cache[page] ?: source.getPage(page).also { cache[page] = it }
    }

    suspend fun prefetch(pages: List<Int>) {
        pages.filter { it in 1..pageCount && it !in cache }
            .forEach { page ->
                try {
                    getPage(page)
                } catch (e: PageDecodeException) {
                    // Prefetch failures are silent -- the page shows its
                    // error state if/when the user actually navigates to it.
                }
            }
    }

    fun close() = source.close()
}
