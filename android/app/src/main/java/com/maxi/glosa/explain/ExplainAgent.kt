package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Planning {
    /** Runs these lookups straight away, without spending a model call on deciding them. */
    data class Seeded(val calls: List<ToolCall>) : Planning

    data object ByModel : Planning

    data object NoTools : Planning
}

data class AgentRequest(
    val passage: Passage,
    val history: List<Exchange>,
    /** What the model is asked to do this turn: an instruction or the reader's own question. */
    val request: String,
    /** Findings from earlier turns about the same passage. */
    val known: List<Finding>,
    val planning: Planning,
)

sealed interface AgentEvent {
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val findings: List<Finding>) : AgentEvent
    data class ToolFailed(val call: ToolCall, val reason: String) : AgentEvent
    data class AnswerChunk(val text: String) : AgentEvent
    data class Answered(val answer: String, val sources: List<Source>) : AgentEvent
}

/** Looks things up with its tools, then has the model answer with what it found. */
class ExplainAgent(private val model: LanguageModel, tools: Collection<Tool>) {
    private val tools = tools.associateBy { it.kind }

    fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val context = LookupContext(ExplainPrompts.surroundings(request.passage), request.request)
        val found = mutableListOf<Finding>()
        val attempted = mutableSetOf<String>()

        suspend fun execute(calls: List<ToolCall>) = coroutineScope {
            attempted += calls.map { it.key }
            val results = calls.map { call ->
                async {
                    send(AgentEvent.ToolStarted(call))
                    val event = lookup(call, context)
                    send(event)
                    (event as? AgentEvent.ToolFinished)?.findings.orEmpty()
                }
            }.awaitAll()
            found += results.flatten()
        }

        when (val planning = request.planning) {
            is Planning.Seeded -> execute(planning.calls.filter { it.tool in tools })
            Planning.ByModel -> for (round in 1..MAX_ROUNDS) {
                val plan = ExplainPrompts.plan(request.passage, request.history, request.request, request.known + found, tools.keys)
                val calls = ToolCallParser.parse(model.complete(plan, PLAN_TOKENS), tools.keys).filter { it.key !in attempted }
                if (calls.isEmpty()) break
                execute(calls)
            }
            Planning.NoTools -> Unit
        }

        val shown = ExplainPrompts.fitFindings(found + request.known.filter { it !in found })
        val answer = StringBuilder()
        model.stream(ExplainPrompts.answer(request.passage, request.history, request.request, shown)).collect { chunk ->
            answer.append(chunk)
            send(AgentEvent.AnswerChunk(chunk))
        }
        val cited = Citations.resolve(answer.toString().trim(), shown, consulted = found)
        send(AgentEvent.Answered(cited.answer, cited.sources))
    }

    private suspend fun lookup(call: ToolCall, context: LookupContext): AgentEvent = try {
        val findings = withTimeoutOrNull(TOOL_TIMEOUT_MS) { tools.getValue(call.tool).run(call.query, context) }
        if (findings == null) AgentEvent.ToolFailed(call, "tardó demasiado") else AgentEvent.ToolFinished(call, findings)
    } catch (e: ToolException) {
        AgentEvent.ToolFailed(call, e.message.orEmpty())
    }

    companion object {
        const val MAX_ROUNDS = 2
        private const val PLAN_TOKENS = 64
        private const val TOOL_TIMEOUT_MS = 12_000L
    }
}
