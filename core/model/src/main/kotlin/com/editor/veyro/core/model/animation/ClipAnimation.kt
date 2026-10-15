package com.editor.veyro.core.model.animation

import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect

/** A property a clip can animate. */
public sealed interface AnimatedProperty {
    /** The name stored in project files. */
    public val key: String
}

/**
 * The placement channels of video clips ([ClipTransform]) and overlays
 * ([OverlayTransform]):
 *
 * | Channel | ClipTransform | OverlayTransform | Unit |
 * |---|---|---|---|
 * | [POSITION_X] | positionX | centerX | fraction of the canvas width |
 * | [POSITION_Y] | positionY | centerY | fraction of the canvas height |
 * | [SCALE] | scale | scale | multiplier |
 * | [ROTATION] | rotationDegrees | rotationDegrees | clockwise degrees |
 * | [OPACITY] | opacity | opacity | 0..1 |
 * | [CROP_LEFT] … [CROP_BOTTOM] | crop edges | — | fraction of the upright source |
 *
 * Rotation keyframes are not wrapped into (-180, 180]: 0 → 720 turns twice.
 * The evaluated angle is wrapped when it is applied to a transform.
 *
 * Crop edges interpolate one by one, so a crop locked to a shape keeps that
 * shape on its keyframes; between two keyframes of different sizes the shape
 * can drift slightly. The evaluated edges are put in order and kept at least
 * [CropRect.MIN_SIZE] apart ([CropRect.coerced]). Overlays have no crop.
 */
public enum class TransformChannel(override val key: String) : AnimatedProperty {
    POSITION_X("positionX"),
    POSITION_Y("positionY"),
    SCALE("scale"),
    ROTATION("rotation"),
    OPACITY("opacity"),
    CROP_LEFT("cropLeft"),
    CROP_TOP("cropTop"),
    CROP_RIGHT("cropRight"),
    CROP_BOTTOM("cropBottom"),
    ;

    /** Whether this is one of the crop edges, which only pictures have. */
    public val isCrop: Boolean get() = this == CROP_LEFT || this == CROP_TOP || this == CROP_RIGHT || this == CROP_BOTTOM

    public companion object {
        /** The channels overlays have. */
        public val OVERLAY_CHANNELS: List<TransformChannel> = entries.filterNot { it.isCrop }

        public fun fromKey(key: String): TransformChannel? = entries.firstOrNull { it.key == key }
    }
}

/** A numeric parameter [param] of the clip's effect [effectId] (see [ClipEffect.params]). */
public data class EffectParam(val effectId: String, val param: String) : AnimatedProperty {
    init {
        require(effectId.isNotBlank() && param.isNotBlank()) { "Effect parameter needs an effect and a name" }
    }

    override val key: String get() = "effect:$effectId:$param"
}

/**
 * A clip's keyframe animation: one [KeyframeTrack] per animated property.
 * Properties without a track keep the clip's static value (its transform,
 * overlay placement or effect parameter), so an empty animation changes
 * nothing.
 *
 * ## Time semantics
 *
 * Keyframe times are **clip-local**: microseconds since the clip's first
 * frame on the timeline. For a clip on the timeline,
 * `localUs = timelineUs - clip.startUs` and
 * `timelineUs = clip.startUs + localUs` ([com.editor.veyro.core.model.timeline.Clip.localTimeOf]).
 * - Moving a clip moves its animation with it.
 * - The editor keeps keyframes on the same moment of the clip's content
 *   when the clip's start is trimmed (it shifts them by the trimmed amount)
 *   and when it is split (each part gets the keyframes it needs, shifted to
 *   its own start). So trims and splits never move an animation relative to
 *   the picture, and playback across a split point is unchanged.
 * - Keyframes outside the clip's range are kept and still shape the curve.
 *
 * Evaluation depends only on the requested time: preview, scrubbing in
 * either direction, and export all compute the same value for the same
 * frame. Nothing runs on a timer.
 */
public data class ClipAnimation(val tracks: Map<AnimatedProperty, KeyframeTrack> = emptyMap()) {

    val isEmpty: Boolean get() = tracks.isEmpty()

    /** Whether any placement channel (position, scale, rotation, opacity) is animated. */
    val animatesTransform: Boolean get() = tracks.keys.any { it is TransformChannel }

    /** Effects whose parameters are animated. */
    val effectIds: Set<String> get() = tracks.keys.filterIsInstance<EffectParam>().mapTo(LinkedHashSet()) { it.effectId }

    public operator fun get(property: AnimatedProperty): KeyframeTrack? = tracks[property]

    /** The animated value of [property] at clip-local [localUs], or null if it isn't animated. */
    public fun valueAt(property: AnimatedProperty, localUs: Long): Double? = tracks[property]?.valueAt(localUs)

    /** Clip-local times that hold a keyframe of any placement channel, sorted. */
    public fun transformKeyframeTimes(): List<Long> =
        tracks.filterKeys { it is TransformChannel }.values.flatMap { track -> track.keyframes.map { it.timeUs } }.distinct().sorted()

    /** Every keyframe time, of any property, sorted. */
    public fun keyframeTimes(): List<Long> = tracks.values.flatMap { track -> track.keyframes.map { it.timeUs } }.distinct().sorted()

    /** A copy with [keyframe] set on [property], replacing one at the same time. */
    public fun with(property: AnimatedProperty, keyframe: Keyframe): ClipAnimation =
        copy(tracks = tracks + (property to (tracks[property]?.with(keyframe) ?: KeyframeTrack.of(keyframe))))

    /** A copy without [property]'s keyframe at [timeUs]. A property left without keyframes stops being animated. */
    public fun without(property: AnimatedProperty, timeUs: Long): ClipAnimation {
        val track = tracks[property] ?: return this
        val remaining = track.without(timeUs)
        return copy(tracks = if (remaining == null) tracks - property else tracks + (property to remaining))
    }

    /** A copy without any animation of [property]. */
    public fun withoutProperty(property: AnimatedProperty): ClipAnimation = if (property in tracks) copy(tracks = tracks - property) else this

    /** Every keyframe moved by [deltaUs] (for a trimmed start). */
    public fun shifted(deltaUs: Long): ClipAnimation =
        if (deltaUs == 0L || isEmpty) this else copy(tracks = tracks.mapValues { it.value.shifted(deltaUs) })

    /**
     * The animation of the part of the clip from clip-local [startUs] to
     * [endUs], re-based so [startUs] becomes 0: every value inside is the
     * same as before. For the parts of a split clip.
     */
    public fun part(startUs: Long, endUs: Long): ClipAnimation =
        if (isEmpty) this else copy(tracks = tracks.mapValues { it.value.keptFor(startUs, endUs).shifted(-startUs) })

    /** Animation of effects other than [effectIds] removed (for effects that were deleted). */
    public fun keepingEffects(effectIds: Set<String>): ClipAnimation =
        copy(tracks = tracks.filterKeys { it !is EffectParam || it.effectId in effectIds })

    /** Animation of the placement channels removed. */
    public fun withoutTransform(): ClipAnimation = copy(tracks = tracks.filterKeys { it !is TransformChannel })

    // ---- Evaluation ----

    /**
     * [base] with its animated channels set to their values at clip-local
     * [localUs]. Values are brought into range (rotation wrapped, scale and
     * opacity clamped, crop edges ordered); everything not animated (anchor,
     * crop shape lock, fit) is [base]'s.
     */
    public fun transformAt(base: ClipTransform, localUs: Long): ClipTransform {
        if (!animatesTransform) return base
        val animatesCrop = tracks.keys.any { it is TransformChannel && it.isCrop }
        return base.with(
            positionX = channel(TransformChannel.POSITION_X, localUs, base.positionX),
            positionY = channel(TransformChannel.POSITION_Y, localUs, base.positionY),
            scale = channel(TransformChannel.SCALE, localUs, base.scale),
            rotationDegrees = channel(TransformChannel.ROTATION, localUs, base.rotationDegrees),
            opacity = channel(TransformChannel.OPACITY, localUs, base.opacity),
            crop = if (!animatesCrop) {
                base.crop
            } else {
                CropRect.coerced(
                    channel(TransformChannel.CROP_LEFT, localUs, base.crop.left),
                    channel(TransformChannel.CROP_TOP, localUs, base.crop.top),
                    channel(TransformChannel.CROP_RIGHT, localUs, base.crop.right),
                    channel(TransformChannel.CROP_BOTTOM, localUs, base.crop.bottom),
                )
            },
        )
    }

    /** [base] (an overlay's placement) with its animated channels at clip-local [localUs]. See [transformAt]. Crop channels don't apply. */
    public fun overlayTransformAt(base: OverlayTransform, localUs: Long): OverlayTransform {
        if (!animatesTransform) return base
        return OverlayTransform.coerced(
            centerX = channel(TransformChannel.POSITION_X, localUs, base.centerX),
            centerY = channel(TransformChannel.POSITION_Y, localUs, base.centerY),
            scale = channel(TransformChannel.SCALE, localUs, base.scale),
            rotationDegrees = channel(TransformChannel.ROTATION, localUs, base.rotationDegrees),
            opacity = channel(TransformChannel.OPACITY, localUs, base.opacity),
        )
    }

    /** [effects] with their animated parameters at clip-local [localUs]. */
    public fun effectsAt(effects: List<ClipEffect>, localUs: Long): List<ClipEffect> {
        if (tracks.keys.none { it is EffectParam }) return effects
        return effects.map { effect ->
            val animated = tracks.filterKeys { it is EffectParam && it.effectId == effect.id }
            if (animated.isEmpty()) {
                effect
            } else {
                effect.copy(params = effect.params + animated.map { (key, track) -> (key as EffectParam).param to track.valueAt(localUs) })
            }
        }
    }

    /** The largest scale the clip reaches: its own, or its keyframes' (for sizing images once). */
    public fun maxScale(baseScale: Float): Float =
        tracks[TransformChannel.SCALE]?.valueRange()?.endInclusive?.toFloat()?.coerceIn(ClipTransform.MIN_SCALE, ClipTransform.MAX_SCALE) ?: baseScale

    /** Whether the opacity is above 0 somewhere (or [baseOpacity] is, when it isn't animated). */
    public fun everVisible(baseOpacity: Float): Boolean =
        tracks[TransformChannel.OPACITY]?.let { it.valueRange().endInclusive > 0.0 } ?: (baseOpacity > 0f)

    private fun channel(channel: TransformChannel, localUs: Long, base: Float): Float =
        tracks[channel]?.valueAt(localUs)?.toFloat() ?: base

    public companion object {
        public val NONE: ClipAnimation = ClipAnimation()
    }
}
