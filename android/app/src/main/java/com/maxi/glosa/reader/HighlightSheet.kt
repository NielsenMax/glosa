package com.maxi.glosa.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.data.HighlightColor

@Composable
fun HighlightSheet(viewModel: ReaderViewModel, highlightId: Long) {
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val highlight = highlights.firstOrNull { it.id == highlightId } ?: return
    var note by rememberSaveable(highlightId) { mutableStateOf(highlight.note.orEmpty()) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "“${highlight.text}”",
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HighlightColor.entries.forEach { color ->
                val isSelected = color == highlight.color
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Color(color.argb), CircleShape)
                        .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { viewModel.recolorHighlight(highlight.id, color) },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Nota") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { viewModel.deleteHighlight(highlight.id) }) {
                Icon(Icons.Outlined.Delete, null)
                Text("Borrar", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { highlight.locator()?.let { viewModel.explain(it, highlightId = highlight.id) } }) {
                Icon(Icons.Filled.AutoAwesome, null)
                Text("Explicar", Modifier.padding(start = 8.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        viewModel.saveNote(highlight.id, note)
                        viewModel.dismissSheet()
                    },
                    enabled = note != highlight.note.orEmpty(),
                ) { Text("Guardar nota") }
            }
        }
    }
}
