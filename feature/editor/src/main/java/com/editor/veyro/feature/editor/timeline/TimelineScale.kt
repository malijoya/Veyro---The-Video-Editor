package com.editor.veyro.feature.editor.timeline

import kotlin.math.roundToLong

/**
 * The one conversion between timeline time (integer microseconds) and screen
 * pixels. The ruler, clips, playhead, gestures and hit-testing all use it, so
 * they can never disagree.
 *
 * [pxPerSecond] is the zoom level. Positions are relative to time 0. Callers
 * add their own scroll offset.
 */
public data class TimelineScale(val pxPerSecond: Float) {
    init {
        require(pxPerSecond.isFinite() && pxPerSecond > 0f) { "pxPerSecond must be positive: $pxPerSecond" }
    }

    /** Pixel distance for a duration or time. */
    public fun usToPx(us: Long): Float = (us.toDouble() * pxPerSecond / US_PER_SECOND).toFloat()

    /** Time for a pixel distance, rounded to the nearest microsecond. */
    public fun pxToUs(px: Float): Long = (px.toDouble() * US_PER_SECOND / pxPerSecond).roundToLong()

    /** The same scale zoomed by [factor], clamped to [MIN_PX_PER_SECOND]..[MAX_PX_PER_SECOND]. */
    public fun zoomedBy(factor: Float): TimelineScale =
        TimelineScale((pxPerSecond * factor).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND))

    public companion object {
        private const val US_PER_SECOND = 1_000_000.0

        /** Zoomed out far enough to fit about an hour on a phone. */
        public const val MIN_PX_PER_SECOND: Float = 0.25f

        /** Zoomed in far enough that frames at 60 fps are several pixels apart on a dense phone screen. */
        public const val MAX_PX_PER_SECOND: Float = 2_400f

        public const val DEFAULT_PX_PER_SECOND: Float = 60f

        /** Zoom step for the zoom buttons. */
        public const val BUTTON_ZOOM_FACTOR: Float = 1.6f

        /**
         * The scale at which [durationUs] fills [widthPx]. Used for "zoom to
         * fit", and clamped to the allowed range.
         */
        public fun fitting(durationUs: Long, widthPx: Float): TimelineScale {
            if (durationUs <= 0 || widthPx <= 0f) return TimelineScale(DEFAULT_PX_PER_SECOND)
            val pxPerSecond = (widthPx.toDouble() * US_PER_SECOND / durationUs).toFloat()
            return TimelineScale(pxPerSecond.coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND))
        }
    }
}
