package com.maxi.glosa.convert

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class LineBuilderTest {
    private fun glyphs(text: String, x: Float, baseline: Float, size: Float = 10f, advance: Float = 5f) =
        text.mapIndexed { i, char -> Glyph(char.toString(), x + i * advance, baseline, advance, size, false, false) }

    @Test
    fun `rebuilds a line from glyphs drawn in any order`() {
        val lines = LineBuilder.lines(glyphs("Market makers quote", x = 72f, baseline = 100f).shuffled(Random(7)))

        assertEquals(listOf("Market makers quote"), lines.map { it.text })
    }

    @Test
    fun `puts back the space a PDF leaves out between words`() {
        val words = glyphs("bid", x = 72f, baseline = 100f) + glyphs("ask", x = 72f + 15f + 3f, baseline = 100f)

        assertEquals(listOf("bid ask"), LineBuilder.lines(words).map { it.text })
    }

    @Test
    fun `keeps a raised footnote call on the line it marks`() {
        val text = glyphs("the spread", x = 72f, baseline = 100f) + Glyph("1", 122f, 96f, 3f, 6f, false, false)

        val lines = LineBuilder.lines(text)

        assertEquals(listOf("the spread1"), lines.map { it.text })
        assertEquals(Run("1", isBold = false, isItalic = false, isRaised = true), lines.single().runs.last())
    }

    @Test
    fun `keeps a large title apart from the text that shares its baseline`() {
        val text = glyphs("familiar with the", x = 72f, baseline = 100f) + glyphs("Trading", x = 165f, baseline = 100f, size = 19f, advance = 9f)

        assertEquals(listOf("familiar with the", "Trading"), LineBuilder.lines(text).map { it.text }.sorted().reversed())
    }

    @Test
    fun `splits a row at a gap as wide as a column gutter`() {
        val row = glyphs("Bid", x = 72f, baseline = 100f) + glyphs("Ask", x = 72f + 15f + 12f, baseline = 100f)

        assertEquals(listOf("Bid", "Ask"), LineBuilder.lines(row).map { it.text })
    }
}
