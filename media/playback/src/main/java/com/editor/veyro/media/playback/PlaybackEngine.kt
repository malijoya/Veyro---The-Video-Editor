package com.editor.veyro.media.playback

import androidx.media3.common.Player

/** One item for the engine: a source URI, optionally clipped to a range. */
public data class EngineItem(
    val uri: String,
    val clipStartMs: Long,
    /** End of the clip, or null to play to the end of the file. */
    val clipEndMs: Long?,
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
    public fun play()
    public fun pause()
    public fun seekTo(itemIndex: Int, positionMs: Long)
    public fun release()
}
