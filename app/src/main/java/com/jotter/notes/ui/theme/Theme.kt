package com.jotter.notes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jotter.notes.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6B5D3F),
    secondaryContainer = Color(0xFFF5E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF4A6547),
    inversePrimary = Color(0xFFF5BF48),
    inverseSurface = Color(0xFF353027),
    inverseOnSurface = Color(0xFFF9EFE2),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1F1B13),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceVariant = Color(0xFFECE1CF),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C5B4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF5EC),
    surfaceContainer = Color(0xFFF6EFE4),
    surfaceContainerHigh = Color(0xFFF0E9DE),
    surfaceContainerHighest = Color(0xFFEAE3D8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF5BF48),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFFD8C4A0),
    secondaryContainer = Color(0xFF524529),
    onSecondaryContainer = Color(0xFFF5E0BB),
    tertiary = Color(0xFFB0CFAA),
    inversePrimary = Color(0xFF7A5900),
    inverseSurface = Color(0xFFEAE1D4),
    inverseOnSurface = Color(0xFF353027),
    background = Color(0xFF17130B),
    surface = Color(0xFF17130B),
    onSurface = Color(0xFFEAE1D4),
    onSurfaceVariant = Color(0xFFD0C5B4),
    surfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF998F80),
    outlineVariant = Color(0xFF4D4639),
    surfaceContainerLowest = Color(0xFF110E07),
    surfaceContainerLow = Color(0xFF1F1B13),
    surfaceContainer = Color(0xFF231F17),
    surfaceContainerHigh = Color(0xFF2E2921),
    surfaceContainerHighest = Color(0xFF39342B),
)

/** Whether the app is currently drawn dark; note colours pick their variant from this. */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun JotterTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

data class NoteColor(val name: String, val light: Color, val dark: Color)

object NoteColors {
    /** Index 0 means "no colour": the note uses the regular surface colour. */
    val all = listOf(
        NoteColor("Default", Color.Unspecified, Color.Unspecified),
        NoteColor("Coral", Color(0xFFFAAFA8), Color(0xFF77172E)),
        NoteColor("Peach", Color(0xFFF39F76), Color(0xFF692B17)),
        NoteColor("Sand", Color(0xFFFFF8B8), Color(0xFF7C4A03)),
        NoteColor("Mint", Color(0xFFE2F6D3), Color(0xFF264D3B)),
        NoteColor("Sage", Color(0xFFB4DDD3), Color(0xFF0C625D)),
        NoteColor("Fog", Color(0xFFD4E4ED), Color(0xFF256377)),
        NoteColor("Storm", Color(0xFFAECCDC), Color(0xFF284255)),
        NoteColor("Dusk", Color(0xFFD3BFDB), Color(0xFF472E5B)),
        NoteColor("Blossom", Color(0xFFF6E2DD), Color(0xFF6C394F)),
        NoteColor("Clay", Color(0xFFE9E3D4), Color(0xFF4B443A)),
        NoteColor("Chalk", Color(0xFFEFEFF1), Color(0xFF232427)),
    )

    fun name(index: Int) = all.getOrElse(index) { all[0] }.name

    /** Background for a note of colour [index]; falls back to the theme surface for the default. */
    @Composable
    fun background(index: Int): Color {
        val c = all.getOrElse(index) { all[0] }
        val color = if (LocalDarkTheme.current) c.dark else c.light
        return if (color == Color.Unspecified) MaterialTheme.colorScheme.surface else color
    }

    /** Text colour that stays readable on every note colour. */
    @Composable
    fun onBackground(index: Int): Color =
        if (index == 0 || index !in all.indices) MaterialTheme.colorScheme.onSurface
        else if (LocalDarkTheme.current) Color(0xFFE8EAED) else Color(0xFF202124)
}
