package com.maxi.glosa

import android.app.Application
import android.content.Context
import com.maxi.glosa.books.BookImporter
import com.maxi.glosa.books.BookOpener
import com.maxi.glosa.books.OpenBooks
import com.maxi.glosa.data.LibraryDatabase
import com.maxi.glosa.explain.GeminiNano
import com.maxi.glosa.reader.ReaderPreferencesStore

class GlosaApp : Application() {
    val database by lazy { LibraryDatabase.open(this) }
    val bookOpener by lazy { BookOpener(this) }
    val importer by lazy { BookImporter(this, bookOpener, database.books()) }
    val openBooks = OpenBooks()
    val geminiNano by lazy { GeminiNano() }
    val readerPreferences by lazy { ReaderPreferencesStore(this) }
}

val Context.glosaApp: GlosaApp get() = applicationContext as GlosaApp
