package com.maxi.glosa.convert

/**
 * A PDF made in the test: pages of text lines placed like a typeset book, plus dark boxes that
 * stand for drawings. Text is drawn glyph by glyph with explicit spaces, as most PDFs do.
 */
class FakePdf(
    private val pages: List<FakePage>,
    override val outline: List<OutlineEntry> = emptyList(),
    language: String? = null,
) : PdfSource {
    override val info = PdfInfo(title = null, author = null, language = language, pageCount = pages.size)

    override fun text(pageIndex: Int): PageText = pages[pageIndex].let { PageText(it.width, it.height, it.glyphs) }

    override fun render(pageIndex: Int, area: Box, scale: Float): Raster {
        val width = (area.width * scale).toInt().coerceAtLeast(1)
        val height = (area.height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(width * height) { WHITE }
        for (ink in pages[pageIndex].drawings) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = area.x0 + x / scale
                    val py = area.y0 + y / scale
                    if (px in ink.x0..ink.x1 && py in ink.y0..ink.y1) pixels[y * width + x] = BLACK
                }
            }
        }
        return Raster(width, height, pixels)
    }

    override fun encodeJpeg(raster: Raster, quality: Int): ByteArray = "JPEG ${raster.width}x${raster.height}".toByteArray()

    override fun close() = Unit

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

class FakePage(val width: Float, val height: Float, val glyphs: List<Glyph>, val drawings: List<Box>)

/** Lays out a page the way a book sets it: a justified column, lines one leading apart. */
class PageBuilder(private val width: Float = 400f, private val height: Float = 600f) {
    val left = 50f
    val right = 350f
    private val glyphs = mutableListOf<Glyph>()
    private val drawings = mutableListOf<Box>()

    /** A line of running text; every line but a paragraph's last reaches the right edge of the column. */
    fun line(text: String, baseline: Float, indent: Float = 0f, isLast: Boolean = false, size: Float = 10f) {
        val start = left + indent
        val advance = if (isLast) 0.5f * size else (right - start) / text.length
        text.forEachIndexed { i, char -> glyphs += Glyph(char.toString(), start + i * advance, baseline, advance, size, false, false) }
    }

    /** Text at its natural width, wherever it goes: a heading, a running head, a folio. */
    fun text(text: String, x: Float, baseline: Float, size: Float = 10f, isBold: Boolean = false) {
        text.forEachIndexed { i, char -> glyphs += Glyph(char.toString(), x + i * 0.5f * size, baseline, 0.5f * size, size, isBold, false) }
    }

    fun drawing(box: Box) {
        drawings += box
    }

    fun build() = FakePage(width, height, glyphs.toList(), drawings.toList())
}

fun page(build: PageBuilder.() -> Unit): FakePage = PageBuilder().apply(build).build()
