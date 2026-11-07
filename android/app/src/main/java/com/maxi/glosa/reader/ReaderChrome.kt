package com.maxi.glosa.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

interface ReaderChromeActions {
    fun close()
    fun go(link: Link)
    fun go(locator: Locator)
    fun goToProgression(progression: Double)
    fun askClaude()
    fun openUrl(url: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChrome(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val isChromeVisible by viewModel.isChromeVisible.collectAsStateWithLifecycle()
    val locator by viewModel.currentLocator.collectAsStateWithLifecycle()
    val bookmark by viewModel.currentBookmark.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(title = viewModel.session.book.title, subtitle = locator?.title, onClose = actions::close) {
                val isBookmarked = bookmark != null
                IconButton(onClick = viewModel::toggleBookmark) {
                    Icon(
                        if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (isBookmarked) "Quitar marcador" else "Marcar esta página",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (viewModel.canOpenAsPdf) {
                    IconButton(onClick = viewModel::openAsPdf) { Icon(Icons.Filled.PictureAsPdf, "Ver esta página en el PDF original") }
                }
                IconButton(onClick = { viewModel.showSheet(ReaderSheet.Contents) }) {
                    Icon(Icons.AutoMirrored.Filled.List, "Índice, notas, marcadores y explicaciones")
                }
                IconButton(onClick = { viewModel.showSheet(ReaderSheet.Settings) }) { Icon(Icons.Filled.TextFields, "Letra y apariencia") }
            }
        }
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(label = locator?.title, progression = locator?.locations?.totalProgression, onSeek = actions::goToProgression)
        }
    }

    val current = sheet ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = current is ReaderSheet.Explain)
    ModalBottomSheet(onDismissRequest = viewModel::dismissSheet, sheetState = sheetState) {
        when (current) {
            ReaderSheet.Contents -> ContentsSheet(viewModel, actions)
            ReaderSheet.Settings -> SettingsSheet(viewModel)
            ReaderSheet.Explain -> ExplainSheet(viewModel, actions)
            is ReaderSheet.HighlightDetail -> HighlightSheet(viewModel, current.highlightId)
        }
    }
}

@Composable
internal fun TopBar(title: String, subtitle: String?, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a la biblioteca") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

@Composable
internal fun BottomBar(label: String?, progression: Double?, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: progression?.toFloat() ?: 0f

    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${(shown * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toDouble()) }
                    dragging = null
                },
            )
        }
    }
}
