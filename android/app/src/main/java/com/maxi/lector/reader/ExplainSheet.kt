package com.maxi.lector.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.lector.explain.ExplainMode

@Composable
fun ExplainSheet(viewModel: ReaderViewModel, onAskClaude: () -> Unit) {
    val state by viewModel.explain.collectAsStateWithLifecycle()
    val explain = state ?: return
    val scroll = rememberScrollState()
    LaunchedEffect(explain.streamingAnswer.length, explain.exchanges.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Text(
            "“${explain.passage.selected.trim()}”",
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(scroll).padding(vertical = 12.dp),
        ) {
            explain.exchanges.forEachIndexed { index, exchange ->
                if (index > 0) Question(exchange.question)
                Text(exchange.answer, style = MaterialTheme.typography.bodyLarge)
            }
            explain.pendingQuestion?.let { question ->
                if (explain.exchanges.isNotEmpty()) Question(question)
                when {
                    explain.downloadedBytes != null -> Status("Descargando Gemini Nano al teléfono… ${explain.downloadedBytes / 1_000_000} MB")
                    explain.streamingAnswer.isEmpty() && explain.isGenerating -> Status("Pensando…")
                    else -> Text(explain.streamingAnswer, style = MaterialTheme.typography.bodyLarge)
                }
            }
            explain.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ExplainMode.Simpler, ExplainMode.Example, ExplainMode.Translate).forEach { mode ->
                AssistChip(onClick = { viewModel.explainAgain(mode) }, label = { Text(mode.label) }, enabled = !explain.isGenerating)
            }
        }
        FollowUpField(enabled = !explain.isGenerating, onSend = viewModel::askFollowUp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            TextButton(onClick = viewModel::saveExplanationAsNote, enabled = explain.exchanges.isNotEmpty() && !explain.isSavedAsNote) {
                Icon(if (explain.isSavedAsNote) Icons.Filled.Check else Icons.Outlined.BookmarkAdd, null)
                Text(if (explain.isSavedAsNote) "Guardado" else "Guardar como nota", Modifier.padding(start = 8.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onAskClaude) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Text("Preguntar a Claude", Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            "Respuesta generada en el teléfono con Gemini Nano. Si no alcanza, Claude recibe la pregunta ya armada.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Status(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, modifier = Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FollowUpField(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Preguntá algo más sobre esto…") },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = { IconButton(onClick = send, enabled = enabled && text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") } },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
