package com.editor.veyro.media.render.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import java.io.IOException

/**
 * Decodes sticker images: upright, with their alpha channel, and no larger
 * than needed. Blocking; call it off the main thread.
 *
 * - The decode is downsampled by powers of two while the image stays at
 *   least [maxLongSidePx] on its longer side, so a 12 MP photo used as a
 *   small sticker doesn't cost 48 MB.
 * - EXIF orientation isn't applied by the platform decoder, so the image is
 *   turned by the project's recorded rotation.
 * - Results are cached (by URI, sample size and rotation), up to
 *   [CACHE_BYTES] in total.
 *
 * Thread-safe.
 */
public class StickerImages(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * The image at [uri], turned [rotationDegrees] clockwise, at least
     * [maxLongSidePx] on its longer side when the file is that big. Null when
     * it can't be read or decoded.
     */
    public fun load(uri: String, rotationDegrees: Int, maxLongSidePx: Int): Bitmap? {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= maxLongSidePx.coerceAtLeast(1)) sample *= 2

        val key = "$uri|$sample|$rotationDegrees"
        cache.get(key)?.let { return it }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888 // Keeps the alpha channel of PNG and WebP stickers.
        }
        val decoded = try {
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return null
        val upright = if (rotationDegrees % 360 == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        }
        cache.put(key, upright)
        return upright
    }

    private companion object {
        const val CACHE_BYTES = 48 * 1024 * 1024
    }
}
