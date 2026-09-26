package com.gpxami.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = TextPrimary,
    primaryContainer = CyanDark,
    secondary = EmeraldAccent,
    tertiary = AmberAccent,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDarkElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun GPXAmiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
