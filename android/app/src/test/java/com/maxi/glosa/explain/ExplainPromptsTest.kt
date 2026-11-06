package com.maxi.glosa.explain

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
        chapter = "14. Bid/Ask Spreads",
        before = before,
        selected = selected,
        after = after,
    )

    @Test
    fun `marks the selection inside the excerpt sent to Gemini Nano`() {
        val prompt = ExplainPrompts.forNano(passage(), ExplainMode.Explain, history = emptyList(), followUp = null)

        assertTrue(prompt.contains("between them is the ⟦bid/ask spread⟧. It compensates dealers"))
    }

    @Test
    fun `keeps only the sentences nearest the selection when the context is long`() {
        val farAway = "This sentence opens the chapter and is far from the selection. "
        val prompt = ExplainPrompts.forNano(
            passage(before = farAway + "x".repeat(800) + ". The difference between them is the "),
            ExplainMode.Explain,
            history = emptyList(),
            followUp = null,
        )

        assertFalse(prompt.contains("opens the chapter"))
        assertTrue(prompt.contains("The difference between them is the ⟦bid/ask spread⟧"))
    }

    @Test
    fun `asks for a definition when the selection is a short term`() {
        val prompt = ExplainPrompts.forNano(passage(), ExplainMode.Explain, history = emptyList(), followUp = null)

        assertTrue(prompt.contains("Explicá qué significa «bid/ask spread» en este contexto"))
    }

    @Test
    fun `asks for a paraphrase when the selection is a whole passage`() {
        val prompt = ExplainPrompts.forNano(
            passage(selected = "It compensates dealers for the risks they take when they trade with informed traders"),
            ExplainMode.Explain,
            history = emptyList(),
            followUp = null,
        )

        assertTrue(prompt.contains("Explicá con tus palabras qué quiere decir lo seleccionado"))
    }

    @Test
    fun `restates earlier answers so a follow-up keeps its context`() {
        val history = listOf(Exchange("¿Qué es?", "Es la diferencia entre el precio de compra y el de venta."))
        val prompt = ExplainPrompts.forNano(passage(), ExplainMode.Explain, history, followUp = "¿Quién lo paga?")

        assertTrue(prompt.contains("Tu respuesta anterior: Es la diferencia entre el precio de compra y el de venta."))
        assertTrue(prompt.trimEnd().endsWith("¿Quién lo paga?"))
    }

    @Test
    fun `separates paragraphs that the reader hands over glued together`() {
        val prompt = ExplainPrompts.forNano(
            passage(before = "Dealers quote two prices.The difference between them is the "),
            ExplainMode.Explain,
            history = emptyList(),
            followUp = null,
        )

        assertTrue(prompt.contains("Dealers quote two prices. The difference"))
    }

    @Test
    fun `tells Claude what the local model already answered`() {
        val history = listOf(Exchange("¿Qué es?", "Es la diferencia entre compra y venta."))
        val message = ExplainPrompts.forClaude(passage(), history, followUp = null)

        assertTrue(message.contains("Estoy leyendo «Trading and Exchanges» de Larry Harris, capítulo «14. Bid/Ask Spreads»."))
        assertTrue(message.contains("**bid/ask spread**"))
        assertTrue(message.contains("Respuesta: Es la diferencia entre compra y venta."))
    }
}
