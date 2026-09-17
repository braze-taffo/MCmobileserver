package com.mcmobile.server.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Tertiary,
    background = DarkBackground,
    surface = DarkSurface,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF286449),
    secondary = Secondary,
    tertiary = Tertiary,
    background = Color(0xFFF1F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDDE8F1),
    surfaceContainer = Color(0xFFF8FAFC),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

@Composable
fun MCServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
