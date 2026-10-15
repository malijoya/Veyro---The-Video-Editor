package com.editor.veyro.core.model.animation

import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import kotlin.math.abs

/**
 * Editing rules for placement keyframes, shared by video clips and overlays.
 * Pure functions; the editor turns their results into undoable commands.
 *
 * A *keyframe moment* is a clip-local time where the placement channels have
 * keyframes. The editor adds, selects, eases and deletes moments, all
 * channels together, as most editors do.
 *
 * Editing an animated clip at a moment (dragging it, a slider) records the
 * changed channels as keyframes at that moment instead of changing the
 * static placement. A clip without placement keyframes is edited as before.
 */
public object Keyframing {

    /** Changes smaller than this are rounding, not edits. */
    private const val EPSILON = 1e-5

    /** The placement channels of a video clip's transform, crop edges included. */
    public fun channels(transform: ClipTransform): Map<TransformChannel, Float> = mapOf(
        TransformChannel.POSITION_X to transform.positionX,
        TransformChannel.POSITION_Y to transform.positionY,
        TransformChannel.SCALE to transform.scale,
        TransformChannel.ROTATION to transform.rotationDegrees,
        TransformChannel.OPACITY to transform.opacity,
        TransformChannel.CROP_LEFT to transform.crop.left,
        TransformChannel.CROP_TOP to transform.crop.top,
        TransformChannel.CROP_RIGHT to transform.crop.right,
        TransformChannel.CROP_BOTTOM to transform.crop.bottom,
    )

    /** The placement channels of an overlay (no crop). */
    public fun channels(transform: OverlayTransform): Map<TransformChannel, Float> = mapOf(
        TransformChannel.POSITION_X to transform.centerX,
        TransformChannel.POSITION_Y to transform.centerY,
        TransformChannel.SCALE to transform.scale,
        TransformChannel.ROTATION to transform.rotationDegrees,
        TransformChannel.OPACITY to transform.opacity,
    )

    /** [transform] with its placement channels replaced by [values] (missing ones kept), brought into range. */
    public fun withChannels(transform: ClipTransform, values: Map<TransformChannel, Float>): ClipTransform = transform.with(
        positionX = values[TransformChannel.POSITION_X] ?: transform.positionX,
        positionY = values[TransformChannel.POSITION_Y] ?: transform.positionY,
        scale = values[TransformChannel.SCALE] ?: transform.scale,
        rotationDegrees = values[TransformChannel.ROTATION] ?: transform.rotationDegrees,
        opacity = values[TransformChannel.OPACITY] ?: transform.opacity,
        crop = if (values.keys.none { it.isCrop }) {
            transform.crop
        } else {
            CropRect.coerced(
                values[TransformChannel.CROP_LEFT] ?: transform.crop.left,
                values[TransformChannel.CROP_TOP] ?: transform.crop.top,
                values[TransformChannel.CROP_RIGHT] ?: transform.crop.right,
                values[TransformChannel.CROP_BOTTOM] ?: transform.crop.bottom,
            )
        },
    )

    /** [transform] with its placement channels replaced by [values], brought into range. Crop values are ignored. */
    public fun withChannels(transform: OverlayTransform, values: Map<TransformChannel, Float>): OverlayTransform = OverlayTransform.coerced(
        centerX = values[TransformChannel.POSITION_X] ?: transform.centerX,
        centerY = values[TransformChannel.POSITION_Y] ?: transform.centerY,
        scale = values[TransformChannel.SCALE] ?: transform.scale,
        rotationDegrees = values[TransformChannel.ROTATION] ?: transform.rotationDegrees,
        opacity = values[TransformChannel.OPACITY] ?: transform.opacity,
    )

    /**
     * The placement keyframe moment within [toleranceUs] of clip-local
     * [localUs] (the nearest, earlier on a tie), or null. The editor passes
     * half a frame, so the moment under the playhead counts as selected.
     */
    public fun momentNear(animation: ClipAnimation, localUs: Long, toleranceUs: Long): Long? =
        animation.transformKeyframeTimes()
            .filter { abs(it - localUs) <= toleranceUs }
            .minWithOrNull(compareBy<Long> { abs(it - localUs) }.thenBy { it })

    /** The easing of the moment at [localUs]: of its first channel keyframe there, or null if there is none. */
    public fun easingAt(animation: ClipAnimation, localUs: Long): Easing? =
        TransformChannel.entries.firstNotNullOfOrNull { animation[it]?.at(localUs)?.easing }

    /**
     * Applies an edit of a video clip's transform made at clip-local
     * [localUs]: [edited] is the transform as the user changed it, starting
     * from what was shown at [shownAtUs] (the exact playhead, which may lie
     * inside the frame that starts at [localUs]). Returns the new static
     * transform and animation.
     * - Not animated: [edited] becomes the static transform.
     * - Animated: each changed placement channel, crop edges included, gets
     *   a keyframe at [localUs] (added, or replacing the one there). Anchor,
     *   crop shape lock and fit aren't animatable and change the static
     *   transform.
     */
    public fun recordEdit(
        base: ClipTransform,
        animation: ClipAnimation,
        edited: ClipTransform,
        localUs: Long,
        shownAtUs: Long = localUs,
    ): Pair<ClipTransform, ClipAnimation> {
        if (!animation.animatesTransform) return edited to animation
        val shown = animation.transformAt(base, shownAtUs)
        val recorded = record(channels(base), channels(shown), channels(edited), animation, localUs)
        // The static placement channels stay; only what can't be animated comes from the edit.
        return withChannels(edited, channels(base)) to recorded
    }

    /** [recordEdit] for an overlay's placement. */
    public fun recordEdit(
        base: OverlayTransform,
        animation: ClipAnimation,
        edited: OverlayTransform,
        localUs: Long,
        shownAtUs: Long = localUs,
    ): Pair<OverlayTransform, ClipAnimation> {
        if (!animation.animatesTransform) return edited to animation
        val shown = animation.overlayTransformAt(base, shownAtUs)
        return base to record(channels(base), channels(shown), channels(edited), animation, localUs)
    }

    /**
     * Adds a moment at [localUs]: every placement channel the clip has (the
     * keys of [base], its static values) gets a keyframe holding its current
     * value there, so nothing visibly changes until the user edits it.
     */
    public fun addMoment(base: Map<TransformChannel, Float>, animation: ClipAnimation, localUs: Long): ClipAnimation {
        val easing = easingAt(animation, localUs) ?: Easing.Linear
        return base.keys.fold(animation) { result, channel ->
            val value = animation.valueAt(channel, localUs) ?: base.getValue(channel).toDouble()
            result.with(channel, Keyframe(localUs, value, animation[channel]?.at(localUs)?.easing ?: easing))
        }
    }

    /**
     * Removes the moment at [localUs] from every placement channel. A channel
     * that loses its last keyframe stops being animated and keeps that
     * keyframe's value as its static value (returned in the new base), so
     * deleting the last keyframe doesn't make the clip jump.
     */
    public fun removeMoment(base: Map<TransformChannel, Float>, animation: ClipAnimation, localUs: Long): Pair<Map<TransformChannel, Float>, ClipAnimation> {
        val newBase = base.toMutableMap()
        var result = animation
        TransformChannel.entries.forEach { channel ->
            val track = animation[channel] ?: return@forEach
            val keyframe = track.at(localUs) ?: return@forEach
            if (track.keyframes.size == 1) newBase[channel] = keyframe.value.toFloat()
            result = result.without(channel, localUs)
        }
        return newBase to result
    }

    /** Sets the easing of the moment at [localUs] on every placement channel that has a keyframe there. */
    public fun setEasing(animation: ClipAnimation, localUs: Long, easing: Easing): ClipAnimation =
        TransformChannel.entries.fold(animation) { result, channel ->
            val keyframe = animation[channel]?.at(localUs) ?: return@fold result
            result.with(channel, keyframe.copy(easing = easing))
        }

    /**
     * Moves the moment at [fromUs] to [toUs] on every placement channel,
     * keeping values and easing. A keyframe already at [toUs] is replaced.
     */
    public fun moveMoment(animation: ClipAnimation, fromUs: Long, toUs: Long): ClipAnimation {
        if (fromUs == toUs) return animation
        return TransformChannel.entries.fold(animation) { result, channel ->
            val keyframe = animation[channel]?.at(fromUs) ?: return@fold result
            result.without(channel, fromUs).with(channel, keyframe.copy(timeUs = toUs))
        }
    }

    /** Every position keyframe moved by ([dx], [dy]) fractions of the canvas (for a nudged copy). */
    public fun offsetPosition(animation: ClipAnimation, dx: Float, dy: Float): ClipAnimation {
        var result = animation
        listOf(TransformChannel.POSITION_X to dx, TransformChannel.POSITION_Y to dy).forEach { (channel, delta) ->
            val track = animation[channel] ?: return@forEach
            result = result.copy(tracks = result.tracks + (channel to KeyframeTrack(track.keyframes.map { it.copy(value = it.value + delta) })))
        }
        return result
    }

    private fun record(
        base: Map<TransformChannel, Float>,
        shown: Map<TransformChannel, Float>,
        edited: Map<TransformChannel, Float>,
        animation: ClipAnimation,
        localUs: Long,
    ): ClipAnimation {
        val easing = easingAt(animation, localUs) ?: Easing.Linear
        return shown.keys.fold(animation) { result, channel ->
            val before = shown.getValue(channel)
            val after = edited.getValue(channel)
            if (abs(after - before) <= EPSILON) return@fold result
            val raw = animation.valueAt(channel, localUs) ?: base.getValue(channel).toDouble()
            val value = if (channel == TransformChannel.ROTATION) {
                // Shown angles are wrapped; keyframes aren't. Turn the stored angle the short way by the same amount, so 170° → -170° is 20°, not 340°.
                raw + OverlayTransform.normalizeDegrees(after - before)
            } else {
                after.toDouble()
            }
            result.with(channel, Keyframe(localUs, value, animation[channel]?.at(localUs)?.easing ?: easing))
        }
    }
}
