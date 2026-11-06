package com.maxi.lector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.maxi.lector.library.LibraryScreen
import com.maxi.lector.library.LibraryViewModel
import com.maxi.lector.reader.ReaderActivity
import com.maxi.lector.ui.LectorTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LectorTheme {
                LibraryScreen(viewModel, onOpenReader = { startActivity(ReaderActivity.intent(this, it)) })
            }
        }
    }
}
