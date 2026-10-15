package com.editor.veyro.media.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.media.render.CompositionFactory
import com.editor.veyro.media.render.transform.LiveTransform

/**
 * [PlaybackEngine] for whole timelines, backed by Media3's
 * [CompositionPlayer].
 *
 * It plays the same composition export renders ([CompositionFactory]), so
 * the preview mixes every audio track at its timeline position, with the
 * same volume, mute, fades and mix headroom, and draws photos, text and
 * stickers itself.
 *
 * - Must be created and used on the main thread (CompositionPlayer uses the
 *   creating thread's Looper).
 * - Requests audio focus. (CompositionPlayer has no "becoming noisy" option, so
 *   unplugging headphones doesn't pause it.)
 * - Reports the whole timeline as one item.
 * - Clip transforms are live ([updateInPlace]): a plan that differs only in
 *   transforms updates the running composition's [LiveTransform]s and, when
 *   paused, redraws the current frame, so moving or cropping a clip doesn't
 *   reload the player. This uses CompositionPlayer's replayable frame cache
 *   (experimental in Media3 1.11). If a redraw isn't possible, the change
 *   shows on the next frame played or the next reload.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
public class CompositionPlaybackEngine(context: Context) : PlaybackEngine {

    private val appContext = context.applicationContext

    private val compositionPlayer: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Keeps the last frames so a paused preview can redraw them with new transforms.
        .experimentalSetEnableReplayableCache(true)
        .build()

    private var listener: (() -> Unit)? = null
    private var planHasVideo = false

    /** The plan playing now, and the transforms its composition reads every frame. */
    private var loaded: ExportPlan? = null
    private var transforms: List<LiveTransform> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            listener?.invoke()
        }
    }

    init {
        compositionPlayer.addListener(playerListener)
    }

    override val player: Player get() = compositionPlayer

    override val status: EngineStatus
        get() = when (compositionPlayer.playbackState) {
            Player.STATE_BUFFERING -> EngineStatus.BUFFERING
            Player.STATE_READY -> EngineStatus.READY
            Player.STATE_ENDED -> EngineStatus.ENDED
            else -> EngineStatus.IDLE
        }

    override val isPlaying: Boolean get() = compositionPlayer.isPlaying
    override val playWhenReady: Boolean get() = compositionPlayer.playWhenReady
    override val currentItemIndex: Int get() = 0
    override val currentPositionMs: Long get() = compositionPlayer.currentPosition.coerceAtLeast(0)
    override val currentItemDurationMs: Long?
        get() = compositionPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    override val hasVideo: Boolean get() = planHasVideo
    override val errorCode: Int? get() = compositionPlayer.playerError?.errorCode

    override fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
        throw UnsupportedOperationException("CompositionPlaybackEngine plays timelines. Use loadTimeline.")
    }

    override fun loadTimeline(plan: ExportPlan, startPositionMs: Long) {
        planHasVideo = plan.video.isNotEmpty()
        loaded = plan
        transforms = plan.video.map { LiveTransform(it.transform) }
        compositionPlayer.setComposition(CompositionFactory.forPreview(plan, appContext, transforms), startPositionMs.coerceAtLeast(0))
        compositionPlayer.prepare()
    }

    override fun updateInPlace(plan: ExportPlan): Boolean {
        val current = loaded ?: return false
        if (compositionPlayer.playerError != null || plan.withoutTransforms() != current.withoutTransforms()) return false
        plan.video.forEachIndexed { index, segment -> transforms[index].value = segment.transform }
        loaded = plan
        if (!compositionPlayer.isPlaying) {
            try {
                compositionPlayer.experimentalRedrawLastFrame()
            } catch (e: IllegalStateException) {
                // No frame to redraw yet; the next rendered frame uses the new transforms.
            } catch (e: UnsupportedOperationException) {
                // Same.
            }
        }
        return true
    }

    private fun ExportPlan.withoutTransforms(): ExportPlan = copy(video = video.map { it.copy(transform = ClipTransform.DEFAULT) })

    override fun play() {
        compositionPlayer.play()
    }

    override fun pause() {
        compositionPlayer.pause()
    }

    override fun seekTo(itemIndex: Int, positionMs: Long) {
        compositionPlayer.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun release() {
        listener = null
        loaded = null
        compositionPlayer.removeListener(playerListener)
        compositionPlayer.release()
    }
}
