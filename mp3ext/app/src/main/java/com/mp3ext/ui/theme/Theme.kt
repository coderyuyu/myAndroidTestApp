package com.mp3ext.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = SlateDarkBackground,
    primaryContainer = SlateDarkSurfaceVariant,
    onPrimaryContainer = CyanPrimary,
    secondary = IndigoAccent,
    onSecondary = TextPrimaryDark,
    secondaryContainer = VioletAccent.copy(alpha = 0.2f),
    onSecondaryContainer = VioletAccent,
    tertiary = EmeraldSuccess,
    background = SlateDarkBackground,
    onBackground = TextPrimaryDark,
    surface = SlateDarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateDarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = SlateBorder,
    error = RoseError
)

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimaryVariant,
    onPrimary = TextPrimaryDark,
    primaryContainer = CyanPrimaryVariant.copy(alpha = 0.15f),
    onPrimaryContainer = CyanPrimaryVariant,
    secondary = IndigoAccent,
    background = SlateLightBackground,
    onBackground = TextPrimaryLight,
    surface = SlateLightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateLightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = SlateLightBorder,
    error = RoseError
)

@Composable
fun Mp3ExtTheme(
    darkTheme: Boolean = true, // Default to sleek modern dark theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
