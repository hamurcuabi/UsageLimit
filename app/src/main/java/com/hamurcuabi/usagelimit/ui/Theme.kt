package com.hamurcuabi.usagelimit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Amber = Color(0xFFF2A541)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2A0C),
    onPrimaryContainer = Color(0xFFFFDFA8),
    secondary = Color(0xFF8FB4D9),
    onSecondary = Color(0xFF0B1B2B),
    secondaryContainer = Color(0xFF243647),
    onSecondaryContainer = Color(0xFFD5E6F7),
    tertiaryContainer = Color(0xFF3A2A0C),
    onTertiaryContainer = Color(0xFFFFDFA8),
    background = Color(0xFF0E1318),
    onBackground = Color(0xFFE8ECF0),
    surface = Color(0xFF0E1318),
    onSurface = Color(0xFFE8ECF0),
    surfaceVariant = Color(0xFF232D38),
    onSurfaceVariant = Color(0xFF93A0AE),
    surfaceContainerLowest = Color(0xFF0A0E12),
    surfaceContainerLow = Color(0xFF151C23),
    surfaceContainer = Color(0xFF19212A),
    surfaceContainerHigh = Color(0xFF202A34),
    surfaceContainerHighest = Color(0xFF28333F),
    outline = Color(0xFF4A5868),
    outlineVariant = Color(0xFF26313C),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF2B0500),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA85F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2B5),
    onPrimaryContainer = Color(0xFF3A2100),
    secondary = Color(0xFF2F5C86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9F5),
    onSecondaryContainer = Color(0xFF10283D),
    tertiaryContainer = Color(0xFFFFE2B5),
    onTertiaryContainer = Color(0xFF3A2100),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF171C22),
    surface = Color(0xFFF6F4EF),
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFE9E5DC),
    onSurfaceVariant = Color(0xFF5D6875),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EDE6),
    surfaceContainerHighest = Color(0xFFE9E5DC),
    outline = Color(0xFF8A929C),
    outlineVariant = Color(0xFFE3DFD6),
    error = Color(0xFFC6362B),
    onError = Color.White,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UsageLimitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
