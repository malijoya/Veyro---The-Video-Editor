package com.editor.veyro.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.designsystem.theme.VeyroColors

/** Small label drawn on top of a thumbnail, such as a duration ("1:05") or "Photo". */
@Composable
public fun OverlayBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .background(VeyroColors.MediaScrim, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Spacing.xs + Spacing.xxs, vertical = Spacing.xxs),
    )
}

/**
 * Marks a feature that is visible but not built yet, for example "Coming
 * soon". It must always appear next to the feature it describes, so users
 * never mistake a missing feature for a broken one.
 */
@Composable
public fun AvailabilityBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}
