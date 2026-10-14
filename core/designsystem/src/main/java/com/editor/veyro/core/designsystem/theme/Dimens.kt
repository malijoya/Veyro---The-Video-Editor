package com.editor.veyro.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale on a 4 dp grid. Use these instead of literal dp values in layouts. */
public object Spacing {
    public val xxs: Dp = 2.dp
    public val xs: Dp = 4.dp
    public val sm: Dp = 8.dp
    public val md: Dp = 12.dp
    public val lg: Dp = 16.dp
    public val xl: Dp = 24.dp
    public val xxl: Dp = 32.dp
    public val xxxl: Dp = 48.dp
}

/** Layout sizes shared by screens. */
public object Layout {
    /** Horizontal padding between screen content and the window edge. */
    public val screenGutter: Dp = 16.dp

    /** Content is centered and capped at this width on large screens, so lines stay readable. */
    public val maxContentWidth: Dp = 1_040.dp

    /** Minimum width of a project card. The grid fits as many columns as the window allows. */
    public val projectCardMinWidth: Dp = 160.dp

    /** Minimum width of a media tile in a project's media grid. */
    public val mediaTileMinWidth: Dp = 112.dp
}

internal val VeyroShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
