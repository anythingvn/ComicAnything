package com.comicanything.reader.ui.home

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenFilterTest {

    private fun comic(id: String, title: String, format: ComicFormat) = ComicItem(
        id = id,
        title = title,
        pathOrUrl = "/fake/$id",
        source = ComicSource.LOCAL,
        format = format
    )

    private val batman = comic("1", "Batman: Year One", ComicFormat.CBZ)
    private val watchmen = comic("2", "Watchmen", ComicFormat.PDF)
    private val sandman = comic("3", "The Sandman", ComicFormat.EPUB)
    private val library = listOf(batman, watchmen, sandman)

    @Test
    fun `no query and no format filter returns everything unchanged`() {
        val result = library.filtered(query = "", formats = emptySet())

        assertEquals(library, result)
    }

    @Test
    fun `query matches by title substring case-insensitively`() {
        val result = library.filtered(query = "bat", formats = emptySet())

        assertEquals(listOf(batman), result)
    }

    @Test
    fun `query with different casing still matches`() {
        val result = library.filtered(query = "WATCHMEN", formats = emptySet())

        assertEquals(listOf(watchmen), result)
    }

    @Test
    fun `blank query is treated as no filter`() {
        val result = library.filtered(query = "   ", formats = emptySet())

        assertEquals(library, result)
    }

    @Test
    fun `format filter narrows to matching formats only`() {
        val result = library.filtered(query = "", formats = setOf(ComicFormat.PDF))

        assertEquals(listOf(watchmen), result)
    }

    @Test
    fun `multiple selected formats are combined with OR`() {
        val result = library.filtered(query = "", formats = setOf(ComicFormat.CBZ, ComicFormat.EPUB))

        assertEquals(listOf(batman, sandman), result)
    }

    @Test
    fun `query and format filter combine with AND`() {
        val result = library.filtered(query = "sandman", formats = setOf(ComicFormat.CBZ))

        assertEquals(emptyList<ComicItem>(), result)
    }

    @Test
    fun `no matches returns an empty list`() {
        val result = library.filtered(query = "nonexistent title", formats = emptySet())

        assertEquals(emptyList<ComicItem>(), result)
    }

    @Test
    fun `query with trailing whitespace still matches`() {
        val result = library.filtered(query = "bat ", formats = emptySet())

        assertEquals(listOf(batman), result)
    }
}
