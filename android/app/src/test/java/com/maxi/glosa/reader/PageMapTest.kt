package com.maxi.glosa.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMapTest {
    /** Chapter 2 opens mid-page 3, so its document repeats page 3 at the top. */
    private val map = PageMap.of(
        listOf(
            "OEBPS/s001.xhtml#p1" to "1",
            "OEBPS/s001.xhtml#p2" to "2",
            "OEBPS/s001.xhtml#p3" to "3",
            "OEBPS/s002.xhtml#p3c" to "3",
            "OEBPS/s002.xhtml#p4" to "4",
            "OEBPS/s002.xhtml#p6" to "6",
        ),
    )

    @Test
    fun `finds the page shown partway through a document`() {
        assertEquals(2, map.pageAt("OEBPS/s001.xhtml", 0.5))
    }

    @Test
    fun `knows a document that opens mid-page starts on that page`() {
        assertEquals(3, map.pageAt("OEBPS/s002.xhtml", 0.1))
    }

    @Test
    fun `takes the end of a document to be its last page`() {
        assertEquals(6, map.pageAt("OEBPS/s002.xhtml", 1.0))
    }

    @Test
    fun `sends a page to where it begins, not to where a later chapter repeats it`() {
        assertEquals(PageMap.Place("OEBPS/s001.xhtml", "p3", 2.0 / 3), map.placeOf(3))
    }

    @Test
    fun `sends a page without text of its own to the page before it`() {
        assertEquals(PageMap.Place("OEBPS/s002.xhtml", "p4", 1.0 / 3), map.placeOf(5))
    }
}
