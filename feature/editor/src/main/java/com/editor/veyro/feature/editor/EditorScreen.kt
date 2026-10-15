package com.editor.veyro.feature.editor

import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.ClipAudio
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.TrackId
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
public fun EditorRoute(viewModel: EditorViewModel, onBack: () -> Unit, onExport: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    val player by viewModel.controller.player.collectAsStateWithLifecycle()
    val viewport = rememberTimelineViewportState()
    BindPreviewLifecycle(viewModel.controller)
    // Write pending edits as soon as the editor leaves the screen (home button, app switch, incoming call).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    PlayheadSync(viewModel.controller, viewport, (uiState as? EditorUiState.Ready)?.durationUs ?: 0)

    val density = LocalDensity.current
    EditorScreen(
        uiState = uiState,
        playback = playback,
        viewport = viewport,
        surface = { modifier -> PreviewSurface(player, modifier) },
        onBack = onBack,
        onExport = {
            // Write pending edits first, so the export reads the timeline the user sees.
            viewModel.controller.pause()
            viewModel.flush()
            onExport()
        },
        onPlayPause = viewModel.controller::togglePlayPause,
        onSelectClip = viewModel::selectClip,
        onScrubStart = viewModel.controller::pause,
        onScrubEnd = { viewModel.controller.seekTo(viewport.playheadUs) },
        onJumpTo = { timeUs ->
            viewport.scrollToTime(timeUs)
            viewModel.controller.seekTo(timeUs)
        },
        editActions = EditActions(
            onDragStart = { id, handle ->
                viewModel.controller.pause()
                viewModel.beginDrag(id, handle)
            },
            onDragUpdate = { deltaUs ->
                // Snap distance is fixed on screen, so it covers less time when zoomed in.
                viewModel.updateDrag(deltaUs, viewport.scale.pxPerSecond, density.density, viewport.playheadUs)
            },
            onDragEnd = viewModel::endDrag,
            onDragCancel = viewModel::cancelDrag,
            onSplit = { viewModel.split(viewport.playheadUs) },
            onDelete = { viewModel.deleteSelected(closeGap = false) },
            onDeleteCloseGap = { viewModel.deleteSelected(closeGap = true) },
            onCloseGaps = viewModel::closeGaps,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onMessageShown = viewModel::messageShown,
            onSetClipAudio = viewModel::setClipAudio,
            onSetTrackAudio = viewModel::setTrackAudio,
            onMoveToTrack = viewModel::moveClipToTrack,
        ),
    )
}

/** Editing intents from the screen. Each maps to one ViewModel call. No editing logic lives in the UI. */
internal class EditActions(
    val onDragStart: (ClipId, DragHandle) -> Unit,
    val onDragUpdate: (Long) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
    val onSplit: () -> Unit,
    val onDelete: () -> Unit,
    val onDeleteCloseGap: () -> Unit,
    val onCloseGaps: (TrackId) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onMessageShown: () -> Unit,
    val onSetClipAudio: (ClipId, ClipAudio) -> Unit = { _, _ -> },
    val onSetTrackAudio: (TrackId, TrackAudio) -> Unit = { _, _ -> },
    val onMoveToTrack: (ClipId, TrackId) -> Unit = { _, _ -> },
)

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
    onExport: () -> Unit = {},
    onPlayPause: () -> Unit,
    onSelectClip: (com.editor.veyro.core.model.timeline.ClipId?) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onJumpTo: (Long) -> Unit,
    editActions: EditActions,
) {
    val ready = uiState as? EditorUiState.Ready
    val snackbarHostState = remember { SnackbarHostState() }
    EditorMessageEffect(ready?.message, snackbarHostState, editActions.onMessageShown)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready?.projectName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ready?.let { SaveStatusLabel(it.saveStatus) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
                actions = {
                    if (ready != null && ready.tracks.any { it.magnetic && it.clips.isNotEmpty() }) {
                        androidx.compose.material3.Button(onClick = onExport, enabled = !ready.isDragging) {
                            Text(stringResource(R.string.editor_export))
                        }
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
                    editActions = editActions,
                )
            }
        }
    }
}

@Composable
private fun EditorMessageEffect(message: EditorMessage?, snackbarHostState: SnackbarHostState, onShown: () -> Unit) {
    val text = when (message) {
        EditorMessage.NOTHING_TO_SPLIT -> stringResource(R.string.editor_msg_nothing_to_split)
        EditorMessage.SPLIT_TOO_CLOSE -> stringResource(R.string.editor_msg_split_too_close)
        EditorMessage.NO_GAPS -> stringResource(R.string.editor_msg_no_gaps)
        EditorMessage.HISTORY_OUT_OF_DATE -> stringResource(R.string.editor_msg_history_out_of_date)
        EditorMessage.CLIP_HAS_NO_AUDIO -> stringResource(R.string.editor_no_audio_stream)
        null -> null
    }
    LaunchedEffect(message) {
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onShown()
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
    editActions: EditActions,
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
            // The project's canvas, letterboxed in the available space. Media is fitted inside it.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .aspectRatio(state.canvasAspectRatio, matchHeightConstraintsFirst = state.canvasAspectRatio < 1f)
                    .background(Color.Black),
            ) {
                PreviewStage(state, playback, surface)
            }
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
                snapGuideUs = state.snapGuideUs,
                onDragStart = editActions.onDragStart,
                onDragUpdate = editActions.onDragUpdate,
                onDragEnd = editActions.onDragEnd,
                onDragCancel = editActions.onDragCancel,
                onToggleTrackMute = { track -> editActions.onSetTrackAudio(track.id, track.audio.copy(muted = !track.audio.muted)) },
            )
        }
        EditToolbar(state, editActions)
        SelectionBar(state, compact, onClearSelection = { onSelectClip(null) }, onJumpTo = onJumpTo, actions = editActions)
    }
}

/** "Saved", "Saving…" or a failure, so users know their edits are safe. Announced politely as it changes. */
@Composable
private fun SaveStatusLabel(status: SaveStatus) {
    val (text, color) = when (status) {
        SaveStatus.SAVED -> stringResource(R.string.editor_saved) to MaterialTheme.colorScheme.onSurfaceVariant
        SaveStatus.PENDING, SaveStatus.SAVING -> stringResource(R.string.editor_saving) to MaterialTheme.colorScheme.onSurfaceVariant
        SaveStatus.FAILED -> stringResource(R.string.editor_save_failed) to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The preview: the composition player's output. Photos, videos and the
 * mixed audio all come from the player, exactly as export renders them.
 */
@Composable
private fun PreviewStage(
    state: EditorUiState.Ready,
    playback: PreviewState,
    surface: @Composable (Modifier) -> Unit,
) {
    when (state.previewTrackKind) {
        TrackKind.VIDEO -> surface(Modifier.fillMaxSize())
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

/**
 * Editing buttons. Each is enabled only when its edit can apply:
 * - Delete needs a selection.
 * - "Delete and close gap" appears only on tracks that keep gaps, because the
 *   main track always closes them.
 * - Close gaps appears when the selected clip's track has gaps.
 */
@Composable
private fun EditToolbar(state: EditorUiState.Ready, actions: EditActions) {
    val track = state.selectedTrack
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm),
    ) {
        IconButton(onClick = actions.onUndo, enabled = state.canUndo && !state.isDragging) {
            Icon(VeyroIcons.Undo, contentDescription = stringResource(R.string.editor_undo))
        }
        IconButton(onClick = actions.onRedo, enabled = state.canRedo && !state.isDragging) {
            Icon(VeyroIcons.Redo, contentDescription = stringResource(R.string.editor_redo))
        }
        IconButton(onClick = actions.onSplit, enabled = state.tracks.any { it.clips.isNotEmpty() } && !state.isDragging) {
            Icon(VeyroIcons.ContentCut, contentDescription = stringResource(R.string.editor_split))
        }
        IconButton(onClick = actions.onDelete, enabled = state.selectedClip != null && !state.isDragging) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_delete))
        }
        if (track != null && !track.magnetic) {
            TextButton(onClick = actions.onDeleteCloseGap, enabled = !state.isDragging) {
                Text(stringResource(R.string.editor_delete_close_gap))
            }
        }
        if (track != null && track.hasGaps) {
            TextButton(onClick = { actions.onCloseGaps(track.id) }, enabled = !state.isDragging) {
                Text(stringResource(R.string.editor_close_gaps))
            }
        }
    }
}

/** Details of the selected clip, and a plain note on which features are still to come. */
@Composable
private fun SelectionBar(
    state: EditorUiState.Ready,
    compact: Boolean,
    onClearSelection: () -> Unit,
    onJumpTo: (Long) -> Unit,
    actions: EditActions,
) {
    val clip = state.selectedClip
    val track = state.selectedTrack
    // The sound panel opens on demand, so the preview keeps its room on small screens.
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Layout.screenGutter, vertical = Spacing.sm)) {
            if (clip == null) {
                Text(
                    stringResource(R.string.editor_select_hint) + " " + stringResource(R.string.editor_edit_hint),
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
                    if (clip.mediaKind != MediaKind.IMAGE) {
                        TextButton(onClick = { soundOpen = !soundOpen }) {
                            Text(stringResource(if (soundOpen) R.string.editor_sound_hide else R.string.editor_sound))
                        }
                    }
                    TextButton(onClick = { onJumpTo(clip.startUs) }) { Text(stringResource(R.string.editor_jump_to_clip)) }
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_clear_selection))
                    }
                }
                if (soundOpen && track != null && clip.mediaKind != MediaKind.IMAGE) {
                    AudioControls(
                        clip = clip,
                        track = track,
                        tracks = state.tracks,
                        enabled = !state.isDragging,
                        onSetClipAudio = actions.onSetClipAudio,
                        onSetTrackAudio = actions.onSetTrackAudio,
                        onMoveToTrack = actions.onMoveToTrack,
                    )
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
                        stringResource(R.string.editor_export_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AvailabilityBadge(stringResource(R.string.editor_coming_soon))
                }
            }
        }
    }
}
