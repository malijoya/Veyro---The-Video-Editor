package com.editor.veyro.core.model.audio

import kotlin.math.abs

/**
 * Editing rules for a clip's volume keyframes ([ClipAudio.keyframes]). Pure
 * functions; the editor applies their results with an ordinary audio edit,
 * so each is one undo step.
 *
 * Volume keyframes are in **source time** (a moment of the media file, see
 * [GainKeyframe]); the editor converts the playhead with
 * `sourceTimeUs = clip.sourceInUs + (timelineUs - clip.startUs)`. A keyframe's
 * gain multiplies the clip's volume (see [GainEnvelope]): 100% at a keyframe
 * means "the clip's volume", 0% silence.
 */
public object GainKeyframes {

    /** The keyframe within [toleranceUs] of [sourceTimeUs] (the nearest, earlier on a tie), or null. */
    public fun near(audio: ClipAudio, sourceTimeUs: Long, toleranceUs: Long): GainKeyframe? =
        audio.keyframes
            .filter { abs(it.sourceTimeUs - sourceTimeUs) <= toleranceUs }
            .minWithOrNull(compareBy<GainKeyframe> { abs(it.sourceTimeUs - sourceTimeUs) }.thenBy { it.sourceTimeUs })

    /**
     * Adds a keyframe at [sourceTimeUs] holding the level the keyframes give
     * there now (100% when there are none), so the sound doesn't change until
     * its level is set. It takes the interpolation of the segment it splits.
     */
    public fun add(audio: ClipAudio, sourceTimeUs: Long): ClipAudio {
        if (audio.keyframes.any { it.sourceTimeUs == sourceTimeUs }) return audio
        val interpolation = audio.keyframes.lastOrNull { it.sourceTimeUs < sourceTimeUs }?.interpolation ?: GainInterpolation.LINEAR
        return withKeyframe(audio, GainKeyframe(sourceTimeUs, audio.keyframeGainAt(sourceTimeUs), interpolation))
    }

    /** Removes the keyframe at [sourceTimeUs]. */
    public fun remove(audio: ClipAudio, sourceTimeUs: Long): ClipAudio =
        audio.copy(keyframes = audio.keyframes.filterNot { it.sourceTimeUs == sourceTimeUs })

    /** Sets the level (0..1, clamped) of the keyframe at [sourceTimeUs]. */
    public fun setGain(audio: ClipAudio, sourceTimeUs: Long, gain: Float): ClipAudio {
        val keyframe = audio.keyframes.firstOrNull { it.sourceTimeUs == sourceTimeUs } ?: return audio
        val level = if (gain.isFinite()) gain.coerceIn(0f, 1f) else keyframe.gain
        return withKeyframe(audio, keyframe.copy(gain = level))
    }

    /** Sets how the level moves from the keyframe at [sourceTimeUs] to the next one. */
    public fun setInterpolation(audio: ClipAudio, sourceTimeUs: Long, interpolation: GainInterpolation): ClipAudio {
        val keyframe = audio.keyframes.firstOrNull { it.sourceTimeUs == sourceTimeUs } ?: return audio
        return withKeyframe(audio, keyframe.copy(interpolation = interpolation))
    }

    /** Moves the keyframe at [fromUs] to [toUs] (not negative), replacing one already there. */
    public fun move(audio: ClipAudio, fromUs: Long, toUs: Long): ClipAudio {
        val keyframe = audio.keyframes.firstOrNull { it.sourceTimeUs == fromUs } ?: return audio
        if (fromUs == toUs || toUs < 0) return audio
        return withKeyframe(remove(audio, fromUs), keyframe.copy(sourceTimeUs = toUs))
    }

    /** No keyframes; the clip plays at its volume throughout. */
    public fun clear(audio: ClipAudio): ClipAudio = audio.copy(keyframes = emptyList())

    private fun withKeyframe(audio: ClipAudio, keyframe: GainKeyframe): ClipAudio =
        audio.copy(keyframes = (audio.keyframes.filter { it.sourceTimeUs != keyframe.sourceTimeUs } + keyframe).sortedBy { it.sourceTimeUs })
}
