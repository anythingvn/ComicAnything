package com.comicanything.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DualPageSpreadReaderTest {

    @Test
    fun `page 1 always stands alone as the cover`() {
        assertEquals(1 to null, spreadPagesFor(currentPage = 1, totalPages = 10))
    }

    @Test
    fun `page 1 alone when the comic has only one page`() {
        assertEquals(1 to null, spreadPagesFor(currentPage = 1, totalPages = 1))
    }

    @Test
    fun `pages 2 and 3 pair together`() {
        assertEquals(2 to 3, spreadPagesFor(currentPage = 2, totalPages = 10))
        assertEquals(2 to 3, spreadPagesFor(currentPage = 3, totalPages = 10))
    }

    @Test
    fun `pages 4 and 5 pair together`() {
        assertEquals(4 to 5, spreadPagesFor(currentPage = 4, totalPages = 10))
        assertEquals(4 to 5, spreadPagesFor(currentPage = 5, totalPages = 10))
    }

    @Test
    fun `an odd-length comic's final page has no partner`() {
        assertEquals(6 to null, spreadPagesFor(currentPage = 6, totalPages = 6))
    }
}
