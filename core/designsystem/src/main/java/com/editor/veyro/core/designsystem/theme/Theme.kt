package com.editor.veyro.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VeyroColorScheme = darkColorScheme(
    primary = VeyroPalette.Violet,
    onPrimary = VeyroPalette.Ink,
    primaryContainer = VeyroPalette.VioletContainer,
    onPrimaryContainer = VeyroPalette.OnVioletContainer,
    secondary = VeyroPalette.Magenta,
    onSecondary = VeyroPalette.Ink,
    secondaryContainer = VeyroPalette.MagentaContainer,
    onSecondaryContainer = VeyroPalette.OnMagentaContainer,
    tertiary = VeyroPalette.Magenta,
    onTertiary = VeyroPalette.Ink,
    background = VeyroPalette.Ink,
    onBackground = VeyroPalette.TextPrimary,
    surface = VeyroPalette.Surface,
    onSurface = VeyroPalette.TextPrimary,
    surfaceVariant = VeyroPalette.SurfaceHigh,
    onSurfaceVariant = VeyroPalette.TextSecondary,
    surfaceDim = VeyroPalette.Ink,
    surfaceBright = VeyroPalette.SurfaceHighest,
    surfaceContainerLowest = VeyroPalette.Ink,
    surfaceContainerLow = VeyroPalette.SurfaceLow,
    surfaceContainer = VeyroPalette.SurfaceMid,
    surfaceContainerHigh = VeyroPalette.SurfaceHigh,
    surfaceContainerHighest = VeyroPalette.SurfaceHighest,
    outline = VeyroPalette.Outline,
    outlineVariant = VeyroPalette.OutlineVariant,
    error = VeyroPalette.Error,
    onError = VeyroPalette.Ink,
    errorContainer = VeyroPalette.ErrorContainer,
    onErrorContainer = VeyroPalette.OnErrorContainer,
    scrim = VeyroPalette.Ink,
)

/**
 * The Veyro theme. It is always dark and never uses dynamic (wallpaper)
 * color, so footage is always judged against the same neutral surroundings.
 */
@Composable
public fun VeyroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VeyroColorScheme,
        typography = VeyroTypography,
        shapes = VeyroShapes,
        content = content,
    )
}
