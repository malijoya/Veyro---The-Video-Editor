package com.editor.veyro.core.model.animation

/**
 * One keyframe: the value a property has at [timeUs].
 *
 * [timeUs] is **clip-local time**, in microseconds from the clip's first
 * frame (see [ClipAnimation] for the exact semantics). It may be negative or
 * past the clip's end: such keyframes are hidden by a trim, but still shape
 * the curve inside the clip, and come back if the trim is undone.
 *
 * [easing] shapes the segment from this keyframe to the next one. The last
 * keyframe's easing has no effect until another keyframe follows it.
 */
public data class Keyframe(
    val timeUs: Long,
    val value: Double,
    val easing: Easing = Easing.Linear,
) {
    init {
        require(value.isFinite()) { "Keyframe value must be finite: $value" }
    }
}

/**
 * The keyframes of one property, sorted by time, at most one per time, never
 * empty.
 *
 * [valueAt] is the property's value at any clip-local time:
 * - before the first keyframe, the first keyframe's value
 * - after the last keyframe, the last keyframe's value
 * - exactly on a keyframe, that keyframe's value
 * - between keyframes `a` and `b`, `a.value + (b.value - a.value) ×
 *   a.easing(fraction)`, where `fraction = (t - a.time) / (b.time - a.time)`
 *
 * Evaluation is a pure function of the time: no state, no clock, no
 * direction. Scrubbing backwards, seeking, and rendering frames out of order
 * all give the same value for the same time.
 */
public class KeyframeTrack(keyframes: List<Keyframe>) {

    public val keyframes: List<Keyframe> = keyframes.toList()

    init {
        require(this.keyframes.isNotEmpty()) { "A keyframe track needs at least one keyframe" }
        require(this.keyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs }) { "Keyframes must be sorted by time, without duplicates" }
    }

    public val first: Keyframe get() = keyframes.first()
    public val last: Keyframe get() = keyframes.last()

    /** The value at clip-local [timeUs]. */
    public fun valueAt(timeUs: Long): Double {
        if (timeUs <= first.timeUs) return first.value
        if (timeUs >= last.timeUs) return last.value
        // The last keyframe at or before timeUs; there is always one after it.
        val index = indexAtOrBefore(timeUs)
        val a = keyframes[index]
        if (a.timeUs == timeUs) return a.value
        val b = keyframes[index + 1]
        val fraction = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs).toDouble()
        val eased = a.easing.ease(fraction)
        return a.value + (b.value - a.value) * eased
    }

    /** The keyframe exactly at [timeUs], if any. */
    public fun at(timeUs: Long): Keyframe? = keyframes.getOrNull(indexAtOrBefore(timeUs))?.takeIf { it.timeUs == timeUs }

    /** A copy with [keyframe] added, replacing one at the same time. */
    public fun with(keyframe: Keyframe): KeyframeTrack =
        KeyframeTrack((keyframes.filter { it.timeUs != keyframe.timeUs } + keyframe).sortedBy { it.timeUs })

    /** A copy without the keyframe at [timeUs]; null if none would be left. */
    public fun without(timeUs: Long): KeyframeTrack? =
        keyframes.filter { it.timeUs != timeUs }.takeIf { it.isNotEmpty() }?.let(::KeyframeTrack)

    /** Every keyframe moved by [deltaUs]. */
    public fun shifted(deltaUs: Long): KeyframeTrack =
        if (deltaUs == 0L) this else KeyframeTrack(keyframes.map { it.copy(timeUs = Math.addExact(it.timeUs, deltaUs)) })

    /**
     * The keyframes needed to evaluate `[startUs, endUs]` exactly as this
     * track does: those inside, plus the nearest one on each side. Used when a
     * clip is split, so each part keeps its own animation without carrying
     * every keyframe of the original.
     */
    public fun keptFor(startUs: Long, endUs: Long): KeyframeTrack {
        val firstInside = keyframes.indexOfFirst { it.timeUs >= startUs }.let { if (it < 0) keyframes.size else it }
        val lastInside = keyframes.indexOfLast { it.timeUs <= endUs }
        val from = (firstInside - 1).coerceAtLeast(0)
        val to = (lastInside + 1).coerceAtMost(keyframes.lastIndex)
        return if (from == 0 && to == keyframes.lastIndex) this else KeyframeTrack(keyframes.subList(from, to + 1))
    }

    /** Smallest and largest value the track reaches (eased segments stay between their ends unless a curve overshoots). */
    public fun valueRange(): ClosedFloatingPointRange<Double> {
        var lo = keyframes.minOf { it.value }
        var hi = keyframes.maxOf { it.value }
        // Overshooting curves can leave the keyframes' range; sample them.
        keyframes.zipWithNext().forEach { (a, b) ->
            val curve = a.easing as? Easing.CubicBezier ?: return@forEach
            if (curve.y1 in 0.0..1.0 && curve.y2 in 0.0..1.0) return@forEach
            for (i in 1 until OVERSHOOT_SAMPLES) {
                val v = a.value + (b.value - a.value) * curve.ease(i.toDouble() / OVERSHOOT_SAMPLES)
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
        }
        return lo..hi
    }

    /** Index of the last keyframe at or before [timeUs], or -1. */
    private fun indexAtOrBefore(timeUs: Long): Int {
        var lo = 0
        var hi = keyframes.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (keyframes[mid].timeUs <= timeUs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    override fun equals(other: Any?): Boolean = other is KeyframeTrack && keyframes == other.keyframes
    override fun hashCode(): Int = keyframes.hashCode()
    override fun toString(): String = "KeyframeTrack($keyframes)"

    public companion object {
        private const val OVERSHOOT_SAMPLES = 32

        public fun of(vararg keyframes: Keyframe): KeyframeTrack = KeyframeTrack(keyframes.toList())
    }
}
