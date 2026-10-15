package com.editor.veyro.core.model.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GainKeyframesTest {

    private val sec = 1_000_000L

    /** 100% at 1 s, down to 20% at 3 s, then held until 0% at 5 s. */
    private val audio = ClipAudio(
        volume = 0.8f,
        keyframes = listOf(
            GainKeyframe(1 * sec, 1f),
            GainKeyframe(3 * sec, 0.2f, GainInterpolation.HOLD),
            GainKeyframe(5 * sec, 0f),
        ),
    )

    @Test
    fun `the keyframe level is linear or held between keyframes and holds outside them`() {
        assertEquals(1f, ClipAudio().keyframeGainAt(2 * sec), 0f) // No keyframes.
        assertEquals(1f, audio.keyframeGainAt(0), 0f)
        assertEquals(0.6f, audio.keyframeGainAt(2 * sec), 1e-6f)
        assertEquals(0.2f, audio.keyframeGainAt(4 * sec), 0f)
        assertEquals(0f, audio.keyframeGainAt(9 * sec), 0f)
        // The envelope uses the same levels, times the clip's volume.
        val envelope = GainEnvelope(durationUs = 6 * sec, sourceInUs = 0, clip = audio)
        assertEquals(0.8f * 0.6f, envelope.gainAt(2 * sec), 1e-6f)
    }

    @Test
    fun `an added keyframe holds the current level and the curve of the segment it splits`() {
        val added = GainKeyframes.add(audio, 2 * sec)
        assertEquals(4, added.keyframes.size)
        assertEquals(GainKeyframe(2 * sec, 0.6f, GainInterpolation.LINEAR), added.keyframes[1])
        // Nothing sounds different.
        (0..60).map { it * 100_000L }.forEach { t -> assertEquals(audio.keyframeGainAt(t), added.keyframeGainAt(t), 1e-6f) }
        assertEquals(GainInterpolation.HOLD, GainKeyframes.add(audio, 4 * sec).keyframes[2].interpolation)
        assertEquals(GainKeyframe(0, 1f), GainKeyframes.add(ClipAudio(), 0).keyframes.single())
        assertSame(audio, GainKeyframes.add(audio, 3 * sec)) // Already there.
    }

    @Test
    fun `levels, curves, moves and removal`() {
        val quieter = GainKeyframes.setGain(audio, 1 * sec, 0.5f)
        assertEquals(0.5f, quieter.keyframes.first().gain)
        assertEquals(0f, GainKeyframes.setGain(audio, 1 * sec, -3f).keyframes.first().gain)
        assertEquals(GainInterpolation.LINEAR, GainKeyframes.setInterpolation(audio, 3 * sec, GainInterpolation.LINEAR).keyframes[1].interpolation)
        val moved = GainKeyframes.move(audio, 3 * sec, 4 * sec)
        assertEquals(listOf(1 * sec, 4 * sec, 5 * sec), moved.keyframes.map { it.sourceTimeUs })
        assertEquals(listOf(1 * sec, 5 * sec), GainKeyframes.move(audio, 3 * sec, 5 * sec).keyframes.map { it.sourceTimeUs }) // Replaces.
        assertEquals(listOf(1 * sec, 5 * sec), GainKeyframes.remove(audio, 3 * sec).keyframes.map { it.sourceTimeUs })
        assertTrue(GainKeyframes.clear(audio).keyframes.isEmpty())
        assertEquals(0.8f, GainKeyframes.clear(audio).volume) // The clip's own volume stays.
    }

    @Test
    fun `keyframes are found within a tolerance`() {
        assertEquals(3 * sec, GainKeyframes.near(audio, 3 * sec + 16_000, 16_667)!!.sourceTimeUs)
        assertNull(GainKeyframes.near(audio, 3 * sec + 40_000, 16_667))
    }
}
