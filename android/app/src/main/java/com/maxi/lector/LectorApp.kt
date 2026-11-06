package com.maxi.lector

import android.app.Application
import android.content.Context
import com.maxi.lector.books.BookImporter
import com.maxi.lector.books.BookOpener
import com.maxi.lector.books.OpenBooks
import com.maxi.lector.data.LibraryDatabase
import com.maxi.lector.explain.GeminiNano
import com.maxi.lector.reader.ReaderPreferencesStore

class LectorApp : Application() {
    val database by lazy { LibraryDatabase.open(this) }
    val bookOpener by lazy { BookOpener(this) }
    val importer by lazy { BookImporter(this, bookOpener, database.books()) }
    val openBooks = OpenBooks()
    val geminiNano by lazy { GeminiNano() }
    val readerPreferences by lazy { ReaderPreferencesStore(this) }
}

val Context.lectorApp: LectorApp get() = applicationContext as LectorApp
