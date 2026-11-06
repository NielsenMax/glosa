package com.maxi.glosa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.maxi.glosa.settings.Appearance

/** [isSystemInDarkTheme] already reflects the app's own night mode, which [com.maxi.glosa.settings.AppSettings] sets. */
@Composable
fun GlosaTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        appearance == Appearance.Sepia -> dynamicLightColorScheme(context).sepia()
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Warm paper surfaces that match Readium's sepia page (#FAF4E8). */
private fun ColorScheme.sepia(): ColorScheme = copy(
    background = Color(0xFFFAF4E8),
    surface = Color(0xFFFAF4E8),
    surfaceBright = Color(0xFFFDF8EE),
    surfaceContainerLowest = Color(0xFFFDF8EE),
    surfaceContainerLow = Color(0xFFF6EEDD),
    surfaceContainer = Color(0xFFF1E7D3),
    surfaceContainerHigh = Color(0xFFEBE0CA),
    surfaceContainerHighest = Color(0xFFE5D8BF),
    surfaceVariant = Color(0xFFE9DDC6),
    onSurface = Color(0xFF2B2418),
    onBackground = Color(0xFF2B2418),
    onSurfaceVariant = Color(0xFF5A4E3B),
)
