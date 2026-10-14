package com.editor.veyro.core.model.time

/**
 * Timeline time convention.
 *
 * Every timestamp and duration in the project model is a `Long` count of
 * microseconds, and its name ends in `Us` (`startUs`, `durationUs`). That is
 * the same unit Media3 uses (`C.TIME_UNSET` aside), so values reach ExoPlayer
 * and Transformer without conversion and stay exact. Floating-point seconds
 * are never stored. They are only for display.
 */
public object TimeUs {
    public const val PER_MILLISECOND: Long = 1_000L
    public const val PER_SECOND: Long = 1_000_000L

    public fun fromMillis(millis: Long): Long = Math.multiplyExact(millis, PER_MILLISECOND)

    public fun fromSeconds(seconds: Long): Long = Math.multiplyExact(seconds, PER_SECOND)
}

/** How to map a time that falls between two frame boundaries onto a frame index. */
public enum class FrameRounding {
    /** The frame being displayed at that time. Use this for preview and seek. */
    FLOOR,

    /** The nearest frame boundary, with ties rounding up. Use this for snapping edits. */
    NEAREST,

    /** The first frame boundary at or after that time. */
    CEIL,
}

/** Integer division with an explicit rounding mode. [divisor] must be positive. */
internal fun divide(dividend: Long, divisor: Long, rounding: FrameRounding): Long {
    require(divisor > 0) { "divisor must be positive: $divisor" }
    return when (rounding) {
        FrameRounding.FLOOR -> Math.floorDiv(dividend, divisor)
        FrameRounding.CEIL -> -Math.floorDiv(-dividend, divisor)
        FrameRounding.NEAREST -> Math.floorDiv(Math.addExact(Math.multiplyExact(dividend, 2L), divisor), Math.multiplyExact(divisor, 2L))
    }
}
