package com.maxi.glosa.books

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import com.maxi.glosa.data.Book
import com.maxi.glosa.data.BookDao
import com.maxi.glosa.data.BookFormat
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting

class BookImporter(
    private val context: Context,
    private val opener: BookOpener,
    private val books: BookDao,
    private val converter: BookConverter,
) {
    private val booksDir get() = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir get() = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * Copies the document into app storage so it stays readable after the picker grant expires.
     * Re-importing a known book reuses its entry: cloud backup restores progress and notes but not
     * the book files, which exceed its size limit. A PDF starts converting to EPUB right away.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString()
        val incoming = File(booksDir, "$name.part")
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw BookOpenException("La app que compartió el archivo no le dio permiso a Glosa para leerlo. Agregalo desde el botón «Agregar libro».")
        } catch (e: FileNotFoundException) {
            throw BookOpenException("No se encontró el archivo elegido: ${e.message}")
        } ?: throw BookOpenException("No se pudo abrir el archivo elegido")
        input.use { source -> incoming.outputStream().use { source.copyTo(it) } }
        val format = if (isPdf(incoming)) BookFormat.Pdf else BookFormat.Epub
        val file = File(booksDir, "$name.${format.name.lowercase()}")
        incoming.renameTo(file)

        val publication = try {
            opener.open(file)
        } catch (e: BookOpenException) {
            file.delete()
            throw e
        }
        try {
            val title = publication.metadata.title ?: displayName(uri) ?: "Sin título"
            val author = publication.metadata.authors.firstOrNull()?.name
            when (format) {
                BookFormat.Epub -> importEpub(file, name, publication, title, author)
                BookFormat.Pdf -> importPdf(file, name, publication, title, author)
            }
        } finally {
            publication.close()
        }
    }

    private suspend fun importEpub(file: File, name: String, publication: Publication, title: String, author: String?): Long {
        val known = books.findByTitle(title, author)?.takeIf { it.pdfPath == null }
        if (known?.epubPath != null && File(known.epubPath).exists()) {
            file.delete()
            return known.id
        }
        val cover = saveCover(publication, name)
        if (known != null) {
            books.relinkEpub(known.id, file.path, cover?.path)
            return known.id
        }
        return books.insert(
            Book(title = title, author = author, epubPath = file.path, coverPath = cover?.path, addedAt = System.currentTimeMillis()),
        )
    }

    private suspend fun importPdf(file: File, name: String, publication: Publication, title: String, author: String?): Long {
        val known = books.findByTitle(title, author)?.takeIf { it.pdfPath != null }
        if (known?.pdfPath != null && File(known.pdfPath).exists()) {
            file.delete()
            return known.id
        }
        val cover = saveCover(publication, name)
        val id = if (known != null) {
            books.relinkPdf(known.id, file.path, cover?.path)
            known.id
        } else {
            books.insert(
                Book(
                    title = title,
                    author = author,
                    epubPath = null,
                    pdfPath = file.path,
                    coverPath = cover?.path,
                    format = BookFormat.Pdf,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
        converter.enqueue(id)
        return id
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        converter.cancel(book.id)
        books.delete(book)
        listOfNotNull(book.epubPath, book.pdfPath, book.coverPath).forEach { File(it).delete() }
    }

    private suspend fun saveCover(publication: Publication, name: String): File? {
        val bitmap = publication.coverFitting(Size(600, 900)) ?: return null
        val file = File(coversDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file
    }

    private fun isPdf(file: File): Boolean {
        val header = ByteArray(PDF_SIGNATURE.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(PDF_SIGNATURE)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        }

    private companion object {
        val PDF_SIGNATURE = "%PDF-".toByteArray()
    }
}
