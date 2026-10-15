package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.EffectParam
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.Keyframe
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.ClipTransform
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyframes through edits: they stay on the same moment of the clip's
 * content through trims, splits and moves, so the picture at every frame is
 * unchanged by edits that don't change it; and keyframe edits undo exactly.
 */
class KeyframeEditTest {

    private val sec = 1_000_000L
    private val rate = FrameRate.FPS_30
    private var nextId = 0

    private val media = mapOf(
        MediaId("cam") to MediaBounds(MediaKind.VIDEO, 20 * sec, hasAudio = true),
        MediaId("photo") to MediaBounds(MediaKind.IMAGE, 0),
        MediaId("song") to MediaBounds(MediaKind.AUDIO, 60 * sec),
    )
    private val context = EditContext(media) { ClipId("c${nextId++}") }

    /** Moves right over 4 s with an ease, spins, fades out at the end; one keyframe sits before the clip's start (trimmed away). */
    private val animation = ClipAnimation()
        .with(TransformChannel.POSITION_X, Keyframe(-sec, 0.0, Easing.EASE_IN_OUT))
        .with(TransformChannel.POSITION_X, Keyframe(sec, 0.3, Easing.EASE_IN_OUT))
        .with(TransformChannel.POSITION_X, Keyframe(4 * sec, 0.9))
        .with(TransformChannel.ROTATION, Keyframe(0, 0.0))
        .with(TransformChannel.ROTATION, Keyframe(4 * sec, 540.0))
        .with(TransformChannel.OPACITY, Keyframe(3 * sec, 1.0, Easing.EASE_OUT))
        .with(TransformChannel.OPACITY, Keyframe(5 * sec, 0.0))

    private val textAnimation = ClipAnimation()
        .with(TransformChannel.SCALE, Keyframe(0, 0.5, Easing.EASE_OUT))
        .with(TransformChannel.SCALE, Keyframe(sec, 1.5))

    /** Main: a 5 s video (source from 2 s) then a 3 s photo. Text over 1..4 s. Music. */
    private fun timeline() = Timeline(
        listOf(
            Track(
                TrackId("overlay-1"), TrackKind.OVERLAY,
                listOf(Clip(ClipId("T"), null, sec, 0, 3 * sec, overlay = Overlay(OverlayContent.Text("Hi")), animation = textAnimation)),
            ),
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("V"), MediaId("cam"), 0, 2 * sec, 5 * sec, transform = ClipTransform(scale = 1.2f), animation = animation),
                    Clip(ClipId("P"), MediaId("photo"), 5 * sec, 0, 3 * sec, animation = animation),
                ),
            ),
            Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("M"), MediaId("song"), 0, 0, 5 * sec)), role = AudioRole.MUSIC),
        ),
        rate,
    )

    private fun applied(t: Timeline, command: EditCommand): Timeline {
        val result = TimelineEditor.apply(t, command, context)
        assertTrue("Expected applied, got $result", result is EditResult.Applied)
        return (result as EditResult.Applied).timeline
    }

    private fun rejected(t: Timeline, command: EditCommand) = (TimelineEditor.apply(t, command, context) as EditResult.Rejected).reason

    /** What a track shows on every frame of [range]: the placement of the clip there (and which content moment it is). */
    private fun pictureAt(t: Timeline, trackId: String, timeUs: Long): Pair<Long, ClipTransform>? {
        val clip = t.track(TrackId(trackId))!!.clipAt(timeUs) ?: return null
        val contentUs = clip.sourceInUs + clip.localTimeOf(timeUs)
        return contentUs to clip.animation.transformAt(clip.transform, clip.localTimeOf(timeUs))
    }

    private fun overlayAt(t: Timeline, timeUs: Long) = t.overlayTracks.firstNotNullOfOrNull { track ->
        track.clipAt(timeUs)?.let { it.animation.overlayTransformAt(it.overlay!!.transform, it.localTimeOf(timeUs)) }
    }

    private fun frames(fromUs: Long, toUs: Long) = (rate.usToFrame(fromUs)..rate.usToFrame(toUs - 1)).map { rate.frameToUs(it) }

    // ---------------- Split ----------------

    @Test
    fun `splitting leaves every frame's placement unchanged, and each part keeps only what it needs`() {
        val before = timeline()
        listOf(sec / 2, 1_234_567L, 3 * sec, 4_500_000L).forEach { at ->
            val after = applied(before, EditCommand.Split(ClipId("V"), rate.snapUs(at)))
            frames(0, 8 * sec).forEach { t -> assertEquals("frame at $t", pictureAt(before, "main", t), pictureAt(after, "main", t)) }
            val right = after.track(TrackId("main"))!!.clips[1]
            assertTrue(right.animation.keyframeTimes().size <= animation.keyframeTimes().size + 1)
        }
        // Photos and text too (they have no source time, so only clip-local re-basing keeps them still).
        val photo = applied(before, EditCommand.Split(ClipId("P"), 6 * sec))
        frames(5 * sec, 8 * sec).forEach { t -> assertEquals(pictureAt(before, "main", t)!!.second, pictureAt(photo, "main", t)!!.second) }
        val text = applied(before, EditCommand.Split(ClipId("T"), 1_500_000))
        frames(sec, 4 * sec).forEach { t -> assertEquals(overlayAt(before, t), overlayAt(text, t)) }
    }

    @Test
    fun `random keyframes and random splits never change a frame`() {
        val random = Random(2026)
        repeat(60) {
            val anim = (TransformChannel.entries).fold(ClipAnimation()) { acc, channel ->
                (0 until random.nextInt(0, 5)).fold(acc) { a, _ ->
                    a.with(channel, Keyframe(random.nextLong(-2 * sec, 7 * sec), random.nextDouble(0.1, 1.0), Easing.PRESETS.random(random)))
                }
            }
            var t = timeline().let { it.withTrack(it.track(TrackId("main"))!!.let { m -> m.copy(clips = m.clips.map { c -> c.copy(animation = anim) }) }) }
            val original = t
            repeat(3) {
                val clip = t.track(TrackId("main"))!!.clips.random(random)
                val at = rate.snapUs(random.nextLong(clip.startUs, clip.endUs))
                val result = TimelineEditor.apply(t, EditCommand.Split(clip.id, at), context)
                if (result is EditResult.Applied) t = result.timeline
            }
            // Placement only: a split photo's parts each count their own time from 0.
            frames(0, 8 * sec).forEach { time -> assertEquals(pictureAt(original, "main", time)?.second, pictureAt(t, "main", time)?.second) }
        }
    }

    // ---------------- Trim and move ----------------

    @Test
    fun `trimming the start keeps keyframes on their moment of the content`() {
        val before = timeline()
        // Main track (magnetic): the clip keeps its start and the rest moves left, so content at source s is now one second earlier.
        val trimmed = applied(before, EditCommand.TrimStart(ClipId("V"), sec))
        frames(sec, 5 * sec).forEach { t -> assertEquals(pictureAt(before, "main", t), pictureAt(trimmed, "main", t - sec)) }
        // And trimming back out restores the keyframe that was hidden before the clip.
        val restored = applied(trimmed, EditCommand.TrimStart(ClipId("V"), -sec)) // On the main track the left edge stays at 0; dragging it left by 1 s extends the clip.
        assertEquals(timeline().findClip(ClipId("V"))!!.animation, restored.findClip(ClipId("V"))!!.animation)

        // Overlay (keeps gaps): the clip's start moves, the text stays where it was at every remaining frame.
        val text = applied(before, EditCommand.TrimStart(ClipId("T"), 2 * sec))
        frames(2 * sec, 4 * sec).forEach { t -> assertEquals(overlayAt(before, t), overlayAt(text, t)) }
    }

    @Test
    fun `trimming the end and moving don't touch keyframes`() {
        val before = timeline()
        val shorter = applied(before, EditCommand.TrimEnd(ClipId("V"), 3 * sec))
        assertEquals(animation, shorter.findClip(ClipId("V"))!!.animation)
        val moved = applied(before, EditCommand.Move(ClipId("T"), 4 * sec))
        frames(0, 3 * sec).forEach { offset -> assertEquals(overlayAt(before, sec + offset), overlayAt(moved, 4 * sec + offset)) }
    }

    // ---------------- Keyframe edits ----------------

    @Test
    fun `keyframes are set with transforms, overlays or on their own, and undo exactly`() {
        val start = timeline()
        val newAnimation = animation.with(TransformChannel.SCALE, Keyframe(2 * sec, 3.0))
        var history = EditHistory()
        var t = start
        listOf(
            EditCommand.SetClipAnimation(ClipId("V"), newAnimation),
            EditCommand.SetClipTransform(ClipId("P"), ClipTransform(positionY = 0.1f), ClipAnimation.NONE),
            EditCommand.SetOverlay(ClipId("T"), Overlay(OverlayContent.Text("Bye")), textAnimation.shifted(sec)),
        ).forEach { command ->
            val next = applied(t, command)
            history = history.record(command, t, next)
            t = next
        }
        assertEquals(newAnimation, t.findClip(ClipId("V"))!!.animation)
        assertEquals(ClipAnimation.NONE, t.findClip(ClipId("P"))!!.animation)
        assertEquals(textAnimation.shifted(sec), t.findClip(ClipId("T"))!!.animation)
        val end = t
        repeat(3) { t = (history.undo(t) as HistoryStep.Moved).also { history = it.history }.timeline }
        assertEquals(start, t)
        repeat(3) { t = (history.redo(t) as HistoryStep.Moved).also { history = it.history }.timeline }
        assertEquals(end, t)
    }

    @Test
    fun `transform commands without keyframes keep the clip's, and nothing changes is refused`() {
        val start = timeline()
        val moved = applied(start, EditCommand.SetClipTransform(ClipId("V"), ClipTransform(positionY = 0.7f)))
        assertEquals(animation, moved.findClip(ClipId("V"))!!.animation)
        assertEquals(EditRejection.NO_CHANGE, rejected(start, EditCommand.SetClipAnimation(ClipId("V"), animation)))
    }

    @Test
    fun `keyframes for something a clip doesn't have are refused`() {
        val start = timeline()
        assertEquals(EditRejection.NOT_ANIMATABLE, rejected(start, EditCommand.SetClipAnimation(ClipId("M"), animation)))
        val effectKeys = ClipAnimation().with(EffectParam("blur", "radius"), Keyframe(0, 1.0))
        assertEquals(EditRejection.NOT_ANIMATABLE, rejected(start, EditCommand.SetClipAnimation(ClipId("V"), effectKeys)))
        // With the effect on the clip, its parameter animates; sound clips may animate effect parameters too.
        val withBlur = start.withTrack(start.track(TrackId("music"))!!.let { it.copy(clips = it.clips.map { c -> c.copy(effects = listOf(ClipEffect("blur", "blur"))) }) })
        val animated = applied(withBlur, EditCommand.SetClipAnimation(ClipId("M"), effectKeys))
        assertEquals(effectKeys, animated.findClip(ClipId("M"))!!.animation)
    }

    @Test
    fun `a duplicated overlay can bring its keyframes`() {
        val added = TimelineEditor.apply(
            timeline(),
            EditCommand.AddOverlay(Overlay(OverlayContent.Text("Copy")), null, sec, 3 * sec, textAnimation),
            context,
        ) as EditResult.Applied
        assertEquals(textAnimation, added.timeline.findClip(added.affectedClipId!!)!!.animation)
    }
}
