package com.editor.veyro.core.model.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateTest {

    @Test
    fun `of reduces fractions so equal rates compare equal`() {
        assertEquals(FrameRate.of(30), FrameRate.of(60, 2))
        assertEquals(FrameRate.FPS_29_97, FrameRate.of(60_000, 2_002))
        assertNotEquals(FrameRate.FPS_30, FrameRate.FPS_29_97)
    }

    @Test
    fun `of rejects non-positive and absurd rates`() {
        assertThrows(IllegalArgumentException::class.java) { FrameRate.of(0) }
        assertThrows(IllegalArgumentException::class.java) { FrameRate.of(30, 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameRate.of(-30) }
        assertThrows(IllegalArgumentException::class.java) { FrameRate.of(1_001) }
    }

    @Test
    fun `integer rates map frames to exact microseconds`() {
        assertEquals(0L, FrameRate.FPS_25.frameToUs(0))
        assertEquals(40_000L, FrameRate.FPS_25.frameToUs(1))
        assertEquals(1_000_000L, FrameRate.FPS_25.frameToUs(25))
        // 1/30 s = 33_333.33 us; frame starts round up to whole microseconds.
        assertEquals(33_334L, FrameRate.FPS_30.frameToUs(1))
        assertEquals(1_000_000L, FrameRate.FPS_30.frameToUs(30))
    }

    @Test
    fun `NTSC rate stays exact over long durations`() {
        // 30000/1001 fps: exactly 30000 frames every 1001 seconds.
        assertEquals(1_001_000_000L, FrameRate.FPS_29_97.frameToUs(30_000))
        assertEquals(30_000L, FrameRate.FPS_29_97.usToFrame(1_001_000_000L))
        // 10 hours of 59.94 content does not drift.
        val tenHoursUs = TimeUs.fromSeconds(36_000)
        val frames = FrameRate.FPS_59_94.usToFrame(tenHoursUs)
        assertEquals(2_157_842L, frames)
        assertTrue(FrameRate.FPS_59_94.frameToUs(frames) <= tenHoursUs)
        assertTrue(FrameRate.FPS_59_94.frameToUs(frames + 1) > tenHoursUs)
    }

    @Test
    fun `frame index round-trips through its start time for every frame`() {
        val rates = listOf(
            FrameRate.FPS_23_976, FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_29_97,
            FrameRate.FPS_30, FrameRate.FPS_50, FrameRate.FPS_59_94, FrameRate.FPS_60,
            FrameRate.of(120), FrameRate.of(240),
        )
        for (rate in rates) {
            for (frame in 0L..10_000L) {
                val us = rate.frameToUs(frame)
                assertEquals("$rate frame $frame", frame, rate.usToFrame(us, FrameRounding.FLOOR))
                assertEquals("$rate frame $frame", frame, rate.usToFrame(us, FrameRounding.CEIL))
                assertEquals("$rate frame $frame", frame, rate.usToFrame(us, FrameRounding.NEAREST))
            }
        }
    }

    @Test
    fun `usToFrame rounding modes`() {
        val rate = FrameRate.FPS_25 // 40_000 us per frame
        assertEquals(0L, rate.usToFrame(39_999, FrameRounding.FLOOR))
        assertEquals(1L, rate.usToFrame(39_999, FrameRounding.CEIL))
        assertEquals(0L, rate.usToFrame(19_999, FrameRounding.NEAREST))
        assertEquals(1L, rate.usToFrame(20_000, FrameRounding.NEAREST))
        assertEquals(1L, rate.usToFrame(40_000, FrameRounding.CEIL))
    }

    @Test
    fun `negative times round consistently`() {
        val rate = FrameRate.FPS_25
        assertEquals(-1L, rate.usToFrame(-1, FrameRounding.FLOOR))
        assertEquals(0L, rate.usToFrame(-1, FrameRounding.CEIL))
        assertEquals(0L, rate.usToFrame(-20_000, FrameRounding.NEAREST))
        assertEquals(-1L, rate.usToFrame(-20_001, FrameRounding.NEAREST))
    }

    @Test
    fun `FLOOR and CEIL match the integer frame-start definition for every microsecond`() {
        for (rate in listOf(FrameRate.FPS_29_97, FrameRate.FPS_30, FrameRate.FPS_23_976)) {
            for (timeUs in -200_000L..200_000L) {
                val floor = rate.usToFrame(timeUs, FrameRounding.FLOOR)
                assertTrue("$rate $timeUs", rate.frameToUs(floor) <= timeUs)
                assertTrue("$rate $timeUs", rate.frameToUs(floor + 1) > timeUs)

                val ceil = rate.usToFrame(timeUs, FrameRounding.CEIL)
                assertTrue("$rate $timeUs", rate.frameToUs(ceil) >= timeUs)
                assertTrue("$rate $timeUs", rate.frameToUs(ceil - 1) < timeUs)
            }
        }
    }

    @Test
    fun `snapUs moves times onto frame starts`() {
        val rate = FrameRate.FPS_30
        assertEquals(33_334L, rate.snapUs(30_000))
        assertEquals(0L, rate.snapUs(10_000))
        assertEquals(0L, rate.snapUs(30_000, FrameRounding.FLOOR))
    }

    @Test
    fun `overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) {
            FrameRate.FPS_29_97.frameToUs(Long.MAX_VALUE / 1_000)
        }
        assertThrows(ArithmeticException::class.java) { TimeUs.fromSeconds(Long.MAX_VALUE) }
    }
}
