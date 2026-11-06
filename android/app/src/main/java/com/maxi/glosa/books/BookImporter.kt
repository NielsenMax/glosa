package com.maxi.glosa.books

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import com.maxi.glosa.data.Book
import com.maxi.glosa.data.BookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.coverFitting
import java.io.File
import java.util.UUID

class BookImporter(
    private val context: Context,
    private val opener: BookOpener,
    private val books: BookDao,
) {
    private val booksDir get() = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir get() = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * Copies the document into app storage so it stays readable after the picker grant expires.
     * Re-importing a known book reuses its entry: cloud backup restores progress and notes but not
     * the EPUB files, which exceed its size limit.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString()
        val file = File(booksDir, "$name.epub")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BookOpenException("No se pudo abrir el archivo elegido")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }

        val publication = try {
            opener.open(file)
        } catch (e: BookOpenException) {
            file.delete()
            throw e
        }
        try {
            val title = publication.metadata.title ?: displayName(uri) ?: "Sin título"
            val author = publication.metadata.authors.firstOrNull()?.name
            val known = books.findByTitle(title, author)
            if (known != null && File(known.filePath).exists()) {
                file.delete()
                return@withContext known.id
            }
            val cover = publication.coverFitting(Size(600, 900))?.let { saveCover(it, File(coversDir, "$name.jpg")) }
            if (known != null) {
                books.relink(known.id, file.path, cover?.path)
                return@withContext known.id
            }
            books.insert(
                Book(
                    title = title,
                    author = author,
                    filePath = file.path,
                    coverPath = cover?.path,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            publication.close()
        }
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        books.delete(book)
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
    }

    private fun saveCover(bitmap: Bitmap, file: File): File {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        }
}
