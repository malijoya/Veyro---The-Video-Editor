package com.editor.veyro.media.playback

import android.net.Uri
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor preview path on a real device: [CompositionPlaybackEngine]
 * plays a whole timeline (video, a photo, and music with a fade on its own
 * track) through [PreviewController], to the end, without errors.
 */
@RunWith(AndroidJUnit4::class)
class CompositionPreviewInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "composition-preview-test").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: PreviewController

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { controller.release() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun awaitState(timeoutMs: Long, predicate: (PreviewState) -> Boolean): PreviewState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val state = controller.state.value
            if (predicate(state)) return state
            Thread.sleep(20)
        }
        return controller.state.value
    }

    @Test
    fun aMixedTimelinePlaysToTheEnd() {
        val video = TestMediaFiles.mp4(File(dir, "v.mp4"), 640, 360, fps = 30, frameCount = 45) // 1.5 s
        val photo = TestMediaFiles.image(File(dir, "p.png"), 320, 240, android.graphics.Bitmap.CompressFormat.PNG)
        val song = TestMediaFiles.wav(File(dir, "s.wav"), sampleRate = 44_100, channels = 2, seconds = 3)
        val plan = ExportPlan(
            settings = ExportSettings(640, 360, FrameRate.FPS_30),
            video = listOf(
                VideoSegment(Uri.fromFile(video).toString(), MediaKind.VIDEO, 0, 1_500_000, sourceDurationUs = 1_500_000),
                VideoSegment(Uri.fromFile(photo).toString(), MediaKind.IMAGE, 0, 1_000_000, sourceDurationUs = 1_000_000),
            ),
            audioTracks = listOf(
                listOf(
                    AudioSegment.Gap(500_000),
                    AudioSegment.Clip(
                        Uri.fromFile(song).toString(), 0, 2_000_000, sourceDurationUs = 3_000_000,
                        gain = SegmentGain(GainEnvelope(2_000_000, 0, ClipAudio(volume = 0.6f, fadeInUs = 500_000)), timelineStartUs = 500_000),
                    ),
                ),
            ),
        )
        instrumentation.runOnMainSync {
            controller = PreviewController({ CompositionPlaybackEngine(context) }, scope)
            controller.setPlan(PreviewPlan.forTimeline(plan))
            controller.attach()
        }
        val ready = awaitState(15_000) { it.status == PreviewStatus.READY || it.status == PreviewStatus.ERROR }
        assertNull("Preview error: ${ready.error}", ready.error)
        assertTrue(ready.hasVideo)

        instrumentation.runOnMainSync { controller.play() }
        val ended = awaitState(15_000) { it.status == PreviewStatus.ENDED || it.status == PreviewStatus.ERROR }
        assertNull("Preview error: ${ended.error}", ended.error)
        assertTrue("Status ${ended.status}", ended.status == PreviewStatus.ENDED)

        // Reloading at a position (as after an edit) starts there.
        instrumentation.runOnMainSync { controller.seekTo(1_200_000) }
        val again = awaitState(10_000) { it.status == PreviewStatus.READY }
        assertTrue("Position ${again.positionUs}", again.positionUs in 1_000_000..1_400_000)
    }
}
