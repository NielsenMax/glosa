package com.maxi.glosa

import android.app.Application
import android.content.Context
import com.maxi.glosa.books.BookImporter
import com.maxi.glosa.books.BookOpener
import com.maxi.glosa.books.OpenBooks
import com.maxi.glosa.data.LibraryDatabase
import com.maxi.glosa.explain.GeminiNano
import com.maxi.glosa.net.HttpJson
import com.maxi.glosa.reader.ReaderPreferencesStore
import com.maxi.glosa.settings.AppSettings

class GlosaApp : Application() {
    val database by lazy { LibraryDatabase.open(this) }
    val bookOpener by lazy { BookOpener(this) }
    val importer by lazy { BookImporter(this, bookOpener, database.books()) }
    val openBooks = OpenBooks()
    val geminiNano by lazy { GeminiNano() }
    val readerPreferences by lazy { ReaderPreferencesStore(this) }
    lateinit var settings: AppSettings
        private set

    /** Wikimedia asks API clients to identify themselves with a name and a way to find the project. */
    val http by lazy {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        HttpJson(userAgent = "Glosa/$version (https://github.com/NielsenMax/glosa)")
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }
}

val Context.glosaApp: GlosaApp get() = applicationContext as GlosaApp
