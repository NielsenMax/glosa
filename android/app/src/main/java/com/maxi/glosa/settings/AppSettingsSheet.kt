package com.maxi.glosa.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppearancePicker(selected: Appearance, onSelect: (Appearance) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Appearance.entries.forEachIndexed { index, appearance ->
            SegmentedButton(
                selected = selected == appearance,
                onClick = { onSelect(appearance) },
                shape = SegmentedButtonDefaults.itemShape(index, Appearance.entries.size),
            ) { Text(appearance.label, maxLines = 1) }
        }
    }
}

@Composable
fun AppSettingsSheet(settings: AppSettings) {
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val savedKey by settings.webSearchKey.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf(savedKey.orEmpty()) }
    val context = LocalContext.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    ) {
        Text("Apariencia", style = MaterialTheme.typography.titleMedium)
        AppearancePicker(appearance, settings::setAppearance)
        Text(
            "Automático sigue el modo oscuro del teléfono. Se aplica a la biblioteca y a las páginas del libro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Búsqueda en la web", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Para explicar, Glosa ya busca en el libro, en tus notas, en Wikipedia y en Wiktionary. Con una clave gratuita de Tavily " +
                "(1000 búsquedas por mes, sin tarjeta) también busca en toda la web. Solo se envían las palabras de la búsqueda.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Clave de Tavily (tvly-…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.tavily.com/home")))
            }) { Text("Conseguir una clave") }
            TextButton(onClick = { settings.setWebSearchKey(key) }, enabled = key.trim() != savedKey.orEmpty()) {
                Text(if (key.isBlank() && savedKey != null) "Quitar clave" else "Guardar")
            }
        }
    }
}
