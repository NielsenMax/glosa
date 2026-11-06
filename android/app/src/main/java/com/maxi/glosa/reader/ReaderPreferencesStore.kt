package com.maxi.glosa.reader

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesSerializer
import org.readium.r2.navigator.preferences.Theme

/** Reading preferences shared by every book. */
class ReaderPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val serializer = EpubPreferencesSerializer()

    private val state = MutableStateFlow(
        prefs.getString(KEY, null)?.let(serializer::deserialize) ?: DEFAULT,
    )
    val preferences: StateFlow<EpubPreferences> = state.asStateFlow()

    fun update(transform: (EpubPreferences) -> EpubPreferences) {
        val updated = transform(state.value)
        state.value = updated
        prefs.edit { putString(KEY, serializer.serialize(updated)) }
    }

    private companion object {
        const val KEY = "epub_preferences"
        val DEFAULT = EpubPreferences(
            theme = Theme.LIGHT,
            fontSize = 1.0,
            lineHeight = 1.5,
            pageMargins = 1.2,
            publisherStyles = false,
        )
    }
}
