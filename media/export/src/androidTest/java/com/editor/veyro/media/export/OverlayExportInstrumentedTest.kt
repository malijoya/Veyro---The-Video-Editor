package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.overlay.TextBackground
import com.editor.veyro.core.model.overlay.TextStyle
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.OverlayLayer
import com.editor.veyro.core.timeline.export.VideoSegment
import com.editor.veyro.media.render.overlay.OverlayPainter
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
 * Text and stickers in real exports: Transformer renders the overlays, and
 * frames decoded back from the saved MP4 are measured for position, timing
 * and transparency.
 *
 * The source video is flat grey, so anything coloured in the output came
 * from an overlay. Colours are compared with tolerances for H.264's 4:2:0
 * chroma and the two encodes (source and export).
 */
@RunWith(AndroidJUnit4::class)
class OverlayExportInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "overlay-export-test").apply { mkdirs() }
    private val outDir = File(dir, "out").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val width = 640
    private val height = 360

    private val red = Argb(0xFFFF0000.toInt())

    /** Opaque red text on an opaque red box: a solid red rectangle exactly the size of the overlay's box. */
    private val title = Overlay(
        OverlayContent.Text("MMMM", TextStyle(sizeFraction = 0.1f, color = red, background = TextBackground(red, paddingFraction = 0.2f, cornerFraction = 0f))),
        OverlayTransform(centerX = 0.25f, centerY = 0.3f),
    )

    /** Half-transparent sticker whose image is opaque blue on the left and fully transparent on the right. */
    private val sticker = Overlay(OverlayContent.Sticker, OverlayTransform(centerX = 0.75f, centerY = 0.5f, opacity = 0.5f))

    /** Scripts the default font doesn't cover, drawn through system fallback fonts, white on a black box. */
    private val caption = Overlay(
        OverlayContent.Text("漢字 😀 مرحبا", TextStyle(sizeFraction = 0.08f, background = TextBackground(Argb.BLACK, paddingFraction = 0.3f))),
        OverlayTransform(centerX = 0.5f, centerY = 0.85f),
    )

    @Test
    fun overlaysAppearAtTheirPositionsTimesAndTransparency() {
        val video = TestMediaFiles.mp4(File(dir, "grey.mp4"), width, height, fps = 30, frameCount = 60) // 2 s
        val stickerFile = halfTransparentPng(File(dir, "sticker.png"), 100, 100)
        val plan = ExportPlan(
            settings = ExportSettings(width, height, FrameRate.FPS_30),
            video = listOf(VideoSegment(uri(video), MediaKind.VIDEO, 0, 2_000_000, sourceDurationUs = 2_000_000)),
            audioTracks = emptyList(),
            overlays = listOf(
                OverlayLayer("sticker", 0, 2_000_000, sticker, imageUri = uri(stickerFile), imageWidthPx = 100, imageHeightPx = 100),
                OverlayLayer("caption", 0, 2_000_000, caption),
                OverlayLayer("title", 500_000, 1_500_000, title), // On top, for the middle second only.
            ),
        )

        val statuses = runBlocking {
            withTimeout(120_000) {
                ExportPipeline(Media3VideoRenderer(context), AndroidOutputVerifier(), copyPublisher, File(dir, "work"))
                    .run(plan, ExportDestination.Document("unused"), "overlays.mp4").toList()
            }
        }
        assertTrue("Expected completion, got ${statuses.last()}", statuses.last() is ExportStatus.Completed)
        val saved = File(outDir, "overlays.mp4")

        val painter = OverlayPainter()
        val titleBox = painter.box(title, width, height)
        val stickerBox = painter.box(sticker, width, height, 100, 100)
        val captionBox = painter.box(caption, width, height)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(saved.absolutePath)
            val middle = frameAt(retriever, 1_000_000)
            assertEquals(width, middle.width)
            assertEquals(height, middle.height)

            // ---- Position and size: the red box is where the shared painter says, at the size it measured. ----
            val redArea = bounds(middle) { isRed(it) }
            assertTrue("No red title found", redArea != null)
            redArea!!
            assertEquals(titleBox.centerX, redArea.exactCenterX(), 4f)
            assertEquals(titleBox.centerY, redArea.exactCenterY(), 4f)
            assertEquals(titleBox.width, redArea.width().toFloat(), 6f)
            assertEquals(titleBox.height, redArea.height().toFloat(), 6f)

            // ---- Timing: the title is only there between 0.5 s and 1.5 s. ----
            val inTitle = Pair(titleBox.centerX.toInt(), titleBox.centerY.toInt())
            assertTrue(isRed(middle.getPixel(inTitle.first, inTitle.second)))
            listOf(250_000L, 1_750_000L).forEach { time ->
                val frame = frameAt(retriever, time)
                assertTrue("Title visible at $time us", bounds(frame) { isRed(it) } == null)
            }

            // ---- Transparency: half-opaque blue over the video on the left, the video untouched on the right. ----
            val reference = middle.getPixel(20, height / 2) // Video only.
            val left = middle.getPixel((stickerBox.centerX - stickerBox.width / 4).toInt(), stickerBox.centerY.toInt())
            val right = middle.getPixel((stickerBox.centerX + stickerBox.width / 4).toInt(), stickerBox.centerY.toInt())
            assertEquals(0.5f * Color.red(reference), Color.red(left).toFloat(), TOLERANCE)
            assertEquals(127.5f + 0.5f * Color.blue(reference), Color.blue(left).toFloat(), TOLERANCE)
            assertEquals(Color.red(reference).toFloat(), Color.red(right).toFloat(), TOLERANCE)
            assertEquals(Color.blue(reference).toFloat(), Color.blue(right).toFloat(), TOLERANCE)
            // Where the sticker sits: blue-ish pixels lie within its box.
            val blueArea = bounds(middle) { Color.blue(it) - Color.red(it) > 60 }!!
            assertEquals(stickerBox.centerX - stickerBox.width / 4, blueArea.exactCenterX(), 6f)
            assertEquals(stickerBox.width / 2, blueArea.width().toFloat(), 6f)
            assertEquals(stickerBox.height, blueArea.height().toFloat(), 6f)

            // ---- Font fallback: the caption box contains drawn glyphs (bright pixels), not an empty box. ----
            val captionRect = Rect(
                (captionBox.centerX - captionBox.width / 2).toInt() + 4,
                (captionBox.centerY - captionBox.height / 2).toInt() + 4,
                (captionBox.centerX + captionBox.width / 2).toInt() - 4,
                (captionBox.centerY + captionBox.height / 2).toInt() - 4,
            )
            var bright = 0
            var dark = 0
            for (y in captionRect.top until captionRect.bottom) {
                for (x in captionRect.left until captionRect.right) {
                    val luma = luma(middle.getPixel(x, y))
                    if (luma > 180) bright++
                    if (luma < 40) dark++
                }
            }
            assertTrue("Caption glyphs missing: $bright bright pixels", bright > 100)
            assertTrue("Caption box missing: $dark dark pixels", dark > bright)
        } finally {
            retriever.release()
        }
    }

    // ---------------- Helpers ----------------

    private val copyPublisher = ExportPublisher { file, _, name ->
        val target = File(outDir, name)
        file.copyTo(target, overwrite = true)
        PublishedFile(Uri.fromFile(target).toString(), name)
    }

    private fun uri(file: File) = Uri.fromFile(file).toString()

    private fun halfTransparentPng(file: File, w: Int, h: Int): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // Starts fully transparent.
        Canvas(bitmap).drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.BLUE })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap =
        checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)) { "No frame at $timeUs us" }
            .copy(Bitmap.Config.ARGB_8888, false)

    private fun isRed(pixel: Int) = Color.red(pixel) > 190 && Color.green(pixel) < 70 && Color.blue(pixel) < 70

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

    private companion object {
        /** Per channel, for compression and colour conversion. */
        const val TOLERANCE = 24f
    }
}
