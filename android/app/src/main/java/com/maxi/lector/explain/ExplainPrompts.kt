package com.maxi.lector.explain

/** What the reader selected, with the text around it as the book shows it. */
data class Passage(
    val bookTitle: String,
    val author: String?,
    val chapter: String?,
    val before: String,
    val selected: String,
    val after: String,
) {
    val isTerm: Boolean get() = selected.trim().split(Regex("\\s+")).size <= 6
}

enum class ExplainMode(val label: String) {
    Explain("Explicar"),
    Simpler("Más simple"),
    Example("Un ejemplo"),
    Translate("Traducir"),
}

data class Exchange(val question: String, val answer: String)

/**
 * Gemini Nano has a small context window, so the passage is trimmed to the sentences around the
 * selection and earlier turns are restated in the prompt instead of kept in a session.
 */
object ExplainPrompts {
    private const val CONTEXT_CHARS = 700

    /** Readium joins the text of consecutive paragraphs with no separator. */
    private val BLOCK_JOIN = Regex("(?<=[.?!:;])(?=[A-Z])")

    fun forNano(passage: Passage, mode: ExplainMode, history: List<Exchange>, followUp: String?): String =
        buildString {
            appendLine("Sos un tutor paciente que ayuda a una persona a estudiar un libro. Respondé siempre en español rioplatense, claro y sin relleno.")
            appendLine()
            appendLine(bookLine(passage))
            appendLine("Fragmento (lo seleccionado está entre ⟦ ⟧):")
            appendLine("\"${excerpt(passage)}\"")
            appendLine()
            history.forEach {
                appendLine("Pregunta anterior: ${it.question}")
                appendLine("Tu respuesta anterior: ${it.answer}")
                appendLine()
            }
            appendLine(followUp ?: instruction(passage, mode))
        }

    fun question(passage: Passage, mode: ExplainMode): String = instruction(passage, mode)

    fun forClaude(passage: Passage, history: List<Exchange>, followUp: String?): String = buildString {
        appendLine("Estoy leyendo ${bookLine(passage).removePrefix("Libro: ")}")
        appendLine()
        appendLine("Fragmento (lo que marqué está en negrita):")
        appendLine("> ${excerpt(passage, open = "**", close = "**")}")
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

    private fun instruction(passage: Passage, mode: ExplainMode): String {
        val selected = passage.selected.trim()
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

    private fun bookLine(passage: Passage): String = buildString {
        append("Libro: «${passage.bookTitle}»")
        passage.author?.let { append(" de $it") }
        passage.chapter?.let { append(", capítulo «$it»") }
        append('.')
    }

    private fun excerpt(passage: Passage, open: String = "⟦", close: String = "⟧"): String {
        val before = passage.before.takeLast(CONTEXT_CHARS).substringAfter(". ", passage.before.takeLast(CONTEXT_CHARS))
        val after = passage.after.take(CONTEXT_CHARS).let { text ->
            val end = text.lastIndexOf(". ")
            if (end > 0) text.substring(0, end + 1) else text
        }
        return "…$before$open${passage.selected}$close$after…"
            .replace(BLOCK_JOIN, " ")
            .replace(Regex("\\s+"), " ")
    }
}
