package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainPromptsTest {
    private fun passage(
        selected: String = "bid/ask spread",
        before: String = "Dealers quote two prices. The difference between them is the ",
        after: String = ". It compensates dealers for the risks they take. Traders pay it when they demand liquidity.",
    ) = Passage(
        bookTitle = "Trading and Exchanges",
        author = "Larry Harris",
        language = "en",
        chapter = "14. Bid/Ask Spreads",
        before = before,
        selected = selected,
        after = after,
    )

    private fun explainPrompt(passage: Passage = passage(), history: List<Exchange> = emptyList(), findings: List<Finding> = emptyList()) =
        ExplainPrompts.answer(passage, history, ExplainPrompts.instruction(passage, ExplainMode.Explain), findings)

    @Test
    fun `marks the selection inside the excerpt sent to Gemini Nano`() {
        assertTrue(explainPrompt().contains("between them is the ⟦bid/ask spread⟧. It compensates dealers"))
    }

    @Test
    fun `tells the model which language the book is written in`() {
        assertTrue(explainPrompt().contains("Libro: «Trading and Exchanges» de Larry Harris, capítulo «14. Bid/Ask Spreads», escrito en inglés."))
    }

    @Test
    fun `keeps only the sentences nearest the selection when the context is long`() {
        val farAway = "This sentence opens the chapter and is far from the selection. "
        val prompt = explainPrompt(passage(before = farAway + "x".repeat(800) + ". The difference between them is the "))

        assertFalse(prompt.contains("opens the chapter"))
        assertTrue(prompt.contains("The difference between them is the ⟦bid/ask spread⟧"))
    }

    @Test
    fun `asks for a definition when the selection is a short term`() {
        assertTrue(explainPrompt().contains("Explicá qué significa «bid/ask spread» en este contexto"))
    }

    @Test
    fun `asks for a paraphrase when the selection is a whole passage`() {
        val prompt = explainPrompt(passage(selected = "It compensates dealers for the risks they take when they trade with informed traders"))

        assertTrue(prompt.contains("Explicá con tus palabras qué quiere decir lo seleccionado"))
    }

    @Test
    fun `restates the conversation so a follow-up keeps its context`() {
        val history = listOf(Exchange("Explicámelo", "Es la diferencia entre el precio de compra y el de venta."))
        val prompt = ExplainPrompts.answer(passage(), history, "¿Quién lo paga?", findings = emptyList())

        assertTrue(prompt.contains("Lector: Explicámelo\nGlosa: Es la diferencia entre el precio de compra y el de venta."))
        assertTrue(prompt.contains("Pedido del lector: ¿Quién lo paga?"))
    }

    @Test
    fun `numbers what was found so the answer can cite it`() {
        val findings = listOf(
            Finding(Source(SourceKind.Book, "14. Bid/Ask Spreads", locatorJson = "{}"), "…dealers earn the spread…"),
            Finding(Source(SourceKind.Wikipedia, "Bid–ask spread"), "The bid–ask spread is the difference…"),
        )

        val prompt = explainPrompt(findings = findings)

        assertTrue(prompt.contains("[1] Libro, 14. Bid/Ask Spreads: …dealers earn the spread…\n[2] Wikipedia, Bid–ask spread: The bid–ask spread"))
        assertTrue(prompt.contains("citá el número entre corchetes"))
    }

    @Test
    fun `drops the findings that do not fit in the model's context`() {
        val long = { title: String -> Finding(Source(SourceKind.Web, title), "x".repeat(2_000)) }

        val fitting = ExplainPrompts.fitFindings(listOf("first", "second", "third", "fourth", "fifth").map(long))

        assertEquals(listOf("first", "second", "third", "fourth"), fitting.map { it.source.title })
        assertEquals(1_000, fitting.first().text.length)
    }

    @Test
    fun `offers the planner only the tools it can use`() {
        val plan = ExplainPrompts.plan(passage(), emptyList(), "¿Qué es un especialista?", known = emptyList(), tools = listOf(SourceKind.Book, SourceKind.Wikipedia))

        assertTrue(plan.contains("LIBRO: <palabras exactas>"))
        assertTrue(plan.contains("WIKIPEDIA: <tema>"))
        assertFalse(plan.contains("WEB:"))
        assertFalse(plan.contains("DICCIONARIO"))
        assertFalse(plan.contains("CALCULAR"))
    }

    @Test
    fun `separates paragraphs that the reader hands over glued together`() {
        assertTrue(explainPrompt(passage(before = "Dealers quote two prices.The difference between them is the ")).contains("Dealers quote two prices. The difference"))
    }

    @Test
    fun `tells Claude what the local model already answered`() {
        val history = listOf(Exchange("Explicámelo", "Es la diferencia entre compra y venta."))
        val message = ExplainPrompts.forClaude(passage(), history, followUp = null)

        assertTrue(message.contains("Estoy leyendo «Trading and Exchanges» de Larry Harris, capítulo «14. Bid/Ask Spreads», escrito en inglés."))
        assertTrue(message.contains("**bid/ask spread**"))
        assertTrue(message.contains("Respuesta: Es la diferencia entre compra y venta."))
    }
}
