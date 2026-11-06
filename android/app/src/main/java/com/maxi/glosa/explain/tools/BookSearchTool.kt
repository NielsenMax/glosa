package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool
import com.maxi.glosa.explain.ToolException
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

/** Finds where the book uses some words; the earliest hits are usually where the author defines a term. */
class BookSearchTool(private val publication: Publication) : Tool {
    override val kind = SourceKind.Book

    override suspend fun run(query: String, context: LookupContext): List<Finding> {
        val phrase = query.trim()
        val longestWord = phrase.split(Regex("\\s+")).maxBy { it.length }
        val hits = search(phrase).ifEmpty { if (longestWord != phrase) search(longestWord) else emptyList() }
        return hits.map { hit ->
            Finding(
                source = Source(SourceKind.Book, title = hit.title ?: "sin capítulo", locatorJson = hit.toJSON().toString()),
                text = snippet(hit),
            )
        }
    }

    /** Readium matches the exact phrase, ignoring case. */
    private suspend fun search(phrase: String): List<Locator> {
        val iterator = publication.search(phrase) ?: throw ToolException("este libro no permite buscar texto")
        try {
            val hits = mutableListOf<Locator>()
            while (hits.size < MAX_HITS) {
                val page = iterator.next().getOrElse { throw ToolException("la búsqueda en el libro falló: ${it.message}") } ?: break
                hits += page.locators
            }
            return hits.take(MAX_HITS)
        } finally {
            iterator.close()
        }
    }

    private fun snippet(hit: Locator): String =
        ("…" + hit.text.before.orEmpty().takeLast(160) + hit.text.highlight.orEmpty() + hit.text.after.orEmpty().take(240) + "…")
            .replace(Regex("\\s+"), " ")

    private companion object {
        const val MAX_HITS = 3
    }
}
