package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where a dragged time lands.
 *
 * @property timeUs the snapped time. When moving a clip, this is its new start.
 * @property guideUs the edge or playhead it snapped to (for drawing a guide
 *   line), or null when it only snapped to the frame grid.
 */
public data class Snap(val timeUs: Long, val guideUs: Long?)

/**
 * Frame-aware snapping for drags and the playhead.
 *
 * A time within the threshold of a target (another clip's edge, the
 * playhead, or 0) jumps to it, so cuts line up exactly. Otherwise it is
 * rounded to the nearest frame boundary of the timeline's frame rate, so
 * every edit lands on a frame.
 *
 * The threshold is a distance on screen, converted to time at the current
 * zoom with [thresholdUs]. A drag that ends the same number of pixels from a
 * target therefore snaps the same way at every zoom level.
 */
public object Snapper {

    /**
     * The snap threshold in time for a [distancePx] on screen at a zoom of
     * [pxPerSecond].
     *
     * It is never less than half a frame. Below that, a target off the frame
     * grid could be skipped over by grid rounding when zoomed far in, so
     * snapping would behave differently at high zoom.
     */
    public fun thresholdUs(distancePx: Float, pxPerSecond: Float, frameRate: FrameRate): Long {
        require(distancePx >= 0f && distancePx.isFinite()) { "distancePx must be a non-negative number: $distancePx" }
        require(pxPerSecond > 0f && pxPerSecond.isFinite()) { "pxPerSecond must be positive: $pxPerSecond" }
        val onScreen = (distancePx.toDouble() * US_PER_SECOND / pxPerSecond.toDouble()).roundToLong()
        val halfFrame = frameRate.frameToUs(1) / 2
        return maxOf(onScreen, halfFrame)
    }

    /** Snaps one dragged time, such as a trim edge. */
    public fun snap(timeUs: Long, targets: Collection<Long>, thresholdUs: Long, frameRate: FrameRate): Snap {
        val nearest = nearest(timeUs, targets)
        if (nearest != null && abs(nearest - timeUs) <= thresholdUs) return Snap(nearest, nearest)
        return Snap(frameRate.snapUs(timeUs, FrameRounding.NEAREST).coerceAtLeast(0), null)
    }

    /**
     * Snaps a moved clip of [durationUs] whose start was dragged to
     * [startUs]. Either edge can snap; the edge closer to its target wins
     * (the start on a tie). [Snap.timeUs] is the clip's new start.
     */
    public fun snapClip(startUs: Long, durationUs: Long, targets: Collection<Long>, thresholdUs: Long, frameRate: FrameRate): Snap {
        val endUs = startUs + durationUs
        val startTarget = nearest(startUs, targets)?.takeIf { abs(it - startUs) <= thresholdUs }
        val endTarget = nearest(endUs, targets)?.takeIf { abs(it - endUs) <= thresholdUs && it - durationUs >= 0 }
        return when {
            startTarget != null && (endTarget == null || abs(startTarget - startUs) <= abs(endTarget - endUs)) -> Snap(startTarget, startTarget)
            endTarget != null -> Snap(endTarget - durationUs, endTarget)
            else -> Snap(frameRate.snapUs(startUs, FrameRounding.NEAREST).coerceAtLeast(0), null)
        }
    }

    /**
     * Snap targets while editing [excluding]: every other clip's start and
     * end on any track, the playhead, and 0. The dragged clip's own edges
     * are left out, so it doesn't stick to where it started.
     */
    public fun targets(timeline: Timeline, excluding: ClipId?, playheadUs: Long?): Set<Long> = buildSet {
        add(0L)
        playheadUs?.let(::add)
        timeline.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip.id != excluding) {
                    add(clip.startUs)
                    add(clip.endUs)
                }
            }
        }
    }

    /** The target closest to [timeUs]. Ties go to the earlier target, so the result never depends on iteration order. */
    private fun nearest(timeUs: Long, targets: Collection<Long>): Long? =
        targets.minWithOrNull(compareBy<Long> { abs(it - timeUs) }.thenBy { it })

    private const val US_PER_SECOND = 1_000_000.0
}
