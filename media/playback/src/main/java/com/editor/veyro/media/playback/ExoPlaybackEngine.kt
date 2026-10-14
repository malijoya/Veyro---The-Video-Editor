package com.editor.veyro.media.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * [PlaybackEngine] backed by one [ExoPlayer].
 *
 * - Built with the application context, so it never holds an Activity.
 * - Requests audio focus and pauses when headphones are unplugged.
 * - Decoders apply the video's rotation metadata when rendering to the
 *   surface, so `player.videoSize` already has upright width and height.
 */
public class ExoPlaybackEngine(context: Context) : PlaybackEngine {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var listener: (() -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            listener?.invoke()
        }
    }

    init {
        exoPlayer.addListener(playerListener)
    }

    override val player: Player get() = exoPlayer

    override val status: EngineStatus
        get() = when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> EngineStatus.BUFFERING
            Player.STATE_READY -> EngineStatus.READY
            Player.STATE_ENDED -> EngineStatus.ENDED
            else -> EngineStatus.IDLE
        }

    override val isPlaying: Boolean get() = exoPlayer.isPlaying
    override val playWhenReady: Boolean get() = exoPlayer.playWhenReady
    override val currentItemIndex: Int get() = exoPlayer.currentMediaItemIndex
    override val currentPositionMs: Long get() = exoPlayer.currentPosition.coerceAtLeast(0)

    override val currentItemDurationMs: Long?
        get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    override val hasVideo: Boolean get() = exoPlayer.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
    override val errorCode: Int? get() = exoPlayer.playerError?.errorCode

    override fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(item.uri)
                .apply {
                    if (item.clipStartMs > 0 || item.clipEndMs != null) {
                        setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(item.clipStartMs)
                                .setEndPositionMs(item.clipEndMs ?: C.TIME_END_OF_SOURCE)
                                .build(),
                        )
                    }
                }
                .build()
        }
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun seekTo(itemIndex: Int, positionMs: Long) {
        exoPlayer.seekTo(itemIndex, positionMs)
    }

    override fun release() {
        listener = null
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}
