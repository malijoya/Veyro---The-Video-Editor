package com.editor.veyro.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import java.util.Locale
import kotlin.math.roundToInt

/** Longest fade the sliders offer: half the clip, at most this. */
private const val MAX_FADE_US = 10_000_000L

/**
 * Sound controls for the selected clip and its track. Every control makes a
 * real edit (one undoable step when the slider is released), and that edit
 * changes both the preview mix and the export:
 * - clip volume (0–100%), mute, fade-in and fade-out
 * - the track's volume and mute
 * - moving an audio clip between the Music and Voiceover tracks
 *
 * Clips without sound (photos, videos without an audio stream) get a plain
 * note instead of controls that would do nothing.
 */
@Composable
internal fun AudioControls(
    clip: TimelineClipUi,
    track: TimelineTrackUi,
    tracks: List<TimelineTrackUi>,
    enabled: Boolean,
    onSetClipAudio: (ClipId, ClipAudio) -> Unit,
    onSetTrackAudio: (TrackId, TrackAudio) -> Unit,
    onMoveToTrack: (ClipId, TrackId) -> Unit,
) {
    if (!clip.hasAudio) {
        Text(
            stringResource(R.string.editor_no_audio_stream),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val audio = clip.audio
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.editor_clip_sound), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = !audio.muted,
                onCheckedChange = { on -> onSetClipAudio(clip.id, audio.copy(muted = !on)) },
                enabled = enabled,
            )
        }
        GainSlider(
            label = stringResource(R.string.editor_clip_volume),
            value = audio.volume,
            enabled = enabled && !audio.muted,
            key = clip.id.value + "/volume",
            onCommit = { onSetClipAudio(clip.id, audio.copy(volume = it)) },
        )
        val maxFadeUs = minOf(clip.durationUs / 2, MAX_FADE_US).coerceAtLeast(1)
        DurationSlider(
            label = stringResource(R.string.editor_fade_in),
            valueUs = audio.fadeInUs.coerceAtMost(maxFadeUs),
            maxUs = maxFadeUs,
            enabled = enabled && !audio.muted,
            key = clip.id.value + "/fadeIn/" + clip.durationUs,
            onCommit = { onSetClipAudio(clip.id, audio.copy(fadeInUs = it)) },
        )
        DurationSlider(
            label = stringResource(R.string.editor_fade_out),
            valueUs = audio.fadeOutUs.coerceAtMost(maxFadeUs),
            maxUs = maxFadeUs,
            enabled = enabled && !audio.muted,
            key = clip.id.value + "/fadeOut/" + clip.durationUs,
            onCommit = { onSetClipAudio(clip.id, audio.copy(fadeOutUs = it)) },
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs).semantics(mergeDescendants = true) {}) {
            Text(
                stringResource(R.string.editor_track_sound, trackName(track)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = !track.audio.muted,
                onCheckedChange = { on -> onSetTrackAudio(track.id, track.audio.copy(muted = !on)) },
                enabled = enabled,
            )
        }
        GainSlider(
            label = stringResource(R.string.editor_track_volume),
            value = track.audio.volume,
            enabled = enabled && !track.audio.muted,
            key = track.id.value + "/volume",
            onCommit = { onSetTrackAudio(track.id, track.audio.copy(volume = it)) },
        )

        val otherAudioTracks = if (track.kind == TrackKind.AUDIO) tracks.filter { it.kind == TrackKind.AUDIO && it.id != track.id } else emptyList()
        if (otherAudioTracks.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                otherAudioTracks.forEach { target ->
                    OutlinedButton(onClick = { onMoveToTrack(clip.id, target.id) }, enabled = enabled) {
                        Text(stringResource(R.string.editor_move_to_track, trackName(target)))
                    }
                }
            }
        }
    }
}

@Composable
private fun trackName(track: TimelineTrackUi): String = stringResource(
    when {
        track.kind == TrackKind.VIDEO -> R.string.editor_track_video
        track.role == AudioRole.VOICEOVER -> R.string.editor_track_voiceover
        track.role == AudioRole.MUSIC -> R.string.editor_track_music
        else -> R.string.editor_track_audio
    },
)

/** 0–100% in steps of 1%. Moves freely while dragging and commits once on release. */
@Composable
private fun GainSlider(label: String, value: Float, enabled: Boolean, key: String, onCommit: (Float) -> Unit) {
    var dragging by remember(key, value) { mutableFloatStateOf(value) }
    val percent = (dragging * 100).roundToInt()
    val text = stringResource(R.string.editor_percent, percent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
        Slider(
            value = dragging,
            onValueChange = { dragging = (it * 100).roundToInt() / 100f },
            onValueChangeFinished = { if (dragging != value) onCommit(dragging) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { stateDescription = text },
        )
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp).padding(start = Spacing.xs))
    }
}

/** 0 to [maxUs] in 0.1 s steps. Commits once on release. */
@Composable
private fun DurationSlider(label: String, valueUs: Long, maxUs: Long, enabled: Boolean, key: String, onCommit: (Long) -> Unit) {
    var dragging by remember(key, valueUs) { mutableFloatStateOf(valueUs.toFloat() / maxUs) }
    val us = (dragging * maxUs / 100_000).roundToInt() * 100_000L
    val text = stringResource(R.string.editor_seconds, String.format(Locale.getDefault(), "%.1f", us / 1_000_000.0))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { if (us.coerceAtMost(maxUs) != valueUs) onCommit(us.coerceAtMost(maxUs)) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { stateDescription = text },
        )
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp).padding(start = Spacing.xs))
    }
}
