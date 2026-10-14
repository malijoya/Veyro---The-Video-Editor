package com.editor.veyro.feature.editor

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.AvailabilityBadge
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.feature.editor.timeline.TimelineMetrics
import com.editor.veyro.feature.editor.timeline.TimelineView
import com.editor.veyro.feature.editor.timeline.TimelineViewportState
import com.editor.veyro.feature.editor.timeline.rememberTimelineViewportState
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.media.playback.PreviewState
import com.editor.veyro.media.playback.PreviewStatus
import com.editor.veyro.media.playback.ui.BindPreviewLifecycle
import com.editor.veyro.media.playback.ui.PreviewSurface

/** Minimum time between seeks while scrubbing. Fast enough to feel live, without saturating the decoder. */
private const val SCRUB_SEEK_INTERVAL_MS = 80L

@Composable
public fun EditorRoute(viewModel: EditorViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    val player by viewModel.controller.player.collectAsStateWithLifecycle()
    val viewport = rememberTimelineViewportState()
    BindPreviewLifecycle(viewModel.controller)
    PlayheadSync(viewModel.controller, viewport, (uiState as? EditorUiState.Ready)?.durationUs ?: 0)

    EditorScreen(
        uiState = uiState,
        playback = playback,
        viewport = viewport,
        surface = { modifier -> PreviewSurface(player, modifier) },
        onBack = onBack,
        onPlayPause = viewModel.controller::togglePlayPause,
        onSelectClip = viewModel::selectClip,
        onScrubStart = viewModel.controller::pause,
        onScrubEnd = { viewModel.controller.seekTo(viewport.playheadUs) },
        onJumpTo = { timeUs ->
            viewport.scrollToTime(timeUs)
            viewModel.controller.seekTo(timeUs)
        },
    )
}

/**
 * Keeps the playhead and the player in step:
 * - while the user isn't touching the timeline, the player's position moves
 *   the playhead
 * - while they scrub, the playhead drives seeks, at most every
 *   [SCRUB_SEEK_INTERVAL_MS]
 */
@Composable
private fun PlayheadSync(controller: PreviewController, viewport: TimelineViewportState, durationUs: Long) {
    LaunchedEffect(durationUs) { viewport.durationUs = durationUs }
    LaunchedEffect(controller, viewport) {
        controller.state.collect { state ->
            if (!viewport.isUserInteracting) viewport.scrollToTime(state.positionUs)
        }
    }
    LaunchedEffect(controller, viewport) {
        var lastSeekAt = 0L
        snapshotFlow { viewport.playheadUs to viewport.isUserInteracting }.collect { (playheadUs, interacting) ->
            val now = SystemClock.uptimeMillis()
            if (interacting && now - lastSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
                controller.seekTo(playheadUs)
                lastSeekAt = now
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    uiState: EditorUiState,
    playback: PreviewState,
    viewport: TimelineViewportState,
    surface: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSelectClip: (com.editor.veyro.core.model.timeline.ClipId?) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onJumpTo: (Long) -> Unit,
) {
    val ready = uiState as? EditorUiState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.projectName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Short windows (landscape phones, split screen) get slimmer tracks so the preview keeps some height.
            val compact = maxHeight < 560.dp
            when (uiState) {
                EditorUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                EditorUiState.NotFound -> EmptyState(
                    icon = VeyroIcons.VideoLibrary,
                    title = stringResource(R.string.editor_not_found_title),
                    message = stringResource(R.string.editor_not_found_message),
                )
                EditorUiState.LoadFailed -> EmptyState(
                    icon = VeyroIcons.BrokenImage,
                    title = stringResource(R.string.editor_load_failed_title),
                    message = stringResource(R.string.editor_load_failed_message),
                )
                is EditorUiState.Ready -> EditorContent(
                    state = uiState,
                    playback = playback,
                    viewport = viewport,
                    compact = compact,
                    surface = surface,
                    onPlayPause = onPlayPause,
                    onSelectClip = onSelectClip,
                    onScrubStart = onScrubStart,
                    onScrubEnd = onScrubEnd,
                    onJumpTo = onJumpTo,
                )
            }
        }
    }
}

@Composable
private fun EditorContent(
    state: EditorUiState.Ready,
    playback: PreviewState,
    viewport: TimelineViewportState,
    compact: Boolean,
    surface: @Composable (Modifier) -> Unit,
    onPlayPause: () -> Unit,
    onSelectClip: (com.editor.veyro.core.model.timeline.ClipId?) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onJumpTo: (Long) -> Unit,
) {
    // Fit the whole timeline once when the editor first has a size and some content.
    var fitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewport, state.durationUs) {
        snapshotFlow { viewport.widthPx }.collect { width ->
            if (!fitted && width > 0f && state.durationUs > 0) {
                viewport.durationUs = state.durationUs
                viewport.zoomToFit()
                fitted = true
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            PreviewStage(state, playback, viewport, surface)
        }
        TransportRow(state, playback, viewport, onPlayPause)
        if (state.tracks.all { it.clips.isEmpty() }) {
            Text(
                stringResource(R.string.editor_empty_timeline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Layout.screenGutter),
            )
        } else {
            TimelineView(
                tracks = state.tracks,
                selectedClipId = state.selectedClip?.id,
                viewport = viewport,
                metrics = if (compact) TimelineMetrics.Compact else TimelineMetrics.Regular,
                onSelectClip = onSelectClip,
                onInteractionStart = onScrubStart,
                onInteractionEnd = onScrubEnd,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SelectionBar(state, compact, onClearSelection = { onSelectClip(null) }, onJumpTo = onJumpTo)
    }
}

/** The preview: the video surface, with a photo drawn on top while the playhead is on a photo clip. */
@Composable
private fun PreviewStage(
    state: EditorUiState.Ready,
    playback: PreviewState,
    viewport: TimelineViewportState,
    surface: @Composable (Modifier) -> Unit,
) {
    val mainTrack = state.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
    val clipUnderPlayhead by remember(mainTrack) {
        derivedStateOf {
            val time = viewport.playheadUs
            mainTrack?.clips?.firstOrNull { time >= it.startUs && time < it.endUs }
                ?: mainTrack?.clips?.lastOrNull()?.takeIf { time >= it.endUs }
        }
    }
    when (state.previewTrackKind) {
        TrackKind.VIDEO -> {
            surface(Modifier.fillMaxSize())
            val clip = clipUnderPlayhead
            if (clip != null && clip.mediaKind == MediaKind.IMAGE) {
                MediaThumbnail(
                    uri = clip.uri,
                    kind = ThumbnailKind.IMAGE,
                    contentDescription = clip.title,
                    unavailableDescription = stringResource(R.string.editor_media_unavailable),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }
        }
        TrackKind.AUDIO -> Icon(
            VeyroIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        null -> Text(
            stringResource(R.string.editor_nothing_to_preview),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    when (playback.status) {
        PreviewStatus.LOADING -> CircularProgressIndicator()
        PreviewStatus.ERROR -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                .padding(Spacing.md),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.editor_preview_error), style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

/** Play/pause plus the playhead time over the timeline duration. */
@Composable
private fun TransportRow(state: EditorUiState.Ready, playback: PreviewState, viewport: TimelineViewportState, onPlayPause: () -> Unit) {
    val playLabel = stringResource(
        when {
            playback.status == PreviewStatus.ENDED -> R.string.editor_replay
            playback.isPlaying -> R.string.editor_pause
            else -> R.string.editor_play
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Layout.screenGutter, vertical = Spacing.xs),
    ) {
        Text(
            "${DurationFormat.clock(viewport.playheadUs)} / ${DurationFormat.clock(state.durationUs)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(
            onClick = onPlayPause,
            enabled = state.previewTrackKind != null &&
                (playback.status == PreviewStatus.READY || playback.status == PreviewStatus.ENDED),
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = playLabel },
        ) {
            Icon(
                when {
                    playback.status == PreviewStatus.ENDED -> VeyroIcons.Replay
                    playback.isPlaying -> VeyroIcons.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = null,
            )
        }
        Box(Modifier.weight(1f))
    }
}

/** Details of the selected clip, and a plain note on which editing tools are still to come. */
@Composable
private fun SelectionBar(
    state: EditorUiState.Ready,
    compact: Boolean,
    onClearSelection: () -> Unit,
    onJumpTo: (Long) -> Unit,
) {
    val clip = state.selectedClip
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Layout.screenGutter, vertical = Spacing.sm)) {
            if (clip == null) {
                Text(
                    stringResource(R.string.editor_select_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.editor_clip_timing, DurationFormat.clock(clip.startUs), DurationFormat.clock(clip.durationUs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onJumpTo(clip.startUs) }) { Text(stringResource(R.string.editor_jump_to_clip)) }
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_clear_selection))
                    }
                }
            }
            if (!compact) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .heightIn(min = 24.dp),
                ) {
                    Text(
                        stringResource(R.string.editor_tools_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AvailabilityBadge(stringResource(R.string.editor_coming_soon))
                }
            }
        }
    }
}
