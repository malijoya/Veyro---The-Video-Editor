package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting clip transforms, and how other edits and undo treat them. */
class ClipTransformEditTest {

    private val sec = 1_000_000L
    private var nextId = 0

    private val media = mapOf(
        MediaId("cam") to MediaBounds(MediaKind.VIDEO, 10 * sec, hasAudio = true),
        MediaId("photo") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 60 * sec),
    )
    private val context = EditContext(media) { ClipId("c${nextId++}") }

    private val moved = ClipTransform(positionX = 0.3f, scale = 1.5f, rotationDegrees = 45f, crop = CropRect(0.1f, 0f, 0.9f, 1f), fit = FitMode.FILL, opacity = 0.8f)

    /** Main: a 4 s video and a 3 s photo. Music: a song. Text on top. */
    private fun timeline() = Timeline(
        listOf(
            Track(TrackId("overlay-1"), TrackKind.OVERLAY, listOf(Clip(ClipId("T"), null, 0, 0, sec, overlay = Overlay(OverlayContent.Text("Hi"))))),
            Track(TrackId("main"), TrackKind.VIDEO, listOf(Clip(ClipId("V"), MediaId("cam"), 0, 0, 4 * sec), Clip(ClipId("P"), MediaId("photo"), 4 * sec, 0, 3 * sec))),
            Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("M"), MediaId("song"), 0, 0, 5 * sec)), role = AudioRole.MUSIC),
        ),
        FrameRate.FPS_30,
    )

    private fun applied(t: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(t, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(t: Timeline, command: EditCommand) = (TimelineEditor.apply(t, command, context) as EditResult.Rejected).reason

    @Test
    fun `videos and photos take a transform; nothing else changes`() {
        val before = timeline()
        val video = applied(before, EditCommand.SetClipTransform(ClipId("V"), moved))
        assertEquals(moved, video.findClip(ClipId("V"))!!.transform)
        // Timing is untouched, and so is every other clip.
        assertEquals(before.findClip(ClipId("V"))!!.copy(transform = moved), video.findClip(ClipId("V")))
        assertEquals(before.tracks.filter { it.id.value != "main" }, video.tracks.filter { it.id.value != "main" })
        val photo = applied(video, EditCommand.SetClipTransform(ClipId("P"), moved))
        assertEquals(moved, photo.findClip(ClipId("P"))!!.transform)
    }

    @Test
    fun `audio, text and unknown clips are refused, and repeating a transform changes nothing`() {
        val t = timeline()
        assertEquals(EditRejection.NOT_A_PICTURE, rejected(t, EditCommand.SetClipTransform(ClipId("M"), moved)))
        assertEquals(EditRejection.NOT_A_PICTURE, rejected(t, EditCommand.SetClipTransform(ClipId("T"), moved)))
        assertEquals(EditRejection.CLIP_NOT_FOUND, rejected(t, EditCommand.SetClipTransform(ClipId("nope"), moved)))
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.SetClipTransform(ClipId("V"), ClipTransform.DEFAULT)))
    }

    @Test
    fun `both halves of a split keep the transform, and trims and moves carry it`() {
        val t = applied(timeline(), EditCommand.SetClipTransform(ClipId("V"), moved))
        val split = applied(t, EditCommand.Split(ClipId("V"), 2 * sec))
        val main = split.mainTrack!!.clips
        assertEquals(listOf(moved, moved, ClipTransform.DEFAULT), main.map { it.transform })
        val trimmed = applied(split, EditCommand.TrimEnd(main[0].id, sec))
        assertEquals(moved, trimmed.findClip(main[0].id)!!.transform)
        // Reordering on the main track moves the clip with its transform.
        val reordered = applied(trimmed, EditCommand.Move(ClipId("P"), 0))
        assertEquals(ClipId("P"), reordered.mainTrack!!.clips.first().id)
        assertEquals(ClipTransform.DEFAULT, reordered.mainTrack!!.clips.first().transform)
        assertEquals(moved, reordered.findClip(main[0].id)!!.transform)
    }

    @Test
    fun `transform changes undo and redo exactly`() {
        val start = timeline()
        var history = EditHistory()
        var current = start
        val steps = listOf(moved, moved.with(scale = 0.5f), ClipTransform.DEFAULT.with(rotationDegrees = 90f))
        val states = mutableListOf(start)
        steps.forEach { transform ->
            val command = EditCommand.SetClipTransform(ClipId("V"), transform)
            val next = applied(current, command)
            history = history.record(command, current, next)
            current = next
            states += next
        }
        for (expected in states.dropLast(1).asReversed()) {
            val step = history.undo(current) as HistoryStep.Moved
            history = step.history
            current = step.timeline
            assertEquals(expected, current)
        }
        for (expected in states.drop(1)) {
            val step = history.redo(current) as HistoryStep.Moved
            history = step.history
            current = step.timeline
            assertEquals(expected, current)
        }
    }
}
