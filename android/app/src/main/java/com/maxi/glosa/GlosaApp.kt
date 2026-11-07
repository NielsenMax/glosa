package com.maxi.glosa

import android.app.Application
import android.content.Context
import com.maxi.glosa.books.BookConverter
import com.maxi.glosa.books.BookImporter
import com.maxi.glosa.books.BookOpener
import com.maxi.glosa.books.OpenBooks
import com.maxi.glosa.data.LibraryDatabase
import com.maxi.glosa.explain.GeminiNano
import com.maxi.glosa.net.HttpJson
import com.maxi.glosa.reader.ReaderPreferencesStore
import com.maxi.glosa.settings.AppSettings
import com.maxi.glosa.update.ApkInstaller
import com.maxi.glosa.update.ReleaseFeed
import com.maxi.glosa.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlosaApp : Application() {
    val database by lazy { LibraryDatabase.open(this) }
    val bookOpener by lazy { BookOpener(this) }
    val converter by lazy { BookConverter(this, database.books(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    val importer by lazy { BookImporter(this, bookOpener, database.books(), converter) }
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

    val updater by lazy {
        Updater(
            feed = ReleaseFeed(http),
            installer = ApkInstaller(this),
            installedVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode,
            prefs = getSharedPreferences("updates", MODE_PRIVATE),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        converter.resumePending()
    }
}

val Context.glosaApp: GlosaApp get() = applicationContext as GlosaApp
