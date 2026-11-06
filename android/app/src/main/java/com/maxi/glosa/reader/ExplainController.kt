package com.maxi.glosa.reader

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.maxi.glosa.data.Explanation
import com.maxi.glosa.data.ExplanationDao
import com.maxi.glosa.data.ExplanationThread
import com.maxi.glosa.data.ExplanationTurn
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.AgentEvent
import com.maxi.glosa.explain.AgentRequest
import com.maxi.glosa.explain.Exchange
import com.maxi.glosa.explain.ExplainAgent
import com.maxi.glosa.explain.ExplainMode
import com.maxi.glosa.explain.ExplainPrompts
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.GeminiNano
import com.maxi.glosa.explain.NanoAvailability
import com.maxi.glosa.explain.Passage
import com.maxi.glosa.explain.Planning
import com.maxi.glosa.explain.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

sealed interface StepStatus {
    data object Running : StepStatus
    data class Found(val findings: List<Finding>) : StepStatus
    data class Failed(val reason: String) : StepStatus
}

data class ToolStep(val call: ToolCall, val status: StepStatus)

data class PendingTurn(val question: String, val steps: List<ToolStep> = emptyList(), val answer: String = "")

data class ExplainUiState(
    val explanationId: Long? = null,
    val passage: Passage,
    val locator: Locator,
    val highlightId: Long? = null,
    val turns: List<Exchange> = emptyList(),
    val pending: PendingTurn? = null,
    /** Everything looked up about this passage so far, offered again to later questions. */
    val findings: List<Finding> = emptyList(),
    val downloadedBytes: Long? = null,
    val error: String? = null,
    val isSavedAsNote: Boolean = false,
) {
    val isGenerating: Boolean get() = pending != null && error == null
}

/** Runs one explanation thread: the agent's lookups and answers, saved turn by turn. */
class ExplainController(
    private val scope: CoroutineScope,
    private val bookId: Long,
    private val nano: GeminiNano,
    private val explanations: ExplanationDao,
    private val newAgent: () -> ExplainAgent,
) {
    private val _state = MutableStateFlow<ExplainUiState?>(null)
    val state: StateFlow<ExplainUiState?> = _state.asStateFlow()

    private var generation: Job? = null

    fun start(passage: Passage, locator: Locator, highlightId: Long?) {
        _state.value = ExplainUiState(passage = passage, locator = locator, highlightId = highlightId)
        val term = passage.selected.trim()
        val planning = when {
            !passage.isTerm -> Planning.ByModel
            term.split(Regex("\\s+")).size <= DICTIONARY_WORDS -> Planning.Seeded(
                listOf(ToolCall(SourceKind.Book, term), ToolCall(SourceKind.Dictionary, term), ToolCall(SourceKind.Wikipedia, term)),
            )
            else -> Planning.Seeded(listOf(ToolCall(SourceKind.Book, term), ToolCall(SourceKind.Wikipedia, term)))
        }
        ask(ExplainMode.Explain.request, ExplainPrompts.instruction(passage, ExplainMode.Explain), planning)
    }

    fun resume(thread: ExplanationThread, passage: Passage, locator: Locator) {
        generation?.cancel()
        _state.value = ExplainUiState(
            explanationId = thread.explanation.id,
            passage = passage,
            locator = locator,
            turns = thread.turns.sortedBy { it.createdAt }.map { Exchange(it.question, it.answer, it.sources) },
        )
    }

    fun explainAgain(mode: ExplainMode) {
        val state = _state.value ?: return
        ask(mode.request, ExplainPrompts.instruction(state.passage, mode), Planning.NoTools)
    }

    fun askFollowUp(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        ask(trimmed, trimmed, Planning.ByModel)
    }

    fun cancel() {
        generation?.cancel()
        _state.update { it?.copy(pending = null) }
    }

    fun markSavedAsNote(highlightId: Long) = _state.update { it?.copy(highlightId = highlightId, isSavedAsNote = true) }

    private fun ask(question: String, request: String, planning: Planning) {
        generation?.cancel()
        generation = scope.launch {
            val start = _state.value ?: return@launch
            _state.update { it?.copy(pending = PendingTurn(question), error = null, isSavedAsNote = false) }
            try {
                ensureModelReady()
                val agentRequest = AgentRequest(start.passage, start.turns, request, start.findings, planning)
                newAgent().run(agentRequest).collect { event -> onEvent(question, event) }
            } catch (e: GenAiException) {
                _state.update { it?.copy(error = describe(e)) }
            } catch (e: NanoUnavailableException) {
                _state.update { it?.copy(error = e.message) }
            }
        }
    }

    private suspend fun onEvent(question: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStarted -> updatePending { it.copy(steps = it.steps + ToolStep(event.call, StepStatus.Running)) }
            is AgentEvent.ToolFinished -> {
                updatePending { it.withStep(event.call, StepStatus.Found(event.findings)) }
                _state.update { state -> state?.copy(findings = (event.findings + state.findings).distinct()) }
            }
            is AgentEvent.ToolFailed -> updatePending { it.withStep(event.call, StepStatus.Failed(event.reason)) }
            is AgentEvent.AnswerChunk -> updatePending { it.copy(answer = it.answer + event.text) }
            is AgentEvent.Answered -> save(Exchange(question, event.answer, event.sources))
        }
    }

    private suspend fun save(exchange: Exchange) {
        val state = _state.value ?: return
        val now = System.currentTimeMillis()
        val explanation = Explanation(
            id = state.explanationId ?: 0,
            bookId = bookId,
            locatorJson = state.locator.toJSON().toString(),
            selectedText = state.passage.selected.trim(),
            chapter = state.passage.chapter,
            progression = state.locator.locations.totalProgression ?: 0.0,
            createdAt = now,
            updatedAt = now,
        )
        val turn = ExplanationTurn(
            explanationId = explanation.id,
            question = exchange.question,
            answer = exchange.answer,
            sources = exchange.sources,
            createdAt = now,
        )
        val id = explanations.record(explanation, turn)
        _state.update { it?.copy(explanationId = id, turns = it.turns + exchange, pending = null, downloadedBytes = null) }
    }

    private fun updatePending(change: (PendingTurn) -> PendingTurn) =
        _state.update { state -> state?.pending?.let { state.copy(pending = change(it), downloadedBytes = null) } ?: state }

    private fun PendingTurn.withStep(call: ToolCall, status: StepStatus) =
        copy(steps = steps.map { if (it.call == call) it.copy(status = status) else it })

    private suspend fun ensureModelReady() {
        when (nano.availability()) {
            NanoAvailability.Ready -> Unit
            NanoAvailability.Unavailable -> throw NanoUnavailableException(
                "Gemini Nano no está disponible en este teléfono por ahora. Podés preguntarle a Claude.",
            )
            NanoAvailability.Downloadable, NanoAvailability.Downloading -> nano.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress -> _state.update { it?.copy(downloadedBytes = status.totalBytesDownloaded) }
                    is DownloadStatus.DownloadFailed -> throw status.e
                    else -> Unit
                }
            }
        }
    }

    private fun describe(e: GenAiException): String = when (e.errorCode) {
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
            "Llegaste al límite de uso de Gemini Nano por hoy. Podés seguir con Claude."
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "La selección es demasiado larga para el modelo local. Probá con menos texto."
        GenAiException.ErrorCode.BUSY -> "Gemini Nano está ocupado. Probá de nuevo en unos segundos."
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "Gemini Nano necesita una actualización del sistema (AICore)."
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "No hay espacio para descargar Gemini Nano."
        else -> "Gemini Nano falló (código ${e.errorCode}): ${e.message}"
    }

    private class NanoUnavailableException(message: String) : Exception(message)

    private companion object {
        /** Wiktionary has entries for words and set phrases, not for longer selections. */
        const val DICTIONARY_WORDS = 3
    }
}
