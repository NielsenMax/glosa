package com.maxi.glosa.books

import android.content.Context
import com.maxi.glosa.data.Book
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class BookOpenException(message: String) : Exception(message)

class BookOpener(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null),
    )

    suspend fun open(file: File): Publication {
        val asset = assetRetriever.retrieve(file).getOrElse {
            throw BookOpenException("No se pudo leer ${file.name}: ${it.message}")
        }
        return publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            throw BookOpenException("${file.name} no es un EPUB que se pueda abrir: ${it.message}")
        }
    }
}

data class ReaderSession(val book: Book, val publication: Publication)

/** Publications opened from the library and handed to the reader screen. Main thread only. */
class OpenBooks {
    private val sessions = mutableMapOf<Long, ReaderSession>()

    fun put(session: ReaderSession) {
        close(session.book.id)
        sessions[session.book.id] = session
    }

    fun get(bookId: Long): ReaderSession? = sessions[bookId]

    fun close(bookId: Long) {
        sessions.remove(bookId)?.publication?.close()
    }
}
