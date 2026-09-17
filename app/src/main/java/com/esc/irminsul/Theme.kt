package com.esc.irminsul

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand palette — theme-independent, referenced by the Material schemes.
val Primary = Color(0xFF00B4D8)
val PrimaryDark = Color(0xFF023E8A)
val PrimaryLight = Color(0xFF90E0EF)
val Secondary = Color(0xFF7B2CBF)
val SecondaryDark = Color(0xFF5A189A)
val SecondaryLight = Color(0xFF9D4EDD)
val AccentDark = Color(0xFFE65100)

// App-specific semantic palette used by the capture UI (custom surfaces,
// status colors, text tiers). Values switch with the theme via IrminsulTheme.
@Immutable
data class IrminsulColors(
    val surface: Color,
    val surfaceLight: Color,
    val surfaceHighlight: Color,
    val background: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val textDisabled: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val buttonPrimary: Color,
    val buttonSuccess: Color
)

val DarkIrminsulColors = IrminsulColors(
    surface = Color(0xFF1A1A2E),
    surfaceLight = Color(0xFF252540),
    surfaceHighlight = Color(0xFF2D2D50),
    background = Color(0xFF0A0A12),
    border = Color(0xFF2D3748),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB8C5D6),
    textHint = Color(0xFF6B7A8C),
    textDisabled = Color(0xFF4A5568),
    accent = Color(0xFFFF9800),
    success = Color(0xFF48BB78),
    error = Color(0xFFFC8181),
    warning = Color(0xFFF6AD55),
    buttonPrimary = Color(0xFF00B4D8),
    buttonSuccess = Color(0xFF38A169)
)

val LightIrminsulColors = IrminsulColors(
    surface = Color(0xFFFFFFFF),
    surfaceLight = Color(0xFFF0F1F7),
    surfaceHighlight = Color(0xFFE7E9F1),
    background = Color(0xFFF6F7FB),
    border = Color(0xFFD9DCE7),
    textPrimary = Color(0xFF1A1B25),
    textSecondary = Color(0xFF565A6E),
    textHint = Color(0xFF8A90A3),
    textDisabled = Color(0xFFB4B8C6),
    accent = Color(0xFFE65100),
    success = Color(0xFF2F855A),
    error = Color(0xFFC53030),
    warning = Color(0xFFC05621),
    buttonPrimary = Color(0xFF0096B7),
    buttonSuccess = Color(0xFF2F855A)
)

private val LocalIrminsulColors = staticCompositionLocalOf { DarkIrminsulColors }

// Theme-aware accessors. The original constant names are kept so call sites
// read as before; they resolve to the current theme's palette and therefore
// must only be read from composable context.
val Background: Color @Composable get() = LocalIrminsulColors.current.background
val Surface: Color @Composable get() = LocalIrminsulColors.current.surface
val SurfaceLight: Color @Composable get() = LocalIrminsulColors.current.surfaceLight
val SurfaceHighlight: Color @Composable get() = LocalIrminsulColors.current.surfaceHighlight
val Border: Color @Composable get() = LocalIrminsulColors.current.border
val TextPrimary: Color @Composable get() = LocalIrminsulColors.current.textPrimary
val TextSecondary: Color @Composable get() = LocalIrminsulColors.current.textSecondary
val TextHint: Color @Composable get() = LocalIrminsulColors.current.textHint
val TextDisabled: Color @Composable get() = LocalIrminsulColors.current.textDisabled
val Accent: Color @Composable get() = LocalIrminsulColors.current.accent
val Success: Color @Composable get() = LocalIrminsulColors.current.success
val Error: Color @Composable get() = LocalIrminsulColors.current.error
val Warning: Color @Composable get() = LocalIrminsulColors.current.warning
val ButtonPrimary: Color @Composable get() = LocalIrminsulColors.current.buttonPrimary
val ButtonSuccess: Color @Composable get() = LocalIrminsulColors.current.buttonSuccess

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = DarkIrminsulColors.accent,
    background = DarkIrminsulColors.background,
    surface = DarkIrminsulColors.surface,
    surfaceContainerLow = DarkIrminsulColors.surface,
    surfaceContainerHigh = DarkIrminsulColors.surfaceLight,
    surfaceVariant = DarkIrminsulColors.surfaceHighlight,
    onPrimary = DarkIrminsulColors.textPrimary,
    onSecondary = DarkIrminsulColors.textPrimary,
    onTertiary = DarkIrminsulColors.textPrimary,
    onBackground = DarkIrminsulColors.textPrimary,
    onSurface = DarkIrminsulColors.textPrimary,
    onSurfaceVariant = DarkIrminsulColors.textSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    tertiary = AccentDark,
    background = LightIrminsulColors.background,
    surface = LightIrminsulColors.surface,
    surfaceContainerLow = LightIrminsulColors.surfaceLight,
    surfaceContainerHigh = LightIrminsulColors.surfaceHighlight,
    surfaceVariant = LightIrminsulColors.surfaceHighlight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = LightIrminsulColors.textPrimary,
    onSurface = LightIrminsulColors.textPrimary,
    onSurfaceVariant = LightIrminsulColors.textSecondary
)

@Composable
fun IrminsulTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalIrminsulColors provides if (darkTheme) DarkIrminsulColors else LightIrminsulColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = androidx.compose.material3.Typography(),
            content = content
        )
    }
}
