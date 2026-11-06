package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.ExplanationDao
import com.maxi.glosa.data.HighlightDao
import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool

/** Searches what the reader already wrote or asked about in this book, ranked by how many query words match. */
class NotesTool(
    private val bookId: Long,
    private val highlights: HighlightDao,
    private val explanations: ExplanationDao,
) : Tool {
    override val kind = SourceKind.Notes

    override suspend fun run(query: String, context: LookupContext): List<Finding> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val fromHighlights = highlights.listForBook(bookId).map { highlight ->
            Finding(
                source = Source(SourceKind.Notes, title = "subrayado en ${highlight.chapter ?: "el libro"}", locatorJson = highlight.locatorJson),
                text = listOfNotNull("«${highlight.text}»", highlight.note?.let { "Nota: $it" }).joinToString(" "),
            )
        }
        val fromExplanations = explanations.listForBook(bookId).flatMap { thread ->
            thread.turns.map { turn ->
                Finding(
                    source = Source(
                        SourceKind.Notes,
                        title = "explicación de «${thread.explanation.selectedText.take(40)}»",
                        locatorJson = thread.explanation.locatorJson,
                    ),
                    text = "${turn.question}: ${turn.answer}",
                )
            }
        }
        return (fromHighlights + fromExplanations)
            .map { it to words(it.text).count(words::contains) }
            .filter { (_, matches) -> matches > 0 }
            .sortedByDescending { (_, matches) -> matches }
            .take(MAX_RESULTS)
            .map { (finding, _) -> finding }
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private companion object {
        const val MAX_RESULTS = 3
    }
}
