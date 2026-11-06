package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Wiktionary, two ways: the English Wiktionary's senses of the word in the book's language, keeping the
 * few that fit the passage, and the Spanish Wiktionary's short Spanish glosses for it.
 */
class DictionaryTool(
    private val http: HttpJson,
    private val bookLanguage: String,
    private val englishWiktionary: String = "https://en.wiktionary.org",
    private val spanishWiktionary: String = "https://es.wiktionary.org",
) : Tool {
    override val kind = SourceKind.Dictionary

    override suspend fun run(query: String, context: LookupContext): List<Finding> = coroutineScope {
        val term = query.trim()
        val glosses = async { spanishGlosses(term) }
        val senses = async { senses(term, context) }
        listOfNotNull(glosses.await(), senses.await())
    }

    private suspend fun senses(term: String, context: LookupContext): Finding? {
        val entry = definitionsOf(term) ?: term.lowercase().takeIf { it != term }?.let { definitionsOf(it) } ?: return null
        val senses = entry.optJSONArray(bookLanguage) ?: return null
        val all = (0 until senses.length()).map(senses::getJSONObject).flatMap { partOfSpeech ->
            val definitions = partOfSpeech.getJSONArray("definitions")
            (0 until definitions.length())
                .map { Sense(partOfSpeech.getString("partOfSpeech"), plainText(definitions.getJSONObject(it).getString("definition"))) }
                .filter { it.definition.isNotEmpty() }
        }
        val fitting = Relevance.mostRelevant(all, Relevance.words(context.passage), count = SENSES) { it.definition }
            .ifEmpty { all.take(SENSES) }
        if (fitting.isEmpty()) return null
        return Finding(
            source = Source(SourceKind.Dictionary, title = "Wiktionary: $term", url = "$englishWiktionary/wiki/${path(term)}"),
            text = "$term — " + fitting.joinToString(" / ") { "${it.partOfSpeech}: ${it.definition}" },
        )
    }

    private suspend fun definitionsOf(term: String): JSONObject? = try {
        http.get("$englishWiktionary/api/rest_v1/page/definition/${path(term)}")
    } catch (e: HttpStatusException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun spanishGlosses(term: String): Finding? {
        val url = "$spanishWiktionary/w/api.php?action=query&format=json&formatversion=2&prop=extracts&explaintext=1&redirects=1" +
            "&titles=" + URLEncoder.encode(term.lowercase(), "UTF-8")
        val page = http.get(url).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val glosses = SpanishEntry.glosses(page.optString("extract"), languageSection())
        if (glosses.isEmpty()) return null
        return Finding(
            source = Source(SourceKind.Dictionary, title = "Wikcionario: ${page.getString("title")}", url = "$spanishWiktionary/wiki/${path(term.lowercase())}"),
            text = "${page.getString("title")} en español — " + glosses.entries.joinToString(". ") { (partOfSpeech, meanings) ->
                "$partOfSpeech: ${meanings.joinToString("; ")}"
            },
        )
    }

    /** The Spanish Wiktionary titles each language's section with its Spanish name, capitalized: "Inglés". */
    private fun languageSection(): String =
        Locale.forLanguageTag(bookLanguage).getDisplayLanguage(SPANISH).replaceFirstChar { it.titlecase(SPANISH) }

    private fun path(term: String) = URLEncoder.encode(term.replace(' ', '_'), "UTF-8")

    private data class Sense(val partOfSpeech: String, val definition: String)

    /** Reads the glosses out of a Spanish Wiktionary extract: numbered lines under each part-of-speech heading. */
    private object SpanishEntry {
        private val heading = Regex("^(=+)\\s*(.+?)\\s*=+$")
        private val label = Regex("^[\\p{Lu}][\\p{L} ]{1,30}:")
        private val notMeanings = setOf(
            "Etimología", "Pronunciación y escritura", "Pronunciación", "Conjugación", "Traducciones", "Véase también",
            "Información adicional", "Locuciones", "Formas flexivas", "Referencias y notas",
        )

        fun glosses(extract: String, language: String): Map<String, List<String>> {
            val glosses = linkedMapOf<String, MutableList<String>>()
            var inLanguage = false
            var partOfSpeech: String? = null
            extract.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val match = heading.find(line)
                when {
                    match != null && match.groupValues[1].length == 2 -> {
                        inLanguage = match.groupValues[2] == language
                        partOfSpeech = null
                    }
                    match != null -> partOfSpeech = match.groupValues[2].takeIf { it !in notMeanings }
                    !inLanguage || partOfSpeech == null -> Unit
                    line.all(Char::isDigit) || '¦' in line || label.containsMatchIn(line) -> Unit
                    else -> glosses.getOrPut(partOfSpeech!!) { mutableListOf() }.takeIf { it.size < GLOSSES }?.add(line.trimEnd('.'))
                }
            }
            return glosses
        }

        private const val GLOSSES = 4
    }

    private companion object {
        const val SENSES = 4
        val SPANISH: Locale = Locale.forLanguageTag("es")
        private val tags = Regex("<style[^>]*>.*?</style>|<[^>]+>", RegexOption.DOT_MATCHES_ALL)

        fun plainText(html: String): String = html.replace(tags, "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
