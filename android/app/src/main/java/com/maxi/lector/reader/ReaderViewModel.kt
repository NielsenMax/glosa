package com.maxi.lector.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.maxi.lector.books.ReaderSession
import com.maxi.lector.data.Bookmark
import com.maxi.lector.data.Highlight
import com.maxi.lector.data.HighlightColor
import com.maxi.lector.explain.Exchange
import com.maxi.lector.explain.ExplainMode
import com.maxi.lector.explain.ExplainPrompts
import com.maxi.lector.explain.NanoAvailability
import com.maxi.lector.explain.Passage
import com.maxi.lector.lectorApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import kotlin.math.abs

data class TocEntry(val title: String, val link: Link, val depth: Int)

data class SearchState(
    val query: String = "",
    val hits: List<Locator> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

sealed interface ReaderSheet {
    data object Contents : ReaderSheet
    data object Settings : ReaderSheet
    data object Explain : ReaderSheet
    data class HighlightDetail(val highlightId: Long) : ReaderSheet
}

data class ExplainUiState(
    val passage: Passage,
    val locator: Locator,
    val highlightId: Long? = null,
    val exchanges: List<Exchange> = emptyList(),
    val pendingQuestion: String? = null,
    val streamingAnswer: String = "",
    val isGenerating: Boolean = false,
    val downloadedBytes: Long? = null,
    val error: String? = null,
    val isSavedAsNote: Boolean = false,
)

class ReaderViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.lectorApp
    private val bookId: Long = checkNotNull(savedState[ReaderActivity.EXTRA_BOOK_ID]) { "ReaderActivity needs a book id" }
    val session: ReaderSession = checkNotNull(app.openBooks.get(bookId)) { "Book $bookId was not opened before the reader" }

    val tableOfContents: List<TocEntry> = flatten(session.publication.tableOfContents, 0)
    val preferences: StateFlow<EpubPreferences> = app.readerPreferences.preferences

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _isChromeVisible = MutableStateFlow(false)
    val isChromeVisible: StateFlow<Boolean> = _isChromeVisible.asStateFlow()

    private val _sheet = MutableStateFlow<ReaderSheet?>(null)
    val sheet: StateFlow<ReaderSheet?> = _sheet.asStateFlow()

    private val _explain = MutableStateFlow<ExplainUiState?>(null)
    val explain: StateFlow<ExplainUiState?> = _explain.asStateFlow()

    val highlights: StateFlow<List<Highlight>> = app.database.highlights().observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = app.database.bookmarks().observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentBookmark: StateFlow<Bookmark?> = combine(currentLocator, bookmarks) { locator, marks ->
        locator?.let { here -> marks.firstOrNull { it.isOnSamePage(here) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var savePositionJob: Job? = null
    private var generation: Job? = null
    private var searchJob: Job? = null

    fun search(query: String) {
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.length < 2) {
            _search.value = SearchState(query = query)
            return
        }
        _search.value = SearchState(query = query, isSearching = true)
        searchJob = viewModelScope.launch {
            val iterator = session.publication.search(trimmed)
            if (iterator == null) {
                _search.value = SearchState(query = query, error = "Este libro no permite buscar texto.")
                return@launch
            }
            try {
                while (_search.value.hits.size < MAX_SEARCH_HITS) {
                    val page = iterator.next().getOrElse {
                        _search.update { state -> state.copy(error = "La búsqueda falló: ${it.message}") }
                        break
                    } ?: break
                    _search.update { it.copy(hits = it.hits + page.locators) }
                }
            } finally {
                iterator.close()
                _search.update { it.copy(isSearching = false) }
            }
        }
    }

    fun onLocatorChanged(locator: Locator) {
        _currentLocator.value = locator
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(600)
            app.database.books().savePosition(
                id = bookId,
                locatorJson = locator.toJSON().toString(),
                progression = locator.locations.totalProgression ?: 0.0,
                openedAt = System.currentTimeMillis(),
            )
        }
    }

    fun toggleChrome() = _isChromeVisible.update { !it }

    fun hideChrome() {
        _isChromeVisible.value = false
    }

    fun showSheet(sheet: ReaderSheet) {
        _sheet.value = sheet
    }

    fun dismissSheet() {
        if (_sheet.value == ReaderSheet.Explain) generation?.cancel()
        _sheet.value = null
    }

    fun updatePreferences(transform: (EpubPreferences) -> EpubPreferences) = app.readerPreferences.update(transform)

    fun addHighlight(locator: Locator, color: HighlightColor) = viewModelScope.launch {
        insertHighlight(locator, color, note = null)
    }

    fun addHighlightWithNote(locator: Locator) = viewModelScope.launch {
        val id = insertHighlight(locator, HighlightColor.Yellow, note = null)
        _sheet.value = ReaderSheet.HighlightDetail(id)
    }

    fun highlightsAsMarkdown(): String = buildString {
        appendLine("# ${session.book.title}")
        session.book.author?.let { appendLine("_${it}_") }
        var chapter: String? = null
        highlights.value.forEach { highlight ->
            if (highlight.chapter != chapter) {
                chapter = highlight.chapter
                appendLine()
                appendLine("## ${chapter ?: "Sin capítulo"}")
            }
            appendLine()
            appendLine("> ${highlight.text.replace("\n", "\n> ")}")
            highlight.note?.let { appendLine(); appendLine(it) }
        }
    }

    private suspend fun insertHighlight(locator: Locator, color: HighlightColor, note: String?): Long =
        app.database.highlights().insert(
            Highlight(
                bookId = bookId,
                locatorJson = locator.toJSON().toString(),
                text = locator.text.highlight.orEmpty().trim(),
                color = color,
                note = note,
                chapter = locator.title ?: currentLocator.value?.title,
                progression = locator.locations.totalProgression ?: currentLocator.value?.locations?.totalProgression ?: 0.0,
                createdAt = System.currentTimeMillis(),
            ),
        )

    fun recolorHighlight(id: Long, color: HighlightColor) = editHighlight(id) { it.copy(color = color) }

    fun saveNote(id: Long, note: String) = editHighlight(id) { it.copy(note = note.ifBlank { null }) }

    fun deleteHighlight(id: Long) = viewModelScope.launch {
        app.database.highlights().delete(id)
        if ((_sheet.value as? ReaderSheet.HighlightDetail)?.highlightId == id) _sheet.value = null
    }

    private fun editHighlight(id: Long, change: (Highlight) -> Highlight) = viewModelScope.launch {
        val highlight = app.database.highlights().get(id) ?: return@launch
        app.database.highlights().update(change(highlight))
    }

    fun toggleBookmark() = viewModelScope.launch {
        val existing = currentBookmark.value
        if (existing != null) {
            app.database.bookmarks().delete(existing.id)
            return@launch
        }
        val locator = currentLocator.value ?: return@launch
        app.database.bookmarks().insert(
            Bookmark(
                bookId = bookId,
                locatorJson = locator.toJSON().toString(),
                chapter = locator.title,
                excerpt = locator.text.highlight?.take(160),
                progression = locator.locations.totalProgression ?: 0.0,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { app.database.bookmarks().delete(id) }

    fun passageFor(locator: Locator): Passage = Passage(
        bookTitle = session.book.title,
        author = session.book.author,
        chapter = locator.title ?: currentLocator.value?.title,
        before = locator.text.before.orEmpty(),
        selected = locator.text.highlight.orEmpty(),
        after = locator.text.after.orEmpty(),
    )

    fun explain(locator: Locator, highlightId: Long? = null) {
        val passage = passageFor(locator)
        _explain.value = ExplainUiState(passage = passage, locator = locator, highlightId = highlightId)
        _sheet.value = ReaderSheet.Explain
        ask(ExplainPrompts.question(passage, ExplainMode.Explain), followUp = null, mode = ExplainMode.Explain)
    }

    fun explainAgain(mode: ExplainMode) {
        val state = _explain.value ?: return
        ask(ExplainPrompts.question(state.passage, mode), followUp = null, mode = mode)
    }

    fun askFollowUp(question: String) {
        if (question.isBlank()) return
        ask(question.trim(), followUp = question.trim(), mode = ExplainMode.Explain)
    }

    fun claudeHandoffText(): String? {
        val state = _explain.value ?: return null
        return ExplainPrompts.forClaude(state.passage, state.exchanges, followUp = null)
    }

    fun saveExplanationAsNote() = viewModelScope.launch {
        val state = _explain.value ?: return@launch
        val note = state.exchanges.joinToString("\n\n") { "${it.question}\n${it.answer}" }
        if (note.isBlank()) return@launch
        val highlightId = state.highlightId
        if (highlightId != null) {
            val highlight = app.database.highlights().get(highlightId) ?: return@launch
            val merged = listOfNotNull(highlight.note, note).joinToString("\n\n")
            app.database.highlights().update(highlight.copy(note = merged))
        } else {
            val id = insertHighlight(state.locator, HighlightColor.Blue, note)
            _explain.update { it?.copy(highlightId = id) }
        }
        _explain.update { it?.copy(isSavedAsNote = true) }
    }

    private fun ask(question: String, followUp: String?, mode: ExplainMode) {
        generation?.cancel()
        generation = viewModelScope.launch {
            val start = _explain.value ?: return@launch
            val prompt = ExplainPrompts.forNano(start.passage, mode, start.exchanges, followUp)
            _explain.update {
                it?.copy(pendingQuestion = question, streamingAnswer = "", isGenerating = true, error = null, isSavedAsNote = false)
            }
            try {
                ensureModelReady()
                app.geminiNano.stream(prompt).collect { piece ->
                    _explain.update { it?.copy(streamingAnswer = it.streamingAnswer + piece, downloadedBytes = null) }
                }
                _explain.update {
                    it?.copy(
                        exchanges = it.exchanges + Exchange(question, it.streamingAnswer.trim()),
                        pendingQuestion = null,
                        streamingAnswer = "",
                        isGenerating = false,
                    )
                }
            } catch (e: GenAiException) {
                _explain.update { it?.copy(isGenerating = false, error = describe(e)) }
            } catch (e: NanoUnavailableException) {
                _explain.update { it?.copy(isGenerating = false, error = e.message) }
            }
        }
    }

    private suspend fun ensureModelReady() {
        when (app.geminiNano.availability()) {
            NanoAvailability.Ready -> Unit
            NanoAvailability.Unavailable -> throw NanoUnavailableException(
                "Gemini Nano no está disponible en este teléfono por ahora. Podés preguntarle a Claude.",
            )
            NanoAvailability.Downloadable, NanoAvailability.Downloading -> app.geminiNano.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress -> _explain.update { it?.copy(downloadedBytes = status.totalBytesDownloaded) }
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

    override fun onCleared() {
        app.openBooks.close(bookId)
    }

    private class NanoUnavailableException(message: String) : Exception(message)

    private companion object {
        const val MAX_SEARCH_HITS = 300

        fun flatten(links: List<Link>, depth: Int): List<TocEntry> = links.flatMap { link ->
            listOf(TocEntry(link.title ?: link.href.toString(), link, depth)) + flatten(link.children, depth + 1)
        }
    }
}

fun Highlight.locator(): Locator? = Locator.fromJSON(JSONObject(locatorJson))

fun Bookmark.locator(): Locator? = Locator.fromJSON(JSONObject(locatorJson))

private fun Bookmark.isOnSamePage(here: Locator): Boolean {
    val total = here.locations.totalProgression ?: return false
    return abs(progression - total) < 0.0015
}
