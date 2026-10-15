package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.overlay.TextStyle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayEditTest {

    private val sec = 1_000_000L
    private val main = TrackId("main")
    private var nextId = 0

    private val media = mapOf(
        MediaId("cam") to MediaBounds(MediaKind.VIDEO, 10 * sec, hasAudio = true),
        MediaId("logo") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 60 * sec),
    )
    private val context = EditContext(media) { ClipId("c${nextId++}") }

    private fun text(words: String) = Overlay(OverlayContent.Text(words))

    /** Main: one 10 s video. Music: empty. No overlays yet. */
    private fun timeline() = Timeline(
        listOf(
            Track(main, TrackKind.VIDEO, listOf(Clip(ClipId("V"), MediaId("cam"), 0, 0, 10 * sec))),
            Track(TrackId("music"), TrackKind.AUDIO, emptyList(), role = AudioRole.MUSIC),
        ),
        FrameRate.FPS_30,
    )

    private fun applied(t: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(t, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(t: Timeline, command: EditCommand) = (TimelineEditor.apply(t, command, context) as EditResult.Rejected).reason

    private fun addText(t: Timeline, words: String, start: Long, duration: Long) =
        applied(t, EditCommand.AddOverlay(text(words), null, start, duration))

    /** Overlay lanes, top first, as the words (or sticker media) on them. */
    private fun lanes(t: Timeline): List<List<String>> = t.overlayTracks.map { track ->
        track.clips.map { (it.overlay!!.content as? OverlayContent.Text)?.text ?: it.mediaId!!.value }
    }

    /** A, B, C and D as built step by step in the first test: lanes [[D], [B, C], [A]]. */
    private fun stacked(): Timeline {
        var t = addText(timeline(), "A", 0, 3 * sec)
        t = addText(t, "B", 1 * sec, 3 * sec)
        t = addText(t, "C", 5 * sec, 2 * sec)
        return addText(t, "D", 2 * sec, 1 * sec)
    }

    private fun idOf(t: Timeline, words: String): ClipId =
        t.overlayTracks.flatMap { it.clips }.first { (it.overlay!!.content as? OverlayContent.Text)?.text == words }.id

    // ---------------- Adding and stacking ----------------

    @Test
    fun `new overlays go above everything they overlap, reusing lanes where there is room`() {
        val first = addText(timeline(), "A", 0, 3 * sec)
        // The first overlay starts a lane directly above the main track.
        assertEquals(listOf("overlay-1", "main", "music"), first.tracks.map { it.id.value })
        assertEquals(listOf(listOf("A")), lanes(first))

        val second = addText(first, "B", 1 * sec, 3 * sec)
        assertEquals(listOf(listOf("B"), listOf("A")), lanes(second)) // Overlaps A, so it goes on a new lane above.

        val third = addText(second, "C", 5 * sec, 2 * sec)
        assertEquals(listOf(listOf("B", "C"), listOf("A")), lanes(third)) // Overlaps nothing: the top lane has room.

        val fourth = addText(third, "D", 2 * sec, 1 * sec)
        assertEquals(listOf(listOf("D"), listOf("B", "C"), listOf("A")), lanes(fourth))
        // The main track is untouched throughout.
        assertEquals(timeline().mainTrack, fourth.mainTrack)
        assertEquals(lanes(stacked()), lanes(fourth))
    }

    @Test
    fun `layer moves only consider overlapping overlays`() {
        val t = stacked()
        val forward = applied(t, EditCommand.ArrangeLayer(idOf(t, "A"), LayerMove.FORWARD))
        assertEquals(listOf(listOf("D"), listOf("A"), listOf("B", "C")), lanes(forward)) // Now above B, still below D.

        val back = applied(forward, EditCommand.ArrangeLayer(idOf(forward, "A"), LayerMove.TO_BACK))
        assertEquals(listOf(listOf("D"), listOf("B", "C"), listOf("A")), lanes(back))

        val front = applied(t, EditCommand.ArrangeLayer(idOf(t, "B"), LayerMove.TO_FRONT))
        assertEquals(listOf(listOf("B"), listOf("D"), listOf("C"), listOf("A")), lanes(front)) // C stays: it overlaps nothing.

        val backward = applied(t, EditCommand.ArrangeLayer(idOf(t, "D"), LayerMove.BACKWARD))
        assertEquals(listOf(listOf("B", "C"), listOf("D"), listOf("A")), lanes(backward))

        // Already at that end of their stack.
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.ArrangeLayer(idOf(t, "D"), LayerMove.TO_FRONT)))
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.ArrangeLayer(idOf(t, "A"), LayerMove.BACKWARD)))
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.ArrangeLayer(idOf(t, "C"), LayerMove.FORWARD)))
        assertEquals(EditRejection.NOT_AN_OVERLAY, rejected(t, EditCommand.ArrangeLayer(ClipId("V"), LayerMove.FORWARD)))
        // Moves keep the overlay's time and look.
        val moved = forward.findClip(idOf(t, "A"))!!
        val original = t.findClip(idOf(t, "A"))!!
        assertEquals(original, moved)
    }

    @Test
    fun `lanes left empty are removed, and undo brings them back exactly`() {
        val t = stacked()
        val deleted = applied(t, EditCommand.Delete(idOf(t, "D"), closeGap = false))
        assertEquals(listOf(listOf("B", "C"), listOf("A")), lanes(deleted))
        assertNull(deleted.track(t.overlayTracks.first().id))

        val history = EditHistory().record(EditCommand.Delete(idOf(t, "D"), false), t, deleted)
        val undone = history.undo(deleted) as HistoryStep.Moved
        assertEquals(t, undone.timeline)
        val redone = undone.history.redo(undone.timeline) as HistoryStep.Moved
        assertEquals(deleted, redone.timeline)
    }

    // ---------------- Changing overlays ----------------

    @Test
    fun `set overlay changes content and placement, but never its kind`() {
        val t = addText(timeline(), "Hi", 0, 2 * sec)
        val id = t.overlayTracks.single().clips.single().id
        val restyled = Overlay(
            OverlayContent.Text("Hello", TextStyle(color = Argb.BLACK, bold = true)),
            OverlayTransform(centerX = 0.2f, centerY = 0.8f, scale = 1.5f, rotationDegrees = -30f, opacity = 0.5f),
        )
        val changed = applied(t, EditCommand.SetOverlay(id, restyled))
        assertEquals(restyled, changed.findClip(id)!!.overlay)
        assertEquals(t.findClip(id)!!.startUs, changed.findClip(id)!!.startUs)

        assertEquals(EditRejection.NO_CHANGE, rejected(changed, EditCommand.SetOverlay(id, restyled)))
        assertEquals(EditRejection.NOT_AN_OVERLAY, rejected(changed, EditCommand.SetOverlay(id, Overlay(OverlayContent.Sticker))))
        assertEquals(EditRejection.NOT_AN_OVERLAY, rejected(changed, EditCommand.SetOverlay(ClipId("V"), restyled)))
        assertEquals(EditRejection.CLIP_NOT_FOUND, rejected(changed, EditCommand.SetOverlay(ClipId("nope"), restyled)))
    }

    @Test
    fun `stickers need an image, text needs none`() {
        val t = timeline()
        val sticker = Overlay(OverlayContent.Sticker)
        assertEquals(EditRejection.UNSUPPORTED_MEDIA, rejected(t, EditCommand.AddOverlay(text("x"), MediaId("logo"), 0, sec)))
        assertEquals(EditRejection.UNSUPPORTED_MEDIA, rejected(t, EditCommand.AddOverlay(sticker, null, 0, sec)))
        assertEquals(EditRejection.UNSUPPORTED_MEDIA, rejected(t, EditCommand.AddOverlay(sticker, MediaId("cam"), 0, sec)))
        assertEquals(EditRejection.MEDIA_NOT_FOUND, rejected(t, EditCommand.AddOverlay(sticker, MediaId("missing"), 0, sec)))

        val added = applied(t, EditCommand.AddOverlay(sticker, MediaId("logo"), -5 * sec, 1))
        val clip = added.overlayTracks.single().clips.single()
        assertEquals(MediaId("logo"), clip.mediaId)
        assertEquals(0L, clip.startUs) // Never before 0.
        assertEquals(added.minClipDurationUs, clip.durationUs) // At least one frame.
    }

    @Test
    fun `overlay clips trim, move and split like photos`() {
        var t = addText(timeline(), "A", 0, 2 * sec)
        t = applied(t, EditCommand.AddOverlay(text("B"), null, 0, 1 * sec)) // A lane above A.
        t = applied(t, EditCommand.MoveToTrack(idOf(t, "B"), t.overlayTracks.last().id, 0)) // Onto A's lane: placed after A.
        assertEquals(listOf(listOf("A", "B")), lanes(t)) // B's old lane, now empty, is gone.
        assertEquals(2 * sec, t.findClip(idOf(t, "B"))!!.startUs)

        val a = idOf(t, "A")
        // No source limit: text can be as long as wanted, up to the next clip on its lane.
        val longer = applied(t, EditCommand.TrimEnd(a, 30 * sec))
        assertEquals(2 * sec, longer.findClip(a)!!.endUs)
        val trimmed = applied(t, EditCommand.TrimStart(a, 500_000))
        assertEquals(500_000L, trimmed.findClip(a)!!.startUs)
        assertEquals(0L, trimmed.findClip(a)!!.sourceInUs)

        val split = applied(t, EditCommand.Split(a, 1 * sec))
        val parts = split.overlayTracks.single().clips.filter { (it.overlay!!.content as OverlayContent.Text).text == "A" }
        assertEquals(2, parts.size)
        assertEquals(parts[0].overlay, parts[1].overlay) // Both halves show the same text.
        assertEquals(parts[0].endUs, parts[1].startUs)

        assertEquals(EditRejection.NO_AUDIO, rejected(t, EditCommand.SetClipAudio(a, ClipAudio(volume = 0.5f))))
        assertEquals(EditRejection.INCOMPATIBLE_TRACK, rejected(t, EditCommand.MoveToTrack(a, main, 0)))
    }

    // ---------------- History ----------------

    @Test
    fun `undo all and redo all across adding, styling, stacking, splitting and deleting overlays`() {
        val start = timeline()
        var t = start
        var history = EditHistory()
        val states = mutableListOf(t)
        fun step(command: EditCommand) {
            val next = applied(t, command)
            history = history.record(command, t, next)
            t = next
            states += t
        }
        step(EditCommand.AddOverlay(text("A"), null, 0, 3 * sec))
        step(EditCommand.AddOverlay(text("B"), null, 1 * sec, 3 * sec))
        step(EditCommand.AddOverlay(Overlay(OverlayContent.Sticker), MediaId("logo"), 2 * sec, 4 * sec))
        step(EditCommand.SetOverlay(idOf(t, "B"), text("B!").copy(transform = OverlayTransform(centerY = 0.1f))))
        step(EditCommand.ArrangeLayer(idOf(t, "A"), LayerMove.TO_FRONT))
        step(EditCommand.Split(idOf(t, "A"), 1 * sec))
        step(EditCommand.Delete(idOf(t, "B!"), closeGap = false))
        step(EditCommand.TrimEnd(ClipId("V"), 8 * sec)) // An ordinary edit in between.

        // Undo everything, checking each intermediate state.
        for (expected in states.dropLast(1).asReversed()) {
            val undone = history.undo(t) as HistoryStep.Moved
            history = undone.history
            t = undone.timeline
            assertEquals(expected, t)
        }
        assertEquals(start, t)
        assertEquals(HistoryStep.Empty, history.undo(t))

        // And redo it all.
        for (expected in states.drop(1)) {
            val redone = history.redo(t) as HistoryStep.Moved
            history = redone.history
            t = redone.timeline
            assertEquals(expected, t)
        }
    }
}
