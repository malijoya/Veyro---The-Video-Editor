package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import kotlin.math.abs

/**
 * Where a dragged time lands.
 *
 * @property timeUs the snapped time.
 * @property guideUs the edge or playhead it snapped to (for drawing a guide
 *   line), or null when it only snapped to the frame grid.
 */
public data class Snap(val timeUs: Long, val guideUs: Long?)

/**
 * Frame-aware snapping for drags and the playhead.
 *
 * A time within [thresholdUs] of a target (another clip's edge, the playhead,
 * or 0) jumps to it, so cuts line up exactly. Otherwise it is rounded to the
 * nearest frame boundary of the timeline's frame rate, so every edit lands on
 * a frame.
 */
public object Snapper {

    public fun snap(timeUs: Long, targets: Collection<Long>, thresholdUs: Long, frameRate: FrameRate): Snap {
        val nearest = targets.minByOrNull { abs(it - timeUs) }
        if (nearest != null && abs(nearest - timeUs) <= thresholdUs) return Snap(nearest, nearest)
        return Snap(frameRate.snapUs(timeUs, FrameRounding.NEAREST).coerceAtLeast(0), null)
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
}
