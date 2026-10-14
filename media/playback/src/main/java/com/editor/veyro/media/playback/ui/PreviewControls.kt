package com.editor.veyro.media.playback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.media.playback.PreviewState
import com.editor.veyro.media.playback.PreviewStatus
import com.editor.veyro.media.playback.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow

/** Test tags for UI tests. */
public object PreviewControlsTags {
    public const val PLAY_PAUSE: String = "preview_play_pause"
    public const val SEEK_BAR: String = "preview_seek_bar"
    public const val TIME: String = "preview_time"
}

/** How far the skip buttons jump. */
public const val PREVIEW_SKIP_US: Long = 5_000_000

/**
 * Basic transport controls: a seek bar with time, play/pause (replay at the
 * end), and ±5 s skips.
 *
 * Dragging the seek bar moves only the thumb and the time label. The seek is
 * sent once, on release, so dragging doesn't flood the decoder with seeks.
 * Controls are disabled until the media is ready or ended.
 */
@Composable
public fun PreviewControls(
    state: PreviewState,
    onPlayPause: () -> Unit,
    onSeekTo: (positionUs: Long) -> Unit,
    onSeekBy: (deltaUs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val enabled = state.status == PreviewStatus.READY || state.status == PreviewStatus.ENDED
    val shownPositionUs = scrubFraction?.let { (it * state.durationUs).toLong() } ?: state.positionUs
    val timeText = stringResource(
        R.string.preview_time,
        DurationFormat.clock(shownPositionUs.coerceAtLeast(0)),
        DurationFormat.clock(state.durationUs.coerceAtLeast(0)),
    )

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = scrubFraction ?: state.progress,
            onValueChange = { scrubFraction = it },
            onValueChangeFinished = {
                scrubFraction?.let { onSeekTo((it * state.durationUs).toLong()) }
                scrubFraction = null
            },
            enabled = enabled && state.durationUs > 0,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PreviewControlsTags.SEEK_BAR)
                .semantics { stateDescription = timeText },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                timeText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewControlsTags.TIME),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSeekBy(-PREVIEW_SKIP_US) }, enabled = enabled) {
                    Icon(VeyroIcons.Replay5, contentDescription = stringResource(R.string.preview_back_5))
                }
                PlayPauseButton(state, enabled, onPlayPause)
                IconButton(onClick = { onSeekBy(PREVIEW_SKIP_US) }, enabled = enabled) {
                    Icon(VeyroIcons.Forward5, contentDescription = stringResource(R.string.preview_forward_5))
                }
            }
            // Balances the time label so the buttons sit in the middle.
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayPauseButton(state: PreviewState, enabled: Boolean, onPlayPause: () -> Unit) {
    val label = when {
        state.status == PreviewStatus.ENDED -> stringResource(R.string.preview_replay)
        state.isPlaying -> stringResource(R.string.preview_pause)
        else -> stringResource(R.string.preview_play)
    }
    FilledIconButton(
        onClick = onPlayPause,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .testTag(PreviewControlsTags.PLAY_PAUSE)
            .semantics { contentDescription = label },
    ) {
        if (state.status == PreviewStatus.LOADING) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = when {
                    state.status == PreviewStatus.ENDED -> VeyroIcons.Replay
                    state.isPlaying -> VeyroIcons.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = null,
                modifier = Modifier.padding(Spacing.xxs),
            )
        }
    }
}
