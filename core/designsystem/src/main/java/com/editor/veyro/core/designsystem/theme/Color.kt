package com.editor.veyro.core.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Veyro's own palette: near-black neutral surfaces with a violet-to-magenta
 * accent. The app is dark-only, as video editors usually are, so footage
 * colors are judged against a neutral, non-glaring frame.
 *
 * Text contrast on `Surface`, measured with the WCAG 2.1 formula:
 * - TextPrimary 16.8:1
 * - TextSecondary 8.4:1
 * - Violet 6.8:1
 * - Magenta 7.3:1
 * - Error 6.8:1
 *
 * Ink on Violet (filled buttons) is 7.1:1.
 */
internal object VeyroPalette {
    val Ink = Color(0xFF0B0B0F)
    val Surface = Color(0xFF111116)
    val SurfaceLow = Color(0xFF15151B)
    val SurfaceMid = Color(0xFF1B1B23)
    val SurfaceHigh = Color(0xFF23232D)
    val SurfaceHighest = Color(0xFF2C2C38)
    val Outline = Color(0xFF4A4A5A)
    val OutlineVariant = Color(0xFF2E2E3A)

    val TextPrimary = Color(0xFFF2F1F7)
    val TextSecondary = Color(0xFFADACBD)

    val Violet = Color(0xFF9D8CFF)
    val VioletDeep = Color(0xFF6E56F7)
    val VioletContainer = Color(0xFF2D2560)
    val OnVioletContainer = Color(0xFFE4DEFF)
    val Magenta = Color(0xFFFF6FA8)
    val MagentaDeep = Color(0xFFCC3878)
    val MagentaContainer = Color(0xFF4F1C35)
    val OnMagentaContainer = Color(0xFFFFD9E6)

    val Error = Color(0xFFFF6B6B)
    val ErrorContainer = Color(0xFF5C1A1A)
    val OnErrorContainer = Color(0xFFFFDAD6)
}

/** Colors outside the Material scheme. */
public object VeyroColors {
    /** Brand gradient for hero surfaces. White text meets 4.5:1 on both stops (4.8:1 each). */
    public val BrandGradient: Brush = Brush.linearGradient(
        listOf(VeyroPalette.VioletDeep, VeyroPalette.MagentaDeep),
    )

    /** Gradient for the wordmark text. */
    public val WordmarkGradient: Brush = Brush.horizontalGradient(
        listOf(VeyroPalette.Violet, VeyroPalette.Magenta),
    )

    /** Scrim behind text drawn over thumbnails (duration badges). */
    public val MediaScrim: Color = Color(0xB3000000)
}
