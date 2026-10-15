package com.editor.veyro.media.render.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.media3.common.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayFont
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.overlay.TextBackground
import com.editor.veyro.core.model.overlay.TextStyle
import com.editor.veyro.core.timeline.export.OverlayLayer
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared overlay renderer on a real device: fonts and their fallbacks,
 * glyph fallback for scripts the font lacks, where text lands on the
 * canvas, and the alpha handling Media3's overlay shader needs.
 */
@RunWith(AndroidJUnit4::class)
class OverlayRenderingInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val painter = OverlayPainter()

    // ---------------- Fonts ----------------

    @Test
    fun aMissingFontFamilyFallsBackToItsGenericFamily() {
        val noFamilies = FontResolver { "veyro-no-such-family" }
        assertFalse(noFamilies.isAvailable(OverlayFont.SANS_CONDENSED))
        assertSame(Typeface.SANS_SERIF, noFamilies.typeface(OverlayFont.SANS_CONDENSED, bold = false, italic = false))
        assertSame(Typeface.SERIF, noFamilies.typeface(OverlayFont.HANDWRITING, bold = false, italic = false))
        // Bold still applies to the fallback.
        assertTrue(noFamilies.typeface(OverlayFont.CASUAL, bold = true, italic = false).isBold)

        val real = FontResolver()
        listOf(OverlayFont.SANS, OverlayFont.SERIF, OverlayFont.MONOSPACE).forEach { assertTrue("$it", real.isAvailable(it)) }
        OverlayFont.entries.forEach { font ->
            // Either the device has the family, or the generic fallback is used: never Android's silent default for an unknown name.
            val typeface = real.typeface(font, bold = false, italic = false)
            assertTrue("$font", real.isAvailable(font) || typeface === Typeface.SANS_SERIF || typeface === Typeface.SERIF || typeface === Typeface.MONOSPACE)
        }
    }

    @Test
    fun everyFontDrawsScriptsItDoesNotCoverThroughSystemFallback() {
        val fonts = FontResolver()
        val samples = listOf("Hello", "漢字", "مرحبا", "नमस्ते", "😀")
        OverlayFont.entries.forEach { font ->
            val paint = Paint().apply { typeface = fonts.typeface(font, bold = false, italic = false) }
            samples.forEach { sample -> assertTrue("$font can't draw $sample", paint.hasGlyph(sample)) }
            samples.forEach { sample ->
                val overlay = Overlay(OverlayContent.Text(sample, TextStyle(font = font, sizeFraction = 0.2f, color = Argb.WHITE)))
                val ink = inkBounds(render(overlay, 240, 240))
                assertTrue("$font drew nothing for $sample", ink != null && ink.width() > 4 && ink.height() > 4)
            }
        }
    }

    // ---------------- Position, size, rotation ----------------

    @Test
    fun textLandsCentredOnItsPositionAtTheMeasuredSize() {
        val overlay = boxed(centerX = 0.25f, centerY = 0.75f)
        val canvas = render(overlay, 640, 360)
        val box = painter.box(overlay, 640, 360)
        val ink = inkBounds(canvas)!!
        // The opaque background box is exactly the overlay's box.
        assertEquals(box.centerX, ink.exactCenterX(), 1.5f)
        assertEquals(box.centerY, ink.exactCenterY(), 1.5f)
        assertEquals(box.width, ink.width().toFloat(), 2f)
        assertEquals(box.height, ink.height().toFloat(), 2f)
        assertEquals(160f, box.centerX, 0.01f)
        assertEquals(270f, box.centerY, 0.01f)
    }

    @Test
    fun layoutIsTheSameAtEveryResolution() {
        val overlay = Overlay(
            OverlayContent.Text("A longer caption that has to wrap onto more than one line at this size", TextStyle(sizeFraction = 0.08f)),
        )
        val small = painter.box(overlay, 720, 1_280)
        val large = OverlayPainter().box(overlay, 2_160, 3_840)
        // Three times the canvas: three times the box, give or take rounding of the layout width.
        assertEquals(small.width * 3, large.width, 6f)
        assertEquals(small.height * 3, large.height, 6f)
    }

    @Test
    fun scaleAndClockwiseRotationMatchTheBox() {
        val upright = boxed(centerX = 0.5f, centerY = 0.5f)
        val turned = upright.copy(transform = upright.transform.copy(rotationDegrees = 90f, scale = 1.5f))
        val box = painter.box(turned, 640, 640)
        val ink = inkBounds(render(turned, 640, 640))!!
        // The wide box now stands upright, 1.5 times larger.
        assertTrue(ink.height() > ink.width())
        assertEquals(box.height, ink.width().toFloat(), 3f)
        assertEquals(box.width, ink.height().toFloat(), 3f)
        assertEquals(painter.box(upright, 640, 640).width * 1.5f, box.width, 0.5f)
    }

    // ---------------- Transparency ----------------

    @Test
    fun opacityFadesTheWholeOverlayOnce() {
        val half = boxed(0.5f, 0.5f).let { it.copy(transform = it.transform.copy(opacity = 0.5f)) }
        val bitmap = render(half, 300, 300)
        val pixel = bitmap.getPixel(150, 150) // Inside the box, where box and text overlap.
        assertEquals(128f, Color.alpha(pixel).toFloat(), 2f)
        assertEquals(255f, Color.red(pixel).toFloat(), 3f) // Unpremultiplied by getPixel: still pure red.
    }

    @Test
    fun straightAlphaConversionKeepsTranslucentColours() {
        val bitmap = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.argb(128, 255, 0, 0))
        toStraightAlpha(bitmap)
        assertFalse(bitmap.isPremultiplied)
        val raw = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(raw)
        // Memory is R, G, B, A: full red at half alpha, as Media3's shader expects (premultiplied would store 128).
        assertEquals(255, raw.get(0).toInt() and 0xFF)
        assertEquals(0, raw.get(1).toInt() and 0xFF)
        assertEquals(128f, (raw.get(3).toInt() and 0xFF).toFloat(), 1f)
    }

    // ---------------- Compositing for Media3 ----------------

    @Test
    fun timelineOverlayRedrawsOnlyAtLayerBoundariesAndPlacesRegionsExactly() {
        val first = OverlayLayer("a", 1_000_000, 2_000_000, boxed(0.25f, 0.25f))
        val second = OverlayLayer("b", 1_500_000, 3_000_000, boxed(0.75f, 0.75f))
        val overlay = TimelineOverlay(LiveOverlays(listOf(first, second)), 640, 360, StickerImages(context))
        overlay.configure(Size(640, 360))

        val none = overlay.getBitmap(0)
        assertEquals(1, none.width) // Nothing active: a single transparent pixel.
        val a = overlay.getBitmap(1_000_000)
        val aWidth = a.width
        assertSame(a, overlay.getBitmap(1_400_000)) // Same layers active: same bitmap, no redraw or upload.
        val ab = overlay.getBitmap(1_500_000)
        assertNotSame(a, ab)
        assertTrue(ab.width > aWidth)
        assertEquals(1, overlay.getBitmap(3_000_000).width) // End is exclusive.

        // The region sits where the layer is: its centre maps to the box's centre in normalised coordinates.
        overlay.getBitmap(1_200_000)
        val anchor = overlay.getOverlaySettings(1_200_000).backgroundFrameAnchor
        val box = painter.box(first.overlay, 640, 360)
        assertEquals((box.centerX / 640f) * 2 - 1, anchor.first, 0.02f)
        assertEquals(1 - (box.centerY / 360f) * 2, anchor.second, 0.02f)
        overlay.release()
    }

    // ---------------- Helpers ----------------

    /** Opaque red text on an opaque red box, so the box's pixels are easy to find. */
    private fun boxed(centerX: Float, centerY: Float) = Overlay(
        OverlayContent.Text(
            "MMMM",
            TextStyle(sizeFraction = 0.1f, color = Argb(0xFFFF0000.toInt()), background = TextBackground(Argb(0xFFFF0000.toInt()), paddingFraction = 0.2f, cornerFraction = 0f)),
        ),
        OverlayTransform(centerX = centerX, centerY = centerY),
    )

    private fun render(overlay: Overlay, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(bitmap), overlay, width, height)
        return bitmap
    }

    /** The rectangle of pixels at least half opaque, or null if there are none. */
    private fun inkBounds(bitmap: Bitmap): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 128) {
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
