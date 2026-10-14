package com.editor.veyro.core.model.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurationFormatTest {
    @Test
    fun `formats minutes and hours`() {
        assertEquals("0:00", DurationFormat.clock(0))
        assertEquals("0:01", DurationFormat.clock(1))
        assertEquals("0:01", DurationFormat.clock(999_999))
        assertEquals("0:07", DurationFormat.clock(7_900_000))
        assertEquals("12:34", DurationFormat.clock(TimeUs.fromSeconds(754)))
        assertEquals("1:02:03", DurationFormat.clock(TimeUs.fromSeconds(3_723)))
    }

    @Test
    fun `rejects negative durations`() {
        assertThrows(IllegalArgumentException::class.java) { DurationFormat.clock(-1) }
    }
}
