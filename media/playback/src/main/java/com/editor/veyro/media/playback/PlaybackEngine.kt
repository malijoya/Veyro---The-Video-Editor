package com.editor.veyro.media.playback

import androidx.media3.common.Player
import com.editor.veyro.core.timeline.export.ExportPlan

/** One item for the engine: a source URI, optionally clipped to a range. */
public data class EngineItem(
    val uri: String,
    val clipStartMs: Long,
    /** End of the clip, or null to play to the end of the file. */
    val clipEndMs: Long?,
    /** For a still image: how long it is held. Null for video and audio. */
    val stillDurationMs: Long? = null,
)

public enum class EngineStatus { IDLE, BUFFERING, READY, ENDED }

/**
 * The narrow slice of a media player that [PreviewController] needs.
 *
 * Production uses [ExoPlaybackEngine]. Tests use a fake, so the controller's
 * lifecycle and timing logic run as plain JVM unit tests. A future timeline
 * engine (for example one built on Media3 compositions) can implement this
 * without the UI changing.
 *
 * All methods must be called on the main thread.
 */
public interface PlaybackEngine {
    /** The Media3 player to render into a surface. Null for engines without video output. */
    public val player: Player?

    public val status: EngineStatus
    public val isPlaying: Boolean
    public val playWhenReady: Boolean
    public val currentItemIndex: Int
    public val currentPositionMs: Long

    /** Duration of the current item, or null while unknown. */
    public val currentItemDurationMs: Long?
    public val hasVideo: Boolean

    /** The Media3 error code of the current failure, or null. */
    public val errorCode: Int?

    /** Called after any change to the values above (except position, which is polled). */
    public fun setListener(listener: (() -> Unit)?)

    /** Replaces the items, positions at [startIndex]/[startPositionMs] and prepares. */
    public fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long)

    /**
     * Replaces what plays with a whole timeline (every track mixed), at
     * [startPositionMs], and prepares. It then reports a single item. Engines
     * that can only play playlists throw.
     */
    public fun loadTimeline(plan: ExportPlan, startPositionMs: Long) {
        throw UnsupportedOperationException("${javaClass.simpleName} can't play timelines")
    }

    /**
     * Shows [plan] instead of the loaded timeline without reloading, when
     * the two differ only in ways the engine can change on the fly (clip
     * transforms, keyframes, text and stickers). Position and play state stay
     * as they are. Returns false when it can't, and the caller then loads
     * [plan] normally.
     */
    public fun updateInPlace(plan: ExportPlan): Boolean = false

    /**
     * Leaves the overlay of clip [clipId] out of the rendered picture (null
     * shows them all), for timelines, without reloading. The setting also
     * applies to timelines loaded later. Engines without overlays ignore it.
     */
    public fun setHiddenOverlay(clipId: String?) {}

    public fun play()
    public fun pause()
    public fun seekTo(itemIndex: Int, positionMs: Long)
    public fun release()
}
