package com.ytstyle.bili.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ytstyle.bili.data.settings.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = YtColors.Red,
    onPrimary = Color.White,
    secondary = YtColors.OnBackgroundSecondary,
    background = YtColors.Background,
    onBackground = YtColors.OnBackground,
    surface = YtColors.Surface,
    onSurface = YtColors.OnBackground,
    surfaceVariant = YtColors.SurfaceElevated,
    onSurfaceVariant = YtColors.OnBackgroundSecondary,
    outline = YtColors.Divider,
)

private val LightScheme = lightColorScheme(
    primary = YtColors.Red,
    onPrimary = Color.White,
    background = YtColors.LightBackground,
    onBackground = YtColors.LightOnBackground,
    surface = YtColors.LightSurface,
    onSurface = YtColors.LightOnBackground,
    surfaceVariant = YtColors.LightSurfaceElevated,
    onSurfaceVariant = YtColors.LightOnBackgroundSecondary,
    outline = YtColors.LightDivider,
)

/**
 * Palette values that vary with the theme.
 *
 * The UI reads colours through this instead of the `YtColors` object directly,
 * so a single theme switch repaints every screen. `YtColors` now only holds the
 * brand colours that are identical in both themes.
 */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val onBackground: Color,
    val onBackgroundSecondary: Color,
    val divider: Color,
    val chipBackground: Color,
    val chipSelected: Color,
    val chipSelectedContent: Color,
)

private val DarkAppColors = AppColors(
    background = YtColors.Background,
    surface = YtColors.Surface,
    surfaceElevated = YtColors.SurfaceElevated,
    onBackground = YtColors.OnBackground,
    onBackgroundSecondary = YtColors.OnBackgroundSecondary,
    divider = YtColors.Divider,
    chipBackground = YtColors.ChipBackground,
    chipSelected = Color.White,
    chipSelectedContent = Color(0xFF0F0F0F),
)

private val LightAppColors = AppColors(
    background = YtColors.LightBackground,
    surface = YtColors.LightSurface,
    surfaceElevated = YtColors.LightSurfaceElevated,
    onBackground = YtColors.LightOnBackground,
    onBackgroundSecondary = YtColors.LightOnBackgroundSecondary,
    divider = YtColors.LightDivider,
    chipBackground = Color(0xFFEFEFEF),
    chipSelected = Color(0xFF0F0F0F),
    chipSelectedContent = Color.White,
)

private val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** Theme-aware palette. Usage: `AppTheme.colors.background`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

@Composable
fun YtBiliTheme(
    themeMode: ThemeMode = ThemeMode.Light,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = YtTypography,
            content = content,
        )
    }
}
