package com.maxi.glosa.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maxi.glosa.books.BookOpenException
import com.maxi.glosa.books.ReaderSession
import com.maxi.glosa.data.BookFormat
import com.maxi.glosa.data.Bookmark
import com.maxi.glosa.data.Explanation
import com.maxi.glosa.data.ExplanationThread
import com.maxi.glosa.data.Highlight
import com.maxi.glosa.data.HighlightColor
import com.maxi.glosa.explain.ExplainAgent
import com.maxi.glosa.explain.ExplainMode
import com.maxi.glosa.explain.ExplainPrompts
import com.maxi.glosa.explain.Passage
import com.maxi.glosa.explain.Tool
import com.maxi.glosa.explain.tools.BookSearchTool
import com.maxi.glosa.explain.tools.CalculatorTool
import com.maxi.glosa.explain.tools.DictionaryTool
import com.maxi.glosa.explain.tools.NotesTool
import com.maxi.glosa.explain.tools.WebSearchTool
import com.maxi.glosa.explain.tools.WikipediaTool
import com.maxi.glosa.glosaApp
import com.maxi.glosa.settings.Appearance
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

data class TocEntry(val title: String, val link: Link, val depth: Int)

data class SearchState(
    val query: String = "",
    val hits: List<Locator> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

sealed interface ReaderEvent {
    /** Show the book in the other [format]: the reader for it has its session ready. */
    data class Open(val format: BookFormat) : ReaderEvent
    data class Message(val text: String) : ReaderEvent
}

sealed interface ReaderSheet {
    data object Contents : ReaderSheet
    data object Settings : ReaderSheet
    data object Explain : ReaderSheet
    data class HighlightDetail(val highlightId: Long) : ReaderSheet
}

class ReaderViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.glosaApp
    private val bookId: Long = checkNotNull(savedState[ReaderActivity.EXTRA_BOOK_ID]) { "ReaderActivity needs a book id" }
    val session: ReaderSession = checkNotNull(app.openBooks.get(bookId, BookFormat.Epub)) { "Book $bookId was not opened before the reader" }

    /** A book converted from a PDF can show the page it came from. */
    val canOpenAsPdf: Boolean = session.book.pdfPath != null

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events: Flow<ReaderEvent> = _events.receiveAsFlow()

    val tableOfContents: List<TocEntry> = tocEntries(session.publication.tableOfContents)
    val preferences: StateFlow<EpubPreferences> = app.readerPreferences.preferences

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _isChromeVisible = MutableStateFlow(false)
    val isChromeVisible: StateFlow<Boolean> = _isChromeVisible.asStateFlow()

    private val _sheet = MutableStateFlow<ReaderSheet?>(null)
    val sheet: StateFlow<ReaderSheet?> = _sheet.asStateFlow()

    val appearance: StateFlow<Appearance> = app.settings.appearance
    val sepiaTone: StateFlow<Float> = app.settings.sepiaTone
    val canSearchWeb: StateFlow<Boolean> = app.settings.webSearchKey.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.settings.webSearchKey.value != null)

    val explainer = ExplainController(viewModelScope, bookId, app.geminiNano, app.database.explanations()) {
        ExplainAgent(app.geminiNano, tools())
    }
    val explain: StateFlow<ExplainUiState?> = explainer.state

    val highlights: StateFlow<List<Highlight>> = app.database.highlights().observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = app.database.bookmarks().observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val explanations: StateFlow<List<ExplanationThread>> = app.database.explanations().observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentBookmark: StateFlow<Bookmark?> = combine(currentLocator, bookmarks) { locator, marks ->
        locator?.let { here -> marks.firstOrNull { it.isOnSamePage(here) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var savePositionJob: Job? = null
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
            app.database.books().saveEpubPosition(
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
        if (_sheet.value == ReaderSheet.Explain) explainer.cancel()
        _sheet.value = null
    }

    fun updatePreferences(transform: (EpubPreferences) -> EpubPreferences) = app.readerPreferences.update(transform)

    fun setAppearance(appearance: Appearance) = app.settings.setAppearance(appearance)

    fun setSepiaTone(tone: Float) = app.settings.setSepiaTone(tone)

    /** Opens the PDF this EPUB was converted from, at the page being read. */
    fun openAsPdf() = viewModelScope.launch {
        val books = app.database.books()
        val pdf = try {
            app.bookOpener.open(session.book, BookFormat.Pdf)
        } catch (e: BookOpenException) {
            _events.send(ReaderEvent.Message(e.message.orEmpty()))
            return@launch
        }
        val here = currentLocator.value
        here?.let { books.saveEpubPosition(bookId, it.toJSON().toString(), it.locations.totalProgression ?: 0.0, System.currentTimeMillis()) }
        val page = here?.let { session.publication.pageMap().pageAt(it.href.toString(), it.locations.progression ?: 0.0) } ?: 1
        pdf.publication.positions().let { positions -> positions.getOrNull(page - 1) ?: positions.lastOrNull() }?.let { target ->
            books.savePdfPosition(bookId, target.toJSON().toString(), target.locations.totalProgression ?: 0.0, System.currentTimeMillis())
        }
        books.setFormat(bookId, BookFormat.Pdf)
        app.openBooks.put(pdf.copy(book = checkNotNull(books.get(bookId)) { "Book $bookId is gone from the library" }))
        _events.send(ReaderEvent.Open(BookFormat.Pdf))
    }

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

    private fun passageFor(locator: Locator): Passage = Passage(
        bookTitle = session.book.title,
        author = session.book.author,
        language = session.publication.metadata.languages.firstOrNull(),
        chapter = locator.title ?: currentLocator.value?.title,
        before = locator.text.before.orEmpty(),
        selected = locator.text.highlight.orEmpty(),
        after = locator.text.after.orEmpty(),
    )

    private fun tools(): List<Tool> = listOfNotNull(
        BookSearchTool(session.publication),
        WikipediaTool(app.http, wikipediaLanguages()),
        DictionaryTool(app.http, bookLanguage()),
        app.settings.webSearchKey.value?.let { WebSearchTool(app.http, it) },
        NotesTool(bookId, app.database.highlights(), app.database.explanations()),
        CalculatorTool,
    )

    private fun bookLanguage(): String =
        session.publication.metadata.languages.firstOrNull()?.substringBefore('-')?.lowercase() ?: "en"

    private fun wikipediaLanguages(): List<String> = listOf(bookLanguage(), "es").distinct()

    fun explain(locator: Locator, highlightId: Long? = null) {
        explainer.start(passageFor(locator), locator, highlightId)
        _sheet.value = ReaderSheet.Explain
    }

    fun openExplanation(id: Long) = viewModelScope.launch {
        val thread = app.database.explanations().get(id) ?: return@launch
        val locator = thread.explanation.locator() ?: return@launch
        explainer.resume(thread, passageFor(locator), locator)
        _sheet.value = ReaderSheet.Explain
    }

    fun deleteExplanation(id: Long) = viewModelScope.launch { app.database.explanations().delete(id) }

    fun explainAgain(mode: ExplainMode) = explainer.explainAgain(mode)

    fun askFollowUp(question: String) = explainer.askFollowUp(question)

    fun claudeHandoffText(): String? {
        val state = explain.value ?: return null
        return ExplainPrompts.forClaude(state.passage, state.turns, followUp = null)
    }

    fun saveExplanationAsNote() = viewModelScope.launch {
        val state = explain.value ?: return@launch
        val note = state.turns.joinToString("\n\n") { "${it.question}\n${it.answer}" }
        if (note.isBlank()) return@launch
        val highlightId = state.highlightId
        if (highlightId != null) {
            val highlight = app.database.highlights().get(highlightId) ?: return@launch
            val merged = listOfNotNull(highlight.note, note).joinToString("\n\n")
            app.database.highlights().update(highlight.copy(note = merged))
            explainer.markSavedAsNote(highlightId)
        } else {
            explainer.markSavedAsNote(insertHighlight(state.locator, HighlightColor.Blue, note))
        }
    }

    override fun onCleared() {
        app.openBooks.close(session)
    }

    private companion object {
        const val MAX_SEARCH_HITS = 300
    }
}

internal fun tocEntries(links: List<Link>, depth: Int = 0): List<TocEntry> = links.flatMap { link ->
    listOf(TocEntry(link.title ?: link.href.toString(), link, depth)) + tocEntries(link.children, depth + 1)
}

fun Highlight.locator(): Locator? = Locator.fromJSON(JSONObject(locatorJson))

fun Bookmark.locator(): Locator? = Locator.fromJSON(JSONObject(locatorJson))

fun Explanation.locator(): Locator? = Locator.fromJSON(JSONObject(locatorJson))

private fun Bookmark.isOnSamePage(here: Locator): Boolean {
    val total = here.locations.totalProgression ?: return false
    return abs(progression - total) < 0.0015
}
