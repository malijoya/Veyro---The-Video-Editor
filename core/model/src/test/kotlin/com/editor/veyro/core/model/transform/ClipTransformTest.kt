package com.editor.veyro.core.model.transform

import com.editor.veyro.core.model.overlay.CanvasPoint
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTransformTest {

    // ---------------- Values ----------------

    @Test
    fun `the default transform is valid and recognised`() {
        assertTrue(ClipTransform().isDefault)
        assertFalse(ClipTransform(scale = 2f).isDefault)
        assertTrue(CropRect.FULL.isFull)
    }

    @Test
    fun `out-of-range values are rejected, and coerced brings them into range`() {
        assertThrows(IllegalArgumentException::class.java) { ClipTransform(scale = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ClipTransform(rotationDegrees = 270f) }
        assertThrows(IllegalArgumentException::class.java) { ClipTransform(anchorX = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { ClipTransform(opacity = Float.NaN) }
        val coerced = ClipTransform.coerced(positionX = 9f, scale = 100f, rotationDegrees = 270f, anchorY = -1f, opacity = 3f)
        assertEquals(ClipTransform.MAX_POSITION, coerced.positionX)
        assertEquals(ClipTransform.MAX_SCALE, coerced.scale)
        assertEquals(-90f, coerced.rotationDegrees)
        assertEquals(0f, coerced.anchorY)
        assertEquals(1f, coerced.opacity)
        assertEquals(ClipTransform.DEFAULT, ClipTransform.coerced(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN, Float.NaN))
    }

    @Test
    fun `crops are put in order and never smaller than the minimum`() {
        assertThrows(IllegalArgumentException::class.java) { CropRect(0.5f, 0f, 0.4f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { CropRect(0f, 0f, 0.01f, 1f) }
        assertEquals(CropRect(0.2f, 0.1f, 0.8f, 0.9f), CropRect.coerced(0.8f, 0.9f, 0.2f, 0.1f))
        val tiny = CropRect.coerced(0.5f, 0f, 0.5f, 1f)
        assertEquals(CropRect.MIN_SIZE, tiny.width, 1e-6f)
        assertEquals(0.5f, tiny.centerX, 1e-6f)
        val atEdge = CropRect.coerced(1f, 0f, 1f, 1f)
        assertEquals(1f, atEdge.right, 1e-6f)
        assertEquals(CropRect.MIN_SIZE, atEdge.width, 1e-6f)
    }

    @Test
    fun `aspect ratios reduce to lowest terms`() {
        assertEquals(AspectRatio(16, 9), AspectRatio.of(1_920, 1_080))
        assertEquals(AspectRatio(9, 16), AspectRatio.of(1_080, 1_920))
        assertNull(AspectRatio.of(0, 1_080))
        assertEquals("4:5", AspectRatio.PORTRAIT_4_5.toString())
    }

    @Test
    fun `a crop of a given shape is the largest that fits, centred where asked`() {
        // A 1920x1080 source cropped square: full height, 1080/1920 of the width.
        val square = CropRect.forAspect(AspectRatio.SQUARE, 1_920, 1_080)!!
        assertEquals(1f, square.height, 1e-6f)
        assertEquals(1_080f / 1_920f, square.width, 1e-5f)
        assertEquals(1f, square.aspectValue(1_920f, 1_080f), 1e-4f)
        // Asked to centre near the right edge, it stops at the edge.
        val right = CropRect.forAspect(AspectRatio.SQUARE, 1_920, 1_080, centerX = 0.99f)!!
        assertEquals(1f, right.right, 1e-6f)
        // A 9:16 crop of a 9:16 source is the whole source.
        assertEquals(CropRect.FULL, CropRect.forAspect(AspectRatio.PORTRAIT_9_16, 1_080, 1_920))
        assertNull(CropRect.forAspect(AspectRatio.SQUARE, 0, 0))
    }

    @Test
    fun `dragging a crop corner keeps the opposite corner and a locked shape`() {
        val start = CropRect.FULL
        // Free: the corner goes where the finger is.
        val free = start.withCorner(2, 0.6f, 0.7f, aspect = null, sourceWidthPx = 1_920f, sourceHeightPx = 1_080f)
        assertEquals(CropRect(0f, 0f, 0.6f, 0.7f), free)
        // Locked square: the top-left corner stays put and the crop stays square in pixels.
        val square = start.withCorner(2, 0.6f, 0.9f, AspectRatio.SQUARE, 1_920f, 1_080f)
        assertEquals(0f, square.left)
        assertEquals(0f, square.top)
        assertEquals(1f, square.aspectValue(1_920f, 1_080f), 1e-3f)
        // Dragging past the opposite corner stops at the minimum size.
        val collapsed = start.withCorner(0, 2f, 2f, aspect = null, sourceWidthPx = 100f, sourceHeightPx = 100f)
        assertEquals(CropRect.MIN_SIZE, collapsed.width, 1e-6f)
        assertEquals(1f, collapsed.right, 1e-6f)
    }

    @Test
    fun `moving a crop stops at the source's edges`() {
        val crop = CropRect(0.2f, 0.2f, 0.6f, 0.6f)
        val moved = crop.moved(0.9f, -0.5f)
        assertEquals(1f, moved.right, 1e-6f)
        assertEquals(0f, moved.top, 1e-6f)
        assertEquals(crop.width, moved.width, 1e-6f)
    }

    @Test
    fun `only clips on video tracks may carry a transform`() {
        val moved = ClipTransform(scale = 2f)
        Track(TrackId("v"), TrackKind.VIDEO, listOf(Clip(ClipId("a"), MediaId("m"), 0, 0, 1_000, transform = moved)))
        assertThrows(IllegalArgumentException::class.java) {
            Track(TrackId("a"), TrackKind.AUDIO, listOf(Clip(ClipId("a"), MediaId("m"), 0, 0, 1_000, transform = moved)))
        }
    }

    // ---------------- Layout ----------------

    @Test
    fun `by default a landscape picture fits a portrait canvas, centred`() {
        val placement = ClipPlacement(ClipTransform.DEFAULT, 1_920, 1_080, 1_080, 1_920)
        assertEquals(1_080f, placement.width, 0.01f)
        assertEquals(607.5f, placement.height, 0.01f)
        assertPoint(CanvasPoint(0f, 656.25f), placement.corners[0])
        assertPoint(CanvasPoint(1_080f, 1_263.75f), placement.corners[2])
    }

    @Test
    fun `fill covers the canvas and cuts the overflow`() {
        val placement = ClipPlacement(ClipTransform(fit = FitMode.FILL), 1_920, 1_080, 1_080, 1_920)
        assertEquals(1_920f, placement.height, 0.01f)
        assertEquals(3_413.33f, placement.width, 0.05f)
    }

    @Test
    fun `the layout scales exactly with the output resolution`() {
        val transform = ClipTransform(positionX = 0.3f, positionY = 0.7f, scale = 0.6f, rotationDegrees = 25f, anchorX = 0.2f, anchorY = 0.9f, crop = CropRect(0.1f, 0f, 0.8f, 0.75f))
        val small = ClipPlacement(transform, 1_920, 1_080, 1_280, 720)
        val large = ClipPlacement(transform, 1_920, 1_080, 2_560, 1_440)
        val huge = ClipPlacement(transform, 1_920, 1_080, 3_840, 2_160)
        small.corners.zip(large.corners).forEach { (s, l) -> assertPoint(CanvasPoint(s.x * 2, s.y * 2), l, 0.01f) }
        small.corners.zip(huge.corners).forEach { (s, h) -> assertPoint(CanvasPoint(s.x * 3, s.y * 3), h, 0.02f) }
        // A different source resolution of the same shape (a proxy) lands in the same place.
        val proxy = ClipPlacement(transform, 640, 360, 1_280, 720)
        small.corners.zip(proxy.corners).forEach { (s, p) -> assertPoint(s, p, 0.01f) }
    }

    @Test
    fun `rotation is clockwise about the anchor, which stays put`() {
        val anchoredCorner = ClipTransform(positionX = 0.25f, positionY = 0.5f, anchorX = 0f, anchorY = 0f)
        listOf(0f, 33f, 90f, -120f).forEach { degrees ->
            listOf(0.5f, 1f, 3f).forEach { scale ->
                val placement = ClipPlacement(anchoredCorner.with(rotationDegrees = degrees, scale = scale), 400, 200, 1_000, 1_000)
                assertPoint(CanvasPoint(250f, 500f), placement.corners[0])
            }
        }
        // Centred square picture turned 90 degrees clockwise: its top-left corner goes to the top right.
        val turned = ClipPlacement(ClipTransform(rotationDegrees = 90f), 100, 100, 100, 100)
        assertPoint(CanvasPoint(100f, 0f), turned.corners[0])
        assertPoint(CanvasPoint(100f, 100f), turned.corners[1])
    }

    @Test
    fun `canvas and picture coordinates convert both ways`() {
        val placement = ClipPlacement(ClipTransform(positionX = 0.4f, positionY = 0.6f, scale = 1.3f, rotationDegrees = -47f, anchorX = 0.3f, anchorY = 0.1f, crop = CropRect(0.2f, 0.3f, 0.9f, 1f)), 1_920, 1_080, 1_080, 1_920)
        val point = CanvasPoint(0.37f, 0.81f)
        val back = placement.canvasToPicture(placement.pictureToCanvas(point.x, point.y))
        assertPoint(point, back, 1e-4f)
        val source = CanvasPoint(0.5f, 0.5f)
        assertPoint(source, placement.canvasToSource(placement.sourceToCanvas(source.x, source.y)), 1e-4f)
        assertTrue(placement.contains(placement.pictureToCanvas(0.5f, 0.5f)))
        assertFalse(placement.contains(placement.pictureToCanvas(1.2f, 0.5f)))
    }

    @Test
    fun `an unknown source size is taken as the canvas's shape`() {
        val placement = ClipPlacement(ClipTransform.DEFAULT, 0, 0, 1_080, 1_920)
        assertEquals(1_080f, placement.width, 0.01f)
        assertEquals(1_920f, placement.height, 0.01f)
    }

    // ---------------- Edits that keep the picture in place ----------------

    @Test
    fun `moving the anchor doesn't move the picture`() {
        val start = ClipPlacement(ClipTransform(positionX = 0.3f, scale = 0.8f, rotationDegrees = 30f), 1_920, 1_080, 1_080, 1_920)
        val moved = ClipPlacement(start.withAnchor(1f, 0f), 1_920, 1_080, 1_080, 1_920)
        assertEquals(1f, moved.transform.anchorX)
        start.corners.zip(moved.corners).forEach { (a, b) -> assertPoint(a, b, 0.05f) }
        // The pivot is now the top-right corner.
        assertPoint(moved.corners[1], moved.anchor, 0.05f)
    }

    @Test
    fun `cropping keeps the source where it is`() {
        val start = ClipPlacement(ClipTransform(positionX = 0.45f, positionY = 0.55f, scale = 0.9f, rotationDegrees = 15f, anchorX = 0.4f, anchorY = 0.6f), 1_920, 1_080, 1_080, 1_920)
        val crop = CropRect(0.3f, 0.1f, 0.7f, 0.9f)
        val cropped = ClipPlacement(start.withCrop(crop), 1_920, 1_080, 1_080, 1_920)
        // Any source point lands on the same canvas point as before.
        listOf(CanvasPoint(0.3f, 0.1f), CanvasPoint(0.5f, 0.5f), CanvasPoint(0.7f, 0.9f)).forEach { p ->
            assertPoint(start.sourceToCanvas(p.x, p.y), cropped.sourceToCanvas(p.x, p.y), 0.05f)
        }
        // The kept part's corners are the crop's corners.
        assertPoint(start.sourceToCanvas(0.3f, 0.1f), cropped.corners[0], 0.05f)
        assertEquals(crop, cropped.transform.crop)
    }

    @Test
    fun `fit and fill size the turned picture to the canvas`() {
        val canvasW = 1_920
        val canvasH = 1_080
        // A landscape clip turned on its side, fitted: it fills the canvas's height, centred.
        val turned = ClipPlacement(ClipTransform(rotationDegrees = 90f, positionX = 0.1f, scale = 3f), 1_920, 1_080, canvasW, canvasH)
        val fit = ClipPlacement(turned.fitted(FitMode.FIT), 1_920, 1_080, canvasW, canvasH)
        val bounds = fit.bounds
        assertEquals(0f, bounds[1], 0.5f)
        assertEquals(canvasH.toFloat(), bounds[3], 0.5f)
        assertEquals(canvasW / 2f, (bounds[0] + bounds[2]) / 2, 0.5f)
        assertEquals(90f, fit.transform.rotationDegrees)

        // Turned 30 degrees and filled: every canvas corner is on the picture, and only just.
        val tilted = ClipPlacement(ClipTransform(rotationDegrees = 30f), 1_920, 1_080, canvasW, canvasH)
        val fill = ClipPlacement(tilted.fitted(FitMode.FILL), 1_920, 1_080, canvasW, canvasH)
        val canvasCorners = listOf(CanvasPoint(0f, 0f), CanvasPoint(canvasW.toFloat(), 0f), CanvasPoint(canvasW.toFloat(), canvasH.toFloat()), CanvasPoint(0f, canvasH.toFloat()))
        canvasCorners.forEach { assertTrue("$it not covered", fill.contains(it, slop = 0.5f)) }
        val smaller = ClipPlacement(fill.transform.with(scale = fill.transform.scale * 0.98f), 1_920, 1_080, canvasW, canvasH)
        assertFalse(canvasCorners.all { smaller.contains(it) })

        // Upright, fit and fill are just scale 1.
        val upright = ClipPlacement(ClipTransform(positionX = 0.2f, scale = 2f), 1_920, 1_080, 1_080, 1_920)
        assertEquals(1f, upright.fitted(FitMode.FIT).scale, 1e-5f)
        assertEquals(1f, upright.fitted(FitMode.FILL).scale, 1e-5f)
        assertEquals(FitMode.FILL, upright.fitted(FitMode.FILL).fit)
    }

    // ---------------- Render matrices ----------------

    @Test
    fun `the render matrices put every source point where the editor geometry says`() {
        val transforms = listOf(
            ClipTransform.DEFAULT,
            ClipTransform(positionX = 0.3f, positionY = 0.8f, scale = 0.5f, rotationDegrees = 90f),
            ClipTransform(scale = 1.7f, rotationDegrees = -33f, anchorX = 0f, anchorY = 1f, positionX = 0.1f, positionY = 0.9f, crop = CropRect(0.25f, 0.1f, 0.75f, 0.6f), fit = FitMode.FILL),
        )
        val sizes = listOf(Pair(1_920, 1_080) to Pair(1_080, 1_920), Pair(1_080, 1_920) to Pair(1_280, 720), Pair(640, 640) to Pair(720, 1_280))
        transforms.forEach { transform ->
            sizes.forEach { (source, canvas) ->
                val placement = ClipPlacement(transform, source.first, source.second, canvas.first, canvas.second)
                val crop = placement.cropMatrix()
                val place = placement.placementMatrix()
                // Source points inside the crop, as fractions (y down) and as source NDC (y up).
                listOf(CanvasPoint(0.3f, 0.2f), CanvasPoint(0.5f, 0.5f), CanvasPoint(0.7f, 0.55f)).forEach { p ->
                    val ndc = ClipPlacement.applyMatrix(place, ClipPlacement.applyMatrix(crop, 2 * p.x - 1, 1 - 2 * p.y).x, ClipPlacement.applyMatrix(crop, 2 * p.x - 1, 1 - 2 * p.y).y)
                    val expected = placement.sourceToCanvas(p.x, p.y)
                    val actual = CanvasPoint((ndc.x + 1) / 2 * canvas.first, (1 - ndc.y) / 2 * canvas.second)
                    assertPoint(expected, actual, 0.05f)
                }
            }
        }
    }

    @Test
    fun `the crop matrix maps the crop's edges onto the whole frame`() {
        val placement = ClipPlacement(ClipTransform(crop = CropRect(0.25f, 0.1f, 0.75f, 0.6f)), 100, 100, 100, 100)
        val m = placement.cropMatrix()
        // Top-left of the crop (x 0.25, y 0.1 from the top) is NDC (-0.5, 0.8).
        assertPoint(CanvasPoint(-1f, 1f), ClipPlacement.applyMatrix(m, -0.5f, 0.8f), 1e-5f)
        assertPoint(CanvasPoint(1f, -1f), ClipPlacement.applyMatrix(m, 0.5f, -0.2f), 1e-5f)
    }

    private fun assertPoint(expected: CanvasPoint, actual: CanvasPoint, tolerance: Float = 0.01f) {
        assertTrue("expected $expected, got $actual", abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance)
    }
}
