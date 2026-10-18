package com.editor.veyro.feature.editor.transition

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionAlignment
import com.editor.veyro.core.model.transition.TransitionLimit
import com.editor.veyro.core.model.transition.TransitionType
import com.editor.veyro.feature.editor.CutUi
import com.editor.veyro.feature.editor.R
import java.util.Locale
import kotlin.math.roundToLong

/** Transitions grouped the way the panel offers them: a kind, then a direction where it has one. */
internal enum class TransitionFamily(val types: List<TransitionType>) {
    FADE(listOf(TransitionType.FADE)),
    DISSOLVE(listOf(TransitionType.DISSOLVE)),
    WIPE(listOf(TransitionType.WIPE_LEFT, TransitionType.WIPE_RIGHT, TransitionType.WIPE_UP, TransitionType.WIPE_DOWN)),
    SLIDE(listOf(TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT, TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN)),
    ZOOM(listOf(TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT)),
    SPIN(listOf(TransitionType.SPIN_CLOCKWISE, TransitionType.SPIN_COUNTERCLOCKWISE)),
    ;

    companion object {
        fun of(type: TransitionType): TransitionFamily = entries.first { type in it.types }
    }
}

/** Panel intents for one cut. Each is one undoable edit. */
internal class TransitionActions(
    val onSet: (Transition?) -> Unit,
    val onFit: () -> Unit,
    val onPlay: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The transition at the selected cut: its kind and direction, length,
 * timing curve and alignment, with what actually plays. Every change is a
 * real edit that preview and export render alike.
 *
 * When the clips can't hold the whole transition, the panel says why and,
 * if trimming would help, offers "Trim to fit".
 */
@Composable
internal fun TransitionControls(cut: CutUi, enabled: Boolean, actions: TransitionActions) {
    val transition = cut.transition
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    transition?.let { transitionName(it.type) } ?: stringResource(R.string.transition_none),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    status(cut),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cut.limit != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (transition != null) {
                TextButton(onClick = actions.onPlay, enabled = enabled) { Text(stringResource(R.string.transition_play)) }
            }
            TextButton(onClick = actions.onClose) { Text(stringResource(R.string.transition_done)) }
        }

        // Kind.
        val family = transition?.let { TransitionFamily.of(it.type) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = transition == null,
                onClick = { actions.onSet(null) },
                enabled = enabled,
                label = { Text(stringResource(R.string.transition_none)) },
            )
            TransitionFamily.entries.forEach { option ->
                FilterChip(
                    selected = family == option,
                    onClick = { if (family != option) actions.onSet(withType(transition, option.types.first())) },
                    enabled = enabled,
                    label = { Text(familyName(option)) },
                )
            }
        }
        if (transition == null) {
            Text(
                stringResource(R.string.transition_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // Direction, for kinds that have one.
        if (family != null && family.types.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                family.types.forEach { type ->
                    FilterChip(
                        selected = transition.type == type,
                        onClick = { actions.onSet(withType(transition, type)) },
                        enabled = enabled,
                        label = { Text(directionName(type).replaceFirstChar { it.titlecase(Locale.getDefault()) }) },
                    )
                }
            }
        }

        LengthSlider(cut, transition, enabled, onCommit = { actions.onSet(transition.copy(durationUs = it)) })

        Text(stringResource(R.string.transition_timing), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Transition.EASINGS.forEach { easing ->
                FilterChip(
                    selected = transition.easing == easing,
                    onClick = { actions.onSet(transition.copy(easing = easing)) },
                    enabled = enabled,
                    label = { Text(easingName(easing)) },
                )
            }
        }

        Text(stringResource(R.string.transition_alignment), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TransitionAlignment.entries.forEach { alignment ->
                FilterChip(
                    selected = transition.alignment == alignment,
                    onClick = { actions.onSet(transition.copy(alignment = alignment)) },
                    enabled = enabled,
                    label = { Text(alignmentName(alignment)) },
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (cut.limit == TransitionLimit.MEDIA_HANDLES) {
                OutlinedButton(onClick = actions.onFit, enabled = enabled) { Text(stringResource(R.string.transition_fit)) }
            }
            OutlinedButton(onClick = { actions.onSet(null) }, enabled = enabled) { Text(stringResource(R.string.transition_remove)) }
        }
    }
}

/** [current] changed to [type], keeping its length and alignment, and its curve unless it was the old type's default. */
private fun withType(current: Transition?, type: TransitionType): Transition =
    if (current == null) {
        Transition(type)
    } else {
        current.copy(type = type, easing = if (current.easing == current.type.defaultEasing) type.defaultEasing else current.easing)
    }

/** The requested length, in steps of 0.1 s. Committed once when the finger lifts, so the preview reloads once. */
@Composable
private fun LengthSlider(cut: CutUi, transition: Transition, enabled: Boolean, onCommit: (Long) -> Unit) {
    val range = (MIN_SLIDER_US.toFloat())..(MAX_SLIDER_US.toFloat())
    var dragging by remember(cut.outgoingClipId, transition.durationUs) {
        mutableFloatStateOf(transition.durationUs.coerceIn(MIN_SLIDER_US, MAX_SLIDER_US).toFloat())
    }
    val us = ((dragging / STEP_US).roundToLong() * STEP_US).coerceIn(MIN_SLIDER_US, MAX_SLIDER_US)
    val text = seconds(us)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.transition_length), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { if (us != transition.durationUs) onCommit(us) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { stateDescription = text },
        )
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp).padding(start = Spacing.xs))
    }
}

/** What plays: the length in seconds and frames, or why it is shorter or a plain cut. */
@Composable
private fun status(cut: CutUi): String {
    if (cut.transition == null) return stringResource(R.string.transition_status_cut)
    val plays = pluralStringResource(R.plurals.transition_frames, cut.frames, seconds(cut.endUs - cut.startUs), cut.frames)
    return when {
        cut.frames == 0 && cut.limit == TransitionLimit.MEDIA_HANDLES -> stringResource(R.string.transition_status_no_media)
        cut.frames == 0 -> stringResource(R.string.transition_status_too_short)
        cut.limit == TransitionLimit.MEDIA_HANDLES -> stringResource(R.string.transition_status_shortened_media, plays)
        cut.limit == TransitionLimit.CLIP_LENGTH -> stringResource(R.string.transition_status_shortened_length, plays)
        else -> plays
    }
}

@Composable
private fun seconds(us: Long): String = stringResource(R.string.editor_seconds, String.format(Locale.getDefault(), "%.1f", us / 1_000_000.0))

@Composable
internal fun transitionName(type: TransitionType): String = when (TransitionFamily.of(type)) {
    TransitionFamily.FADE, TransitionFamily.DISSOLVE -> familyName(TransitionFamily.of(type))
    else -> stringResource(R.string.transition_name_with_direction, familyName(TransitionFamily.of(type)), directionName(type))
}

@Composable
private fun familyName(family: TransitionFamily): String = stringResource(
    when (family) {
        TransitionFamily.FADE -> R.string.transition_fade
        TransitionFamily.DISSOLVE -> R.string.transition_dissolve
        TransitionFamily.WIPE -> R.string.transition_wipe
        TransitionFamily.SLIDE -> R.string.transition_slide
        TransitionFamily.ZOOM -> R.string.transition_zoom
        TransitionFamily.SPIN -> R.string.transition_spin
    },
)

@Composable
private fun directionName(type: TransitionType): String = stringResource(
    when (type) {
        TransitionType.WIPE_LEFT, TransitionType.SLIDE_LEFT -> R.string.transition_direction_left
        TransitionType.WIPE_RIGHT, TransitionType.SLIDE_RIGHT -> R.string.transition_direction_right
        TransitionType.WIPE_UP, TransitionType.SLIDE_UP -> R.string.transition_direction_up
        TransitionType.WIPE_DOWN, TransitionType.SLIDE_DOWN -> R.string.transition_direction_down
        TransitionType.ZOOM_IN -> R.string.transition_direction_in
        TransitionType.ZOOM_OUT -> R.string.transition_direction_out
        TransitionType.SPIN_CLOCKWISE -> R.string.transition_direction_clockwise
        TransitionType.SPIN_COUNTERCLOCKWISE -> R.string.transition_direction_counterclockwise
        TransitionType.FADE -> R.string.transition_fade
        TransitionType.DISSOLVE -> R.string.transition_dissolve
    },
)

@Composable
private fun easingName(easing: Easing): String = stringResource(
    when (easing) {
        Easing.EASE_IN -> R.string.easing_ease_in
        Easing.EASE_OUT -> R.string.easing_ease_out
        Easing.EASE_IN_OUT -> R.string.easing_ease_in_out
        Easing.Hold -> R.string.easing_hold
        Easing.Linear -> R.string.easing_linear
        else -> R.string.easing_custom
    },
)

@Composable
private fun alignmentName(alignment: TransitionAlignment): String = stringResource(
    when (alignment) {
        TransitionAlignment.CENTER -> R.string.transition_align_center
        TransitionAlignment.BEFORE_CUT -> R.string.transition_align_before
        TransitionAlignment.AFTER_CUT -> R.string.transition_align_after
    },
)

private const val STEP_US = 100_000L
private const val MIN_SLIDER_US = 100_000L
private const val MAX_SLIDER_US = Transition.MAX_DURATION_US
