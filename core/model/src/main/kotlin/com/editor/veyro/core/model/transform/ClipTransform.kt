package com.editor.veyro.core.model.transform

import com.editor.veyro.core.model.overlay.OverlayTransform
import kotlin.math.max
import kotlin.math.min

/**
 * How a picture's base size is chosen, before [ClipTransform.scale]:
 * - [FIT]: as large as possible with all of it on the canvas (bars where
 *   the shapes differ).
 * - [FILL]: as small as possible while covering the whole canvas (the
 *   overflow is cut off).
 *
 * Both are ratios of the picture's and canvas's shapes, so a clip takes the
 * same share of the frame at every output resolution.
 */
public enum class FitMode { FIT, FILL }

/**
 * A crop shape, as whole numbers ([width]:[height]). Only the ratio matters.
 */
public data class AspectRatio(val width: Int, val height: Int) {
    init {
        require(width in 1..MAX_TERM && height in 1..MAX_TERM) { "Invalid aspect ratio $width:$height" }
    }

    /** Width divided by height. */
    val value: Float get() = width.toFloat() / height

    override fun toString(): String = "$width:$height"

    public companion object {
        public const val MAX_TERM: Int = 10_000

        public val SQUARE: AspectRatio = AspectRatio(1, 1)
        public val PORTRAIT_9_16: AspectRatio = AspectRatio(9, 16)
        public val LANDSCAPE_16_9: AspectRatio = AspectRatio(16, 9)
        public val PORTRAIT_4_5: AspectRatio = AspectRatio(4, 5)
        public val PORTRAIT_3_4: AspectRatio = AspectRatio(3, 4)
        public val LANDSCAPE_4_3: AspectRatio = AspectRatio(4, 3)

        /** The shapes the editor offers, besides free and the source's own. */
        public val PRESETS: List<AspectRatio> = listOf(SQUARE, PORTRAIT_9_16, LANDSCAPE_16_9, PORTRAIT_4_5, PORTRAIT_3_4, LANDSCAPE_4_3)

        /** [width]:[height] in lowest terms, scaled down if needed to fit [MAX_TERM]. Null for non-positive sizes. */
        public fun of(width: Int, height: Int): AspectRatio? {
            if (width <= 0 || height <= 0) return null
            val divisor = gcd(width, height)
            var w = width / divisor
            var h = height / divisor
            if (w > MAX_TERM || h > MAX_TERM) {
                val shrink = max(w, h).toDouble() / MAX_TERM
                w = (w / shrink).toInt().coerceIn(1, MAX_TERM)
                h = (h / shrink).toInt().coerceIn(1, MAX_TERM)
            }
            return AspectRatio(w, h)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * The part of the source picture that is kept, as fractions of the upright
 * source (after its rotation metadata or EXIF orientation is applied):
 * x from [left] to [right], y from [top] to [bottom], with (0, 0) the
 * top-left corner and (1, 1) the bottom-right.
 *
 * Fractions, not pixels, so a crop survives proxies, re-encodes and a file
 * being replaced by another resolution of the same footage.
 */
public data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) { "Crop edges must be within 0..1" }
        require(right - left >= MIN_SIZE - EPSILON && bottom - top >= MIN_SIZE - EPSILON) { "Crop is smaller than $MIN_SIZE: $this" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val isFull: Boolean get() = this == FULL

    /** The crop's shape in pixels, for a source of the given upright size. */
    public fun aspectValue(sourceWidthPx: Float, sourceHeightPx: Float): Float = (width * sourceWidthPx) / (height * sourceHeightPx)

    /** The same crop moved by ([dx], [dy]) (fractions of the source), stopping at the source's edges. */
    public fun moved(dx: Float, dy: Float): CropRect {
        val x = (left + dx).coerceIn(0f, 1f - width)
        val y = (top + dy).coerceIn(0f, 1f - height)
        return CropRect(x, y, (x + width).coerceAtMost(1f), (y + height).coerceAtMost(1f))
    }

    /**
     * The crop with one corner dragged to ([x], [y]) while the opposite
     * corner stays put. [corner] is 0 top-left, 1 top-right, 2 bottom-right,
     * 3 bottom-left. With an [aspect], the crop keeps that shape (in pixels
     * of a [sourceWidthPx] × [sourceHeightPx] source) and the dragged corner
     * goes as far toward the finger as the shape allows.
     */
    public fun withCorner(corner: Int, x: Float, y: Float, aspect: AspectRatio?, sourceWidthPx: Float, sourceHeightPx: Float): CropRect {
        require(corner in 0..3) { "Corner must be 0..3" }
        val leftSide = corner == 0 || corner == 3
        val topSide = corner == 0 || corner == 1
        val fixedX = if (leftSide) right else left
        val fixedY = if (topSide) bottom else top
        // Room between the fixed corner and the source's edge, in the dragged direction.
        val roomX = if (leftSide) fixedX else 1f - fixedX
        val roomY = if (topSide) fixedY else 1f - fixedY
        var w = (if (leftSide) fixedX - x else x - fixedX).coerceIn(min(MIN_SIZE, roomX), roomX)
        var h = (if (topSide) fixedY - y else y - fixedY).coerceIn(min(MIN_SIZE, roomY), roomY)
        if (aspect != null && sourceWidthPx > 0f && sourceHeightPx > 0f) {
            // In pixels: shrink the side that is too long for the shape, then grow both to the minimum and shrink to the room.
            var wPx = w * sourceWidthPx
            var hPx = h * sourceHeightPx
            val ratio = aspect.value
            if (wPx / hPx > ratio) wPx = hPx * ratio else hPx = wPx / ratio
            val grow = max(MIN_SIZE * sourceWidthPx / wPx, MIN_SIZE * sourceHeightPx / hPx)
            if (grow > 1f) {
                wPx *= grow
                hPx *= grow
            }
            val shrink = min(roomX * sourceWidthPx / wPx, roomY * sourceHeightPx / hPx)
            if (shrink < 1f) {
                wPx *= shrink
                hPx *= shrink
            }
            w = (wPx / sourceWidthPx).coerceIn(0f, roomX)
            h = (hPx / sourceHeightPx).coerceIn(0f, roomY)
        }
        val l = if (leftSide) fixedX - w else fixedX
        val t = if (topSide) fixedY - h else fixedY
        return coerced(l, t, l + w, t + h)
    }

    public companion object {
        /** The smallest crop on each side: 5% of the source. */
        public const val MIN_SIZE: Float = 0.05f

        /** Float slack for crops computed from other crops. */
        private const val EPSILON = 1e-4f

        public val FULL: CropRect = CropRect()

        /**
         * A crop from unchecked values (gestures, sliders, hand-edited
         * files): edges are put in order and inside 0..1, and a crop smaller
         * than [MIN_SIZE] grows about its centre. Non-finite values fall back
         * to the full picture's edges.
         */
        public fun coerced(left: Float, top: Float, right: Float, bottom: Float): CropRect {
            fun finite(value: Float, default: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else default
            val (l, r) = span(finite(left, 0f), finite(right, 1f))
            val (t, b) = span(finite(top, 0f), finite(bottom, 1f))
            return CropRect(l, t, r, b)
        }

        /**
         * The largest crop of [aspect] that fits the source, centred as near
         * to ([centerX], [centerY]) as it can be. Null when the source size
         * is unknown.
         */
        public fun forAspect(aspect: AspectRatio, sourceWidthPx: Int, sourceHeightPx: Int, centerX: Float = 0.5f, centerY: Float = 0.5f): CropRect? {
            if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
            val sourceRatio = sourceWidthPx.toFloat() / sourceHeightPx
            val w: Float
            val h: Float
            if (aspect.value > sourceRatio) {
                w = 1f
                h = sourceRatio / aspect.value
            } else {
                w = aspect.value / sourceRatio
                h = 1f
            }
            val cw = w.coerceIn(MIN_SIZE, 1f)
            val ch = h.coerceIn(MIN_SIZE, 1f)
            val l = (centerX - cw / 2).coerceIn(0f, 1f - cw)
            val t = (centerY - ch / 2).coerceIn(0f, 1f - ch)
            return coerced(l, t, l + cw, t + ch)
        }

        /** [a] and [b] in order, at least [MIN_SIZE] apart, inside 0..1. */
        private fun span(a: Float, b: Float): Pair<Float, Float> {
            var lo = min(a, b)
            var hi = max(a, b)
            if (hi - lo < MIN_SIZE) {
                val centre = (lo + hi) / 2
                lo = (centre - MIN_SIZE / 2).coerceIn(0f, 1f - MIN_SIZE)
                hi = lo + MIN_SIZE
            }
            return lo to hi.coerceAtMost(1f)
        }
    }
}

/**
 * Where and how a video or photo clip appears on the canvas. Stored with
 * the clip, in units that don't depend on any screen or output size:
 *
 * - [crop]: the part of the upright source that is kept (see [CropRect]).
 *   The kept part is "the picture" below.
 * - [fit]: the picture's base size: fit inside the canvas, or fill it.
 * - [scale]: a multiplier on that base size. 1 is the base size.
 * - [anchorX], [anchorY]: the pivot, as fractions of the picture (0.5, 0.5
 *   is its centre). Scaling and rotation happen about it.
 * - [positionX], [positionY]: where the pivot sits, as fractions of the
 *   canvas (0.5, 0.5 is the canvas's centre). May lie off the canvas.
 * - [rotationDegrees]: clockwise, about the pivot, in (-180, 180].
 * - [opacity]: 0 (invisible) to 1. Below the main track there is only
 *   black, so a translucent clip is darker.
 * - [cropAspect]: the shape the crop is locked to while it is edited, or
 *   null for a free crop. Rendering only uses [crop].
 *
 * The default transform shows the whole picture centred and fitted, which
 * is how clips looked before transforms existed. [ClipPlacement] turns a
 * transform into canvas geometry.
 */
public data class ClipTransform(
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val crop: CropRect = CropRect.FULL,
    val cropAspect: AspectRatio? = null,
    val fit: FitMode = FitMode.FIT,
    val opacity: Float = 1f,
) {
    init {
        require(positionX.isFinite() && positionX in MIN_POSITION..MAX_POSITION) { "Invalid positionX $positionX" }
        require(positionY.isFinite() && positionY in MIN_POSITION..MAX_POSITION) { "Invalid positionY $positionY" }
        require(scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) { "Invalid scale $scale" }
        require(rotationDegrees.isFinite() && rotationDegrees > -180f && rotationDegrees <= 180f) { "Invalid rotation $rotationDegrees" }
        require(anchorX.isFinite() && anchorX in 0f..1f) { "Invalid anchorX $anchorX" }
        require(anchorY.isFinite() && anchorY in 0f..1f) { "Invalid anchorY $anchorY" }
        require(opacity.isFinite() && opacity in 0f..1f) { "Invalid opacity $opacity" }
    }

    /** Whether this is the untouched default (whole picture, centred, fitted, opaque). */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * A copy with the given values brought into range (see [coerced]); for
     * values from gestures and sliders.
     */
    public fun with(
        positionX: Float = this.positionX,
        positionY: Float = this.positionY,
        scale: Float = this.scale,
        rotationDegrees: Float = this.rotationDegrees,
        anchorX: Float = this.anchorX,
        anchorY: Float = this.anchorY,
        crop: CropRect = this.crop,
        cropAspect: AspectRatio? = this.cropAspect,
        fit: FitMode = this.fit,
        opacity: Float = this.opacity,
    ): ClipTransform = coerced(positionX, positionY, scale, rotationDegrees, anchorX, anchorY, crop, cropAspect, fit, opacity)

    public companion object {
        public const val MIN_POSITION: Float = -1f
        public const val MAX_POSITION: Float = 2f
        public const val MIN_SCALE: Float = 0.05f
        public const val MAX_SCALE: Float = 20f

        public val DEFAULT: ClipTransform = ClipTransform()

        /**
         * A transform from unchecked values: each is brought into range,
         * rotation is wrapped into (-180, 180], and non-finite values fall
         * back to the defaults.
         */
        public fun coerced(
            positionX: Float = 0.5f,
            positionY: Float = 0.5f,
            scale: Float = 1f,
            rotationDegrees: Float = 0f,
            anchorX: Float = 0.5f,
            anchorY: Float = 0.5f,
            crop: CropRect = CropRect.FULL,
            cropAspect: AspectRatio? = null,
            fit: FitMode = FitMode.FIT,
            opacity: Float = 1f,
        ): ClipTransform = ClipTransform(
            positionX = finiteOr(positionX, 0.5f).coerceIn(MIN_POSITION, MAX_POSITION),
            positionY = finiteOr(positionY, 0.5f).coerceIn(MIN_POSITION, MAX_POSITION),
            scale = finiteOr(scale, 1f).coerceIn(MIN_SCALE, MAX_SCALE),
            rotationDegrees = OverlayTransform.normalizeDegrees(finiteOr(rotationDegrees, 0f)),
            anchorX = finiteOr(anchorX, 0.5f).coerceIn(0f, 1f),
            anchorY = finiteOr(anchorY, 0.5f).coerceIn(0f, 1f),
            crop = crop,
            cropAspect = cropAspect,
            fit = fit,
            opacity = finiteOr(opacity, 1f).coerceIn(0f, 1f),
        )

        private fun finiteOr(value: Float, default: Float) = if (value.isFinite()) value else default
    }
}
