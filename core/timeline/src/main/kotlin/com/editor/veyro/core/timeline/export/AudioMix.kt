package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.audio.Waveform

/**
 * Timeline-wide gain reduction that keeps the mix from clipping.
 *
 * Media3 mixes 16-bit audio and saturates the sum (`AudioMixingUtil`), so a
 * limiter after the mixer would be too late. Instead every source is turned
 * down *before* mixing, by the same factor, wherever the sum of their peaks
 * would pass [MixHeadroom.TARGET_PEAK]. See [MixHeadroom.compute].
 *
 * The curve is piecewise linear over windows of [windowUs]: across window
 * `w` it moves from `min(c[w-1], c[w])` to `min(c[w], c[w+1])`. Both ends are
 * at most `c[w]`, so every sample in a window gets no more gain than that
 * window allows, and the curve has no jumps.
 */
public class HeadroomCurve internal constructor(
    public val windowUs: Long,
    private val gains: FloatArray,
) {
    /** True when nothing is turned down anywhere. */
    public val isUnity: Boolean get() = gains.isEmpty()

    /** The lowest gain anywhere on the curve, for display. */
    public val minGain: Float get() = gains.minOrNull() ?: 1f

    /** Gain at [timelineUs], 0..1. */
    public fun gainAt(timelineUs: Long): Float {
        if (gains.isEmpty() || timelineUs < 0) return 1f
        val w = (timelineUs / windowUs).toInt()
        if (w >= gains.size) return 1f
        val start = minOf(at(w - 1), at(w))
        val end = minOf(at(w), at(w + 1))
        val fraction = (timelineUs - w * windowUs).toFloat() / windowUs
        return start + (end - start) * fraction
    }

    /** Whether the gain is exactly 1 for the whole of [[startUs], [endUs]). */
    public fun isUnityBetween(startUs: Long, endUs: Long): Boolean {
        if (gains.isEmpty()) return true
        val first = (startUs / windowUs).toInt() - 1
        val last = ((endUs - 1) / windowUs).toInt() + 1
        return (first..last).all { at(it) == 1f }
    }

    private fun at(index: Int): Float = if (index < 0 || index >= gains.size) 1f else gains[index]

    override fun equals(other: Any?): Boolean = other is HeadroomCurve && windowUs == other.windowUs && gains.contentEquals(other.gains)
    override fun hashCode(): Int = 31 * windowUs.hashCode() + gains.contentHashCode()
    override fun toString(): String = if (isUnity) "HeadroomCurve(unity)" else "HeadroomCurve(windows=${gains.size}, min=$minGain)"

    public companion object {
        public val UNITY: HeadroomCurve = HeadroomCurve(Waveform.DEFAULT_BUCKET_US, FloatArray(0))
    }
}

/** One sound in the mix, as [MixHeadroom] sees it. */
public class MixSource(
    public val timelineStartUs: Long,
    public val envelope: GainEnvelope,
    /** The media's peaks, or null when not analysed yet. Unknown peaks count as full scale. */
    public val waveform: Waveform?,
) {
    public val durationUs: Long get() = envelope.durationUs
    public val sourceStartUs: Long get() = envelope.sourceInUs
}

public object MixHeadroom {

    /**
     * Where a reduced mix is brought to: about -0.45 dBFS. The margin covers
     * the stereo down-mix and rounding in the mixer. Reduction only starts
     * when the summed peak would pass full scale (1.0); below that nothing
     * can clip, so nothing is changed.
     */
    public const val TARGET_PEAK: Float = 0.95f

    private const val FULL_SCALE = 1f

    /** Fastest rise in gain per window after a loud passage: full recovery takes 10 windows (200 ms). */
    private const val RELEASE_PER_WINDOW = 0.1f

    /** Fastest drop in gain per window before a loud passage: the reduction starts 2 windows (40 ms) early. */
    private const val ATTACK_PER_WINDOW = 0.5f

    /**
     * The gain reduction for a mix of [sources] over [durationUs].
     *
     * For each window, the worst case is the sum over the sources playing in
     * it of `envelope gain × measured peak`. Where that passes full scale,
     * all sources are scaled by `TARGET_PEAK / sum`. The result is then
     * smoothed: it drops at most [ATTACK_PER_WINDOW] per window (starting
     * early, since the whole timeline is known) and rises at most
     * [RELEASE_PER_WINDOW]. Smoothing only ever lowers the gain.
     *
     * A single source never needs reduction (its gain and peak are both at
     * most 1), so this only acts where sources overlap.
     */
    public fun compute(sources: List<MixSource>, durationUs: Long, windowUs: Long = Waveform.DEFAULT_BUCKET_US): HeadroomCurve {
        if (sources.size < 2 || durationUs <= 0) return HeadroomCurve.UNITY
        val windows = ((durationUs + windowUs - 1) / windowUs).toInt()
        val required = FloatArray(windows) { 1f }
        var anyReduction = false
        for (w in 0 until windows) {
            val start = w * windowUs
            val end = minOf(start + windowUs, durationUs)
            var sum = 0f
            for (source in sources) {
                val clipStart = source.timelineStartUs
                val clipEnd = clipStart + source.durationUs
                if (clipEnd <= start || clipStart >= end) continue
                val from = maxOf(start, clipStart) - clipStart
                val to = minOf(end, clipEnd) - clipStart
                val gain = maxOf(source.envelope.gainAt(from), source.envelope.gainAt(to))
                if (gain == 0f) continue
                val peak = source.waveform?.peakBetween(source.sourceStartUs + from, source.sourceStartUs + to) ?: 1f
                sum += gain * peak
            }
            if (sum > FULL_SCALE) {
                required[w] = TARGET_PEAK / sum
                anyReduction = true
            }
        }
        if (!anyReduction) return HeadroomCurve.UNITY

        val gains = required.copyOf()
        for (w in 1 until windows) gains[w] = minOf(gains[w], gains[w - 1] + RELEASE_PER_WINDOW)
        for (w in windows - 2 downTo 0) gains[w] = minOf(gains[w], gains[w + 1] + ATTACK_PER_WINDOW)
        return HeadroomCurve(windowUs, gains)
    }
}

/**
 * The full gain for one segment of sound: the clip's own envelope times the
 * mix headroom at its place on the timeline. Offsets are from the segment's
 * start, which is also how Media3 positions per-item audio processors (0 at
 * the clipped item's start, in both Transformer and CompositionPlayer).
 */
public data class SegmentGain(
    val envelope: GainEnvelope,
    val timelineStartUs: Long,
    val headroom: HeadroomCurve = HeadroomCurve.UNITY,
) {
    public fun gainAt(offsetUs: Long): Float =
        envelope.gainAt(offsetUs) * headroom.gainAt(timelineStartUs + offsetUs.coerceIn(0, envelope.durationUs))

    /** True when the segment plays exactly as recorded, so no gain stage is needed. */
    val isUnity: Boolean
        get() = envelope.isUnity && headroom.isUnityBetween(timelineStartUs, timelineStartUs + envelope.durationUs)

    /** End of the stretch from [offsetUs] where the total gain is exactly 1, or null. */
    public fun unityUntilUs(offsetUs: Long): Long? {
        val end = envelope.unityUntilUs(offsetUs) ?: return null
        return if (headroom.isUnityBetween(timelineStartUs + offsetUs, timelineStartUs + end)) end else null
    }
}
