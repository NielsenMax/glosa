package com.maxi.glosa.update

import android.content.SharedPreferences
import androidx.core.content.edit
import com.maxi.glosa.net.NetworkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState

    /** [release] is null when the check itself failed, so the library does not nag about being offline. */
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/** Lives as long as the app process, so a download survives leaving the screen that started it. */
class Updater(
    private val feed: ReleaseFeed,
    private val installer: ApkInstaller,
    val installedVersionCode: Long,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    fun checkIfDue() {
        if (clock() - prefs.getLong(KEY_LAST_CHECK, 0) >= CHECK_INTERVAL_MS) check()
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = feed.latest()
                prefs.edit { putLong(KEY_LAST_CHECK, clock()) }
                if (release != null && release.versionCode > installedVersionCode) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: NetworkException) {
                UpdateState.Failed("No se pudo buscar actualizaciones: ${e.message}", release = null)
            }
        }
    }

    fun install(release: AppRelease) {
        if (!installer.canInstall) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            try {
                val apk = installer.download(release.apkUrl) { _state.value = UpdateState.Downloading(release, it) }
                _state.value = UpdateState.Installing(release)
                installer.install(apk)
            } catch (e: NetworkException) {
                _state.value = UpdateState.Failed("No se pudo descargar ${release.versionName}: ${e.message}", release)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed("Android no aceptó el archivo de ${release.versionName}: ${e.message}", release)
            }
        }
    }

    /** Called when the reader comes back from the "install unknown apps" setting. */
    fun onPermissionMaybeGranted() {
        val pending = _state.value as? UpdateState.NeedsPermission ?: return
        if (installer.canInstall) install(pending.release)
    }

    fun permissionSettings() = installer.permissionSettings()

    fun installFailed(message: String) {
        val release = when (val current = _state.value) {
            is UpdateState.Installing -> current.release
            else -> null
        }
        _state.value = UpdateState.Failed("Android no instaló la actualización: $message", release)
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
