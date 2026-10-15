package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainKeyframe
import com.editor.veyro.core.model.audio.TrackAudio
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEditTest {

    private val sec = 1_000_000L
    private val main = TrackId("main")
    private val music = TrackId("music")
    private val voice = TrackId("voice")
    private var nextId = 0

    private val media = mapOf(
        MediaId("cam") to MediaBounds(MediaKind.VIDEO, 10 * sec, hasAudio = true),
        MediaId("silent") to MediaBounds(MediaKind.VIDEO, 10 * sec, hasAudio = false),
        MediaId("img") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 60 * sec),
        MediaId("talk") to MediaBounds(MediaKind.AUDIO, 20 * sec),
    )
    private val context = EditContext(media) { ClipId("new-${nextId++}") }

    private fun clip(id: String, media: String, start: Long, sourceIn: Long, duration: Long, audio: ClipAudio = ClipAudio.DEFAULT) =
        Clip(ClipId(id), MediaId(media), start, sourceIn, duration, audio = audio)

    /** Main: A cam [0..4 s), P photo [4..6 s), S silent video [6..8 s). Music: M song at 0, 8 s. Voice: V talk at 2 s, 3 s. */
    private fun timeline() = Timeline(
        listOf(
            Track(main, TrackKind.VIDEO, listOf(clip("A", "cam", 0, 0, 4 * sec), clip("P", "img", 4 * sec, 0, 2 * sec), clip("S", "silent", 6 * sec, 0, 2 * sec))),
            Track(music, TrackKind.AUDIO, listOf(clip("M", "song", 0, 0, 8 * sec)), role = AudioRole.MUSIC),
            Track(voice, TrackKind.AUDIO, listOf(clip("V", "talk", 2 * sec, 0, 3 * sec)), role = AudioRole.VOICEOVER),
        ),
        FrameRate.FPS_30,
    )

    private fun applied(t: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(t, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(t: Timeline, command: EditCommand) = (TimelineEditor.apply(t, command, context) as EditResult.Rejected).reason

    // ---------------- Clip sound ----------------

    @Test
    fun `clip volume, mute and fades are set, with fades capped to the clip`() {
        val t = applied(timeline(), EditCommand.SetClipAudio(ClipId("M"), ClipAudio(volume = 0.4f, fadeInUs = 2 * sec, fadeOutUs = 20 * sec)))
        assertEquals(ClipAudio(volume = 0.4f, fadeInUs = 2 * sec, fadeOutUs = 8 * sec), t.findClip(ClipId("M"))!!.audio)
        val muted = applied(t, EditCommand.SetClipAudio(ClipId("A"), ClipAudio(muted = true)))
        assertTrue(muted.findClip(ClipId("A"))!!.audio.muted)
        // Timing is untouched by sound edits.
        assertEquals(timeline().tracks.map { tr -> tr.clips.map { it.startUs to it.durationUs } }, muted.tracks.map { tr -> tr.clips.map { it.startUs to it.durationUs } })
    }

    @Test
    fun `clips without sound reject audio edits`() {
        assertEquals(EditRejection.NO_AUDIO, rejected(timeline(), EditCommand.SetClipAudio(ClipId("P"), ClipAudio(volume = 0.5f))))
        assertEquals(EditRejection.NO_AUDIO, rejected(timeline(), EditCommand.SetClipAudio(ClipId("S"), ClipAudio(volume = 0.5f))))
        assertEquals(EditRejection.NO_CHANGE, rejected(timeline(), EditCommand.SetClipAudio(ClipId("A"), ClipAudio.DEFAULT)))
        assertEquals(EditRejection.CLIP_NOT_FOUND, rejected(timeline(), EditCommand.SetClipAudio(ClipId("nope"), ClipAudio.DEFAULT)))
    }

    @Test
    fun `splitting keeps the fade-in on the left and the fade-out on the right`() {
        val keyframes = listOf(GainKeyframe(1 * sec, 1f), GainKeyframe(6 * sec, 0.2f))
        val faded = applied(timeline(), EditCommand.SetClipAudio(ClipId("M"), ClipAudio(volume = 0.5f, fadeInUs = 1 * sec, fadeOutUs = 2 * sec, keyframes = keyframes)))
        val split = applied(faded, EditCommand.Split(ClipId("M"), 4 * sec))
        val left = split.findClip(ClipId("M"))!!.audio
        val right = split.findClip(ClipId("new-0"))!!.audio
        assertEquals(ClipAudio(volume = 0.5f, fadeInUs = 1 * sec, fadeOutUs = 0, keyframes = keyframes), left)
        assertEquals(ClipAudio(volume = 0.5f, fadeInUs = 0, fadeOutUs = 2 * sec, keyframes = keyframes), right)
    }

    // ---------------- Track sound ----------------

    @Test
    fun `track volume and mute are set on the track`() {
        val t = applied(timeline(), EditCommand.SetTrackAudio(music, TrackAudio(volume = 0.3f, muted = true)))
        assertEquals(TrackAudio(volume = 0.3f, muted = true), t.track(music)!!.audio)
        assertEquals(timeline().track(music)!!.clips, t.track(music)!!.clips)
        assertEquals(EditRejection.NO_CHANGE, rejected(t, EditCommand.SetTrackAudio(music, TrackAudio(volume = 0.3f, muted = true))))
        assertEquals(EditRejection.TRACK_NOT_FOUND, rejected(t, EditCommand.SetTrackAudio(TrackId("x"), TrackAudio.DEFAULT)))
    }

    // ---------------- Moving between tracks ----------------

    @Test
    fun `audio moves between music and voiceover to the nearest free spot`() {
        // M (0..8 s) to Voice, where V sits at 2..5 s: nearest free start for 8 s is 5 s.
        val t = applied(timeline(), EditCommand.MoveToTrack(ClipId("M"), voice, 0))
        assertTrue(t.track(music)!!.clips.isEmpty())
        assertEquals(listOf("V" to 2 * sec, "M" to 5 * sec), t.track(voice)!!.clips.map { it.id.value to it.startUs })
        // The clip keeps its source range and sound settings.
        assertEquals(timeline().findClip(ClipId("M"))!!.copy(startUs = 5 * sec), t.findClip(ClipId("M")))
    }

    @Test
    fun `clips can't move onto other kinds of track or the magnetic track`() {
        assertEquals(EditRejection.INCOMPATIBLE_TRACK, rejected(timeline(), EditCommand.MoveToTrack(ClipId("M"), main, 0)))
        assertEquals(EditRejection.INCOMPATIBLE_TRACK, rejected(timeline(), EditCommand.MoveToTrack(ClipId("A"), music, 0)))
        assertEquals(EditRejection.TRACK_NOT_FOUND, rejected(timeline(), EditCommand.MoveToTrack(ClipId("M"), TrackId("x"), 0)))
    }

    @Test
    fun `moving within the same track is a normal move`() {
        val t = applied(timeline(), EditCommand.MoveToTrack(ClipId("V"), voice, 10 * sec))
        assertEquals(10 * sec, t.findClip(ClipId("V"))!!.startUs)
    }

    // ---------------- Undo and redo ----------------

    private class Session(var timeline: Timeline, var history: EditHistory = EditHistory())

    private fun Session.edit(command: EditCommand) {
        val after = applied(timeline, command)
        history = history.record(command, timeline, after)
        timeline = after
    }

    private fun Session.undo() {
        val step = history.undo(timeline) as HistoryStep.Moved
        timeline = step.timeline
        history = step.history
    }

    private fun Session.redo() {
        val step = history.redo(timeline) as HistoryStep.Moved
        timeline = step.timeline
        history = step.history
    }

    @Test
    fun `sound edits and cross-track moves undo and redo exactly, in both directions`() {
        val session = Session(timeline())
        val states = mutableListOf(session.timeline)
        listOf(
            EditCommand.SetClipAudio(ClipId("M"), ClipAudio(volume = 0.6f, fadeOutUs = 3 * sec)),
            EditCommand.SetTrackAudio(voice, TrackAudio(volume = 0.8f)),
            EditCommand.MoveToTrack(ClipId("M"), voice, 0), // Music -> Voice (a later track).
            EditCommand.MoveToTrack(ClipId("V"), music, 2 * sec), // Voice -> Music (an earlier track).
            EditCommand.SetTrackAudio(music, TrackAudio(muted = true)),
            EditCommand.SetClipAudio(ClipId("A"), ClipAudio(muted = true)),
        ).forEach { command ->
            session.edit(command)
            states += session.timeline
        }
        for (i in states.indices.reversed().drop(1)) {
            session.undo()
            assertEquals("undo to state $i", states[i], session.timeline)
        }
        for (i in 1 until states.size) {
            session.redo()
            assertEquals("redo to state $i", states[i], session.timeline)
        }
    }

    @Test
    fun `a track setting patch refuses a track changed elsewhere`() {
        val before = timeline()
        val after = applied(before, EditCommand.SetTrackAudio(music, TrackAudio(volume = 0.5f)))
        val patch = TimelinePatch.between(before, after)
        assertEquals(0, patch.clipRecordCount) // Only the track setting is stored.
        val changedElsewhere = before.withTrack(before.track(music)!!.copy(audio = TrackAudio(volume = 0.9f)))
        assertEquals(null, patch.applyTo(changedElsewhere))
        assertEquals(after, patch.applyTo(before))
        assertEquals(before, patch.inverse().applyTo(after))
    }
}
