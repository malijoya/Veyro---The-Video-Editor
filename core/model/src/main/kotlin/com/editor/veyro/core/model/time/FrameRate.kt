package com.editor.veyro.core.model.time

/**
 * An exact frame rate stored as a reduced fraction of frames per second, such
 * as 30000/1001 for NTSC 29.97.
 *
 * Conversions use integer arithmetic only, so preview and export land on the
 * same frame boundaries. Arithmetic overflow throws [ArithmeticException]
 * instead of wrapping silently.
 */
public class FrameRate private constructor(
    /** Frames, per [denominator] seconds. */
    public val numerator: Long,
    public val denominator: Long,
) {
    /**
     * Start time of [frameIndex]: the first whole microsecond at or after the
     * exact frame boundary. Frame 0 starts at 0.
     *
     * Frame k is shown for every time in `[frameToUs(k), frameToUs(k + 1))`,
     * so `usToFrame(frameToUs(k)) == k` in every rounding mode. With rates
     * capped at [MAX_FPS], consecutive starts are at least 1 ms apart.
     */
    public fun frameToUs(frameIndex: Long): Long =
        divide(
            Math.multiplyExact(Math.multiplyExact(frameIndex, TimeUs.PER_SECOND), denominator),
            numerator,
            FrameRounding.CEIL,
        )

    /**
     * Frame index at [timeUs], using the given [rounding].
     * - [FrameRounding.FLOOR]: the frame on screen at [timeUs].
     * - [FrameRounding.CEIL]: the first frame whose start is at or after [timeUs].
     * - [FrameRounding.NEAREST]: the frame whose exact boundary is closest to [timeUs].
     */
    public fun usToFrame(timeUs: Long, rounding: FrameRounding = FrameRounding.FLOOR): Long {
        val usPerDenominator = Math.multiplyExact(TimeUs.PER_SECOND, denominator)
        return when (rounding) {
            // Integer starts are ceilings, so ceil(x) <= t holds exactly when x <= t.
            FrameRounding.FLOOR ->
                Math.floorDiv(Math.multiplyExact(timeUs, numerator), usPerDenominator)
            // ceil(x) >= t holds exactly when x > t - 1.
            FrameRounding.CEIL ->
                Math.floorDiv(Math.multiplyExact(Math.subtractExact(timeUs, 1L), numerator), usPerDenominator) + 1
            FrameRounding.NEAREST ->
                divide(Math.multiplyExact(timeUs, numerator), usPerDenominator, FrameRounding.NEAREST)
        }
    }

    /** Moves [timeUs] onto the start of a frame. */
    public fun snapUs(timeUs: Long, rounding: FrameRounding = FrameRounding.NEAREST): Long =
        frameToUs(usToFrame(timeUs, rounding))

    /** Approximate frames per second. Use it for display and codec hints only, never in timing math. */
    public val fps: Double get() = numerator.toDouble() / denominator.toDouble()

    override fun equals(other: Any?): Boolean =
        other is FrameRate && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "FrameRate($numerator/$denominator)"

    public companion object {
        /** Frame rates above this are rejected. They are not real video content. */
        public const val MAX_FPS: Long = 1_000L

        public val FPS_23_976: FrameRate = of(24_000, 1_001)
        public val FPS_24: FrameRate = of(24)
        public val FPS_25: FrameRate = of(25)
        public val FPS_29_97: FrameRate = of(30_000, 1_001)
        public val FPS_30: FrameRate = of(30)
        public val FPS_50: FrameRate = of(50)
        public val FPS_59_94: FrameRate = of(60_000, 1_001)
        public val FPS_60: FrameRate = of(60)

        /** Broadcast and camera rates that measured values are snapped to. */
        private val STANDARD_RATES: List<FrameRate> by lazy {
            listOf(
                FPS_23_976, FPS_24, FPS_25, FPS_29_97, FPS_30,
                of(48_000, 1_001), of(48), FPS_50, FPS_59_94, FPS_60,
                of(90), of(100), of(120_000, 1_001), of(120), of(240),
            )
        }

        /** Largest difference from a standard rate that still snaps to it. 29.97 and 30 are 0.03 apart. */
        private const val SNAP_TOLERANCE_FPS = 0.01

        /**
         * Converts a measured or container-declared rate (such as 29.97002,
         * or 30 from a `MediaFormat`) to an exact [FrameRate].
         *
         * Values within 0.01 fps of a standard rate snap to it (29.9700 becomes
         * 30000/1001). Anything else is kept to 1/1000 fps. Returns `null` for
         * values that are not usable rates: NaN, infinite, zero or below, or
         * above [MAX_FPS].
         */
        public fun fromApproximateFps(fps: Double): FrameRate? {
            if (!fps.isFinite() || fps <= 0.0 || fps > MAX_FPS) return null
            STANDARD_RATES.firstOrNull { kotlin.math.abs(it.fps - fps) <= SNAP_TOLERANCE_FPS }?.let { return it }
            val milli = kotlin.math.round(fps * 1_000).toLong()
            return if (milli <= 0) null else of(milli, 1_000)
        }

        /** Creates the frame rate [numerator]/[denominator] fps, reduced to lowest terms. */
        public fun of(numerator: Long, denominator: Long = 1): FrameRate {
            require(numerator > 0 && denominator > 0) {
                "Frame rate must be positive: $numerator/$denominator"
            }
            val gcd = gcd(numerator, denominator)
            val n = numerator / gcd
            val d = denominator / gcd
            require(n <= MAX_FPS * d) { "Frame rate exceeds $MAX_FPS fps: $numerator/$denominator" }
            return FrameRate(n, d)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
