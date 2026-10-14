package com.editor.veyro.media.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives preview playback of a [PreviewPlan] and exposes it as [state].
 *
 * Lifecycle, designed so Compose recomposition never recreates the player:
 * - The owner (a ViewModel) creates one controller and keeps it across
 *   configuration changes.
 * - [attach] creates the engine (and its decoders) when the screen starts.
 *   It does nothing if one already exists.
 * - [detach] releases the engine when the app goes to the background,
 *   remembering position and play/pause, so codecs and memory go back to the
 *   system. The next [attach] restores both.
 * - [release] is final. Call it from `ViewModel.onCleared`.
 *
 * [setPlan] switches media in place. The engine is reused, and the old
 * position is dropped.
 *
 * Positions come from the engine on events, and are polled every
 * [positionPollIntervalMs] only while playing. All calls must be on the main
 * thread, and [scope] must use the main dispatcher.
 */
public class PreviewController(
    private val engineFactory: () -> PlaybackEngine,
    private val scope: CoroutineScope,
    private val positionPollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(PreviewState())
    public val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)

    /** The player to render, or null while detached. Bind it to a surface. */
    public val player: StateFlow<Player?> = _player.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var plan: PreviewPlan? = null
    private var savedPositionUs = 0L
    private var savedPlayWhenReady = false
    private var pollJob: Job? = null
    private var released = false

    public val isAttached: Boolean get() = engine != null

    public fun setPlan(newPlan: PreviewPlan) {
        check(!released) { "Controller was released" }
        if (newPlan == plan) return
        plan = newPlan
        savedPositionUs = 0
        savedPlayWhenReady = false
        _state.value = PreviewState(
            status = if (engine != null) PreviewStatus.LOADING else PreviewStatus.IDLE,
            durationUs = newPlan.durationUs,
        )
        engine?.let { load(it, newPlan, positionUs = 0, playWhenReady = false) }
    }

    public fun attach() {
        if (released || engine != null) return
        val created = engineFactory()
        engine = created
        created.setListener(::refresh)
        _player.value = created.player
        plan?.let { load(created, it, savedPositionUs, savedPlayWhenReady) }
    }

    public fun detach() {
        val current = engine ?: return
        if (_state.value.status != PreviewStatus.ERROR) {
            savedPositionUs = currentPlanPositionUs(current)
            savedPlayWhenReady = current.playWhenReady && current.status != EngineStatus.ENDED
        }
        stopPolling()
        current.setListener(null)
        current.release()
        engine = null
        _player.value = null
        _state.update {
            it.copy(
                status = if (it.status == PreviewStatus.ERROR) PreviewStatus.ERROR else PreviewStatus.IDLE,
                isPlaying = false,
                positionUs = savedPositionUs,
            )
        }
    }

    public fun release() {
        detach()
        released = true
    }

    public fun play() {
        val current = engine
        if (current == null) {
            savedPlayWhenReady = true
            return
        }
        if (current.status == EngineStatus.ENDED) seekTo(0)
        current.play()
        refresh()
    }

    public fun pause() {
        val current = engine
        if (current == null) {
            savedPlayWhenReady = false
            return
        }
        current.pause()
        refresh()
    }

    public fun togglePlayPause() {
        val current = engine
        val playing = if (current != null) current.playWhenReady && current.status != EngineStatus.ENDED else savedPlayWhenReady
        if (playing) pause() else play()
    }

    /** Seeks to [planTimeUs], clamped to the plan. */
    public fun seekTo(planTimeUs: Long) {
        val currentPlan = plan ?: return
        val target = planTimeUs.coerceIn(0, currentPlan.durationUs)
        val current = engine
        if (current == null) {
            savedPositionUs = target
        } else {
            val location = currentPlan.locate(target)
            current.seekTo(location.segmentIndex, location.offsetUs / 1_000)
        }
        _state.update { it.copy(positionUs = target, status = if (it.status == PreviewStatus.ENDED) PreviewStatus.READY else it.status) }
    }

    public fun seekBy(deltaUs: Long) {
        seekTo(_state.value.positionUs + deltaUs)
    }

    /** After an error, reopens the media at the last known position. */
    public fun retry() {
        val current = engine ?: return
        val currentPlan = plan ?: return
        _state.update { it.copy(status = PreviewStatus.LOADING, error = null) }
        load(current, currentPlan, _state.value.positionUs, playWhenReady = false)
    }

    private fun load(target: PlaybackEngine, plan: PreviewPlan, positionUs: Long, playWhenReady: Boolean) {
        val location = plan.locate(positionUs.coerceIn(0, plan.durationUs))
        target.load(
            items = plan.segments.map { segment ->
                EngineItem(
                    uri = segment.uri,
                    clipStartMs = segment.sourceStartUs / 1_000,
                    clipEndMs = if (segment.clipped) (segment.sourceStartUs + segment.durationUs) / 1_000 else null,
                )
            },
            startIndex = location.segmentIndex,
            startPositionMs = location.offsetUs / 1_000,
        )
        if (playWhenReady) target.play() else target.pause()
        refresh()
    }

    /** Recomputes [state] from the engine. Called on every engine event and by the position poll. */
    private fun refresh() {
        val current = engine ?: return
        val currentPlan = plan ?: return
        val errorCode = current.errorCode
        val status = when {
            errorCode != null -> PreviewStatus.ERROR
            current.status == EngineStatus.READY -> PreviewStatus.READY
            current.status == EngineStatus.ENDED -> PreviewStatus.ENDED
            else -> PreviewStatus.LOADING
        }
        // A whole single asset reports its exact decoded duration once known. The probed value is only an estimate.
        val engineDurationUs = current.currentItemDurationMs?.times(1_000)
        val durationUs = if (currentPlan.segments.size == 1 && !currentPlan.segments[0].clipped && engineDurationUs != null) {
            engineDurationUs
        } else {
            currentPlan.durationUs
        }
        val positionUs = if (status == PreviewStatus.ENDED) durationUs else currentPlanPositionUs(current).coerceAtMost(durationUs)
        _state.value = PreviewState(
            status = status,
            isPlaying = current.isPlaying,
            positionUs = positionUs,
            durationUs = durationUs,
            hasVideo = current.hasVideo,
            error = errorCode?.let(PreviewError::fromPlaybackErrorCode),
        )
        if (current.isPlaying) startPolling() else stopPolling()
    }

    private fun currentPlanPositionUs(current: PlaybackEngine): Long {
        val currentPlan = plan ?: return 0
        val index = current.currentItemIndex.coerceIn(0, currentPlan.segments.lastIndex)
        return currentPlan.planTimeUs(index, current.currentPositionMs * 1_000)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(positionPollIntervalMs)
                val current = engine ?: break
                _state.update { it.copy(positionUs = currentPlanPositionUs(current).coerceAtMost(it.durationUs)) }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    public companion object {
        /** Fast enough for a smooth seek bar, slow enough to cost nothing. */
        public const val DEFAULT_POLL_INTERVAL_MS: Long = 50
    }
}
