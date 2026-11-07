package com.maxi.glosa.convert

import kotlin.math.abs

sealed interface Span {
    data class Text(val run: Run) : Span

    /** Where a page of the PDF begins; `page` counts from 1. */
    data class PageMark(val page: Int) : Span
}

/** `page` is the page of the PDF, from 1, on which the block starts. */
sealed interface Block {
    val page: Int

    class Paragraph(val spans: List<Span>, val isListItem: Boolean, override val page: Int) : Block
    class Heading(val level: Int, val text: String, val id: String, override val page: Int) : Block
    class Figure(val image: String, override val page: Int) : Block
    class Caption(val spans: List<Span>, override val page: Int) : Block

    /** Margin notes or footnotes, set apart from the running text. */
    class Aside(val paragraphs: List<Paragraph>, val isFootnotes: Boolean, override val page: Int) : Block

    /** A page that begins between blocks; a continuation repeats it where a document opens mid-page. */
    class PageMark(override val page: Int, val isContinuation: Boolean = false) : Block
}

/**
 * Turns laid-out pages into one stream of blocks. Paragraphs run on across pages and columns;
 * figures, captions and notes wait until the paragraph they interrupt is over.
 */
class BookAssembler(private val stats: BookStats, private val crop: (pageIndex: Int, box: Box) -> String?) {
    private val blocks = mutableListOf<Block>()
    private val deferred = mutableListOf<Block>()
    private val pendingMarks = mutableListOf<Int>()
    private val main = Paragraphs(isFootnotes = false)
    private var headings = 0

    fun addPage(pageIndex: Int, items: List<PageItem>) {
        pendingMarks += pageIndex + 1
        for (item in items) {
            when (item) {
                is PageItem.Text -> addBand(item.rows)
                is PageItem.Caption -> {
                    main.interrupt()
                    defer(Block.Caption(spansOf(item.rows), pageIndex + 1))
                }
                is PageItem.Graphic -> {
                    main.interrupt()
                    crop(pageIndex, item.box)?.let { defer(Block.Figure(it, pageIndex + 1)) }
                }
                is PageItem.Margin -> defer(Block.Aside(paragraphsOf(item.rows, isFootnotes = false), isFootnotes = false, pageIndex + 1))
                is PageItem.Footnotes -> defer(Block.Aside(paragraphsOf(item.rows, isFootnotes = true), isFootnotes = true, pageIndex + 1))
            }
        }
    }

    fun finish(): List<Block> {
        closeParagraph()
        placeMarks()
        return blocks.toList()
    }

    private fun addBand(rows: List<Row>) {
        var i = 0
        while (i < rows.size) {
            if (isOrnament(rows[i])) {
                i++
                continue
            }
            if (CHAPTER_NUMBER.matches(rows[i].text) && stats.headingLevel(rows[i].size) != null) {
                chapterNumber = rows[i].text
                i++
                continue
            }
            val level = headingLevel(rows[i], isFirstInBand = i == 0)
            if (level == null) {
                chapterNumber = null
                main.feed(rows[i], pendingMarks.toList())?.let(::emitParagraph)
                pendingMarks.clear()
                i++
                continue
            }
            val lines = mutableListOf(rows[i])
            var next = i + 1
            while (next < rows.size) {
                val row = rows[next]
                val isClose = (row.gapAbove ?: 0f) < 0.8f * row.size
                val isDecoration = isOrnament(row) || CHAPTER_NUMBER.matches(row.text)
                if (!isClose || (!isDecoration && headingLevel(row, isFirstInBand = true) == null)) break
                if (!isDecoration) lines += row
                next++
            }
            closeParagraph()
            placeMarks()
            addHeading(level, lines.joinToString(" ") { it.text }, rows[i].pageIndex + 1)
            i = next
        }
    }

    /** Short headings are where OCR leaves its debris; a real one uses words the book uses elsewhere. */
    private fun isKnownWords(text: String): Boolean = text.split(' ')
        .map { word -> word.filter(Char::isLetter).lowercase() }
        .filter { it.isNotEmpty() }
        .all { (stats.vocabulary[it] ?: 0) >= 3 }

    /** A large glyph or two that spell nothing, like an arrow on a chapter opener, is decoration. */
    private fun isOrnament(row: Row): Boolean =
        row.size >= BookStats.HEADING_SCALE * stats.bodySize && row.text.length <= 3 &&
            row.text.count(Char::isLetterOrDigit) <= 1 && !CHAPTER_NUMBER.matches(row.text)

    /**
     * A chapter number set large waits for the title it numbers, without ending the paragraph
     * before it; with no title after it, it was decoration, like a stray digit on a scan.
     */
    private var chapterNumber: String? = null

    private fun addHeading(level: Int, text: String, page: Int) {
        val number = chapterNumber?.let { "$it " }.orEmpty()
        chapterNumber = null
        blocks += Block.Heading(level, number + text, "h${++headings}", page)
    }

    private fun headingLevel(row: Row, isFirstInBand: Boolean): Int? {
        val text = row.text
        val letters = text.count(Char::isLetter)
        if (text.length > 150 || letters < 2 || letters < 0.45 * text.length) return null
        if (letters < 5 && !isKnownWords(text)) return null
        stats.headingLevel(row.size)?.let { return it }
        val standsApart = isFirstInBand || (row.gapAbove ?: 0f) > 0.4f * row.size
        val isShort = text.length <= 90 && row.x1 - row.x0 < 0.85f * row.column.width
        if (!standsApart || !isShort || text.endsWith('.') || !stats.isBodySize(row.size)) return null
        val isCapitalized = letters >= 4 && text.count(Char::isUpperCase) >= 0.9 * letters
        return if (row.isBold || isCapitalized) stats.runInLevel else null
    }

    /**
     * A block that waits for the paragraph to end takes along the marks of the pages it was found
     * on, so a page with nothing but a figure begins at that figure.
     */
    private fun defer(block: Block) {
        if (!main.isOpen) {
            emit(block)
            return
        }
        val marks = pendingMarks.filter { it <= block.page }
        pendingMarks -= marks.toSet()
        marks.forEach { deferred += Block.PageMark(it) }
        deferred += block
    }

    private fun emit(block: Block) {
        placeMarks()
        blocks += block
    }

    private fun emitParagraph(paragraph: Block.Paragraph) {
        blocks += paragraph
        blocks += deferred
        deferred.clear()
    }

    private fun closeParagraph() {
        main.close()?.let(::emitParagraph)
        blocks += deferred
        deferred.clear()
    }

    private fun placeMarks() {
        pendingMarks.forEach { blocks += Block.PageMark(it) }
        pendingMarks.clear()
    }

    private fun spansOf(rows: List<Row>): List<Span> = rows.flatMapIndexed { index, row ->
        val runs = if (index == 0) row.runs else listOf(Run(" ", isBold = false, isItalic = false)) + row.runs
        runs.map(Span::Text)
    }

    private fun paragraphsOf(rows: List<Row>, isFootnotes: Boolean): List<Block.Paragraph> {
        val builder = Paragraphs(isFootnotes)
        return rows.mapNotNull { builder.feed(it, emptyList()) } + listOfNotNull(builder.close())
    }

    /** Joins rows into paragraphs, deciding at every row whether it opens a new one. */
    private inner class Paragraphs(private val isFootnotes: Boolean) {
        private var spans: MutableList<Span>? = null
        private var isListItem = false
        private var page = 0
        private var previous: Row? = null
        private var isFirstRow = false
        private var listMarkerX: Float? = null
        private var isInterrupted = false

        val isOpen: Boolean get() = spans != null

        /** A figure between two rows widens the gap between them without ending the paragraph. */
        fun interrupt() {
            isInterrupted = true
        }

        /** Returns the paragraph this row closed, if any. */
        fun feed(row: Row, marks: List<Int>): Block.Paragraph? {
            val current = spans
            var closed: Block.Paragraph? = null
            if (current == null || opensParagraph(row)) {
                closed = close()
                val hasMarker = LIST_MARKER.containsMatchIn(row.text)
                spans = mutableListOf()
                isListItem = hasMarker
                listMarkerX = if (hasMarker) row.x0 else null
                page = row.pageIndex + 1
                isFirstRow = true
            } else {
                breakLine(current, row)
                isFirstRow = false
            }
            val target = checkNotNull(spans)
            marks.forEach { target += Span.PageMark(it) }
            row.runs.forEach { target += Span.Text(it) }
            previous = row
            isInterrupted = false
            return closed
        }

        fun close(): Block.Paragraph? {
            val current = spans ?: return null
            spans = null
            previous = null
            listMarkerX = null
            return Block.Paragraph(current, isListItem, page)
        }

        private fun opensParagraph(row: Row): Boolean {
            val prev = previous ?: return true
            val size = row.size
            val isSamePlace = prev.pageIndex == row.pageIndex && prev.x0 < row.x1 && row.x0 < prev.x1
            val prevIsShort = if (prev.column.isRagged) {
                prev.text.trimEnd().lastOrNull() in SENTENCE_ENDS && prev.x1 < prev.column.right - 0.2f * prev.column.width
            } else {
                prev.x1 < prev.column.right - 1.5f * prev.size
            }
            val isSpacedApart = isSamePlace && !isInterrupted && (row.gapAbove ?: 0f) > (stats.leading - size) + 0.45f * size
            val changesSize = abs(row.size - prev.size) > 0.25f * minOf(row.size, prev.size)
            val prevEndsSentence = prev.text.trimEnd().lastOrNull() in SENTENCE_ENDS || prev.text.trimEnd().endsWith(';')
            val marker = listMarkerX
            return when {
                LIST_MARKER.containsMatchIn(row.text) ->
                    prevIsShort || prevEndsSentence || isSpacedApart || abs(row.indent - prev.indent) > 0.5f * size
                marker != null -> row.x0 <= marker + 0.3f * size || prevIsShort || isSpacedApart
                isFootnotes -> prevIsShort || (FOOTNOTE_NUMBER.containsMatchIn(row.text) && row.indent < 2 * size)
                isFirstRow && !prevIsShort && row.indent > prev.indent + 0.5f * size -> false
                else -> {
                    val isIndented = row.indent in (0.8f * size)..(6f * size) && abs(row.indent - prev.indent) > 0.5f * size &&
                        (!row.column.isRagged || prevEndsSentence)
                    isIndented || prevIsShort || isSpacedApart || changesSize
                }
            }
        }

        /** Ends the paragraph's text so far for the next row: a space, or a hyphenated word put back together. */
        private fun breakLine(spans: MutableList<Span>, next: Row) {
            val index = spans.indexOfLast { it is Span.Text }
            if (index < 0) return
            val run = (spans[index] as Span.Text).run
            val text = run.text.trimEnd()
            val hyphenated = HYPHENATED.find(text)
            val joined = when {
                text.endsWith('—') -> text
                hyphenated != null -> joinHyphenated(text, hyphenated.groupValues[1], next.text)
                else -> "$text "
            }
            spans[index] = Span.Text(run.copy(text = joined))
        }

        /**
         * Keeps the hyphen when the book spells the word with one more often than without. A word it
         * spells neither way more often keeps it inside a longer compound, like "over-the-counter", or
         * when both halves are words of their own, like "liquidity-supplying".
         */
        private fun joinHyphenated(text: String, head: String, nextText: String): String {
            val kept = text.dropLast(1) + "-"
            val joined = text.dropLast(1)
            val tail = LEADING_WORD.find(nextText)?.value ?: return kept
            if (!tail.first().isLowerCase()) return kept
            val vocabulary = stats.vocabulary
            val first = head.lowercase()
            val second = tail.lowercase()
            val hyphenated = vocabulary["$first-$second"] ?: 0
            val solid = vocabulary["$first$second"] ?: 0
            if (hyphenated != solid) return if (hyphenated > solid) kept else joined
            val isInCompound = text.dropLast(head.length + 1).endsWith('-') || nextText.getOrNull(tail.length) == '-'
            val areWords = listOf(first, second).all { it.length >= MIN_COMPOUND_HALF && (vocabulary[it] ?: 0) > 0 }
            return if (isInCompound || areWords) kept else joined
        }
    }

    private companion object {
        val CHAPTER_NUMBER = Regex("^(\\d{1,3}|[IVXLC]{1,6})\\.?$")
        val LIST_MARKER = Regex("^(•|·|▪|■|●|◦|–|-|\\d{1,2}[.)]|\\(\\d{1,2}\\)|[a-z][.)])\\s")
        val FOOTNOTE_NUMBER = Regex("^[\\d*†‡§¹²³⁴⁵⁶⁷⁸⁹]+\\s?")
        val HYPHENATED = Regex("(\\p{L}+)[-­‐]$")
        val LEADING_WORD = Regex("^\\p{L}+")

        /** Shorter halves are prefixes and suffixes that happen to be words, like the "in" of "in-significant". */
        const val MIN_COMPOUND_HALF = 4
        val SENTENCE_ENDS = setOf('.', '?', '!', ':', '"', '”', '»', ')')
    }
}
