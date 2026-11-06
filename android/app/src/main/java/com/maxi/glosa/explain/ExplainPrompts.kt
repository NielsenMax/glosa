package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import java.util.Locale

/** What the reader selected, with the text around it as the book shows it. */
data class Passage(
    val bookTitle: String,
    val author: String?,
    /** BCP 47 tag of the book's language, as its metadata declares it. */
    val language: String?,
    val chapter: String?,
    val before: String,
    val selected: String,
    val after: String,
) {
    val isTerm: Boolean get() = selected.trim().split(Regex("\\s+")).size <= 6
}

enum class ExplainMode(val request: String) {
    Explain("Explicámelo"),
    Simpler("Más simple"),
    Example("Dame un ejemplo"),
    Translate("Traducilo"),
}

data class Exchange(val question: String, val answer: String, val sources: List<Source> = emptyList())

/**
 * Gemini Nano reads at most about 4000 tokens, so every part of a prompt has a character budget:
 * the passage is trimmed to the sentences around the selection, only the latest turns are restated
 * and findings are cut to fit.
 */
object ExplainPrompts {
    private const val CONTEXT_CHARS = 700
    private const val PLAN_CONTEXT_CHARS = 300
    private const val SELECTION_CHARS = 1_500
    private const val HISTORY_TURNS = 3
    private const val HISTORY_ANSWER_CHARS = 600
    private const val FINDING_CHARS = 1_000
    private const val FINDINGS_CHARS = 4_000

    /** Readium joins the text of consecutive paragraphs with no separator. */
    private val BLOCK_JOIN = Regex("(?<=[.?!:;])(?=[A-Z])")

    private const val ROLE =
        "Sos Glosa, un tutor paciente que ayuda a una persona a estudiar un libro. Respondé siempre en español rioplatense, claro y sin relleno."

    private val toolHelp: Map<SourceKind, String> = mapOf(
        SourceKind.Book to "LIBRO: <palabras exactas> — busca dónde aparecen esas palabras en el libro. Escribilas en el idioma del libro.",
        SourceKind.Wikipedia to "WIKIPEDIA: <tema> — trae el artículo de Wikipedia sobre un concepto: el resumen y las partes que tienen que ver con el fragmento. Mejor en el idioma del libro.",
        SourceKind.Dictionary to "DICCIONARIO: <palabra o expresión> — trae de Wiktionary las acepciones que encajan con el fragmento y su traducción al español.",
        SourceKind.Web to "WEB: <consulta> — busca en internet; sirve para datos actuales o temas que no están en Wikipedia.",
        SourceKind.Notes to "NOTAS: <palabras> — busca en los subrayados, notas y explicaciones anteriores del lector.",
        SourceKind.Calculator to "CALCULAR: <cuenta> — resuelve una cuenta con + - * / ^ ( ) y punto decimal.",
    )

    fun instruction(passage: Passage, mode: ExplainMode): String {
        val selected = passage.selected.trim().take(SELECTION_CHARS)
        return when (mode) {
            ExplainMode.Explain -> if (passage.isTerm) {
                "Explicá qué significa «$selected» en este contexto, en 3 a 5 oraciones. Si es un término técnico, dá la definición y por qué importa acá."
            } else {
                "Explicá con tus palabras qué quiere decir lo seleccionado, en 3 a 5 oraciones."
            }
            ExplainMode.Simpler -> "Explicá «$selected» como si yo no supiera nada del tema, en 2 o 3 oraciones muy simples."
            ExplainMode.Example -> "Dame un ejemplo concreto y cotidiano que ilustre «$selected» tal como se usa en este fragmento."
            ExplainMode.Translate -> "Traducí al español lo seleccionado, de forma natural. Si es un término técnico, agregá entre paréntesis cómo se suele decir en español."
        }
    }

    /** Asks the model which lookups would help, in the line format [ToolCallParser] reads. */
    fun plan(
        passage: Passage,
        history: List<Exchange>,
        request: String,
        known: List<Finding>,
        tools: Collection<SourceKind>,
    ): String = buildString {
        appendLine("Sos el planificador de Glosa, un asistente que ayuda a estudiar un libro. No respondas la pregunta: decidí qué buscar para poder responderla bien.")
        appendLine()
        appendLine("Herramientas:")
        toolHelp.filterKeys { it in tools }.values.forEach { appendLine(it) }
        appendLine()
        appendLine(bookLine(passage))
        appendLine("Fragmento: \"${excerpt(passage, PLAN_CONTEXT_CHARS)}\"")
        conversation(history.takeLast(2), answerChars = 300)
        if (known.isNotEmpty()) {
            appendLine("Ya encontraste: " + known.joinToString("; ") { label(it.source) })
        }
        appendLine("Pregunta del lector: $request")
        appendLine()
        appendLine("Escribí de una a tres líneas con el formato HERRAMIENTA: consulta. Si podés responder bien sin buscar nada, escribí solo RESPONDER.")
        appendLine("Ejemplos:")
        if (SourceKind.Wikipedia in tools) appendLine("¿Qué es un especialista? → WIKIPEDIA: specialist (stock exchange)")
        if (SourceKind.Book in tools) appendLine("¿Dónde más habla el autor de esto? → LIBRO: specialist")
        if (SourceKind.Dictionary in tools) appendLine("¿Qué quiere decir hedge acá? → DICCIONARIO: hedge")
        if (SourceKind.Calculator in tools) appendLine("Si compra a 10.05 y vende a 10, ¿cuánto es el spread en porcentaje? → CALCULAR: (10.05 - 10) / 10 * 100")
        appendLine("Resumilo en una oración → RESPONDER")
    }

    /** The findings that fit in the answer prompt, in the order they are numbered there. */
    fun fitFindings(findings: List<Finding>): List<Finding> {
        var remaining = FINDINGS_CHARS
        val fitting = mutableListOf<Finding>()
        for (finding in findings) {
            val text = finding.text.take(FINDING_CHARS)
            if (text.length > remaining) break
            remaining -= text.length
            fitting += finding.copy(text = text)
        }
        return fitting
    }

    fun answer(passage: Passage, history: List<Exchange>, request: String, findings: List<Finding>): String = buildString {
        appendLine(ROLE)
        appendLine()
        appendLine(bookLine(passage))
        appendLine("Fragmento que el lector está mirando (lo seleccionado está entre ⟦ ⟧):")
        appendLine("\"${excerpt(passage, CONTEXT_CHARS)}\"")
        conversation(history, answerChars = HISTORY_ANSWER_CHARS)
        if (findings.isNotEmpty()) {
            appendLine()
            appendLine("Información que encontraste:")
            findings.forEachIndexed { index, finding ->
                appendLine("[${index + 1}] ${label(finding.source)}: ${oneLine(finding.text)}")
            }
        }
        appendLine()
        appendLine("Pedido del lector: $request")
        appendLine()
        append("Respondé el pedido de forma breve, a partir del fragmento y de la conversación. ")
        if (findings.isNotEmpty()) append("Usá la información encontrada cuando sirva y citá el número entre corchetes, por ejemplo [1]. ")
        appendLine("Si no sabés algo con seguridad, decilo en vez de inventarlo.")
    }

    fun forClaude(passage: Passage, history: List<Exchange>, followUp: String?): String = buildString {
        appendLine("Estoy leyendo ${bookLine(passage).removePrefix("Libro: ")}")
        appendLine()
        appendLine("Fragmento (lo que marqué está en negrita):")
        appendLine("> ${excerpt(passage, CONTEXT_CHARS, open = "**", close = "**")}")
        appendLine()
        if (history.isNotEmpty()) {
            appendLine("Un modelo local (Gemini Nano) ya me respondió esto, pero me quedé corto:")
            history.forEach {
                appendLine("- Pregunté: ${it.question}")
                appendLine("  Respuesta: ${it.answer}")
            }
            appendLine()
        }
        appendLine(
            followUp ?: if (passage.isTerm) {
                "Explicame en profundidad qué significa «${passage.selected.trim()}» en este contexto: la definición precisa, por qué importa en el libro, con qué conceptos se relaciona y un ejemplo concreto."
            } else {
                "Explicame este fragmento en profundidad: qué quiere decir el autor, qué conceptos asume que ya sé y un ejemplo concreto."
            },
        )
    }

    private fun StringBuilder.conversation(history: List<Exchange>, answerChars: Int) {
        val recent = history.takeLast(HISTORY_TURNS)
        if (recent.isEmpty()) return
        appendLine()
        appendLine("Conversación hasta ahora:")
        recent.forEach {
            appendLine("Lector: ${it.question}")
            appendLine("Glosa: ${oneLine(it.answer).take(answerChars)}")
        }
    }

    private fun label(source: Source): String = when (source.kind) {
        SourceKind.Book -> "Libro, ${source.title}"
        SourceKind.Wikipedia -> "Wikipedia, ${source.title}"
        SourceKind.Dictionary -> "Diccionario, ${source.title}"
        SourceKind.Web -> "Web, ${source.title}"
        SourceKind.Notes -> "Notas del lector, ${source.title}"
        SourceKind.Calculator -> "Cuenta"
    }

    private fun bookLine(passage: Passage): String = buildString {
        append("Libro: «${passage.bookTitle}»")
        passage.author?.let { append(" de $it") }
        passage.chapter?.let { append(", capítulo «$it»") }
        languageName(passage.language)?.let { append(", escrito en $it") }
        append('.')
    }

    private fun languageName(tag: String?): String? =
        tag?.let { Locale.forLanguageTag(it).getDisplayLanguage(SPANISH).ifEmpty { null } }

    private val SPANISH = Locale.forLanguageTag("es")

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** The selection with the sentences around it, plain, for tools that rank what they found against it. */
    fun surroundings(passage: Passage): String = excerpt(passage, CONTEXT_CHARS, open = "", close = "")

    private fun excerpt(passage: Passage, contextChars: Int, open: String = "⟦", close: String = "⟧"): String {
        val before = passage.before.takeLast(contextChars).let { it.substringAfter(". ", it) }
        val after = passage.after.take(contextChars).let { text ->
            val end = text.lastIndexOf(". ")
            if (end > 0) text.substring(0, end + 1) else text
        }
        return "…$before$open${passage.selected.take(SELECTION_CHARS)}$close$after…"
            .replace(BLOCK_JOIN, " ")
            .replace(Regex("\\s+"), " ")
    }
}
