package com.editor.veyro.feature.editor.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.model.overlay.CanvasPoint
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayBox
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.feature.editor.OverlayUi
import com.editor.veyro.media.render.overlay.OverlayPainter
import com.editor.veyro.media.render.overlay.StickerImages
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The editing layer over the preview, the size of the canvas on screen.
 *
 * The player renders every overlay except the selected one. This layer
 * draws the selected one itself, with the same [OverlayPainter] the player
 * and export use, scaled from canvas pixels to the screen, so a change shows
 * on the very next frame without reloading the player. It also draws the
 * selection frame and the resize/rotate handle.
 *
 * Gestures, on overlays visible at the playhead:
 * - Touch an overlay to select it; drag to move it.
 * - Pinch to resize and twist to rotate (two fingers, anywhere).
 * - Drag the corner handle to resize and rotate with one finger.
 * - Tap the selected text again to edit its words.
 * - Tap empty space to clear the selection.
 *
 * Moves snap to the canvas's centre lines and rotation to right angles,
 * with guides drawn while snapped. Every gesture is shown live through
 * [onChange] and saved once, as one undo step, through [onCommit].
 */
@Composable
internal fun OverlayCanvas(
    overlays: List<OverlayUi>,
    selected: OverlayUi?,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    playheadUs: () -> Long,
    enabled: Boolean,
    selectionDescription: String,
    onSelect: (ClipId?) -> Unit,
    onChange: (ClipId, Overlay) -> Unit,
    onCommit: () -> Unit,
    onEditText: (ClipId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember { OverlayPainter() }
    val images = remember(context) { StickerImages(context) }
    val sticker by produceState<Bitmap?>(null, selected?.imageUri, selected?.imageRotationDegrees) {
        val uri = selected?.imageUri
        val rotation = selected?.imageRotationDegrees ?: 0
        value = if (uri == null) null else withContext(Dispatchers.IO) { images.load(uri, rotation, STICKER_DECODE_PX) }
    }
    var guides by remember { mutableStateOf(Guides.NONE) }

    val currentOverlays by rememberUpdatedState(overlays)
    val currentSelected by rememberUpdatedState(selected)
    val currentEnabled by rememberUpdatedState(enabled)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val editText by rememberUpdatedState(onEditText)

    val density = LocalDensity.current
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }
    val snapPx = with(density) { SNAP_DISTANCE.toPx() }
    val touchSlopPx = with(density) { 8.dp.toPx() }

    fun boxOf(overlay: OverlayUi, ov: Overlay = overlay.overlay): OverlayBox =
        painter.box(ov, canvasWidthPx, canvasHeightPx, overlay.imageWidthPx, overlay.imageHeightPx)

    Canvas(
        modifier
            .semantics { if (selected != null) contentDescription = selectionDescription }
            .pointerInput(canvasWidthPx, canvasHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    val k = size.width / canvasWidthPx.toFloat()
                    val nowUs = playheadUs()
                    val point = CanvasPoint(down.position.x / k, down.position.y / k)
                    val active = currentSelected?.takeIf { nowUs in it }
                    val handle = active?.let { boxOf(it).corners[2] }?.let { Offset(it.x * k, it.y * k) }
                    val onHandle = handle != null && (down.position - handle).getDistance() <= handleRadiusPx * 2
                    val target = when {
                        onHandle -> active
                        active != null && boxOf(active).contains(point, slop = handleRadiusPx / k) -> active
                        // The topmost visible overlay under the finger.
                        else -> currentOverlays.lastOrNull { nowUs in it && boxOf(it).contains(point) }
                    }
                    if (target == null) {
                        // Empty space: a tap (without a drag) clears an overlay selection.
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((pointer.position - down.position).getDistance() > touchSlopPx) dragged = true
                            if (!pointer.pressed) break
                        }
                        if (!dragged && currentSelected != null) select(null)
                        return@awaitEachGesture
                    }
                    val wasSelected = target.clipId == currentSelected?.clipId
                    if (!wasSelected) select(target.clipId)
                    down.consume()

                    val start = target.overlay.transform
                    val centre = Offset(start.centerX * canvasWidthPx * k, start.centerY * canvasHeightPx * k)
                    val handleStart = down.position - centre
                    var pan = Offset.Zero
                    var zoom = 1f
                    var rotation = 0f
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (onHandle) {
                            val finger = event.changes.firstOrNull { it.id == down.id }?.position ?: break
                            val now = finger - centre
                            val startLength = handleStart.getDistance().coerceAtLeast(1f)
                            zoom = now.getDistance() / startLength
                            rotation = Math.toDegrees(
                                (atan2(now.y, now.x) - atan2(handleStart.y, handleStart.x)).toDouble(),
                            ).toFloat()
                        } else {
                            pan += event.calculatePan()
                            zoom *= event.calculateZoom()
                            rotation += event.calculateRotation()
                        }
                        if (!moved && (pan.getDistance() > touchSlopPx || abs(zoom - 1f) > 0.02f || abs(rotation) > 2f || onHandle)) moved = true
                        if (!moved) continue
                        event.changes.forEach { it.consume() }

                        // Snap the centre to the canvas's centre lines while moving, and the angle to right angles while turning.
                        val moving = pan != Offset.Zero
                        val turning = onHandle || abs(rotation) > 0.5f
                        var x = start.centerX + pan.x / (canvasWidthPx * k)
                        var y = start.centerY + pan.y / (canvasHeightPx * k)
                        val snapX = moving && abs(x - 0.5f) * canvasWidthPx * k < snapPx
                        val snapY = moving && abs(y - 0.5f) * canvasHeightPx * k < snapPx
                        if (snapX) x = 0.5f
                        if (snapY) y = 0.5f
                        var angle = OverlayTransform.normalizeDegrees(start.rotationDegrees + rotation)
                        val right = Math.round(angle / 90f) * 90f
                        val snapAngle = turning && abs(angle - right) < ROTATION_SNAP_DEGREES
                        if (snapAngle) angle = OverlayTransform.normalizeDegrees(right)
                        guides = Guides(snapX, snapY, snapAngle)
                        val transform = OverlayTransform.coerced(x, y, start.scale * zoom, angle, start.opacity)
                        change(target.clipId, target.overlay.copy(transform = transform))
                    }
                    guides = Guides.NONE
                    when {
                        moved -> commit()
                        // A second tap on selected text opens it for typing.
                        wasSelected && target.overlay.content is OverlayContent.Text -> editText(target.clipId)
                    }
                }
            },
    ) {
        val overlay = selected ?: return@Canvas
        if (playheadUs() !in overlay) return@Canvas
        val k = size.width / canvasWidthPx
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(k, k)
            painter.draw(native, overlay.overlay, canvasWidthPx, canvasHeightPx, sticker, overlay.imageWidthPx, overlay.imageHeightPx)
            native.restore()
        }

        // Selection frame: a light line over a dark one, visible on any footage.
        val corners = boxOf(overlay).corners.map { Offset(it.x * k, it.y * k) }
        val frame = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(frame, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 3.dp.toPx()))
        drawPath(frame, Color.White, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(Color.White, radius = handleRadiusPx, center = corners[2])
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = handleRadiusPx, center = corners[2], style = Stroke(width = 1.5.dp.toPx()))
        // Two small arcs in the handle hint at "turn and resize".
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = handleRadiusPx * 0.45f, center = corners[2], style = Stroke(width = 1.5.dp.toPx()))

        val guide = Color(0xFFFFD54F)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        if (guides.vertical) drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
        if (guides.horizontal) drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
        if (guides.angle) {
            val centre = Offset(overlay.overlay.transform.centerX * size.width, overlay.overlay.transform.centerY * size.height)
            val reach = hypot(size.width, size.height)
            val radians = Math.toRadians(overlay.overlay.transform.rotationDegrees.toDouble())
            val direction = Offset(kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat())
            drawLine(guide, centre - direction * reach, centre + direction * reach, 1.dp.toPx(), pathEffect = dash)
        }
    }
}

/** Which snap guides to draw while a gesture is snapped. */
private data class Guides(val vertical: Boolean, val horizontal: Boolean, val angle: Boolean) {
    companion object {
        val NONE = Guides(vertical = false, horizontal = false, angle = false)
    }
}

private val HANDLE_RADIUS = 11.dp

/** How close (on screen) the centre must come to a centre line to snap to it. */
private val SNAP_DISTANCE = 8.dp
private const val ROTATION_SNAP_DEGREES = 4f

/** Stickers being edited are decoded up to this size; the screen never needs more. */
private const val STICKER_DECODE_PX = 1_440
