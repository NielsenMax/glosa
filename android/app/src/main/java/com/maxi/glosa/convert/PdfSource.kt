package com.maxi.glosa.convert

import java.io.Closeable

/** A rectangle on a page, in points from the page's top-left corner. */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/** One character as the PDF draws it; `size` is the font size in points after every transform. */
data class Glyph(
    val text: String,
    val x: Float,
    val baseline: Float,
    val width: Float,
    val size: Float,
    val isBold: Boolean,
    val isItalic: Boolean,
)

class PageText(val width: Float, val height: Float, val glyphs: List<Glyph>)

/** A bookmark of the PDF; `depth` 0 is the top level. */
data class OutlineEntry(val title: String, val pageIndex: Int, val depth: Int)

data class PdfInfo(val title: String?, val author: String?, val language: String?, val pageCount: Int)

/** ARGB pixels, row by row. */
class Raster(val width: Int, val height: Int, val pixels: IntArray)

/** What the converter reads from a PDF. Pages are 0-based and not thread-safe: one page at a time. */
interface PdfSource : Closeable {
    val info: PdfInfo
    val outline: List<OutlineEntry>

    fun text(pageIndex: Int): PageText

    /** The page drawn at [scale] pixels per point, clipped to [area]. */
    fun render(pageIndex: Int, area: Box, scale: Float): Raster

    fun encodeJpeg(raster: Raster, quality: Int): ByteArray
}
