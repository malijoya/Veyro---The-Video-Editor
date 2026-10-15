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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
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
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.feature.editor.OverlayUi
import com.editor.veyro.feature.editor.PictureUi
import com.editor.veyro.media.render.overlay.OverlayPainter
import com.editor.veyro.media.render.overlay.StickerImages
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Editing of the main track's videos and photos on the preview (see
 * [OverlayCanvas]). The player renders the transform itself, live, so this
 * layer only draws frames and handles.
 *
 * @property pictures the main track's pictures, for picking the one at the playhead.
 * @property selected the selected picture, with any change in progress.
 * @property cropMode true while the crop tab is open: the preview shows the
 *   crop frame and the whole source's outline, and gestures change the crop.
 * @property onChange shows a change at once without saving it.
 * @property onCommit saves the change shown, as one undo step.
 */
internal class PictureEditing(
    val pictures: List<PictureUi>,
    val selected: PictureUi?,
    val cropMode: Boolean,
    val description: String,
    val onChange: (ClipId, ClipTransform) -> Unit,
    val onCommit: () -> Unit,
)

/**
 * The editing layer over the preview, the size of the canvas on screen.
 *
 * All positions are converted at once from screen pixels to canvas pixels
 * (dividing by the canvas's size on screen) and stored as fractions of the
 * canvas, so nothing saved depends on this screen.
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
 *
 * On the video or photo at the playhead ([pictureEditing]):
 * - Tap it to select it. Once selected, drag to move it, pinch to scale and
 *   twist to rotate, about its anchor point; or use its corner handle.
 * - In crop mode, drag a corner of the crop frame (keeping a locked shape)
 *   or drag inside it to move the frame over the source. The source stays
 *   still on the canvas while its crop changes.
 *
 * Tap empty space to clear the selection. Moves snap to the canvas's centre
 * lines, rotation to right angles and picture scale to its fitted size,
 * with guides drawn while snapped. Every gesture is shown live and saved
 * once, as one undo step, when the fingers lift.
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
    pictureEditing: PictureEditing? = null,
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
    val currentPictures by rememberUpdatedState(pictureEditing)
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

    fun placementOf(picture: PictureUi, transform: ClipTransform = picture.transform): ClipPlacement =
        picture.placement(canvasWidthPx, canvasHeightPx, transform)

    /** Waits for the fingers to lift; true if they moved further than a tap. */
    suspend fun AwaitPointerEventScope.awaitTapOrDrag(down: PointerInputChange): Boolean {
        var dragged = false
        while (true) {
            val event = awaitPointerEvent()
            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((pointer.position - down.position).getDistance() > touchSlopPx) dragged = true
            if (!pointer.pressed) break
        }
        return dragged
    }

    val description = listOfNotNull(selected?.let { selectionDescription }, pictureEditing?.takeIf { it.selected != null }?.description)
        .joinToString()

    Canvas(
        modifier
            .semantics { if (description.isNotEmpty()) contentDescription = description }
            .pointerInput(canvasWidthPx, canvasHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    val k = size.width / canvasWidthPx.toFloat()
                    val nowUs = playheadUs()
                    val point = CanvasPoint(down.position.x / k, down.position.y / k)
                    val pictures = currentPictures
                    val activePicture = pictures?.selected?.takeIf { nowUs in it }

                    // ---- Crop mode: only the selected picture's crop frame responds. ----
                    if (pictures != null && pictures.cropMode && activePicture != null) {
                        val start = activePicture.transform
                        val placement = placementOf(activePicture)
                        val corners = placement.corners.map { Offset(it.x * k, it.y * k) }
                        val corner = corners.indices.minByOrNull { (corners[it] - down.position).getDistance() }
                            ?.takeIf { (corners[it] - down.position).getDistance() <= handleRadiusPx * 2 }
                        if (corner == null && !placement.contains(point)) return@awaitEachGesture
                        down.consume()
                        val startSource = placement.canvasToSource(point)
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!finger.pressed) break
                            if (!moved && (finger.position - down.position).getDistance() > touchSlopPx) moved = true
                            if (!moved) continue
                            event.changes.forEach { it.consume() }
                            // The source stays still while cropping, so the start layout maps the finger for the whole drag.
                            val source = placement.canvasToSource(CanvasPoint(finger.position.x / k, finger.position.y / k))
                            val crop = if (corner != null) {
                                start.crop.withCorner(corner, source.x, source.y, start.cropAspect, placement.sourceWidthPx, placement.sourceHeightPx)
                            } else {
                                start.crop.moved(source.x - startSource.x, source.y - startSource.y)
                            }
                            pictures.onChange(activePicture.clipId, placement.withCrop(crop))
                        }
                        if (moved) pictures.onCommit()
                        return@awaitEachGesture
                    }

                    val active = currentSelected?.takeIf { nowUs in it }
                    val handle = active?.let { boxOf(it).corners[2] }?.let { Offset(it.x * k, it.y * k) }
                    val onHandle = handle != null && (down.position - handle).getDistance() <= handleRadiusPx * 2
                    val pictureHandle = activePicture?.let { placementOf(it).corners[2] }?.let { Offset(it.x * k, it.y * k) }
                    val onPictureHandle = !onHandle && pictureHandle != null && (down.position - pictureHandle).getDistance() <= handleRadiusPx * 2
                    val target = when {
                        onPictureHandle -> null
                        onHandle -> active
                        active != null && boxOf(active).contains(point, slop = handleRadiusPx / k) -> active
                        // The topmost visible overlay under the finger.
                        else -> currentOverlays.lastOrNull { nowUs in it && boxOf(it).contains(point) }
                    }

                    if (target == null) {
                        // The video or photo under the finger, if any.
                        val picture = when {
                            onPictureHandle -> activePicture
                            pictures == null -> null
                            else -> pictures.pictures.firstOrNull { nowUs in it }?.takeIf { placementOf(it).contains(point) }
                        }
                        if (picture == null) {
                            // Empty space: a tap (without a drag) clears the selection.
                            val dragged = awaitTapOrDrag(down)
                            if (!dragged && (currentSelected != null || pictures?.selected != null)) select(null)
                            return@awaitEachGesture
                        }
                        if (picture.clipId != pictures?.selected?.clipId) {
                            // A first touch only selects, so footage never moves by accident.
                            down.consume()
                            if (!awaitTapOrDrag(down)) select(picture.clipId)
                            return@awaitEachGesture
                        }
                        down.consume()
                        val start = picture.transform
                        val startPlacement = placementOf(picture)
                        val anchor = Offset(startPlacement.anchor.x * k, startPlacement.anchor.y * k)
                        val handleStart = down.position - anchor
                        var pan = Offset.Zero
                        var zoom = 1f
                        var rotation = 0f
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (onPictureHandle) {
                                val finger = event.changes.firstOrNull { it.id == down.id }?.position ?: break
                                val now = finger - anchor
                                zoom = now.getDistance() / handleStart.getDistance().coerceAtLeast(1f)
                                rotation = Math.toDegrees((atan2(now.y, now.x) - atan2(handleStart.y, handleStart.x)).toDouble()).toFloat()
                            } else {
                                pan += event.calculatePan()
                                zoom *= event.calculateZoom()
                                rotation += event.calculateRotation()
                            }
                            if (!moved && (pan.getDistance() > touchSlopPx || abs(zoom - 1f) > 0.02f || abs(rotation) > 2f || onPictureHandle)) moved = true
                            if (!moved) continue
                            event.changes.forEach { it.consume() }

                            val moving = pan != Offset.Zero
                            val turning = onPictureHandle || abs(rotation) > 0.5f
                            val scaling = onPictureHandle || abs(zoom - 1f) > 0.01f
                            var x = start.positionX + pan.x / (canvasWidthPx * k)
                            var y = start.positionY + pan.y / (canvasHeightPx * k)
                            val snapX = moving && abs(x - 0.5f) * canvasWidthPx * k < snapPx
                            val snapY = moving && abs(y - 0.5f) * canvasHeightPx * k < snapPx
                            if (snapX) x = 0.5f
                            if (snapY) y = 0.5f
                            var angle = OverlayTransform.normalizeDegrees(start.rotationDegrees + rotation)
                            val right = Math.round(angle / 90f) * 90f
                            val snapAngle = turning && abs(angle - right) < ROTATION_SNAP_DEGREES
                            if (snapAngle) angle = OverlayTransform.normalizeDegrees(right)
                            var scale = start.scale * zoom
                            // Back at the fitted (or filled) size.
                            val snapScale = scaling && abs(scale - 1f) < SCALE_SNAP
                            if (snapScale) scale = 1f
                            guides = Guides(snapX, snapY, snapAngle, snapScale)
                            pictures!!.onChange(picture.clipId, start.with(positionX = x, positionY = y, scale = scale, rotationDegrees = angle))
                        }
                        guides = Guides.NONE
                        if (moved) pictures!!.onCommit()
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
                        guides = Guides(snapX, snapY, snapAngle, scale = false)
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
        val k = size.width / canvasWidthPx
        val now = playheadUs()

        // ---- The selected video or photo: drawn by the player; here only its frame. ----
        val editing = pictureEditing
        val picture = editing?.selected?.takeIf { now in it }
        if (editing != null && picture != null) {
            val placement = placementOf(picture)
            if (editing.cropMode) {
                drawCropFrame(placement, k, handleRadiusPx)
            } else {
                val corners = placement.corners.map { Offset(it.x * k, it.y * k) }
                drawFrame(corners)
                drawHandle(corners[2], handleRadiusPx)
                // The anchor: what scaling and turning pivot about.
                val anchor = Offset(placement.anchor.x * k, placement.anchor.y * k)
                val arm = 7.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.5f), radius = arm * 0.6f, center = anchor, style = Stroke(width = 3.dp.toPx()))
                drawCircle(Color.White, radius = arm * 0.6f, center = anchor, style = Stroke(width = 1.5.dp.toPx()))
                drawLine(Color.White, anchor - Offset(arm, 0f), anchor + Offset(arm, 0f), 1.dp.toPx())
                drawLine(Color.White, anchor - Offset(0f, arm), anchor + Offset(0f, arm), 1.dp.toPx())
            }
            drawGuides(guides, Offset(placement.anchor.x * k, placement.anchor.y * k), picture.transform.rotationDegrees, guides.scale)
        }

        // ---- The selected overlay: drawn here, live, over the player's frame. ----
        val overlay = selected ?: return@Canvas
        if (now !in overlay) return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(k, k)
            painter.draw(native, overlay.overlay, canvasWidthPx, canvasHeightPx, sticker, overlay.imageWidthPx, overlay.imageHeightPx)
            native.restore()
        }
        val corners = boxOf(overlay).corners.map { Offset(it.x * k, it.y * k) }
        drawFrame(corners)
        drawHandle(corners[2], handleRadiusPx)
        val centre = Offset(overlay.overlay.transform.centerX * size.width, overlay.overlay.transform.centerY * size.height)
        drawGuides(guides, centre, overlay.overlay.transform.rotationDegrees, showScale = false)
    }
}

/** Selection frame: a light line over a dark one, visible on any footage. */
private fun DrawScope.drawFrame(corners: List<Offset>) {
    val frame = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        corners.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(frame, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 3.dp.toPx()))
    drawPath(frame, Color.White, style = Stroke(width = 1.5.dp.toPx()))
}

/** The resize and rotate handle. Two rings hint at "turn and resize". */
private fun DrawScope.drawHandle(at: Offset, radius: Float) {
    drawCircle(Color.White, radius = radius, center = at)
    drawCircle(Color.Black.copy(alpha = 0.6f), radius = radius, center = at, style = Stroke(width = 1.5.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.6f), radius = radius * 0.45f, center = at, style = Stroke(width = 1.5.dp.toPx()))
}

/** The crop frame: the whole source's outline dashed, the kept part framed with thirds and corner handles. */
private fun DrawScope.drawCropFrame(placement: ClipPlacement, k: Float, handleRadius: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    val source = placement.sourceCorners.map { Offset(it.x * k, it.y * k) }
    val outline = Path().apply {
        moveTo(source[0].x, source[0].y)
        source.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(outline, Color.White.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx(), pathEffect = dash))
    val corners = placement.corners.map { Offset(it.x * k, it.y * k) }
    drawFrame(corners)
    // Rule of thirds inside the kept part.
    for (i in 1..2) {
        val t = i / 3f
        drawLine(Color.White.copy(alpha = 0.6f), lerp(corners[0], corners[1], t), lerp(corners[3], corners[2], t), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.6f), lerp(corners[0], corners[3], t), lerp(corners[1], corners[2], t), 1.dp.toPx())
    }
    corners.forEach { corner ->
        drawCircle(Color.White, radius = handleRadius * 0.8f, center = corner)
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = handleRadius * 0.8f, center = corner, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset = a + (b - a) * t

private fun DrawScope.drawGuides(guides: Guides, centre: Offset, rotationDegrees: Float, showScale: Boolean) {
    val guide = Color(0xFFFFD54F)
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    if (guides.vertical) drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
    if (guides.horizontal) drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
    if (guides.angle) {
        val reach = hypot(size.width, size.height)
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val direction = Offset(kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat())
        drawLine(guide, centre - direction * reach, centre + direction * reach, 1.dp.toPx(), pathEffect = dash)
    }
    if (showScale && guides.scale) {
        drawCircle(guide, radius = 14.dp.toPx(), center = centre, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash))
    }
}

/** Which snap guides to draw while a gesture is snapped. */
private data class Guides(val vertical: Boolean, val horizontal: Boolean, val angle: Boolean, val scale: Boolean) {
    companion object {
        val NONE = Guides(vertical = false, horizontal = false, angle = false, scale = false)
    }
}

private val HANDLE_RADIUS = 11.dp

/** How close (on screen) the centre must come to a centre line to snap to it. */
private val SNAP_DISTANCE = 8.dp
private const val ROTATION_SNAP_DEGREES = 4f

/** How close a picture's scale must come to its fitted size (1) to snap to it. */
private const val SCALE_SNAP = 0.03f

/** Stickers being edited are decoded up to this size; the screen never needs more. */
private const val STICKER_DECODE_PX = 1_440
