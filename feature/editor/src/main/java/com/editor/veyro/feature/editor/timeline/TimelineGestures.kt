package com.editor.veyro.feature.editor.timeline

import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Timeline touch handling in one detector, so gestures never fight:
 * - one-finger horizontal drag scrolls, then flings on release
 * - two-finger pinch zooms around the playhead
 * - a tap that doesn't move past touch slop reports its position (clip selection)
 *
 * [onInteractionStart] fires when scrolling or zooming begins, and
 * [onInteractionEnd] fires once the gesture and any fling have finished.
 * The editor pauses playback and seeks around these.
 *
 * Clip gestures (trim handles, long-press move) are handled by child
 * composables, which see pointer events first. Once a child consumes a
 * gesture, this detector ignores the rest of it.
 */
internal fun Modifier.timelineGestures(
    state: TimelineViewportState,
    onTap: (Offset) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
): Modifier = pointerInput(state) {
    val decay = splineBasedDecay<Float>(this)
    coroutineScope {
        var flingJob: Job? = null
        // Each gesture gets a number. A cancelled fling's cleanup must not end a newer gesture's interaction.
        var generation = 0
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val gesture = ++generation
            // Touching the timeline stops a running fling, like any scroll view.
            val wasFlinging = flingJob?.isActive == true
            flingJob?.cancel()
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            var pinching = false
            var totalDx = 0f
            var claimedByChild = false

            fun begin() {
                if (!state.isUserInteracting) {
                    state.isUserInteracting = true
                    onInteractionStart()
                }
            }

            do {
                val event = awaitPointerEvent()
                if (!dragging && !pinching && event.changes.any { it.isConsumed }) claimedByChild = true
                if (claimedByChild) continue
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    if (!pinching) {
                        pinching = true
                        begin()
                    }
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) state.zoomBy(zoom)
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && !pinching) {
                    val change = pressed.first()
                    val dx = change.positionChange().x
                    totalDx += dx
                    if (!dragging && abs(totalDx) > viewConfiguration.touchSlop) {
                        dragging = true
                        begin()
                    }
                    if (dragging) {
                        state.scrollByPx(dx)
                        change.consume()
                    }
                    velocity.addPosition(change.uptimeMillis, change.position)
                }
            } while (event.changes.any { it.pressed })

            when {
                claimedByChild -> if (wasFlinging) {
                    state.isUserInteracting = false
                    onInteractionEnd()
                }
                dragging && !pinching -> {
                    val vx = velocity.calculateVelocity().x
                    flingJob = launch {
                        try {
                            state.fling(vx, decay)
                        } finally {
                            if (gesture == generation) {
                                state.isUserInteracting = false
                                onInteractionEnd()
                            }
                        }
                    }
                }
                dragging || pinching || wasFlinging -> {
                    state.isUserInteracting = false
                    onInteractionEnd()
                }
                else -> onTap(down.position)
            }
        }
    }
}
