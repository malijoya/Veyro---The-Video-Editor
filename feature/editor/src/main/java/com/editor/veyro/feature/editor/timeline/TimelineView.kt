package com.editor.veyro.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.feature.editor.CutUi
import com.editor.veyro.feature.editor.DragHandle
import com.editor.veyro.feature.editor.R
import com.editor.veyro.feature.editor.TimelineClipUi
import com.editor.veyro.feature.editor.TimelineTrackUi
import com.editor.veyro.feature.editor.transition.transitionName
import kotlin.math.floor
import kotlin.math.roundToInt

/** Heights of the timeline rows. Compact values are for short windows such as landscape phones. */
public data class TimelineMetrics(val rulerHeight: Dp, val videoTrackHeight: Dp, val audioTrackHeight: Dp, val labelWidth: Dp) {
    public fun trackHeight(kind: TrackKind): Dp = if (kind == TrackKind.VIDEO) videoTrackHeight else audioTrackHeight

    public companion object {
        public val Regular: TimelineMetrics = TimelineMetrics(28.dp, 64.dp, 44.dp, 60.dp)
        public val Compact: TimelineMetrics = TimelineMetrics(24.dp, 48.dp, 34.dp, 48.dp)
    }
}

/**
 * The editor timeline: zoom controls, a time ruler, labelled tracks of clips
 * with filmstrips, and a fixed centre playhead.
 *
 * Editing gestures report pixel distances converted to microseconds with the
 * viewport's single [TimelineScale]. The editing itself happens in the
 * ViewModel and domain layer:
 * - a selected clip shows trim handles on both edges
 * - a long press on any clip picks it up to move
 * - [snapGuideUs] draws the edge a drag snapped to
 * - every cut on the main track has a marker ([cuts]): a tap opens its
 *   transition, and the stretch a transition plays over is shaded
 *
 * Recomposition happens only when [tracks] or [selectedClipId] change.
 * Scrolling, zooming and playback only re-lay-out and redraw: clip
 * positions are read from [viewport] in the layout phase, and ruler and
 * filmstrip content in the draw phase.
 */
@Composable
public fun TimelineView(
    tracks: List<TimelineTrackUi>,
    selectedClipId: ClipId?,
    viewport: TimelineViewportState,
    metrics: TimelineMetrics,
    onSelectClip: (ClipId?) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    modifier: Modifier = Modifier,
    snapGuideUs: Long? = null,
    onDragStart: (ClipId, DragHandle) -> Unit = { _, _ -> },
    onDragUpdate: (deltaUs: Long) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    /** Tapping a track's label mutes or unmutes the whole track. */
    onToggleTrackMute: (TimelineTrackUi) -> Unit = {},
    /** The main track's cuts, in order. */
    cuts: List<CutUi> = emptyList(),
    selectedCutId: ClipId? = null,
    onSelectCut: (ClipId) -> Unit = {},
) {
    val thumbnails = rememberStripThumbnails()
    val dragStart by rememberUpdatedState(onDragStart)
    val dragUpdate by rememberUpdatedState(onDragUpdate)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    // Remembered once and forwarding to the latest lambdas, so clips get the same instance and skip recomposition.
    val clipGestures = remember(viewport) {
        ClipGestureCallbacks(
            onStart = { id, handle -> dragStart(id, handle) },
            // The single px-to-time conversion, at the current zoom.
            onUpdate = { totalDxPx -> dragUpdate(viewport.scale.pxToUs(totalDxPx)) },
            onEnd = { dragEnd() },
            onCancel = { dragCancel() },
        )
    }
    val density = LocalDensity.current
    val currentTracks by rememberUpdatedState(tracks)
    val selectClip by rememberUpdatedState(onSelectClip)
    val startInteraction by rememberUpdatedState(onInteractionStart)
    val endInteraction by rememberUpdatedState(onInteractionEnd)
    val selectFromClip: (ClipId) -> Unit = remember { { id -> selectClip(id) } }
    val selectCut by rememberUpdatedState(onSelectCut)
    val selectFromMarker: (ClipId) -> Unit = remember { { id -> selectCut(id) } }

    Column(modifier) {
        ZoomBar(viewport)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(metrics.labelWidth)) {
                Box(Modifier.height(metrics.rulerHeight))
                tracks.forEach { track -> TrackLabel(track, metrics.trackHeight(track.kind), metrics.labelWidth, onToggleTrackMute) }
            }
            val backLabel = stringResource(R.string.editor_a11y_back_second)
            val forwardLabel = stringResource(R.string.editor_a11y_forward_second)
            val zoomInLabel = stringResource(R.string.editor_zoom_in)
            val zoomOutLabel = stringResource(R.string.editor_zoom_out)
            val timelineDescription = stringResource(R.string.editor_a11y_timeline)
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .onSizeChanged { viewport.widthPx = it.width.toFloat() }
                    .timelineGestures(
                        state = viewport,
                        onTap = { offset ->
                            selectClip(hitTest(offset, currentTracks, viewport, metrics, density))
                        },
                        onInteractionStart = { startInteraction() },
                        onInteractionEnd = { endInteraction() },
                    )
                    .semantics {
                        contentDescription = timelineDescription
                        customActions = listOf(
                            CustomAccessibilityAction(backLabel) { viewport.scrollToTime(viewport.playheadUs - 1_000_000); endInteraction(); true },
                            CustomAccessibilityAction(forwardLabel) { viewport.scrollToTime(viewport.playheadUs + 1_000_000); endInteraction(); true },
                            CustomAccessibilityAction(zoomInLabel) { viewport.zoomBy(TimelineScale.BUTTON_ZOOM_FACTOR); true },
                            CustomAccessibilityAction(zoomOutLabel) { viewport.zoomBy(1 / TimelineScale.BUTTON_ZOOM_FACTOR); true },
                        )
                    },
            ) {
                Column {
                    TimelineRuler(viewport, Modifier.fillMaxWidth().height(metrics.rulerHeight))
                    tracks.forEach { track ->
                        key(track.id.value) {
                            Box(Modifier.fillMaxWidth().height(metrics.trackHeight(track.kind))) {
                                TrackLane(
                                    track = track,
                                    selectedClipId = selectedClipId,
                                    viewport = viewport,
                                    thumbnails = thumbnails,
                                    gestures = clipGestures,
                                    onSelect = selectFromClip,
                                    modifier = Modifier.matchParentSize(),
                                )
                                if (track.magnetic && cuts.isNotEmpty()) {
                                    CutMarkers(cuts, selectedCutId, viewport, selectFromMarker, Modifier.matchParentSize())
                                }
                            }
                        }
                    }
                }
                if (snapGuideUs != null) SnapGuide(viewport, snapGuideUs, Modifier.matchParentSize())
                Playhead(Modifier.matchParentSize())
            }
        }
    }
}

/** Finds the clip under a tap, or null for empty space or the ruler (which clears the selection). */
private fun hitTest(
    offset: Offset,
    tracks: List<TimelineTrackUi>,
    viewport: TimelineViewportState,
    metrics: TimelineMetrics,
    density: androidx.compose.ui.unit.Density,
): ClipId? {
    var top = with(density) { metrics.rulerHeight.toPx() }
    if (offset.y < top) return null
    val timeUs = viewport.timeForX(offset.x)
    for (track in tracks) {
        val bottom = top + with(density) { metrics.trackHeight(track.kind).toPx() }
        if (offset.y < bottom) return track.clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }?.id
        top = bottom
    }
    return null
}

@Composable
private fun ZoomBar(viewport: TimelineViewportState) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = { viewport.zoomBy(1 / TimelineScale.BUTTON_ZOOM_FACTOR) }) {
            Icon(VeyroIcons.ZoomOut, contentDescription = stringResource(R.string.editor_zoom_out))
        }
        IconButton(onClick = { viewport.zoomBy(TimelineScale.BUTTON_ZOOM_FACTOR) }) {
            Icon(VeyroIcons.ZoomIn, contentDescription = stringResource(R.string.editor_zoom_in))
        }
        IconButton(onClick = { viewport.zoomToFit() }) {
            Icon(VeyroIcons.FitScreen, contentDescription = stringResource(R.string.editor_zoom_fit))
        }
    }
}

/**
 * A track's name and sound switch. Tapping it mutes or unmutes the track;
 * the mute applies in preview and export. On the main track it mutes the
 * videos' own sound. Overlay lanes have no sound, so their label is just a
 * name.
 */
@Composable
private fun TrackLabel(track: TimelineTrackUi, height: Dp, width: Dp, onToggleMute: (TimelineTrackUi) -> Unit) {
    if (track.kind == TrackKind.OVERLAY) {
        OverlayTrackLabel(height, width)
        return
    }
    val name = stringResource(
        when {
            track.kind == TrackKind.VIDEO -> R.string.editor_track_video
            track.role == AudioRole.VOICEOVER -> R.string.editor_track_voiceover
            track.role == AudioRole.MUSIC -> R.string.editor_track_music
            else -> R.string.editor_track_audio
        },
    )
    val muted = track.audio.muted
    val stateText = stringResource(if (muted) R.string.editor_track_muted else R.string.editor_track_sound_on)
    val toggleLabel = stringResource(if (muted) R.string.editor_unmute_track else R.string.editor_mute_track)
    val tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(width, height)
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
            .clickable(onClickLabel = toggleLabel) { onToggleMute(track) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = stateText
            },
    ) {
        Icon(
            when {
                muted -> VeyroIcons.VolumeOff
                track.kind == TrackKind.VIDEO -> VeyroIcons.Movie
                track.role == AudioRole.VOICEOVER -> VeyroIcons.Mic
                else -> VeyroIcons.MusicNote
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverlayTrackLabel(height: Dp, width: Dp) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(width, height)
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(VeyroIcons.Layers, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.editor_track_overlay), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Time ruler. Drawn entirely in the draw phase from [viewport], with labels spaced at least 72 dp apart. */
@Composable
private fun TimelineRuler(viewport: TimelineViewportState, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tickColor = MaterialTheme.colorScheme.outline
    val minMajorSpacingPx = with(LocalDensity.current) { 72.dp.toPx() }
    Canvas(modifier) {
        val ticks = RulerTicks.choose(viewport.scale, minMajorSpacingPx)
        val firstVisibleUs = viewport.timeForX(0f).coerceAtLeast(0)
        val lastVisibleUs = minOf(viewport.timeForX(size.width), viewport.durationUs)
        if (lastVisibleUs < firstVisibleUs) return@Canvas
        var timeUs = (firstVisibleUs / ticks.minorUs) * ticks.minorUs
        while (timeUs <= lastVisibleUs) {
            val x = viewport.xForTime(timeUs)
            val major = timeUs % ticks.majorUs == 0L
            val tickHeight = if (major) size.height * 0.45f else size.height * 0.2f
            drawLine(tickColor, Offset(x, size.height - tickHeight), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            if (major) {
                drawText(textMeasurer, RulerTicks.label(timeUs), topLeft = Offset(x + 3.dp.toPx(), 1.dp.toPx()), style = labelStyle)
            }
            timeUs += ticks.minorUs
        }
    }
}

/**
 * One track's clips. A custom layout reads [viewport] while measuring and
 * placing:
 * - each clip is sized to its on-screen span and placed at its position
 * - off-screen clips are skipped
 * - clip composables don't recompose on scroll or zoom
 */
@Composable
private fun TrackLane(
    track: TimelineTrackUi,
    selectedClipId: ClipId?,
    viewport: TimelineViewportState,
    thumbnails: StripThumbnails,
    gestures: ClipGestureCallbacks,
    onSelect: (ClipId) -> Unit,
    modifier: Modifier,
) {
    val laneColor = MaterialTheme.colorScheme.surfaceContainerLow
    Layout(
        modifier = modifier.drawBehind {
            // Shade the part of the lane that lies within the timeline's duration.
            val span = viewport.visibleSpan(0, viewport.durationUs) ?: return@drawBehind
            drawRect(laneColor, topLeft = Offset(span.start, 0f), size = androidx.compose.ui.geometry.Size(span.endInclusive - span.start, size.height))
        },
        content = {
            track.clips.forEach { clip ->
                key(clip.id.value) {
                    TimelineClip(
                        clip = clip,
                        trackKind = track.kind,
                        trackAudio = track.audio,
                        selected = clip.id == selectedClipId,
                        viewport = viewport,
                        thumbnails = thumbnails,
                        gestures = gestures,
                        onSelect = onSelect,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val spans = track.clips.map { viewport.visibleSpan(it.startUs, it.endUs) }
        val placeables = measurables.mapIndexed { index, measurable ->
            spans.getOrNull(index)?.let { span ->
                val width = (span.endInclusive - span.start).roundToInt().coerceAtLeast(1)
                measurable.measure(Constraints.fixed(width, height)) to span.start.roundToInt()
            }
        }
        layout(constraints.maxWidth, height) {
            placeables.forEach { entry -> entry?.let { (placeable, x) -> placeable.place(x, 0) } }
        }
    }
}

@Composable
private fun TimelineClip(
    clip: TimelineClipUi,
    trackKind: TrackKind,
    trackAudio: TrackAudio,
    selected: Boolean,
    viewport: TimelineViewportState,
    thumbnails: StripThumbnails,
    gestures: ClipGestureCallbacks,
    onSelect: (ClipId) -> Unit,
) {
    val currentGestures by rememberUpdatedState(gestures)
    val shape = RoundedCornerShape(6.dp)
    val isText = clip.overlay?.content is OverlayContent.Text
    val container = when (trackKind) {
        TrackKind.VIDEO -> MaterialTheme.colorScheme.surfaceContainerHighest
        TrackKind.AUDIO -> MaterialTheme.colorScheme.secondaryContainer
        TrackKind.OVERLAY -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val labelColor = when (trackKind) {
        TrackKind.VIDEO -> Color.White
        TrackKind.AUDIO -> MaterialTheme.colorScheme.onSecondaryContainer
        TrackKind.OVERLAY -> if (isText) MaterialTheme.colorScheme.onTertiaryContainer else Color.White
    }
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    val silent = clip.audio.isSilent || trackAudio.isSilent
    val waveColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (silent) 0.25f else 0.7f)
    val gainLineColor = MaterialTheme.colorScheme.primary
    val videoWaveColor = Color.White.copy(alpha = if (silent) 0.25f else 0.8f)
    val keyframeAtPlayhead = MaterialTheme.colorScheme.primary
    val gainKeyframeColor = MaterialTheme.colorScheme.tertiary
    val kindLabel = stringResource(
        when {
            isText -> R.string.editor_clip_text
            clip.overlay != null -> R.string.editor_clip_sticker
            clip.mediaKind == MediaKind.VIDEO -> R.string.editor_clip_video
            clip.mediaKind == MediaKind.IMAGE -> R.string.editor_clip_photo
            else -> R.string.editor_clip_audio
        },
    )
    val title = if (isText) clip.title.ifBlank { stringResource(R.string.overlay_empty_text) } else clip.title
    val description = stringResource(
        R.string.editor_a11y_clip,
        kindLabel,
        title,
        DurationFormat.clock(clip.startUs),
        DurationFormat.clock(clip.durationUs),
    )
    val selectLabel = stringResource(R.string.editor_a11y_select_clip)
    Box(
        Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = 1.dp)
            .clip(shape)
            .background(container)
            .drawBehind {
                // Text has no picture of its own; stickers show their image.
                if (clip.mediaKind != MediaKind.AUDIO && !isText) drawFilmstrip(clip, viewport, thumbnails, placeholder)
                when {
                    // Audio clips: the waveform fills the clip, under the title.
                    clip.mediaKind == MediaKind.AUDIO ->
                        drawWaveform(clip, trackAudio, viewport, waveColor, gainLineColor, bandTop = size.height * 0.3f, bandHeight = size.height * 0.65f)
                    // Videos with sound: a waveform band along the bottom of the filmstrip, in sync with the frames above it.
                    clip.hasAudio && clip.mediaKind == MediaKind.VIDEO -> {
                        val band = size.height * 0.32f
                        drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, size.height - band), size = androidx.compose.ui.geometry.Size(size.width, band))
                        drawWaveform(clip, trackAudio, viewport, videoWaveColor, gainLineColor, bandTop = size.height - band, bandHeight = band)
                    }
                }
            }
            // Keyframe markers, over everything else in the clip.
            .then(
                if (clip.keyframeTimesUs.isEmpty() && clip.gainKeyframeTimesUs.isEmpty()) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        // Placement keyframes along the bottom edge; volume keyframes above them, smaller, in another color.
                        drawKeyframes(clip, clip.keyframeTimesUs, viewport, Color.White, keyframeAtPlayhead, size.height - 8.dp.toPx(), 5.dp.toPx())
                        drawKeyframes(clip, clip.gainKeyframeTimesUs, viewport, gainKeyframeColor, keyframeAtPlayhead, size.height - 20.dp.toPx(), 4.dp.toPx())
                    }
                },
            )
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                onClick(label = selectLabel) { onSelect(clip.id); true }
            }
            // Long press picks the clip up. Dragging then moves it (a reorder on the main track).
            .pointerInput(clip.id) {
                var totalDx = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        totalDx = 0f
                        currentGestures.onStart(clip.id, DragHandle.BODY)
                    },
                    onDragEnd = { currentGestures.onEnd() },
                    onDragCancel = { currentGestures.onCancel() },
                    onDrag = { change, amount ->
                        change.consume()
                        totalDx += amount.x
                        currentGestures.onUpdate(totalDx)
                    },
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .padding(Spacing.xs)
                .background(Color.Black.copy(alpha = if (trackKind == TrackKind.VIDEO || (trackKind == TrackKind.OVERLAY && !isText)) 0.45f else 0f), RoundedCornerShape(4.dp))
                .padding(horizontal = Spacing.xs),
        ) {
            if (trackKind == TrackKind.AUDIO) {
                Icon(VeyroIcons.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (isText) {
                Icon(VeyroIcons.TextFields, contentDescription = null, modifier = Modifier.size(14.dp), tint = labelColor)
            }
            if (clip.hasAudio && clip.audio.muted) {
                Icon(VeyroIcons.VolumeOff, contentDescription = stringResource(R.string.editor_clip_muted), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            }
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            TrimHandle(clip, DragHandle.START, viewport, currentGestures)
            TrimHandle(clip, DragHandle.END, viewport, currentGestures)
        }
    }
}

/** Gesture callbacks shared by all clips. Distances are in pixels. The editor converts them to time. */
@androidx.compose.runtime.Stable
internal class ClipGestureCallbacks(
    val onStart: (ClipId, DragHandle) -> Unit,
    val onUpdate: (totalDxPx: Float) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

private val TRIM_HANDLE_WIDTH = 20.dp

/**
 * A grip on one edge of the selected clip. It sits at the clip's true edge,
 * read from [viewport] in the placement phase, even when the clip is only
 * partly on screen.
 */
@Composable
private fun TrimHandle(
    clip: TimelineClipUi,
    handle: DragHandle,
    viewport: TimelineViewportState,
    gestures: ClipGestureCallbacks,
) {
    val color = MaterialTheme.colorScheme.primary
    val grip = MaterialTheme.colorScheme.onPrimary
    val label = stringResource(if (handle == DragHandle.START) R.string.editor_trim_start else R.string.editor_trim_end)
    Box(
        Modifier
            .offset {
                val widthPx = TRIM_HANDLE_WIDTH.roundToPx()
                val spanStart = viewport.visibleSpan(clip.startUs, clip.endUs)?.start ?: 0f
                val edge = if (handle == DragHandle.START) clip.startUs else clip.endUs
                val x = viewport.xForTime(edge) - spanStart - if (handle == DragHandle.END) widthPx.toFloat() else 0f
                IntOffset(x.roundToInt(), 0)
            }
            .width(TRIM_HANDLE_WIDTH)
            .fillMaxHeight()
            .background(color, RoundedCornerShape(4.dp))
            .drawBehind {
                val x = size.width / 2
                drawLine(grip, Offset(x, size.height * 0.3f), Offset(x, size.height * 0.7f), strokeWidth = 2.dp.toPx())
            }
            .semantics { contentDescription = label }
            .pointerInput(clip.id, handle) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        gestures.onStart(clip.id, handle)
                    },
                    onDragEnd = { gestures.onEnd() },
                    onDragCancel = { gestures.onCancel() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        totalDx += dx
                        gestures.onUpdate(totalDx)
                    },
                )
            },
    )
}

private val CUT_MARKER_SIZE = 26.dp

/**
 * A marker on every cut of the main track, centred on the cut, and a
 * shaded band over the stretch each transition plays (as fitted to the
 * clips, so a shortened transition shows shorter). Positions follow the
 * viewport in the layout and draw phases, like the clips.
 */
@Composable
private fun CutMarkers(
    cuts: List<CutUi>,
    selectedCutId: ClipId?,
    viewport: TimelineViewportState,
    onSelect: (ClipId) -> Unit,
    modifier: Modifier,
) {
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val bandEdge = MaterialTheme.colorScheme.primary
    Layout(
        modifier = modifier.drawBehind {
            cuts.forEach { cut ->
                if (cut.endUs <= cut.startUs) return@forEach
                val span = viewport.visibleSpan(cut.startUs, cut.endUs) ?: return@forEach
                drawRect(band, topLeft = Offset(span.start, 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(span.endInclusive - span.start, size.height - 4.dp.toPx()))
                drawLine(bandEdge, Offset(span.start, size.height - 3.dp.toPx()), Offset(span.endInclusive, size.height - 3.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        },
        content = {
            cuts.forEach { cut ->
                key(cut.outgoingClipId.value) {
                    CutMarker(cut, selected = cut.outgoingClipId == selectedCutId, onClick = { onSelect(cut.outgoingClipId) })
                }
            }
        },
    ) { measurables, constraints ->
        val sizePx = CUT_MARKER_SIZE.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(sizePx, sizePx)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val x = viewport.xForTime(cuts[index].cutUs)
                if (x < -sizePx || x > constraints.maxWidth + sizePx) return@forEachIndexed
                placeable.place((x - sizePx / 2f).roundToInt(), (constraints.maxHeight - sizePx) / 2)
            }
        }
    }
}

@Composable
private fun CutMarker(cut: CutUi, selected: Boolean, onClick: () -> Unit) {
    val transition = cut.transition
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        transition != null -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        transition != null -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val description = if (transition == null) {
        stringResource(R.string.transition_a11y_add, DurationFormat.clock(cut.cutUs))
    } else {
        stringResource(R.string.transition_a11y_marker, transitionName(transition.type), DurationFormat.clock(cut.cutUs))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CUT_MARKER_SIZE)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            },
    ) {
        Icon(
            if (transition == null) Icons.Filled.Add else VeyroIcons.Transition,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A vertical line at the time a drag snapped to. */
@Composable
private fun SnapGuide(viewport: TimelineViewportState, timeUs: Long, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val x = viewport.xForTime(timeUs)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }
}

/**
 * A diamond of half-size [half] at each clip-local time in [timesUs], centred
 * at height [y]. The one under the playhead is drawn in [atPlayhead].
 * Positions follow the viewport in the draw phase, like the filmstrip.
 */
private fun DrawScope.drawKeyframes(
    clip: TimelineClipUi,
    timesUs: List<Long>,
    viewport: TimelineViewportState,
    color: Color,
    atPlayhead: Color,
    y: Float,
    half: Float,
) {
    if (timesUs.isEmpty()) return
    val span = viewport.visibleSpan(clip.startUs, clip.endUs) ?: return
    val playheadX = viewport.xForTime(viewport.playheadUs) - span.start
    timesUs.forEach { localUs ->
        val x = viewport.xForTime(clip.startUs + localUs) - span.start
        if (x < -half || x > size.width + half) return@forEach
        val diamond = Path().apply {
            moveTo(x, y - half)
            lineTo(x + half, y)
            lineTo(x, y + half)
            lineTo(x - half, y)
            close()
        }
        val selected = kotlin.math.abs(x - playheadX) < 1.5.dp.toPx()
        drawPath(diamond, if (selected) atPlayhead else color)
        drawPath(diamond, Color.Black.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
    }
}

/** Square frame tiles across the clip. Only tiles within the clip's visible span are drawn or requested. */
private fun DrawScope.drawFilmstrip(
    clip: TimelineClipUi,
    viewport: TimelineViewportState,
    thumbnails: StripThumbnails,
    placeholder: Color,
) {
    val span = viewport.visibleSpan(clip.startUs, clip.endUs) ?: return
    // Where the clip's true start sits in this composable's coordinates (zero or negative).
    val clipLeft = viewport.xForTime(clip.startUs) - span.start
    val tileWidth = size.height.coerceAtLeast(1f)
    val tileDurationUs = viewport.scale.pxToUs(tileWidth)
    val decodeSize = ((tileWidth.roundToInt() + 63) / 64 * 64).coerceIn(64, 256)
    val kind = if (clip.mediaKind == MediaKind.VIDEO) ThumbnailKind.VIDEO else ThumbnailKind.IMAGE
    val first = floor(-clipLeft / tileWidth).toInt().coerceAtLeast(0)
    val last = floor((size.width - clipLeft) / tileWidth).toInt()
    for (index in first..last) {
        val x = clipLeft + index * tileWidth
        val offsetInClipUs = viewport.scale.pxToUs(index * tileWidth + tileWidth / 2)
        if (index > 0 && offsetInClipUs >= clip.durationUs) break
        val frameUs = if (kind == ThumbnailKind.VIDEO) {
            StripThumbnails.quantizeTimeUs(clip.sourceInUs + offsetInClipUs.coerceIn(0, clip.durationUs - 1), tileDurationUs)
        } else {
            0L
        }
        val bitmap = thumbnails.get(TileKey(clip.uri, kind, frameUs, decodeSize))
        if (bitmap == null) {
            drawRect(placeholder, topLeft = Offset(x + 1f, 1f), size = androidx.compose.ui.geometry.Size(tileWidth - 2f, size.height - 2f))
        } else {
            drawCenterCrop(bitmap, x, tileWidth)
        }
    }
}

private fun DrawScope.drawCenterCrop(bitmap: ImageBitmap, x: Float, tileWidth: Float) {
    val targetAspect = tileWidth / size.height
    val sourceAspect = bitmap.width.toFloat() / bitmap.height
    val (srcWidth, srcHeight) = if (sourceAspect > targetAspect) {
        (bitmap.height * targetAspect).roundToInt() to bitmap.height
    } else {
        bitmap.width to (bitmap.width / targetAspect).roundToInt()
    }
    drawImage(
        image = bitmap,
        srcOffset = IntOffset((bitmap.width - srcWidth) / 2, (bitmap.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1)),
        dstOffset = IntOffset(x.roundToInt(), 0),
        dstSize = IntSize(tileWidth.roundToInt(), size.height.roundToInt()),
    )
}

/** The fixed playhead: a line through the ruler and tracks, with a handle at the top. */
@Composable
private fun Playhead(modifier: Modifier) {
    val color = Color.White
    Canvas(modifier) {
        val x = size.width / 2f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        val handle = 6.dp.toPx()
        val path = Path().apply {
            moveTo(x - handle, 0f)
            lineTo(x + handle, 0f)
            lineTo(x, handle * 1.4f)
            close()
        }
        drawPath(path, color)
    }
}
