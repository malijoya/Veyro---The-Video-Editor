package com.editor.veyro.core.model.overlay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A point in canvas pixels, y pointing down. */
public data class CanvasPoint(val x: Float, val y: Float)

/**
 * An overlay's box on the canvas, in canvas pixels: a [width] × [height]
 * rectangle centred on ([centerX], [centerY]) and turned
 * [rotationDegrees] clockwise about its centre.
 */
public data class OverlayBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float,
) {
    private val radians: Double get() = rotationDegrees * PI / 180.0

    /** Whether [point] is inside the box, with [slop] extra pixels on every side (for touch targets). */
    public fun contains(point: CanvasPoint, slop: Float = 0f): Boolean {
        val local = toLocal(point)
        return abs(local.x) <= width / 2 + slop && abs(local.y) <= height / 2 + slop
    }

    /** [point] in the box's own unrotated frame, relative to its centre. */
    public fun toLocal(point: CanvasPoint): CanvasPoint {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val c = cos(-radians)
        val s = sin(-radians)
        return CanvasPoint((dx * c - dy * s).toFloat(), (dx * s + dy * c).toFloat())
    }

    /** A point given in the box's frame (relative to its centre), on the canvas. */
    public fun toCanvas(local: CanvasPoint): CanvasPoint {
        val c = cos(radians)
        val s = sin(radians)
        return CanvasPoint(
            (centerX + local.x * c - local.y * s).toFloat(),
            (centerY + local.x * s + local.y * c).toFloat(),
        )
    }

    /** Corners on the canvas: top-left, top-right, bottom-right, bottom-left (before rotation). */
    public val corners: List<CanvasPoint>
        get() = listOf(
            toCanvas(CanvasPoint(-width / 2, -height / 2)),
            toCanvas(CanvasPoint(width / 2, -height / 2)),
            toCanvas(CanvasPoint(width / 2, height / 2)),
            toCanvas(CanvasPoint(-width / 2, height / 2)),
        )

    /** The axis-aligned rectangle covering the rotated box, as left, top, right, bottom. */
    public val bounds: FloatArray
        get() {
            val points = corners
            return floatArrayOf(
                points.minOf { it.x }, points.minOf { it.y },
                points.maxOf { it.x }, points.maxOf { it.y },
            )
        }
}

/**
 * Overlay geometry shared by every renderer and the editing handles.
 * Platform-independent: content sizes come in from whoever measured them
 * (the text renderer, or the image's dimensions).
 */
public object OverlayGeometry {

    /** The length relative sizes are measured against: the canvas's shorter side. */
    public fun unitPx(canvasWidthPx: Int, canvasHeightPx: Int): Float = min(canvasWidthPx, canvasHeightPx).toFloat()

    /** Font size in canvas pixels, before the overlay's scale. */
    public fun fontSizePx(style: TextStyle, canvasWidthPx: Int, canvasHeightPx: Int): Float =
        style.sizeFraction * unitPx(canvasWidthPx, canvasHeightPx)

    /** Width at which text wraps, in canvas pixels, before the overlay's scale. */
    public fun wrapWidthPx(canvasWidthPx: Int): Float = TextStyle.MAX_LINE_WIDTH_FRACTION * canvasWidthPx

    /**
     * A sticker's size at scale 1: the image's shape, with its longer side
     * [OverlayContent.Sticker.BASE_SIZE_FRACTION] of the canvas's shorter side.
     * An unknown image size gives a square.
     */
    public fun stickerSizePx(imageWidthPx: Int, imageHeightPx: Int, canvasWidthPx: Int, canvasHeightPx: Int): Pair<Float, Float> {
        val longSide = OverlayContent.Sticker.BASE_SIZE_FRACTION * unitPx(canvasWidthPx, canvasHeightPx)
        if (imageWidthPx <= 0 || imageHeightPx <= 0) return longSide to longSide
        val scale = longSide / max(imageWidthPx, imageHeightPx)
        return imageWidthPx * scale to imageHeightPx * scale
    }

    /**
     * The overlay's box on the canvas, from the size of its content at scale
     * 1 ([contentWidthPx] × [contentHeightPx], in canvas pixels).
     */
    public fun box(transform: OverlayTransform, contentWidthPx: Float, contentHeightPx: Float, canvasWidthPx: Int, canvasHeightPx: Int): OverlayBox =
        OverlayBox(
            centerX = transform.centerX * canvasWidthPx,
            centerY = transform.centerY * canvasHeightPx,
            width = contentWidthPx * transform.scale,
            height = contentHeightPx * transform.scale,
            rotationDegrees = transform.rotationDegrees,
        )
}
