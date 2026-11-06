package com.maxi.glosa.reader

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.data.Bookmark
import com.maxi.glosa.data.Highlight
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

private enum class ContentsTab(val label: String) { Toc("Índice"), Search("Buscar"), Highlights("Notas"), Bookmarks("Marcadores") }

@Composable
fun ContentsSheet(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            ContentsTab.entries.forEachIndexed { index, entry ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(entry.label) })
            }
        }
        when (ContentsTab.entries[tab]) {
            ContentsTab.Toc -> TocList(viewModel, actions)
            ContentsTab.Search -> SearchPanel(viewModel, actions)
            ContentsTab.Highlights -> HighlightList(viewModel, actions)
            ContentsTab.Bookmarks -> BookmarkList(viewModel, actions)
        }
    }
}

@Composable
private fun TocList(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val locator by viewModel.currentLocator.collectAsStateWithLifecycle()
    val currentHref = locator?.href?.toString()?.substringBefore('#')
    LazyColumn {
        items(viewModel.tableOfContents) { entry ->
            val isCurrent = entry.link.href.toString().substringBefore('#') == currentHref
            Text(
                entry.title,
                style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.go(entry.link) }
                    .padding(start = (20 + entry.depth * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SearchPanel(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val state by viewModel.search.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar en el libro") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (!state.isSearching && state.query.isNotBlank() && state.hits.isEmpty() && state.error == null) {
            Text("Sin resultados", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.hits.isNotEmpty()) {
            Text(
                "${state.hits.size} resultados",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn {
            items(state.hits) { hit -> SearchHitRow(hit) { actions.go(hit) } }
        }
    }
}

@Composable
private fun SearchHitRow(hit: Locator, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            buildAnnotatedString {
                append("…" + hit.text.before.orEmpty().takeLast(60).trimStart())
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFD54F))) {
                    append(hit.text.highlight.orEmpty())
                }
                append(hit.text.after.orEmpty().take(60).trimEnd() + "…")
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
        Text(
            positionLabel(hit.title, hit.locations.totalProgression),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun HighlightList(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (highlights.isEmpty()) {
        EmptyHint("Mantené apretado sobre el texto para resaltar, anotar o pedir una explicación.")
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/markdown")
                    .putExtra(Intent.EXTRA_SUBJECT, "Notas — ${viewModel.session.book.title}")
                    .putExtra(Intent.EXTRA_TEXT, viewModel.highlightsAsMarkdown())
                context.startActivity(Intent.createChooser(share, "Exportar notas"))
            }) {
                Icon(Icons.Outlined.IosShare, null)
                Text("Exportar en Markdown", Modifier.padding(start = 8.dp))
            }
        }
        LazyColumn {
            items(highlights, key = { it.id }) { highlight ->
                HighlightRow(
                    highlight = highlight,
                    onOpen = { highlight.locator()?.let(actions::go) },
                    onEdit = { viewModel.showSheet(ReaderSheet.HighlightDetail(highlight.id)) },
                )
            }
        }
    }
}

@Composable
private fun HighlightRow(highlight: Highlight, onOpen: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Box(Modifier.width(4.dp).height(48.dp).background(Color(highlight.color.argb)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(highlight.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            highlight.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                positionLabel(highlight.chapter, highlight.progression),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = onEdit) { Text("Editar") }
    }
    HorizontalDivider()
}

@Composable
private fun BookmarkList(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    if (bookmarks.isEmpty()) {
        EmptyHint("Tocá el ícono de marcador arriba para guardar la página en la que estás.")
        return
    }
    LazyColumn {
        items(bookmarks, key = { it.id }) { bookmark -> BookmarkRow(bookmark, viewModel, actions) }
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { bookmark.locator()?.let(actions::go) }
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(positionLabel(bookmark.chapter, bookmark.progression), style = MaterialTheme.typography.bodyMedium)
            bookmark.excerpt?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { viewModel.deleteBookmark(bookmark.id) }) { Icon(Icons.Outlined.Delete, "Borrar marcador") }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

fun positionLabel(chapter: String?, progression: Double?): String =
    listOfNotNull(chapter, progression?.let { "${(it * 100).roundToInt()}%" }).joinToString(" · ")
