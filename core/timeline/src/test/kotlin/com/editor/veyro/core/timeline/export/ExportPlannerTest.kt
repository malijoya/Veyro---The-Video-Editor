package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.WaveformBuilder
import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.timeline.EditCommand
import com.editor.veyro.core.timeline.EditContext
import com.editor.veyro.core.timeline.EditResult
import com.editor.veyro.core.timeline.MediaBounds
import com.editor.veyro.core.timeline.TimelineEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {

    private val sec = 1_000_000L
    private val main = TrackId("main")
    private val music = TrackId("music")
    private val voice = TrackId("voice")
    private val settings = ExportSettings(1_080, 1_920, FrameRate.FPS_30)

    private fun ref(id: String, kind: MediaKind, durationUs: Long, withAudio: Boolean = false) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = "$id.file", mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs, widthPx = if (kind == MediaKind.AUDIO) 0 else 1_920, heightPx = if (kind == MediaKind.AUDIO) 0 else 1_080,
        rotationDegrees = 0, sizeBytes = null,
        audio = if (withAudio || kind == MediaKind.AUDIO) AudioTrackInfo("audio/mp4a-latm", 48_000, 2, null) else null,
    )

    private val media = listOf(
        ref("v1", MediaKind.VIDEO, 10 * sec, withAudio = true),
        ref("v2", MediaKind.VIDEO, 6 * sec),
        ref("img", MediaKind.IMAGE, 0),
        ref("song", MediaKind.AUDIO, 60 * sec),
        ref("talk", MediaKind.AUDIO, 20 * sec),
    )

    private fun clip(id: String, media: String, start: Long, sourceIn: Long, duration: Long, effects: List<ClipEffect> = emptyList(), audio: ClipAudio = ClipAudio.DEFAULT) =
        Clip(ClipId(id), MediaId(media), start, sourceIn, duration, effects, audio)

    /** Main: A = v1 [2..6 s) at 0, B = photo 3 s at 4 s, C = v2 [1..4 s) at 7 s. Ends at 10 s. */
    private fun timeline(
        audio: List<Clip> = emptyList(),
        voiceClips: List<Clip> = emptyList(),
        mainClips: List<Clip>? = null,
        musicAudio: TrackAudio = TrackAudio.DEFAULT,
        mainAudio: TrackAudio = TrackAudio.DEFAULT,
    ) = Timeline(
        listOf(
            Track(main, TrackKind.VIDEO, mainClips ?: listOf(clip("A", "v1", 0, 2 * sec, 4 * sec), clip("B", "img", 4 * sec, 0, 3 * sec), clip("C", "v2", 7 * sec, 1 * sec, 3 * sec)), audio = mainAudio),
            Track(music, TrackKind.AUDIO, audio, audio = musicAudio, role = AudioRole.MUSIC),
            Track(voice, TrackKind.AUDIO, voiceClips, role = AudioRole.VOICEOVER),
        ),
        FrameRate.FPS_30,
    )

    private fun ready(result: ExportPlanResult): ExportPlan {
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan
    }

    /** Where each audio clip starts on the timeline, per track. */
    private fun clipStarts(plan: ExportPlan): List<List<Long>> = plan.audioTracks.map { track ->
        var cursor = 0L
        track.mapNotNull { segment -> (if (segment is AudioSegment.Clip) cursor else null).also { cursor += segment.durationUs } }
    }

    // ---------------- Picture track ----------------

    @Test
    fun `segments follow clip order with exact source ranges`() {
        val plan = ready(ExportPlanner.plan(timeline(), media, settings))
        assertEquals(listOf("content://v1", "content://img", "content://v2"), plan.video.map { it.uri })
        assertEquals(listOf(2 * sec to 4 * sec, 0L to 3 * sec, 1 * sec to 3 * sec), plan.video.map { it.sourceStartUs to it.durationUs })
        assertEquals(listOf(10 * sec, 3 * sec, 6 * sec), plan.video.map { it.sourceDurationUs })
        // Only v1 has an audio stream.
        assertEquals(listOf(true, false, false), plan.video.map { it.hasAudio })
        assertEquals(10 * sec, plan.durationUs)
        assertTrue(plan.includeClipAudio)
        assertEquals(settings, plan.settings)
    }

    @Test
    fun `edits made in the editor are what gets exported`() {
        var t = timeline()
        var nextId = 0
        val context = EditContext(media.associate { it.id to MediaBounds(it.kind, it.durationUs, it.hasAudio) }) { ClipId("new-${nextId++}") }
        fun apply(command: EditCommand) {
            t = (TimelineEditor.apply(t, command, context) as EditResult.Applied).timeline
        }
        apply(EditCommand.Split(ClipId("A"), 1 * sec)) // A plays v1 [2..3 s), new-0 plays v1 [3..6 s).
        apply(EditCommand.Move(ClipId("C"), 0)) // C's middle (1.5 s) lands after A's (0.5 s): order A, C, new-0, B.
        apply(EditCommand.TrimEnd(ClipId("new-0"), 6 * sec)) // new-0 starts at 4 s; now 2 s long, v1 [3..5 s).

        val plan = ready(ExportPlanner.plan(t, media, settings))
        assertEquals(listOf("content://v1", "content://v2", "content://v1", "content://img"), plan.video.map { it.uri })
        assertEquals(
            listOf(2 * sec to 1 * sec, 1 * sec to 3 * sec, 3 * sec to 2 * sec, 0L to 3 * sec),
            plan.video.map { it.sourceStartUs to it.durationUs },
        )
        assertEquals(9 * sec, plan.durationUs)
    }

    @Test
    fun `a video's sound carries the clip's and the main track's settings`() {
        val faded = timeline(
            mainClips = listOf(clip("A", "v1", 0, 2 * sec, 4 * sec, audio = ClipAudio(volume = 0.5f, fadeInUs = 1 * sec))),
            mainAudio = TrackAudio(volume = 0.5f),
        )
        val gain = ready(ExportPlanner.plan(faded, media, settings)).video.single().audio!!
        assertEquals(GainEnvelope(4 * sec, 2 * sec, ClipAudio(volume = 0.5f, fadeInUs = 1 * sec), TrackAudio(volume = 0.5f)), gain.envelope)
        assertEquals(0f, gain.gainAt(0), 0f)
        assertEquals(0.25f, gain.gainAt(2 * sec), 1e-4f)
        assertEquals(0L, gain.timelineStartUs)
    }

    @Test
    fun `muted video sound and videos without an audio stream add no audio`() {
        val muted = timeline(mainClips = listOf(clip("A", "v1", 0, 0, 4 * sec, audio = ClipAudio(muted = true)), clip("C", "v2", 4 * sec, 0, 2 * sec)))
        val plan = ready(ExportPlanner.plan(muted, media, settings))
        assertFalse(plan.includeClipAudio)
        assertFalse(plan.hasAudio)
        assertTrue(plan.audioTracks.isEmpty())
        // A muted main track does the same.
        assertFalse(ready(ExportPlanner.plan(timeline(mainAudio = TrackAudio(muted = true)), media, settings)).includeClipAudio)
    }

    // ---------------- Audio tracks: sync and gaps ----------------

    @Test
    fun `audio clips play at their timeline positions, with gaps as silence`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(audio = listOf(clip("S1", "song", 1 * sec, 5 * sec, 2 * sec), clip("S2", "song", 5 * sec, 0, 3 * sec))),
                media, settings, waveforms = quietPeaks,
            ),
        )
        val track = plan.audioTracks.single()
        assertEquals(listOf(AudioSegment.Gap(1 * sec)), track.take(1))
        assertEquals(listOf(5 * sec to 2 * sec, 0L to 3 * sec), track.filterIsInstance<AudioSegment.Clip>().map { it.sourceStartUs to it.durationUs })
        assertEquals(listOf(listOf(1 * sec, 5 * sec)), clipStarts(plan))
        assertEquals(plan.durationUs, track.sumOf { it.durationUs })
        // Each clip's gain is placed at its own timeline start, for the mix headroom.
        assertEquals(listOf(1 * sec, 5 * sec), track.filterIsInstance<AudioSegment.Clip>().map { it.gain.timelineStartUs })
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `music and voiceover are separate tracks mixed under the picture`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(
                    audio = listOf(clip("M", "song", 0, 0, 10 * sec)),
                    voiceClips = listOf(clip("V1", "talk", 2 * sec, 0, 3 * sec), clip("V2", "talk", 6 * sec, 4 * sec, 2 * sec)),
                ),
                media, settings,
            ),
        )
        assertEquals(2, plan.audioTracks.size)
        assertEquals(listOf(listOf(0L), listOf(2 * sec, 6 * sec)), clipStarts(plan))
        // Both end exactly with the video.
        plan.audioTracks.forEach { assertEquals(10 * sec, it.sumOf { s -> s.durationUs }) }
    }

    @Test
    fun `audio past the end of the video is cut there, keeping the fade-out shape up to the cut`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(audio = listOf(clip("S1", "song", 8 * sec, 0, 30 * sec, audio = ClipAudio(fadeOutUs = 1 * sec)), clip("S2", "song", 40 * sec, 0, 5 * sec))),
                media, settings, waveforms = quietPeaks,
            ),
        )
        val segments = plan.audioTracks.single()
        assertEquals(AudioSegment.Gap(8 * sec), segments[0])
        val cut = segments[1] as AudioSegment.Clip
        assertEquals(2 * sec, cut.durationUs)
        // The envelope still describes the whole 30 s clip, so the cut part is at full level (its fade-out was later).
        assertEquals(30 * sec, cut.gain.envelope.durationUs)
        assertEquals(1f, cut.gain.gainAt(1 * sec), 0f)
        assertEquals(setOf(ExportWarning.AUDIO_TRIMMED_TO_VIDEO), plan.warnings)
    }

    @Test
    fun `muted tracks and silent clips are left out of the mix`() {
        val mutedTrack = ready(
            ExportPlanner.plan(timeline(audio = listOf(clip("M", "song", 0, 0, 5 * sec)), musicAudio = TrackAudio(muted = true)), media, settings),
        )
        assertTrue(mutedTrack.audioTracks.isEmpty())

        val zeroVolume = ready(
            ExportPlanner.plan(
                timeline(audio = listOf(clip("M1", "song", 0, 0, 2 * sec, audio = ClipAudio(volume = 0f)), clip("M2", "song", 4 * sec, 0, 2 * sec))),
                media, settings,
            ),
        )
        // The silent clip becomes part of the leading gap.
        assertEquals(listOf(listOf(4 * sec)), clipStarts(zeroVolume))
    }

    // ---------------- Overlaps and headroom ----------------

    /** Measured peaks of 0.3 for every file: overlaps can't clip, so no headroom and no warnings. */
    private val quietPeaks: (MediaId) -> com.editor.veyro.core.model.audio.Waveform? = { flatPeaks(0.3f, 60 * sec) }

    private fun flatPeaks(peak: Float, durationUs: Long) = WaveformBuilder().apply {
        for (i in 0 until (durationUs / 20_000).toInt()) addFloat(floatArrayOf(peak), 1, 1, 50, i * 20_000L)
    }.build()

    @Test
    fun `loud overlaps across tracks get headroom, quiet ones don't`() {
        val overlapping = timeline(
            audio = listOf(clip("M", "song", 0, 0, 10 * sec)),
            voiceClips = listOf(clip("V", "talk", 2 * sec, 0, 3 * sec)),
        )
        val loud = ready(ExportPlanner.plan(overlapping, media, settings, waveforms = { flatPeaks(0.9f, 60 * sec) }))
        assertTrue(ExportWarning.MIX_LOWERED_TO_PREVENT_CLIPPING in loud.warnings)
        val voiceGain = (loud.audioTracks[1][1] as AudioSegment.Clip).gain
        assertTrue(voiceGain.gainAt(1 * sec) < 0.7f) // Inside the overlap: turned down.
        val musicGain = (loud.audioTracks[0][0] as AudioSegment.Clip).gain
        assertEquals(1f, musicGain.gainAt(8 * sec), 0f) // Outside it: untouched.

        val quiet = ready(ExportPlanner.plan(overlapping, media, settings, waveforms = { flatPeaks(0.3f, 60 * sec) }))
        assertFalse(ExportWarning.MIX_LOWERED_TO_PREVENT_CLIPPING in quiet.warnings)
        assertTrue(quiet.audioTracks.flatten().filterIsInstance<AudioSegment.Clip>().all { it.gain.isUnity })
    }

    @Test
    fun `unanalysed overlapping audio is flagged`() {
        val overlapping = timeline(audio = listOf(clip("M", "song", 0, 0, 10 * sec)), voiceClips = listOf(clip("V", "talk", 2 * sec, 0, 3 * sec)))
        val plan = ready(ExportPlanner.plan(overlapping, media, settings))
        assertTrue(ExportWarning.AUDIO_NOT_ANALYSED in plan.warnings)
    }

    // ---------------- Blockers, preview and warnings ----------------

    @Test
    fun `an empty main track can't be exported, but previews its audio`() {
        val audioOnly = timeline(mainClips = emptyList(), audio = listOf(clip("S", "song", 1 * sec, 0, 5 * sec)), voiceClips = listOf(clip("V", "talk", 0, 0, 2 * sec)))
        assertEquals(ExportPlanResult.Blocked(setOf(ExportBlocker.NO_VIDEO)), ExportPlanner.plan(audioOnly, media, settings))
        val preview = ready(ExportPlanner.plan(audioOnly, media, settings, requireVideo = false))
        assertTrue(preview.video.isEmpty())
        assertEquals(6 * sec, preview.durationUs)
        assertEquals(listOf(listOf(1 * sec), listOf(0L)), clipStarts(preview))
        // Nothing audible at all: nothing to preview.
        val silent = timeline(mainClips = emptyList(), audio = listOf(clip("S", "song", 0, 0, 5 * sec)), musicAudio = TrackAudio(muted = true))
        assertEquals(ExportPlanResult.Blocked(setOf(ExportBlocker.NO_VIDEO)), ExportPlanner.plan(silent, media, settings, requireVideo = false))
    }

    @Test
    fun `missing files block the export and are named`() {
        val result = ExportPlanner.plan(
            timeline(audio = listOf(clip("S", "song", 0, 0, 5 * sec))),
            media, settings, unavailable = setOf(MediaId("v2"), MediaId("song")),
        ) as ExportPlanResult.Blocked
        assertEquals(setOf(ExportBlocker.MEDIA_UNAVAILABLE), result.blockers)
        assertEquals(listOf("v2.file", "song.file"), result.unavailableMedia)
    }

    @Test
    fun `a clip with no media in the project blocks the export`() {
        val result = ExportPlanner.plan(timeline(), media.filterNot { it.id == MediaId("img") }, settings) as ExportPlanResult.Blocked
        assertEquals(setOf(ExportBlocker.MEDIA_UNAVAILABLE), result.blockers)
        assertEquals(listOf("img"), result.unavailableMedia)
    }

    @Test
    fun `a clip reaching past its re-probed source blocks the export`() {
        val shrunk = media.map { if (it.id == MediaId("v2")) it.copy(durationUs = 3 * sec) else it }
        val result = ExportPlanner.plan(timeline(), shrunk, settings) as ExportPlanResult.Blocked
        assertEquals(setOf(ExportBlocker.SOURCE_RANGE_INVALID), result.blockers)
        assertEquals(listOf("v2.file"), result.unavailableMedia)
    }

    @Test
    fun `effects and overlay tracks are reported as not exported`() {
        val withEffect = timeline(mainClips = listOf(clip("A", "v1", 0, 0, 4 * sec, listOf(ClipEffect("e", "blur")))))
        val extra = withEffect.copy(tracks = withEffect.tracks + Track(TrackId("overlay"), TrackKind.VIDEO, listOf(clip("O", "img", 0, 0, sec))))
        val plan = ready(ExportPlanner.plan(extra, media, settings))
        assertEquals(setOf(ExportWarning.EFFECTS_NOT_RENDERED, ExportWarning.EXTRA_TRACKS_IGNORED), plan.warnings)
        val disabled = timeline(mainClips = listOf(clip("A", "v1", 0, 0, 4 * sec, listOf(ClipEffect("e", "blur", enabled = false)))))
        assertTrue(ready(ExportPlanner.plan(disabled, media, settings)).warnings.isEmpty())
    }

    // ---------------- Settings and options ----------------

    @Test
    fun `output frame rates round to whole numbers`() {
        assertEquals(30, ExportSettings(16, 16, FrameRate.FPS_29_97).outputFps)
        assertEquals(24, ExportSettings(16, 16, FrameRate.FPS_23_976).outputFps)
        assertEquals(60, ExportSettings(16, 16, FrameRate.FPS_59_94).outputFps)
        assertEquals(25, ExportSettings(16, 16, FrameRate.FPS_25).outputFps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd sizes are rejected`() {
        ExportSettings(1_081, 1_920, FrameRate.FPS_30)
    }

    @Test
    fun `resolution choices keep the canvas shape with even sides`() {
        assertEquals(
            listOf(ExportResolution(1_080, 1_920), ExportResolution(720, 1_280), ExportResolution(480, 854)),
            ExportOptions.resolutionsFor(1_080, 1_920),
        )
        assertEquals(
            listOf(ExportResolution(3_840, 2_160), ExportResolution(2_560, 1_440), ExportResolution(1_920, 1_080), ExportResolution(1_280, 720), ExportResolution(854, 480)),
            ExportOptions.resolutionsFor(3_840, 2_160),
        )
        assertEquals(listOf(ExportResolution(480, 480)), ExportOptions.resolutionsFor(480, 480))
    }

    @Test
    fun `frame rate choices start with the project's rate without duplicates`() {
        assertEquals(listOf(FrameRate.FPS_29_97, FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_30, FrameRate.FPS_60), ExportOptions.frameRatesFor(FrameRate.FPS_29_97))
        assertEquals(listOf(FrameRate.FPS_30, FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_60), ExportOptions.frameRatesFor(FrameRate.FPS_30))
    }

    @Test
    fun `plans with nothing to play are impossible`() {
        assertNull(runCatching { ExportPlan(settings, emptyList(), emptyList()) }.getOrNull())
    }
}
