package com.maxi.glosa.books

import android.content.Context
import com.maxi.glosa.data.Book
import com.maxi.glosa.data.BookFormat
import java.io.File
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class BookOpenException(message: String) : Exception(message)

class BookOpener(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = PdfiumDocumentFactory(context)),
    )

    suspend fun open(file: File): Publication {
        val asset = assetRetriever.retrieve(file).getOrElse {
            throw BookOpenException("No se pudo leer ${file.name}: ${it.message}")
        }
        return publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            throw BookOpenException("${file.name} no es un EPUB ni un PDF que se pueda abrir: ${it.message}")
        }
    }

    /** Opens the book in [format], which must be one the book has. */
    suspend fun open(book: Book, format: BookFormat): ReaderSession {
        val path = when (format) {
            BookFormat.Epub -> book.epubPath
            BookFormat.Pdf -> book.pdfPath
        } ?: throw BookOpenException("«${book.title}» no está disponible como ${format.name.uppercase()}")
        return ReaderSession(book, format, open(File(path)))
    }
}

data class ReaderSession(val book: Book, val format: BookFormat, val publication: Publication)

/** Publications opened from the library and handed to a reader screen. Main thread only. */
class OpenBooks {
    private val sessions = mutableMapOf<Pair<Long, BookFormat>, ReaderSession>()

    fun put(session: ReaderSession) {
        sessions[session.book.id to session.format] = session
    }

    fun get(bookId: Long, format: BookFormat): ReaderSession? = sessions[bookId to format]

    /** Closes the session's publication; a session opened since for the same book and format stays. */
    fun close(session: ReaderSession) {
        val key = session.book.id to session.format
        if (sessions[key] === session) sessions.remove(key)
        session.publication.close()
    }
}
