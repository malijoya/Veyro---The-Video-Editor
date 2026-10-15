package com.editor.veyro.feature.editor.keyframe

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.Keyframing
import com.editor.veyro.feature.editor.R
import kotlin.math.abs

/** What a keyframe bar can do. Each maps to one ViewModel call at the playhead. [T] is the kind of curve. */
internal class KeyframeActions<T>(
    val onAdd: () -> Unit,
    val onRemove: () -> Unit,
    /** Moves the playhead to a timeline time (to select a keyframe). */
    val onJumpTo: (Long) -> Unit,
    val onCurve: (T) -> Unit,
    /** Moves the keyframe at the playhead by a number of frames. */
    val onNudge: (Int) -> Unit,
    val onClear: () -> Unit,
)

/** The words a keyframe bar uses for what it animates. */
internal class KeyframeTexts(
    @StringRes val hintNone: Int,
    @StringRes val hintBetween: Int,
    @StringRes val curves: Int = R.string.keyframe_easing,
)

/**
 * Keyframes of the selected clip, for one kind of property.
 *
 * The keyframe at the playhead (within half a frame) is the selected one:
 * the diamond button deletes it, the curve chips set how it moves toward
 * the next keyframe, and [selectedContent] (or the clip's own controls) edits
 * its value. Elsewhere in the clip, the button adds one holding the current
 * value. The arrows step the playhead from keyframe to keyframe, which is how
 * a keyframe is selected without touching the timeline.
 *
 * [momentsUs] are keyframe times on the timeline, sorted. Nothing here keeps
 * time of its own: it all follows [playheadUs].
 */
@Composable
internal fun <T> KeyframeBar(
    momentsUs: List<Long>,
    clipStartUs: Long,
    clipEndUs: Long,
    playheadUs: Long,
    frameDurationUs: Long,
    enabled: Boolean,
    curves: List<T>,
    curveLabel: (T) -> Int,
    curveAt: (timelineUs: Long) -> T?,
    texts: KeyframeTexts,
    actions: KeyframeActions<T>,
    modifier: Modifier = Modifier,
    selectedContent: @Composable (timelineUs: Long) -> Unit = {},
) {
    val halfFrame = frameDurationUs / 2
    val moments = momentsUs.filter { it in clipStartUs until clipEndUs }
    val selected = moments.filter { abs(it - playheadUs) <= halfFrame }.minByOrNull { abs(it - playheadUs) }
    val inside = playheadUs in clipStartUs until clipEndUs
    val previous = moments.lastOrNull { it < playheadUs - halfFrame }
    val next = moments.firstOrNull { it > playheadUs + halfFrame }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { previous?.let(actions.onJumpTo) }, enabled = enabled && previous != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.keyframe_previous))
            }
            IconButton(
                onClick = { if (selected != null) actions.onRemove() else actions.onAdd() },
                enabled = enabled && inside,
            ) {
                Icon(
                    if (selected != null) VeyroIcons.Keyframe else VeyroIcons.KeyframeOutline,
                    contentDescription = stringResource(if (selected != null) R.string.keyframe_delete else R.string.keyframe_add),
                    tint = if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { next?.let(actions.onJumpTo) }, enabled = enabled && next != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.keyframe_next))
            }
            Text(
                pluralStringResource(R.plurals.keyframe_count, moments.size, moments.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.xs)
                    .semantics { heading() },
            )
            if (moments.isNotEmpty()) {
                TextButton(onClick = actions.onClear, enabled = enabled) { Text(stringResource(R.string.keyframe_clear)) }
            }
        }

        val isLast = selected != null && selected == moments.last()
        val hint = when {
            !inside -> R.string.keyframe_hint_outside
            moments.isEmpty() -> texts.hintNone
            selected == null -> texts.hintBetween
            isLast -> R.string.keyframe_hint_last
            else -> null
        }
        if (selected != null) {
            selectedContent(selected)
            val current = curveAt(selected)
            Text(stringResource(texts.curves), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                curves.forEach { curve ->
                    FilterChip(
                        selected = current == curve,
                        onClick = { actions.onCurve(curve) },
                        enabled = enabled && !isLast,
                        label = { Text(stringResource(curveLabel(curve))) },
                    )
                }
                if (current != null && current !in curves) {
                    FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(stringResource(R.string.easing_custom)) })
                }
                val earlier = stringResource(R.string.keyframe_earlier)
                val later = stringResource(R.string.keyframe_later)
                TextButton(onClick = { actions.onNudge(-1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = earlier }) {
                    Text(stringResource(R.string.keyframe_earlier_short))
                }
                TextButton(onClick = { actions.onNudge(1) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = later }) {
                    Text(stringResource(R.string.keyframe_later_short))
                }
            }
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
    }
}

/**
 * Placement keyframes (position, scale, rotation, opacity, crop) of the
 * selected picture or overlay. Moving, resizing, turning, fading or cropping
 * the clip with the playhead on a keyframe changes its values. [animation]
 * is in clip-local time.
 */
@Composable
internal fun PlacementKeyframeBar(
    animation: ClipAnimation,
    clipStartUs: Long,
    clipEndUs: Long,
    playheadUs: Long,
    frameDurationUs: Long,
    enabled: Boolean,
    actions: KeyframeActions<Easing>,
    modifier: Modifier = Modifier,
) {
    KeyframeBar(
        momentsUs = animation.transformKeyframeTimes().map { clipStartUs + it },
        clipStartUs = clipStartUs,
        clipEndUs = clipEndUs,
        playheadUs = playheadUs,
        frameDurationUs = frameDurationUs,
        enabled = enabled,
        curves = Easing.PRESETS,
        curveLabel = ::easingLabel,
        curveAt = { timelineUs -> Keyframing.easingAt(animation, timelineUs - clipStartUs) ?: Easing.Linear },
        texts = KeyframeTexts(R.string.keyframe_hint_none, R.string.keyframe_hint_between),
        actions = actions,
        modifier = modifier,
    )
}

private fun easingLabel(easing: Easing): Int = when (easing) {
    Easing.Linear -> R.string.easing_linear
    Easing.Hold -> R.string.easing_hold
    Easing.EASE_IN -> R.string.easing_ease_in
    Easing.EASE_OUT -> R.string.easing_ease_out
    Easing.EASE_IN_OUT -> R.string.easing_ease_in_out
    else -> R.string.easing_custom
}
