@file:OptIn(ExperimentalReadiumApi::class)

package com.maxi.glosa.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
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
import com.maxi.glosa.data.Explanation
import com.maxi.glosa.data.Highlight
import com.maxi.glosa.data.HighlightColor
import com.maxi.glosa.explain.ClaudeHandoff
import com.maxi.glosa.glosaApp
import com.maxi.glosa.settings.Appearance
import com.maxi.glosa.ui.GlosaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression

class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()
    private lateinit var navigator: EpubNavigatorFragment

    /** The activity handles uiMode changes itself, so the page keeps its place when dark mode flips. */
    private val isNight = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val session = glosaApp.openBooks.get(bookId)
        if (session == null) {
            super.onCreate(null)
            finish()
            return
        }
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(session.publication).createFragmentFactory(
            initialLocator = session.book.locatorJson?.let { Locator.fromJSON(JSONObject(it)) },
            initialPreferences = pagePreferences(
                glosaApp.readerPreferences.preferences.value,
                glosaApp.settings.appearance.value,
                resources.configuration.isNightModeActive,
            ),
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionCallback),
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isNight.value = resources.configuration.isNightModeActive

        val root = FrameLayout(this)
        root.addView(FragmentContainerView(this).apply { id = R.id.navigator_container }, MATCH_PARENT, MATCH_PARENT)
        root.addView(
            ComposeView(this).apply {
                setContent {
                    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
                    GlosaTheme(appearance) {
                        ReaderChrome(viewModel = viewModel, actions = chromeActions)
                    }
                }
            },
            MATCH_PARENT,
            MATCH_PARENT,
        )
        setContentView(root)

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.navigator_container, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment
        wireNavigator()
    }

    private fun wireNavigator() {
        navigator.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                viewModel.toggleChrome()
                return true
            }
        })
        navigator.addDecorationListener(HIGHLIGHTS, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id.removePrefix(HIGHLIGHT_PREFIX).toLongOrNull() ?: return false
                viewModel.showSheet(ReaderSheet.HighlightDetail(id))
                return true
            }
        })
        navigator.addDecorationListener(EXPLANATIONS, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id.removePrefix(EXPLANATION_PREFIX).toLongOrNull() ?: return false
                viewModel.openExplanation(id)
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { navigator.currentLocator.collect(viewModel::onLocatorChanged) }
                launch { viewModel.highlights.collect { navigator.applyDecorations(it.mapNotNull(::decoration), HIGHLIGHTS) } }
                launch {
                    combine(viewModel.preferences, viewModel.appearance, isNight, ::pagePreferences)
                        .collect(navigator::submitPreferences)
                }
                launch {
                    viewModel.explanations.collect { threads ->
                        navigator.applyDecorations(threads.mapNotNull { explanationMark(it.explanation) }, EXPLANATIONS)
                    }
                }
                launch { viewModel.isChromeVisible.collect(::showSystemBars) }
            }
        }
    }

    private fun decoration(highlight: Highlight): Decoration? {
        val locator = highlight.locator() ?: return null
        return Decoration(
            id = "$HIGHLIGHT_PREFIX${highlight.id}",
            locator = locator,
            style = Decoration.Style.Highlight(tint = highlight.color.argb.toInt()),
        )
    }

    /** A highlight rather than an underline: Readium's night theme repaints every border in the text color. */
    private fun explanationMark(explanation: Explanation): Decoration? {
        val locator = explanation.locator() ?: return null
        return Decoration(
            id = "$EXPLANATION_PREFIX${explanation.id}",
            locator = locator,
            style = Decoration.Style.Highlight(tint = EXPLANATION_TINT),
        )
    }

    /** The app's appearance decides the page colors, so the book and the app chrome always match. */
    private fun pagePreferences(stored: EpubPreferences, appearance: Appearance, isNight: Boolean): EpubPreferences {
        val theme = appearance.pageTheme(isNight)
        return stored.copy(theme = theme, imageFilter = if (theme == Theme.DARK) ImageFilter.DARKEN else null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isNight.value = newConfig.isNightModeActive
        enableEdgeToEdge()
    }

    private fun showSystemBars(isVisible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private val chromeActions = object : ReaderChromeActions {
        override fun close() = finish()
        override fun go(link: Link) {
            navigator.go(link, animated = false)
            viewModel.dismissSheet()
            viewModel.hideChrome()
        }
        override fun go(locator: Locator) {
            navigator.go(locator, animated = false)
            viewModel.dismissSheet()
            viewModel.hideChrome()
        }
        override fun goToProgression(progression: Double) {
            lifecycleScope.launch {
                viewModel.session.publication.locateProgression(progression)?.let { navigator.go(it) }
            }
        }
        override fun askClaude() {
            viewModel.claudeHandoffText()?.let { ClaudeHandoff.send(this@ReaderActivity, it) }
        }
        override fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@ReaderActivity, "No hay un navegador para abrir $url", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.action_explain, 0, R.string.action_explain)
            menu.add(Menu.NONE, R.id.action_highlight, 1, R.string.action_highlight)
            menu.add(Menu.NONE, R.id.action_note, 2, R.string.action_note)
            menu.add(Menu.NONE, R.id.action_copy, 3, android.R.string.copy)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            lifecycleScope.launch {
                val selection = navigator.currentSelection() ?: return@launch
                when (item.itemId) {
                    R.id.action_explain -> viewModel.explain(selection.locator)
                    R.id.action_highlight -> viewModel.addHighlight(selection.locator, HighlightColor.Yellow)
                    R.id.action_note -> viewModel.addHighlightWithNote(selection.locator)
                    R.id.action_copy -> copy(selection.locator.text.highlight.orEmpty())
                }
                navigator.clearSelection()
                mode.finish()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Texto", text))
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        private const val NAVIGATOR_TAG = "navigator"
        private const val HIGHLIGHTS = "highlights"
        private const val HIGHLIGHT_PREFIX = "highlight-"
        private const val EXPLANATIONS = "explanations"
        private const val EXPLANATION_PREFIX = "explanation-"
        private const val EXPLANATION_TINT = 0xFF7E57C2.toInt()

        fun intent(context: Context, bookId: Long): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
