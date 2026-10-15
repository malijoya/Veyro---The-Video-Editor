package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformBuilder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixHeadroomTest {

    private val sec = 1_000_000L

    /** A waveform with a constant [peak] over [durationUs]. */
    private fun flat(peak: Float, durationUs: Long): Waveform {
        val builder = WaveformBuilder()
        val buckets = (durationUs / Waveform.DEFAULT_BUCKET_US).toInt()
        for (i in 0 until buckets) builder.addFloat(floatArrayOf(peak), 1, 1, 50, i * Waveform.DEFAULT_BUCKET_US)
        return builder.build()
    }

    private fun source(startUs: Long, durationUs: Long, peak: Float?, audio: ClipAudio = ClipAudio.DEFAULT) =
        MixSource(startUs, GainEnvelope(durationUs, 0, audio), peak?.let { flat(it, durationUs) })

    /** The worst-case summed level at [t], after headroom, using the same peaks the planner sees. */
    private fun summedPeak(sources: List<MixSource>, curve: HeadroomCurve, t: Long): Float = sources.sumOf { s ->
        val offset = t - s.timelineStartUs
        if (offset < 0 || offset >= s.durationUs) 0.0 else {
            val peak = s.waveform?.peakBetween(offset, offset + 1) ?: 1f
            (s.envelope.gainAt(offset) * peak * curve.gainAt(t)).toDouble()
        }
    }.toFloat()

    @Test
    fun `a single source is never turned down`() {
        assertTrue(MixHeadroom.compute(listOf(source(0, 5 * sec, 1f)), 5 * sec).isUnity)
    }

    @Test
    fun `quiet overlapping sounds are left alone`() {
        val sources = listOf(source(0, 5 * sec, 0.4f), source(1 * sec, 3 * sec, 0.45f))
        assertTrue(MixHeadroom.compute(sources, 5 * sec).isUnity)
    }

    @Test
    fun `loud overlaps are turned down just enough, only where they overlap`() {
        // Two full-scale sounds overlap from 2 s to 4 s.
        val sources = listOf(source(0, 4 * sec, 1f), source(2 * sec, 4 * sec, 1f))
        val curve = MixHeadroom.compute(sources, 6 * sec)
        assertTrue(!curve.isUnity)
        // Nothing can clip anywhere, and the overlap sits at the target.
        for (t in 0 until 6 * sec step 5_000) {
            assertTrue("t=$t", summedPeak(sources, curve, t) <= 1f + 1e-4f)
        }
        for (t in 2 * sec until 4 * sec step 5_000) {
            assertTrue("t=$t", summedPeak(sources, curve, t) <= MixHeadroom.TARGET_PEAK + 1e-4f)
        }
        // In the middle of the overlap: about half each.
        assertEquals(MixHeadroom.TARGET_PEAK / 2, curve.gainAt(3 * sec), 0.01f)
        // Well away from the overlap: untouched.
        assertEquals(1f, curve.gainAt(1 * sec), 0f)
        assertEquals(1f, curve.gainAt(5 * sec), 0f)
    }

    @Test
    fun `the gain changes smoothly and starts dropping before the overlap`() {
        val sources = listOf(source(0, 4 * sec, 1f), source(2 * sec, 4 * sec, 1f))
        val curve = MixHeadroom.compute(sources, 6 * sec)
        // No jumps: neighbouring samples 1 ms apart differ by little.
        var previous = curve.gainAt(0)
        for (t in 1_000L until 6 * sec step 1_000) {
            val g = curve.gainAt(t)
            assertTrue("jump at $t: $previous -> $g", abs(g - previous) < 0.03f)
            previous = g
        }
        // Already lowered a little before 2 s, since the whole mix is known in advance.
        assertTrue(curve.gainAt(2 * sec - 10_000) < 1f)
    }

    @Test
    fun `fades and volume reduce what needs headroom`() {
        // The same overlap, but the second sound plays at 30%: 1.0 + 0.3 still clips a little.
        val sources = listOf(source(0, 4 * sec, 1f), source(2 * sec, 4 * sec, 1f, ClipAudio(volume = 0.3f)))
        val curve = MixHeadroom.compute(sources, 6 * sec)
        assertEquals(MixHeadroom.TARGET_PEAK / 1.3f, curve.gainAt(3 * sec), 0.01f)
        // At 10% the sum is under target and nothing changes.
        val quiet = listOf(source(0, 4 * sec, 0.8f), source(2 * sec, 4 * sec, 1f, ClipAudio(volume = 0.1f)))
        assertTrue(MixHeadroom.compute(quiet, 6 * sec).isUnity)
    }

    @Test
    fun `unanalysed audio counts as full scale`() {
        val sources = listOf(source(0, 4 * sec, 0.3f), source(0, 4 * sec, null))
        val curve = MixHeadroom.compute(sources, 4 * sec)
        assertEquals(MixHeadroom.TARGET_PEAK / 1.3f, curve.gainAt(2 * sec), 0.01f)
    }

    @Test
    fun `segment gain combines the clip envelope with the curve at the clip's timeline position`() {
        val sources = listOf(source(0, 4 * sec, 1f), source(2 * sec, 4 * sec, 1f))
        val curve = MixHeadroom.compute(sources, 6 * sec)
        val second = SegmentGain(GainEnvelope(4 * sec, 0, ClipAudio(volume = 0.5f)), timelineStartUs = 2 * sec, headroom = curve)
        // Offset 1 s into the second clip is timeline 3 s.
        assertEquals(0.5f * curve.gainAt(3 * sec), second.gainAt(1 * sec), 1e-6f)
        assertTrue(!second.isUnity)
        val untouched = SegmentGain(GainEnvelope(1 * sec, 0, ClipAudio.DEFAULT), timelineStartUs = 0)
        assertTrue(untouched.isUnity)
    }
}
