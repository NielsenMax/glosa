package com.maxi.glosa.convert

import java.util.TreeMap

/** One XHTML document of the EPUB. */
class Section(val file: String, val title: String?, val blocks: List<Block>)

class TocEntry(val title: String, val href: String, val depth: Int)

/**
 * Where page [page] of the PDF begins in the EPUB; `href` carries the anchor's fragment. A
 * continuation repeats a page at the top of the next document when a chapter opens mid-page.
 */
class PageTarget(val page: Int, val href: String, val isContinuation: Boolean = false)

class SplitBook(val sections: List<Section>, val toc: List<TocEntry>, val pageList: List<PageTarget>)

/**
 * Cuts the block stream into documents, one per chapter. The PDF's bookmarks say where chapters
 * begin; without them, the commonest top-level heading does. A document never runs longer than
 * [MAX_PAGES] pages of the PDF, which keeps each one quick to lay out.
 */
object Sectioner {
    private const val MAX_PAGES = 40

    private class Start(val title: String?, val isInToc: Boolean = true)

    fun split(blocks: List<Block>, outline: List<OutlineEntry>): SplitBook {
        val starts = TreeMap<Int, Start>()
        val entryStarts = if (outline.isNotEmpty()) startsFromOutline(blocks, outline, starts) else emptyMap()
        if (outline.isEmpty()) startsFromHeadings(blocks, starts)
        if (starts.isEmpty() || starts.firstKey() != 0) starts[0] = Start(title = null, isInToc = false)
        capLength(blocks, starts)

        val sections = sectionsOf(blocks, starts)
        val pageList = pageListOf(sections)
        val fileAt = starts.keys.zip(sections.map { it.file }).toMap()
        val toc = if (outline.isNotEmpty()) {
            outline.filter { it.depth <= 2 }.mapNotNull { entry ->
                val href = entryStarts[entry]?.let(fileAt::get)
                    ?: headingHref(sections, entry)
                    ?: pageHref(pageList, entry.pageIndex + 1)
                    ?: return@mapNotNull null
                TocEntry(entry.title, href, entry.depth)
            }
        } else {
            headingToc(sections, pageCount(blocks))
        }
        return SplitBook(sections, toc.ifEmpty { sectionToc(sections, starts) }, pageList)
    }

    /** Splits at the bookmarks of the top level, or of the second when the top holds only a part or two. */
    private fun startsFromOutline(blocks: List<Block>, outline: List<OutlineEntry>, starts: TreeMap<Int, Start>): Map<OutlineEntry, Int> {
        val splitDepth = if (outline.count { it.depth == 0 } < 3 && outline.count { it.depth == 1 } >= 3) 1 else 0
        val entryStarts = mutableMapOf<OutlineEntry, Int>()
        for (entry in outline.filter { it.depth <= splitDepth }.sortedBy { it.pageIndex }) {
            val index = startOfPage(blocks, entry.pageIndex + 1) ?: continue
            if (index !in starts) starts[index] = Start(entry.title)
            entryStarts[entry] = index
        }
        return entryStarts
    }

    /**
     * The first heading on the page, else the first block that starts on it, else the first one
     * after; the page marks right before it open the new document with it.
     */
    private fun startOfPage(blocks: List<Block>, page: Int): Int? {
        val onPage = blocks.indices.filter { blocks[it].page == page && blocks[it] !is Block.PageMark }
        var start = onPage.firstOrNull { blocks[it] is Block.Heading }
            ?: onPage.firstOrNull()
            ?: blocks.indices.firstOrNull { blocks[it].page > page && blocks[it] !is Block.PageMark }
            ?: return null
        while (start > 0 && blocks[start - 1] is Block.PageMark) start--
        return start
    }

    /** Headings with nothing but other headings between them, like a part's number and its name, open one document. */
    private fun startsFromHeadings(blocks: List<Block>, starts: TreeMap<Int, Start>) {
        val titles = titleHeadings(blocks)
        val level = chapterLevel(titles, pageCount(blocks)) ?: return
        var lastStart: Int? = null
        blocks.forEachIndexed { index, block ->
            if (block !is Block.Heading || block !in titles || block.level > level) return@forEachIndexed
            val previous = lastStart
            if (previous != null && (previous until index).all { blocks[it] is Block.Heading || blocks[it] is Block.PageMark }) {
                starts[previous] = Start("${starts.getValue(previous).title} ${block.text}")
                return@forEachIndexed
            }
            var start = index
            while (start > 0 && blocks[start - 1] is Block.PageMark) start--
            starts[start] = Start(block.text)
            lastStart = start
        }
    }

    /** Text that recurs as a heading three times or more, like a library stamp on a scan, titles nothing. */
    private fun titleHeadings(blocks: List<Block>): Set<Block.Heading> {
        val headings = blocks.filterIsInstance<Block.Heading>()
        val counts = headings.groupingBy { it.text.lowercase() }.eachCount()
        return headings.filter { (counts[it.text.lowercase()] ?: 0) < 3 }.toSet()
    }

    /** The deepest heading level whose headings, with those above it, still leave four pages or more per chapter. */
    private fun chapterLevel(headings: Collection<Block.Heading>, pageCount: Int): Int? {
        val levels = headings.map { it.level }.distinct().sorted()
        return levels.lastOrNull { level ->
            val count = headings.count { it.level <= level }
            count >= 2 && count * PAGES_PER_CHAPTER <= pageCount
        }
    }

    private const val PAGES_PER_CHAPTER = 4

    private fun pageCount(blocks: List<Block>): Int = blocks.maxOfOrNull { it.page } ?: 0

    private fun capLength(blocks: List<Block>, starts: TreeMap<Int, Start>) {
        val ends = starts.keys.drop(1) + blocks.size
        for ((start, end) in starts.keys.toList().zip(ends)) {
            val title = starts.getValue(start).title
            var firstPage = blocks.getOrNull(start)?.page ?: continue
            for (index in start + 1 until end) {
                val block = blocks[index]
                val opensSomething = block is Block.Heading || block is Block.Paragraph || block is Block.Figure
                if (opensSomething && block.page - firstPage >= MAX_PAGES) {
                    starts[index] = Start(title, isInToc = false)
                    firstPage = block.page
                }
            }
        }
    }

    private fun sectionsOf(blocks: List<Block>, starts: TreeMap<Int, Start>): List<Section> {
        val placed = mutableSetOf<Int>()
        val ends = starts.keys.drop(1) + blocks.size
        return starts.entries.zip(ends).mapIndexed { number, (entry, end) ->
            val slice = blocks.subList(entry.key, end)
            val firstPage = slice.firstOrNull { it !is Block.PageMark }?.page
            val continuation = if (firstPage != null && firstPage in placed) listOf(Block.PageMark(firstPage, isContinuation = true)) else emptyList()
            slice.forEach { placed += marksIn(it) }
            Section("s%03d.xhtml".format(number + 1), entry.value.title, continuation + slice)
        }
    }

    private fun marksIn(block: Block): List<Int> = when (block) {
        is Block.PageMark -> listOf(block.page)
        is Block.Paragraph -> block.spans.filterIsInstance<Span.PageMark>().map { it.page }
        is Block.Caption -> block.spans.filterIsInstance<Span.PageMark>().map { it.page }
        else -> emptyList()
    }

    private fun pageListOf(sections: List<Section>): List<PageTarget> = sections.flatMap { section ->
        section.blocks.flatMap { block ->
            when (block) {
                is Block.PageMark -> listOf(
                    PageTarget(block.page, "${section.file}#${pageAnchor(block.page, block.isContinuation)}", block.isContinuation),
                )
                else -> marksIn(block).map { PageTarget(it, "${section.file}#${pageAnchor(it, isContinuation = false)}") }
            }
        }
    }

    private fun pageHref(pageList: List<PageTarget>, page: Int): String? =
        pageList.firstOrNull { it.page >= page && !it.isContinuation }?.href

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun normalized(text: String) = text.lowercase().replace(NOT_WORD, " ").trim()

    /** A bookmark's own heading on its page, which also carries the section number the bookmark leaves out. */
    private fun headingHref(sections: List<Section>, entry: OutlineEntry): String? {
        val title = normalized(entry.title).ifEmpty { return null }
        for (section in sections) {
            val heading = section.blocks.firstOrNull { it is Block.Heading && it.page == entry.pageIndex + 1 && normalized(it.text).endsWith(title) }
            if (heading is Block.Heading) return "${section.file}#${heading.id}"
        }
        return null
    }

    /**
     * Chapters, and the sections under them while there is no more than one per page. A heading that
     * opens its document links to the document itself, which is how the reader names the chapter it shows.
     */
    private fun headingToc(sections: List<Section>, pageCount: Int): List<TocEntry> {
        val titles = titleHeadings(sections.flatMap { it.blocks })
        val level = chapterLevel(titles, pageCount) ?: return emptyList()
        val deepest = if (titles.count { it.level <= level + 1 } <= pageCount) level + 1 else level
        val depths = titles.map { it.level }.filter { it <= deepest }.distinct().sorted()
        return sections.flatMap { section ->
            val opening = section.blocks.firstOrNull { it !is Block.PageMark }
            section.blocks.filterIsInstance<Block.Heading>()
                .filter { it in titles && it.level <= deepest }
                .map { heading ->
                    val href = if (heading === opening) section.file else "${section.file}#${heading.id}"
                    TocEntry(heading.text, href, depths.indexOf(heading.level))
                }
        }
    }

    private fun sectionToc(sections: List<Section>, starts: TreeMap<Int, Start>): List<TocEntry> =
        sections.zip(starts.values).mapNotNull { (section, start) ->
            section.title?.takeIf { start.isInToc }?.let { TocEntry(it, section.file, 0) }
        }

    fun pageAnchor(page: Int, isContinuation: Boolean): String = if (isContinuation) "p${page}c" else "p$page"
}
