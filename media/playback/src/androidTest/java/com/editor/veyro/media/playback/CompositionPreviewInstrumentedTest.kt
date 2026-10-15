package com.editor.veyro.media.playback

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.timeline.export.AudioSegment
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.OverlayLayer
import com.editor.veyro.core.timeline.export.SegmentGain
import com.editor.veyro.core.timeline.export.VideoSegment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor preview path on a real device: [CompositionPlaybackEngine]
 * plays a whole timeline (video, a photo, music with a fade on its own
 * track, and text and a sticker over the picture) through
 * [PreviewController], to the end, without errors.
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
            // Across the video-to-photo cut, so the overlay runs over both kinds of item.
            overlays = listOf(
                OverlayLayer("sticker", 0, 2_500_000, Overlay(OverlayContent.Sticker, OverlayTransform(opacity = 0.5f)), imageUri = Uri.fromFile(photo).toString(), imageWidthPx = 320, imageHeightPx = 240),
                OverlayLayer("title", 1_000_000, 2_000_000, Overlay(OverlayContent.Text("Preview 漢字 😀"), OverlayTransform(centerY = 0.8f, rotationDegrees = -15f))),
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

    /**
     * Clip transforms in the preview: a transformed timeline plays, and a
     * plan that changes only transforms is applied to the running
     * composition in place (paused, with a redraw), while any other change
     * is refused so the caller reloads.
     */
    @Test
    fun transformsPlayAndUpdateInPlace() {
        val video = TestMediaFiles.mp4(File(dir, "t.mp4"), 640, 360, fps = 30, frameCount = 45, rotationDegrees = 90)
        val photo = TestMediaFiles.image(File(dir, "t.png"), 320, 240, android.graphics.Bitmap.CompressFormat.PNG)
        fun plan(first: ClipTransform, photoDurationUs: Long = 1_000_000) = ExportPlan(
            settings = ExportSettings(360, 640, FrameRate.FPS_30),
            video = listOf(
                VideoSegment(Uri.fromFile(video).toString(), MediaKind.VIDEO, 0, 1_500_000, sourceDurationUs = 1_500_000, transform = first),
                VideoSegment(
                    Uri.fromFile(photo).toString(), MediaKind.IMAGE, 0, photoDurationUs, sourceDurationUs = photoDurationUs,
                    transform = ClipTransform(rotationDegrees = 30f, crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f), opacity = 0.7f),
                ),
            ),
            audioTracks = emptyList(),
        )
        lateinit var engine: CompositionPlaybackEngine
        instrumentation.runOnMainSync {
            controller = PreviewController({ CompositionPlaybackEngine(context).also { engine = it } }, scope)
            controller.setPlan(PreviewPlan.forTimeline(plan(ClipTransform(scale = 0.5f))))
            controller.attach()
        }
        val ready = awaitState(15_000) { it.status == PreviewStatus.READY || it.status == PreviewStatus.ERROR }
        assertNull("Preview error: ${ready.error}", ready.error)

        var applied = false
        var refused = true
        instrumentation.runOnMainSync {
            controller.seekTo(500_000)
            applied = engine.updateInPlace(plan(ClipTransform(scale = 1.4f, rotationDegrees = 45f, positionX = 0.3f, crop = CropRect(0f, 0f, 0.5f, 1f))))
            refused = !engine.updateInPlace(plan(ClipTransform.DEFAULT, photoDurationUs = 2_000_000))
        }
        assertTrue("Transform-only change not applied in place", applied)
        assertTrue("A timing change must reload", refused)
        Thread.sleep(500) // Let the redraw run.
        assertNull("Preview error after redraw: ${controller.state.value.error}", controller.state.value.error)
        assertFalse(controller.state.value.status == PreviewStatus.ERROR)

        instrumentation.runOnMainSync { controller.play() }
        val ended = awaitState(15_000) { it.status == PreviewStatus.ENDED || it.status == PreviewStatus.ERROR }
        assertNull("Preview error: ${ended.error}", ended.error)
        assertTrue("Status ${ended.status}", ended.status == PreviewStatus.ENDED)
    }
}
