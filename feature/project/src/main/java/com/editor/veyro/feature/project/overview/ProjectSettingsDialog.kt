package com.editor.veyro.feature.project.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.CanvasPreset
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.feature.project.R
import com.editor.veyro.feature.project.formatFps

private val FRAME_RATES = listOf(
    FrameRate.FPS_23_976, FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_29_97,
    FrameRate.FPS_30, FrameRate.FPS_50, FrameRate.FPS_59_94, FrameRate.FPS_60,
)
private val PHOTO_DURATIONS_US = listOf(1_000_000L, 2_000_000L, 3_000_000L, 5_000_000L, 10_000_000L)

/** Snap distances offered, in dp, with their labels. */
private val SNAP_DISTANCES = listOf(
    6 to R.string.project_settings_snap_fine,
    ProjectSettings.DEFAULT_SNAP_DISTANCE_DP to R.string.project_settings_snap_normal,
    20 to R.string.project_settings_snap_strong,
)

/**
 * Edits a project's canvas, frame rate and editing preferences. Changes
 * apply only on Save.
 *
 * Changing the frame rate changes the edit's snapping grid and minimum clip
 * length. Existing clip times are kept exactly. The canvas sets the preview
 * frame now, and will set the export size later.
 */
@Composable
internal fun ProjectSettingsDialog(
    current: ProjectSettings,
    matchingMedia: ProjectSettings,
    onConfirm: (ProjectSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                SettingLabel(stringResource(R.string.project_settings_canvas, draft.canvasWidthPx, draft.canvasHeightPx))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = draft.canvasWidthPx == matchingMedia.canvasWidthPx && draft.canvasHeightPx == matchingMedia.canvasHeightPx,
                        onClick = { draft = draft.copy(canvasWidthPx = matchingMedia.canvasWidthPx, canvasHeightPx = matchingMedia.canvasHeightPx) },
                        label = { Text(stringResource(R.string.project_settings_match_media)) },
                    )
                    CanvasPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = draft.canvasWidthPx == preset.width && draft.canvasHeightPx == preset.height,
                            onClick = { draft = draft.copy(canvasWidthPx = preset.width, canvasHeightPx = preset.height) },
                            label = { Text(stringResource(preset.labelRes)) },
                        )
                    }
                }

                SettingLabel(stringResource(R.string.project_settings_frame_rate))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FRAME_RATES.forEach { rate ->
                        FilterChip(
                            selected = draft.frameRate == rate,
                            onClick = { draft = draft.copy(frameRate = rate) },
                            label = { Text(formatFps(rate)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.project_settings_frame_rate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingLabel(stringResource(R.string.project_settings_photo_duration))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PHOTO_DURATIONS_US.forEach { duration ->
                        FilterChip(
                            selected = draft.photoDurationUs == duration,
                            onClick = { draft = draft.copy(photoDurationUs = duration) },
                            label = { Text(stringResource(R.string.project_settings_seconds, duration / 1_000_000)) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.project_settings_snap), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.project_settings_snap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = draft.snapToEdges, onCheckedChange = { draft = draft.copy(snapToEdges = it) })
                }
                if (draft.snapToEdges) {
                    SettingLabel(stringResource(R.string.project_settings_snap_distance))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SNAP_DISTANCES.forEach { (distance, label) ->
                            FilterChip(
                                selected = draft.snapDistanceDp == distance,
                                onClick = { draft = draft.copy(snapDistanceDp = distance) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft != current) { Text(stringResource(R.string.project_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.project_cancel)) } },
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

private val CanvasPreset.labelRes: Int
    get() = when (this) {
        CanvasPreset.PORTRAIT_9_16 -> R.string.project_canvas_9_16
        CanvasPreset.LANDSCAPE_16_9 -> R.string.project_canvas_16_9
        CanvasPreset.SQUARE_1_1 -> R.string.project_canvas_1_1
        CanvasPreset.PORTRAIT_4_5 -> R.string.project_canvas_4_5
    }
