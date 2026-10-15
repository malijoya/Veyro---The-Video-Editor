package com.editor.veyro.core.model.animation

import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.AspectRatio
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframingTest {

    private val sec = 1_000_000L
    private val frame = 33_334L

    /** Two moments: at 0 (x 0.2) and at 1 s (x 0.8), every channel keyed. */
    private fun twoMoments(base: ClipTransform = ClipTransform.DEFAULT): ClipAnimation {
        val atStart = Keyframing.addMoment(Keyframing.channels(base.with(positionX = 0.2f)), ClipAnimation.NONE, 0)
        val (_, moved) = Keyframing.recordEdit(base, atStart, base.with(positionX = 0.8f), sec)
        // The second moment exists only for x; give every channel one.
        return Keyframing.addMoment(Keyframing.channels(base), moved, sec)
    }

    @Test
    fun `editing a clip without keyframes changes its static transform`() {
        val edited = ClipTransform(positionX = 0.1f, crop = CropRect(0.2f, 0f, 1f, 1f))
        val (static, animation) = Keyframing.recordEdit(ClipTransform.DEFAULT, ClipAnimation.NONE, edited, sec)
        assertEquals(edited, static)
        assertSame(ClipAnimation.NONE, animation)
    }

    @Test
    fun `adding a moment keys every channel at its current value, so nothing moves`() {
        val base = ClipTransform(positionX = 0.3f, scale = 2f, rotationDegrees = 30f, opacity = 0.5f)
        val animation = Keyframing.addMoment(Keyframing.channels(base), ClipAnimation.NONE, sec)
        assertEquals(TransformChannel.entries.toSet(), animation.tracks.keys)
        listOf(0L, sec, 5 * sec).forEach { assertEquals(base, animation.transformAt(base, it)) }

        // A second moment on a linear path also changes nothing until edited.
        val path = twoMoments()
        val before = (0..30).map { path.transformAt(ClipTransform.DEFAULT, it * frame) }
        val more = Keyframing.addMoment(Keyframing.channels(ClipTransform.DEFAULT), path, sec / 2)
        (0..30).forEach { i -> assertEquals(before[i].positionX, more.transformAt(ClipTransform.DEFAULT, i * frame).positionX, 1e-6f) }
    }

    @Test
    fun `editing an animated clip keys only the changed channels at that moment`() {
        val animation = twoMoments()
        val shown = animation.transformAt(ClipTransform.DEFAULT, sec / 2)
        val (static, recorded) = Keyframing.recordEdit(ClipTransform.DEFAULT, animation, shown.with(scale = 2f), sec / 2)
        assertEquals(ClipTransform.DEFAULT, static) // Static placement untouched.
        assertEquals(2.0, recorded[TransformChannel.SCALE]!!.at(sec / 2)!!.value, 1e-6)
        assertNull(recorded[TransformChannel.POSITION_X]!!.at(sec / 2)) // Unchanged: no new keyframe.
        assertEquals(2f, recorded.transformAt(ClipTransform.DEFAULT, sec / 2).scale, 1e-6f)
        assertEquals(0.5f, recorded.transformAt(ClipTransform.DEFAULT, sec / 2).positionX, 1e-6f)

        // On an existing keyframe, the edit replaces its value and keeps its curve.
        val eased = Keyframing.setEasing(animation, 0, Easing.EASE_OUT)
        val (_, onKey) = Keyframing.recordEdit(ClipTransform.DEFAULT, eased, eased.transformAt(ClipTransform.DEFAULT, 0).with(positionX = 0.1f), 0)
        assertEquals(Keyframe(0, 0.1f.toDouble(), Easing.EASE_OUT), onKey[TransformChannel.POSITION_X]!!.at(0))
    }

    @Test
    fun `things that can't be animated still change the static transform`() {
        val animation = twoMoments()
        val shown = animation.transformAt(ClipTransform.DEFAULT, 0)
        val edited = shown.with(anchorX = 0f, fit = FitMode.FILL, cropAspect = AspectRatio.SQUARE)
        val (static, recorded) = Keyframing.recordEdit(ClipTransform.DEFAULT, animation, edited, 0)
        assertEquals(0f, static.anchorX)
        assertEquals(FitMode.FILL, static.fit)
        assertEquals(AspectRatio.SQUARE, static.cropAspect)
        assertEquals(animation, recorded)
    }

    @Test
    fun `a rotation edit turns the short way from the shown angle`() {
        val base = ClipTransform(rotationDegrees = 170f)
        val animation = Keyframing.addMoment(Keyframing.channels(base), ClipAnimation.NONE, 0)
        // Shown at 170°, turned 20° clockwise: shown as -170°, stored as 190°.
        val (_, recorded) = Keyframing.recordEdit(base, animation, base.with(rotationDegrees = -170f), sec)
        assertEquals(190.0, recorded[TransformChannel.ROTATION]!!.at(sec)!!.value, 1e-4)
        // Halfway it is at 180°, not swinging back through 0.
        assertEquals(180f, recorded.transformAt(base, sec / 2).rotationDegrees, 1e-3f)
    }

    @Test
    fun `removing a moment deletes it everywhere, and a last keyframe leaves its value behind`() {
        val animation = twoMoments()
        val (base, without) = Keyframing.removeMoment(Keyframing.channels(ClipTransform.DEFAULT), animation, sec)
        assertEquals(listOf(0L), without.transformKeyframeTimes())
        assertEquals(Keyframing.channels(ClipTransform.DEFAULT), base) // Others remain, so the static values stay.

        val (lastBase, empty) = Keyframing.removeMoment(base, without, 0)
        assertFalse(empty.animatesTransform)
        assertEquals(0.2f, lastBase.getValue(TransformChannel.POSITION_X), 1e-6f) // Holds where it was.
    }

    @Test
    fun `moments are found within a tolerance, nearest first`() {
        val animation = twoMoments()
        assertEquals(sec, Keyframing.momentNear(animation, sec + frame / 2, frame / 2))
        assertNull(Keyframing.momentNear(animation, sec + frame, frame / 2))
        assertEquals(0L, Keyframing.momentNear(animation, -3, frame / 2))
    }

    @Test
    fun `easing and moving apply to the whole moment`() {
        val animation = Keyframing.setEasing(twoMoments(), 0, Easing.Hold)
        TransformChannel.entries.forEach { assertEquals(Easing.Hold, animation[it]!!.at(0)!!.easing) }
        assertEquals(Easing.Hold, Keyframing.easingAt(animation, 0))
        assertEquals(0.2f, animation.transformAt(ClipTransform.DEFAULT, sec - 1).positionX, 1e-6f)

        val moved = Keyframing.moveMoment(animation, 0, frame)
        assertEquals(listOf(frame, sec), moved.transformKeyframeTimes())
        assertEquals(Easing.Hold, Keyframing.easingAt(moved, frame))
    }

    @Test
    fun `overlay edits follow the same rules`() {
        val placed = OverlayTransform(centerX = 0.5f)
        val animation = Keyframing.addMoment(Keyframing.channels(placed), ClipAnimation.NONE, 0)
        val (static, recorded) = Keyframing.recordEdit(placed, animation, placed.copy(centerX = 0.9f), sec)
        assertEquals(placed, static)
        assertEquals(0.7f, recorded.overlayTransformAt(placed, sec / 2).centerX, 1e-6f)
        val moved = Keyframing.offsetPosition(recorded, 0.03f, 0.03f)
        assertEquals(0.73f, moved.overlayTransformAt(placed, sec / 2).centerX, 1e-6f)
    }
}
