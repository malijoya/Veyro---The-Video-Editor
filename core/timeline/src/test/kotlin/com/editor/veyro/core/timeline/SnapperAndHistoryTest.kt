package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapperAndHistoryTest {

    private val sec = 1_000_000L

    @Test
    fun `snaps to the nearest target within the threshold`() {
        val snap = Snapper.snap(4_960_000, targets = setOf(0, 5 * sec, 9 * sec), thresholdUs = 100_000, frameRate = FrameRate.FPS_30)
        assertEquals(Snap(5 * sec, 5 * sec), snap)
    }

    @Test
    fun `outside the threshold it rounds to the frame grid`() {
        // 30 fps: frames start at 0, 33 334, 66 667, 100 000 us...
        val snap = Snapper.snap(50_000, targets = setOf(5 * sec), thresholdUs = 10_000, frameRate = FrameRate.FPS_30)
        assertEquals(66_667L, snap.timeUs)
        assertNull(snap.guideUs)
        assertEquals(33_334L, Snapper.snap(40_000, emptySet(), 0, FrameRate.FPS_30).timeUs)
    }

    @Test
    fun `frame snapping honours NTSC and film rates`() {
        val ntsc = FrameRate.FPS_29_97
        val snapped = Snapper.snap(1_001_000_000 + 10, emptySet(), 0, ntsc).timeUs
        assertEquals(1_001_000_000L, snapped) // Exactly frame 30000.
        assertEquals(30_000L, ntsc.usToFrame(snapped))

        val film = FrameRate.FPS_23_976
        val time = Snapper.snap(2_100_000, emptySet(), 0, film).timeUs
        assertEquals(time, film.frameToUs(film.usToFrame(time))) // On a frame boundary.
    }

    @Test
    fun `snapped times never go negative`() {
        assertEquals(0L, Snapper.snap(-500_000, emptySet(), 0, FrameRate.FPS_25).timeUs)
    }

    @Test
    fun `targets are other clips' edges, the playhead and zero`() {
        val timeline = Timeline(
            listOf(
                Track(TrackId("v"), TrackKind.VIDEO, listOf(
                    Clip(ClipId("a"), MediaId("m"), 0, 0, 2 * sec),
                    Clip(ClipId("b"), MediaId("m"), 2 * sec, 0, 3 * sec),
                )),
                Track(TrackId("s"), TrackKind.AUDIO, listOf(Clip(ClipId("c"), MediaId("m"), 7 * sec, 0, 1 * sec))),
            ),
        )
        assertEquals(setOf(0L, 2 * sec, 7 * sec, 8 * sec, 4_200_000L), Snapper.targets(timeline, excluding = ClipId("b"), playheadUs = 4_200_000))
        assertEquals(setOf(0L, 2 * sec, 5 * sec, 7 * sec, 8 * sec), Snapper.targets(timeline, excluding = null, playheadUs = null))
    }

    @Test
    fun `history undoes and redoes in order and a new edit clears redo`() {
        val t0 = Timeline(emptyList(), FrameRate.FPS_24)
        val t1 = t0.copy(frameRate = FrameRate.FPS_25)
        val t2 = t0.copy(frameRate = FrameRate.FPS_30)

        var history = EditHistory().record(t0).record(t1) // Current is t2.
        val (back1, h1) = history.undo(t2)!!
        assertEquals(t1, back1)
        val (back0, h0) = h1.undo(back1)!!
        assertEquals(t0, back0)
        assertFalse(h0.canUndo)
        assertNull(h0.undo(back0))

        val (forward1, hf) = h0.redo(back0)!!
        assertEquals(t1, forward1)
        assertTrue(hf.canRedo)

        history = hf.record(forward1) // A new edit after undo.
        assertFalse(history.canRedo)
    }

    @Test
    fun `history keeps only the latest steps`() {
        var history = EditHistory(limit = 3)
        repeat(10) { history = history.record(Timeline(emptyList(), FrameRate.of(it + 1L))) }
        assertEquals(3, history.past.size)
        assertEquals(FrameRate.of(10), history.past.last().frameRate)
    }
}
