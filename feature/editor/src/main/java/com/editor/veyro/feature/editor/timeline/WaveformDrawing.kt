package com.editor.veyro.feature.editor.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.feature.editor.TimelineClipUi

/** Horizontal distance between waveform bars. */
private const val BAR_STEP_PX = 3f
private const val BAR_WIDTH_PX = 2f

/**
 * Draws a clip's measured waveform in the band [[bandTop], [bandTop] +
 * [bandHeight]), plus its gain line.
 *
 * - Bars are the file's real peaks ([TimelineClipUi.waveform]) over the
 *   clip's source range, scaled by the clip's gain (volume, fades, mute and
 *   track volume), so they show what will be heard. Before the mix
 *   headroom, which only applies where sounds overlap.
 * - The gain line runs along the band: at the top for 100%, at the bottom
 *   for silence. Fades show as ramps.
 * - Without a waveform (still analysing, or unreadable) only the gain line is
 *   drawn. No placeholder bars are ever drawn.
 *
 * Only the visible part of the clip is drawn, using the same coordinates as
 * the filmstrip.
 */
internal fun DrawScope.drawWaveform(
    clip: TimelineClipUi,
    track: TrackAudio,
    viewport: TimelineViewportState,
    barColor: Color,
    lineColor: Color,
    bandTop: Float,
    bandHeight: Float,
) {
    val waveform = clip.waveform
    val span = viewport.visibleSpan(clip.startUs, clip.endUs) ?: return
    val clipLeft = viewport.xForTime(clip.startUs) - span.start
    val clipRight = clipLeft + viewport.scale.usToPx(clip.durationUs)
    val envelope = GainEnvelope(clip.durationUs, clip.sourceInUs, clip.audio, track)
    val columnUs = viewport.scale.pxToUs(BAR_STEP_PX).coerceAtLeast(1)
    val center = bandTop + bandHeight / 2f
    val from = maxOf(0f, clipLeft)
    val to = minOf(size.width, clipRight)

    var x = from
    while (waveform != null && x < to) {
        val offsetUs = viewport.scale.pxToUs(x - clipLeft).coerceIn(0, clip.durationUs)
        val sourceUs = clip.sourceInUs + offsetUs
        val peak = waveform.peakBetween(sourceUs, sourceUs + columnUs)
        val half = (peak * envelope.gainAt(offsetUs) * bandHeight / 2f).coerceAtLeast(0.5f)
        drawLine(barColor, Offset(x, center - half), Offset(x, center + half), strokeWidth = BAR_WIDTH_PX)
        x += BAR_STEP_PX
    }

    val line = Path()
    x = from
    var first = true
    while (x <= to) {
        val offsetUs = viewport.scale.pxToUs(x - clipLeft).coerceIn(0, clip.durationUs)
        val y = bandTop + bandHeight * (1f - envelope.gainAt(offsetUs))
        if (first) line.moveTo(x, y) else line.lineTo(x, y)
        first = false
        x += BAR_STEP_PX
    }
    drawPath(line, lineColor, style = Stroke(width = 1.5f))
}
