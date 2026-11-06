package com.maxi.glosa.settings

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.navigator.preferences.Theme

enum class Appearance(val label: String) {
    System("Automático"),
    Light("Claro"),
    Sepia("Sepia"),
    Dark("Oscuro");

    /** The page colors for this appearance; [System] follows the phone's dark mode. */
    fun pageTheme(isNight: Boolean): Theme = when (this) {
        System -> if (isNight) Theme.DARK else Theme.LIGHT
        Light -> Theme.LIGHT
        Sepia -> Theme.SEPIA
        Dark -> Theme.DARK
    }

    val nightMode: Int
        get() = when (this) {
            System -> UiModeManager.MODE_NIGHT_AUTO
            Light, Sepia -> UiModeManager.MODE_NIGHT_NO
            Dark -> UiModeManager.MODE_NIGHT_YES
        }
}

/** App-wide settings. The night mode is handed to the system, which applies it to every screen of the app. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val uiModeManager = context.getSystemService(UiModeManager::class.java)

    private val _appearance = MutableStateFlow(
        prefs.getString(KEY_APPEARANCE, null)?.let(Appearance::valueOf) ?: Appearance.System,
    )
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _webSearchKey = MutableStateFlow(prefs.getString(KEY_WEB_SEARCH, null))

    /** Tavily API key; without one, the assistant looks things up only in the book and on Wikipedia. */
    val webSearchKey: StateFlow<String?> = _webSearchKey.asStateFlow()

    init {
        uiModeManager.setApplicationNightMode(_appearance.value.nightMode)
    }

    fun setAppearance(appearance: Appearance) {
        _appearance.value = appearance
        prefs.edit { putString(KEY_APPEARANCE, appearance.name) }
        uiModeManager.setApplicationNightMode(appearance.nightMode)
    }

    fun setWebSearchKey(key: String) {
        val trimmed = key.trim().ifEmpty { null }
        _webSearchKey.value = trimmed
        prefs.edit { putString(KEY_WEB_SEARCH, trimmed) }
    }

    private companion object {
        const val KEY_APPEARANCE = "appearance"
        const val KEY_WEB_SEARCH = "tavily_api_key"
    }
}
