package com.maxi.glosa.convert

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningHeadsTest {
    private fun line(text: String, baseline: Float, size: Float = 10f) =
        TextLine(listOf(Run(text, isBold = false, isItalic = false)), x0 = 50f, x1 = 50f + 5f * text.length, baseline = baseline, size = size)

    private fun page(index: Int, vararg lines: TextLine) = PageLines(index, 400f, 600f, lines.toList())

    private val openings = listOf(
        "Dealers quote two prices at once",
        "Brokers arrange trades for clients",
        "Specialists keep the limit order book",
        "Arbitrageurs buy cheap and sell dear",
        "Value traders estimate what it is worth",
        "Bluffers try to fool the other traders",
    )

    private fun body(index: Int) = arrayOf(
        line(openings[index], 70f),
        line("and the page goes on from there.", 82f),
    )

    private fun strip(pages: List<PageLines>) = RunningHeads.strip(pages, BookStats.of(pages)).map { page -> page.lines.map { it.text } }

    @Test
    fun `drops a header repeated with its page number and the folios`() {
        val pages = (0 until 6).map { page(it, line("${it + 10} TRADING AND EXCHANGES", 30f, size = 8f), *body(it), line("${it + 10}", 570f, size = 8f)) }

        assertEquals((0 until 6).map { body(it).map(TextLine::text) }, strip(pages))
    }

    @Test
    fun `drops a header whose words change when it always stands in the same spot above the text`() {
        val pages = (0 until 6).map { page(it, line("Section ${'A' + it} of the chapter", 30f, size = 9f), *body(it)) }

        assertEquals((0 until 6).map { body(it).map(TextLine::text) }, strip(pages))
    }

    @Test
    fun `keeps the first line of text even though it starts every page at the same height`() {
        val pages = (0 until 6).map { page(it, *body(it)) }

        assertEquals((0 until 6).map { body(it).map(TextLine::text) }, strip(pages))
    }
}
