package com.maxi.glosa.convert

import kotlin.math.abs
import kotlin.math.roundToInt

class PageLines(val index: Int, val width: Float, val height: Float, val lines: List<TextLine>)

/** What the whole book says about itself, which no single page can tell. */
class BookStats(
    val bodySize: Float,
    /** Distance between the baselines of two lines of body text. */
    val leading: Float,
    /** Font sizes of headings, largest first: index 0 is the top level. */
    val headingSizes: List<Float>,
    /**
     * How often each word, in lowercase, stands on its own. A line's first word in lowercase is
     * left out, unless it is hyphenated itself: it may be the end of a word broken on the line
     * before, like the "ting" of "set-ting".
     */
    val vocabulary: Map<String, Int>,
    /** ISO 639-1 code, or null when the text is in none of the languages [LanguageGuess] knows. */
    val language: String?,
) {
    fun isBodySize(size: Float): Boolean = abs(size - bodySize) <= 0.15f * bodySize

    /** The heading level of text at [size], from 1, or null when it is not set larger than the body. */
    fun headingLevel(size: Float): Int? {
        if (size < HEADING_SCALE * bodySize) return null
        val tier = headingSizes.indexOfFirst { abs(it - size) <= SAME_SIZE }.takeIf { it >= 0 }
            ?: headingSizes.indexOfFirst { it < size }.takeIf { it >= 0 }
            ?: headingSizes.size
        return minOf(tier + 1, MAX_SIZED_LEVEL)
    }

    /** Bold or capitalized lines at body size head the smallest sections. */
    val runInLevel: Int get() = minOf(headingSizes.size + 1, MAX_SIZED_LEVEL + 1)

    companion object {
        const val HEADING_SCALE = 1.15f
        private const val SAME_SIZE = 0.75f
        private const val MAX_SIZED_LEVEL = 4

        fun of(pages: List<PageLines>): BookStats {
            val lines = pages.flatMap { it.lines }
            val bodySize = lines
                .groupBy { (it.size * 2).roundToInt() / 2f }
                .maxByOrNull { (_, group) -> group.sumOf { it.text.length } }
                ?.key ?: 10f
            val vocabulary = vocabularyOf(lines)
            return BookStats(
                bodySize = bodySize,
                leading = leadingOf(pages, bodySize),
                headingSizes = headingSizesOf(lines, bodySize),
                vocabulary = vocabulary,
                language = LanguageGuess.of(vocabulary),
            )
        }

        private fun leadingOf(pages: List<PageLines>, bodySize: Float): Float {
            val gaps = pages.flatMap { page ->
                page.lines
                    .filter { abs(it.size - bodySize) <= 0.15f * bodySize }
                    .sortedBy { it.baseline }
                    .zipWithNext { a, b -> b.baseline - a.baseline }
                    .filter { it in 0.9f * bodySize..2.2f * bodySize }
            }
            return gaps.ifEmpty { listOf(1.2f * bodySize) }.median()
        }

        private fun headingSizesOf(lines: List<TextLine>, bodySize: Float): List<Float> {
            val sizes = lines
                .filter { it.size >= HEADING_SCALE * bodySize && it.text.count(Char::isLetter) >= 2 && it.text.length <= 150 }
                .map { (it.size * 2).roundToInt() / 2f }
                .distinct()
                .sortedDescending()
            val tiers = mutableListOf<Float>()
            for (size in sizes) {
                if (tiers.isEmpty() || tiers.last() - size > SAME_SIZE) tiers += size
            }
            return tiers
        }

        private val WORD_EDGES = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}-]+$")

        private fun vocabularyOf(lines: List<TextLine>): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (line in lines) {
                for ((index, raw) in line.text.split(' ').withIndex()) {
                    if (index == 0 && raw.firstOrNull()?.isLowerCase() == true && '-' !in raw) continue
                    val word = raw.replace(WORD_EDGES, "").lowercase()
                    if (word.isNotEmpty() && !word.endsWith('-')) counts[word] = (counts[word] ?: 0) + 1
                }
            }
            return counts
        }
    }
}

/**
 * Page headers and footers repeat from page to page, either word for word or in the same spot set
 * apart from the text; dropping them keeps them out of the running text.
 */
object RunningHeads {
    private val DIGITS = Regex("\\d+")
    private val ROMAN = Regex("^[ivxlcdm]+$")
    private val NOT_WORD = Regex("[^\\p{L}#]+")

    /** Baselines are compared in steps of this many points, which absorbs a scan's slight skew. */
    private const val SPOT = 3f

    fun strip(pages: List<PageLines>, stats: BookStats): List<PageLines> {
        val candidates = pages.associate { page -> page.index to candidatesOf(page, stats) }
        val pagesPerKey = candidates.values
            .flatMap { lines -> lines.map { keyOf(it.line) }.distinct() }
            .groupingBy { it }
            .eachCount()
        val pagesPerSpot = candidates.values
            .flatMap { lines -> lines.filter { it.isApart }.map { spotOf(it.line) }.distinct() }
            .groupingBy { it }
            .eachCount()
        val isRecurring = { count: Int? -> pages.size >= 5 && (count ?: 0) >= maxOf(3, pages.size / 4) }
        return pages.map { page ->
            val dropped = candidates.getValue(page.index).filter { candidate ->
                val key = keyOf(candidate.line)
                val spot = spotOf(candidate.line)
                key == "#" ||
                    (pages.size >= 5 && (pagesPerKey[key] ?: 0) >= 3) ||
                    (candidate.isApart && (spot - 1..spot + 1).any { isRecurring(pagesPerSpot[it]) })
            }.map { it.line }.toSet()
            PageLines(page.index, page.width, page.height, page.lines.filterNot { it in dropped })
        }
    }

    private class Candidate(val line: TextLine, val isApart: Boolean)

    private fun candidatesOf(page: PageLines, stats: BookStats): List<Candidate> {
        val byBaseline = page.lines.sortedBy { it.baseline }
        val gap = 1.5f * stats.leading
        val header = byBaseline.take(2).filter { it.top < 0.12f * page.height }.map { line ->
            val below = byBaseline.firstOrNull { it.baseline > line.baseline + 1 }
            Candidate(line, isApart = below == null || below.baseline - line.baseline > gap)
        }
        val footer = byBaseline.takeLast(2).filter { it.bottom > 0.88f * page.height }.map { line ->
            val above = byBaseline.lastOrNull { it.baseline < line.baseline - 1 }
            Candidate(line, isApart = above == null || line.baseline - above.baseline > gap)
        }
        return (header + footer).distinctBy { it.line }.filter { it.line.size < BookStats.HEADING_SCALE * stats.bodySize }
    }

    private fun spotOf(line: TextLine): Int = (line.baseline / SPOT).toInt()

    private fun keyOf(line: TextLine): String {
        val key = line.text.lowercase().replace(DIGITS, "#").replace(NOT_WORD, " ").trim()
        return if (ROMAN.matches(key)) "#" else key.replace(Regex("\\s+"), " ")
    }
}

/** Most PDFs do not declare their language; their commonest words give it away. */
object LanguageGuess {
    private val markers = mapOf(
        "en" to listOf("the", "and", "of", "to", "is", "that", "with", "for", "this", "are"),
        "es" to listOf("de", "la", "que", "el", "los", "las", "del", "por", "una", "para"),
    )

    /** In running text these ten words make up well over a tenth of all words. */
    private const val MIN_SHARE = 0.05

    fun of(vocabulary: Map<String, Int>): String? {
        val total = vocabulary.values.sum().takeIf { it > 0 } ?: return null
        val (language, hits) = markers.mapValues { (_, words) -> words.sumOf { vocabulary[it] ?: 0 } }.maxBy { it.value }
        return language.takeIf { hits >= MIN_SHARE * total }
    }
}
