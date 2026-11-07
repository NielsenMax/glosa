package com.maxi.glosa.convert

import java.io.OutputStream
import java.time.Instant

class ConversionException(message: String) : Exception(message)

/**
 * Converts a PDF with a text layer into a reflowable EPUB. Two passes: the first reads the text
 * of every page, since running heads, the body size and the hyphenation vocabulary need the
 * whole book; the second lays out each page and crops its figures.
 */
class PdfToEpub(private val source: PdfSource) {
    /** [onProgress] receives the share done, 0 to 1; it may throw to cancel the conversion. */
    fun convert(identifier: String, title: String, author: String?, output: OutputStream, onProgress: (Float) -> Unit) {
        val pageCount = source.info.pageCount
        if (pageCount == 0) throw ConversionException("el PDF no tiene páginas")
        val pages = (0 until pageCount).map { index ->
            val text = source.text(index)
            onProgress(READING_SHARE * (index + 1) / pageCount)
            PageLines(index, text.width, text.height, LineBuilder.lines(text.glyphs))
        }
        val printed = pages.sumOf { page -> page.lines.sumOf { it.text.length } }
        if (printed < MIN_CHARACTERS_PER_PAGE * pageCount) {
            throw ConversionException("el PDF casi no tiene texto: parece un escaneo sin reconocimiento de caracteres (OCR)")
        }

        val stats = BookStats.of(pages)
        val cleaned = RunningHeads.strip(pages, stats).map(Misreadings(stats.vocabulary)::fix)
        EpubWriter(output).use { writer ->
            val assembler = BookAssembler(stats) { pageIndex, box ->
                figure(pages[pageIndex], box)?.let { jpeg ->
                    "img/p%04d-%d.jpg".format(pageIndex + 1, box.y0.toInt()).also { writer.addImage(it, jpeg) }
                }
            }
            val layout = PageLayout(stats)
            for (page in cleaned) {
                val ink = { InkMap(source.render(page.index, Box(0f, 0f, page.width, page.height), INK_SCALE), INK_SCALE) }
                assembler.addPage(page.index, layout.layout(page, pages[page.index].lines, ink))
                onProgress(READING_SHARE + (1 - READING_SHARE) * (page.index + 1) / pageCount)
            }
            val metadata = EpubMetadata(
                identifier = identifier,
                title = title,
                author = author,
                language = source.info.language ?: stats.language ?: "und",
                modified = Instant.now(),
            )
            writer.finish(Sectioner.split(assembler.finish(), source.outline), metadata, cover(pages.first()))
        }
    }

    private fun figure(page: PageLines, box: Box): ByteArray? {
        val clipped = Box(box.x0.coerceAtLeast(0f), box.y0.coerceAtLeast(0f), box.x1.coerceAtMost(page.width), box.y1.coerceAtMost(page.height))
        if (clipped.width < MIN_FIGURE_SIDE || clipped.height < MIN_FIGURE_SIDE) return null
        val trimmed = trimWhitespace(source.render(page.index, clipped, FIGURE_SCALE)) ?: return null
        return source.encodeJpeg(trimmed, JPEG_QUALITY)
    }

    private fun cover(first: PageLines): ByteArray {
        val scale = COVER_WIDTH / first.width
        return source.encodeJpeg(source.render(0, Box(0f, 0f, first.width, first.height), scale), JPEG_QUALITY)
    }

    private companion object {
        const val READING_SHARE = 0.4f

        /** Even a page of mostly figures carries a caption; scans without OCR carry nothing. */
        const val MIN_CHARACTERS_PER_PAGE = 80

        /** Pixels per point: enough to see a figure's ink, and quick to scan. */
        const val INK_SCALE = 1.5f
        const val FIGURE_SCALE = 2f
        const val COVER_WIDTH = 600f
        const val JPEG_QUALITY = 80
        const val MIN_FIGURE_SIDE = 16f
    }
}

/** The raster cut down to its ink plus a small border, or null when nothing on it is printed. */
internal fun trimWhitespace(raster: Raster): Raster? {
    var top = -1
    var bottom = -1
    var left = raster.width
    var right = -1
    for (y in 0 until raster.height) {
        val offset = y * raster.width
        for (x in 0 until raster.width) {
            if (InkMap.luminance(raster.pixels[offset + x]) < TRIM_LIGHTNESS) {
                if (top < 0) top = y
                bottom = y
                if (x < left) left = x
                if (x > right) right = x
            }
        }
    }
    if (top < 0 || right - left < MIN_INK_PIXELS || bottom - top < MIN_INK_PIXELS) return null
    val x0 = (left - TRIM_BORDER).coerceAtLeast(0)
    val y0 = (top - TRIM_BORDER).coerceAtLeast(0)
    val x1 = (right + TRIM_BORDER + 1).coerceAtMost(raster.width)
    val y1 = (bottom + TRIM_BORDER + 1).coerceAtMost(raster.height)
    val pixels = IntArray((x1 - x0) * (y1 - y0))
    for (y in y0 until y1) System.arraycopy(raster.pixels, y * raster.width + x0, pixels, (y - y0) * (x1 - x0), x1 - x0)
    return Raster(x1 - x0, y1 - y0, pixels)
}

/** Lighter than body text: faint grid lines and tints still count as part of a figure. */
private const val TRIM_LIGHTNESS = 200
private const val TRIM_BORDER = 8
private const val MIN_INK_PIXELS = 12
