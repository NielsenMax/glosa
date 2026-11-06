package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool
import java.net.URLEncoder

/**
 * Looks a topic up in the first Wikipedia, of [languages], that has an article on it: the book's own
 * language first, where its terms are written, then Spanish. Reads the whole article and keeps its
 * introduction plus the paragraphs closest to the passage being read.
 */
class WikipediaTool(
    private val http: HttpJson,
    private val languages: List<String>,
    private val siteUrl: (language: String) -> String = { "https://$it.wikipedia.org" },
) : Tool {
    override val kind = SourceKind.Wikipedia

    override suspend fun run(query: String, context: LookupContext): List<Finding> =
        listOfNotNull(languages.firstNotNullOfOrNull { language -> topArticle(language, query, context) })

    private suspend fun topArticle(language: String, query: String, context: LookupContext): Finding? {
        val url = siteUrl(language) + "/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
            "&generator=search&gsrlimit=1&gsrsearch=" + URLEncoder.encode(query, "UTF-8") +
            "&prop=extracts%7Cinfo&explaintext=1&exsectionformat=wiki&inprop=url"
        val page = http.get(url).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val article = Article.parse(page.optString("extract"))
        if (article.introduction.isEmpty()) return null
        val wanted = Relevance.words("$query ${context.passage} ${context.question}")
        val related = Relevance.mostRelevant(article.paragraphs, wanted, count = RELATED_PARAGRAPHS) { it.text }
        val text = buildString {
            append(clip(article.introduction, INTRODUCTION_CHARS))
            related.forEach { append("\n§ ${it.section}: ").append(clip(it.text, PARAGRAPH_CHARS)) }
        }
        return Finding(
            source = Source(SourceKind.Wikipedia, title = page.getString("title"), url = page.optString("fullurl").ifEmpty { null }),
            text = text,
        )
    }

    private data class Paragraph(val section: String, val text: String)

    /** A plain-text extract with `== Heading ==` lines, as TextExtracts returns it with `exsectionformat=wiki`. */
    private class Article(val introduction: String, val paragraphs: List<Paragraph>) {
        companion object {
            private val heading = Regex("^=+\\s*(.+?)\\s*=+$")

            /** Lists, references and links say nothing about the topic itself. */
            private val backMatter = setOf(
                "See also", "References", "External links", "Notes", "Further reading", "Bibliography", "Sources",
                "Véase también", "Referencias", "Enlaces externos", "Notas", "Bibliografía", "Fuentes",
            )

            fun parse(extract: String): Article {
                val introduction = mutableListOf<String>()
                val paragraphs = mutableListOf<Paragraph>()
                var section: String? = null
                extract.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                    val title = heading.find(line)?.groupValues?.get(1)
                    when {
                        title != null -> section = title
                        section == null -> introduction += line
                        section !in backMatter -> paragraphs += Paragraph(section!!, line)
                    }
                }
                return Article(introduction.joinToString(" "), paragraphs)
            }
        }
    }

    private companion object {
        const val INTRODUCTION_CHARS = 450
        const val PARAGRAPH_CHARS = 260
        const val RELATED_PARAGRAPHS = 2
    }
}
