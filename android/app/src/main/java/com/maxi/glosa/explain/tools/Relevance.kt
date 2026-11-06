package com.maxi.glosa.explain.tools

/**
 * Picks, among several senses or paragraphs, the ones that share the most words with what the reader
 * is looking at (simplified Lesk). Very common English and Spanish words are ignored, and two words
 * match when, without their inflection ending, one starts with the other: "trading" meets "trade".
 */
object Relevance {
    private val stopwords = setOf(
        "the", "and", "for", "that", "with", "from", "this", "which", "when", "their", "they", "there", "what",
        "about", "into", "more", "than", "such", "other", "have", "has", "had", "are", "was", "were", "been",
        "its", "not", "but", "can", "will", "would", "also", "who", "whom", "them", "these", "those", "any",
        "que", "los", "las", "del", "por", "para", "con", "una", "uno", "como", "más", "pero", "sus", "esta",
        "este", "esto", "eso", "qué", "cómo", "cuál", "quién", "son", "está", "hay", "sobre", "entre", "sin",
    )

    fun words(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in stopwords }
        .map(::stem)
        .toSet()

    /** The [count] best-matching candidates, in their original order; candidates sharing no word are left out. */
    fun <T> mostRelevant(candidates: List<T>, context: Set<String>, count: Int, text: (T) -> String): List<T> =
        candidates.withIndex()
            .map { (index, candidate) -> Triple(index, candidate, words(text(candidate)).count { it.matchesAny(context) }) }
            .filter { (_, _, score) -> score > 0 }
            .sortedWith(compareByDescending<Triple<Int, T, Int>> { it.third }.thenBy { it.first })
            .take(count)
            .sortedBy { it.first }
            .map { it.second }

    private fun String.matchesAny(context: Set<String>): Boolean = context.any { other ->
        if (length <= other.length) other.startsWith(this) else startsWith(other)
    }

    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }
}
