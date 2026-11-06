package com.maxi.glosa.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shown above the library only while there is something to do about an update. */
@Composable
fun UpdateBanner(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val (message, action) = when (val current = state) {
        is UpdateState.Available -> "Hay una versión nueva de Glosa: ${current.release.versionName}" to
            ("Actualizar" to { updater.install(current.release) })
        is UpdateState.NeedsPermission -> "Para actualizarse, Glosa necesita permiso para instalar apps. Activalo y volvé." to
            ("Dar permiso" to { context.startActivity(updater.permissionSettings()) })
        is UpdateState.Downloading -> "Descargando ${current.release.versionName}…" to null
        is UpdateState.Installing -> "Instalando ${current.release.versionName}. Glosa se va a cerrar: abrila de nuevo." to null
        is UpdateState.Failed -> current.release?.let { release -> current.message to ("Reintentar" to { updater.install(release) }) }
            ?: return
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            (state as? UpdateState.Available)?.release?.notes?.lineSequence()?.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            (state as? UpdateState.Downloading)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth()) }
            action?.let { (label, onClick) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onClick) { Text(label) }
                }
            }
        }
    }
}

/** The installed version and a manual check, for the settings sheet. */
@Composable
fun UpdateSettingsRow(updater: Updater, installedVersionName: String) {
    val state by updater.state.collectAsStateWithLifecycle()
    val status = when (val current = state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> "Buscando…"
        UpdateState.UpToDate -> "Tenés la última versión."
        is UpdateState.Available -> "Hay una versión nueva: ${current.release.versionName}."
        is UpdateState.NeedsPermission -> "Falta el permiso para instalar apps."
        is UpdateState.Downloading -> "Descargando ${current.release.versionName}…"
        is UpdateState.Installing -> "Instalando ${current.release.versionName}…"
        is UpdateState.Failed -> current.message
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Versión $installedVersionName", style = MaterialTheme.typography.bodyMedium)
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (val current = state) {
            is UpdateState.Available -> TextButton(onClick = { updater.install(current.release) }) { Text("Actualizar") }
            else -> TextButton(onClick = updater::check, enabled = current != UpdateState.Checking) { Text("Buscar actualizaciones") }
        }
    }
}
