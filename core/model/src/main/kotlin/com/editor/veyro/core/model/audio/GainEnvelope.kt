package com.editor.veyro.core.model.audio

/**
 * The gain of one clip over its length on the timeline: the canonical
 * definition used by preview, export and the waveform drawing.
 *
 * Gain at `t` µs after the clip's start =
 * `volume × trackVolume × fadeIn(t) × fadeOut(t) × keyframes(sourceInUs + t)`,
 * as a linear amplitude factor in 0..1:
 * - Fades are linear in amplitude. If they overlap (fade-in + fade-out longer
 *   than the clip, for example after a trim), both shrink in proportion, so
 *   they meet without ever adding up to more than the clip.
 * - Keyframes are read at source time. Before the first and after the last
 *   keyframe the nearest keyframe's gain holds. No keyframes means 1.
 * - Muted clips or tracks have gain 0 everywhere.
 */
public data class GainEnvelope(
    val durationUs: Long,
    val sourceInUs: Long,
    val clip: ClipAudio,
    val track: TrackAudio = TrackAudio.DEFAULT,
) {
    init {
        require(durationUs > 0) { "Envelope needs a length" }
        require(sourceInUs >= 0) { "sourceInUs must not be negative" }
    }

    /** Volume before fades and keyframes. */
    val baseGain: Float = if (clip.isSilent || track.isSilent) 0f else clip.volume * track.volume

    /** Fade lengths after shrinking any overlap. */
    val effectiveFadeInUs: Long
    val effectiveFadeOutUs: Long

    init {
        val fadeIn = clip.fadeInUs.coerceAtMost(durationUs)
        val fadeOut = clip.fadeOutUs.coerceAtMost(durationUs)
        if (fadeIn + fadeOut > durationUs) {
            effectiveFadeInUs = (fadeIn.toDouble() * durationUs / (fadeIn + fadeOut)).toLong()
            effectiveFadeOutUs = durationUs - effectiveFadeInUs
        } else {
            effectiveFadeInUs = fadeIn
            effectiveFadeOutUs = fadeOut
        }
    }

    /** True when the clip plays exactly as recorded: no gain change anywhere. */
    val isUnity: Boolean
        get() = baseGain == 1f && effectiveFadeInUs == 0L && effectiveFadeOutUs == 0L && clip.keyframes.all { it.gain == 1f }

    /** True when the clip makes no sound at all. */
    val isSilent: Boolean get() = baseGain == 0f || (clip.keyframes.isNotEmpty() && clip.keyframes.all { it.gain == 0f })

    /** Gain at [offsetUs] after the clip's start. Offsets outside the clip are clamped to it. */
    public fun gainAt(offsetUs: Long): Float {
        if (baseGain == 0f) return 0f
        val t = offsetUs.coerceIn(0, durationUs)
        var gain = baseGain
        if (effectiveFadeInUs > 0 && t < effectiveFadeInUs) gain *= t.toFloat() / effectiveFadeInUs
        val fadeOutStart = durationUs - effectiveFadeOutUs
        if (effectiveFadeOutUs > 0 && t > fadeOutStart) gain *= (durationUs - t).toFloat() / effectiveFadeOutUs
        if (clip.keyframes.isNotEmpty()) gain *= clip.keyframeGainAt(sourceInUs + t)
        return gain.coerceIn(0f, 1f)
    }

    /**
     * The end (exclusive, offset from the clip's start) of the stretch from
     * [offsetUs] where the gain stays exactly 1, or null if the gain at
     * [offsetUs] isn't 1. Lets audio processors skip work in the unity part.
     */
    public fun unityUntilUs(offsetUs: Long): Long? {
        if (baseGain != 1f || clip.keyframes.any { it.gain != 1f }) return null
        val end = durationUs - effectiveFadeOutUs
        return if (offsetUs >= effectiveFadeInUs && offsetUs < end) end else null
    }
}
