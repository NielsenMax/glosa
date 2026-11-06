package com.maxi.glosa.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.ExplainMode
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

@Composable
fun ExplainSheet(viewModel: ReaderViewModel, actions: ReaderChromeActions) {
    val state by viewModel.explain.collectAsStateWithLifecycle()
    val canSearchWeb by viewModel.canSearchWeb.collectAsStateWithLifecycle()
    val explain = state ?: return
    val pending = explain.pending
    val scroll = rememberScrollState()
    LaunchedEffect(pending?.answer?.length, pending?.steps, explain.turns.size) { scroll.animateScrollTo(scroll.maxValue) }
    val openSource = { source: Source ->
        val locator = source.locatorJson?.let { Locator.fromJSON(JSONObject(it)) }
        when {
            source.url != null -> actions.openUrl(source.url)
            locator != null -> actions.go(locator)
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "“${explain.passage.selected.trim()}”",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.go(explain.locator) }) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, "Ir al pasaje en el libro")
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(scroll).padding(vertical = 12.dp),
        ) {
            explain.turns.forEachIndexed { index, turn ->
                if (index > 0) Question(turn.question)
                AnswerText(turn.answer)
                if (turn.sources.isNotEmpty()) Sources(turn.sources, openSource)
            }
            if (pending != null) {
                if (explain.turns.isNotEmpty()) Question(pending.question)
                pending.steps.forEach { StepRow(it) }
                when {
                    explain.downloadedBytes != null -> Status("Descargando Gemini Nano al teléfono… ${explain.downloadedBytes / 1_000_000} MB")
                    pending.answer.isNotEmpty() -> AnswerText(pending.answer)
                    explain.error == null -> Status(if (pending.steps.any { it.status == StepStatus.Running }) "Buscando…" else "Pensando…")
                }
            }
            explain.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(ExplainMode.Simpler, ExplainMode.Example, ExplainMode.Translate).forEach { mode ->
                AssistChip(onClick = { viewModel.explainAgain(mode) }, label = { Text(mode.request) }, enabled = !explain.isGenerating)
            }
        }
        FollowUpField(enabled = !explain.isGenerating, onSend = viewModel::askFollowUp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            TextButton(onClick = viewModel::saveExplanationAsNote, enabled = explain.turns.isNotEmpty() && !explain.isSavedAsNote) {
                Icon(if (explain.isSavedAsNote) Icons.Filled.Check else Icons.Outlined.BookmarkAdd, null)
                Text(if (explain.isSavedAsNote) "Guardado" else "Guardar como nota", Modifier.padding(start = 8.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = actions::askClaude) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Text("Preguntar a Claude", Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            if (canSearchWeb) {
                "Responde Gemini Nano en el teléfono. Para buscar consulta el libro, tus notas, Wikipedia, Wiktionary y la web; solo salen del teléfono las palabras de la búsqueda."
            } else {
                "Responde Gemini Nano en el teléfono. Para buscar consulta el libro, tus notas, Wikipedia y Wiktionary; con una clave de Tavily en Ajustes también busca en la web."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun StepRow(step: ToolStep) {
    val status = step.status
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            StepStatus.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            is StepStatus.Found -> Icon(Icons.Outlined.TaskAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            is StepStatus.Failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
        Text(
            describe(step),
            style = MaterialTheme.typography.bodySmall,
            color = if (status is StepStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun describe(step: ToolStep): String {
    val query = "«${step.call.query}»"
    return when (val status = step.status) {
        StepStatus.Running -> when (step.call.tool) {
            SourceKind.Book -> "Buscando $query en el libro…"
            SourceKind.Wikipedia -> "Buscando $query en Wikipedia…"
            SourceKind.Dictionary -> "Buscando $query en el diccionario…"
            SourceKind.Web -> "Buscando $query en la web…"
            SourceKind.Notes -> "Buscando $query en tus notas…"
            SourceKind.Calculator -> "Calculando ${step.call.query}…"
        }
        is StepStatus.Found -> {
            val count = status.findings.size
            when (step.call.tool) {
                SourceKind.Book -> if (count == 0) "$query no aparece en el libro" else "$query aparece en el libro"
                SourceKind.Wikipedia -> status.findings.firstOrNull()?.let { "Wikipedia: ${it.source.title}" }
                    ?: "Wikipedia no tiene un artículo sobre $query"
                SourceKind.Dictionary -> if (count == 0) "$query no está en el diccionario" else "$query está en el diccionario"
                SourceKind.Web -> when (count) {
                    0 -> "Sin resultados en la web para $query"
                    1 -> "1 resultado en la web para $query"
                    else -> "$count resultados en la web para $query"
                }
                SourceKind.Notes -> when (count) {
                    0 -> "No hay notas tuyas sobre $query"
                    1 -> "1 nota tuya sobre $query"
                    else -> "$count notas tuyas sobre $query"
                }
                SourceKind.Calculator -> status.findings.firstOrNull()?.text.orEmpty()
            }
        }
        is StepStatus.Failed -> when (step.call.tool) {
            SourceKind.Book -> "No pude buscar en el libro: ${status.reason}"
            SourceKind.Wikipedia -> "No pude buscar en Wikipedia: ${status.reason}"
            SourceKind.Dictionary -> "No pude buscar en el diccionario: ${status.reason}"
            SourceKind.Web -> "No pude buscar en la web: ${status.reason}"
            SourceKind.Notes -> "No pude buscar en tus notas: ${status.reason}"
            SourceKind.Calculator -> "No pude hacer la cuenta: ${status.reason}"
        }
    }
}

@Composable
private fun Sources(sources: List<Source>, onOpen: (Source) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { index, source ->
            SuggestionChip(
                onClick = { onOpen(source) },
                enabled = source.url != null || source.locatorJson != null,
                icon = { Icon(icon(source.kind), null, Modifier.size(16.dp)) },
                label = { Text("${index + 1}. ${source.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

private fun icon(kind: SourceKind): ImageVector = when (kind) {
    SourceKind.Book -> Icons.AutoMirrored.Outlined.MenuBook
    SourceKind.Wikipedia -> Icons.Outlined.Language
    SourceKind.Dictionary -> Icons.Outlined.Translate
    SourceKind.Web -> Icons.Outlined.Public
    SourceKind.Notes -> Icons.Outlined.EditNote
    SourceKind.Calculator -> Icons.Outlined.Calculate
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
