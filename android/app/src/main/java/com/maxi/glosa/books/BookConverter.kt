package com.maxi.glosa.books

import android.content.Context
import com.maxi.glosa.convert.ConversionException
import com.maxi.glosa.convert.PdfToEpub
import com.maxi.glosa.data.BookDao
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Converts imported PDFs to EPUB one at a time while the app runs. A conversion cut short when
 * Android ends the process starts over the next time the app opens: see [resumePending].
 */
class BookConverter(private val context: Context, private val books: BookDao, private val scope: CoroutineScope) {
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val queued = mutableSetOf<Long>()
    @Volatile
    private var current: Pair<Long, Job>? = null

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Share converted, 0 to 1, of each book being converted now. */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    init {
        scope.launch {
            for (bookId in queue) {
                val job = launch { convert(bookId) }
                current = bookId to job
                job.join()
                current = null
                synchronized(queued) { queued -= bookId }
            }
        }
    }

    fun enqueue(bookId: Long) {
        val isNew = synchronized(queued) { queued.add(bookId) }
        if (isNew) queue.trySend(bookId)
    }

    fun resumePending() = scope.launch {
        books.awaitingConversion().forEach { enqueue(it.id) }
    }

    fun cancel(bookId: Long) {
        current?.takeIf { it.first == bookId }?.second?.cancel()
    }

    private suspend fun convert(bookId: Long) {
        val book = books.get(bookId) ?: return
        val pdf = book.pdfPath?.let(::File) ?: return
        if (!book.isConverting) return
        val epub = File(pdf.parentFile, "${pdf.nameWithoutExtension}.epub")
        val partial = File(pdf.parentFile, "${epub.name}.part")
        _progress.update { it + (bookId to 0f) }
        try {
            withContext(Dispatchers.Default) {
                AndroidPdfSource(context, pdf).use { source ->
                    partial.outputStream().buffered().use { out ->
                        PdfToEpub(source).convert("urn:uuid:${UUID.randomUUID()}", book.title, book.author, out) { done ->
                            ensureActive()
                            _progress.update { it + (bookId to done) }
                        }
                    }
                }
            }
            check(partial.renameTo(epub)) { "Could not move ${partial.name} to ${epub.name}" }
            books.attachEpub(bookId, epub.path)
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: ConversionException) {
            partial.delete()
            books.failConversion(bookId, e.message.orEmpty())
        } catch (e: OutOfMemoryError) {
            // Left unrecorded, the conversion would resume, and run out of memory, on every start.
            partial.delete()
            books.failConversion(bookId, "el PDF es demasiado grande para convertirlo en el teléfono")
        } catch (e: Exception) {
            // PdfBox throws plain runtime exceptions on malformed files; the reader still has the PDF.
            partial.delete()
            books.failConversion(bookId, "no se pudo leer el PDF (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            _progress.update { it - bookId }
        }
    }
}
