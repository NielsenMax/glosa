package com.maxi.glosa.convert

import kotlin.math.abs

/** The horizontal extent of a text column; ragged columns are set flush left, not justified. */
class Column(val left: Float, val right: Float, val isRagged: Boolean) {
    val width: Float get() = right - left
}

/** The segments of a text flow that share a baseline, read left to right. */
class Row(val segments: List<TextLine>, val column: Column, val pageIndex: Int, val gapAbove: Float?) {
    val x0: Float = segments.minOf { it.x0 }
    val x1: Float = segments.maxOf { it.x1 }
    val top: Float = segments.minOf { it.top }
    val bottom: Float = segments.maxOf { it.bottom }
    val size: Float = segments.maxOf { it.size }
    val text: String = segments.joinToString(" ") { it.text }
    val indent: Float get() = x0 - column.left
    val isBold: Boolean get() = segments.all { it.isBold }

    val runs: List<Run>
        get() = segments.flatMapIndexed { index, line ->
            if (index == 0) line.runs else listOf(Run(" ", isBold = false, isItalic = false)) + line.runs
        }

    /** Table cells, chart labels and formulas: split rows, few letters, or small type that does not fill a line. */
    fun isTabular(bodySize: Float): Boolean {
        val letters = text.count(Char::isLetter)
        val isSmall = size < 0.65f * bodySize || (size < 0.9f * bodySize && x1 - x0 < 0.5f * column.width)
        return segments.size > 1 || letters < 0.45 * text.length || isSmall
    }

    fun inColumn(column: Column) = Row(segments, column, pageIndex, gapAbove)
}

sealed interface PageItem {
    val top: Float
    val bottom: Float

    sealed interface Rows : PageItem {
        val rows: List<Row>
        override val top: Float get() = rows.minOf { it.top }
        override val bottom: Float get() = rows.maxOf { it.bottom }
    }

    class Text(override val rows: List<Row>) : Rows
    class Caption(override val rows: List<Row>) : Rows

    /** Notes in the outer margin, read after the paragraph they stand beside. */
    class Margin(override val rows: List<Row>) : Rows
    class Footnotes(override val rows: List<Row>) : Rows

    /** A figure or a table, kept as a picture of the page. */
    class Graphic(val box: Box) : PageItem {
        override val top: Float get() = box.y0
        override val bottom: Float get() = box.y1
    }
}

/** Dark pixels of a rendered page, to find figures the text layer says nothing about. */
class InkMap(private val raster: Raster, private val scale: Float) {
    fun regions(x0: Float, x1: Float, from: Float, to: Float): List<ClosedFloatingPointRange<Float>> {
        val left = (x0 * scale).toInt().coerceIn(0, raster.width - 1)
        val right = (x1 * scale).toInt().coerceIn(left + 1, raster.width)
        val inked = mutableListOf<Float>()
        for (y in (from * scale).toInt().coerceAtLeast(0) until (to * scale).toInt().coerceAtMost(raster.height)) {
            var dark = 0
            val offset = y * raster.width
            for (x in left until right) {
                if (luminance(raster.pixels[offset + x]) < DARK) dark++
            }
            if (dark >= MIN_DARK_PIXELS) inked += y / scale
        }
        val regions = mutableListOf<ClosedFloatingPointRange<Float>>()
        var start = inked.firstOrNull() ?: return regions
        var end = start
        for (y in inked.drop(1)) {
            if (y - end > MAX_BLANK) {
                if (end - start >= MIN_HEIGHT) regions += start..end
                start = y
            }
            end = y
        }
        if (end - start >= MIN_HEIGHT) regions += start..end
        return regions
    }

    companion object {
        const val DARK = 150
        private const val MIN_DARK_PIXELS = 2
        private const val MAX_BLANK = 4f
        private const val MIN_HEIGHT = 12f

        fun luminance(argb: Int): Int = (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000
    }
}

/**
 * Splits a page into what the book is made of: running text in reading order, figures, captions,
 * margin notes and footnotes. Large type in the margin is a title set beside the text, read before
 * it when it stands in the upper half of the page. Keeps the last column of each side, so a page with too little text
 * to measure its own column borrows it.
 */
class PageLayout(private val stats: BookStats) {
    private val lastColumns = mutableMapOf<Int, Column>()

    private val body get() = stats.bodySize

    /**
     * [drawn] holds every line on the page, running heads included, so their ink never passes for a
     * figure. [ink] renders the page; it is only called when the page has room for a figure.
     */
    fun layout(page: PageLines, drawn: List<TextLine>, ink: (() -> InkMap)?): List<PageItem> {
        val (lines, footnotes) = splitFootnotes(page)
        val column = mainColumn(page, lines)
        val (inside, outside) = lines.partition { it.x1 > column.left + 3 && it.x0 < column.right - 3 }
        val (titles, margin) = outside.partition { it.size >= BookStats.HEADING_SCALE * body }

        val flows = readingOrder(inside, column).toMutableList()
        if (titles.isNotEmpty()) {
            val titleFlow = Column(titles.minOf { it.x0 }, titles.maxOf { it.x1 }, isRagged = true) to titles
            if (titles.minOf { it.top } < page.height / 2) flows.add(0, titleFlow) else flows += titleFlow
        }
        val items = flows.flatMap { (flowColumn, flowLines) -> flowItems(page.index, flowColumn, flowLines) }.toMutableList()
        if (ink != null) placeGraphics(items, figuresFromInk(page, drawn, column, ink))
        classifyCaptions(items)
        val merged = mergeGraphics(absorbLabels(items))
        val result = if (isFigurePage(merged, column)) asFigurePage(merged) else merged

        val notes = buildList {
            bands(page.index, Column(margin.minOfOrNull { it.x0 } ?: 0f, margin.maxOfOrNull { it.x1 } ?: 0f, isRagged = true), margin)
                .forEach { add(PageItem.Margin(it)) }
            if (footnotes.isNotEmpty()) add(PageItem.Footnotes(rows(page.index, column, footnotes)))
        }
        if (result.isEmpty() && notes.isEmpty() && ink != null && lines.isEmpty()) {
            return wholePage(page, drawn, ink)
        }
        return result + notes
    }

    private val FOOTNOTE_START = Regex("^[\\d*†‡§¹²³⁴⁵⁶⁷⁸⁹]")

    /** Footnotes: smaller text below the last line of body text, opening with a note number or symbol. */
    private fun splitFootnotes(page: PageLines): Pair<List<TextLine>, List<TextLine>> {
        val lastBody = page.lines.filter { stats.isBodySize(it.size) && it.text.length > 3 }.maxOfOrNull { it.bottom }
            ?: return page.lines to emptyList()
        val first = page.lines
            .filter { it.top > lastBody && it.size < 0.9f * body && it.top > 0.5f * page.height }
            .minByOrNull { it.top }
        if (first == null || !FOOTNOTE_START.containsMatchIn(first.text)) return page.lines to emptyList()
        return page.lines.partition { it.top < first.top - 1 }
    }

    private fun mainColumn(page: PageLines, lines: List<TextLine>): Column {
        val parity = page.index % 2
        val measured = lines.filter { stats.isBodySize(it.size) && it.width > 0.3f * page.width }
        if (measured.size < 3) {
            return lastColumns[parity] ?: Column(lines.minOfOrNull { it.x0 } ?: 0f, lines.maxOfOrNull { it.x1 } ?: page.width, isRagged = false)
        }
        val left = measured.map { it.x0 }.sorted()[measured.size / 10]
        val right = measured.map { it.x1 }.sorted()[measured.size * 9 / 10]
        val flush = measured.count { it.x1 >= right - 0.75f * body }
        return Column(left, right, isRagged = flush < 0.5 * measured.size).also { lastColumns[parity] = it }
    }

    /** One flow per column; on two-column pages, lines that cross the gutter separate the stretches. */
    private fun readingOrder(lines: List<TextLine>, column: Column): List<Pair<Column, List<TextLine>>> {
        val mid = (column.left + column.right) / 2
        val tolerance = 0.03f * column.width
        val bodyLines = lines.filter { stats.isBodySize(it.size) }
        val onLeft = bodyLines.count { it.x1 < mid + tolerance }
        val onRight = bodyLines.count { it.x0 > mid - tolerance }
        if (onLeft < 5 || onRight < 5 || onLeft + onRight < 0.85 * bodyLines.size) return listOf(column to lines)

        val leftColumn = Column(column.left, bodyLines.filter { it.x1 < mid + tolerance }.maxOf { it.x1 }, column.isRagged)
        val rightColumn = Column(bodyLines.filter { it.x0 > mid - tolerance }.minOf { it.x0 }, column.right, column.isRagged)
        val flows = mutableListOf<Pair<Column, List<TextLine>>>()
        val spanning = lines.filter { it.x0 < mid - tolerance && it.x1 > mid + tolerance }.sortedBy { it.top }
        var from = Float.NEGATIVE_INFINITY
        for (stop in spanning.map { it.top } + Float.POSITIVE_INFINITY) {
            val stretch = lines.filter { it.top >= from && it.top < stop && it !in spanning }
            stretch.filter { it.x1 < mid + tolerance }.takeIf { it.isNotEmpty() }?.let { flows += leftColumn to it }
            stretch.filter { it.x1 >= mid + tolerance }.takeIf { it.isNotEmpty() }?.let { flows += rightColumn to it }
            spanning.firstOrNull { it.top == stop }?.let { flows += column to listOf(it) }
            from = stop
        }
        return flows
    }

    /** Lines on one baseline form a row only at a similar size: a title beside the text stays a line of its own. */
    private fun rows(pageIndex: Int, column: Column, lines: List<TextLine>): List<Row> {
        val groups = mutableListOf<MutableList<TextLine>>()
        for (line in lines.sortedBy { it.baseline }) {
            val group = groups.lastOrNull { abs(line.baseline - it.first().baseline) < 0.3f * line.size && isSimilarSize(it.first(), line) }
            if (group != null) group += line else groups += mutableListOf(line)
        }
        val rows = mutableListOf<Row>()
        for (group in withDropCaps(groups).sortedBy { group -> group.minOf { it.top } }) {
            val segments = group.sortedBy { it.x0 }
            rows += Row(segments, column, pageIndex, gapAbove = rows.lastOrNull()?.let { segments.minOf(TextLine::top) - it.bottom })
        }
        return rows
    }

    private fun isSimilarSize(a: TextLine, b: TextLine) = maxOf(a.size, b.size) <= 1.3f * minOf(a.size, b.size)

    /**
     * A drop cap spans several lines; its letter belongs at the start of the first line beside it,
     * and the lines beside it start where it does, so their indent opens no paragraph.
     */
    private fun withDropCaps(groups: List<MutableList<TextLine>>): List<MutableList<TextLine>> {
        val caps = groups.filter { it.size == 1 && it[0].text.length == 1 && it[0].text[0].isLetter() && it[0].size >= 1.8f * body }
        if (caps.isEmpty()) return groups
        val result = groups.toMutableList()
        for (group in caps) {
            val cap = group[0]
            val beside = result
                .filter { it !== group && it.first().x0 > cap.x1 - 2 && it.first().top in (cap.top - 6)..cap.bottom }
                .sortedBy { it.first().top }
            if (beside.isEmpty()) continue
            result.remove(group)
            beside.forEachIndexed { index, line ->
                val first = line.minBy { it.x0 }
                val runs = if (index == 0) listOf(first.runs.first().let { it.copy(text = cap.text + it.text) }) + first.runs.drop(1) else first.runs
                line[line.indexOf(first)] = TextLine(runs, x0 = cap.x0, x1 = first.x1, baseline = first.baseline, size = first.size)
            }
        }
        return result
    }

    private fun bands(pageIndex: Int, column: Column, lines: List<TextLine>): List<List<Row>> {
        val bands = mutableListOf<MutableList<Row>>()
        for (row in rows(pageIndex, column, lines)) {
            val gap = row.gapAbove
            if (gap != null && gap <= BAND_GAP * body) bands.last() += row else bands += mutableListOf(row)
        }
        return bands.map { band -> ownColumn(band, column)?.let { own -> band.map { it.inColumn(own) } } ?: band }
    }

    /**
     * A block of text set even slightly narrower than the page's column, like a box, has lines that
     * end at its own right edge, or none at all when it is set flush left; measured against the
     * page's column, each line would look like a paragraph's last. A block of four lines or more
     * is measured on its own.
     */
    private fun ownColumn(band: List<Row>, column: Column): Column? {
        if (band.size < 4) return null
        val left = band.map { it.x0 }.sorted()[band.size / 10]
        val right = band.map { it.x1 }.sorted()[band.size * 9 / 10]
        val flush = band.count { it.x1 >= right - 0.75f * it.size }
        return Column(left, right, isRagged = flush < 0.5 * band.size)
    }

    /** A band opening with "Figure 3" stays text even when set small: it is the caption, kept for reading and search. */
    private fun flowItems(pageIndex: Int, column: Column, lines: List<TextLine>): List<PageItem> = bands(pageIndex, column, lines).map { band ->
        val tabular = band.count { it.isTabular(body) }
        val isTable = tabular * 2 >= band.size && (band.size >= 2 || band[0].text.count(Char::isLetter) < 0.3 * band[0].text.length)
        if (isTable && !isCaption(band[0].text)) PageItem.Graphic(boxOf(band, column)) else PageItem.Text(band)
    }

    private fun boxOf(rows: List<Row>, column: Column) =
        Box(minOf(column.left, rows.minOf { it.x0 }), rows.minOf { it.top }, maxOf(column.right, rows.maxOf { it.x1 }), rows.maxOf { it.bottom })

    private fun figuresFromInk(page: PageLines, drawn: List<TextLine>, column: Column, ink: () -> InkMap): List<PageItem.Graphic> {
        val start = maxOf(36f, 0.04f * page.height)
        val end = page.height - maxOf(30f, 0.04f * page.height)
        val gaps = mutableListOf<Pair<Float, Float>>()
        var covered = start
        for ((top, bottom) in drawn.map { it.top to it.bottom }.sortedBy { it.first } + (end to end)) {
            val gapEnd = minOf(top, end)
            if (gapEnd - covered >= MIN_FIGURE_GAP) gaps += covered to gapEnd
            covered = maxOf(covered, bottom)
        }
        if (gaps.isEmpty()) return emptyList()
        val map = ink()
        return gaps.flatMap { (from, to) ->
            map.regions(column.left, column.right, from + 2, to - 2).map { region ->
                PageItem.Graphic(Box(column.left - 6, maxOf(from, region.start - 6), column.right + 6, minOf(to, region.endInclusive + 6)))
            }
        }
    }

    /** Graphics found on the rendered page go before the first item that starts below them. */
    private fun placeGraphics(items: MutableList<PageItem>, graphics: List<PageItem.Graphic>) {
        for (graphic in graphics) {
            val index = items.indexOfFirst { it.top > graphic.top }
            if (index < 0) items += graphic else items.add(index, graphic)
        }
    }

    private val CAPTION_LABEL = Regex("^(fig(ure|ura)?\\.?|table|tabla|cuadro|gráfico|exhibit|chart|imagen)\\s*\\d+([.\\-–]\\d+)*", RegexOption.IGNORE_CASE)

    /** "Figure 3. Spreads" or "TABLE 2-1 Orders" is a caption; "Figure 3 shows" is a sentence that mentions one. */
    private fun isCaption(text: String): Boolean {
        val label = CAPTION_LABEL.find(text) ?: return false
        val rest = text.substring(label.range.last + 1).trimStart()
        return rest.isEmpty() || rest[0] == '.' || rest[0] == ':' || rest[0].isUpperCase()
    }

    /** A band opening with "Figure 3" or "Table 2-1" next to a graphic is its caption, not running text. */
    private fun classifyCaptions(items: MutableList<PageItem>) {
        for (i in items.indices) {
            val item = items[i] as? PageItem.Text ?: continue
            val nextToGraphic = items.getOrNull(i - 1) is PageItem.Graphic || items.getOrNull(i + 1) is PageItem.Graphic
            if (nextToGraphic && isCaption(item.rows[0].text)) items[i] = PageItem.Caption(item.rows)
        }
    }

    /** A short text band wedged between two graphics is a label inside one figure. */
    private fun absorbLabels(items: List<PageItem>): List<PageItem> = items.mapIndexed { i, item ->
        val isLabel = item is PageItem.Text && item.rows.size <= 2 &&
            items.getOrNull(i - 1) is PageItem.Graphic && items.getOrNull(i + 1) is PageItem.Graphic
        if (isLabel) PageItem.Graphic(boxOf(item.rows, item.rows[0].column)) else item
    }

    private fun mergeGraphics(items: List<PageItem>): List<PageItem> {
        val merged = mutableListOf<PageItem>()
        for (item in items) {
            val last = merged.lastOrNull()
            if (item is PageItem.Graphic && last is PageItem.Graphic && item.box.y0 - last.box.y1 < MERGE_GAP) {
                merged[merged.lastIndex] = PageItem.Graphic(union(last.box, item.box))
            } else {
                merged += item
            }
        }
        return merged
    }

    private fun isFigurePage(items: List<PageItem>, column: Column): Boolean {
        val prose = items.filterIsInstance<PageItem.Text>().flatMap { it.rows }
            .count { !it.isTabular(body) && it.x1 - it.x0 > 0.4f * column.width }
        return prose < 3 && items.any { it is PageItem.Graphic }
    }

    /** A page that is mostly figure becomes one picture, with its caption kept as text. */
    private fun asFigurePage(items: List<PageItem>): List<PageItem> {
        val pictured = items.filterNot { it is PageItem.Caption }
        val box = pictured.map { item ->
            when (item) {
                is PageItem.Graphic -> item.box
                is PageItem.Rows -> boxOf(item.rows, item.rows[0].column)
            }
        }.reduce(::union)
        return listOf(PageItem.Graphic(box)) + items.filterIsInstance<PageItem.Caption>()
    }

    private fun wholePage(page: PageLines, drawn: List<TextLine>, ink: () -> InkMap): List<PageItem> {
        val margin = maxOf(30f, 0.04f * page.height)
        val top = drawn.filter { it.bottom < 0.12f * page.height }.maxOfOrNull { it.bottom + 2 } ?: margin
        val bottom = drawn.filter { it.top > 0.88f * page.height }.minOfOrNull { it.top - 2 } ?: (page.height - margin)
        val inked = ink().regions(margin, page.width - margin, top, bottom)
        if (inked.isEmpty()) return emptyList()
        return listOf(PageItem.Graphic(Box(margin, inked.first().start - 6, page.width - margin, inked.last().endInclusive + 6)))
    }

    private fun union(a: Box, b: Box) = Box(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1))

    private companion object {
        /** Rows closer than this share of the body size belong to the same band of text. */
        const val BAND_GAP = 1.0f

        /** A blank stretch must be this tall, in points, to hold a figure. */
        const val MIN_FIGURE_GAP = 20f

        /** Graphics closer than this, in points, are parts of one figure. */
        const val MERGE_GAP = 24f
    }
}
