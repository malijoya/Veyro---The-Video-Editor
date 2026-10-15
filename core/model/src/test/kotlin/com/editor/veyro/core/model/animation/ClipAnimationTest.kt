package com.editor.veyro.core.model.animation

import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAnimationTest {

    private val sec = 1_000_000L

    /** Slides right over the first second and fades out over the second. */
    private val slide = ClipAnimation()
        .with(TransformChannel.POSITION_X, Keyframe(0, 0.2))
        .with(TransformChannel.POSITION_X, Keyframe(sec, 0.8))
        .with(TransformChannel.OPACITY, Keyframe(sec, 1.0))
        .with(TransformChannel.OPACITY, Keyframe(2 * sec, 0.0))

    private val base = ClipTransform(positionY = 0.3f, scale = 1.5f, anchorX = 0.1f, crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), fit = FitMode.FILL)

    // ---------------- Evaluation ----------------

    @Test
    fun `animated channels follow their keyframes, everything else is the static transform`() {
        val mid = slide.transformAt(base, sec / 2)
        assertEquals(0.5f, mid.positionX, 1e-6f)
        assertEquals(1f, mid.opacity, 0f)
        // Not animated: kept from the static transform, including what can't be animated.
        assertEquals(base.positionY, mid.positionY, 0f)
        assertEquals(base.scale, mid.scale, 0f)
        assertEquals(base.anchorX, mid.anchorX, 0f)
        assertEquals(base.crop, mid.crop)
        assertEquals(base.fit, mid.fit)
        assertEquals(0.5f, slide.transformAt(base, sec + sec / 2).opacity, 1e-6f)
        // No animation: the very same transform.
        assertSame(base, ClipAnimation.NONE.transformAt(base, 123))
    }

    @Test
    fun `values outside a transform's range are clamped and rotation wraps`() {
        val wild = ClipAnimation()
            .with(TransformChannel.SCALE, Keyframe(0, 100.0))
            .with(TransformChannel.OPACITY, Keyframe(0, 1.7))
            .with(TransformChannel.ROTATION, Keyframe(0, 0.0))
            .with(TransformChannel.ROTATION, Keyframe(sec, 720.0))
        val start = wild.transformAt(ClipTransform.DEFAULT, 0)
        assertEquals(ClipTransform.MAX_SCALE, start.scale, 0f)
        assertEquals(1f, start.opacity, 0f)
        // Two full turns: 90° in at an eighth of the way, 180° at a quarter, back to 0 at the end.
        assertEquals(90f, wild.transformAt(ClipTransform.DEFAULT, sec / 8).rotationDegrees, 1e-3f)
        assertEquals(180f, wild.transformAt(ClipTransform.DEFAULT, sec / 4).rotationDegrees, 1e-3f)
        assertEquals(-90f, wild.transformAt(ClipTransform.DEFAULT, 3 * sec / 8).rotationDegrees, 1e-3f)
        assertEquals(0f, wild.transformAt(ClipTransform.DEFAULT, sec).rotationDegrees, 1e-3f)
    }

    @Test
    fun `overlays animate the same channels`() {
        val placed = OverlayTransform(centerY = 0.9f, scale = 2f)
        val mid = slide.overlayTransformAt(placed, sec / 2)
        assertEquals(0.5f, mid.centerX, 1e-6f)
        assertEquals(0.9f, mid.centerY, 0f)
        assertEquals(2f, mid.scale, 0f)
        assertEquals(0f, slide.overlayTransformAt(placed, 5 * sec).opacity, 0f)
    }

    @Test
    fun `effect parameters animate, other parameters and effects stay`() {
        val blur = ClipEffect("e1", "blur", params = mapOf("radius" to 2.0, "quality" to 1.0))
        val tint = ClipEffect("e2", "tint", params = mapOf("amount" to 0.5))
        val radius = EffectParam("e1", "radius")
        val animation = ClipAnimation().with(radius, Keyframe(0, 0.0)).with(radius, Keyframe(sec, 10.0))
        val effects = animation.effectsAt(listOf(blur, tint), sec / 4)
        assertEquals(2.5, effects[0].params.getValue("radius"), 1e-12)
        assertEquals(1.0, effects[0].params.getValue("quality"), 0.0)
        assertEquals(tint, effects[1])
        assertEquals(setOf("e1"), animation.effectIds)
        assertFalse(animation.animatesTransform)
    }

    @Test
    fun `the largest scale and whether it is ever visible account for keyframes`() {
        val grow = ClipAnimation().with(TransformChannel.SCALE, Keyframe(0, 1.0)).with(TransformChannel.SCALE, Keyframe(sec, 3.0))
        assertEquals(3f, grow.maxScale(1f), 0f)
        assertEquals(0.7f, ClipAnimation.NONE.maxScale(0.7f), 0f)
        assertTrue(slide.everVisible(0f)) // Fades from 1 to 0.
        assertFalse(ClipAnimation().with(TransformChannel.OPACITY, Keyframe(0, 0.0)).everVisible(1f))
        assertFalse(ClipAnimation.NONE.everVisible(0f))
    }

    // ---------------- Clip-local time ----------------

    @Test
    fun `clip-local time is time since the clip's first frame`() {
        val clip = Clip(ClipId("c"), MediaId("m"), startUs = 4 * sec, sourceInUs = 10 * sec, durationUs = 2 * sec)
        assertEquals(0L, clip.localTimeOf(4 * sec))
        assertEquals(1_500_000L, clip.localTimeOf(5_500_000))
        assertEquals(-sec, clip.localTimeOf(3 * sec)) // Before the clip: negative.
        assertEquals(6 * sec, clip.timelineTimeOf(2 * sec))
        // The two conversions are inverse, and don't depend on the source in-point.
        (-10..10).map { it * 333_333L }.forEach { assertEquals(it, clip.localTimeOf(clip.timelineTimeOf(it))) }
        assertThrows(ArithmeticException::class.java) { clip.timelineTimeOf(Long.MAX_VALUE) }
    }

    @Test
    fun `moving the clip moves its animation, because times are clip-local`() {
        val clip = Clip(ClipId("c"), MediaId("m"), 0, 0, 2 * sec, animation = slide)
        val moved = clip.copy(startUs = 7 * sec)
        (0..20).map { it * 100_000L }.forEach { local ->
            assertEquals(
                clip.animation.transformAt(clip.transform, clip.localTimeOf(clip.timelineTimeOf(local))),
                moved.animation.transformAt(moved.transform, moved.localTimeOf(moved.timelineTimeOf(local))),
            )
        }
    }

    @Test
    fun `a part re-bases keyframes so every value inside it is unchanged`() {
        val right = slide.part(sec / 2, 2 * sec)
        (0..15).map { it * 100_000L }.forEach { offset ->
            assertEquals(slide.transformAt(base, sec / 2 + offset), right.transformAt(base, offset))
        }
        val shifted = slide.shifted(-sec / 2)
        assertEquals(slide.transformAt(base, sec), shifted.transformAt(base, sec / 2))
    }

    @Test
    fun `clips only animate what they have`() {
        // Effect keyframes need the effect.
        assertThrows(IllegalArgumentException::class.java) {
            Clip(ClipId("c"), MediaId("m"), 0, 0, sec, animation = ClipAnimation().with(EffectParam("missing", "x"), Keyframe(0, 1.0)))
        }
        // Sound has no placement.
        val sound = Clip(ClipId("s"), MediaId("song"), 0, 0, sec, animation = slide)
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId("music"), TrackKind.AUDIO, listOf(sound)) }
        // Pictures and overlays do.
        Track(TrackId("main"), TrackKind.VIDEO, listOf(sound.copy(id = ClipId("v"))))
        val text = Clip(ClipId("t"), null, 0, 0, sec, overlay = Overlay(OverlayContent.Text("Hi")), animation = slide)
        Track(TrackId("overlay-1"), TrackKind.OVERLAY, listOf(text))
    }
}
