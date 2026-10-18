package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionLook
import com.editor.veyro.core.model.transition.TransitionType
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportPlanResult
import com.editor.veyro.core.timeline.export.ExportPlanner
import com.editor.veyro.core.timeline.export.ExportSettings
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transitions in real exports, decoded back frame by frame: the frame
 * before the window shows only the outgoing clip, the frame after it only
 * the incoming one, and every frame inside is the blend the model gives,
 * at several frame rates and sizes. Also checks that the handle layer
 * really plays media from beyond a video clip's edge, and that geometry
 * (wipe edge, slide seam, spin coverage) lands where the model says.
 *
 * Sources are flat grey (photos and videos), so a blend is a level between
 * the two greys and an edge is where the level changes. Whether Media3
 * blends in linear or gamma-encoded light changes the in-between levels, so
 * blends are checked for order and range, not exact values.
 */
@RunWith(AndroidJUnit4::class)
class TransitionExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "transition-export-test").apply { mkdirs() }
    private val outDir = File(dir, "out").apply { mkdirs() }
    private val sec = 1_000_000L
    private val dark = 70
    private val bright = 210

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun dissolveBlendsOnEveryFrameOfItsWindowAtEveryRateAndSize() {
        for ((fps, size) in listOf(FrameRate.FPS_30 to (640 to 360), FrameRate.FPS_24 to (320 to 180), FrameRate.FPS_60 to (1_280 to 720))) {
            val (width, height) = size
            val a = photo("a", dark, width, height)
            val b = photo("b", bright, width, height)
            val plan = plan(twoClips(a, 0, b, 0, sec, Transition(TransitionType.DISSOLVE, 500_000, Easing.Linear), fps), listOf(a, b), width, height, fps)
            val window = plan.transitions.single().window
            val first = fps.usToFrame(window.startUs, FrameRounding.CEIL)
            val levels = levelsAt(export(plan, "dissolve-${fps.fps.toInt()}.mp4"), fps, (first - 1)..(first + window.frames)) { centre(it) }
            val message = "fps=${fps.fps} ${width}x$height levels=$levels"
            assertEquals(message, dark.toDouble(), levels.first(), LEVEL_TOLERANCE)
            assertEquals(message, bright.toDouble(), levels.last(), LEVEL_TOLERANCE)
            val inside = levels.drop(1).dropLast(1)
            assertEquals(message, window.frames, inside.size)
            assertTrue(message, inside.all { it > dark + 3 && it < bright - 3 })
            assertTrue(message, inside.zipWithNext().all { (p, q) -> q >= p - 2 })
            assertTrue(message, inside.last() - inside.first() > (bright - dark) / 2)
        }
    }

    @Test
    fun theHandleLayerPlaysVideoFromBeyondTheClipEdges() {
        val fps = FrameRate.FPS_30
        // A plays the first second of a 2 s dark file (a second left after it); B the second second of a bright one.
        val a = video("a", 60, 640, 360, 2 * sec)
        val b = video("b", 200, 640, 360, 2 * sec)
        val plan = plan(twoClips(a, 0, b, sec, sec, Transition(TransitionType.DISSOLVE, 500_000, Easing.Linear), fps), listOf(a, b), 640, 360, fps)
        val window = plan.transitions.single().window
        assertEquals(15, window.frames)
        val first = fps.usToFrame(window.startUs, FrameRounding.CEIL)
        val levels = levelsAt(export(plan, "handles.mp4"), fps, (first - 1)..(first + window.frames)) { centre(it) }
        val darkLevel = levels.first()
        val brightLevel = levels.last()
        val inside = levels.drop(1).dropLast(1)
        assertTrue("levels=$levels", brightLevel - darkLevel > 100)
        // Both before and after the cut, the picture is a blend: the other clip's handle is on screen under the main track.
        assertTrue("levels=$levels", inside.all { it > darkLevel + 3 && it < brightLevel - 3 })
        assertTrue("levels=$levels", inside.last() > inside.first())
    }

    @Test
    fun aWipesEdgeIsWhereTheModelPutsIt() {
        val fps = FrameRate.FPS_30
        val a = photo("a", dark, 640, 360)
        val b = photo("b", bright, 640, 360)
        val plan = plan(twoClips(a, 0, b, 0, sec, Transition(TransitionType.WIPE_RIGHT, 600_000, Easing.Linear), fps), listOf(a, b), 640, 360, fps)
        val window = plan.transitions.single().window
        val first = fps.usToFrame(window.startUs, FrameRounding.CEIL)
        val frames = listOf(first + 3, first + window.frames / 2, first + window.frames - 4)
        val output = export(plan, "wipe.mp4")
        frames.forEach { frame ->
            val timeUs = fps.frameToUs(frame)
            val p = window.transition.easing.ease(window.progress(timeUs))
            // The incoming picture is revealed from the left: the level crosses half way at the edge's centre.
            val edge = (-TransitionLook.WIPE_SOFTNESS / 2 + p * (1 + TransitionLook.WIPE_SOFTNESS)) * 640
            val crossing = crossingColumn(frameAt(output, timeUs), fromBright = true)
            assertEquals("frame $frame", edge, crossing.toDouble(), EDGE_TOLERANCE_PX)
        }
    }

    @Test
    fun aSlidePushesBothPicturesTogether() {
        val fps = FrameRate.FPS_30
        val a = photo("a", dark, 640, 360)
        val b = photo("b", bright, 640, 360)
        val plan = plan(twoClips(a, 0, b, 0, sec, Transition(TransitionType.SLIDE_LEFT, 600_000, Easing.Linear), fps), listOf(a, b), 640, 360, fps)
        val window = plan.transitions.single().window
        val first = fps.usToFrame(window.startUs, FrameRounding.CEIL)
        val output = export(plan, "slide.mp4")
        listOf(first + 2, first + window.frames / 2, first + window.frames - 3).forEach { frame ->
            val timeUs = fps.frameToUs(frame)
            val p = window.progress(timeUs)
            // The outgoing (dark) picture has moved p of the width left; the incoming (bright) one follows right behind it.
            val seam = (1 - p) * 640
            val crossing = crossingColumn(frameAt(output, timeUs), fromBright = false)
            assertEquals("frame $frame", seam, crossing.toDouble(), EDGE_TOLERANCE_PX)
        }
    }

    @Test
    fun spinsAndZoomsExportAndSpinsKeepTheCanvasCovered() {
        val fps = FrameRate.FPS_30
        for (type in listOf(TransitionType.SPIN_CLOCKWISE, TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT, TransitionType.FADE)) {
            val a = photo("a", 90, 640, 360)
            val b = photo("b", 220, 640, 360)
            val plan = plan(twoClips(a, 0, b, 0, sec, Transition(type, 600_000), fps), listOf(a, b), 640, 360, fps)
            val window = plan.transitions.single().window
            val output = export(plan, "${type.key}.mp4")
            val middle = fps.frameToUs(fps.usToFrame(window.startUs, FrameRounding.CEIL) + window.frames / 2)
            val frame = frameAt(output, middle)
            if (type == TransitionType.SPIN_CLOCKWISE) {
                // Both pictures are scaled to cover the canvas while turned: no black corners.
                listOf(6 to 6, 633 to 6, 6 to 353, 633 to 353).forEach { (x, y) ->
                    assertTrue("$type corner $x,$y = ${level(frame.getPixel(x, y))}", level(frame.getPixel(x, y)) > 60)
                }
            }
            if (type == TransitionType.FADE) {
                // Centred, the middle frame is the first one after the dip: darker than either picture.
                assertTrue("$type middle = ${centre(frame)}", centre(frame) < 90 - 10)
            }
            assertEquals(90.0, centre(frameAt(output, fps.frameToUs(fps.usToFrame(window.startUs, FrameRounding.CEIL) - 1))), LEVEL_TOLERANCE)
            assertEquals(220.0, centre(frameAt(output, window.endUs)), LEVEL_TOLERANCE)
        }
    }

    // ---------------- Helpers ----------------

    private fun photo(name: String, level: Int, width: Int, height: Int): MediaRef {
        val file = TestMediaFiles.greyPng(File(dir, "$name-$level-${width}x$height.png"), width, height, level)
        return MediaRef(
            id = MediaId(file.name), uri = Uri.fromFile(file).toString(), kind = MediaKind.IMAGE, displayName = file.name, mimeType = "image/png",
            durationUs = 0, widthPx = width, heightPx = height, rotationDegrees = 0, sizeBytes = file.length(),
        )
    }

    private fun video(name: String, luma: Int, width: Int, height: Int, durationUs: Long): MediaRef {
        val frames = (durationUs * 30 / sec).toInt()
        val file = TestMediaFiles.mp4(File(dir, "$name-$luma.mp4"), width, height, fps = 30, frameCount = frames, flatLuma = luma)
        return MediaRef(
            id = MediaId(file.name), uri = Uri.fromFile(file).toString(), kind = MediaKind.VIDEO, displayName = file.name, mimeType = "video/mp4",
            durationUs = durationUs, widthPx = width, heightPx = height, rotationDegrees = 0, sizeBytes = file.length(),
        )
    }

    /** Two clips of [lengthUs] back to back, with [transition] between them, on a timeline at [rate]. */
    private fun twoClips(first: MediaRef, firstInUs: Long, second: MediaRef, secondInUs: Long, lengthUs: Long, transition: Transition, rate: FrameRate) = Timeline(
        listOf(
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("a"), first.id, 0, firstInUs, lengthUs, transitionOut = transition),
                    Clip(ClipId("b"), second.id, lengthUs, secondInUs, lengthUs),
                ),
            ),
        ),
        rate,
    )

    private fun plan(timeline: Timeline, media: List<MediaRef>, width: Int, height: Int, fps: FrameRate): ExportPlan {
        val result = ExportPlanner.plan(timeline, media, ExportSettings(width, height, fps))
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan.also { assertEquals(1, it.transitions.size) }
    }

    private fun export(plan: ExportPlan, name: String): File {
        val statuses = runBlocking {
            withTimeout(180_000) {
                ExportPipeline(Media3VideoRenderer(context), AndroidOutputVerifier(), copyPublisher, File(dir, "work"))
                    .run(plan, ExportDestination.Document("unused"), name).toList()
            }
        }
        assertTrue("Expected completion, got ${statuses.last()}", statuses.last() is ExportStatus.Completed)
        return File(outDir, name)
    }

    private val copyPublisher = ExportPublisher { file, _, name ->
        val target = File(outDir, name)
        file.copyTo(target, overwrite = true)
        PublishedFile(Uri.fromFile(target).toString(), name)
    }

    private fun frameAt(file: File, timeUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame at $timeUs" }
                .copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            retriever.release()
        }
    }

    private fun levelsAt(file: File, rate: FrameRate, frames: LongRange, measure: (Bitmap) -> Double): List<Double> =
        frames.map { measure(frameAt(file, rate.frameToUs(it))) }

    /** Mean grey level over a small box in the middle of the frame. */
    private fun centre(bitmap: Bitmap): Double {
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        val values = (-4..4).flatMap { dx -> (-4..4).map { dy -> level(bitmap.getPixel(cx + dx, cy + dy)) } }
        return values.average()
    }

    private fun level(pixel: Int): Double = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3.0

    /** Along the middle row, the first column where the level crosses half way between the greys, going right. */
    private fun crossingColumn(bitmap: Bitmap, fromBright: Boolean): Int {
        val y = bitmap.height / 2
        val half = (dark + bright) / 2.0
        for (x in 0 until bitmap.width) {
            val value = level(bitmap.getPixel(x, y))
            if (if (fromBright) value < half else value > half) return x
        }
        return bitmap.width
    }

    private companion object {
        /** Flat grey survives H.264 within a few levels. */
        const val LEVEL_TOLERANCE = 8.0

        /** A few pixels: the soft edge plus encoder ringing. One frame of these motions is about 35 px. */
        const val EDGE_TOLERANCE_PX = 8.0
    }
}
