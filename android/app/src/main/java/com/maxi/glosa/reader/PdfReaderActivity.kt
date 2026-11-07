@file:OptIn(ExperimentalReadiumApi::class)

package com.maxi.glosa.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maxi.glosa.R
import com.maxi.glosa.data.BookFormat
import com.maxi.glosa.glosaApp
import com.maxi.glosa.settings.Paper
import com.maxi.glosa.ui.GlosaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression

/** The PDF as printed. Its pages have no colors of their own to theme, so the whole view is tinted to the paper. */
class PdfReaderActivity : AppCompatActivity() {
    private val viewModel: PdfReaderViewModel by viewModels()
    private lateinit var navigator: PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>
    private lateinit var pages: View

    /** The activity handles uiMode changes itself, so the page keeps its place when dark mode flips. */
    private val isNight = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val session = glosaApp.openBooks.get(bookId, BookFormat.Pdf)
        if (session == null) {
            super.onCreate(null)
            finish()
            return
        }
        supportFragmentManager.fragmentFactory = PdfNavigatorFactory(session.publication, PdfiumEngineProvider()).createFragmentFactory(
            initialLocator = session.book.pdfLocatorJson?.let { Locator.fromJSON(JSONObject(it)) },
            initialPreferences = PdfiumPreferences(fit = Fit.WIDTH, pageSpacing = PAGE_SPACING),
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isNight.value = resources.configuration.isNightModeActive

        val root = FrameLayout(this)
        pages = FragmentContainerView(this).apply {
            id = R.id.navigator_container
            setBackgroundColor(GUTTER)
        }
        root.addView(pages, MATCH_PARENT, MATCH_PARENT)
        root.addView(
            ComposeView(this).apply {
                setContent {
                    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
                    val sepiaTone by viewModel.sepiaTone.collectAsStateWithLifecycle()
                    GlosaTheme(appearance, sepiaTone) {
                        PdfReaderChrome(viewModel = viewModel, actions = chromeActions)
                    }
                }
            },
            MATCH_PARENT,
            MATCH_PARENT,
        )
        setContentView(root)

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.navigator_container, PdfNavigatorFragment::class.java, null, NAVIGATOR_TAG)
            }
        }
        @Suppress("UNCHECKED_CAST")
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>
        wireNavigator()
    }

    private fun wireNavigator() {
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                viewModel.toggleChrome()
                return true
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { navigator.currentLocator.collect(viewModel::onLocatorChanged) }
                launch {
                    combine(viewModel.appearance, viewModel.sepiaTone, isNight) { appearance, tone, night -> appearance.paper(night, tone) }
                        .collect(::tint)
                }
                launch { viewModel.isChromeVisible.collect(::showSystemBars) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ReaderEvent.Open -> {
                                startActivity(Readers.intent(this@PdfReaderActivity, viewModel.session.book.id, event.format))
                                finish()
                            }
                            is ReaderEvent.Message -> Toast.makeText(this@PdfReaderActivity, event.text, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /** Draws the pages through a color matrix that maps their white to the paper and their black to its ink. */
    private fun tint(paper: Paper?) {
        if (paper == null) {
            pages.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(paper.colorMatrix())) }
        pages.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isNight.value = newConfig.isNightModeActive
        enableEdgeToEdge()
    }

    private fun showSystemBars(isVisible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isVisible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private val chromeActions = object : PdfReaderActions {
        override fun close() = finish()

        override fun go(link: Link) {
            navigator.go(link, animated = false)
            viewModel.dismissSheet()
            viewModel.hideChrome()
        }

        override fun goToProgression(progression: Double) {
            lifecycleScope.launch {
                viewModel.session.publication.locateProgression(progression)?.let { navigator.go(it) }
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val NAVIGATOR_TAG = "navigator"
        private const val PAGE_SPACING = 6.0

        /** Between pages; the tint turns it into a darker shade of the paper. */
        private const val GUTTER = 0xFFD6D6D6.toInt()

        fun intent(context: Context, bookId: Long): Intent =
            Intent(context, PdfReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
