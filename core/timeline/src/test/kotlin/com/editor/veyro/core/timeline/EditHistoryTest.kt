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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private val main = TrackId("main")
    private val audio = TrackId("audio")
    private val sec = 1_000_000L
    private var nextId = 0

    private val media = mapOf(
        MediaId("v10") to MediaBounds(MediaKind.VIDEO, 10 * sec),
        MediaId("v6") to MediaBounds(MediaKind.VIDEO, 6 * sec),
        MediaId("img") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 30 * sec),
    )
    private val context = EditContext(media) { ClipId("new-${nextId++}") }

    private fun clip(id: String, media: String, start: Long, sourceIn: Long, duration: Long) =
        Clip(ClipId(id), MediaId(media), start, sourceIn, duration)

    /** Main: A [0..6 s), B photo [6..9 s), C [9..15 s). Audio: S [1..11 s), T [14..19 s). */
    private fun timeline() = Timeline(
        tracks = listOf(
            Track(main, TrackKind.VIDEO, listOf(clip("A", "v10", 0, 2 * sec, 6 * sec), clip("B", "img", 6 * sec, 0, 3 * sec), clip("C", "v6", 9 * sec, 0, 6 * sec))),
            Track(audio, TrackKind.AUDIO, listOf(clip("S", "song", 1 * sec, 0, 10 * sec), clip("T", "song", 14 * sec, 10 * sec, 5 * sec))),
        ),
        frameRate = FrameRate.FPS_25,
    )

    /** An editing session: the engine applies commands, and the history records each applied one. */
    private class Session(var timeline: Timeline, var history: EditHistory = EditHistory()) {
        fun edit(command: EditCommand, context: EditContext): EditResult {
            val result = TimelineEditor.apply(timeline, command, context)
            if (result is EditResult.Applied) {
                history = history.record(command, timeline, result.timeline)
                timeline = result.timeline
            }
            return result
        }

        fun undo(): HistoryStep = history.undo(timeline).also(::take)
        fun redo(): HistoryStep = history.redo(timeline).also(::take)

        private fun take(step: HistoryStep) {
            when (step) {
                is HistoryStep.Moved -> {
                    timeline = step.timeline
                    history = step.history
                }
                is HistoryStep.Conflict -> history = step.history
                HistoryStep.Empty -> Unit
            }
        }
    }

    /** Trim, split, move, reorder, delete (both kinds) and close gaps, on both tracks. */
    private val sequence = listOf(
        EditCommand.TrimStart(ClipId("A"), 1 * sec),
        EditCommand.TrimEnd(ClipId("T"), 17 * sec),
        EditCommand.Split(ClipId("C"), 10 * sec),
        EditCommand.Move(ClipId("C"), 0), // Reorder on the magnetic track.
        EditCommand.Move(ClipId("T"), 12 * sec), // Free move on the audio track.
        EditCommand.Delete(ClipId("B"), closeGap = true),
        EditCommand.Delete(ClipId("S"), closeGap = false),
        EditCommand.CloseGaps(audio),
        EditCommand.TrimEnd(ClipId("A"), 2 * sec),
    )

    // ---------------- Sequential edits ----------------

    @Test
    fun `each undo restores exactly the timeline before that edit, and each redo the one after`() {
        val session = Session(timeline())
        val states = mutableListOf(session.timeline)
        sequence.forEach { command ->
            assertTrue("$command", session.edit(command, context) is EditResult.Applied)
            states += session.timeline
        }
        assertEquals(sequence.size, session.history.past.size)

        for (i in sequence.indices.reversed()) {
            val step = session.undo() as HistoryStep.Moved
            assertEquals(sequence[i], step.edit.command)
            assertEquals("after undoing ${sequence[i]}", states[i], session.timeline)
        }
        for (i in sequence.indices) {
            val step = session.redo() as HistoryStep.Moved
            assertEquals(sequence[i], step.edit.command)
            assertEquals("after redoing ${sequence[i]}", states[i + 1], session.timeline)
        }
    }

    @Test
    fun `undo all returns to the original and redo all to the final timeline`() {
        val original = timeline()
        val session = Session(original)
        sequence.forEach { session.edit(it, context) }
        val final = session.timeline

        while (session.history.canUndo) session.undo()
        assertEquals(original, session.timeline)
        assertFalse(session.history.canUndo)
        assertEquals(sequence.size, session.history.future.size)

        while (session.history.canRedo) session.redo()
        assertEquals(final, session.timeline)
        assertFalse(session.history.canRedo)
    }

    @Test
    fun `redo of a split restores the same clip ID without creating a new one`() {
        val session = Session(timeline())
        session.edit(EditCommand.Split(ClipId("A"), 3 * sec), context)
        session.edit(EditCommand.TrimEnd(ClipId("new-0"), 5 * sec), context) // Edits the split-off part by its ID.
        val idsIssued = nextId

        session.undo()
        session.undo()
        session.redo()
        session.redo() // Works only if the split brought back "new-0".
        assertEquals(idsIssued, nextId)
        assertEquals(2 * sec, session.timeline.findClip(ClipId("new-0"))!!.durationUs)
    }

    @Test
    fun `a new edit after undo clears redo`() {
        val session = Session(timeline())
        session.edit(EditCommand.TrimEnd(ClipId("A"), 5 * sec), context)
        session.undo()
        assertTrue(session.history.canRedo)
        session.edit(EditCommand.Delete(ClipId("T"), closeGap = false), context)
        assertFalse(session.history.canRedo)
        assertEquals(HistoryStep.Empty, session.redo())
    }

    // ---------------- Invalid operations ----------------

    @Test
    fun `undo and redo with nothing to do change nothing`() {
        val t = timeline()
        assertEquals(HistoryStep.Empty, EditHistory().undo(t))
        assertEquals(HistoryStep.Empty, EditHistory().redo(t))

        val session = Session(t)
        session.edit(EditCommand.Split(ClipId("A"), 2 * sec), context)
        session.undo()
        assertEquals(HistoryStep.Empty, session.undo()) // Past the start.
        assertEquals(t, session.timeline)
        session.redo()
        assertEquals(HistoryStep.Empty, session.redo()) // Past the end.
    }

    @Test
    fun `rejected and no-op edits are not recorded`() {
        val session = Session(timeline())
        assertTrue(session.edit(EditCommand.Split(ClipId("missing"), sec), context) is EditResult.Rejected)
        assertTrue(session.edit(EditCommand.Split(ClipId("A"), 20 * sec), context) is EditResult.Rejected)
        assertTrue(session.edit(EditCommand.CloseGaps(main), context) is EditResult.Rejected)
        // Valid, but clamped to where the clip already is: nothing changes.
        assertTrue(session.edit(EditCommand.TrimStart(ClipId("S"), 0), context) is EditResult.Applied)
        assertFalse(session.history.canUndo)
    }

    @Test
    fun `undo on a timeline changed outside the history is refused and leaves it untouched`() {
        val session = Session(timeline())
        session.edit(EditCommand.TrimEnd(ClipId("T"), 16 * sec), context)
        session.edit(EditCommand.Split(ClipId("S"), 5 * sec), context)

        // Someone else deletes the split-off part.
        val changed = session.timeline.let { t -> t.withTrack(t.track(audio)!!.let { it.copy(clips = it.clips.filterNot { c -> c.id == ClipId("new-0") }) }) }
        session.timeline = changed
        val step = session.undo()
        assertTrue(step is HistoryStep.Conflict)
        assertEquals(changed, session.timeline)
        assertFalse(session.history.canUndo) // Stale undo steps are dropped...
        assertEquals(HistoryStep.Empty, session.undo()) // ...so they can't half-apply later.
    }

    @Test
    fun `redo is refused when it would overlap a clip added since`() {
        val session = Session(timeline())
        session.edit(EditCommand.Move(ClipId("T"), 11 * sec), context) // T now [11..16 s).
        session.undo() // T back at [14..19 s).
        // Another writer puts a clip where T's redo would land.
        val blocker = clip("X", "song", 11 * sec, 0, 2 * sec)
        session.timeline = session.timeline.withTrack(session.timeline.track(audio)!!.let { it.copy(clips = (it.clips + blocker).sortedBy { c -> c.startUs }) })
        val before = session.timeline
        assertTrue(session.redo() is HistoryStep.Conflict)
        assertEquals(before, session.timeline)
        assertFalse(session.history.canRedo)
    }

    @Test
    fun `a patch never leaves a gap on the magnetic track`() {
        val t = timeline()
        // Shortening the last main-track clip, C, from [9..15 s) to [9..12 s) stores only C.
        val trimmed = (TimelineEditor.apply(t, EditCommand.TrimEnd(ClipId("C"), 12 * sec), context) as EditResult.Applied).timeline
        val patch = TimelinePatch.between(t, trimmed)
        assertEquals(trimmed, patch.applyTo(t))
        // Someone appended D right after the original C. Shortening C now would leave a gap before D.
        val withD = t.withTrack(t.track(main)!!.let { it.copy(clips = it.clips + clip("D", "v6", 15 * sec, 0, 2 * sec)) })
        assertNull(patch.applyTo(withD))
    }

    @Test
    fun `a patch is refused when a removed clip differs or an added ID is taken`() {
        val t = timeline()
        val split = (TimelineEditor.apply(t, EditCommand.Split(ClipId("S"), 5 * sec), context) as EditResult.Applied).timeline
        val patch = TimelinePatch.between(t, split)
        assertEquals(split, patch.applyTo(t))
        assertNull(patch.applyTo(split)) // Already applied: S is no longer the clip it expects, and new-0 exists.
        val movedS = t.withTrack(t.track(audio)!!.let { it.copy(clips = listOf(it.clips[0].copy(startUs = 0), it.clips[1])) })
        assertNull(patch.applyTo(movedS))
    }

    @Test
    fun `edits that change the track list are recorded and undone exactly`() {
        val t = timeline()
        val fewerTracks = t.copy(tracks = t.tracks.take(1))
        val history = EditHistory().record(EditCommand.CloseGaps(audio), t, fewerTracks)
        val patch = history.past.single().patch
        assertEquals(t.tracks.map { it.id }, patch.layout!!.before.map { it.id })
        assertTrue("Shells carry no clips", patch.layout!!.after.all { it.clips.isEmpty() })

        val undone = history.undo(fewerTracks) as HistoryStep.Moved
        assertEquals(t, undone.timeline)
        val redone = undone.history.redo(undone.timeline) as HistoryStep.Moved
        assertEquals(fewerTracks, redone.timeline)
        // A layout patch only fits the track list it was made from.
        assertNull(patch.applyTo(fewerTracks))
        assertThrows(IllegalArgumentException::class.java) { TrackLayout(t.tracks, emptyList()) }
    }

    // ---------------- Memory ----------------

    @Test
    fun `patches hold only the clips an edit changed`() {
        // 500 clips on a free track: a trim stores the old and new version of one clip.
        val many = Timeline(
            listOf(
                Track(main, TrackKind.VIDEO, emptyList()),
                Track(audio, TrackKind.AUDIO, (0 until 500).map { clip("c$it", "song", it * 2 * sec, 0, sec) }),
            ),
        )
        val trimmed = (TimelineEditor.apply(many, EditCommand.TrimEnd(ClipId("c250"), 501 * sec + 500_000), context) as EditResult.Applied).timeline
        val history = EditHistory().record(EditCommand.TrimEnd(ClipId("c250"), 501 * sec + 500_000), many, trimmed)
        assertEquals(2, history.clipRecordCount)
        val patch = history.past.single().patch
        assertEquals(listOf(audio), patch.tracks.map { it.trackId }) // The untouched main track isn't stored.
        assertSame(many.findClip(ClipId("c250")), patch.tracks.single().removed.single()) // Shared, not copied.
    }

    @Test
    fun `a magnetic trim stores the trimmed clip and the clips it shifted`() {
        val t = timeline()
        val trimmed = (TimelineEditor.apply(t, EditCommand.TrimEnd(ClipId("B"), 8 * sec), context) as EditResult.Applied).timeline
        val patch = TimelinePatch.between(t, trimmed)
        // B changed and C shifted left; A is unchanged.
        assertEquals(listOf("B", "C"), patch.tracks.single().removed.map { it.id.value })
        assertEquals(4, patch.clipRecordCount)
    }

    @Test
    fun `history keeps only the latest steps`() {
        val session = Session(timeline(), EditHistory(limit = 3))
        repeat(10) { i -> session.edit(EditCommand.TrimEnd(ClipId("T"), 19 * sec - (i + 1) * 100_000), context) }
        assertEquals(3, session.history.past.size)
        repeat(3) { session.undo() }
        assertEquals(18_300_000L, session.timeline.findClip(ClipId("T"))!!.endUs) // Back to after the 7th edit, no further.
        assertFalse(session.history.canUndo)
    }

    @Test
    fun `history drops the oldest steps to stay within its clip-record budget`() {
        // Every main-track trim of A shifts B and C, so each step stores 6 records.
        val session = Session(timeline(), EditHistory(maxClipRecords = 20))
        repeat(8) { i -> session.edit(EditCommand.TrimEnd(ClipId("A"), 6 * sec - (i + 1) * 200_000), context) }
        assertTrue(session.history.clipRecordCount <= 20)
        assertEquals(3, session.history.past.size)
        // The newest step always survives, even alone over budget.
        val tiny = Session(timeline(), EditHistory(maxClipRecords = 1))
        tiny.edit(EditCommand.TrimEnd(ClipId("A"), 5 * sec), context)
        assertTrue(tiny.history.canUndo)
    }

    @Test
    fun `a long random session undoes and redoes exactly`() {
        val random = java.util.Random(7)
        val original = timeline()
        val session = Session(original, EditHistory(limit = 1_000, maxClipRecords = 100_000))
        val states = mutableListOf(original)
        repeat(300) {
            val clips = session.timeline.tracks.flatMap { it.clips }
            if (clips.isEmpty()) return@repeat
            val target = clips[random.nextInt(clips.size)]
            val time = (random.nextDouble() * 30 * sec).toLong()
            val command = when (random.nextInt(5)) {
                0 -> EditCommand.TrimStart(target.id, time)
                1 -> EditCommand.TrimEnd(target.id, time)
                2 -> EditCommand.Split(target.id, target.startUs + (random.nextDouble() * target.durationUs).toLong())
                3 -> EditCommand.Move(target.id, time)
                else -> if (clips.size > 3) EditCommand.Delete(target.id, random.nextBoolean()) else EditCommand.TrimEnd(target.id, time)
            }
            val before = session.timeline
            session.edit(command, context)
            if (session.timeline != before) states += session.timeline
        }
        assertEquals(states.size - 1, session.history.past.size)
        assertNotEquals(original, session.timeline)

        for (i in states.indices.reversed().drop(1)) {
            assertTrue(session.undo() is HistoryStep.Moved)
            assertEquals(states[i], session.timeline)
        }
        assertEquals(original, session.timeline)
        for (i in 1 until states.size) {
            assertTrue(session.redo() is HistoryStep.Moved)
            assertEquals(states[i], session.timeline)
        }
    }
}
