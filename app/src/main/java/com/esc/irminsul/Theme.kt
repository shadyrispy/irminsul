package com.esc.irminsul

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// "羊皮晨光" palette — warm paper light theme with teal primary and amber
// accent; dark variant keeps the same hue relationships on warm charcoal.
private val DarkIrminsulColors = IrminsulColors(
    surface = Color(0xFF1D1A15),
    surfaceLight = Color(0xFF26221C),
    surfaceHighlight = Color(0xFF2F2A22),
    background = Color(0xFF14120E),
    border = Color(0xFF3A342B),
    textPrimary = Color(0xFFF2EFE7),
    textSecondary = Color(0xFFA29B8C),
    textHint = Color(0xFF77715F),
    textDisabled = Color(0xFF57524A),
    accent = Color(0xFFF0B429),
    success = Color(0xFF5EEAD4),
    error = Color(0xFFF87171),
    warning = Color(0xFFF0B429),
    buttonPrimary = Color(0xFF5EEAD4),
    buttonSuccess = Color(0xFF5EEAD4)
)

private val LightIrminsulColors = IrminsulColors(
    surface = Color(0xFFFFFDF8),
    surfaceLight = Color(0xFFFFFFFF),
    surfaceHighlight = Color(0xFFEFEAE0),
    background = Color(0xFFF7F5EF),
    border = Color(0xFFE0D9C9),
    textPrimary = Color(0xFF23211C),
    textSecondary = Color(0xFF6E6A60),
    textHint = Color(0xFF98938A),
    textDisabled = Color(0xFFC9C4B8),
    accent = Color(0xFFB45309),
    success = Color(0xFF0F766E),
    error = Color(0xFFB91C1C),
    warning = Color(0xFFB45309),
    buttonPrimary = Color(0xFF0F766E),
    buttonSuccess = Color(0xFF0F766E)
)

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
    primary = DarkIrminsulColors.buttonPrimary,
    onPrimary = Color(0xFF0F2E2B),
    secondary = DarkIrminsulColors.accent,
    onSecondary = Color(0xFF26210C),
    tertiary = DarkIrminsulColors.accent,
    background = DarkIrminsulColors.background,
    surface = DarkIrminsulColors.surface,
    surfaceContainerLow = DarkIrminsulColors.surface,
    surfaceContainerHigh = DarkIrminsulColors.surfaceLight,
    surfaceVariant = DarkIrminsulColors.surfaceHighlight,
    onBackground = DarkIrminsulColors.textPrimary,
    onSurface = DarkIrminsulColors.textPrimary,
    onSurfaceVariant = DarkIrminsulColors.textSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = LightIrminsulColors.buttonPrimary,
    secondary = LightIrminsulColors.accent,
    tertiary = LightIrminsulColors.accent,
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

// Five-level type scale; numbers and timestamps additionally use a monospace
// family at the call sites.
private val AppTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun IrminsulTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Fixed brand palette; dynamic wallpaper colors are off by default so the
    // paper/teal identity survives on Android 12+.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
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
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
