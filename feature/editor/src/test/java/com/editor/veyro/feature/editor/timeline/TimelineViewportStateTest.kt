package com.editor.veyro.feature.editor.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineViewportStateTest {

    private fun viewport(playheadUs: Long = 5_000_000, pxPerSecond: Float = 100f) =
        TimelineViewportState(pxPerSecond, playheadUs).apply {
            widthPx = 1_000f
            durationUs = 20_000_000
        }

    @Test
    fun `playhead time sits at the centre of the viewport`() {
        val v = viewport()
        assertEquals(500f, v.xForTime(5_000_000), 0.001f)
        assertEquals(400f, v.xForTime(4_000_000), 0.001f)
        assertEquals(5_000_000L, v.timeForX(500f))
        assertEquals(6_000_000L, v.timeForX(600f))
    }

    @Test
    fun `x and time conversions are inverse`() {
        val v = viewport(playheadUs = 1_234_567, pxPerSecond = 37.5f)
        for (x in listOf(0f, 123.4f, 500f, 999f)) {
            assertEquals(x, v.xForTime(v.timeForX(x)), 0.05f)
        }
    }

    @Test
    fun `dragging right moves the playhead back and is clamped`() {
        val v = viewport()
        v.scrollByPx(100f) // 1 s at 100 px/s
        assertEquals(4_000_000L, v.playheadUs)
        v.scrollByPx(10_000f)
        assertEquals(0L, v.playheadUs)
        v.scrollByPx(-1_000_000f)
        assertEquals(20_000_000L, v.playheadUs)
    }

    @Test
    fun `zoom keeps the playhead time under the playhead`() {
        val v = viewport()
        v.zoomBy(2f)
        assertEquals(200f, v.scale.pxPerSecond)
        assertEquals(5_000_000L, v.playheadUs)
        assertEquals(500f, v.xForTime(5_000_000), 0.001f)
        v.zoomBy(Float.NaN)
        assertEquals(200f, v.scale.pxPerSecond)
    }

    @Test
    fun `zoom to fit shows the whole duration within the width`() {
        val v = viewport()
        v.zoomToFit()
        // 20 s over 90% of 1000 px.
        assertEquals(45f, v.scale.pxPerSecond, 0.01f)
    }

    @Test
    fun `visible span clips to the viewport plus margin`() {
        val v = viewport()
        // Clip from 4 s to 6 s: fully visible at 400..600 px.
        val span = v.visibleSpan(4_000_000, 6_000_000)!!
        assertEquals(400f, span.start, 0.001f)
        assertEquals(600f, span.endInclusive, 0.001f)

        // A long clip from 0 s to 20 s is cut to the screen plus the margin.
        val long = v.visibleSpan(0, 20_000_000)!!
        assertEquals(0f, long.start, 0.001f) // 0 s is at x = 0
        assertEquals(1_000f + TimelineViewportState.OFFSCREEN_MARGIN_PX, long.endInclusive, 0.001f)

        // Entirely off-screen to the right.
        assertNull(v.visibleSpan(19_000_000, 20_000_000))
    }

    @Test
    fun `scroll to time clamps to the timeline`() {
        val v = viewport()
        v.scrollToTime(-5)
        assertEquals(0L, v.playheadUs)
        v.scrollToTime(99_000_000)
        assertEquals(20_000_000L, v.playheadUs)
    }

    @Test
    fun `filmstrip frame times are quantised more coarsely as tiles cover more time`() {
        assertEquals(1_000_000L, StripThumbnails.quantizeTimeUs(1_100_000, tileDurationUs = 100_000))
        assertEquals(1_000_000L, StripThumbnails.quantizeTimeUs(1_499_999, tileDurationUs = 600_000))
        assertEquals(2_000_000L, StripThumbnails.quantizeTimeUs(3_999_999, tileDurationUs = 2_500_000))
        assertEquals(0L, StripThumbnails.quantizeTimeUs(0, tileDurationUs = 10_000_000))
    }
}
