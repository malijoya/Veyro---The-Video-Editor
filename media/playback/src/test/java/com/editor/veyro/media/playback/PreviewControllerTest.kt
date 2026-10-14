package com.editor.veyro.media.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle, timing and error handling of [PreviewController] against a scripted fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewControllerTest {

    private class FakeEngine : PlaybackEngine {
        override val player: Player? = null
        override var status = EngineStatus.IDLE
        override var isPlaying = false
        override var playWhenReady = false
        override var currentItemIndex = 0
        override var currentPositionMs = 0L
        override var currentItemDurationMs: Long? = null
        override var hasVideo = false
        override var errorCode: Int? = null

        var listener: (() -> Unit)? = null
        var loadedItems: List<EngineItem>? = null
        var loadCount = 0
        var released = false
        val seeks = mutableListOf<Pair<Int, Long>>()

        override fun setListener(listener: (() -> Unit)?) {
            this.listener = listener
        }

        override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
            loadedItems = items
            loadCount++
            currentItemIndex = startIndex
            currentPositionMs = startPositionMs
            status = EngineStatus.BUFFERING
            errorCode = null
            isPlaying = false
        }

        override fun play() {
            playWhenReady = true
            isPlaying = status == EngineStatus.READY
        }

        override fun pause() {
            playWhenReady = false
            isPlaying = false
        }

        override fun seekTo(itemIndex: Int, positionMs: Long) {
            seeks += itemIndex to positionMs
            currentItemIndex = itemIndex
            currentPositionMs = positionMs
            if (status == EngineStatus.ENDED) status = EngineStatus.READY
        }

        override fun release() {
            released = true
        }

        fun ready(durationMs: Long, video: Boolean = true) {
            status = EngineStatus.READY
            currentItemDurationMs = durationMs
            hasVideo = video
            isPlaying = playWhenReady
            listener?.invoke()
        }

        fun end() {
            status = EngineStatus.ENDED
            isPlaying = false
            listener?.invoke()
        }

        fun fail(code: Int) {
            errorCode = code
            status = EngineStatus.IDLE
            isPlaying = false
            listener?.invoke()
        }
    }

    private val engines = mutableListOf<FakeEngine>()
    private val engine get() = engines.last()

    private fun TestScope.controller() =
        PreviewController(engineFactory = { FakeEngine().also { engines += it } }, scope = backgroundScope, positionPollIntervalMs = 50)

    private fun plan(uri: String = "content://a", durationUs: Long = 10_000_000) =
        PreviewPlan(listOf(PreviewSegment(uri, sourceStartUs = 0, durationUs = durationUs, clipped = false)))

    @Test
    fun `attach creates one engine and loads the plan`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        assertEquals(PreviewStatus.IDLE, controller.state.value.status)

        controller.attach()
        controller.attach() // Recomposition or a repeated ON_START must not create a second player.
        assertEquals(1, engines.size)
        assertEquals(listOf(EngineItem("content://a", 0, null)), engine.loadedItems)
        assertEquals(PreviewStatus.LOADING, controller.state.value.status)

        engine.ready(durationMs = 9_990)
        val state = controller.state.value
        assertEquals(PreviewStatus.READY, state.status)
        assertEquals(9_990_000L, state.durationUs) // The decoded duration replaces the probed estimate.
        assertTrue(state.hasVideo)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `position is polled only while playing`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        engine.ready(10_000)
        controller.play()
        assertTrue(controller.state.value.isPlaying)

        engine.currentPositionMs = 1_250
        advanceTimeBy(60)
        assertEquals(1_250_000L, controller.state.value.positionUs)

        controller.pause()
        engine.currentPositionMs = 4_000
        advanceTimeBy(500)
        assertEquals(1_250_000L, controller.state.value.positionUs)
    }

    @Test
    fun `detach releases the engine and attach restores position and play state`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        engine.ready(10_000)
        controller.play()
        engine.currentPositionMs = 3_000
        val first = engine

        controller.detach()
        assertTrue(first.released)
        assertNull(first.listener)
        assertNull(controller.player.value)
        assertEquals(PreviewStatus.IDLE, controller.state.value.status)
        assertEquals(3_000_000L, controller.state.value.positionUs)
        assertFalse(controller.state.value.isPlaying)

        controller.attach()
        assertEquals(2, engines.size)
        assertEquals(3_000L, engine.currentPositionMs)
        assertTrue(engine.playWhenReady)
    }

    @Test
    fun `changing media reuses the engine and resets position`() = runTest {
        val controller = controller()
        controller.setPlan(plan("content://a"))
        controller.attach()
        engine.ready(10_000)
        controller.seekTo(5_000_000)

        controller.setPlan(plan("content://b", durationUs = 2_000_000))
        assertEquals(1, engines.size)
        assertEquals(2, engine.loadCount)
        assertEquals("content://b", engine.loadedItems?.single()?.uri)
        assertEquals(0L, engine.currentPositionMs)
        assertEquals(0L, controller.state.value.positionUs)
        assertEquals(PreviewStatus.LOADING, controller.state.value.status)

        controller.setPlan(plan("content://b", durationUs = 2_000_000)) // Same plan: no reload.
        assertEquals(2, engine.loadCount)
    }

    @Test
    fun `seek clamps and maps plan time onto segments`() = runTest {
        val controller = controller()
        controller.setPlan(
            PreviewPlan(
                listOf(
                    PreviewSegment("a", sourceStartUs = 1_000_000, durationUs = 2_000_000, clipped = true),
                    PreviewSegment("b", sourceStartUs = 0, durationUs = 3_000_000, clipped = true),
                ),
            ),
        )
        controller.attach()
        assertEquals(
            listOf(EngineItem("a", 1_000, 3_000), EngineItem("b", 0, 3_000)),
            engine.loadedItems,
        )
        engine.ready(2_000)

        controller.seekTo(2_500_000)
        assertEquals(1 to 500L, engine.seeks.last())
        assertEquals(2_500_000L, controller.state.value.positionUs)
        assertEquals(5_000_000L, controller.state.value.durationUs) // Multi-segment plans use the plan duration.

        controller.seekTo(99_000_000)
        assertEquals(1 to 3_000L, engine.seeks.last())
        controller.seekBy(-10_000_000)
        assertEquals(0 to 0L, engine.seeks.last())
    }

    @Test
    fun `seeking while detached is applied on the next attach`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.seekTo(4_000_000)
        controller.play()
        controller.attach()
        assertEquals(4_000L, engine.currentPositionMs)
        assertTrue(engine.playWhenReady)
    }

    @Test
    fun `playing after the end restarts from the beginning`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        engine.ready(10_000)
        controller.play()
        engine.currentPositionMs = 10_000
        engine.end()
        assertEquals(PreviewStatus.ENDED, controller.state.value.status)
        assertEquals(controller.state.value.durationUs, controller.state.value.positionUs)

        controller.togglePlayPause()
        assertEquals(0 to 0L, engine.seeks.last())
        assertTrue(engine.playWhenReady)
    }

    @Test
    fun `errors are reported and retry reloads at the same position`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        engine.ready(10_000)
        controller.seekTo(2_000_000)
        engine.fail(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val failed = controller.state.value
        assertEquals(PreviewStatus.ERROR, failed.status)
        assertEquals(PreviewError.FILE_NOT_FOUND, failed.error)

        controller.retry()
        assertEquals(2, engine.loadCount)
        assertEquals(2_000L, engine.currentPositionMs)
        assertEquals(PreviewStatus.LOADING, controller.state.value.status)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `audio-only media reports no video`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        engine.ready(10_000, video = false)
        assertFalse(controller.state.value.hasVideo)
    }

    @Test
    fun `release is final`() = runTest {
        val controller = controller()
        controller.setPlan(plan())
        controller.attach()
        controller.release()
        assertTrue(engine.released)
        controller.attach()
        assertEquals(1, engines.size)
        runCurrent()
    }
}
