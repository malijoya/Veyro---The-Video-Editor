package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.Keyframe
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.VideoSegment
import java.io.File
import kotlin.math.max
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
 * Keyframed placement in a real export. The animated clip is the *second*
 * segment, so this also checks the time base: the per-item effects must see
 * composition time (the frame's time on the timeline), as the plan's
 * [com.editor.veyro.core.timeline.export.SegmentTransform] assumes. If they
 * saw time within the item instead, every frame would be placed half a
 * clip off.
 *
 * Frames are decoded back from the MP4 at exact frame times and the picture
 * is measured against [ClipPlacement] of the plan's own evaluation at that
 * time, the same evaluation the preview uses.
 */
@RunWith(AndroidJUnit4::class)
class KeyframeExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "keyframe-export-test").apply { mkdirs() }
    private val outDir = File(dir, "out").apply { mkdirs() }
    private val sec = 1_000_000L

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun keyframedPlacementLandsOnEveryFrameWhereTheModelSays() {
        val video = TestMediaFiles.mp4(File(dir, "clip.mp4"), 320, 180, fps = 30, frameCount = 30, marked = true)
        val uri = Uri.fromFile(video).toString()
        // Half size, sliding from the left third to the right third over its second, turning on the way, eased in and out.
        val slide = ClipAnimation()
            .with(TransformChannel.POSITION_X, Keyframe(0, 0.3, Easing.EASE_IN_OUT))
            .with(TransformChannel.POSITION_X, Keyframe(sec, 0.7))
            .with(TransformChannel.ROTATION, Keyframe(0, 0.0))
            .with(TransformChannel.ROTATION, Keyframe(sec, 90.0))
        val plan = ExportPlan(
            ExportSettings(640, 360, FrameRate.FPS_30),
            listOf(
                VideoSegment(uri, MediaKind.VIDEO, 0, sec, sec),
                VideoSegment(uri, MediaKind.VIDEO, 0, sec, sec, transform = ClipTransform(scale = 0.5f), animation = slide),
            ),
            emptyList(),
        )
        val output = export(plan, "slide.mp4")
        val rate = FrameRate.FPS_30
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            listOf(31L, 38L, 45L, 52L, 58L).forEach { frameIndex ->
                val timeUs = rate.frameToUs(frameIndex)
                val frame = checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame at $timeUs" }
                    .copy(Bitmap.Config.ARGB_8888, false)
                val expected = ClipPlacement(plan.segmentTransforms()[1].at(timeUs), 320, 180, 640, 360)
                val corners = expected.corners
                val box = floatArrayOf(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                assertRect("picture at frame $frameIndex", box, bounds(frame) { luma(it) > 50 }, 640, 360)
            }
        } finally {
            retriever.release()
        }
    }

    // ---------------- Helpers ----------------

    private fun export(plan: ExportPlan, name: String): File {
        val statuses = runBlocking {
            withTimeout(120_000) {
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

    /** Within a few pixels: one frame of this motion is about 10 px, so a frame's offset in time fails. */
    private fun assertRect(what: String, expected: FloatArray, actual: Rect?, width: Int, height: Int) {
        assertNotNull("$what: nothing found", actual)
        actual!!
        val e = floatArrayOf(
            expected[0].coerceIn(0f, width.toFloat()),
            expected[1].coerceIn(0f, height.toFloat()),
            expected[2].coerceIn(0f, width.toFloat()),
            expected[3].coerceIn(0f, height.toFloat()),
        )
        val tolerance = 3f + 0.005f * max(width, height)
        val message = "$what: expected ${e.toList()}, got $actual"
        assertEquals(message, e[0], actual.left.toFloat(), tolerance)
        assertEquals(message, e[1], actual.top.toFloat(), tolerance)
        assertEquals(message, e[2], actual.right.toFloat(), tolerance)
        assertEquals(message, e[3], actual.bottom.toFloat(), tolerance)
    }

    private fun luma(pixel: Int) = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1_000

    private fun bounds(bitmap: Bitmap, test: (Int) -> Boolean): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (test(bitmap.getPixel(x, y))) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }
}
