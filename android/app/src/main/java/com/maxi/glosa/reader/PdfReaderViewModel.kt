package com.maxi.glosa.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maxi.glosa.books.BookOpenException
import com.maxi.glosa.books.ReaderSession
import com.maxi.glosa.data.Book
import com.maxi.glosa.data.BookFormat
import com.maxi.glosa.glosaApp
import com.maxi.glosa.settings.Appearance
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

enum class PdfSheet { Contents, Appearance }

class PdfReaderViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.glosaApp
    private val bookId: Long = checkNotNull(savedState[PdfReaderActivity.EXTRA_BOOK_ID]) { "PdfReaderActivity needs a book id" }
    val session: ReaderSession = checkNotNull(app.openBooks.get(bookId, BookFormat.Pdf)) { "Book $bookId was not opened before the reader" }

    val tableOfContents: List<TocEntry> = tocEntries(session.publication.tableOfContents)
    val pageCount: Int? = session.publication.metadata.numberOfPages

    /** The book as it changes while it is open: its conversion to EPUB may finish meanwhile. */
    val book: StateFlow<Book> = app.database.books().observe(bookId).filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.book)

    /** Share converted to EPUB so far, while the conversion runs. */
    val conversionProgress: StateFlow<Float?> = app.converter.progress.map { it[bookId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val appearance: StateFlow<Appearance> = app.settings.appearance
    val sepiaTone: StateFlow<Float> = app.settings.sepiaTone

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _isChromeVisible = MutableStateFlow(false)
    val isChromeVisible: StateFlow<Boolean> = _isChromeVisible.asStateFlow()

    private val _sheet = MutableStateFlow<PdfSheet?>(null)
    val sheet: StateFlow<PdfSheet?> = _sheet.asStateFlow()

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events: Flow<ReaderEvent> = _events.receiveAsFlow()

    private var savePositionJob: Job? = null

    fun onLocatorChanged(locator: Locator) {
        _currentLocator.value = locator
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(600)
            savePosition(locator)
        }
    }

    private suspend fun savePosition(locator: Locator) = app.database.books().savePdfPosition(
        id = bookId,
        locatorJson = locator.toJSON().toString(),
        progression = locator.locations.totalProgression ?: 0.0,
        openedAt = System.currentTimeMillis(),
    )

    fun toggleChrome() = _isChromeVisible.update { !it }

    fun hideChrome() {
        _isChromeVisible.value = false
    }

    fun showSheet(sheet: PdfSheet) {
        _sheet.value = sheet
    }

    fun dismissSheet() {
        _sheet.value = null
    }

    fun setAppearance(appearance: Appearance) = app.settings.setAppearance(appearance)

    fun setSepiaTone(tone: Float) = app.settings.setSepiaTone(tone)

    fun showConversionError() = viewModelScope.launch {
        val error = book.value.conversionError ?: return@launch
        _events.send(ReaderEvent.Message("No se pudo convertir a EPUB: $error"))
    }

    /** Opens the EPUB converted from this PDF where the current page begins. */
    fun openAsEpub() = viewModelScope.launch {
        val books = app.database.books()
        val epub = try {
            app.bookOpener.open(book.value, BookFormat.Epub)
        } catch (e: BookOpenException) {
            _events.send(ReaderEvent.Message(e.message.orEmpty()))
            return@launch
        }
        val here = currentLocator.value
        here?.let { savePosition(it) }
        val page = here?.locations?.position ?: 1
        epub.publication.pageMap().placeOf(page)?.locator()?.let { target ->
            books.saveEpubPosition(bookId, target.toJSON().toString(), book.value.progression, System.currentTimeMillis())
        }
        books.setFormat(bookId, BookFormat.Epub)
        app.openBooks.put(epub.copy(book = checkNotNull(books.get(bookId)) { "Book $bookId is gone from the library" }))
        _events.send(ReaderEvent.Open(BookFormat.Epub))
    }

    override fun onCleared() {
        app.openBooks.close(session)
    }
}
