package com.editor.veyro.feature.editor.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.editor.veyro.core.designsystem.component.LocalThumbnailProvider
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.component.ThumbnailProvider
import com.editor.veyro.core.designsystem.component.ThumbnailRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One filmstrip tile: a frame of [uri] at [timeUs], decoded to about [sizePx]. */
internal data class TileKey(val uri: String, val kind: ThumbnailKind, val timeUs: Long, val sizePx: Int)

/**
 * Frames for timeline filmstrips, loaded on demand and kept in a bounded LRU.
 *
 * - Clip drawing calls [get]. A missing frame is requested once and drawn as
 *   a placeholder.
 * - When the frame arrives, the state-backed map changes and only the
 *   affected clips redraw. Nothing recomposes.
 * - Only tiles that are actually drawn are ever requested. Long timelines cost
 *   the visible tiles plus the cache, never the whole duration.
 * - Decoding and the per-file retriever reuse happen in the shared
 *   `ThumbnailLoader` behind [provider].
 */
@Stable
internal class StripThumbnails(
    private val provider: ThumbnailProvider,
    private val scope: CoroutineScope,
    private val maxTiles: Int = MAX_TILES,
) {
    private val bitmaps = mutableStateMapOf<TileKey, ImageBitmap>()

    // Plain (non-snapshot) bookkeeping, touched only on the main thread.
    private val requested = HashSet<TileKey>()
    private val order = ArrayDeque<TileKey>()

    /** The frame for [key], or null while it loads (or if it can't be decoded). Safe to call while drawing. */
    fun get(key: TileKey): ImageBitmap? {
        bitmaps[key]?.let { return it }
        if (requested.add(key)) {
            scope.launch {
                val bitmap = provider.load(ThumbnailRequest(key.uri, key.kind, key.sizePx, key.timeUs)) ?: return@launch
                bitmaps[key] = bitmap
                order.addLast(key)
                while (order.size > maxTiles) {
                    val evicted = order.removeFirst()
                    bitmaps.remove(evicted)
                    requested.remove(evicted)
                }
            }
        }
        return null
    }

    companion object {
        /** About 300 small tiles at ~128 px: a few tens of MB at most, dropped when the editor closes. */
        const val MAX_TILES = 300

        /**
         * Rounds a frame time to a grid that coarsens as tiles cover more
         * time. Nearby zoom levels then share decoded frames instead of
         * re-decoding for every tiny zoom change.
         */
        fun quantizeTimeUs(timeUs: Long, tileDurationUs: Long): Long {
            var quantum = 250_000L
            while (quantum * 2 <= tileDurationUs) quantum *= 2
            return (timeUs / quantum) * quantum
        }
    }
}

@Composable
internal fun rememberStripThumbnails(): StripThumbnails {
    val provider = LocalThumbnailProvider.current
    val scope = rememberCoroutineScope()
    return remember(provider, scope) { StripThumbnails(provider, scope) }
}
