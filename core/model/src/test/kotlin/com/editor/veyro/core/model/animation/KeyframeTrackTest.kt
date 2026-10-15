package com.editor.veyro.core.model.animation

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeTrackTest {

    private val sec = 1_000_000L

    /** 0 at 1 s, 10 at 3 s (linear), 4 at 5 s. */
    private val track = KeyframeTrack.of(
        Keyframe(1 * sec, 0.0),
        Keyframe(3 * sec, 10.0),
        Keyframe(5 * sec, 4.0),
    )

    // ---------------- Interpolation ----------------

    @Test
    fun `before the first and after the last keyframe the nearest value holds`() {
        assertEquals(0.0, track.valueAt(0), 0.0)
        assertEquals(0.0, track.valueAt(-7 * sec), 0.0)
        assertEquals(4.0, track.valueAt(5 * sec + 1), 0.0)
        assertEquals(4.0, track.valueAt(Long.MAX_VALUE), 0.0)
    }

    @Test
    fun `on a keyframe the value is exactly the keyframe's`() {
        track.keyframes.forEach { assertEquals(it.value, track.valueAt(it.timeUs), 0.0) }
    }

    @Test
    fun `linear segments interpolate in proportion to elapsed microseconds`() {
        assertEquals(5.0, track.valueAt(2 * sec), 0.0)
        assertEquals(2.5, track.valueAt(1_500_000), 1e-12)
        assertEquals(7.0, track.valueAt(4 * sec), 1e-12)
        // One microsecond after a keyframe moves by exactly one microsecond's worth.
        assertEquals(10.0 / (2 * sec), track.valueAt(1 * sec + 1), 1e-15)
    }

    @Test
    fun `each segment uses the easing of the keyframe it starts at`() {
        val eased = KeyframeTrack.of(
            Keyframe(0, 0.0, Easing.EASE_IN),
            Keyframe(sec, 100.0, Easing.Hold),
            Keyframe(2 * sec, 0.0),
        )
        assertEquals(100.0 * Easing.EASE_IN.ease(0.5), eased.valueAt(sec / 2), 1e-9)
        assertEquals(100.0, eased.valueAt(sec + sec / 2), 0.0) // Held until the next keyframe...
        assertEquals(0.0, eased.valueAt(2 * sec), 0.0) // ...then its value.
        assertEquals(100.0, eased.valueAt(2 * sec - 1), 0.0)
    }

    @Test
    fun `evaluation depends only on the time, in whatever order times are asked for`() {
        val eased = KeyframeTrack.of(Keyframe(0, -3.0, Easing.EASE_IN_OUT), Keyframe(777_777, 12.5, Easing.EASE_OUT), Keyframe(2 * sec, 1.0))
        val times = (-100..2_200).map { it * 1_000L }
        val forward = times.associateWith { eased.valueAt(it) }
        // Scrubbing backwards and random seeks see bit-identical values.
        times.reversed().forEach { assertEquals(forward.getValue(it), eased.valueAt(it), 0.0) }
        times.shuffled(Random(7)).forEach { assertEquals(forward.getValue(it), eased.valueAt(it), 0.0) }
    }

    // ---------------- Editing ----------------

    @Test
    fun `keyframes are sorted, unique, finite and never empty`() {
        assertThrows(IllegalArgumentException::class.java) { KeyframeTrack(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { KeyframeTrack.of(Keyframe(2, 0.0), Keyframe(1, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { KeyframeTrack.of(Keyframe(1, 0.0), Keyframe(1, 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { Keyframe(0, Double.NaN) }
    }

    @Test
    fun `adding replaces a keyframe at the same time and keeps order, removing can empty the track`() {
        val added = track.with(Keyframe(2 * sec, 1.0)).with(Keyframe(3 * sec, -1.0))
        assertEquals(listOf(1 * sec, 2 * sec, 3 * sec, 5 * sec), added.keyframes.map { it.timeUs })
        assertEquals(-1.0, added.at(3 * sec)!!.value, 0.0)
        assertNull(added.at(4 * sec))
        assertEquals(listOf(1 * sec, 5 * sec), track.without(3 * sec)!!.keyframes.map { it.timeUs })
        assertNull(KeyframeTrack.of(Keyframe(0, 1.0)).without(0))
    }

    @Test
    fun `shifting moves the curve in time without changing its shape`() {
        val shifted = track.shifted(-2 * sec)
        (-3_000..6_000).map { it * 1_000L }.forEach { t -> assertEquals(track.valueAt(t), shifted.valueAt(t - 2 * sec), 0.0) }
        assertSame(track, track.shifted(0))
    }

    @Test
    fun `the keyframes kept for a range evaluate identically inside it`() {
        val random = Random(42)
        repeat(200) {
            val times = (0 until random.nextInt(1, 8)).map { random.nextLong(-2 * sec, 8 * sec) }.distinct().sorted()
            val full = KeyframeTrack(times.map { Keyframe(it, random.nextDouble(-50.0, 50.0), Easing.PRESETS.random(random)) })
            val start = random.nextLong(0, 5 * sec)
            val end = start + random.nextLong(1, 3 * sec)
            val kept = full.keptFor(start, end)
            assertTrue(kept.keyframes.size <= full.keyframes.size)
            (0..100).map { start + (end - start) * it / 100 }.forEach { t -> assertEquals(full.valueAt(t), kept.valueAt(t), 0.0) }
        }
    }

    @Test
    fun `the value range includes overshoot of custom curves`() {
        assertEquals(0.0..10.0, track.valueRange())
        val back = KeyframeTrack.of(Keyframe(0, 0.0, Easing.CubicBezier(0.3, 0.0, 0.7, 1.5)), Keyframe(sec, 1.0))
        assertTrue(back.valueRange().endInclusive > 1.05)
    }
}
