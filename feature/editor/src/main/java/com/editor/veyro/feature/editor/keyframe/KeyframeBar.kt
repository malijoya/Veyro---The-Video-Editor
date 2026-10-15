package com.editor.veyro.feature.editor.keyframe

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

/** What the keyframe bar can do. Each maps to one ViewModel call at the playhead. */
internal class KeyframeActions(
    val onAdd: () -> Unit,
    val onRemove: () -> Unit,
    /** Moves the playhead to a timeline time (to select a keyframe). */
    val onJumpTo: (Long) -> Unit,
    val onEasing: (Easing) -> Unit,
    /** Moves the keyframe at the playhead by a number of frames. */
    val onNudge: (Int) -> Unit,
    val onClear: () -> Unit,
)

/**
 * Keyframes of the selected picture or overlay.
 *
 * The keyframe at the playhead (within half a frame) is the selected one:
 * the diamond button deletes it, the curve chips set how it eases toward the
 * next keyframe, and moving, resizing, turning or fading the clip changes
 * its values. Elsewhere in the clip, the button adds one holding the current
 * values. The arrows step the playhead from keyframe to keyframe, which is
 * how a keyframe is selected without touching the timeline.
 *
 * [animation] is in clip-local time; [clipStartUs] turns it into timeline
 * time. Nothing here keeps time of its own: it all follows [playheadUs].
 */
@Composable
internal fun KeyframeBar(
    animation: ClipAnimation,
    clipStartUs: Long,
    clipEndUs: Long,
    playheadUs: Long,
    frameDurationUs: Long,
    enabled: Boolean,
    actions: KeyframeActions,
    modifier: Modifier = Modifier,
) {
    val halfFrame = frameDurationUs / 2
    val moments = animation.transformKeyframeTimes().map { clipStartUs + it }.filter { it in clipStartUs until clipEndUs }
    val selectedLocal = Keyframing.momentNear(animation, playheadUs - clipStartUs, halfFrame)?.takeIf { clipStartUs + it in clipStartUs until clipEndUs }
    val inside = playheadUs in clipStartUs until clipEndUs
    val previous = moments.lastOrNull { it < playheadUs - halfFrame }
    val next = moments.firstOrNull { it > playheadUs + halfFrame }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { previous?.let(actions.onJumpTo) }, enabled = enabled && previous != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.keyframe_previous))
            }
            IconButton(
                onClick = { if (selectedLocal != null) actions.onRemove() else actions.onAdd() },
                enabled = enabled && inside,
            ) {
                Icon(
                    if (selectedLocal != null) VeyroIcons.Keyframe else VeyroIcons.KeyframeOutline,
                    contentDescription = stringResource(if (selectedLocal != null) R.string.keyframe_delete else R.string.keyframe_add),
                    tint = if (selectedLocal != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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

        val hint = when {
            !inside -> R.string.keyframe_hint_outside
            moments.isEmpty() -> R.string.keyframe_hint_none
            selectedLocal == null -> R.string.keyframe_hint_between
            clipStartUs + selectedLocal == moments.last() -> R.string.keyframe_hint_last
            else -> null
        }
        if (selectedLocal != null) {
            val current = Keyframing.easingAt(animation, selectedLocal) ?: Easing.Linear
            val isLast = clipStartUs + selectedLocal == moments.last()
            Text(
                stringResource(R.string.keyframe_easing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Easing.PRESETS.forEach { easing ->
                    FilterChip(
                        selected = current == easing,
                        onClick = { actions.onEasing(easing) },
                        enabled = enabled && !isLast,
                        label = { Text(stringResource(easingLabel(easing))) },
                    )
                }
                if (current !in Easing.PRESETS) {
                    FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(stringResource(R.string.easing_custom)) })
                }
                val earlier = stringResource(R.string.keyframe_earlier)
                val later = stringResource(R.string.keyframe_later)
                TextButton(
                    onClick = { actions.onNudge(-1) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = earlier },
                ) {
                    Text(stringResource(R.string.keyframe_earlier_short))
                }
                TextButton(
                    onClick = { actions.onNudge(1) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = later },
                ) {
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

private fun easingLabel(easing: Easing): Int = when (easing) {
    Easing.Linear -> R.string.easing_linear
    Easing.Hold -> R.string.easing_hold
    Easing.EASE_IN -> R.string.easing_ease_in
    Easing.EASE_OUT -> R.string.easing_ease_out
    Easing.EASE_IN_OUT -> R.string.easing_ease_in_out
    else -> R.string.easing_custom
}
