package com.editor.veyro.core.model.transition

import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every transition draws, frame by frame, on the CPU reference that
 * mirrors the GPU stage and Media3's compositor.
 */
class TransitionLookTest {

    private val sec = 1_000_000L
    private val rate = FrameRate.FPS_30
    private val cut = 3 * sec
    private val plenty = CutSide(10 * sec, 10 * sec)
    private val width = 1_280
    private val height = 720

    /** Two opaque pictures with no black anywhere, varying across the canvas so any misplacement shows. */
    private val a: (Double, Double) -> Rgb = { x, y -> Rgb(0.2 + 0.6 * x, 0.1 + 0.2 * y, 0.3 + 0.4 * y) }
    private val b: (Double, Double) -> Rgb = { x, y -> Rgb(0.9 - 0.5 * y, 0.8 - 0.3 * x, 0.1 + 0.3 * x) }

    /** Sample points, kept off the exact middles and edges where two slid pictures meet. */
    private val points = (0..8).flatMap { i -> (0..8).map { j -> 0.053 + i * 0.111 to 0.047 + j * 0.112 } }

    private fun window(type: TransitionType, easing: Easing = type.defaultEasing, frames: Int = 15) =
        TransitionResolver.resolve(Transition(type, rate.frameToUs(frames.toLong()), easing), cut, plenty, plenty, rate)

    private fun windowFrames(w: TransitionWindow): List<Long> {
        val first = rate.usToFrame(w.startUs, FrameRounding.CEIL)
        return generateSequence(first) { it + 1 }.takeWhile { rate.frameToUs(it) < w.endUs }.toList()
    }

    private fun look(w: TransitionWindow, role: TransitionRole, layer: TransitionLayer, t: Long, w2: Int = width, h2: Int = height) =
        TransitionLook.of(w, role, layer, t, w2, h2)

    private fun assertRgb(expected: Rgb, actual: Rgb, tolerance: Double = 1e-9, message: String = "") {
        assertEquals("$message r", expected.r, actual.r, tolerance)
        assertEquals("$message g", expected.g, actual.g, tolerance)
        assertEquals("$message b", expected.b, actual.b, tolerance)
    }

    @Test
    fun `every transition looks the same whichever picture is on top`() {
        for (type in TransitionType.entries) {
            val w = window(type)
            for (frame in windowFrames(w)) {
                val t = rate.frameToUs(frame)
                for ((x, y) in points) {
                    val outgoingOnTop = TransitionComposite.blend(
                        TransitionComposite.layer(look(w, TransitionRole.OUTGOING, TransitionLayer.TOP, t), a, x, y),
                        TransitionComposite.layer(look(w, TransitionRole.INCOMING, TransitionLayer.BOTTOM, t), b, x, y),
                    )
                    val incomingOnTop = TransitionComposite.blend(
                        TransitionComposite.layer(look(w, TransitionRole.INCOMING, TransitionLayer.TOP, t), b, x, y),
                        TransitionComposite.layer(look(w, TransitionRole.OUTGOING, TransitionLayer.BOTTOM, t), a, x, y),
                    )
                    assertRgb(outgoingOnTop, incomingOnTop, 1e-9, "$type frame $frame at $x,$y")
                }
            }
        }
    }

    @Test
    fun `the frames around the window show one clip each, as a plain cut does`() {
        for (type in TransitionType.entries) {
            val w = window(type)
            val frames = windowFrames(w)
            val before = rate.frameToUs(frames.first() - 1)
            val after = rate.frameToUs(frames.last() + 1)
            for ((x, y) in points) {
                assertRgb(a(x, y), TransitionComposite.frame(w, a, b, before, x, y, width, height), 1e-9, "$type before")
                assertRgb(b(x, y), TransitionComposite.frame(w, a, b, after, x, y, width, height), 1e-9, "$type after")
            }
            // The middle frame is neither.
            val middle = rate.frameToUs(frames[frames.size / 2])
            val shown = points.map { (x, y) -> TransitionComposite.frame(w, a, b, middle, x, y, width, height) }
            assertTrue("$type middle", points.indices.any { i -> shown[i] != a(points[i].first, points[i].second) })
            assertTrue("$type middle", points.indices.any { i -> shown[i] != b(points[i].first, points[i].second) })
        }
    }

    @Test
    fun `a dissolve mixes in proportion to its progress on every frame`() {
        for (frames in listOf(1, 2, 7, 15, 30)) {
            val w = window(TransitionType.DISSOLVE, Easing.Linear, frames)
            val inWindow = windowFrames(w)
            assertEquals(frames, inWindow.size)
            inWindow.forEachIndexed { i, frame ->
                val p = (i + 1.0) / (frames + 1)
                val (x, y) = points[40]
                val shown = TransitionComposite.frame(w, a, b, rate.frameToUs(frame), x, y, width, height)
                val expected = Rgb(a(x, y).r * (1 - p) + b(x, y).r * p, a(x, y).g * (1 - p) + b(x, y).g * p, a(x, y).b * (1 - p) + b(x, y).b * p)
                assertRgb(expected, shown, 1e-3, "frames=$frames i=$i")
            }
        }
    }

    @Test
    fun `easing shapes the progress`() {
        val linear = window(TransitionType.DISSOLVE, Easing.Linear)
        val easeIn = window(TransitionType.DISSOLVE, Easing.EASE_IN)
        val first = rate.frameToUs(windowFrames(linear).first())
        val (x, y) = points[40]
        val mixLinear = TransitionComposite.frame(linear, a, b, first, x, y, width, height)
        val mixEaseIn = TransitionComposite.frame(easeIn, a, b, first, x, y, width, height)
        // Ease-in starts slower, so its first frame is closer to the outgoing picture.
        assertTrue(abs(mixEaseIn.r - a(x, y).r) < abs(mixLinear.r - a(x, y).r))
    }

    @Test
    fun `a wipe's soft edge moves across the canvas in its direction`() {
        val cases = mapOf(
            TransitionType.WIPE_RIGHT to { x: Double, _: Double -> x },
            TransitionType.WIPE_LEFT to { x: Double, _: Double -> 1 - x },
            TransitionType.WIPE_DOWN to { _: Double, y: Double -> y },
            TransitionType.WIPE_UP to { _: Double, y: Double -> 1 - y },
        )
        for ((type, along) in cases) {
            val w = window(type, Easing.Linear)
            val frames = windowFrames(w)
            frames.forEachIndexed { i, frame ->
                val p = (i + 1.0) / (frames.size + 1)
                val edge = -TransitionLook.WIPE_SOFTNESS / 2 + p * (1 + TransitionLook.WIPE_SOFTNESS)
                for ((x, y) in points) {
                    val s = along(x, y)
                    val shown = TransitionComposite.frame(w, a, b, rate.frameToUs(frame), x, y, width, height)
                    when {
                        s < edge - TransitionLook.WIPE_SOFTNESS -> assertRgb(b(x, y), shown, 1e-9, "$type revealed")
                        s > edge + TransitionLook.WIPE_SOFTNESS -> assertRgb(a(x, y), shown, 1e-9, "$type covered")
                    }
                }
            }
        }
    }

    @Test
    fun `slides move both pictures together and leave no gap`() {
        for (type in listOf(TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT, TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN)) {
            val w = window(type)
            for (frame in windowFrames(w)) {
                val t = rate.frameToUs(frame)
                for ((x, y) in points) {
                    val shown = TransitionComposite.frame(w, a, b, t, x, y, width, height)
                    // Every point shows one of the pictures, unblended: never black, never a mix.
                    val outgoing = look(w, TransitionRole.OUTGOING, TransitionLayer.TOP, t)
                    val incoming = look(w, TransitionRole.INCOMING, TransitionLayer.TOP, t)
                    val fromA = a(outgoing.inverse.mapX(x, y), outgoing.inverse.mapY(x, y))
                    val fromB = b(incoming.inverse.mapX(x, y), incoming.inverse.mapY(x, y))
                    assertTrue("$type at $x,$y", shown == fromA || shown == fromB)
                }
            }
        }
    }

    @Test
    fun `spins and zooms keep both pictures covering the canvas without shearing`() {
        for ((w2, h2) in listOf(1_280 to 720, 720 to 1_280, 1_080 to 1_080)) {
            for (type in listOf(TransitionType.SPIN_CLOCKWISE, TransitionType.SPIN_COUNTERCLOCKWISE, TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT)) {
                val w = window(type)
                for (frame in windowFrames(w)) {
                    val t = rate.frameToUs(frame)
                    for (role in TransitionRole.entries) {
                        val l = look(w, role, TransitionLayer.TOP, t, w2, h2)
                        // Shapes are kept in pixels: two points a pixel distance apart land a fixed multiple of it apart.
                        val ratio = pixelDistance(l, 0.3, 0.4, 0.6, 0.4, w2, h2) / (0.3 * w2)
                        assertEquals("$type $role", ratio, pixelDistance(l, 0.5, 0.2, 0.5, 0.7, w2, h2) / (0.5 * h2), 1e-9)
                        // A spin is scaled up just enough to cover the corners.
                        if (type == TransitionType.SPIN_CLOCKWISE || type == TransitionType.SPIN_COUNTERCLOCKWISE) {
                            for ((cx, cy) in listOf(0.001 to 0.001, 0.999 to 0.001, 0.001 to 0.999, 0.999 to 0.999)) {
                                val px = l.inverse.mapX(cx, cy)
                                val py = l.inverse.mapY(cx, cy)
                                assertTrue("$type $role corner", px in -1e-9..1.000000001 && py in -1e-9..1.000000001)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a fade dips to black at the cut and comes back`() {
        val w = window(TransitionType.FADE, Easing.Linear)
        val (x, y) = points[40]
        val brightness = windowFrames(w).map { frame ->
            val t = rate.frameToUs(frame)
            val shown = TransitionComposite.frame(w, a, b, t, x, y, width, height)
            val picture = if (t < w.midUs) a(x, y) else b(x, y)
            shown.r / picture.r
        }
        val darkest = brightness.indexOf(brightness.min())
        assertTrue(brightness.all { it < 1.0 })
        assertTrue(brightness.take(darkest + 1).zipWithNext().all { (p, q) -> q < p })
        assertTrue(brightness.drop(darkest).zipWithNext().all { (p, q) -> q > p })
        assertTrue(brightness.min() < 0.15)
        // Centred, the dip is on the cut: the outgoing clip fades out before it, the incoming one fades in after.
        assertEquals(w.cutUs, w.midUs)
    }

    @Test
    fun `a transition that doesn't fit is a plain cut`() {
        val w = TransitionResolver.resolve(Transition(TransitionType.DISSOLVE), cut, CutSide(5 * sec, 0), CutSide(5 * sec, 0), rate)
        val (x, y) = points[10]
        assertRgb(a(x, y), TransitionComposite.frame(w, a, b, cut - 1, x, y, width, height))
        assertRgb(b(x, y), TransitionComposite.frame(w, a, b, cut, x, y, width, height))
        assertNotEquals(a(x, y), b(x, y))
    }

    private fun pixelDistance(look: LayerLook, x1: Double, y1: Double, x2: Double, y2: Double, w: Int, h: Int): Double {
        val dx = (look.inverse.mapX(x1, y1) - look.inverse.mapX(x2, y2)) * w
        val dy = (look.inverse.mapY(x1, y1) - look.inverse.mapY(x2, y2)) * h
        return hypot(dx, dy)
    }
}
