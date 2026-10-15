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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapperTest {

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
    fun `ties go to the earlier target whatever the order`() {
        val forward = Snapper.snap(5 * sec, listOf(4 * sec, 6 * sec), thresholdUs = sec, frameRate = FrameRate.FPS_30)
        val backward = Snapper.snap(5 * sec, listOf(6 * sec, 4 * sec), thresholdUs = sec, frameRate = FrameRate.FPS_30)
        assertEquals(Snap(4 * sec, 4 * sec), forward)
        assertEquals(forward, backward)
    }

    // ---------------- Threshold and zoom ----------------

    @Test
    fun `the threshold is a fixed screen distance converted at the current zoom`() {
        // 12 px at 60 px/s = 200 ms; zoomed in 10x it covers a tenth of the time.
        assertEquals(200_000L, Snapper.thresholdUs(12f, 60f, FrameRate.FPS_30))
        assertEquals(20_000L, Snapper.thresholdUs(12f, 600f, FrameRate.FPS_30))
        assertEquals(48_000_000L, Snapper.thresholdUs(12f, 0.25f, FrameRate.FPS_30))
    }

    @Test
    fun `the threshold never drops below half a frame`() {
        // 12 px at 2400 px/s is 5 ms, under half a 30 fps frame (16 667 us).
        assertEquals(FrameRate.FPS_30.frameToUs(1) / 2, Snapper.thresholdUs(12f, 2_400f, FrameRate.FPS_30))
        assertEquals(FrameRate.FPS_23_976.frameToUs(1) / 2, Snapper.thresholdUs(0f, 60f, FrameRate.FPS_23_976))
    }

    @Test
    fun `a drag the same number of pixels from a target snaps the same at every zoom`() {
        val target = 7_000_000L
        val distancePx = 12f
        for (pxPerSecond in listOf(0.25f, 1f, 15f, 60f, 240f, 600f)) {
            val threshold = Snapper.thresholdUs(distancePx, pxPerSecond, FrameRate.FPS_30)
            val usPerPx = 1_000_000.0 / pxPerSecond
            for (px in listOf(1.0, 6.0, 11.9)) {
                val near = target - (px * usPerPx).toLong()
                assertEquals("$px px at $pxPerSecond px/s", target, Snapper.snap(near, setOf(target), threshold, FrameRate.FPS_30).guideUs)
                val after = target + (px * usPerPx).toLong()
                assertEquals("$px px after at $pxPerSecond px/s", target, Snapper.snap(after, setOf(target), threshold, FrameRate.FPS_30).guideUs)
            }
            for (px in listOf(12.1, 20.0, 100.0)) {
                val far = target + (px * usPerPx).toLong()
                assertNull("$px px at $pxPerSecond px/s", Snapper.snap(far, setOf(target), threshold, FrameRate.FPS_30).guideUs)
            }
        }
    }

    @Test
    fun `an off-grid target is reachable when zoomed all the way in`() {
        // A clip end 10 ms past a 30 fps frame boundary. Grid rounding alone would never land on it.
        val target = 1_010_000L
        val threshold = Snapper.thresholdUs(12f, 2_400f, FrameRate.FPS_30)
        assertEquals(target, Snapper.snap(1_000_000, setOf(target), threshold, FrameRate.FPS_30).timeUs)
    }

    // ---------------- Moving a whole clip ----------------

    @Test
    fun `a moved clip snaps by its end when the end is closer`() {
        // A 2 s clip dragged to start at 2.9 s: its start is 100 ms from 3 s, its end 50 ms from 5 s.
        val snap = Snapper.snapClip(2_900_000, 2 * sec, setOf(3 * sec, 4_950_000), thresholdUs = 150_000, frameRate = FrameRate.FPS_30)
        assertEquals(Snap(2_950_000, 4_950_000), snap)
    }

    @Test
    fun `a moved clip prefers its start on a tie and falls back to the grid`() {
        val tie = Snapper.snapClip(2_900_000, 2 * sec, setOf(3 * sec, 5 * sec), thresholdUs = 150_000, frameRate = FrameRate.FPS_30)
        assertEquals(Snap(3 * sec, 3 * sec), tie)
        val none = Snapper.snapClip(2_910_000, 2 * sec, setOf(9 * sec), thresholdUs = 150_000, frameRate = FrameRate.FPS_30)
        assertEquals(Snap(FrameRate.FPS_30.snapUs(2_910_000), null), none)
    }

    @Test
    fun `a moved clip never snaps its end where its start would go negative`() {
        val snap = Snapper.snapClip(100_000, 2 * sec, setOf(1 * sec), thresholdUs = 2 * sec, frameRate = FrameRate.FPS_30)
        assertTrue(snap.timeUs >= 0)
        assertEquals(Snap(0, 0), Snapper.snapClip(100_000, 2 * sec, setOf(0, 1 * sec), thresholdUs = 2 * sec, frameRate = FrameRate.FPS_30))
    }
}
