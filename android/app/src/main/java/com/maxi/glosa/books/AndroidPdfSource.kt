package com.maxi.glosa.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.maxi.glosa.convert.Box
import com.maxi.glosa.convert.Glyph
import com.maxi.glosa.convert.OutlineEntry
import com.maxi.glosa.convert.PageText
import com.maxi.glosa.convert.PdfInfo
import com.maxi.glosa.convert.PdfSource
import com.maxi.glosa.convert.Raster
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil

/** PdfBox reads the text layer; the platform's PDFium renderer draws figures, which it does faster and more faithfully. */
class AndroidPdfSource(context: Context, file: File) : PdfSource {
    private val document: PDDocument
    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer
    private val stripper: GlyphStripper

    init {
        // PdfBox's text classes load their glyph lists when first touched, from the assets this hands them.
        PDFBoxResourceLoader.init(context.applicationContext)
        stripper = GlyphStripper()
        document = PDDocument.load(file)
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
    }

    override val info: PdfInfo = PdfInfo(
        title = document.documentInformation?.title?.trim()?.ifEmpty { null },
        author = document.documentInformation?.author?.trim()?.ifEmpty { null },
        language = document.documentCatalog.language?.substringBefore('-')?.lowercase()?.ifEmpty { null },
        pageCount = document.numberOfPages,
    )

    override val outline: List<OutlineEntry> by lazy {
        val root = document.documentCatalog.documentOutline ?: return@lazy emptyList()
        buildList { walk(root, depth = 0) }
    }

    /** Bookmarks pointing nowhere, which broken PDFs carry, are left out of the index. */
    private fun MutableList<OutlineEntry>.walk(node: PDOutlineNode, depth: Int) {
        for (item in node.children()) {
            val page = try {
                item.findDestinationPage(document)
            } catch (e: IOException) {
                null
            }
            val index = page?.let { document.pages.indexOf(it) } ?: -1
            val title = item.title?.trim().orEmpty()
            if (index >= 0 && title.isNotEmpty()) add(OutlineEntry(title, index, depth))
            if (depth < MAX_OUTLINE_DEPTH) walk(item, depth + 1)
        }
    }

    override fun text(pageIndex: Int): PageText = stripper.read(document, pageIndex)

    override fun render(pageIndex: Int, area: Box, scale: Float): Raster {
        renderer.openPage(pageIndex).use { page ->
            val width = ceil(area.width * scale).toInt().coerceAtLeast(1)
            val height = ceil(area.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val transform = Matrix().apply {
                setTranslate(-area.x0, -area.y0)
                postScale(scale, scale)
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            return Raster(width, height, pixels)
        }
    }

    override fun encodeJpeg(raster: Raster, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    override fun close() {
        renderer.close()
        descriptor.close()
        document.close()
    }

    /** Takes PdfBox's characters once it has merged diacritics and dropped fake-bold duplicates, before it lays them out. */
    private class GlyphStripper : PDFTextStripper() {
        private val glyphs = mutableListOf<Glyph>()
        private val styles = HashMap<PDFont?, Pair<Boolean, Boolean>>()

        init {
            setSortByPosition(false)
            setSuppressDuplicateOverlappingText(true)
        }

        fun read(document: PDDocument, pageIndex: Int): PageText {
            glyphs.clear()
            setStartPage(pageIndex + 1)
            setEndPage(pageIndex + 1)
            getText(document)
            val page = document.getPage(pageIndex)
            val isTurned = page.rotation % 180 != 0
            val box = page.cropBox
            return PageText(
                width = if (isTurned) box.height else box.width,
                height = if (isTurned) box.width else box.height,
                glyphs = glyphs.toList(),
            )
        }

        override fun writePage() {
            for (article in charactersByArticle) {
                for (position in article) glyphOf(position)?.let(glyphs::add)
            }
        }

        /** Rotated text, like a chart's axis labels, does not belong to any line of the page. */
        private fun glyphOf(position: TextPosition): Glyph? {
            val text = position.unicode ?: return null
            if (position.dir != 0f) return null
            val (isBold, isItalic) = styles.getOrPut(position.font) { styleOf(position.font) }
            return Glyph(
                text = text,
                x = position.xDirAdj,
                baseline = position.yDirAdj,
                width = position.widthDirAdj,
                size = abs(position.yScale).takeIf { it > 0f } ?: position.fontSizeInPt.toFloat(),
                isBold = isBold,
                isItalic = isItalic,
            )
        }

        private fun styleOf(font: PDFont?): Pair<Boolean, Boolean> {
            val name = font?.name.orEmpty()
            val descriptor = font?.fontDescriptor
            val isBold = BOLD.containsMatchIn(name) || descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f
            val isItalic = ITALIC.containsMatchIn(name) || descriptor?.isItalic == true || (descriptor?.italicAngle ?: 0f) != 0f
            return isBold to isItalic
        }
    }

    private companion object {
        const val MAX_OUTLINE_DEPTH = 3
        val BOLD = Regex("bold|black|heavy|semibold|demi", RegexOption.IGNORE_CASE)
        val ITALIC = Regex("italic|oblique", RegexOption.IGNORE_CASE)
    }
}
