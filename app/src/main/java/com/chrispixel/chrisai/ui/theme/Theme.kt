package com.chrispixel.chrisai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cyan = Color(0xFF22D3EE)
val CyanDark = Color(0xFF0E7490)
val BackgroundDark = Color(0xFF12141A)
val SurfaceDark = Color(0xFF1B1E28)
val SurfaceDarkHigh = Color(0xFF242836)
val TextPrimaryDark = Color(0xFFE6E9F0)
val TextSecondaryDark = Color(0xFF9AA3B5)
val ErrorRed = Color(0xFFFF6B6B)

val BackgroundLight = Color(0xFFF6F7FB)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceLightHigh = Color(0xFFEDEFF5)
val TextPrimaryLight = Color(0xFF16181D)
val TextSecondaryLight = Color(0xFF565E70)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF06262C),
    primaryContainer = Color(0xFF0E4A57),
    onPrimaryContainer = Color(0xFFC4F4FC),
    secondary = CyanDark,
    onSecondary = Color(0xFFF1FDFF),
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = TextSecondaryDark,
    error = ErrorRed,
    onError = Color(0xFF3A0000)
)

private val LightColors = lightColorScheme(
    primary = CyanDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7F3FB),
    onPrimaryContainer = Color(0xFF033039),
    secondary = CyanDark,
    onSecondary = Color(0xFFFFFFFF),
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceLightHigh,
    onSurfaceVariant = TextSecondaryLight,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun ChrisAiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}