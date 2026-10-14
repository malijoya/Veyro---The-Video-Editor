package com.editor.veyro.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.feature.editor.R
import com.editor.veyro.feature.editor.TimelineClipUi
import com.editor.veyro.feature.editor.TimelineTrackUi
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
) {
    val thumbnails = rememberStripThumbnails()
    val density = LocalDensity.current
    val currentTracks by rememberUpdatedState(tracks)
    val selectClip by rememberUpdatedState(onSelectClip)
    val startInteraction by rememberUpdatedState(onInteractionStart)
    val endInteraction by rememberUpdatedState(onInteractionEnd)

    Column(modifier) {
        ZoomBar(viewport)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(metrics.labelWidth)) {
                Box(Modifier.height(metrics.rulerHeight))
                tracks.forEach { track -> TrackLabel(track.kind, metrics.trackHeight(track.kind), metrics.labelWidth) }
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
                            TrackLane(
                                track = track,
                                selectedClipId = selectedClipId,
                                viewport = viewport,
                                thumbnails = thumbnails,
                                onSelect = { selectClip(it) },
                                modifier = Modifier.fillMaxWidth().height(metrics.trackHeight(track.kind)),
                            )
                        }
                    }
                }
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

@Composable
private fun TrackLabel(kind: TrackKind, height: Dp, width: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(width, height)
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
    ) {
        Icon(
            if (kind == TrackKind.VIDEO) VeyroIcons.Movie else VeyroIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (kind == TrackKind.VIDEO) R.string.editor_track_video else R.string.editor_track_audio),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
                        selected = clip.id == selectedClipId,
                        viewport = viewport,
                        thumbnails = thumbnails,
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
    selected: Boolean,
    viewport: TimelineViewportState,
    thumbnails: StripThumbnails,
    onSelect: (ClipId) -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val container = if (trackKind == TrackKind.VIDEO) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    val kindLabel = stringResource(
        when (clip.mediaKind) {
            MediaKind.VIDEO -> R.string.editor_clip_video
            MediaKind.IMAGE -> R.string.editor_clip_photo
            MediaKind.AUDIO -> R.string.editor_clip_audio
        },
    )
    val description = stringResource(
        R.string.editor_a11y_clip,
        kindLabel,
        clip.title,
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
                if (clip.mediaKind != MediaKind.AUDIO) drawFilmstrip(clip, viewport, thumbnails, placeholder)
            }
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                onClick(label = selectLabel) { onSelect(clip.id); true }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .padding(Spacing.xs)
                .background(Color.Black.copy(alpha = if (trackKind == TrackKind.VIDEO) 0.45f else 0f), RoundedCornerShape(4.dp))
                .padding(horizontal = Spacing.xs),
        ) {
            if (trackKind == TrackKind.AUDIO) {
                Icon(VeyroIcons.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                clip.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (trackKind == TrackKind.VIDEO) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
