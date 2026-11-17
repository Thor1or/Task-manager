package com.folio.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.folio.notes.data.ThemeMode
import com.folio.notes.markdown.MarkdownColors

// Warm paper and ink, with an indigo accent. "surface" is the card colour that lists and sheets sit on.
private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E5FA),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = Color(0xFFE08A00),
    secondaryContainer = Color(0xFFE1E5FA),
    onSecondaryContainer = Color(0xFF1A237E),
    background = Color(0xFFF5F4F0),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECEBE6),
    onSurfaceVariant = Color(0xFF6B6A70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFECEBE6),
    outline = Color(0xFFB9B8BD),
    outlineVariant = Color(0xFFE2E1DC),
    error = Color(0xFFD03A2F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B0FF),
    onPrimary = Color(0xFF10163F),
    primaryContainer = Color(0xFF2B3270),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFFFB74D),
    secondaryContainer = Color(0xFF2B3270),
    onSecondaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF111113),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF1D1D21),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF2A2A2F),
    onSurfaceVariant = Color(0xFF9D9CA4),
    surfaceContainerLowest = Color(0xFF111113),
    surfaceContainerLow = Color(0xFF1D1D21),
    surfaceContainer = Color(0xFF232327),
    surfaceContainerHigh = Color(0xFF29292E),
    surfaceContainerHighest = Color(0xFF323238),
    outline = Color(0xFF5A5A62),
    outlineVariant = Color(0xFF34343A),
    error = Color(0xFFFF6B5E),
)

@Composable
fun FolioTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

@Composable
fun markdownColors(): MarkdownColors {
    val scheme = MaterialTheme.colorScheme
    return MarkdownColors(
        text = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        accent = scheme.primary,
        codeBackground = scheme.surfaceVariant,
        highlight = scheme.secondary.copy(alpha = 0.35f),
    )
}
