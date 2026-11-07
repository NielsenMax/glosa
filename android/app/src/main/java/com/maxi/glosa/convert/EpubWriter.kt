package com.maxi.glosa.convert

import java.io.Closeable
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubMetadata(val identifier: String, val title: String, val author: String?, val language: String, val modified: Instant)

/**
 * Writes an EPUB 3 as the conversion goes: images as they are cropped, the documents and the
 * package at the end, once the whole book is known.
 */
class EpubWriter(output: OutputStream) : Closeable {
    private val zip = ZipOutputStream(output)
    private val images = mutableListOf<String>()

    init {
        // The OCF container requires an uncompressed "mimetype" as the very first entry.
        stored("mimetype", "application/epub+zip".toByteArray())
        text("META-INF/container.xml", CONTAINER)
    }

    /** [name] is relative to the documents, like `img/p0012-1.jpg`. */
    fun addImage(name: String, jpeg: ByteArray) {
        stored("OEBPS/$name", jpeg)
        images += name
    }

    fun finish(book: SplitBook, metadata: EpubMetadata, cover: ByteArray?) {
        cover?.let { stored("OEBPS/cover.jpg", it) }
        text("OEBPS/style.css", CSS)
        for (section in book.sections) text("OEBPS/${section.file}", Xhtml.document(section, metadata))
        text("OEBPS/nav.xhtml", Xhtml.navigation(book, metadata))
        text("OEBPS/content.opf", packageDocument(book, metadata, hasCover = cover != null))
        zip.finish()
    }

    override fun close() = zip.close()

    private fun packageDocument(book: SplitBook, metadata: EpubMetadata, hasCover: Boolean): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">""")
        appendLine("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
        appendLine("""<dc:identifier id="bookid">${Xhtml.escape(metadata.identifier)}</dc:identifier>""")
        appendLine("""<dc:title>${Xhtml.escape(metadata.title)}</dc:title>""")
        metadata.author?.let { appendLine("""<dc:creator>${Xhtml.escape(it)}</dc:creator>""") }
        appendLine("""<dc:language>${Xhtml.escape(metadata.language)}</dc:language>""")
        appendLine("""<meta property="dcterms:modified">${metadata.modified.truncatedTo(ChronoUnit.SECONDS)}</meta>""")
        if (hasCover) appendLine("""<meta name="cover" content="cover"/>""")
        appendLine("</metadata>")
        appendLine("<manifest>")
        appendLine("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        appendLine("""<item id="css" href="style.css" media-type="text/css"/>""")
        if (hasCover) appendLine("""<item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""")
        book.sections.forEachIndexed { i, section ->
            appendLine("""<item id="d${i + 1}" href="${section.file}" media-type="application/xhtml+xml"/>""")
        }
        images.forEachIndexed { i, image -> appendLine("""<item id="i${i + 1}" href="$image" media-type="image/jpeg"/>""") }
        appendLine("</manifest>")
        appendLine("<spine>")
        book.sections.indices.forEach { appendLine("""<itemref idref="d${it + 1}"/>""") }
        appendLine("</spine>")
        appendLine("</package>")
    }

    private fun text(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    /** JPEGs are already compressed; storing them saves time on the phone. */
    private fun stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private companion object {
        const val CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        const val CSS = """body { font-family: serif; line-height: 1.45; margin: 0 0.4em; }
h1 { font-size: 1.5em; margin: 1.5em 0 1em; }
h2 { font-size: 1.25em; margin: 1.6em 0 0.6em; }
h3 { font-size: 1.1em; margin: 1.3em 0 0.5em; }
h4, h5, h6 { font-size: 1em; margin: 1.1em 0 0.4em; }
p { margin: 0; text-indent: 1.3em; text-align: justify; }
h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, h6 + p, .figure + p, .caption + p, aside + p { text-indent: 0; }
p.li { text-indent: -1.2em; margin-left: 1.2em; margin-top: 0.3em; }
p.caption { text-indent: 0; font-size: 0.85em; margin: 0.4em 0 1em; text-align: left; }
.figure { text-align: center; margin: 1em 0 0.5em; }
.figure img { max-width: 100%; }
aside.margin { font-size: 0.9em; border-left: 3px solid #888; padding: 0.3em 0 0.3em 0.8em; margin: 1.2em 0; }
aside.notes { font-size: 0.85em; border-top: 1px solid #888; padding-top: 0.3em; margin: 1em 0; }
aside p { text-indent: 0; margin: 0.2em 0; }
sup { font-size: 0.7em; line-height: 0; }
"""
    }
}

/** XHTML for the documents and the navigation; the reader parses it as XML, so it must be well formed. */
internal object Xhtml {
    private val SPACE_BEFORE = Regex(" +([,.;:!?)\\]”’»])")
    private val SPACE_AFTER = Regex("([(\\[“‘«¿¡]) +")
    private val WHITESPACE = Regex("\\s+")

    fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                isAllowed(c) -> append(c)
            }
        }
    }

    /** XML 1.0 forbids most control characters; symbol fonts map glyphs to the private use area, which shows as boxes. */
    private fun isAllowed(c: Char): Boolean = when {
        c < ' ' -> c == '\t' || c == '\n' || c == '\r'
        c in ''..'' -> false
        c == '￾' || c == '￿' -> false
        c.isSurrogate() -> false
        else -> true
    }

    fun document(section: Section, metadata: EpubMetadata): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!DOCTYPE html>")
        appendLine(
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${metadata.language}" xml:lang="${metadata.language}">""",
        )
        appendLine("""<head><meta charset="utf-8"/><title>${escape(section.title ?: metadata.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>""")
        appendLine("<body>")
        section.blocks.forEach { block -> block(block)?.let(::appendLine) }
        appendLine("</body></html>")
    }

    private fun block(block: Block): String? = when (block) {
        is Block.Paragraph -> paragraph(block, cssClass = if (block.isListItem) "li" else null)
        is Block.Heading -> minOf(block.level, 6).let { """<h$it id="${block.id}">${escape(block.text)}</h$it>""" }
        is Block.Figure -> """<div class="figure"><img src="${block.image}" alt=""/></div>"""
        is Block.Caption -> inline(block.spans).takeIf { it.isNotEmpty() }?.let { """<p class="caption">$it</p>""" }
        is Block.Aside -> block.paragraphs.mapNotNull { paragraph(it, cssClass = null) }.takeIf { it.isNotEmpty() }
            ?.joinToString("", """<aside class="${if (block.isFootnotes) "notes" else "margin"}">""", "</aside>")
        is Block.PageMark -> pageMark(block.page, block.isContinuation)
    }

    private fun paragraph(paragraph: Block.Paragraph, cssClass: String?): String? {
        val content = inline(paragraph.spans)
        if (content.isEmpty()) return null
        return if (cssClass == null) "<p>$content</p>" else """<p class="$cssClass">$content</p>"""
    }

    private fun pageMark(page: Int, isContinuation: Boolean): String {
        val id = Sectioner.pageAnchor(page, isContinuation)
        return if (isContinuation) """<span id="$id"></span>""" else """<span id="$id" epub:type="pagebreak" role="doc-pagebreak" aria-label="$page"></span>"""
    }

    fun inline(spans: List<Span>): String {
        val html = StringBuilder()
        val text = StringBuilder()
        var style: Run? = null

        fun flush() {
            val current = style ?: return
            html.append(styled(text.toString(), current))
            text.clear()
            style = null
        }

        for (span in spans) {
            when (span) {
                is Span.PageMark -> {
                    flush()
                    html.append(pageMark(span.page, isContinuation = false))
                }
                is Span.Text -> {
                    val run = span.run
                    val current = style
                    val isSameStyle = current != null && current.isBold == run.isBold && current.isItalic == run.isItalic && current.isRaised == run.isRaised
                    if (!isSameStyle && run.text.isNotBlank()) {
                        flush()
                        style = run
                    } else if (current == null) {
                        style = run
                    }
                    text.append(run.text)
                }
            }
        }
        flush()
        return html.toString()
            .replace(WHITESPACE, " ")
            .replace(SPACE_BEFORE, "$1")
            .replace(SPACE_AFTER, "$1")
            .trim()
    }

    private fun styled(text: String, style: Run): String {
        val core = escape(text.trim())
        if (core.isEmpty()) return if (text.isEmpty()) "" else " "
        val lead = if (text.first().isWhitespace()) " " else ""
        val trail = if (text.last().isWhitespace()) " " else ""
        var wrapped = core
        if (style.isItalic) wrapped = "<em>$wrapped</em>"
        if (style.isBold && core.length > 2) wrapped = "<strong>$wrapped</strong>"
        if (style.isRaised) wrapped = "<sup>$wrapped</sup>"
        return lead + wrapped + trail
    }

    fun navigation(book: SplitBook, metadata: EpubMetadata): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!DOCTYPE html>")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${metadata.language}" xml:lang="${metadata.language}">""")
        appendLine("""<head><meta charset="utf-8"/><title>${escape(metadata.title)}</title></head>""")
        appendLine("<body>")
        appendLine("""<nav epub:type="toc" id="toc"><h1>${escape(metadata.title)}</h1>""")
        val toc = book.toc.ifEmpty { listOf(TocEntry(metadata.title, book.sections.first().file, 0)) }
        append(nestedList(toc))
        appendLine("</nav>")
        if (book.pageList.isNotEmpty()) {
            appendLine("""<nav epub:type="page-list" id="page-list" hidden=""><ol>""")
            book.pageList.forEach { appendLine("""<li><a href="${it.href}">${it.page}</a></li>""") }
            appendLine("</ol></nav>")
        }
        appendLine("</body></html>")
    }

    /** An `ol` per depth; a bookmark two levels below its parent is shown one level below. */
    private fun nestedList(entries: List<TocEntry>): String = buildString {
        var depth = -1
        for (entry in entries) {
            val target = minOf(entry.depth, depth + 1).coerceAtLeast(0)
            when {
                target > depth -> repeat(target - depth) { append("<ol>") }
                else -> {
                    append("</li>")
                    repeat(depth - target) { append("</ol></li>") }
                }
            }
            append("""<li><a href="${entry.href}">${escape(entry.title)}</a>""")
            depth = target
        }
        append("</li>")
        repeat(depth) { append("</ol></li>") }
        appendLine("</ol>")
    }
}
