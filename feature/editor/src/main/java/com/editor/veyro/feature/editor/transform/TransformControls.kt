package com.editor.veyro.feature.editor.transform

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.transform.AspectRatio
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import com.editor.veyro.feature.editor.PictureUi
import com.editor.veyro.feature.editor.R
import com.editor.veyro.feature.editor.overlay.LabeledSlider
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the transform panel can do. Each maps to one ViewModel call. */
internal class TransformActions(
    /** Shows a change at once without saving it (sliders). */
    val onPreview: (ClipTransform) -> Unit,
    /** Saves the change shown by [onPreview] as one undo step. */
    val onCommit: () -> Unit,
    /** Saves a change at once (a button, a preset). */
    val onSet: (ClipTransform) -> Unit,
    val onFit: (FitMode) -> Unit,
    val onReset: () -> Unit,
)

/** The panel's tabs. [CROP] also switches the preview to crop editing. */
internal enum class TransformTab(@StringRes val label: Int) {
    ADJUST(R.string.transform_tab_adjust),
    CROP(R.string.transform_tab_crop),
    ANCHOR(R.string.transform_tab_anchor),
}

/**
 * Position, scale, rotation, opacity, crop and anchor of the selected video
 * or photo. Everything the preview gestures do is also here, without
 * gestures. Sliders show their effect live (in the real render) and save
 * once when released; buttons and presets save at once.
 *
 * Values are shown in canvas terms (percent of the fitted size, percent of
 * the canvas from its centre), never in screen pixels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransformControls(
    picture: PictureUi,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    tab: TransformTab,
    onTabChange: (TransformTab) -> Unit,
    enabled: Boolean,
    actions: TransformActions,
) {
    val transform = picture.transform
    val placement = picture.placement(canvasWidthPx, canvasHeightPx)
    Column(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(onClick = { actions.onFit(FitMode.FIT) }, enabled = enabled) { Text(stringResource(R.string.transform_fit)) }
            OutlinedButton(onClick = { actions.onFit(FitMode.FILL) }, enabled = enabled) { Text(stringResource(R.string.transform_fill)) }
            OutlinedButton(onClick = actions.onReset, enabled = enabled && !transform.isDefault) { Text(stringResource(R.string.transform_reset)) }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp, containerColor = Color.Transparent) {
            TransformTab.entries.forEach { candidate ->
                Tab(
                    selected = candidate == tab,
                    onClick = {
                        actions.onCommit()
                        onTabChange(candidate)
                    },
                    text = { Text(stringResource(candidate.label)) },
                )
            }
        }
        Box(Modifier.padding(top = Spacing.sm)) {
            when (tab) {
                TransformTab.ADJUST -> AdjustTab(transform, enabled, actions)
                TransformTab.CROP -> CropTab(picture, transform, enabled, actions) { crop, aspect -> placement.withCrop(crop, aspect) }
                TransformTab.ANCHOR -> AnchorTab(transform, enabled, actions) { u, v -> placement.withAnchor(u, v) }
            }
        }
    }
}

@Composable
private fun AdjustTab(transform: ClipTransform, enabled: Boolean, actions: TransformActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LabeledSlider(
            label = stringResource(R.string.transform_scale),
            valueText = stringResource(R.string.editor_percent, (transform.scale * 100).roundToInt()),
            value = transform.scale,
            range = MIN_UI_SCALE..MAX_UI_SCALE,
            enabled = enabled,
            onChange = { actions.onPreview(transform.with(scale = snapped(it, 1f, 0.02f))) },
            onDone = actions.onCommit,
        )
        LabeledSlider(
            label = stringResource(R.string.transform_rotation),
            valueText = stringResource(R.string.overlay_degrees, transform.rotationDegrees.roundToInt()),
            value = transform.rotationDegrees,
            range = -179f..180f,
            enabled = enabled,
            onChange = { actions.onPreview(transform.with(rotationDegrees = snapped(it, (it / 90f).roundToInt() * 90f, 2f))) },
            onDone = actions.onCommit,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { actions.onSet(transform.with(rotationDegrees = transform.rotationDegrees - 90f)) }, enabled = enabled) {
                Text(stringResource(R.string.transform_rotate_left))
            }
            OutlinedButton(onClick = { actions.onSet(transform.with(rotationDegrees = transform.rotationDegrees + 90f)) }, enabled = enabled) {
                Text(stringResource(R.string.transform_rotate_right))
            }
            OutlinedButton(onClick = { actions.onSet(transform.with(positionX = 0.5f, positionY = 0.5f)) }, enabled = enabled) {
                Text(stringResource(R.string.transform_center))
            }
        }
        LabeledSlider(
            label = stringResource(R.string.transform_opacity),
            valueText = stringResource(R.string.editor_percent, (transform.opacity * 100).roundToInt()),
            value = transform.opacity,
            range = 0f..1f,
            enabled = enabled,
            onChange = { actions.onPreview(transform.with(opacity = it)) },
            onDone = actions.onCommit,
        )
        // Offsets from the canvas's centre, in percent of the canvas.
        LabeledSlider(
            label = stringResource(R.string.transform_position_x),
            valueText = stringResource(R.string.transform_offset, ((transform.positionX - 0.5f) * 100).roundToInt()),
            value = transform.positionX,
            range = MIN_UI_POSITION..MAX_UI_POSITION,
            enabled = enabled,
            onChange = { actions.onPreview(transform.with(positionX = snapped(it, 0.5f, 0.01f))) },
            onDone = actions.onCommit,
        )
        LabeledSlider(
            label = stringResource(R.string.transform_position_y),
            valueText = stringResource(R.string.transform_offset, ((transform.positionY - 0.5f) * 100).roundToInt()),
            value = transform.positionY,
            range = MIN_UI_POSITION..MAX_UI_POSITION,
            enabled = enabled,
            onChange = { actions.onPreview(transform.with(positionY = snapped(it, 0.5f, 0.01f))) },
            onDone = actions.onCommit,
        )
    }
}

/**
 * Crop shapes and a reset. The crop frame itself is dragged on the
 * preview. [withCrop] keeps the source where it is on the canvas.
 */
@Composable
private fun CropTab(
    picture: PictureUi,
    transform: ClipTransform,
    enabled: Boolean,
    actions: TransformActions,
    withCrop: (CropRect, AspectRatio?) -> ClipTransform,
) {
    val known = picture.sourceWidthPx > 0 && picture.sourceHeightPx > 0
    val original = AspectRatio.of(picture.sourceWidthPx, picture.sourceHeightPx)
    val crop = transform.crop
    fun shaped(aspect: AspectRatio): ClipTransform? =
        CropRect.forAspect(aspect, picture.sourceWidthPx, picture.sourceHeightPx, crop.centerX, crop.centerY)?.let { withCrop(it, aspect) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.transform_crop_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = transform.cropAspect == null,
                onClick = { actions.onSet(transform.with(cropAspect = null)) },
                enabled = enabled,
                label = { Text(stringResource(R.string.transform_crop_free)) },
            )
            if (original != null) {
                FilterChip(
                    selected = transform.cropAspect == original,
                    onClick = { shaped(original)?.let(actions.onSet) },
                    enabled = enabled && known,
                    label = { Text(stringResource(R.string.transform_crop_original)) },
                )
            }
            AspectRatio.PRESETS.forEach { aspect ->
                FilterChip(
                    selected = transform.cropAspect == aspect,
                    onClick = { shaped(aspect)?.let(actions.onSet) },
                    enabled = enabled && known,
                    label = { Text(aspect.toString()) },
                )
            }
        }
        OutlinedButton(
            onClick = { actions.onSet(withCrop(CropRect.FULL, null)) },
            enabled = enabled && (!crop.isFull || transform.cropAspect != null),
        ) { Text(stringResource(R.string.transform_crop_reset)) }
    }
}

/**
 * The pivot for scaling and rotation. Moving it never moves the picture:
 * [withAnchor] moves the position with it.
 */
@Composable
private fun AnchorTab(transform: ClipTransform, enabled: Boolean, actions: TransformActions, withAnchor: (Float, Float) -> ClipTransform) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.transform_anchor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AnchorGrid(transform, enabled) { u, v -> actions.onSet(withAnchor(u, v)) }
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = stringResource(R.string.transform_anchor_x),
                    valueText = stringResource(R.string.editor_percent, (transform.anchorX * 100).roundToInt()),
                    value = transform.anchorX,
                    range = 0f..1f,
                    enabled = enabled,
                    onChange = { actions.onPreview(withAnchor(it, transform.anchorY)) },
                    onDone = actions.onCommit,
                )
                LabeledSlider(
                    label = stringResource(R.string.transform_anchor_y),
                    valueText = stringResource(R.string.editor_percent, (transform.anchorY * 100).roundToInt()),
                    value = transform.anchorY,
                    range = 0f..1f,
                    enabled = enabled,
                    onChange = { actions.onPreview(withAnchor(transform.anchorX, it)) },
                    onDone = actions.onCommit,
                )
            }
        }
    }
}

/** Nine anchor presets: corners, edge midpoints and the centre. */
@Composable
private fun AnchorGrid(transform: ClipTransform, enabled: Boolean, onPick: (Float, Float) -> Unit) {
    val names = listOf(
        R.string.transform_anchor_top_left, R.string.transform_anchor_top, R.string.transform_anchor_top_right,
        R.string.transform_anchor_left, R.string.transform_anchor_center, R.string.transform_anchor_right,
        R.string.transform_anchor_bottom_left, R.string.transform_anchor_bottom, R.string.transform_anchor_bottom_right,
    )
    Column {
        for (row in 0..2) {
            Row {
                for (column in 0..2) {
                    val u = column / 2f
                    val v = row / 2f
                    val isSelected = abs(transform.anchorX - u) < 0.005f && abs(transform.anchorY - v) < 0.005f
                    val description = stringResource(names[row * 3 + column])
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(enabled = enabled) { onPick(u, v) }
                            .semantics {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = description
                            },
                    ) {
                        Box(
                            Modifier
                                .size(if (isSelected) 16.dp else 10.dp)
                                .border(
                                    width = if (isSelected) 5.dp else 1.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** [value], or [target] when within [distance] of it: sliders click into their usual stops. */
private fun snapped(value: Float, target: Float, distance: Float): Float = if (abs(value - target) <= distance) target else value

private const val MIN_UI_SCALE = 0.1f
private const val MAX_UI_SCALE = 5f
private const val MIN_UI_POSITION = -0.5f
private const val MAX_UI_POSITION = 1.5f
