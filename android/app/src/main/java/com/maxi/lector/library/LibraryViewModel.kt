package com.maxi.lector.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maxi.lector.books.BookOpenException
import com.maxi.lector.books.ReaderSession
import com.maxi.lector.data.Book
import com.maxi.lector.lectorApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface LibraryEvent {
    data class OpenReader(val bookId: Long) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.lectorApp

    val books: StateFlow<List<Book>?> = app.database.books().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    fun import(uri: Uri) = viewModelScope.launch {
        _isBusy.value = true
        try {
            app.importer.import(uri)
        } catch (e: BookOpenException) {
            _events.send(LibraryEvent.Message(e.message.orEmpty()))
        } finally {
            _isBusy.value = false
        }
    }

    fun open(book: Book) = viewModelScope.launch {
        _isBusy.value = true
        try {
            val publication = app.bookOpener.open(File(book.filePath))
            app.openBooks.put(ReaderSession(book, publication))
            _events.send(LibraryEvent.OpenReader(book.id))
        } catch (e: BookOpenException) {
            _events.send(LibraryEvent.Message(e.message.orEmpty()))
        } finally {
            _isBusy.value = false
        }
    }

    fun delete(book: Book) = viewModelScope.launch { app.importer.delete(book) }
}
