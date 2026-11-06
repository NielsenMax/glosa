@file:OptIn(ExperimentalReadiumApi::class)

package com.maxi.lector.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maxi.lector.R
import com.maxi.lector.data.Highlight
import com.maxi.lector.data.HighlightColor
import com.maxi.lector.explain.ClaudeHandoff
import com.maxi.lector.lectorApp
import com.maxi.lector.ui.LectorTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression

class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()
    private lateinit var navigator: EpubNavigatorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val session = lectorApp.openBooks.get(bookId)
        if (session == null) {
            super.onCreate(null)
            finish()
            return
        }
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(session.publication).createFragmentFactory(
            initialLocator = session.book.locatorJson?.let { Locator.fromJSON(JSONObject(it)) },
            initialPreferences = lectorApp.readerPreferences.preferences.value,
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionCallback),
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = FrameLayout(this)
        root.addView(FragmentContainerView(this).apply { id = R.id.navigator_container }, MATCH_PARENT, MATCH_PARENT)
        root.addView(
            ComposeView(this).apply {
                setContent {
                    LectorTheme {
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { navigator.currentLocator.collect(viewModel::onLocatorChanged) }
                launch { viewModel.highlights.collect { navigator.applyDecorations(it.mapNotNull(::decoration), HIGHLIGHTS) } }
                launch { viewModel.preferences.collect(navigator::submitPreferences) }
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

        fun intent(context: Context, bookId: Long): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
