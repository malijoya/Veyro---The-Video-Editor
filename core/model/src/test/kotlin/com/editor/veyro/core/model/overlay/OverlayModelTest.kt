package com.editor.veyro.core.model.overlay

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayModelTest {

    private val text = Overlay(OverlayContent.Text("Hello"))
    private val sticker = Overlay(OverlayContent.Sticker)

    // ---------------- Colors ----------------

    @Test
    fun `colors round-trip through their stored hex form`() {
        val color = Argb.of(0x80, 0x12, 0xAB, 0xFF)
        assertEquals("#8012ABFF", color.toHex())
        assertEquals(color, Argb.parse(color.toHex()))
        assertEquals(0x80, color.alpha)
        assertEquals(0x12, color.red)
        assertEquals(0xAB, color.green)
        assertEquals(0xFF, color.blue)
        assertEquals(Argb.WHITE, Argb.parse("#FFFFFF")) // Six digits read as opaque.
        assertEquals("#00000000", Argb.TRANSPARENT.toHex())
        assertNull(Argb.parse("red"))
        assertNull(Argb.parse("#12345"))
        assertEquals(0x40, Argb.WHITE.withAlpha(0x40).alpha)
        assertEquals(0xFFFFFF, Argb.WHITE.withAlpha(0x40).value and 0xFFFFFF)
    }

    // ---------------- Validation ----------------

    @Test
    fun `out-of-range styles and placements are refused, and coerced ones are brought into range`() {
        assertThrows(IllegalArgumentException::class.java) { TextStyle(sizeFraction = 0f) }
        assertThrows(IllegalArgumentException::class.java) { TextStyle(sizeFraction = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TextOutline(Argb.BLACK, widthFraction = 1f) }
        assertThrows(IllegalArgumentException::class.java) { TextShadow(Argb.BLACK, offsetXFraction = 2f) }
        assertThrows(IllegalArgumentException::class.java) { TextBackground(Argb.BLACK, paddingFraction = -1f) }
        assertThrows(IllegalArgumentException::class.java) { OverlayTransform(opacity = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { OverlayTransform(rotationDegrees = -180f) }
        assertThrows(IllegalArgumentException::class.java) { OverlayContent.Text("x".repeat(OverlayContent.Text.MAX_LENGTH + 1)) }

        val coerced = OverlayTransform.coerced(centerX = 9f, centerY = Float.NaN, scale = 0f, rotationDegrees = 540f, opacity = -1f)
        assertEquals(OverlayTransform.MAX_CENTER, coerced.centerX)
        assertEquals(0.5f, coerced.centerY)
        assertEquals(OverlayTransform.MIN_SCALE, coerced.scale)
        assertEquals(180f, coerced.rotationDegrees)
        assertEquals(0f, coerced.opacity)
        assertEquals(-90f, OverlayTransform.normalizeDegrees(270f))
        assertEquals(180f, OverlayTransform.normalizeDegrees(-180f))
    }

    @Test
    fun `unknown font keys fall back to sans-serif, and every font has a generic fallback`() {
        assertEquals(OverlayFont.SANS, OverlayFont.fromKey("comic-papyrus"))
        OverlayFont.entries.forEach { assertEquals(it, OverlayFont.fromKey(it.key)) }
        assertEquals(OverlayFont.entries.size, OverlayFont.entries.map { it.key }.toSet().size)
        assertEquals(GenericFont.SERIF, OverlayFont.SERIF.fallback)
        assertEquals(GenericFont.MONOSPACE, OverlayFont.MONOSPACE.fallback)
    }

    @Test
    fun `blank text and invisible overlays draw nothing`() {
        assertTrue(text.isVisible)
        assertFalse(Overlay(OverlayContent.Text("  \n")).isVisible)
        assertFalse(text.copy(transform = OverlayTransform(opacity = 0f)).isVisible)
        assertTrue(sticker.isVisible)
    }

    // ---------------- Clips and tracks ----------------

    @Test
    fun `text clips have no media, stickers and other clips need media`() {
        Clip(ClipId("t"), null, 0, 0, 1_000, overlay = text)
        Clip(ClipId("s"), MediaId("img"), 0, 0, 1_000, overlay = sticker)
        assertThrows(IllegalArgumentException::class.java) { Clip(ClipId("t"), MediaId("img"), 0, 0, 1_000, overlay = text) }
        assertThrows(IllegalArgumentException::class.java) { Clip(ClipId("s"), null, 0, 0, 1_000, overlay = sticker) }
        assertThrows(IllegalArgumentException::class.java) { Clip(ClipId("v"), null, 0, 0, 1_000) }
    }

    @Test
    fun `overlay tracks hold only overlays and are stacked top first`() {
        val overlayClip = Clip(ClipId("t"), null, 0, 0, 1_000, overlay = text)
        val videoClip = Clip(ClipId("v"), MediaId("v"), 0, 0, 1_000)
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId("o"), TrackKind.OVERLAY, listOf(videoClip)) }
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId("m"), TrackKind.VIDEO, listOf(overlayClip)) }

        val timeline = Timeline(
            listOf(
                Track(TrackId("top"), TrackKind.OVERLAY, listOf(overlayClip)),
                Track(TrackId("bottom"), TrackKind.OVERLAY, emptyList()),
                Track(TrackId("main"), TrackKind.VIDEO, listOf(videoClip)),
            ),
        )
        assertEquals(listOf("top", "bottom"), timeline.overlayTracks.map { it.id.value })
        assertEquals(TrackId("main"), timeline.mainTrack?.id) // Overlay lanes above it don't change the main track.
    }

    // ---------------- Geometry ----------------

    @Test
    fun `sizes are relative to the canvas, so every resolution gets the same layout`() {
        val style = TextStyle(sizeFraction = 0.1f)
        assertEquals(108f, OverlayGeometry.fontSizePx(style, 1_080, 1_920), 0.001f)
        assertEquals(72f, OverlayGeometry.fontSizePx(style, 720, 1_280), 0.001f)
        assertEquals(972f, OverlayGeometry.wrapWidthPx(1_080), 0.001f)

        // A 200 × 100 sticker on a 1080 × 1920 canvas: longer side 40% of 1080.
        val (w, h) = OverlayGeometry.stickerSizePx(200, 100, 1_080, 1_920)
        assertEquals(432f, w, 0.001f)
        assertEquals(216f, h, 0.001f)
        val (sw, sh) = OverlayGeometry.stickerSizePx(0, 0, 1_080, 1_920)
        assertEquals(sw, sh, 0f) // Unknown size: square.
    }

    @Test
    fun `boxes follow the centre, scale and clockwise rotation`() {
        val transform = OverlayTransform(centerX = 0.25f, centerY = 0.75f, scale = 2f, rotationDegrees = 90f)
        val box = OverlayGeometry.box(transform, contentWidthPx = 100f, contentHeightPx = 20f, canvasWidthPx = 1_000, canvasHeightPx = 1_000)
        assertEquals(250f, box.centerX, 0.001f)
        assertEquals(750f, box.centerY, 0.001f)
        assertEquals(200f, box.width, 0.001f)
        assertEquals(40f, box.height, 0.001f)

        // Turned a quarter clockwise, the wide box stands upright: 40 wide, 200 tall.
        val (left, top, right, bottom) = box.bounds.toList()
        assertEquals(230f, left, 0.01f)
        assertEquals(650f, top, 0.01f)
        assertEquals(270f, right, 0.01f)
        assertEquals(850f, bottom, 0.01f)
        // Clockwise: the box's top-left corner ends up at the top-right.
        val topLeft = box.corners[0]
        assertEquals(270f, topLeft.x, 0.01f)
        assertEquals(650f, topLeft.y, 0.01f)

        assertTrue(box.contains(CanvasPoint(250f, 840f)))
        assertFalse(box.contains(CanvasPoint(340f, 750f))) // Inside the unrotated box, outside the rotated one.
        assertTrue(box.contains(CanvasPoint(275f, 750f), slop = 10f))
        val local = box.toLocal(box.toCanvas(CanvasPoint(30f, -7f)))
        assertEquals(30f, local.x, 0.01f)
        assertEquals(-7f, local.y, 0.01f)
    }
}
