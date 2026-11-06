package com.maxi.glosa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.library.LibraryScreen
import com.maxi.glosa.library.LibraryViewModel
import com.maxi.glosa.reader.ReaderActivity
import com.maxi.glosa.ui.GlosaTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by glosaApp.settings.appearance.collectAsStateWithLifecycle()
            GlosaTheme(appearance) {
                LibraryScreen(viewModel, glosaApp.settings, onOpenReader = { startActivity(ReaderActivity.intent(this, it)) })
            }
        }
        if (savedInstanceState == null) openSharedBook(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSharedBook(intent)
    }

    /** An EPUB tapped in a file manager or shared from another app gets imported and opened. */
    private fun openSharedBook(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        viewModel.importAndOpen(uri)
    }
}
