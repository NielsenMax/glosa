package com.maxi.glosa.convert

import kotlin.math.abs

/** Text sharing one style. `isRaised` marks superscripts such as footnote calls. */
data class Run(val text: String, val isBold: Boolean, val isItalic: Boolean, val isRaised: Boolean = false)

/** Text on one baseline with no gap wide enough to separate columns or table cells. */
class TextLine(val runs: List<Run>, val x0: Float, val x1: Float, val baseline: Float, val size: Float) {
    val text: String = runs.joinToString("") { it.text }.trim()
    val top: Float get() = baseline - ASCENT * size
    val bottom: Float get() = baseline + DESCENT * size
    val width: Float get() = x1 - x0

    val isBold: Boolean get() = styledShare { it.isBold } > 0.6
    val isItalic: Boolean get() = styledShare { it.isItalic } > 0.6

    private fun styledShare(style: (Run) -> Boolean): Double {
        val letters = runs.sumOf { run -> run.text.count { it.isLetterOrDigit() } }
        if (letters == 0) return 0.0
        return runs.filter(style).sumOf { run -> run.text.count { it.isLetterOrDigit() } }.toDouble() / letters
    }

    companion object {
        /** Typical ascender and descender of a Latin font, as a share of its size. */
        const val ASCENT = 0.78f
        const val DESCENT = 0.22f
    }
}

/**
 * Rebuilds lines from the glyphs of a page. PDFs place glyphs in drawing order, which is not
 * always reading order, so lines come from geometry: a shared baseline, then horizontal gaps.
 */
object LineBuilder {
    /** Baselines closer than this share of the font size belong to the same line. */
    private const val BASELINE_TOLERANCE = 0.2f

    /** A gap wider than this share of the font size separates two words. */
    private const val WORD_GAP = 0.15f

    /** A gap wider than this separates columns, table cells or a margin note. */
    private const val COLUMN_GAP = 1.0f

    private class Placed(val glyph: Glyph, val isRaised: Boolean)

    private class Cluster(val glyphs: MutableList<Glyph>) {
        val baseline: Float get() = glyphs.map { it.baseline }.median()
        val size: Float get() = glyphs.filterNot { it.text.isBlank() }.map { it.size }.ifEmpty { glyphs.map { it.size } }.median()
        val x0: Float get() = glyphs.minOf { it.x }
        val x1: Float get() = glyphs.maxOf { it.x + it.width }
        val letters: Int get() = glyphs.count { it.text.isNotBlank() }
    }

    fun lines(glyphs: List<Glyph>): List<TextLine> {
        val clusters = clusterByBaseline(glyphs.filter { it.text.isNotEmpty() && it.size in 1f..400f })
        val raised = mutableSetOf<Glyph>()
        val merged = mergeRaised(clusters, raised)
        return merged
            .flatMap { cluster -> split(cluster.glyphs.map { Placed(it, it in raised) }) }
            .sortedWith(compareBy({ it.baseline }, { it.x0 }))
    }

    /**
     * Glyphs of very different sizes never share a line, even on one baseline: a drop cap sits on the
     * baseline of a later line of its paragraph, and a title in the margin on that of a line of text.
     */
    private fun clusterByBaseline(glyphs: List<Glyph>): MutableList<Cluster> {
        val clusters = mutableListOf<Cluster>()
        for (glyph in glyphs.sortedBy { it.baseline }) {
            val cluster = clusters.asReversed()
                .takeWhile { glyph.baseline - it.glyphs.first().baseline <= BASELINE_TOLERANCE * MAX_SIZE_RATIO * glyph.size }
                .firstOrNull { cluster ->
                    val reference = cluster.glyphs.first()
                    glyph.baseline - reference.baseline <= BASELINE_TOLERANCE * maxOf(glyph.size, reference.size) &&
                        (glyph.text.isBlank() || maxOf(glyph.size, reference.size) <= MAX_SIZE_RATIO * minOf(glyph.size, reference.size))
                }
            if (cluster != null) cluster.glyphs += glyph else clusters += Cluster(mutableListOf(glyph))
        }
        clusters.sortBy { it.baseline }
        return clusters
    }

    private const val MAX_SIZE_RATIO = 1.3f

    /** Superscripts and subscripts sit on their own baseline; they join the line they decorate. */
    private fun mergeRaised(clusters: MutableList<Cluster>, raised: MutableSet<Glyph>): List<Cluster> {
        var i = 0
        while (i < clusters.size) {
            val small = clusters[i]
            val host = if (small.letters <= MAX_SCRIPT_LETTERS) hostOf(small, clusters, i) else null
            if (host == null) {
                i++
                continue
            }
            if (host.baseline > small.baseline) raised += small.glyphs.filterNot { it.text.isBlank() }
            host.glyphs += small.glyphs
            clusters.removeAt(i)
        }
        return clusters
    }

    private const val MAX_SCRIPT_LETTERS = 6

    private fun hostOf(small: Cluster, clusters: List<Cluster>, index: Int): Cluster? =
        listOfNotNull(clusters.getOrNull(index + 1), clusters.getOrNull(index - 1)).firstOrNull { host ->
            val shift = host.baseline - small.baseline
            small.size < 0.85f * host.size &&
                shift in -0.35f * host.size..0.6f * host.size &&
                small.x0 >= host.x0 - host.size && small.x1 <= host.x1 + host.size
        }

    private fun split(glyphs: List<Placed>): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        var current = mutableListOf<Placed>()
        var end = Float.NEGATIVE_INFINITY
        for (placed in glyphs.sortedBy { it.glyph.x }) {
            val glyph = placed.glyph
            if (glyph.text.isBlank()) {
                if (current.isNotEmpty()) current += placed
                continue
            }
            if (current.isNotEmpty() && glyph.x - end > COLUMN_GAP * glyph.size) {
                lineOf(current)?.let(lines::add)
                current = mutableListOf()
            }
            current += placed
            end = maxOf(end, glyph.x + glyph.width)
        }
        lineOf(current)?.let(lines::add)
        return lines
    }

    private fun lineOf(placed: List<Placed>): TextLine? {
        val printed = placed.filterNot { it.glyph.text.isBlank() }
        if (printed.isEmpty()) return null
        val body = printed.filterNot { it.isRaised }.ifEmpty { printed }
        val runs = mutableListOf<Run>()
        var end = Float.NEGATIVE_INFINITY
        var isSpacePending = false
        for (item in placed) {
            val glyph = item.glyph
            if (glyph.text.isBlank()) {
                isSpacePending = true
                continue
            }
            val gap = glyph.x - end
            val separator = if (runs.isNotEmpty() && (isSpacePending || gap > WORD_GAP * glyph.size)) " " else ""
            val last = runs.lastOrNull()
            if (last != null && last.isBold == glyph.isBold && last.isItalic == glyph.isItalic && last.isRaised == item.isRaised) {
                runs[runs.lastIndex] = last.copy(text = last.text + separator + glyph.text)
            } else {
                if (separator.isNotEmpty() && last != null) runs[runs.lastIndex] = last.copy(text = last.text + separator)
                runs += Run(glyph.text, glyph.isBold, glyph.isItalic, item.isRaised)
            }
            end = maxOf(end, glyph.x + glyph.width)
            isSpacePending = false
        }
        return TextLine(
            runs = runs,
            x0 = printed.minOf { it.glyph.x },
            x1 = printed.maxOf { it.glyph.x + it.glyph.width },
            baseline = body.map { it.glyph.baseline }.median(),
            size = body.map { it.glyph.size }.median(),
        )
    }
}

internal fun List<Float>.median(): Float {
    val sorted = sorted()
    return sorted[sorted.size / 2]
}
