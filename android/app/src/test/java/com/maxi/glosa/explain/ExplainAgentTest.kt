package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini Nano runs in AICore behind an on-device service that a JVM test cannot reach, so the model is
 * replaced at [LanguageModel], the agent's own seam, by one that replies from a script.
 */
class ExplainAgentTest {
    private val passage = Passage(
        bookTitle = "Trading and Exchanges",
        author = "Larry Harris",
        language = "en",
        chapter = "14. Bid/Ask Spreads",
        before = "Dealers quote two prices. The difference between them is the ",
        selected = "bid/ask spread",
        after = ". It compensates dealers for the risks they take.",
    )
    private val marketMaker = Finding(
        Source(SourceKind.Wikipedia, "Market maker", url = "https://en.wikipedia.org/wiki/Market_maker"),
        "A market maker or liquidity provider is a company or individual that quotes both a buy and a sell price.",
    )
    private val definition = Finding(
        Source(SourceKind.Book, "14. Bid/Ask Spreads", locatorJson = "{}"),
        "…dealers earn the bid/ask spread when they buy at the bid and sell at the ask…",
    )

    private class ScriptedModel(private val plans: List<String> = emptyList(), private val answer: String) : LanguageModel {
        val answerPrompts = mutableListOf<String>()
        private var nextPlan = 0

        override suspend fun complete(prompt: String, maxOutputTokens: Int): String = plans.getOrElse(nextPlan++) { "RESPONDER" }

        override fun stream(prompt: String): Flow<String> {
            answerPrompts += prompt
            return flowOf(*answer.chunked(7).toTypedArray())
        }
    }

    private class PlannerMustNotRun(private val answer: String) : LanguageModel {
        override suspend fun complete(prompt: String, maxOutputTokens: Int): String = throw AssertionError("planner ran: $prompt")
        override fun stream(prompt: String): Flow<String> = flowOf(answer)
    }

    private class StubTool(
        override val kind: SourceKind,
        private val found: Map<String, List<Finding>> = emptyMap(),
        private val failure: String? = null,
        private val hangs: Boolean = false,
    ) : Tool {
        override suspend fun run(query: String, context: LookupContext): List<Finding> {
            if (hangs) awaitCancellation()
            failure?.let { throw ToolException(it) }
            return found[query].orEmpty()
        }
    }

    private fun request(planning: Planning, question: String = "¿Quién gana el spread?", known: List<Finding> = emptyList()) =
        AgentRequest(passage, history = emptyList(), request = question, known = known, planning = planning)

    private fun started(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.ToolStarted>().map { it.call }

    private fun answered(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.Answered>().single()

    @Test
    fun `answers with what the planner asked to look up, citing it`() = runTest {
        val model = ScriptedModel(plans = listOf("WIKIPEDIA: market maker"), answer = "Lo gana el creador de mercado [1].")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Wikipedia, mapOf("market maker" to listOf(marketMaker)))))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertEquals(AgentEvent.Answered("Lo gana el creador de mercado [1].", listOf(marketMaker.source)), answered(events))
        assertTrue(model.answerPrompts.single().contains("[1] Wikipedia, Market maker: A market maker or liquidity provider"))
    }

    @Test
    fun `answers without looking anything up when the planner says RESPONDER`() = runTest {
        val model = ScriptedModel(plans = listOf("RESPONDER"), answer = "El dealer.")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Wikipedia)))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertEquals(emptyList<ToolCall>(), started(events))
        assertFalse(model.answerPrompts.single().contains("Información que encontraste"))
    }

    @Test
    fun `reports a lookup that failed and still answers`() = runTest {
        val model = ScriptedModel(plans = listOf("WIKIPEDIA: market maker"), answer = "El dealer.")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Wikipedia, failure = "no hay conexión a internet")))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertTrue(AgentEvent.ToolFailed(ToolCall(SourceKind.Wikipedia, "market maker"), "no hay conexión a internet") in events)
        assertEquals("El dealer.", answered(events).answer)
    }

    @Test
    fun `gives up on a lookup that takes too long`() = runTest {
        val model = ScriptedModel(plans = listOf("WEB: NYSE specialist"), answer = "No estoy seguro.")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Web, hangs = true)))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertTrue(AgentEvent.ToolFailed(ToolCall(SourceKind.Web, "NYSE specialist"), "tardó demasiado") in events)
        assertEquals("No estoy seguro.", answered(events).answer)
    }

    @Test
    fun `does not repeat a lookup it already made`() = runTest {
        val model = ScriptedModel(plans = listOf("WIKIPEDIA: market maker", "WIKIPEDIA: Market  Maker"), answer = "El dealer.")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Wikipedia)))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertEquals(listOf(ToolCall(SourceKind.Wikipedia, "market maker")), started(events))
    }

    @Test
    fun `stops looking things up after two rounds`() = runTest {
        val model = ScriptedModel(plans = listOf("LIBRO: dealer", "LIBRO: spread", "LIBRO: inventory"), answer = "El dealer.")
        val agent = ExplainAgent(model, listOf(StubTool(SourceKind.Book)))

        val events = agent.run(request(Planning.ByModel)).toList()

        assertEquals(listOf(ToolCall(SourceKind.Book, "dealer"), ToolCall(SourceKind.Book, "spread")), started(events))
    }

    @Test
    fun `runs the opening lookups without asking the planner`() = runTest {
        val tools = listOf(
            StubTool(SourceKind.Book, mapOf("bid/ask spread" to listOf(definition))),
            StubTool(SourceKind.Wikipedia, mapOf("bid/ask spread" to listOf(marketMaker))),
        )
        val seeded = Planning.Seeded(listOf(ToolCall(SourceKind.Book, "bid/ask spread"), ToolCall(SourceKind.Wikipedia, "bid/ask spread")))

        val events = ExplainAgent(PlannerMustNotRun("Es lo que cobra el dealer [1]."), tools).run(request(seeded)).toList()

        assertEquals(seeded.calls.toSet(), started(events).toSet())
        assertEquals(listOf(definition.source), answered(events).sources)
    }

    @Test
    fun `offers what earlier turns found to the next answer`() = runTest {
        val model = ScriptedModel(answer = "Más simple: es la comisión del dealer [1].")
        val agent = ExplainAgent(model, emptyList())

        val events = agent.run(request(Planning.NoTools, question = "Más simple", known = listOf(definition))).toList()

        assertTrue(model.answerPrompts.single().contains("[1] Libro, 14. Bid/Ask Spreads: …dealers earn the bid/ask spread"))
        assertEquals(listOf(definition.source), answered(events).sources)
    }
}
