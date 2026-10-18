package com.editor.veyro.core.model.transition

import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fitting transitions to cuts: whole frames of the timeline's rate, exact
 * window boundaries at every rate, handles and clip lengths, and the
 * longest fit always chosen.
 */
class TransitionResolverTest {

    private val sec = 1_000_000L
    private val rates = listOf(
        FrameRate.FPS_23_976, FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_29_97,
        FrameRate.FPS_30, FrameRate.FPS_50, FrameRate.FPS_59_94, FrameRate.FPS_60,
    )
    private val plenty = CutSide(10 * sec, 10 * sec)

    private fun dissolve(durationUs: Long = 500_000, alignment: TransitionAlignment = TransitionAlignment.CENTER) =
        Transition(TransitionType.DISSOLVE, durationUs, alignment = alignment)

    /** Timeline frames whose start lies in `[from, to)`. */
    private fun framesIn(rate: FrameRate, from: Long, to: Long): List<Long> {
        val first = rate.usToFrame(from, FrameRounding.CEIL)
        return generateSequence(first) { it + 1 }.takeWhile { rate.frameToUs(it) < to }.toList()
    }

    @Test
    fun `the window holds exactly the requested frames at every rate, on and off the frame grid`() {
        for (rate in rates) {
            val requested = rate.usToFrame(500_000, FrameRounding.NEAREST).toInt()
            // On the grid, a microsecond after a frame start, and in the middle of a frame.
            for (cut in listOf(rate.frameToUs(90), rate.frameToUs(90) + 1, rate.frameToUs(90) + rate.frameToUs(1) / 2)) {
                for (alignment in TransitionAlignment.entries) {
                    val w = TransitionResolver.resolve(dissolve(alignment = alignment), cut, plenty, plenty, rate)
                    assertEquals("$rate $alignment", requested, w.frames)
                    assertNull(w.limit)
                    val frames = framesIn(rate, w.startUs, w.endUs)
                    assertEquals("$rate $alignment cut=$cut", w.frames, frames.size)
                    // Lead frames are the ones still showing the outgoing clip on the main track.
                    assertEquals(w.leadFrames, frames.count { rate.frameToUs(it) < cut })
                    assertEquals(w.trailFrames, frames.count { rate.frameToUs(it) >= cut })
                    // The frame before the window and the one after it are outside.
                    assertFalse(rate.frameToUs(frames.first() - 1) in w)
                    assertFalse(rate.frameToUs(frames.last() + 1) in w)
                    assertTrue(frames.all { rate.frameToUs(it) in w })
                }
            }
        }
    }

    @Test
    fun `alignment splits the frames around the cut, the odd frame after it`() {
        val rate = FrameRate.FPS_30
        val cut = 3 * sec
        val odd = 15 * sec / 30 // 0.5 s = 15 frames
        val centred = TransitionResolver.resolve(dissolve(odd), cut, plenty, plenty, rate)
        assertEquals(7, centred.leadFrames)
        assertEquals(8, centred.trailFrames)
        val before = TransitionResolver.resolve(dissolve(odd, TransitionAlignment.BEFORE_CUT), cut, plenty, plenty, rate)
        assertEquals(15, before.leadFrames)
        assertEquals(cut, before.endUs)
        val after = TransitionResolver.resolve(dissolve(odd, TransitionAlignment.AFTER_CUT), cut, plenty, plenty, rate)
        assertEquals(15, after.trailFrames)
        assertEquals(cut, after.startUs)
    }

    @Test
    fun `lengths round to whole frames and never below one`() {
        val rate = FrameRate.FPS_30
        assertEquals(1, TransitionResolver.resolve(dissolve(Transition.MIN_DURATION_US), 3 * sec, plenty, plenty, rate).requestedFrames)
        assertEquals(15, TransitionResolver.resolve(dissolve(495_000), 3 * sec, plenty, plenty, rate).requestedFrames)
        assertEquals(150, TransitionResolver.resolve(dissolve(Transition.MAX_DURATION_US), 3 * sec, plenty, plenty, rate).requestedFrames)
    }

    @Test
    fun `without handles an overlapping transition is a plain cut, a centred fade is not`() {
        val rate = FrameRate.FPS_30
        val noMedia = CutSide(5 * sec, 0)
        val w = TransitionResolver.resolve(dissolve(), 5 * sec, noMedia, noMedia, rate)
        assertEquals(0, w.frames)
        assertFalse(w.isActive)
        assertEquals(TransitionLimit.MEDIA_HANDLES, w.limit)

        val fade = TransitionResolver.resolve(Transition(TransitionType.FADE), 5 * sec, noMedia, noMedia, rate)
        assertEquals(15, fade.frames)
        assertNull(fade.limit)
        assertEquals(0, fade.outgoingHandleUs)
        assertEquals(0, fade.incomingHandleUs)
        assertEquals(fade.cutUs, fade.midUs)

        // A fade that ends at the cut needs the incoming clip from its middle on.
        val early = TransitionResolver.resolve(Transition(TransitionType.FADE, alignment = TransitionAlignment.BEFORE_CUT), 5 * sec, noMedia, noMedia, rate)
        assertEquals(TransitionLimit.MEDIA_HANDLES, early.limit)
    }

    @Test
    fun `photos have unlimited handles`() {
        val rate = FrameRate.FPS_30
        val photo = CutSide(3 * sec, null)
        val w = TransitionResolver.resolve(dissolve(sec), 3 * sec, photo, photo, rate)
        assertEquals(30, w.frames)
        assertNull(w.limit)
    }

    @Test
    fun `short handles shorten the transition, keeping its alignment, to the longest that fits`() {
        val rate = FrameRate.FPS_30
        val frame = rate.frameToUs(1)
        // The outgoing clip has 4 frames after its out-point; the incoming one plenty before.
        val w = TransitionResolver.resolve(dissolve(sec), 5 * sec, CutSide(5 * sec, 4 * frame), plenty, rate)
        assertEquals(TransitionLimit.MEDIA_HANDLES, w.limit)
        assertEquals(8, w.frames)
        assertEquals(4, w.trailFrames)
        assertTrue(w.outgoingHandleUs <= 4 * frame)
        // One frame more would need more than there is.
        val longer = TransitionResolver.resolve(dissolve(9 * frame), 5 * sec, plenty, plenty, rate)
        assertTrue(longer.outgoingHandleUs > 4 * frame)
    }

    @Test
    fun `a transition uses at most half of each clip`() {
        val rate = FrameRate.FPS_30
        val cut = rate.frameToUs(10)
        // Room for 5 frames before the cut (half of 10) and 3 after it (half of 6).
        val w = TransitionResolver.resolve(dissolve(2 * sec), cut, CutSide(rate.frameToUs(10), 10 * sec), CutSide(rate.frameToUs(6), 10 * sec), rate)
        assertEquals(TransitionLimit.CLIP_LENGTH, w.limit)
        assertTrue((w.cutUs - w.startUs) * 2 <= rate.frameToUs(10))
        assertTrue((w.endUs - w.cutUs) * 2 <= rate.frameToUs(6))
        // Centred, 6 frames is 3 + 3; 7 would put 4 after the cut.
        assertEquals(6, w.frames)
    }

    @Test
    fun `a one-frame clip leaves no room on its side`() {
        val rate = FrameRate.FPS_30
        val oneFrame = CutSide(rate.frameToUs(1), 10 * sec)
        // All before the cut: the one-frame incoming clip isn't touched.
        val before = TransitionResolver.resolve(dissolve(sec, TransitionAlignment.BEFORE_CUT), 5 * sec, plenty, oneFrame, rate)
        assertEquals(30, before.frames)
        for (alignment in listOf(TransitionAlignment.CENTER, TransitionAlignment.AFTER_CUT)) {
            val w = TransitionResolver.resolve(dissolve(sec, alignment), 5 * sec, plenty, oneFrame, rate)
            assertFalse(w.isActive)
            assertEquals(TransitionLimit.CLIP_LENGTH, w.limit)
        }
    }

    @Test
    fun `handle stretches are at least one frame long`() {
        for (rate in rates) {
            val frame = rate.frameToUs(1)
            // A cut a microsecond after a frame start: the one lead frame starts just before the cut, yet a whole frame of handle is read.
            val cut = rate.frameToUs(60) + 1
            val w = TransitionResolver.resolve(dissolve(2 * frame), cut, plenty, plenty, rate)
            assertEquals(1, w.leadFrames)
            assertTrue(w.incomingHandleUs >= frame)
            assertTrue(w.outgoingHandleUs >= frame)
            assertTrue(w.incomingHandleUs >= w.cutUs - w.incomingFromUs)
            assertTrue(w.outgoingHandleUs >= w.outgoingUntilUs - w.cutUs)
        }
    }

    @Test
    fun `progress spreads the window's frames evenly and symmetrically`() {
        val rate = FrameRate.FPS_30
        val w = TransitionResolver.resolve(dissolve(10 * rate.frameToUs(1)), 3 * sec, plenty, plenty, rate)
        val frames = framesIn(rate, w.startUs, w.endUs)
        val progress = frames.map { w.progress(rate.frameToUs(it)) }
        progress.forEachIndexed { i, p -> assertEquals((i + 1.0) / (frames.size + 1), p, 1e-3) }
        assertEquals(0.0, w.progress(w.startUs - 1), 0.0)
        assertEquals(1.0, w.progress(w.endUs), 0.0)
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
    }

    @Test
    fun `random cuts always get the longest transition that fits`() {
        val random = Random(7)
        repeat(3_000) {
            val rate = rates.random(random)
            val frame = rate.frameToUs(1)
            val cut = random.nextLong(20 * sec, 60 * sec)
            val outgoing = CutSide(random.nextLong(frame, 4 * sec), if (random.nextInt(4) == 0) null else random.nextLong(0, sec))
            val incoming = CutSide(random.nextLong(frame, 4 * sec), if (random.nextInt(4) == 0) null else random.nextLong(0, sec))
            val transition = Transition(
                type = TransitionType.entries.random(random),
                durationUs = random.nextLong(Transition.MIN_DURATION_US, 2 * sec),
                alignment = TransitionAlignment.entries.random(random),
            )
            val w = TransitionResolver.resolve(transition, cut, outgoing, incoming, rate)
            assertTrue(w.frames in 0..w.requestedFrames)
            assertEquals(w.frames, w.leadFrames + w.trailFrames)
            assertEquals(w.frames < w.requestedFrames, w.limit != null)
            if (w.isActive) {
                assertTrue(fits(w, outgoing, incoming))
                assertEquals(w.frames, framesIn(rate, w.startUs, w.endUs).size)
            }
            if (w.frames < w.requestedFrames) {
                // One frame more breaks a rule.
                val more = TransitionResolver.resolve(transition.copy(durationUs = rate.frameToUs(w.frames + 1L)), cut, CutSide(10 * sec, null), CutSide(10 * sec, null), rate)
                assertFalse(fits(more, outgoing, incoming))
            }
        }
    }

    private fun fits(w: TransitionWindow, outgoing: CutSide, incoming: CutSide): Boolean =
        (w.cutUs - w.startUs) * 2 <= outgoing.durationUs &&
            (w.endUs - w.cutUs) * 2 <= incoming.durationUs &&
            (outgoing.handleUs == null || w.outgoingHandleUs <= outgoing.handleUs) &&
            (incoming.handleUs == null || w.incomingHandleUs <= incoming.handleUs)
}
