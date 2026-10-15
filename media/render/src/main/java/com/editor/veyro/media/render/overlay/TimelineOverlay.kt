package com.editor.veyro.media.render.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.timeline.export.OverlayLayer
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The overlays a composition draws, as values the editor can change while
 * the composition plays. Export sets them once. Thread-safe: written on the
 * main thread, read on Media3's GL thread.
 *
 * @property layers every text and sticker, bottom to top.
 * @property hiddenClipId an overlay left out of the render: while paused,
 *   the editor draws the one being edited itself.
 */
public class LiveOverlays(initial: List<OverlayLayer>) {
    @Volatile
    public var layers: List<OverlayLayer> = initial

    @Volatile
    public var hiddenClipId: String? = null
}

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
 * `[startUs, endUs)`, and a keyframed layer is drawn with its placement at
 * exactly that frame's time ([OverlayLayer.overlayAt]). No clock is involved.
 *
 * For each frame it works out what is visible (the active layers, evaluated
 * at that time) and draws one bitmap with [OverlayPainter]:
 * - Only the region the layers can touch is drawn, not the whole frame, and
 *   it is placed pixel for pixel: Media3 sizes an overlay by its pixel size
 *   relative to the frame, and here the scale is 1 and the anchor is the
 *   region's centre.
 * - When nothing visible changes (static layers between layer boundaries),
 *   the same bitmap, already uploaded to the GPU, is reused. Animated layers
 *   are redrawn on every frame into recycled bitmaps. The bitmap on screen is
 *   never drawn into, so changed content always arrives as a different
 *   bitmap, which Media3 uploads.
 * - Stickers are decoded once per layer, at the largest size their
 *   keyframes reach.
 * - Android draws premultiplied alpha, but Media3's overlay shader blends
 *   straight alpha (`overlay.rgb * a + video.rgb * (1 - a)`). The region is
 *   converted to straight alpha before upload, so translucent text, soft
 *   shadows and sticker edges blend correctly instead of darkening.
 *
 * Stateful and used on Media3's GL thread only: create one per composition.
 */
@OptIn(UnstableApi::class)
internal class TimelineOverlay(
    private val live: LiveOverlays,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    private val images: StickerImages,
    private val painter: OverlayPainter = OverlayPainter(),
) : BitmapOverlay() {

    private var canvasWidth = canvasWidthPx
    private var canvasHeight = canvasHeightPx

    /** One layer as drawn on a frame: the layer with its overlay evaluated at that frame's time. */
    private class Drawn(val layer: OverlayLayer, val overlay: Overlay)

    /** What a frame shows. Frames with equal keys look identical. */
    private class Frame(val key: List<OverlayLayer>, val bitmap: Bitmap, val settings: OverlaySettings)

    /** Nothing to show: one transparent pixel, which leaves the video untouched. */
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val centred = StaticOverlaySettings.Builder().build()
    private var frame: Frame? = null
    private var scratch: ByteBuffer? = null

    /** Bitmaps of earlier frames, reused when a later region has the same size. */
    private val spare = ArrayDeque<Bitmap>()

    /** Decoded sticker images, by URI, rotation and decode size. */
    private val stickers = HashMap<String, Bitmap>()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        // The frame this effect draws on: the Presentation's output, i.e. the export (or preview) size.
        if (videoSize.width != canvasWidth || videoSize.height != canvasHeight) {
            canvasWidth = videoSize.width
            canvasHeight = videoSize.height
            replaceFrame(null)
            stickers.clear()
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = frameAt(presentationTimeUs).bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = frameAt(presentationTimeUs).settings

    override fun release() {
        super.release()
        replaceFrame(null)
        spare.forEach { it.recycle() }
        spare.clear()
        empty.recycle()
        scratch = null
        stickers.clear()
    }

    private fun frameAt(timeUs: Long): Frame {
        val hidden = live.hiddenClipId
        val active = live.layers
            .filter { timeUs in it && it.clipId != hidden }
            .map { Drawn(it, it.overlayAt(timeUs)) }
        // The layers as they look on this frame; equal keys draw identical bitmaps.
        val key = active.map { it.layer.copy(overlay = it.overlay, animation = ClipAnimation.NONE) }
        frame?.takeIf { it.key == key }?.let { return it }
        val rendered = render(key, active)
        replaceFrame(rendered)
        return rendered
    }

    private fun replaceFrame(next: Frame?) {
        val previous = frame?.bitmap
        frame = next
        // The previous bitmap was already uploaded; the GPU keeps its own copy, so it can be drawn into again later.
        if (previous != null && previous !== empty && previous !== next?.bitmap) {
            spare.addLast(previous)
            while (spare.size > MAX_SPARE_BITMAPS) spare.removeFirst().recycle()
        }
    }

    /** A cleared bitmap of the given size: a spare one if one fits, else a new one. Never the bitmap on screen. */
    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val reused = spare.firstOrNull { it.width == width && it.height == height && !it.isRecycled }
        if (reused != null) {
            spare.remove(reused)
            // The last upload left it marked straight-alpha; Canvas draws premultiplied.
            reused.isPremultiplied = true
            reused.eraseColor(Color.TRANSPARENT)
            return reused
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun render(key: List<OverlayLayer>, active: List<Drawn>): Frame {
        if (active.isEmpty()) return Frame(key, empty, centred)
        val images = active.map { loadImage(it.layer) }

        // The region every active layer can touch, in whole pixels, within the frame.
        val union = RectF()
        active.forEachIndexed { i, drawn ->
            val (width, height) = imageSize(drawn.layer, images[i])
            union.union(painter.inkBounds(drawn.overlay, canvasWidth, canvasHeight, width, height))
        }
        val region = Rect(
            max(0, floor(union.left).toInt()),
            max(0, floor(union.top).toInt()),
            minOf(canvasWidth, ceil(union.right).toInt()),
            minOf(canvasHeight, ceil(union.bottom).toInt()),
        )
        if (region.width() <= 0 || region.height() <= 0) return Frame(key, empty, centred) // Entirely off screen.

        val bitmap = obtainBitmap(region.width(), region.height())
        val canvas = Canvas(bitmap)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())
        active.forEachIndexed { i, drawn ->
            val (width, height) = imageSize(drawn.layer, images[i])
            painter.draw(canvas, drawn.overlay, canvasWidth, canvasHeight, images[i], width, height)
        }
        scratch = toStraightAlpha(bitmap, scratch)

        // Pixel for pixel: scale 1, the bitmap's centre on the region's centre (in normalised device coordinates, y up).
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                (region.exactCenterX() / canvasWidth) * 2f - 1f,
                1f - (region.exactCenterY() / canvasHeight) * 2f,
            )
            .build()
        return Frame(key, bitmap, settings)
    }

    /**
     * A sticker's image, decoded once, at the size it is drawn at its
     * largest (keyframes may grow it). Export fails rather than silently
     * leave it out.
     */
    private fun loadImage(layer: OverlayLayer): Bitmap? {
        val uri = layer.imageUri ?: return null
        val (width, height) = imageSize(layer, null)
        val largest = layer.overlay.copy(
            transform = OverlayTransform.coerced(scale = layer.animation.maxScale(layer.overlay.transform.scale)),
        )
        val box = painter.box(largest, canvasWidth, canvasHeight, width, height)
        val needed = ceil(max(box.width, box.height)).toInt().coerceIn(1, MAX_STICKER_DECODE_PX)
        val cacheKey = "$uri|${layer.imageRotationDegrees}|$needed"
        stickers[cacheKey]?.let { return it }
        val image = images.load(uri, layer.imageRotationDegrees, needed)
            ?: throw VideoFrameProcessingException("Sticker image can't be read: $uri")
        stickers[cacheKey] = image
        return image
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

        /** Two spares, so animated frames alternate between bitmaps instead of allocating one per frame. */
        const val MAX_SPARE_BITMAPS = 2
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
