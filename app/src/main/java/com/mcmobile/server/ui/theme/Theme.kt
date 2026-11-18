package com.mcmobile.server.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BurntOrange, onPrimary = OnBurntOrange,
    primaryContainer = Color(0xFFFFDFC8), onPrimaryContainer = Color(0xFF652901),
    secondary = Color(0xFF7C553D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E1D1), onSecondaryContainer = Color(0xFF432C1F),
    tertiary = Color(0xFF795831), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3BE), onTertiaryContainer = Color(0xFF452D11),
    background = Color(0xFFFCF9F5), onBackground = Color(0xFF2A211C),
    surface = Color(0xFFFCF9F5), onSurface = Color(0xFF2A211C),
    surfaceVariant = Color(0xFFF0E6DC), onSurfaceVariant = Color(0xFF64584F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F3EE),
    surfaceContainer = Color(0xFFF4EEE7), surfaceContainerHigh = Color(0xFFEEE6DE),
    surfaceContainerHighest = Color(0xFFE9DFD5),
    surfaceBright = Color(0xFFFFFBF8), surfaceDim = Color(0xFFE2D9D0),
    outline = Color(0xFF827469), outlineVariant = Color(0xFFD6C8BC),
    inverseSurface = Color(0xFF342C26), inverseOnSurface = Color(0xFFFAF3ED),
    inversePrimary = Apricot,
)

private val DarkColors = darkColorScheme(
    primary = Apricot, onPrimary = Color(0xFF492005),
    primaryContainer = Color(0xFF733409), onPrimaryContainer = Color(0xFFFFDFC8),
    secondary = Color(0xFFE3BFA7), onSecondary = Color(0xFF402B1D),
    secondaryContainer = Color(0xFF513D30), onSecondaryContainer = Color(0xFFF9DFCD),
    tertiary = Color(0xFFE9C395), onTertiary = Color(0xFF432D14),
    tertiaryContainer = Color(0xFF594025), onTertiaryContainer = Color(0xFFFFE3BE),
    background = Color(0xFF151310), onBackground = Color(0xFFF2ECE6),
    surface = Color(0xFF211E1A), onSurface = Color(0xFFF2ECE6),
    surfaceVariant = Color(0xFF39332D), onSurfaceVariant = Color(0xFFCFC4B9),
    surfaceContainerLowest = Color(0xFF100F0D), surfaceContainerLow = Color(0xFF1E1B18),
    surfaceContainer = Color(0xFF29241F), surfaceContainerHigh = Color(0xFF302A25),
    surfaceContainerHighest = Color(0xFF39332D),
    surfaceBright = Color(0xFF403A34), surfaceDim = Color(0xFF171411),
    outline = Color(0xFFA7998C), outlineVariant = Color(0xFF52483F),
    inverseSurface = Color(0xFFF2ECE6), inverseOnSurface = Color(0xFF342C26),
    inversePrimary = BurntOrange,
)

@Composable
fun MCServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
