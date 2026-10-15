package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditorTest {

    private val main = TrackId("main")
    private val audio = TrackId("audio")
    private var nextId = 0
    private val sec = 1_000_000L

    /** Media: a 10 s video, a 6 s video, a photo, a 30 s song. */
    private val media = mapOf(
        MediaId("v10") to MediaBounds(MediaKind.VIDEO, 10 * sec),
        MediaId("v6") to MediaBounds(MediaKind.VIDEO, 6 * sec),
        MediaId("img") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 30 * sec),
    )
    private val context = EditContext(media) { ClipId("new-${nextId++}") }

    private fun clip(id: String, media: String, start: Long, sourceIn: Long, duration: Long) =
        Clip(ClipId(id), MediaId(media), start, sourceIn, duration)

    /**
     * Main track: A = v10 [2 s..8 s) at 0, B = img 3 s at 6 s, C = v6 [0..6 s) at 9 s.
     * Audio track: S = song [0..10 s) at 1 s, T = song [10..15 s) at 14 s (gap 11..14 s).
     */
    private fun timeline(frameRate: FrameRate = FrameRate.FPS_25) = Timeline(
        tracks = listOf(
            Track(
                main, TrackKind.VIDEO,
                listOf(
                    clip("A", "v10", 0, 2 * sec, 6 * sec),
                    clip("B", "img", 6 * sec, 0, 3 * sec),
                    clip("C", "v6", 9 * sec, 0, 6 * sec),
                ),
            ),
            Track(
                audio, TrackKind.AUDIO,
                listOf(
                    clip("S", "song", 1 * sec, 0, 10 * sec),
                    clip("T", "song", 14 * sec, 10 * sec, 5 * sec),
                ),
            ),
        ),
        frameRate = frameRate,
    )

    private fun apply(timeline: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(timeline, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(timeline: Timeline, command: EditCommand): EditRejection =
        (TimelineEditor.apply(timeline, command, context) as EditResult.Rejected).reason

    private fun Timeline.clip(id: String) = findClip(ClipId(id))!!
    private fun Timeline.ids(track: TrackId) = track(track)!!.clips.map { it.id.value }

    /** Main-track invariant: packed from 0 with no gaps. */
    private fun assertPacked(timeline: Timeline) {
        var cursor = 0L
        timeline.track(main)!!.clips.forEach {
            assertEquals("${it.id.value} start", cursor, it.startUs)
            cursor = it.endUs
        }
    }

    // ---------------- Trim start ----------------

    @Test
    fun `trim start on the main track moves the source in-point and repacks`() {
        val result = apply(timeline(), EditCommand.TrimStart(ClipId("A"), newStartUs = 1 * sec))
        val a = result.clip("A")
        assertEquals(0L, a.startUs) // The left edge stays put on the magnetic track.
        assertEquals(3 * sec, a.sourceInUs)
        assertEquals(5 * sec, a.durationUs)
        assertEquals(8 * sec, a.sourceOutUs) // The out-point is unchanged.
        assertEquals(5 * sec, result.clip("B").startUs) // Later clips close up.
        assertPacked(result)
    }

    @Test
    fun `trim start cannot reveal footage before the file starts`() {
        // A starts 2 s into its file. Asking to extend 5 s stops at 2 s.
        val result = apply(timeline(), EditCommand.TrimStart(ClipId("A"), newStartUs = -5 * sec))
        val a = result.clip("A")
        assertEquals(0L, a.sourceInUs)
        assertEquals(8 * sec, a.durationUs)
        assertPacked(result)
    }

    @Test
    fun `trim start keeps at least one frame`() {
        val t = timeline(FrameRate.FPS_25) // One frame = 40 ms.
        val a = apply(t, EditCommand.TrimStart(ClipId("A"), newStartUs = 100 * sec)).clip("A")
        assertEquals(40_000L, a.durationUs)
        assertEquals(8 * sec - 40_000, a.sourceInUs)
    }

    @Test
    fun `trim start on a free track moves the clip edge and stops at the neighbour`() {
        // T starts at 14 s, 10 s into the song. S ends at 11 s, so T's left edge can't go below 11 s.
        val result = apply(timeline(), EditCommand.TrimStart(ClipId("T"), newStartUs = 9 * sec))
        val t = result.clip("T")
        assertEquals(11 * sec, t.startUs)
        assertEquals(7 * sec, t.sourceInUs)
        assertEquals(8 * sec, t.durationUs)
        assertEquals(19 * sec, t.endUs) // The right edge didn't move.
        assertEquals(1 * sec, result.clip("S").startUs) // Neighbours are untouched.
    }

    @Test
    fun `trim start on a free track cannot go before time zero`() {
        val result = apply(timeline(), EditCommand.TrimStart(ClipId("S"), newStartUs = -3 * sec))
        val s = result.clip("S")
        // S has no source before 0, so it can't extend left at all.
        assertEquals(1 * sec, s.startUs)
        assertEquals(0L, s.sourceInUs)
    }

    @Test
    fun `photos can be lengthened at either edge without a source limit`() {
        val longer = apply(timeline(), EditCommand.TrimStart(ClipId("B"), newStartUs = 2 * sec)).clip("B")
        assertEquals(7 * sec, longer.durationUs) // 4 s earlier: 3 s becomes 7 s.
        assertEquals(0L, longer.sourceInUs)
        val muchLonger = apply(timeline(), EditCommand.TrimEnd(ClipId("B"), newEndUs = 600 * sec)).clip("B")
        assertEquals(594 * sec, muchLonger.durationUs)
    }

    // ---------------- Trim end ----------------

    @Test
    fun `trim end stops at the end of the source file`() {
        // A plays 2..8 s of a 10 s file: it can grow by at most 2 s.
        val result = apply(timeline(), EditCommand.TrimEnd(ClipId("A"), newEndUs = 20 * sec))
        assertEquals(8 * sec, result.clip("A").durationUs)
        assertEquals(10 * sec, result.clip("A").sourceOutUs)
        assertEquals(8 * sec, result.clip("B").startUs)
        assertPacked(result)
    }

    @Test
    fun `trim end shortens and keeps at least one frame`() {
        val shorter = apply(timeline(), EditCommand.TrimEnd(ClipId("C"), newEndUs = 11 * sec)).clip("C")
        assertEquals(2 * sec, shorter.durationUs)
        val minimal = apply(timeline(), EditCommand.TrimEnd(ClipId("C"), newEndUs = 0)).clip("C")
        assertEquals(timeline().minClipDurationUs, minimal.durationUs)
        assertTrue(minimal.durationUs > 0)
    }

    @Test
    fun `trim end on a free track stops at the next clip`() {
        val s = apply(timeline(), EditCommand.TrimEnd(ClipId("S"), newEndUs = 20 * sec)).clip("S")
        assertEquals(14 * sec, s.endUs) // T starts at 14 s.
        assertEquals(13 * sec, s.sourceOutUs)
    }

    @Test
    fun `durations stay positive for any trim request`() {
        val base = timeline(FrameRate.FPS_29_97)
        for (id in listOf("A", "B", "C", "S", "T")) {
            for (time in listOf(Long.MIN_VALUE / 4, -1L, 0L, 1L, 5 * sec, 50 * sec, Long.MAX_VALUE / 4)) {
                listOf(EditCommand.TrimStart(ClipId(id), time), EditCommand.TrimEnd(ClipId(id), time)).forEach { command ->
                    val result = apply(base, command)
                    result.tracks.forEach { track -> track.clips.forEach { assertTrue("$command -> $it", it.durationUs >= base.minClipDurationUs) } }
                    assertPacked(result)
                }
            }
        }
    }

    // ---------------- Split ----------------

    @Test
    fun `split preserves the source range and timeline continuity`() {
        val result = apply(timeline(), EditCommand.Split(ClipId("A"), atUs = 2_500_000))
        val (left, right) = result.track(main)!!.clips.take(2)
        assertEquals("A", left.id.value)
        assertEquals("new-0", right.id.value)
        assertEquals(left.endUs, right.startUs) // No gap on the timeline.
        assertEquals(left.sourceOutUs, right.sourceInUs) // No jump in the source.
        assertEquals(2 * sec, left.sourceInUs)
        assertEquals(8 * sec, right.sourceOutUs) // The original out-point.
        assertEquals(6 * sec, left.durationUs + right.durationUs) // Total length unchanged.
        assertEquals(timeline().durationUs, result.durationUs)
        assertPacked(result)
    }

    @Test
    fun `split reports the right-hand part as affected`() {
        val result = TimelineEditor.apply(timeline(), EditCommand.Split(ClipId("C"), 12 * sec), context) as EditResult.Applied
        assertEquals(ClipId("new-0"), result.affectedClipId)
        assertEquals(listOf("A", "B", "C", "new-0"), result.timeline.ids(main))
    }

    @Test
    fun `split of a photo gives two stills`() {
        val result = apply(timeline(), EditCommand.Split(ClipId("B"), 7 * sec))
        val parts = result.track(main)!!.clips.filter { it.mediaId == MediaId("img") }
        assertEquals(listOf(1 * sec, 2 * sec), parts.map { it.durationUs })
        assertTrue(parts.all { it.sourceInUs == 0L })
    }

    @Test
    fun `split is rejected at or outside clip edges and within a frame of them`() {
        val t = timeline(FrameRate.FPS_25)
        assertEquals(EditRejection.OUTSIDE_CLIP, rejected(t, EditCommand.Split(ClipId("A"), 0)))
        assertEquals(EditRejection.OUTSIDE_CLIP, rejected(t, EditCommand.Split(ClipId("A"), 6 * sec)))
        assertEquals(EditRejection.OUTSIDE_CLIP, rejected(t, EditCommand.Split(ClipId("A"), 7 * sec)))
        assertEquals(EditRejection.TOO_CLOSE_TO_EDGE, rejected(t, EditCommand.Split(ClipId("A"), 39_999)))
        assertEquals(EditRejection.TOO_CLOSE_TO_EDGE, rejected(t, EditCommand.Split(ClipId("A"), 6 * sec - 1)))
        // Exactly one frame in is allowed.
        apply(t, EditCommand.Split(ClipId("A"), 40_000))
    }

    @Test
    fun `split at an NTSC frame boundary keeps both parts frame-aligned`() {
        val ntsc = FrameRate.FPS_29_97
        val t = timeline(ntsc)
        val at = ntsc.frameToUs(75) // 2.5025 s
        val result = apply(t, EditCommand.Split(ClipId("A"), at))
        val (left, right) = result.track(main)!!.clips.take(2)
        assertEquals(75L, ntsc.usToFrame(left.durationUs))
        assertEquals(at, right.startUs)
        assertEquals(left.sourceOutUs, right.sourceInUs)
    }

    // ---------------- Move ----------------

    @Test
    fun `moving on the main track reorders and stays packed`() {
        // Drag C (6 s) to start near 0: it becomes first.
        val first = apply(timeline(), EditCommand.Move(ClipId("C"), toStartUs = 0))
        assertEquals(listOf("C", "A", "B"), first.ids(main))
        assertPacked(first)
        // Drag A to the end.
        val last = apply(timeline(), EditCommand.Move(ClipId("A"), toStartUs = 30 * sec))
        assertEquals(listOf("B", "C", "A"), last.ids(main))
        assertPacked(last)
        // A small drag that doesn't pass a neighbour's middle keeps the order.
        val same = apply(timeline(), EditCommand.Move(ClipId("B"), toStartUs = 6 * sec + 400_000))
        assertEquals(listOf("A", "B", "C"), same.ids(main))
        // Reordering never changes source ranges or total length.
        assertEquals(timeline().track(main)!!.endUs, last.track(main)!!.endUs)
        assertEquals(timeline().clip("A").sourceInUs, last.clip("A").sourceInUs)
    }

    @Test
    fun `moving on a free track keeps gaps and never overlaps`() {
        // With T lifted out, everything after S (from 11 s) is free, so T lands where it was dropped.
        val nudged = apply(timeline(), EditCommand.Move(ClipId("T"), toStartUs = 12 * sec))
        assertEquals(12 * sec, nudged.clip("T").startUs)
        // Dropped overlapping S, it is pushed to the nearest free spot: right after S.
        val overlapping = apply(timeline(), EditCommand.Move(ClipId("T"), toStartUs = 9 * sec))
        assertEquals(11 * sec, overlapping.clip("T").startUs)
        // Dragged far right, it moves there and leaves a gap.
        val far = apply(timeline(), EditCommand.Move(ClipId("T"), toStartUs = 40 * sec))
        assertEquals(40 * sec, far.clip("T").startUs)
        // Dragged before S into the 0..1 s gap (too small), it snaps to the nearest fit after S.
        val early = apply(timeline(), EditCommand.Move(ClipId("T"), toStartUs = 0))
        assertEquals(11 * sec, early.clip("T").startUs)
        // Negative positions clamp to 0 when the clip fits there.
        val s = apply(timeline(), EditCommand.Move(ClipId("S"), toStartUs = -5 * sec))
        assertEquals(0L, s.clip("S").startUs)
    }

    @Test
    fun `nearest free start picks the closest gap that fits`() {
        val others = listOf(clip("x", "song", 2 * sec, 0, 2 * sec), clip("y", "song", 10 * sec, 0, 2 * sec))
        assertEquals(0L, TimelineEditor.nearestFreeStart(others, desired = 0, duration = 2 * sec))
        assertEquals(5 * sec, TimelineEditor.nearestFreeStart(others, desired = 5 * sec, duration = 3 * sec))
        assertEquals(7 * sec, TimelineEditor.nearestFreeStart(others, desired = 9 * sec, duration = 3 * sec))
        assertEquals(12 * sec, TimelineEditor.nearestFreeStart(others, desired = 11 * sec, duration = 7 * sec))
    }

    // ---------------- Delete and gaps ----------------

    @Test
    fun `delete on the main track always closes the gap`() {
        val result = apply(timeline(), EditCommand.Delete(ClipId("B"), closeGap = false))
        assertEquals(listOf("A", "C"), result.ids(main))
        assertEquals(6 * sec, result.clip("C").startUs)
        assertPacked(result)
    }

    @Test
    fun `delete on a free track keeps or closes the gap as asked`() {
        val kept = apply(timeline(), EditCommand.Delete(ClipId("S"), closeGap = false))
        assertEquals(14 * sec, kept.clip("T").startUs)
        val closed = apply(timeline(), EditCommand.Delete(ClipId("S"), closeGap = true))
        assertEquals(4 * sec, closed.clip("T").startUs) // Shifted left by S's 10 s.
    }

    @Test
    fun `close gaps packs a track from zero and reports no change when already packed`() {
        val result = apply(timeline(), EditCommand.CloseGaps(audio))
        assertEquals(listOf(0L, 10 * sec), result.track(audio)!!.clips.map { it.startUs })
        assertFalse(TimelineEditor.hasGaps(result.track(audio)!!))
        assertTrue(TimelineEditor.hasGaps(timeline().track(audio)!!))
        assertEquals(EditRejection.NO_CHANGE, rejected(timeline(), EditCommand.CloseGaps(main)))
        assertEquals(EditRejection.TRACK_NOT_FOUND, rejected(timeline(), EditCommand.CloseGaps(TrackId("nope"))))
    }

    @Test
    fun `unknown clips and media are rejected`() {
        assertEquals(EditRejection.CLIP_NOT_FOUND, rejected(timeline(), EditCommand.TrimEnd(ClipId("zz"), 0)))
        val noMedia = EditContext(emptyMap()) { ClipId("x") }
        val result = TimelineEditor.apply(timeline(), EditCommand.Split(ClipId("A"), 3 * sec), noMedia)
        assertEquals(EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND), result)
    }

    @Test
    fun `effects travel with clips through trim, split and move`() {
        val effect = com.editor.veyro.core.model.timeline.ClipEffect("fx1", "color.brightness", params = mapOf("amount" to 0.2))
        val base = timeline().let { t -> t.withTrack(t.track(main)!!.let { tr -> tr.copy(clips = tr.clips.map { if (it.id.value == "A") it.copy(effects = listOf(effect)) else it }) }) }
        val split = apply(base, EditCommand.Split(ClipId("A"), 3 * sec))
        assertEquals(listOf(effect), split.clip("A").effects)
        assertEquals(listOf(effect), split.clip("new-0").effects)
        val trimmed = apply(split, EditCommand.TrimStart(ClipId("new-0"), 3_500_000))
        assertEquals(listOf(effect), trimmed.clip("new-0").effects)
        val moved = apply(trimmed, EditCommand.Move(ClipId("A"), 30 * sec))
        assertEquals(listOf(effect), moved.clip("A").effects)
    }

    // ---------------- Duration calculations ----------------

    @Test
    fun `timeline duration follows the longest track through edits`() {
        val t = timeline()
        assertEquals(19 * sec, t.durationUs) // T ends at 19 s. Main ends at 15 s.
        assertEquals(15 * sec, t.track(main)!!.endUs)
        val afterDelete = apply(t, EditCommand.Delete(ClipId("T"), closeGap = false))
        assertEquals(15 * sec, afterDelete.durationUs)
        val afterTrim = apply(afterDelete, EditCommand.TrimEnd(ClipId("C"), 10 * sec))
        assertEquals(11 * sec, afterTrim.durationUs) // Main is now 10 s, so the song's 1..11 s sets the length.
    }

    @Test
    fun `random edit sequences keep every invariant`() {
        val random = java.util.Random(42)
        var t = timeline(FrameRate.FPS_23_976)
        repeat(500) {
            val clips = t.tracks.flatMap { it.clips }
            if (clips.isEmpty()) return@repeat
            val target = clips[random.nextInt(clips.size)]
            val time = (random.nextDouble() * 40 * sec).toLong() - 5 * sec
            val command = when (random.nextInt(6)) {
                0 -> EditCommand.TrimStart(target.id, time)
                1 -> EditCommand.TrimEnd(target.id, time)
                2 -> EditCommand.Split(target.id, time)
                3 -> EditCommand.Move(target.id, time)
                4 -> EditCommand.Delete(target.id, random.nextBoolean())
                else -> EditCommand.CloseGaps(t.trackOf(target.id)!!.id)
            }
            val result = TimelineEditor.apply(t, command, context)
            if (result is EditResult.Applied) t = result.timeline
            // Track and Timeline constructors already reject overlaps, disorder and duplicate IDs.
            t.tracks.flatMap { it.clips }.forEach { clip ->
                assertTrue(clip.durationUs > 0)
                assertTrue(clip.startUs >= 0)
                val bounds = media.getValue(clip.mediaId)
                if (!bounds.isStill) {
                    assertTrue("$clip in", clip.sourceInUs >= 0)
                    assertTrue("$clip out", clip.sourceOutUs <= bounds.durationUs)
                }
            }
            assertPacked(t)
        }
    }
}
