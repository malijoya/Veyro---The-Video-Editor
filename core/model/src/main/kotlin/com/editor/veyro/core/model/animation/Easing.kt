package com.editor.veyro.core.model.animation

import kotlin.math.abs

/**
 * How a value travels from one keyframe to the next: a map from the elapsed
 * fraction of the segment (0..1) to the fraction of the value change (0 at
 * the start, 1 at the end).
 *
 * Every curve is pure arithmetic with a fixed number of steps, so the same
 * fraction gives bit-for-bit the same result on every call, device, thread
 * and in both preview and export.
 */
public sealed interface Easing {

    /** The eased progress at [fraction] (clamped to 0..1). `ease(0) == 0` and `ease(1) == 1` exactly. */
    public fun ease(fraction: Double): Double

    /** Constant speed. */
    public data object Linear : Easing {
        override fun ease(fraction: Double): Double = fraction.coerceIn(0.0, 1.0)
    }

    /** Keeps the start value for the whole segment, then jumps to the next keyframe's value on it. */
    public data object Hold : Easing {
        override fun ease(fraction: Double): Double = if (fraction >= 1.0) 1.0 else 0.0
    }

    /**
     * A CSS-style cubic Bézier timing curve from (0, 0) to (1, 1) with control
     * points ([x1], [y1]) and ([x2], [y2]). The x values must be within 0..1,
     * so time only moves forward. The y values may overshoot (within
     * [MIN_Y]..[MAX_Y]) for a "back" effect.
     */
    public data class CubicBezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Easing {
        init {
            require(x1.isFinite() && x1 in 0.0..1.0 && x2.isFinite() && x2 in 0.0..1.0) { "Bézier x values must be within 0..1" }
            require(y1.isFinite() && y1 in MIN_Y..MAX_Y && y2.isFinite() && y2 in MIN_Y..MAX_Y) { "Bézier y values must be within $MIN_Y..$MAX_Y" }
        }

        override fun ease(fraction: Double): Double {
            if (fraction.isNaN() || fraction <= 0.0) return 0.0
            if (fraction >= 1.0) return 1.0
            return sample(y1, y2, solveT(fraction))
        }

        /** The curve parameter t whose x is [x]. Newton's method, then bisection if it didn't converge. */
        private fun solveT(x: Double): Double {
            var t = x
            repeat(NEWTON_STEPS) {
                val error = sample(x1, x2, t) - x
                if (abs(error) < PRECISION) return t
                val slope = slope(x1, x2, t)
                if (abs(slope) < 1e-9) return@repeat
                t = (t - error / slope).coerceIn(0.0, 1.0)
            }
            var lo = 0.0
            var hi = 1.0
            t = x
            repeat(BISECTION_STEPS) {
                val value = sample(x1, x2, t)
                if (abs(value - x) < PRECISION) return t
                if (value < x) lo = t else hi = t
                t = (lo + hi) / 2
            }
            return t
        }

        public companion object {
            public const val MIN_Y: Double = -2.0
            public const val MAX_Y: Double = 3.0
            private const val NEWTON_STEPS = 8
            private const val BISECTION_STEPS = 40
            private const val PRECISION = 1e-7

            /** One coordinate of the curve at [t], with end points 0 and 1 and control values [p1], [p2]. */
            private fun sample(p1: Double, p2: Double, t: Double): Double {
                val u = 1 - t
                return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t
            }

            private fun slope(p1: Double, p2: Double, t: Double): Double {
                val u = 1 - t
                return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2)
            }
        }
    }

    public companion object {
        /** Starts slowly, ends at full speed. CSS `ease-in`. */
        public val EASE_IN: Easing = CubicBezier(0.42, 0.0, 1.0, 1.0)

        /** Starts at full speed, slows to a stop. CSS `ease-out`. */
        public val EASE_OUT: Easing = CubicBezier(0.0, 0.0, 0.58, 1.0)

        /** Slow at both ends. CSS `ease-in-out`. */
        public val EASE_IN_OUT: Easing = CubicBezier(0.42, 0.0, 0.58, 1.0)

        /** The curves the editor offers, in the order it shows them. */
        public val PRESETS: List<Easing> = listOf(Linear, EASE_IN, EASE_OUT, EASE_IN_OUT, Hold)

        /**
         * The portable name stored in project files: `linear`, `hold`,
         * `ease-in`, `ease-out`, `ease-in-out`, or `cubic-bezier(x1,y1,x2,y2)`.
         */
        public fun key(easing: Easing): String = when (easing) {
            Linear -> "linear"
            Hold -> "hold"
            EASE_IN -> "ease-in"
            EASE_OUT -> "ease-out"
            EASE_IN_OUT -> "ease-in-out"
            is CubicBezier -> "cubic-bezier(${easing.x1},${easing.y1},${easing.x2},${easing.y2})"
        }

        /** The easing stored as [key], or null for anything this version can't read. */
        public fun parse(key: String): Easing? {
            val text = key.trim().lowercase()
            PRESETS.firstOrNull { key(it) == text }?.let { return it }
            if (!text.startsWith("cubic-bezier(") || !text.endsWith(")")) return null
            val values = text.removePrefix("cubic-bezier(").removeSuffix(")").split(',').map { it.trim().toDoubleOrNull() ?: return null }
            if (values.size != 4) return null
            return runCatching { CubicBezier(values[0], values[1], values[2], values[3]) }.getOrNull()
        }
    }
}
