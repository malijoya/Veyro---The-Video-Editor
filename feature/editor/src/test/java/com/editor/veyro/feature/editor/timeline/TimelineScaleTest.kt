package com.editor.veyro.feature.editor.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineScaleTest {

    @Test
    fun `time and pixels convert both ways`() {
        val scale = TimelineScale(100f)
        assertEquals(100f, scale.usToPx(1_000_000), 0.0001f)
        assertEquals(50f, scale.usToPx(500_000), 0.0001f)
        assertEquals(1_000_000L, scale.pxToUs(100f))
        assertEquals(-250_000L, scale.pxToUs(-25f))
    }

    @Test
    fun `round trip stays within half a pixel's worth of time`() {
        for (pxPerSecond in listOf(0.25f, 1f, 33.3f, 60f, 777f, 2_400f)) {
            val scale = TimelineScale(pxPerSecond)
            val usPerPx = 1_000_000.0 / pxPerSecond
            for (us in listOf(0L, 1L, 33_366L, 1_000_000L, 3_599_999_999L)) {
                val back = scale.pxToUs(scale.usToPx(us))
                // Float pixels carry about 7 significant digits. Allow that error, plus rounding.
                val tolerance = maxOf(1.0, usPerPx * 0.5, us * 1e-6)
                assertTrue("$pxPerSecond px/s: $us -> $back", kotlin.math.abs(back - us).toDouble() <= tolerance)
            }
        }
    }

    @Test
    fun `zoom is clamped`() {
        assertEquals(TimelineScale.MAX_PX_PER_SECOND, TimelineScale(2_000f).zoomedBy(10f).pxPerSecond)
        assertEquals(TimelineScale.MIN_PX_PER_SECOND, TimelineScale(1f).zoomedBy(0.01f).pxPerSecond)
        assertEquals(120f, TimelineScale(60f).zoomedBy(2f).pxPerSecond)
    }

    @Test
    fun `fit scales duration to width`() {
        assertEquals(100f, TimelineScale.fitting(10_000_000, 1_000f).pxPerSecond, 0.001f)
        assertEquals(TimelineScale.DEFAULT_PX_PER_SECOND, TimelineScale.fitting(0, 1_000f).pxPerSecond)
        assertEquals(TimelineScale.MIN_PX_PER_SECOND, TimelineScale.fitting(100L * 3_600_000_000, 100f).pxPerSecond)
    }

    @Test
    fun `invalid scales are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TimelineScale(0f) }
        assertThrows(IllegalArgumentException::class.java) { TimelineScale(Float.NaN) }
    }

    @Test
    fun `ruler picks the finest readable step`() {
        // 60 px/s with 80 px minimum: 1 s = 60 px is too tight, 2 s = 120 px fits.
        assertEquals(2_000_000L, RulerTicks.choose(TimelineScale(60f), 80f).majorUs)
        // Zoomed in to 1000 px/s: 0.1 s = 100 px.
        assertEquals(100_000L, RulerTicks.choose(TimelineScale(1_000f), 80f).majorUs)
        // Fully zoomed out falls back to the largest step.
        assertEquals(1_800_000_000L, RulerTicks.choose(TimelineScale(TimelineScale.MIN_PX_PER_SECOND), 10_000f).majorUs)
        assertEquals(500_000L, RulerTicks(2_000_000, 4).minorUs)
    }

    @Test
    fun `ruler labels`() {
        assertEquals("0:00", RulerTicks.label(0))
        assertEquals("0:05", RulerTicks.label(5_000_000))
        assertEquals("1:05", RulerTicks.label(65_000_000))
        assertEquals("0.5s", RulerTicks.label(500_000))
        assertEquals("2.5s", RulerTicks.label(2_500_000))
        assertEquals("1:05.5", RulerTicks.label(65_500_000))
        assertEquals("60:00", RulerTicks.label(3_600_000_000))
    }
}
