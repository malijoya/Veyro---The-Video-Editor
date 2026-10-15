package com.editor.veyro.core.model.project

import com.editor.veyro.core.model.time.FrameRate

/**
 * Per-project output and editing settings, saved with the project.
 *
 * @property canvasWidthPx output width. Even, between 16 and [MAX_DIMENSION_PX].
 * @property canvasHeightPx output height. Same limits.
 * @property frameRate the edit's frame grid: snapping and the minimum clip
 *   length use it, and export will too.
 * @property snapToEdges whether drags snap to other clips and the playhead.
 *   Frame-grid snapping always applies.
 * @property snapDistanceDp how close (on screen, in density-independent
 *   pixels) a dragged edge must come to a target to snap to it. A screen
 *   distance rather than a time, so snapping feels the same at every zoom.
 * @property photoDurationUs how long a newly placed photo lasts.
 */
public data class ProjectSettings(
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val frameRate: FrameRate,
    val snapToEdges: Boolean = true,
    val snapDistanceDp: Int = DEFAULT_SNAP_DISTANCE_DP,
    val photoDurationUs: Long = DEFAULT_PHOTO_DURATION_US,
) {
    init {
        require(canvasWidthPx in MIN_DIMENSION_PX..MAX_DIMENSION_PX && canvasWidthPx % 2 == 0) { "Invalid canvas width $canvasWidthPx" }
        require(canvasHeightPx in MIN_DIMENSION_PX..MAX_DIMENSION_PX && canvasHeightPx % 2 == 0) { "Invalid canvas height $canvasHeightPx" }
        require(snapDistanceDp in MIN_SNAP_DISTANCE_DP..MAX_SNAP_DISTANCE_DP) { "Invalid snap distance $snapDistanceDp" }
        require(photoDurationUs in MIN_PHOTO_DURATION_US..MAX_PHOTO_DURATION_US) { "Invalid photo duration $photoDurationUs" }
    }

    val aspectRatio: Float get() = canvasWidthPx.toFloat() / canvasHeightPx

    public companion object {
        public const val MIN_DIMENSION_PX: Int = 16

        /** 8K. Larger canvases exceed what device encoders can produce. */
        public const val MAX_DIMENSION_PX: Int = 7_680
        public const val DEFAULT_PHOTO_DURATION_US: Long = 3_000_000
        public const val MIN_PHOTO_DURATION_US: Long = 100_000
        public const val MAX_PHOTO_DURATION_US: Long = 600_000_000
        public const val DEFAULT_SNAP_DISTANCE_DP: Int = 12
        public const val MIN_SNAP_DISTANCE_DP: Int = 4
        public const val MAX_SNAP_DISTANCE_DP: Int = 32

        /** Portrait 1080p at 30 fps: the usual phone-video default when there's nothing to match. */
        public val DEFAULT: ProjectSettings = ProjectSettings(1_080, 1_920, FrameRate.FPS_30)

        /**
         * Settings matching the first imported visual: its displayed
         * (upright) size, capped at 4K on the longer side, and its frame
         * rate. Falls back to [DEFAULT] for anything unknown.
         */
        public fun matching(media: List<MediaRef>): ProjectSettings {
            val visual = media.firstOrNull { it.kind != MediaKind.AUDIO && it.displayWidthPx > 0 && it.displayHeightPx > 0 }
            val frameRate = media.firstNotNullOfOrNull { it.video?.frameRate } ?: DEFAULT.frameRate
            if (visual == null) return DEFAULT.copy(frameRate = frameRate)
            val (width, height) = fitWithin(visual.displayWidthPx, visual.displayHeightPx, maxLongSide = 3_840)
            return ProjectSettings(width, height, frameRate)
        }

        /** Scales a size down to [maxLongSide] keeping its shape, and rounds both sides to even numbers (encoders need that). */
        public fun fitWithin(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
            val scale = minOf(1.0, maxLongSide.toDouble() / maxOf(width, height))
            fun even(value: Double) = (Math.round(value / 2.0) * 2).toInt().coerceIn(MIN_DIMENSION_PX, MAX_DIMENSION_PX)
            return even(width * scale) to even(height * scale)
        }
    }
}

/** Common canvas shapes offered in project settings. */
public enum class CanvasPreset(public val width: Int, public val height: Int) {
    PORTRAIT_9_16(1_080, 1_920),
    LANDSCAPE_16_9(1_920, 1_080),
    SQUARE_1_1(1_080, 1_080),
    PORTRAIT_4_5(1_080, 1_350),
}
