package com.editor.veyro.media.render.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.timeline.export.OverlayLayer
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Every text and sticker of a render plan, as one Media3 overlay.
 *
 * Media3 calls [getBitmap] for each output frame with the frame's
 * composition time. That is timeline time in both the editor preview
 * (CompositionPlayer) and export (Transformer), because the overlay runs as a
 * composition-level effect on the single main sequence. (Checked in the
 * Media3 1.11.1 sources: Transformer offsets each item's frames by the
 * length of the items before it, and CompositionPlayer adjusts buffer times
 * to composition time.) So a layer appears on exactly the frames inside its
 * `[startUs, endUs)`.
 *
 * For each set of active layers it draws one bitmap with [OverlayPainter]:
 * - Only the region the layers can touch is drawn, not the whole frame, and
 *   it is placed pixel for pixel: Media3 sizes an overlay by its pixel size
 *   relative to the frame, and here the scale is 1 and the anchor is the
 *   region's centre.
 * - The set only changes at layer boundaries, so between them the same
 *   bitmap (already uploaded to the GPU) is reused.
 * - Android draws premultiplied alpha, but Media3's overlay shader blends
 *   straight alpha (`overlay.rgb * a + video.rgb * (1 - a)`). The region is
 *   converted to straight alpha before upload, so translucent text, soft
 *   shadows and sticker edges blend correctly instead of darkening.
 *
 * Stateful and used on Media3's GL thread only: create one per composition.
 */
@OptIn(UnstableApi::class)
internal class TimelineOverlay(
    private val layers: List<OverlayLayer>,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    private val images: StickerImages,
    private val painter: OverlayPainter = OverlayPainter(),
) : BitmapOverlay() {

    private var canvasWidth = canvasWidthPx
    private var canvasHeight = canvasHeightPx

    private class Frame(val active: List<Int>, val bitmap: Bitmap, val settings: OverlaySettings)

    /** Nothing to show: one transparent pixel, which leaves the video untouched. */
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val centred = StaticOverlaySettings.Builder().build()
    private var frame: Frame? = null
    private var scratch: ByteBuffer? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        // The frame this effect draws on: the Presentation's output, i.e. the export (or preview) size.
        if (videoSize.width != canvasWidth || videoSize.height != canvasHeight) {
            canvasWidth = videoSize.width
            canvasHeight = videoSize.height
            replaceFrame(null)
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = frameAt(presentationTimeUs).bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = frameAt(presentationTimeUs).settings

    override fun release() {
        super.release()
        replaceFrame(null)
        empty.recycle()
        scratch = null
    }

    private fun frameAt(timeUs: Long): Frame {
        val active = layers.indices.filter { timeUs in layers[it] }
        frame?.takeIf { it.active == active }?.let { return it }
        val rendered = render(active)
        replaceFrame(rendered)
        return rendered
    }

    private fun replaceFrame(next: Frame?) {
        val previous = frame
        frame = next
        // The previous bitmap was already uploaded; the GPU keeps its own copy.
        if (previous != null && previous.bitmap !== empty && previous.bitmap !== next?.bitmap) previous.bitmap.recycle()
    }

    private fun render(active: List<Int>): Frame {
        if (active.isEmpty()) return Frame(active, empty, centred)
        val images = active.associateWith { index -> loadImage(layers[index]) }

        // The region every active layer can touch, in whole pixels, within the frame.
        val union = RectF()
        active.forEach { index ->
            val layer = layers[index]
            val (width, height) = imageSize(layer, images[index])
            union.union(painter.inkBounds(layer.overlay, canvasWidth, canvasHeight, width, height))
        }
        val region = Rect(
            max(0, floor(union.left).toInt()),
            max(0, floor(union.top).toInt()),
            minOf(canvasWidth, ceil(union.right).toInt()),
            minOf(canvasHeight, ceil(union.bottom).toInt()),
        )
        if (region.width() <= 0 || region.height() <= 0) return Frame(active, empty, centred) // Entirely off screen.

        val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())
        active.forEach { index ->
            val layer = layers[index]
            val (width, height) = imageSize(layer, images[index])
            painter.draw(canvas, layer.overlay, canvasWidth, canvasHeight, images[index], width, height)
        }
        scratch = toStraightAlpha(bitmap, scratch)

        // Pixel for pixel: scale 1, the bitmap's centre on the region's centre (in normalised device coordinates, y up).
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                (region.exactCenterX() / canvasWidth) * 2f - 1f,
                1f - (region.exactCenterY() / canvasHeight) * 2f,
            )
            .build()
        return Frame(active, bitmap, settings)
    }

    /** A sticker's image, decoded at the size it will be drawn. Export fails rather than silently leave it out. */
    private fun loadImage(layer: OverlayLayer): Bitmap? {
        val uri = layer.imageUri ?: return null
        val (width, height) = imageSize(layer, null)
        val box = painter.box(layer.overlay, canvasWidth, canvasHeight, width, height)
        val needed = ceil(max(box.width, box.height)).toInt().coerceIn(1, MAX_STICKER_DECODE_PX)
        return images.load(uri, layer.imageRotationDegrees, needed)
            ?: throw VideoFrameProcessingException("Sticker image can't be read: $uri")
    }

    private fun imageSize(layer: OverlayLayer, image: Bitmap?): Pair<Int, Int> = when {
        layer.overlay.content !is OverlayContent.Sticker -> 0 to 0
        layer.imageWidthPx > 0 && layer.imageHeightPx > 0 -> layer.imageWidthPx to layer.imageHeightPx
        image != null -> image.width to image.height
        else -> 0 to 0
    }

    private companion object {
        /** Stickers are decoded at most this large, whatever their scale. */
        const val MAX_STICKER_DECODE_PX = 4_096
    }
}

/**
 * Rewrites [bitmap]'s pixels from premultiplied to straight alpha and marks
 * it as such, which is what a GL upload of it then sends. ARGB_8888 pixels
 * sit in memory as R, G, B, A bytes. [scratch] is reused when it is large
 * enough; the buffer used is returned for next time.
 */
internal fun toStraightAlpha(bitmap: Bitmap, scratch: ByteBuffer? = null): ByteBuffer {
    val size = bitmap.byteCount
    val buffer = scratch?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocate(size)
    buffer.clear()
    buffer.limit(size)
    bitmap.copyPixelsToBuffer(buffer)
    val bytes = buffer.array()
    var i = buffer.arrayOffset()
    val end = i + size
    while (i < end) {
        val alpha = bytes[i + 3].toInt() and 0xFF
        if (alpha in 1..254) {
            for (c in 0..2) {
                val value = bytes[i + c].toInt() and 0xFF
                bytes[i + c] = minOf(255, (value * 255 + alpha / 2) / alpha).toByte()
            }
        }
        i += 4
    }
    buffer.rewind()
    bitmap.isPremultiplied = false
    bitmap.copyPixelsFromBuffer(buffer)
    return buffer
}
