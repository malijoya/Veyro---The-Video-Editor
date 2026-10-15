package com.editor.veyro.core.model.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EasingTest {

    private val fractions = (0..1000).map { it / 1000.0 }

    @Test
    fun `every curve starts at exactly 0 and ends at exactly 1`() {
        (Easing.PRESETS + Easing.CubicBezier(0.3, -0.5, 0.7, 1.6)).forEach { easing ->
            assertEquals("$easing at 0", 0.0, easing.ease(0.0), 0.0)
            assertEquals("$easing at 1", 1.0, easing.ease(1.0), 0.0)
            // Outside the segment the ends hold.
            assertEquals(0.0, easing.ease(-3.0), 0.0)
            assertEquals(1.0, easing.ease(7.0), 0.0)
        }
    }

    @Test
    fun `linear is the identity and hold jumps only at the end`() {
        fractions.forEach { f ->
            assertEquals(f, Easing.Linear.ease(f), 0.0)
            assertEquals(if (f >= 1.0) 1.0 else 0.0, Easing.Hold.ease(f), 0.0)
        }
    }

    @Test
    fun `the CSS curves match their reference values`() {
        // Reference values of CSS ease-in / ease-out / ease-in-out at 25%, 50% and 75% of the time.
        assertEquals(0.0935, Easing.EASE_IN.ease(0.25), 1e-3)
        assertEquals(0.3153, Easing.EASE_IN.ease(0.5), 1e-3)
        assertEquals(0.6219, Easing.EASE_IN.ease(0.75), 1e-3)
        assertEquals(0.6847, Easing.EASE_OUT.ease(0.5), 1e-3)
        assertEquals(0.5, Easing.EASE_IN_OUT.ease(0.5), 1e-6)
        // Mirror images: ease-out(f) = 1 - ease-in(1 - f).
        fractions.forEach { f -> assertEquals(1 - Easing.EASE_IN.ease(1 - f), Easing.EASE_OUT.ease(f), 1e-5) }
        // ease-in-out is symmetric about the middle.
        fractions.forEach { f -> assertEquals(1 - Easing.EASE_IN_OUT.ease(1 - f), Easing.EASE_IN_OUT.ease(f), 1e-5) }
    }

    @Test
    fun `ease in starts slower and ease out starts faster than linear, and all presets only move forward`() {
        fractions.filter { it > 0.0 && it < 1.0 }.forEach { f ->
            assertTrue(Easing.EASE_IN.ease(f) < f)
            assertTrue(Easing.EASE_OUT.ease(f) > f)
        }
        Easing.PRESETS.forEach { easing ->
            fractions.zipWithNext().forEach { (a, b) -> assertTrue("$easing goes back between $a and $b", easing.ease(b) >= easing.ease(a) - 1e-12) }
        }
    }

    @Test
    fun `curves are deterministic, the same fraction gives the identical result every time`() {
        val first = fractions.map { Easing.EASE_IN_OUT.ease(it) }
        repeat(3) { assertEquals(first, fractions.map { Easing.EASE_IN_OUT.ease(it) }) }
        // Evaluated backwards (scrubbing back), the values are bit-for-bit the same.
        assertEquals(first, fractions.reversed().map { Easing.EASE_IN_OUT.ease(it) }.reversed())
    }

    @Test
    fun `a custom curve may overshoot`() {
        val back = Easing.CubicBezier(0.3, 0.0, 0.7, 1.5)
        assertTrue(fractions.any { back.ease(it) > 1.0 })
        assertThrows(IllegalArgumentException::class.java) { Easing.CubicBezier(1.2, 0.0, 0.5, 1.0) } // Time must move forward.
        assertThrows(IllegalArgumentException::class.java) { Easing.CubicBezier(0.2, 9.0, 0.5, 1.0) }
    }

    @Test
    fun `easing names round trip and unknown names are refused`() {
        (Easing.PRESETS + Easing.CubicBezier(0.1, 0.2, 0.3, 0.4)).forEach { easing ->
            assertEquals(easing, Easing.parse(Easing.key(easing)))
        }
        assertEquals(Easing.EASE_IN, Easing.parse(" Ease-In "))
        assertEquals(Easing.EASE_IN, Easing.parse("cubic-bezier(0.42, 0, 1, 1)"))
        assertNull(Easing.parse("bounce"))
        assertNull(Easing.parse("cubic-bezier(0.1,0.2,0.3)"))
        assertNull(Easing.parse("cubic-bezier(2,0,1,1)"))
    }
}
