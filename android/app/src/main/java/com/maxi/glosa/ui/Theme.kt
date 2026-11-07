package com.maxi.glosa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.maxi.glosa.settings.Appearance
import com.maxi.glosa.settings.Paper

/** [isSystemInDarkTheme] already reflects the app's own night mode, which [com.maxi.glosa.settings.AppSettings] sets. */
@Composable
fun GlosaTheme(appearance: Appearance, sepiaTone: Float, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        appearance == Appearance.Sepia -> dynamicLightColorScheme(context).on(Paper.sepia(sepiaTone))
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Surfaces of the same paper as the page, each container a shade darker, so the app matches the book. */
private fun ColorScheme.on(paper: Paper): ColorScheme {
    val background = Color(paper.background)
    val ink = Color(paper.ink)
    val shade = { fraction: Float -> lerp(background, SHADOW, fraction) }
    return copy(
        background = background,
        surface = background,
        surfaceBright = lerp(background, Color.White, 0.3f),
        surfaceContainerLowest = lerp(background, Color.White, 0.3f),
        surfaceContainerLow = shade(0.04f),
        surfaceContainer = shade(0.08f),
        surfaceContainerHigh = shade(0.12f),
        surfaceContainerHighest = shade(0.16f),
        surfaceVariant = shade(0.14f),
        onSurface = ink,
        onBackground = ink,
        onSurfaceVariant = lerp(ink, background, 0.3f),
    )
}

private val SHADOW = Color(0xFF6B5432)
