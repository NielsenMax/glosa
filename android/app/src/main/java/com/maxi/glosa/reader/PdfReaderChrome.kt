package com.maxi.glosa.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.data.Book
import com.maxi.glosa.settings.AppearanceControls
import org.readium.r2.shared.publication.Link

interface PdfReaderActions {
    fun close()
    fun go(link: Link)
    fun goToProgression(progression: Double)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderChrome(viewModel: PdfReaderViewModel, actions: PdfReaderActions) {
    val isChromeVisible by viewModel.isChromeVisible.collectAsStateWithLifecycle()
    val locator by viewModel.currentLocator.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    val progress by viewModel.conversionProgress.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val page = locator?.locations?.position
    val pageLabel = page?.let { current -> "Página $current" + viewModel.pageCount?.let { " de $it" }.orEmpty() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(title = book.title, subtitle = pageLabel, onClose = actions::close) {
                EpubButton(book, progress, onOpen = viewModel::openAsEpub, onError = viewModel::showConversionError)
                IconButton(onClick = { viewModel.showSheet(PdfSheet.Contents) }) { Icon(Icons.AutoMirrored.Filled.List, "Índice") }
                IconButton(onClick = { viewModel.showSheet(PdfSheet.Appearance) }) { Icon(Icons.Filled.Contrast, "Apariencia") }
            }
        }
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(label = pageLabel, progression = locator?.locations?.totalProgression, onSeek = actions::goToProgression)
        }
    }

    val current = sheet ?: return
    ModalBottomSheet(onDismissRequest = viewModel::dismissSheet) {
        when (current) {
            PdfSheet.Contents -> PdfContents(viewModel.tableOfContents, page, onSelect = { actions.go(it.link) })
            PdfSheet.Appearance -> PdfAppearance(viewModel)
        }
    }
}

/** Reading the converted EPUB, or why it cannot be read yet. */
@Composable
private fun EpubButton(book: Book, progress: Float?, onOpen: () -> Unit, onError: () -> Unit) {
    when {
        book.epubPath != null -> IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, "Leer como texto, con letra ajustable y explicaciones")
        }
        book.conversionError != null -> IconButton(onClick = onError) {
            Icon(Icons.Outlined.ErrorOutline, "No se pudo convertir a EPUB", tint = MaterialTheme.colorScheme.error)
        }
        else -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = "Convirtiendo a EPUB" + progress?.let { ": ${(it * 100).toInt()}%" }.orEmpty()
            },
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
    }
}

/** The bookmarks of the PDF; the current one is the last that starts at or before the page shown. */
@Composable
private fun PdfContents(entries: List<TocEntry>, page: Int?, onSelect: (TocEntry) -> Unit) {
    val current = page?.let { shown -> entries.lastOrNull { (it.page() ?: Int.MAX_VALUE) <= shown } }
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        if (entries.isEmpty()) {
            Text(
                "Este PDF no tiene índice.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            TocRows(entries, isCurrent = { it == current }, onSelect = onSelect)
        }
    }
}

private fun TocEntry.page(): Int? = link.href.toString().substringAfter("#page=", "").toIntOrNull()

@Composable
private fun PdfAppearance(viewModel: PdfReaderViewModel) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val sepiaTone by viewModel.sepiaTone.collectAsStateWithLifecycle()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
    ) {
        Text("Apariencia", style = MaterialTheme.typography.labelLarge)
        AppearanceControls(appearance, sepiaTone, viewModel::setAppearance, viewModel::setSepiaTone)
        Text(
            "Las páginas del PDF toman el color del papel: sepia las entibia y oscuro las invierte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
