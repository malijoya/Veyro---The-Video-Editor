package com.editor.veyro.media.export

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformBuilder
import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportPlanResult
import com.editor.veyro.core.timeline.export.ExportPlanner
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.ExportWarning
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports timelines with music and voiceover through the real pipeline
 * (planner, Media3 composition, Transformer), then decodes the MP4's audio
 * with the platform decoder and measures it: where sound starts and stops,
 * fade shapes, volume, mute, and clipping.
 *
 * The test tones are generated on the device, and their waveforms are built
 * from the same samples, as the app's analyser would measure them.
 */
@RunWith(AndroidJUnit4::class)
class AudioExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "audio-export-test").apply { mkdirs() }
    private val outDir = File(dir, "out").apply { mkdirs() }
    private val sec = 1_000_000L
    private val waveforms = HashMap<MediaId, Waveform>()

    /** AAC encoding, priming and frame boundaries move edges by a few tens of milliseconds. */
    private val edgeToleranceUs = 60_000L

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------- Fixtures ----------------

    /** A silent 640×360 video of [seconds]. Its MediaRef may claim sound, to test files whose audio stream went missing. */
    private fun silentVideo(id: String, seconds: Int, claimsAudio: Boolean = false): MediaRef {
        val file = TestMediaFiles.mp4(File(dir, "$id.mp4"), 640, 360, fps = 30, frameCount = seconds * 30)
        return MediaRef(
            id = MediaId(id), uri = Uri.fromFile(file).toString(), kind = MediaKind.VIDEO, displayName = id, mimeType = "video/mp4",
            durationUs = seconds * sec, widthPx = 640, heightPx = 360, rotationDegrees = 0, sizeBytes = file.length(),
            audio = if (claimsAudio) AudioTrackInfo("audio/mp4a-latm", 48_000, 2, null) else null,
        )
    }

    /** A stereo sine tone WAV, with its measured waveform registered for the planner. */
    private fun tone(id: String, seconds: Double, frequency: Double, amplitude: Double, sampleRate: Int = 48_000): MediaRef {
        val samples = TestMediaFiles.tone(sampleRate, channels = 2, seconds = seconds, frequency = frequency, amplitude = amplitude)
        val file = TestMediaFiles.wav(File(dir, "$id.wav"), samples, sampleRate, channels = 2)
        waveforms[MediaId(id)] = WaveformBuilder().apply { add16Bit(samples, samples.size, 2, sampleRate, 0) }.build()
        return MediaRef(
            id = MediaId(id), uri = Uri.fromFile(file).toString(), kind = MediaKind.AUDIO, displayName = id, mimeType = "audio/wav",
            durationUs = (seconds * sec).toLong(), widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = file.length(),
            audio = AudioTrackInfo("audio/raw", sampleRate, 2, null),
        )
    }

    private fun clip(id: String, media: MediaRef, startUs: Long, durationUs: Long, sourceInUs: Long = 0, audio: ClipAudio = ClipAudio.DEFAULT) =
        Clip(ClipId(id), media.id, startUs, sourceInUs, durationUs, audio = audio)

    private fun timeline(video: MediaRef, music: List<Clip> = emptyList(), voice: List<Clip> = emptyList(), musicAudio: TrackAudio = TrackAudio.DEFAULT) = Timeline(
        listOf(
            Track(TrackId("main"), TrackKind.VIDEO, listOf(Clip(ClipId("v"), video.id, 0, 0, video.durationUs))),
            Track(TrackId("music"), TrackKind.AUDIO, music, audio = musicAudio, role = AudioRole.MUSIC),
            Track(TrackId("voice"), TrackKind.AUDIO, voice, role = AudioRole.VOICEOVER),
        ),
        FrameRate.FPS_30,
    )

    private fun plan(timeline: Timeline, media: List<MediaRef>): ExportPlan {
        val result = ExportPlanner.plan(timeline, media, ExportSettings(640, 360, FrameRate.FPS_30), waveforms = waveforms::get)
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan
    }

    /** Exports [plan] and decodes the saved file's audio. */
    private fun export(plan: ExportPlan, name: String): DecodedAudio {
        val pipeline = ExportPipeline(
            Media3VideoRenderer(context),
            AndroidOutputVerifier(),
            { file, _, display -> File(outDir, display).also { file.copyTo(it, overwrite = true) }.let { PublishedFile(Uri.fromFile(it).toString(), display) } },
            File(dir, "work"),
        )
        val last = runBlocking { withTimeout(120_000) { pipeline.run(plan, ExportDestination.Document("unused"), name).toList().last() } }
        assertTrue("Expected completion, got $last", last is ExportStatus.Completed)
        val audio = DecodedAudio.of(File(outDir, name))
        assertNotNull("Exported file has no audio track", audio)
        return audio!!
    }

    private fun assertNear(message: String, expectedUs: Long, actualUs: Long?) {
        assertNotNull(message, actualUs)
        assertTrue("$message: expected ~$expectedUs us, got $actualUs us", abs(actualUs!! - expectedUs) <= edgeToleranceUs)
    }

    // ---------------- Synchronization ----------------

    @Test
    fun musicAndVoiceoverPlayAtTheirTimelinePositions() {
        val video = silentVideo("v", seconds = 4)
        val music = tone("music", 2.0, 440.0, 0.5)
        val voice = tone("voice", 1.0, 880.0, 0.5)
        val audio = export(
            plan(timeline(video, music = listOf(clip("m", music, 1 * sec, 2 * sec)), voice = listOf(clip("vo", voice, 2_500_000, 1 * sec))), listOf(video, music, voice)),
            "sync.mp4",
        )
        // Silent until the music starts at 1.0 s.
        assertTrue("before music: ${audio.rms(100_000, 900_000)}", audio.rms(100_000, 900_000) < 0.01f)
        assertNear("music onset", 1 * sec, audio.onsetUs(0.05f))
        // Music alone, then music + voice, then voice alone (music ended at 3.0 s), then silence after 3.5 s.
        assertTrue(audio.rms(1_200_000, 2_400_000) > 0.2f)
        assertTrue(audio.rms(3_100_000, 3_400_000) > 0.2f)
        assertTrue("after voice: ${audio.rms(3_600_000, 3_950_000)}", audio.rms(3_600_000, 3_950_000) < 0.01f)
        // The voice starts within the music: its onset shows as a level jump at 2.5 s.
        val before = audio.rms(2_300_000, 2_450_000)
        val after = audio.rms(2_550_000, 2_700_000)
        assertTrue("voice adds level at 2.5 s: $before -> $after", after > before * 1.3f)
    }

    @Test
    fun trimmedAudioPlaysTheChosenPartOfTheSource() {
        // The source is silent for 1 s, then a tone: trimming in by 1 s must start the sound right at the clip's start (0.5 s).
        val samples = ShortArray(48_000 * 2) + TestMediaFiles.tone(48_000, 2, 2.0, 440.0, 0.5)
        val file = TestMediaFiles.wav(File(dir, "late.wav"), samples, 48_000, 2)
        val late = MediaRef(
            id = MediaId("late"), uri = Uri.fromFile(file).toString(), kind = MediaKind.AUDIO, displayName = "late", mimeType = "audio/wav",
            durationUs = 3 * sec, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = file.length(),
            audio = AudioTrackInfo("audio/raw", 48_000, 2, null),
        )
        waveforms[late.id] = WaveformBuilder().apply { add16Bit(samples, samples.size, 2, 48_000, 0) }.build()
        val video = silentVideo("v", seconds = 3)
        val audio = export(plan(timeline(video, music = listOf(clip("m", late, 500_000, 2 * sec, sourceInUs = 1 * sec))), listOf(video, late)), "trim.mp4")
        assertNear("trimmed onset", 500_000, audio.onsetUs(0.05f))
    }

    // ---------------- Fades, volume and mute ----------------

    @Test
    fun fadesRampTheLevelInAndOut() {
        val video = silentVideo("v", seconds = 4)
        val music = tone("music", 4.0, 440.0, 0.8)
        val audio = export(
            plan(timeline(video, music = listOf(clip("m", music, 0, 4 * sec, audio = ClipAudio(fadeInUs = 1 * sec, fadeOutUs = 1 * sec)))), listOf(video, music)),
            "fades.mp4",
        )
        val full = audio.rms(1_500_000, 2_500_000)
        val earlyIn = audio.rms(150_000, 350_000) // Fade-in at about 25%.
        val lateIn = audio.rms(650_000, 850_000) // About 75%.
        val lateOut = audio.rms(3_650_000, 3_850_000) // About 25% left.
        assertTrue("full level $full", full > 0.3f)
        assertEquals("fade-in at 25%", 0.25f, earlyIn / full, 0.08f)
        assertEquals("fade-in at 75%", 0.75f, lateIn / full, 0.08f)
        assertEquals("fade-out at 25%", 0.25f, lateOut / full, 0.08f)
    }

    @Test
    fun volumeScalesTheLevel() {
        val video = silentVideo("v", seconds = 4)
        val music = tone("music", 4.0, 440.0, 0.8)
        val audio = export(
            plan(
                timeline(video, music = listOf(clip("loud", music, 0, 1_500_000), clip("half", music, 2 * sec, 1_500_000, audio = ClipAudio(volume = 0.5f)))),
                listOf(video, music),
            ),
            "volume.mp4",
        )
        val ratio = audio.rms(2_300_000, 3_200_000) / audio.rms(300_000, 1_200_000)
        assertEquals("50% volume", 0.5f, ratio, 0.06f)
    }

    @Test
    fun mutedTracksAndClipsAreNotHeard() {
        val video = silentVideo("v", seconds = 4)
        val music = tone("music", 4.0, 440.0, 0.8)
        val voice = tone("voice", 1.0, 880.0, 0.5)
        val audio = export(
            plan(
                timeline(
                    video,
                    music = listOf(clip("m", music, 0, 4 * sec)),
                    voice = listOf(clip("vo", voice, 2 * sec, 1 * sec)),
                    musicAudio = TrackAudio(muted = true),
                ),
                listOf(video, music, voice),
            ),
            "muted.mp4",
        )
        assertTrue("muted music: ${audio.rms(200_000, 1_800_000)}", audio.rms(200_000, 1_800_000) < 0.01f)
        assertNear("voice onset", 2 * sec, audio.onsetUs(0.05f))
    }

    // ---------------- Overlaps and clipping ----------------

    @Test
    fun loudOverlapsAreMixedWithoutClipping() {
        val video = silentVideo("v", seconds = 4)
        val music = tone("music", 4.0, 440.0, 0.95)
        val voice = tone("voice", 2.0, 660.0, 0.95)
        val plan = plan(timeline(video, music = listOf(clip("m", music, 0, 4 * sec)), voice = listOf(clip("vo", voice, 1 * sec, 2 * sec))), listOf(video, music, voice))
        assertTrue(ExportWarning.MIX_LOWERED_TO_PREVENT_CLIPPING in plan.warnings)
        val audio = export(plan, "overlap.mp4")
        assertEquals("hard-clipped samples", 0, audio.clippedSamples)
        assertTrue("peak ${audio.peak}", audio.peak < 0.99f)
        // Both sounds are still there in the overlap, at a sensible level.
        assertTrue(audio.rms(1_300_000, 2_700_000) > 0.3f)
        // Outside the overlap the music is untouched (0.95 peak sine: RMS about 0.67).
        assertEquals(0.67f, audio.rms(3_400_000, 3_900_000), 0.07f)
    }

    // ---------------- Missing audio streams ----------------

    @Test
    fun aVideoWhoseAudioStreamIsMissingExportsSilence() {
        // The project thinks the video has sound, but the file has none (it was replaced, or the probe was wrong).
        val video = silentVideo("v", seconds = 2, claimsAudio = true)
        val plan = plan(timeline(video), listOf(video))
        assertTrue(plan.includeClipAudio)
        val audio = export(plan, "missing-stream.mp4")
        assertTrue("silence: ${audio.rms(0, 2 * sec)}", audio.rms(0, 2 * sec) < 0.01f)
    }
}
