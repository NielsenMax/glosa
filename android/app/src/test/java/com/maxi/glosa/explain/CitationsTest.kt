package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationsTest {
    private val book = Finding(Source(SourceKind.Book, "14. Bid/Ask Spreads", locatorJson = "{}"), "…the spread compensates dealers…")
    private val wikipedia = Finding(Source(SourceKind.Wikipedia, "Market maker", url = "https://en.wikipedia.org/wiki/Market_maker"), "A market maker…")
    private val web = Finding(Source(SourceKind.Web, "Bid-ask spread", url = "https://example.org"), "The bid-ask spread…")

    @Test
    fun `renumbers citations in the order the answer first uses them`() {
        val cited = Citations.resolve("Es lo que cobra el dealer [3]. Lo explica el autor [1, 3].", listOf(book, wikipedia, web), consulted = emptyList())

        assertEquals("Es lo que cobra el dealer [1]. Lo explica el autor [2, 1].", cited.answer)
        assertEquals(listOf(web.source, book.source), cited.sources)
    }

    @Test
    fun `leaves brackets that do not point at a finding as they are`() {
        val cited = Citations.resolve("Según el informe [2001], el spread cayó [1].", listOf(wikipedia), consulted = emptyList())

        assertEquals("Según el informe [2001], el spread cayó [1].", cited.answer)
        assertEquals(listOf(wikipedia.source), cited.sources)
    }

    @Test
    fun `lists what was looked up this turn when the answer cites nothing`() {
        val cited = Citations.resolve("Es la diferencia entre compra y venta.", listOf(book, wikipedia), consulted = listOf(wikipedia))

        assertEquals("Es la diferencia entre compra y venta.", cited.answer)
        assertEquals(listOf(wikipedia.source), cited.sources)
    }
}
