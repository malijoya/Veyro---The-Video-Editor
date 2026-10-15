package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clip transforms in real exports. Sources are grey with a white block over
 * their top-left quarter (as stored). Frames decoded back from the saved MP4
 * are measured: where the picture (anything not black) and the block (white)
 * land must match [ClipPlacement], the geometry the editor's handles use.
 *
 * Covers several output resolutions and shapes, a video with 90 degrees of
 * rotation metadata (a phone's portrait clip), an EXIF-rotated photo, crop,
 * fill and opacity.
 */
@RunWith(AndroidJUnit4::class)
class ClipTransformExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "transform-export-test").apply { mkdirs() }
    private val outDir = File(dir, "out").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Stored 320x180 marked video, 1 s. */
    private fun landscapeVideo(rotationDegrees: Int = 0, name: String = "marked-$rotationDegrees.mp4") =
        TestMediaFiles.mp4(File(dir, name), 320, 180, fps = 30, frameCount = 30, rotationDegrees = rotationDegrees, marked = true)

    @Test
    fun placementMatchesTheSharedGeometryAtEveryOutputResolution() {
        val video = landscapeVideo()
        val transform = ClipTransform(positionX = 0.4f, positionY = 0.55f, scale = 0.6f, rotationDegrees = 20f, anchorX = 0.3f, anchorY = 0.7f)
        listOf(640 to 360, 1_280 to 720, 360 to 640, 480 to 480).forEach { (w, h) ->
            val frame = exportFrame(segment(video, MediaKind.VIDEO, transform), w, h, "res-${w}x$h.mp4")
            assertEquals(w, frame.width)
            assertEquals(h, frame.height)
            val placement = ClipPlacement(transform, 320, 180, w, h)
            assertRect("picture at ${w}x$h", expectedBounds(placement, 0f, 0f, 1f, 1f), bounds(frame) { luma(it) > 50 }, w, h)
            assertRect("marker at ${w}x$h", expectedBounds(placement, 0f, 0f, 0.25f, 0.25f), bounds(frame) { luma(it) > 200 }, w, h)
        }
    }

    @Test
    fun portraitPhoneVideoIsUprightAndTransformsApplyToTheUprightPicture() {
        // Stored landscape with 90 degrees of rotation metadata: upright it is 180x320, and the
        // stored top-left block is at the upright top-right.
        val video = landscapeVideo(rotationDegrees = 90)
        val upright = Pair(180, 320)
        val marker = floatArrayOf(0.75f, 0f, 1f, 0.25f)

        // Default transform in a portrait canvas: fills it exactly.
        val plain = exportFrame(segment(video, MediaKind.VIDEO, ClipTransform.DEFAULT), 360, 640, "portrait.mp4")
        val fitted = ClipPlacement(ClipTransform.DEFAULT, upright.first, upright.second, 360, 640)
        assertRect("portrait picture", expectedBounds(fitted, 0f, 0f, 1f, 1f), bounds(plain) { luma(it) > 50 }, 360, 640)
        assertRect("portrait marker", expectedBounds(fitted, marker[0], marker[1], marker[2], marker[3]), bounds(plain) { luma(it) > 200 }, 360, 640)

        // In a landscape canvas, turned a quarter clockwise and moved left.
        val turned = ClipTransform(rotationDegrees = 90f, positionX = 0.35f, scale = 0.8f)
        val frame = exportFrame(segment(video, MediaKind.VIDEO, turned), 640, 360, "portrait-turned.mp4")
        val placement = ClipPlacement(turned, upright.first, upright.second, 640, 360)
        assertRect("turned picture", expectedBounds(placement, 0f, 0f, 1f, 1f), bounds(frame) { luma(it) > 50 }, 640, 360)
        assertRect("turned marker", expectedBounds(placement, marker[0], marker[1], marker[2], marker[3]), bounds(frame) { luma(it) > 200 }, 640, 360)
    }

    @Test
    fun exifRotatedPhotoCropAndFill() {
        // Stored 400x300 with EXIF "rotate 90": upright 300x400, block at the upright top-right.
        val photo = TestMediaFiles.markedJpeg(File(dir, "photo.jpg"), 400, 300, ExifInterface.ORIENTATION_ROTATE_90)
        val upright = Pair(300, 400)

        // Cropped to the upright top-right quarter (block inside), filling a landscape canvas.
        val withBlock = ClipTransform(crop = CropRect(0.5f, 0f, 1f, 0.5f), fit = FitMode.FILL)
        val frame = exportFrame(segment(photo, MediaKind.IMAGE, withBlock), 640, 360, "crop-block.mp4")
        val placement = ClipPlacement(withBlock, upright.first, upright.second, 640, 360)
        // Fill: no black anywhere.
        assertNull("Black showing with fill", bounds(frame) { luma(it) < 30 })
        val expected = expectedBounds(placement, 0.75f, 0f, 1f, 0.25f, sourceSpace = true).clipTo(640, 360)
        assertRect("cropped marker", expected, bounds(frame) { luma(it) > 200 }, 640, 360)

        // Cropped to the bottom-left quarter: the block is cut away entirely.
        val without = ClipTransform(crop = CropRect(0f, 0.5f, 0.5f, 1f))
        val empty = exportFrame(segment(photo, MediaKind.IMAGE, without), 640, 360, "crop-empty.mp4")
        assertNull("Cropped-away block visible", bounds(empty) { luma(it) > 200 })
        val fit = ClipPlacement(without, upright.first, upright.second, 640, 360)
        assertRect("cropped picture", expectedBounds(fit, 0f, 0f, 1f, 1f), bounds(empty) { luma(it) > 50 }, 640, 360)
    }

    @Test
    fun opacityDarkensTowardBlack() {
        val video = landscapeVideo()
        fun centreLuma(opacity: Float, name: String): Int {
            val frame = exportFrame(segment(video, MediaKind.VIDEO, ClipTransform(opacity = opacity)), 320, 180, name)
            return luma(frame.getPixel(240, 120)) // Grey, away from the block.
        }
        val full = centreLuma(1f, "opaque.mp4")
        val half = centreLuma(0.5f, "half.mp4")
        val none = centreLuma(0f, "none.mp4")
        assertTrue("Opaque grey expected, got $full", full in 100..145)
        assertTrue("Half opacity should be between black and the opaque grey: $none < $half < $full", half in (none + 10) until (full - 10))
        assertTrue("Zero opacity should be black, got $none", none < 20)
    }

    // ---------------- Helpers ----------------

    private fun segment(file: File, kind: MediaKind, transform: ClipTransform) =
        VideoSegment(Uri.fromFile(file).toString(), kind, 0, 1_000_000, 1_000_000, transform = transform)

    /** Exports [segment] alone at [width] x [height] and decodes the frame at 0.5 s. */
    private fun exportFrame(segment: VideoSegment, width: Int, height: Int, name: String): Bitmap {
        val plan = ExportPlan(ExportSettings(width, height, FrameRate.FPS_30), listOf(segment), emptyList())
        val statuses = runBlocking {
            withTimeout(120_000) {
                ExportPipeline(Media3VideoRenderer(context), AndroidOutputVerifier(), copyPublisher, File(dir, "work"))
                    .run(plan, ExportDestination.Document("unused"), name).toList()
            }
        }
        assertTrue("Expected completion, got ${statuses.last()}", statuses.last() is ExportStatus.Completed)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(File(outDir, name).absolutePath)
            return checkNotNull(retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame" }
                .copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            retriever.release()
        }
    }

    private val copyPublisher = ExportPublisher { file, _, name ->
        val target = File(outDir, name)
        file.copyTo(target, overwrite = true)
        PublishedFile(Uri.fromFile(target).toString(), name)
    }

    /**
     * The canvas rectangle covering a region given as fractions of the
     * picture (or, with [sourceSpace], of the whole upright source), as
     * left, top, right, bottom.
     */
    private fun expectedBounds(placement: ClipPlacement, l: Float, t: Float, r: Float, b: Float, sourceSpace: Boolean = false): FloatArray {
        val corners = listOf(l to t, r to t, r to b, l to b).map { (x, y) ->
            if (sourceSpace) placement.sourceToCanvas(x, y) else placement.pictureToCanvas(x, y)
        }
        return floatArrayOf(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun FloatArray.clipTo(width: Int, height: Int) =
        floatArrayOf(this[0].coerceIn(0f, width.toFloat()), this[1].coerceIn(0f, height.toFloat()), this[2].coerceIn(0f, width.toFloat()), this[3].coerceIn(0f, height.toFloat()))

    /**
     * [actual] matches [expected] (clipped to the frame) within a few pixels:
     * filtering, 4:2:0 chroma and two encodes soften edges.
     */
    private fun assertRect(what: String, expected: FloatArray, actual: Rect?, width: Int, height: Int) {
        assertNotNull("$what: nothing found", actual)
        actual!!
        val e = expected.clipTo(width, height)
        val tolerance = 3f + 0.01f * max(width, height)
        val message = "$what: expected ${e.toList()}, got $actual"
        assertEquals(message, e[0], actual.left.toFloat(), tolerance)
        assertEquals(message, e[1], actual.top.toFloat(), tolerance)
        assertEquals(message, e[2], actual.right.toFloat(), tolerance)
        assertEquals(message, e[3], actual.bottom.toFloat(), tolerance)
    }

    private fun luma(pixel: Int) = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1_000

    /** The rectangle covering every pixel matching [test], or null if none does. */
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
