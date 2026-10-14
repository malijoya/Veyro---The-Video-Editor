package com.editor.veyro.media.importer

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.editor.veyro.core.model.project.MediaKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads small preview bitmaps for media URIs, sized for list cells.
 *
 * - Prefers the provider's own thumbnail (`ContentResolver.loadThumbnail`, API
 *   29+). Otherwise:
 *   - video: decodes a frame
 *   - image: decodes a subsampled copy
 *   - audio: decodes the embedded cover art, if any
 * - Bitmaps are cached in a memory LRU of about 1/8 of the heap, which is
 *   cleared when the system reports memory pressure.
 * - At most [MAX_CONCURRENT_DECODES] decodes run at once, so fast scrolling
 *   can't start dozens of decoders.
 */
public class ThumbnailLoader(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val appContext = context.applicationContext
    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES)

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    init {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // Drop everything when backgrounded or under pressure. Thumbnails are cheap to recreate.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in API 35", ReplaceWith(""))
            override fun onLowMemory() = cache.evictAll()
        })
    }

    /**
     * Returns a bitmap whose longer side is about [sizePx], or null if there
     * is nothing to show (unreadable file, or audio without cover art).
     */
    public suspend fun load(uri: String, kind: MediaKind, sizePx: Int): Bitmap? {
        require(sizePx > 0) { "sizePx must be positive" }
        val key = "$sizePx|$uri"
        cache.get(key)?.let { return it }
        return withContext(ioDispatcher) {
            decodePermits.withPermit {
                cache.get(key) ?: decode(uri.toUri(), kind, sizePx)?.also { cache.put(key, it) }
            }
        }
    }

    private fun decode(uri: Uri, kind: MediaKind, sizePx: Int): Bitmap? =
        providerThumbnail(uri, sizePx) ?: when (kind) {
            MediaKind.VIDEO -> videoFrame(uri, sizePx)
            MediaKind.IMAGE -> subsampledImage(uri, sizePx)
            MediaKind.AUDIO -> embeddedArt(uri, sizePx)
        }

    private fun providerThumbnail(uri: Uri, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            appContext.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } catch (e: Exception) {
            null // Provider has no thumbnail support or the file is gone. Fall back to decoding.
        }
    }

    private fun videoFrame(uri: Uri, sizePx: Int): Bitmap? = withRetriever(uri) { retriever ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, sizePx) }
        }
    }

    private fun embeddedArt(uri: Uri, sizePx: Int): Bitmap? = withRetriever(uri) { retriever ->
        retriever.embeddedPicture?.let { bytes -> decodeSampled(sizePx) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } }
    }

    private fun subsampledImage(uri: Uri, sizePx: Int): Bitmap? = try {
        val resolver = appContext.contentResolver
        decodeSampled(sizePx) { options -> resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
    } catch (e: Exception) {
        null
    }

    /** Runs [decode] once for bounds, then again with a power-of-two sample size near [sizePx]. */
    private inline fun decodeSampled(sizePx: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private inline fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            block(retriever)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Nothing to recover.
            }
        }
    }

    private fun scaleDown(source: Bitmap, sizePx: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= sizePx) return source
        val scale = sizePx.toFloat() / longest
        val scaled = source.scale(
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private companion object {
        const val MAX_CONCURRENT_DECODES = 3

        fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    }
}
