package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionAlignment
import com.editor.veyro.core.model.transition.TransitionLimit
import com.editor.veyro.core.model.transition.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transitions through edits: where they can go, how splits, moves, deletes
 * and trims treat them, "Trim to fit", and exact undo.
 */
class TransitionEditTest {

    private val sec = 1_000_000L
    private val rate = FrameRate.FPS_30
    private var nextId = 0

    private val media = mapOf(
        MediaId("cam") to MediaBounds(MediaKind.VIDEO, 20 * sec, hasAudio = true),
        MediaId("photo") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 60 * sec),
    )
    private val context = EditContext(media) { ClipId("c${nextId++}") }
    private val dissolve = Transition(TransitionType.DISSOLVE, sec)

    /** Main: A (whole file, no handles), B (source 2..7 s), a photo. Music under it. */
    private fun timeline() = Timeline(
        listOf(
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("A"), MediaId("cam"), 0, 0, 20 * sec),
                    Clip(ClipId("B"), MediaId("cam"), 20 * sec, 2 * sec, 5 * sec),
                    Clip(ClipId("P"), MediaId("photo"), 25 * sec, 0, 3 * sec),
                ),
            ),
            Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("M"), MediaId("song"), 0, 0, 10 * sec)), role = AudioRole.MUSIC),
        ),
        rate,
    )

    private fun applied(t: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(t, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(t: Timeline, command: EditCommand) = (TimelineEditor.apply(t, command, context) as EditResult.Rejected).reason

    private fun clip(t: Timeline, id: String) = t.findClip(ClipId(id))!!

    private fun cuts(t: Timeline) = TransitionLayout.resolve(t, TransitionLayout.sourceLengthFrom(context))

    @Test
    fun `a transition goes on a cut of the main track and nowhere else`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("B"), dissolve))
        assertEquals(dissolve, clip(t, "B").transitionOut)
        // The last clip has no cut after it; other tracks have no cuts to transition.
        assertEquals(EditRejection.NO_CUT, rejected(t, EditCommand.SetTransition(ClipId("P"), dissolve)))
        assertEquals(EditRejection.NO_CUT, rejected(t, EditCommand.SetTransition(ClipId("M"), dissolve)))
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.SetTransition(ClipId("B"), dissolve)))
        assertEquals(EditRejection.CLIP_NOT_FOUND, rejected(t, EditCommand.SetTransition(ClipId("nope"), dissolve)))
        // Nothing else moves: the timeline keeps its length and every clip its place.
        assertEquals(timeline().durationUs, t.durationUs)
        timeline().tracks.flatMap { it.clips }.forEach { before -> assertEquals(before.copy(transitionOut = clip(t, before.id.value).transitionOut), clip(t, before.id.value)) }
        // Removing it.
        val removed = applied(t, EditCommand.SetTransition(ClipId("B"), null))
        assertNull(clip(removed, "B").transitionOut)
    }

    @Test
    fun `a split leaves the transition on the right part, at the same cut`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("B"), dissolve))
        val split = applied(t, EditCommand.Split(ClipId("B"), 22 * sec))
        val parts = split.mainTrack!!.clips.filter { it.mediaId == MediaId("cam") && it.id != ClipId("A") }
        assertNull(parts[0].transitionOut)
        assertEquals(dissolve, parts[1].transitionOut)
        assertEquals(cuts(t).single().window, cuts(split).single().window)
    }

    @Test
    fun `a moved clip takes its transition to its new neighbour`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("A"), dissolve))
        val moved = applied(t, EditCommand.Move(ClipId("A"), 30 * sec))
        assertEquals(listOf("B", "P", "A"), moved.mainTrack!!.clips.map { it.id.value })
        // Now last: kept, but there's no cut to play it on.
        assertEquals(dissolve, clip(moved, "A").transitionOut)
        assertTrue(cuts(moved).isEmpty())
        // With a clip after it again, it plays again.
        val back = applied(moved, EditCommand.Move(ClipId("P"), 100 * sec))
        assertEquals(listOf("B", "A", "P"), back.mainTrack!!.clips.map { it.id.value })
        assertEquals(ClipId("A"), cuts(back).single().outgoing.id)
    }

    @Test
    fun `deleting the incoming clip moves the cut, and the transition joins the next clip`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("B"), dissolve))
        val deleted = applied(applied(t, EditCommand.SetTransition(ClipId("A"), dissolve)), EditCommand.Delete(ClipId("B"), closeGap = true))
        val cut = cuts(deleted).single()
        assertEquals(ClipId("A"), cut.outgoing.id)
        assertEquals(ClipId("P"), cut.incoming.id)
    }

    @Test
    fun `media past the clip edges limits the transition and trims change it`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("A"), dissolve))
        // A runs to its file's end: nothing after its out-point, so the dissolve can't play.
        val none = cuts(t).single().window
        assertEquals(0, none.frames)
        assertEquals(TransitionLimit.MEDIA_HANDLES, none.limit)
        // Ending the transition at the cut needs only B's media before its in-point (2 s): full length.
        val before = applied(t, EditCommand.SetTransition(ClipId("A"), dissolve.copy(alignment = TransitionAlignment.BEFORE_CUT)))
        assertEquals(30, cuts(before).single().window.frames)
        // Trimming 0.3 s off A's end gives it 9 frames of handle: a centred dissolve of 18 frames (9 after the cut).
        val trimmed = applied(t, EditCommand.TrimEnd(ClipId("A"), 19_700_000))
        val window = cuts(trimmed).single().window
        assertEquals(30, window.requestedFrames)
        assertEquals(18, window.frames)
        assertEquals(TransitionLimit.MEDIA_HANDLES, window.limit)
        assertTrue(window.outgoingHandleUs <= 300_000)
    }

    @Test
    fun `trim to fit makes room for the full transition in one undoable edit`() {
        val t = applied(timeline(), EditCommand.SetTransition(ClipId("A"), dissolve))
        val fitted = applied(t, EditCommand.FitTransition(ClipId("A")))
        val window = cuts(fitted).single().window
        assertEquals(30, window.frames)
        assertNull(window.limit)
        // Only A's end was trimmed (B had handles), by whole frames, and the track stays packed.
        val a = clip(fitted, "A")
        assertEquals(rate.frameToUs(15), 20 * sec - a.durationUs) // 15 frames: the half after the cut.
        assertEquals(clip(t, "B").sourceInUs, clip(fitted, "B").sourceInUs)
        assertEquals(a.endUs, clip(fitted, "B").startUs)
        // Fits already: nothing to do.
        assertEquals(EditRejection.NO_CHANGE, rejected(fitted, EditCommand.FitTransition(ClipId("A"))))
        // One step undoes it exactly.
        val history = EditHistory().record(EditCommand.FitTransition(ClipId("A")), t, fitted)
        val undone = history.undo(fitted) as HistoryStep.Moved
        assertEquals(t, undone.timeline)
        assertEquals(fitted, (undone.history.redo(undone.timeline) as HistoryStep.Moved).timeline)
    }

    @Test
    fun `trim to fit trims the incoming clip when it is the one without media`() {
        // B starts at its file's start, A has plenty after its out-point.
        val t = Timeline(
            listOf(
                Track(
                    TrackId("main"), TrackKind.VIDEO,
                    listOf(
                        Clip(ClipId("A"), MediaId("cam"), 0, 0, 5 * sec, transitionOut = dissolve),
                        Clip(ClipId("B"), MediaId("cam"), 5 * sec, 0, 5 * sec),
                    ),
                ),
            ),
            rate,
        )
        assertEquals(TransitionLimit.MEDIA_HANDLES, cuts(t).single().window.limit)
        val fitted = applied(t, EditCommand.FitTransition(ClipId("A")))
        assertEquals(30, cuts(fitted).single().window.frames)
        assertEquals(5 * sec, clip(fitted, "A").durationUs)
        assertTrue(clip(fitted, "B").sourceInUs > 0)
    }

    @Test
    fun `trim to fit can't help clips that are too short`() {
        val t = Timeline(
            listOf(
                Track(
                    TrackId("main"), TrackKind.VIDEO,
                    listOf(
                        Clip(ClipId("A"), MediaId("photo"), 0, 0, 200_000, transitionOut = dissolve),
                        Clip(ClipId("B"), MediaId("photo"), 200_000, 0, 200_000),
                    ),
                ),
            ),
            rate,
        )
        assertEquals(TransitionLimit.CLIP_LENGTH, cuts(t).single().window.limit)
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.FitTransition(ClipId("A"))))
    }

    @Test
    fun `transition edits undo and redo exactly`() {
        var t = timeline()
        var history = EditHistory()
        val steps = listOf(
            EditCommand.SetTransition(ClipId("A"), dissolve),
            EditCommand.SetTransition(ClipId("B"), Transition(TransitionType.WIPE_LEFT)),
            EditCommand.SetTransition(ClipId("A"), dissolve.copy(type = TransitionType.SPIN_CLOCKWISE, alignment = TransitionAlignment.AFTER_CUT)),
            EditCommand.FitTransition(ClipId("A")),
            EditCommand.SetTransition(ClipId("B"), null),
        )
        val states = mutableListOf(t)
        steps.forEach { command ->
            val next = applied(t, command)
            history = history.record(command, t, next)
            t = next
            states += t
        }
        for (i in steps.indices.reversed()) {
            val step = history.undo(t) as HistoryStep.Moved
            t = step.timeline
            history = step.history
            assertEquals(states[i], t)
        }
        for (i in steps.indices) {
            val step = history.redo(t) as HistoryStep.Moved
            t = step.timeline
            history = step.history
            assertEquals(states[i + 1], t)
        }
    }
}
