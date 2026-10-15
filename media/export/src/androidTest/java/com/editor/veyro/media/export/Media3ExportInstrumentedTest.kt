package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.timeline.export.AudioSegment
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.SegmentGain
import com.editor.veyro.core.timeline.export.VideoSegment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end export on a real device or emulator: Media3 Transformer renders
 * media generated on the device, [AndroidOutputVerifier] probes the result,
 * and the file is then independently checked with `MediaExtractor` and
 * played to the end with ExoPlayer.
 */
@RunWith(AndroidJUnit4::class)
class Media3ExportInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "export-test").apply { mkdirs() }
    private val workDir = File(dir, "work")
    private val outDir = File(dir, "out").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** "Publishes" by copying into [outDir], so the test can inspect the saved file. */
    private val copyPublisher = ExportPublisher { file, _, name ->
        val target = File(outDir, name)
        file.copyTo(target, overwrite = true)
        PublishedFile(Uri.fromFile(target).toString(), name)
    }

    private fun pipeline(publisher: ExportPublisher = copyPublisher) =
        ExportPipeline(Media3VideoRenderer(context), AndroidOutputVerifier(), publisher, workDir)

    private fun uri(file: File) = Uri.fromFile(file).toString()

    /** Plays the sound as recorded: no volume change, fades or headroom. */
    private fun unity(durationUs: Long, sourceInUs: Long, timelineStartUs: Long) =
        SegmentGain(GainEnvelope(durationUs, sourceInUs, ClipAudio.DEFAULT), timelineStartUs)

    private fun runExport(plan: ExportPlan, name: String, publisher: ExportPublisher = copyPublisher): List<ExportStatus> = runBlocking {
        withTimeout(120_000) {
            pipeline(publisher).run(plan, ExportDestination.Document("unused"), name).toList()
        }
    }

    @Test
    fun exportsClipsInOrderWithSourceRangesPhotoAndMixedAudio() {
        val a = TestMediaFiles.mp4(File(dir, "a.mp4"), 640, 360, fps = 30, frameCount = 90) // 3 s
        val b = TestMediaFiles.mp4(File(dir, "b.mp4"), 640, 360, fps = 30, frameCount = 60) // 2 s
        val photo = TestMediaFiles.image(File(dir, "photo.png"), 320, 240, Bitmap.CompressFormat.PNG)
        val song = TestMediaFiles.wav(File(dir, "song.wav"), sampleRate = 44_100, channels = 2, seconds = 4)
        val voice = TestMediaFiles.wav(File(dir, "voice.wav"), sampleRate = 48_000, channels = 1, seconds = 2)

        val plan = ExportPlan(
            settings = ExportSettings(640, 360, FrameRate.FPS_30),
            video = listOf(
                VideoSegment(uri(a), MediaKind.VIDEO, sourceStartUs = 500_000, durationUs = 2_000_000, sourceDurationUs = 3_000_000),
                VideoSegment(uri(photo), MediaKind.IMAGE, sourceStartUs = 0, durationUs = 1_000_000, sourceDurationUs = 1_000_000),
                VideoSegment(uri(b), MediaKind.VIDEO, sourceStartUs = 0, durationUs = 1_500_000, sourceDurationUs = 2_000_000),
            ),
            // Different sample rates and channel counts, with gaps at the start, middle and end.
            audioTracks = listOf(
                listOf(
                    AudioSegment.Gap(500_000),
                    AudioSegment.Clip(uri(song), 1_000_000, 2_000_000, sourceDurationUs = 4_000_000, gain = unity(2_000_000, 1_000_000, timelineStartUs = 500_000)),
                    AudioSegment.Gap(500_000),
                    AudioSegment.Clip(uri(voice), 0, 1_000_000, sourceDurationUs = 2_000_000, gain = unity(1_000_000, 0, timelineStartUs = 3_000_000)),
                    AudioSegment.Gap(500_000),
                ),
            ),
        )

        val statuses = runExport(plan, "mixed.mp4")
        val completed = statuses.last()
        assertTrue("Expected completion, got $completed", completed is ExportStatus.Completed)
        val result = (completed as ExportStatus.Completed).result

        // Progress is the renderer's own, so it only moves forward and stays within 0..100.
        val percents = statuses.filterIsInstance<ExportStatus.Rendering>().mapNotNull { it.percent }
        assertTrue(percents.all { it in 0..100 })
        assertEquals(percents, percents.sorted())

        val saved = File(outDir, "mixed.mp4")
        assertTrue(saved.length() > 0)
        assertEquals(640, result.video.widthPx)
        assertEquals(360, result.video.heightPx)
        assertNotNull(result.video.audioMime)

        // Independent probe of the saved file.
        val probe = probe(saved)
        assertEquals("video/avc", probe.videoMime)
        assertNotNull("Expected an audio track", probe.audioMime)
        assertTrue("Video duration ${probe.videoDurationUs}", abs(probe.videoDurationUs - plan.durationUs) <= 300_000)

        // And it plays to the end.
        val played = playToEnd(saved)
        assertNull("Playback error: ${played.error}", played.error)
        assertTrue("Played duration ${played.durationMs} ms", abs(played.durationMs - plan.durationUs / 1_000) <= 300)
        assertTrue("Temporary files are deleted", workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun portraitExportReportsUprightSize() {
        val clip = TestMediaFiles.mp4(File(dir, "wide.mp4"), 640, 360, fps = 30, frameCount = 30)
        val photo = TestMediaFiles.image(File(dir, "tall.png"), 240, 320, Bitmap.CompressFormat.PNG)
        val plan = ExportPlan(
            settings = ExportSettings(360, 640, FrameRate.FPS_25),
            video = listOf(
                VideoSegment(uri(photo), MediaKind.IMAGE, 0, 1_000_000, sourceDurationUs = 1_000_000),
                VideoSegment(uri(clip), MediaKind.VIDEO, 0, 1_000_000, sourceDurationUs = 1_000_000),
            ),
            audioTracks = emptyList(),
        )
        val completed = runExport(plan, "portrait.mp4").last() as ExportStatus.Completed
        // Transformer may encode portrait frames sideways with a rotation flag; the verified size is upright either way.
        assertEquals(360, completed.result.video.widthPx)
        assertEquals(640, completed.result.video.heightPx)
        assertNull("Video-only plan has no audio track", completed.result.video.audioMime)
        val played = playToEnd(File(outDir, "portrait.mp4"))
        assertNull(played.error)
    }

    @Test
    fun missingSourceFailsAndSavesNothing() {
        var published = false
        val plan = ExportPlan(
            settings = ExportSettings(640, 360, FrameRate.FPS_30),
            video = listOf(VideoSegment(uri(File(dir, "gone.mp4")), MediaKind.VIDEO, 0, 1_000_000, sourceDurationUs = 1_000_000)),
            audioTracks = emptyList(),
        )
        val statuses = runExport(plan, "gone.mp4", publisher = { _, _, _ -> published = true; error("must not publish") })
        val failed = statuses.last()
        assertTrue("Expected failure, got $failed", failed is ExportStatus.Failed)
        assertFalse(statuses.any { it is ExportStatus.Completed })
        assertFalse(published)
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellingStopsTransformerAndDeletesThePartialFile() {
        val clip = TestMediaFiles.mp4(File(dir, "long.mp4"), 640, 360, fps = 30, frameCount = 150) // 5 s
        val plan = ExportPlan(
            settings = ExportSettings(1_280, 720, FrameRate.FPS_30),
            video = List(12) { VideoSegment(uri(clip), MediaKind.VIDEO, 0, 5_000_000, sourceDurationUs = 5_000_000) }, // 60 s
            audioTracks = emptyList(),
        )
        val seen = mutableListOf<ExportStatus>()
        runBlocking {
            val rendering = CountDownLatch(1)
            val job = launch {
                pipeline().run(plan, ExportDestination.Document("unused"), "cancelled.mp4")
                    .takeWhile { it !is ExportStatus.Completed }
                    .collect {
                        seen += it
                        if (it is ExportStatus.Rendering && it.percent != null) rendering.countDown()
                    }
            }
            // Wait for real progress on a worker thread, then cancel.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rendering.await(60, TimeUnit.SECONDS) }
            job.cancel()
            job.join()
        }
        assertFalse(seen.any { it is ExportStatus.Completed || it is ExportStatus.Verifying })
        assertTrue("Partial file deleted", workDir.listFiles().orEmpty().isEmpty())
        assertFalse(File(outDir, "cancelled.mp4").exists())
    }

    // ---------------- Helpers ----------------

    private class Probe(val videoMime: String?, val audioMime: String?, val videoDurationUs: Long)

    private fun probe(file: File): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) video = format
                if (mime.startsWith("audio/") && audio == null) audio = format
            }
            return Probe(
                videoMime = video?.getString(MediaFormat.KEY_MIME),
                audioMime = audio?.getString(MediaFormat.KEY_MIME),
                videoDurationUs = video?.getLong(MediaFormat.KEY_DURATION) ?: 0,
            )
        } finally {
            extractor.release()
        }
    }

    private class Played(val error: PlaybackException?, val durationMs: Long)

    /** Plays [file] with ExoPlayer on the main thread until it ends or fails. */
    private fun playToEnd(file: File): Played {
        val done = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: PlaybackException? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) done.countDown()
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error = e
                        done.countDown()
                    }
                })
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
        assertTrue("Playback didn't finish", done.await(30, TimeUnit.SECONDS))
        var result: Played? = null
        instrumentation.runOnMainSync {
            val p = checkNotNull(player)
            result = Played(error, p.duration)
            p.release()
        }
        return checkNotNull(result)
    }
}
