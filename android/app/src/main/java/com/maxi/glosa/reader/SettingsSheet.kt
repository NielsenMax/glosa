package com.maxi.glosa.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxi.glosa.settings.AppearancePicker
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign

private val fonts = listOf(null to "Original", FontFamily.SERIF to "Serif", FontFamily.SANS_SERIF to "Sans")

@Composable
fun SettingsSheet(viewModel: ReaderViewModel) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
    ) {
        Labeled("Tamaño de letra") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 14.sp)
                Slider(
                    value = (prefs.fontSize ?: 1.0).toFloat(),
                    onValueChange = { size -> viewModel.updatePreferences { it.copy(fontSize = size.toDouble()) } },
                    valueRange = 0.7f..2.2f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("A", fontSize = 24.sp)
            }
        }
        Labeled("Apariencia") {
            AppearancePicker(appearance, viewModel::setAppearance)
        }
        Labeled("Tipografía") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fonts.forEach { (font, label) ->
                    FilterChip(
                        selected = prefs.fontFamily == font,
                        onClick = { viewModel.updatePreferences { it.copy(fontFamily = font) } },
                        label = { Text(label) },
                    )
                }
            }
        }
        Labeled("Interlineado") {
            Slider(
                value = (prefs.lineHeight ?: 1.5).toFloat(),
                onValueChange = { height -> viewModel.updatePreferences { it.copy(lineHeight = height.toDouble()) } },
                valueRange = 1.1f..2.2f,
            )
        }
        Labeled("Márgenes") {
            Slider(
                value = (prefs.pageMargins ?: 1.2).toFloat(),
                onValueChange = { margins -> viewModel.updatePreferences { it.copy(pageMargins = margins.toDouble()) } },
                valueRange = 0.5f..3f,
            )
        }
        Labeled("Lectura") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = prefs.scroll != true,
                    onClick = { viewModel.updatePreferences { it.copy(scroll = false) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Páginas") }
                SegmentedButton(
                    selected = prefs.scroll == true,
                    onClick = { viewModel.updatePreferences { it.copy(scroll = true) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Desplazamiento") }
            }
        }
        Labeled("Alineación") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = prefs.textAlign != TextAlign.START,
                    onClick = { viewModel.updatePreferences { it.copy(textAlign = TextAlign.JUSTIFY) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Justificado") }
                SegmentedButton(
                    selected = prefs.textAlign == TextAlign.START,
                    onClick = { viewModel.updatePreferences { it.copy(textAlign = TextAlign.START) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("A la izquierda") }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}
