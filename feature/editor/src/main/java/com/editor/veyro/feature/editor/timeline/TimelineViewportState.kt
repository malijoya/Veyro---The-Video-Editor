package com.editor.veyro.feature.editor.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Scroll and zoom of the timeline.
 *
 * - The playhead sits at the horizontal center of the viewport and the
 *   timeline moves under it, so [playheadUs] is the scroll position.
 * - Only geometry lives here. Clips and selection come from the ViewModel.
 * - Layout and drawing read these values in their own phases, so scrolling
 *   and zooming re-lay-out and redraw without recomposing any clip.
 * - Every time/pixel mapping goes through [scale], via [xForTime] and
 *   [timeForX].
 */
@Stable
public class TimelineViewportState(pxPerSecond: Float, playheadUs: Long) {

    public var scale: TimelineScale by mutableStateOf(TimelineScale(pxPerSecond))
        private set

    public var playheadUs: Long by mutableLongStateOf(playheadUs)
        private set

    /** Total timeline duration. The playhead can't move past it. */
    public var durationUs: Long by mutableLongStateOf(0L)

    /** Width of the scrolling area in pixels, set from layout. */
    public var widthPx: Float by mutableFloatStateOf(0f)

    /** True while a finger is down or a fling is running. Playback must not move the playhead then. */
    public var isUserInteracting: Boolean by mutableStateOf(false)
        internal set

    /** Screen x (in the scrolling area) of timeline time [timeUs]. */
    public fun xForTime(timeUs: Long): Float = widthPx / 2f + scale.usToPx(timeUs - playheadUs)

    /** Timeline time at screen x. Not clamped. */
    public fun timeForX(x: Float): Long = playheadUs + scale.pxToUs(x - widthPx / 2f)

    /**
     * The on-screen part of `[startUs, endUs)` in pixels, extended by
     * [OFFSCREEN_MARGIN_PX] on both sides so short flings don't reveal
     * undrawn edges. Returns null when the range is off-screen.
     *
     * Clip layout and clip drawing both use this, so they always agree.
     */
    public fun visibleSpan(startUs: Long, endUs: Long): ClosedFloatingPointRange<Float>? {
        val left = xForTime(startUs)
        val right = xForTime(endUs)
        val visibleLeft = maxOf(left, -OFFSCREEN_MARGIN_PX)
        val visibleRight = minOf(right, widthPx + OFFSCREEN_MARGIN_PX)
        return if (visibleRight - visibleLeft >= 1f) visibleLeft..visibleRight else null
    }

    /** Moves the playhead to [timeUs], clamped to the timeline. Used for playback sync and jumps. */
    public fun scrollToTime(timeUs: Long) {
        playheadUs = timeUs.coerceIn(0, durationUs)
    }

    /** Dragging content right by [deltaPx] moves the playhead back in time. */
    public fun scrollByPx(deltaPx: Float) {
        scrollToTime(playheadUs - scale.pxToUs(deltaPx))
    }

    /** Zooms by [factor] around the playhead, which keeps its time. */
    public fun zoomBy(factor: Float) {
        if (factor.isFinite() && factor > 0f) scale = scale.zoomedBy(factor)
    }

    /** Zooms so the whole timeline fits the viewport, with a small margin. */
    public fun zoomToFit() {
        scale = TimelineScale.fitting(durationUs, widthPx * FIT_FRACTION)
    }

    /** Continues a drag with decaying velocity. [velocityPxPerSecond] uses the same sign as drag deltas. */
    public suspend fun fling(velocityPxPerSecond: Float, decay: DecayAnimationSpec<Float>) {
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocityPxPerSecond).animateDecay(decay) {
            val delta = value - last
            last = value
            val before = playheadUs
            scrollByPx(delta)
            // Stop at either end instead of animating against the clamp.
            if (playheadUs == before && delta != 0f) cancelAnimation()
        }
    }

    public companion object {
        public const val OFFSCREEN_MARGIN_PX: Float = 256f
        private const val FIT_FRACTION = 0.9f

        public val Saver: Saver<TimelineViewportState, List<Any>> = Saver(
            save = { listOf(it.scale.pxPerSecond, it.playheadUs) },
            restore = { TimelineViewportState(it[0] as Float, it[1] as Long) },
        )
    }
}

@Composable
public fun rememberTimelineViewportState(
    pxPerSecond: Float = TimelineScale.DEFAULT_PX_PER_SECOND,
): TimelineViewportState = rememberSaveable(saver = TimelineViewportState.Saver) {
    TimelineViewportState(pxPerSecond, playheadUs = 0)
}
