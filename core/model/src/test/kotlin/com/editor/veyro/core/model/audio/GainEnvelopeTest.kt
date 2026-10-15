package com.editor.veyro.core.model.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GainEnvelopeTest {

    private val sec = 1_000_000L
    private val eps = 1e-4f

    @Test
    fun `untouched clips are unity everywhere`() {
        val envelope = GainEnvelope(4 * sec, 0, ClipAudio.DEFAULT)
        assertTrue(envelope.isUnity)
        assertFalse(envelope.isSilent)
        listOf(0L, 1L, 2 * sec, 4 * sec).forEach { assertEquals(1f, envelope.gainAt(it), 0f) }
        assertEquals(4 * sec, envelope.unityUntilUs(0))
    }

    @Test
    fun `volume and track volume multiply`() {
        val envelope = GainEnvelope(4 * sec, 0, ClipAudio(volume = 0.5f), TrackAudio(volume = 0.5f))
        assertEquals(0.25f, envelope.gainAt(2 * sec), eps)
        assertFalse(envelope.isUnity)
        assertNull(envelope.unityUntilUs(2 * sec))
    }

    @Test
    fun `fades are linear ramps from and to silence`() {
        val envelope = GainEnvelope(10 * sec, 0, ClipAudio(volume = 0.8f, fadeInUs = 2 * sec, fadeOutUs = 4 * sec))
        assertEquals(0f, envelope.gainAt(0), eps)
        assertEquals(0.4f, envelope.gainAt(1 * sec), eps) // Halfway up the fade-in.
        assertEquals(0.8f, envelope.gainAt(2 * sec), eps)
        assertEquals(0.8f, envelope.gainAt(6 * sec), eps) // Fade-out starts here.
        assertEquals(0.2f, envelope.gainAt(9 * sec), eps) // A quarter of the fade-out left.
        assertEquals(0f, envelope.gainAt(10 * sec), eps)
        // Monotonic within each fade.
        val rising = (0..20).map { envelope.gainAt(it * 100_000L) }
        assertEquals(rising, rising.sorted())
    }

    @Test
    fun `overlapping fades shrink in proportion and meet`() {
        // 3 s of fades on a 2 s clip (after a trim): scaled to 1.33 s and 0.67 s.
        val envelope = GainEnvelope(2 * sec, 0, ClipAudio(fadeInUs = 2 * sec, fadeOutUs = 1 * sec))
        assertEquals(1_333_333L, envelope.effectiveFadeInUs)
        assertEquals(666_667L, envelope.effectiveFadeOutUs)
        assertEquals(envelope.durationUs, envelope.effectiveFadeInUs + envelope.effectiveFadeOutUs)
        assertTrue(envelope.gainAt(1_333_333) > 0.99f) // The peak where they meet.
        assertTrue((0..200).all { envelope.gainAt(it * 10_000L) in 0f..1f })
    }

    @Test
    fun `mute silences without losing the volume`() {
        val clip = GainEnvelope(4 * sec, 0, ClipAudio(volume = 0.7f, muted = true))
        assertTrue(clip.isSilent)
        assertEquals(0f, clip.gainAt(2 * sec), 0f)
        val track = GainEnvelope(4 * sec, 0, ClipAudio(volume = 0.7f), TrackAudio(muted = true))
        assertTrue(track.isSilent)
        assertEquals(0.7f, ClipAudio(volume = 0.7f, muted = true).volume, 0f)
    }

    @Test
    fun `keyframes are read in source time, so trims don't move them`() {
        val keyframes = listOf(GainKeyframe(10 * sec, 1f), GainKeyframe(12 * sec, 0f))
        // Clip plays source 9..13 s.
        val whole = GainEnvelope(4 * sec, 9 * sec, ClipAudio(keyframes = keyframes))
        assertEquals(1f, whole.gainAt(0), eps) // Before the first keyframe: its value holds.
        assertEquals(0.5f, whole.gainAt(2 * sec), eps) // Source 11 s, halfway between.
        assertEquals(0f, whole.gainAt(4 * sec), eps)
        // Trimmed to start at source 11 s: the same moment of audio still has the same gain.
        val trimmed = GainEnvelope(2 * sec, 11 * sec, ClipAudio(keyframes = keyframes))
        assertEquals(0.5f, trimmed.gainAt(0), eps)
    }

    @Test
    fun `hold keyframes step`() {
        val audio = ClipAudio(keyframes = listOf(GainKeyframe(0, 0.2f, GainInterpolation.HOLD), GainKeyframe(2 * sec, 0.9f)))
        val envelope = GainEnvelope(4 * sec, 0, audio)
        assertEquals(0.2f, envelope.gainAt(1_999_999), eps)
        assertEquals(0.9f, envelope.gainAt(2 * sec), eps)
    }

    @Test
    fun `invalid settings are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ClipAudio(volume = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { ClipAudio(volume = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ClipAudio(fadeInUs = -1) }
        assertThrows(IllegalArgumentException::class.java) { TrackAudio(volume = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { GainKeyframe(0, 2f) }
        assertThrows(IllegalArgumentException::class.java) {
            ClipAudio(keyframes = listOf(GainKeyframe(5, 1f), GainKeyframe(5, 0f)))
        }
    }
}
