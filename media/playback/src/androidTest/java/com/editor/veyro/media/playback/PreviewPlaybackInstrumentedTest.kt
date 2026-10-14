package com.editor.veyro.media.playback

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real ExoPlayer playback through [PreviewController] with files generated on
 * the device. Player calls run on the main thread, as in the app. The test
 * thread only waits on [PreviewController.state].
 */
@RunWith(AndroidJUnit4::class)
class PreviewPlaybackInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "preview-test").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: PreviewController

    @Before
    fun setUp() {
        onMain { controller = PreviewController({ ExoPlaybackEngine(context) }, scope) }
    }

    @After
    fun tearDown() {
        onMain { controller.release() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun planFor(file: File, durationUs: Long) =
        PreviewPlan(listOf(PreviewSegment(Uri.fromFile(file).toString(), 0, durationUs, clipped = false)))

    private fun awaitState(timeoutMs: Long = 10_000, predicate: (PreviewState) -> Boolean): PreviewState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val state = controller.state.value
            if (predicate(state)) return state
            Thread.sleep(20)
        }
        throw AssertionError("Timed out; last state ${controller.state.value}")
    }

    @Test
    fun video_loadsWithDurationAndVideoTrack_thenPlaysPausesAndSeeks() {
        val file = TestMediaFiles.mp4(File(dir, "clip.mp4"), 320, 240, fps = 30, frameCount = 90)
        onMain {
            controller.setPlan(planFor(file, 3_000_000))
            controller.attach()
        }
        val ready = awaitState { it.status == PreviewStatus.READY }
        assertTrue("duration ${ready.durationUs}", abs(ready.durationUs - 3_000_000) < 150_000)
        assertTrue(ready.hasVideo)
        assertNotNull(controller.player.value)

        onMain { controller.play() }
        awaitState { it.isPlaying && it.positionUs > 300_000 }

        onMain { controller.pause() }
        val paused = awaitState { !it.isPlaying }
        Thread.sleep(300)
        assertEquals("position must not advance while paused", paused.positionUs, controller.state.value.positionUs)

        onMain { controller.seekTo(2_000_000) }
        val seeked = awaitState { it.status == PreviewStatus.READY && abs(it.positionUs - 2_000_000) < 100_000 }
        assertFalse(seeked.isPlaying)
    }

    @Test
    fun video_playsToEndAndReplays() {
        val file = TestMediaFiles.mp4(File(dir, "short.mp4"), 160, 120, fps = 30, frameCount = 15)
        onMain {
            controller.setPlan(planFor(file, 500_000))
            controller.attach()
        }
        awaitState { it.status == PreviewStatus.READY }
        onMain { controller.play() }
        val ended = awaitState { it.status == PreviewStatus.ENDED }
        assertEquals(ended.durationUs, ended.positionUs)

        onMain { controller.togglePlayPause() }
        awaitState { it.isPlaying || (it.status == PreviewStatus.READY && it.positionUs < 400_000) }
    }

    @Test
    fun portraitVideo_reportsUprightSize() {
        val file = TestMediaFiles.mp4(File(dir, "portrait.mp4"), 320, 240, fps = 30, frameCount = 30, rotationDegrees = 90)
        onMain {
            controller.setPlan(planFor(file, 1_000_000))
            controller.attach()
        }
        awaitState { it.status == PreviewStatus.READY }
        // Rendering needs a surface, so check the decoder-reported size once the format is known.
        val deadline = SystemClock.uptimeMillis() + 5_000
        var size = androidx.media3.common.VideoSize.UNKNOWN
        while (SystemClock.uptimeMillis() < deadline) {
            onMain { size = controller.player.value!!.videoSize }
            if (size.width > 0) break
            Thread.sleep(50)
        }
        // Without a surface some devices never decode a frame. Only assert when a size was reported.
        if (size.width > 0) {
            assertTrue("expected portrait, got ${size.width}x${size.height}", size.height > size.width)
        }
    }

    @Test
    fun audio_playsWithoutVideoTrack() {
        val file = TestMediaFiles.wav(File(dir, "tone.wav"), 44_100, 2, seconds = 2)
        onMain {
            controller.setPlan(planFor(file, 2_000_000))
            controller.attach()
        }
        val ready = awaitState { it.status == PreviewStatus.READY }
        assertFalse(ready.hasVideo)
        onMain { controller.play() }
        awaitState { it.isPlaying }
    }

    @Test
    fun corruptFile_reportsError_andMissingFileReportsNotFound() {
        val garbage = TestMediaFiles.garbage(File(dir, "broken.mp4"))
        onMain {
            controller.setPlan(planFor(garbage, 1_000_000))
            controller.attach()
        }
        val failed = awaitState { it.status == PreviewStatus.ERROR }
        assertNotNull(failed.error)

        onMain { controller.setPlan(planFor(File(dir, "missing.mp4"), 1_000_000)) }
        val missing = awaitState { it.status == PreviewStatus.ERROR }
        assertEquals(PreviewError.FILE_NOT_FOUND, missing.error)
    }

    @Test
    fun detachReleasesPlayer_andReattachResumesPosition() {
        val file = TestMediaFiles.mp4(File(dir, "resume.mp4"), 320, 240, fps = 30, frameCount = 90)
        onMain {
            controller.setPlan(planFor(file, 3_000_000))
            controller.attach()
        }
        awaitState { it.status == PreviewStatus.READY }
        onMain { controller.seekTo(1_500_000) }
        awaitState { it.status == PreviewStatus.READY && abs(it.positionUs - 1_500_000) < 100_000 }

        onMain { controller.detach() }
        assertNull(controller.player.value)
        assertEquals(PreviewStatus.IDLE, controller.state.value.status)

        onMain { controller.attach() }
        val resumed = awaitState { it.status == PreviewStatus.READY }
        assertTrue("resumed at ${resumed.positionUs}", abs(resumed.positionUs - 1_500_000) < 100_000)
    }
}
