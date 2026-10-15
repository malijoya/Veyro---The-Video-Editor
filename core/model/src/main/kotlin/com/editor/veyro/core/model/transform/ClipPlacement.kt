package com.editor.veyro.core.model.transform

import com.editor.veyro.core.model.overlay.CanvasPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A [ClipTransform] laid out on a canvas: the one geometry the preview, the
 * export and the editing handles share. Platform-independent.
 *
 * ## Coordinate spaces
 * - **Canvas**: the output frame. Stored values are fractions of it
 *   ((0, 0) top-left, (1, 1) bottom-right, y down). This class works in
 *   canvas pixels of a [canvasWidthPx] × [canvasHeightPx] frame, the same
 *   size the renderer draws, whatever the export resolution is.
 * - **Source**: the upright source frame (rotation metadata or EXIF already
 *   applied), as fractions, y down. The crop is given in this space.
 * - **Picture**: the cropped part of the source, as fractions (u, v), y
 *   down. The anchor is given in this space.
 * - **NDC**: OpenGL's normalised device coordinates (-1..1, y up), only for
 *   the render matrices ([cropMatrix], [placementMatrix]).
 *
 * Screen coordinates never get here. The editor divides touch positions by
 * the size of the preview on screen to get canvas fractions first.
 *
 * ## Layout
 * The picture (the source's cropped part, [pictureWidthPx] × [pictureHeightPx]
 * source pixels) is drawn [pixelScale] canvas pixels per source pixel:
 * the [FitMode] base scale times [ClipTransform.scale]. Its anchor point
 * lands on the position, and it is turned clockwise about that point.
 *
 * Every canvas length is proportional to the canvas size, so a placement
 * at 1280 × 720 is exactly half of the one at 2560 × 1440.
 *
 * @param sourceWidthPx the source's upright width in pixels. Only its ratio
 *   to [sourceHeightPx] matters. When unknown (0), the picture is assumed
 *   to have the canvas's shape. The renderer passes the decoded frame's real
 *   size, so stale metadata can't misplace a clip in the output.
 */
public class ClipPlacement(
    public val transform: ClipTransform,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    public val canvasWidthPx: Int,
    public val canvasHeightPx: Int,
) {
    init {
        require(canvasWidthPx > 0 && canvasHeightPx > 0) { "Canvas size must be positive" }
    }

    private val known = sourceWidthPx > 0 && sourceHeightPx > 0

    /** The upright source size used: the given one, or the canvas's when unknown. */
    public val sourceWidthPx: Float = if (known) sourceWidthPx.toFloat() else canvasWidthPx.toFloat()
    public val sourceHeightPx: Float = if (known) sourceHeightPx.toFloat() else canvasHeightPx.toFloat()

    /** The cropped picture, in source pixels. */
    public val pictureWidthPx: Float = this.sourceWidthPx * transform.crop.width
    public val pictureHeightPx: Float = this.sourceHeightPx * transform.crop.height

    /** Canvas pixels per source pixel at scale 1. */
    public val baseScale: Float = baseScale(transform.fit, pictureWidthPx, pictureHeightPx, canvasWidthPx, canvasHeightPx)

    /** Canvas pixels per source pixel. */
    public val pixelScale: Float = baseScale * transform.scale

    /** The picture's size on the canvas, in canvas pixels (before rotation). */
    public val width: Float = pictureWidthPx * pixelScale
    public val height: Float = pictureHeightPx * pixelScale

    /** Where the anchor sits, in canvas pixels. */
    public val anchor: CanvasPoint = CanvasPoint(transform.positionX * canvasWidthPx, transform.positionY * canvasHeightPx)

    private val radians = transform.rotationDegrees * PI / 180.0
    private val cosR = cos(radians)
    private val sinR = sin(radians)

    /** A picture point ([u], [v] fractions of the cropped picture) on the canvas, in canvas pixels. */
    public fun pictureToCanvas(u: Float, v: Float): CanvasPoint {
        val lx = (u - transform.anchorX) * width
        val ly = (v - transform.anchorY) * height
        return CanvasPoint(
            (anchor.x + lx * cosR - ly * sinR).toFloat(),
            (anchor.y + lx * sinR + ly * cosR).toFloat(),
        )
    }

    /** A canvas point (pixels) as fractions of the picture; outside 0..1 when it misses the picture. */
    public fun canvasToPicture(point: CanvasPoint): CanvasPoint {
        val dx = point.x - anchor.x
        val dy = point.y - anchor.y
        // Undo the clockwise turn.
        val lx = dx * cosR + dy * sinR
        val ly = -dx * sinR + dy * cosR
        return CanvasPoint(
            (transform.anchorX + lx / width).toFloat(),
            (transform.anchorY + ly / height).toFloat(),
        )
    }

    /** A source point ([x], [y] fractions of the whole upright source) on the canvas. */
    public fun sourceToCanvas(x: Float, y: Float): CanvasPoint {
        val crop = transform.crop
        return pictureToCanvas((x - crop.left) / crop.width, (y - crop.top) / crop.height)
    }

    /** A canvas point (pixels) as fractions of the whole upright source. */
    public fun canvasToSource(point: CanvasPoint): CanvasPoint {
        val crop = transform.crop
        val picture = canvasToPicture(point)
        return CanvasPoint(crop.left + picture.x * crop.width, crop.top + picture.y * crop.height)
    }

    /** The picture's corners on the canvas: top-left, top-right, bottom-right, bottom-left (before rotation). */
    public val corners: List<CanvasPoint>
        get() = listOf(pictureToCanvas(0f, 0f), pictureToCanvas(1f, 0f), pictureToCanvas(1f, 1f), pictureToCanvas(0f, 1f))

    /** The whole uncropped source's corners on the canvas, in the same order: where cropped-away parts would be. */
    public val sourceCorners: List<CanvasPoint>
        get() = listOf(sourceToCanvas(0f, 0f), sourceToCanvas(1f, 0f), sourceToCanvas(1f, 1f), sourceToCanvas(0f, 1f))

    /** Whether [point] (canvas pixels) is on the picture, with [slop] extra canvas pixels on every side. */
    public fun contains(point: CanvasPoint, slop: Float = 0f): Boolean {
        val p = canvasToPicture(point)
        val slopU = if (width > 0f) slop / width else 0f
        val slopV = if (height > 0f) slop / height else 0f
        return p.x >= -slopU && p.x <= 1f + slopU && p.y >= -slopV && p.y <= 1f + slopV
    }

    /** The axis-aligned rectangle covering the turned picture: left, top, right, bottom, in canvas pixels. */
    public val bounds: FloatArray
        get() {
            val points = corners
            return floatArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }

    // ---------------- Edits that keep the picture in place ----------------

    /**
     * The transform with the pivot moved to ([anchorX], [anchorY]) of the
     * picture, and the position moved with it, so nothing moves on screen.
     */
    public fun withAnchor(anchorX: Float, anchorY: Float): ClipTransform {
        val u = anchorX.coerceIn(0f, 1f)
        val v = anchorY.coerceIn(0f, 1f)
        val at = pictureToCanvas(u, v)
        return transform.with(positionX = at.x / canvasWidthPx, positionY = at.y / canvasHeightPx, anchorX = u, anchorY = v)
    }

    /**
     * The transform with [crop] (and [aspect] lock) instead, adjusted so the
     * source stays exactly where it is on the canvas: only the kept part
     * changes. Scale and position change to compensate (the base size
     * depends on the cropped shape), within their limits.
     */
    public fun withCrop(crop: CropRect, aspect: AspectRatio? = transform.cropAspect): ClipTransform {
        val newPicture = ClipPlacement(transform.copy(crop = crop, scale = 1f), sourceWidthPx.toInt(), sourceHeightPx.toInt(), canvasWidthPx, canvasHeightPx)
        val scale = pixelScale / newPicture.baseScale
        // The anchor stays at the same fractions of the new picture; find where that source point is now.
        val anchorSource = CanvasPoint(crop.left + transform.anchorX * crop.width, crop.top + transform.anchorY * crop.height)
        val at = sourceToCanvas(anchorSource.x, anchorSource.y)
        return transform.with(
            positionX = at.x / canvasWidthPx,
            positionY = at.y / canvasHeightPx,
            scale = scale,
            crop = crop,
            cropAspect = aspect,
        )
    }

    /**
     * The transform with [mode] as its base size, centred (anchor and
     * position), and scaled so the picture, at its current rotation, fits
     * inside the canvas ([FitMode.FIT]) or covers all of it ([FitMode.FILL]).
     * Upright, that is scale 1. Crop, rotation and opacity are kept.
     */
    public fun fitted(mode: FitMode): ClipTransform {
        val base = baseScale(mode, pictureWidthPx, pictureHeightPx, canvasWidthPx, canvasHeightPx)
        val c = abs(cosR).toFloat()
        val s = abs(sinR).toFloat()
        val cw = canvasWidthPx.toFloat()
        val ch = canvasHeightPx.toFloat()
        val pixelScale = when (mode) {
            // The turned picture's bounding box inside the canvas.
            FitMode.FIT -> min(cw / (pictureWidthPx * c + pictureHeightPx * s), ch / (pictureWidthPx * s + pictureHeightPx * c))
            // Every canvas corner inside the turned picture.
            FitMode.FILL -> max((cw * c + ch * s) / pictureWidthPx, (cw * s + ch * c) / pictureHeightPx)
        }
        return transform.with(positionX = 0.5f, positionY = 0.5f, anchorX = 0.5f, anchorY = 0.5f, scale = pixelScale / base, fit = mode)
    }

    // ---------------- Render matrices ----------------

    /**
     * The crop as a 4×4 column-major OpenGL matrix on source NDC: it maps the
     * crop rectangle onto the whole -1..1 square, so everything outside it
     * is clipped away. The frame keeps its pixel size; [placementMatrix]
     * accounts for the stretch.
     */
    public fun cropMatrix(): FloatArray {
        val crop = transform.crop
        // Crop edges in NDC (y up).
        val l = 2 * crop.left - 1
        val r = 2 * crop.right - 1
        val t = 1 - 2 * crop.top
        val b = 1 - 2 * crop.bottom
        val sx = 2 / (r - l)
        val sy = 2 / (t - b)
        return affine(sx, 0f, 0f, sy, -(l + r) / 2 * sx, -(t + b) / 2 * sy)
    }

    /**
     * The placement as a 4×4 column-major OpenGL matrix from picture NDC
     * (the -1..1 square [cropMatrix] produces) to canvas NDC. Scale and
     * rotation are applied in canvas pixels, so nothing shears on a canvas
     * that isn't square.
     */
    public fun placementMatrix(): FloatArray {
        fun canvasNdc(x: Float, y: Float): CanvasPoint {
            // Picture NDC to picture fractions (y down), to canvas pixels, to canvas NDC (y up).
            val p = pictureToCanvas((x + 1) / 2, (1 - y) / 2)
            return CanvasPoint(2 * p.x / canvasWidthPx - 1, 1 - 2 * p.y / canvasHeightPx)
        }
        val origin = canvasNdc(0f, 0f)
        val unitX = canvasNdc(1f, 0f)
        val unitY = canvasNdc(0f, 1f)
        return affine(unitX.x - origin.x, unitX.y - origin.y, unitY.x - origin.x, unitY.y - origin.y, origin.x, origin.y)
    }

    public companion object {
        /** Canvas pixels per picture pixel for [mode] at scale 1. */
        public fun baseScale(mode: FitMode, pictureWidthPx: Float, pictureHeightPx: Float, canvasWidthPx: Int, canvasHeightPx: Int): Float {
            val sx = canvasWidthPx / pictureWidthPx
            val sy = canvasHeightPx / pictureHeightPx
            return if (mode == FitMode.FIT) min(sx, sy) else max(sx, sy)
        }

        /**
         * Applies an affine map `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`
         * to the point ([x], [y]) of a matrix made by this class. For tests
         * and the editor's overlays.
         */
        public fun applyMatrix(matrix: FloatArray, x: Float, y: Float): CanvasPoint =
            CanvasPoint(matrix[0] * x + matrix[4] * y + matrix[12], matrix[1] * x + matrix[5] * y + matrix[13])

        /** The column-major 4×4 matrix of the 2D affine map `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`. */
        private fun affine(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): FloatArray = floatArrayOf(
            a, b, 0f, 0f,
            c, d, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        )
    }
}
