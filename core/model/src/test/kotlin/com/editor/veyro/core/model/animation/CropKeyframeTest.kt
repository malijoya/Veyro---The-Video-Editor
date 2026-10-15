package com.editor.veyro.core.model.animation

import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.transform.AspectRatio
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyframed crop: four edge channels that only pictures have. */
class CropKeyframeTest {

    private val sec = 1_000_000L

    @Test
    fun `crop edges interpolate and the rest of the crop settings stay static`() {
        val base = ClipTransform(crop = CropRect.FULL, cropAspect = AspectRatio.SQUARE)
        val zoomIn = Keyframing.addMoment(Keyframing.channels(base), ClipAnimation.NONE, 0)
        val (static, animation) = Keyframing.recordEdit(base, zoomIn, base.with(crop = CropRect(0.2f, 0.2f, 0.6f, 0.6f)), sec)
        assertEquals(base, static) // The static crop is untouched...
        assertEquals(setOf(TransformChannel.CROP_LEFT, TransformChannel.CROP_TOP, TransformChannel.CROP_RIGHT, TransformChannel.CROP_BOTTOM),
            TransformChannel.entries.filter { animation[it]?.at(sec) != null }.toSet()) // ...only the changed edges are keyed.
        val mid = animation.transformAt(base, sec / 2)
        assertEquals(CropRect(0.1f, 0.1f, 0.8f, 0.8f).left, mid.crop.left, 1e-6f)
        assertEquals(0.8f, mid.crop.right, 1e-6f)
        assertEquals(AspectRatio.SQUARE, mid.cropAspect)
        assertEquals(CropRect(0.2f, 0.2f, 0.6f, 0.6f), animation.transformAt(base, 5 * sec).crop)
    }

    @Test
    fun `crossing edges are ordered and kept a minimum size apart`() {
        val animation = ClipAnimation()
            .with(TransformChannel.CROP_LEFT, Keyframe(0, 0.0))
            .with(TransformChannel.CROP_LEFT, Keyframe(sec, 1.0))
            .with(TransformChannel.CROP_RIGHT, Keyframe(0, 1.0))
            .with(TransformChannel.CROP_RIGHT, Keyframe(sec, 0.0))
        (0..20).map { it * 50_000L }.forEach { t ->
            val crop = animation.transformAt(ClipTransform.DEFAULT, t).crop
            assertTrue(crop.width >= CropRect.MIN_SIZE - 1e-4f)
            assertTrue(crop.left <= crop.right)
        }
    }

    @Test
    fun `overlays have no crop channels`() {
        val placed = OverlayTransform()
        val animation = Keyframing.addMoment(Keyframing.channels(placed), ClipAnimation.NONE, 0)
        assertEquals(TransformChannel.OVERLAY_CHANNELS.toSet(), animation.tracks.keys)
        assertFalse(TransformChannel.OVERLAY_CHANNELS.any { it.isCrop })
        // Crop keyframes, if any, don't change an overlay.
        val stray = animation.with(TransformChannel.CROP_LEFT, Keyframe(0, 0.5))
        assertEquals(animation.overlayTransformAt(placed, 0), stray.overlayTransformAt(placed, 0))
    }

    @Test
    fun `a picture moment keys the crop too, and deleting the last one keeps the crop shown`() {
        val base = ClipTransform(crop = CropRect(0.1f, 0f, 0.9f, 1f))
        val animation = Keyframing.addMoment(Keyframing.channels(base), ClipAnimation.NONE, 0)
        assertEquals(0.1, animation[TransformChannel.CROP_LEFT]!!.at(0)!!.value, 1e-6)
        val (values, empty) = Keyframing.removeMoment(Keyframing.channels(ClipTransform.DEFAULT), animation, 0)
        assertFalse(empty.animatesTransform)
        assertEquals(base.crop, Keyframing.withChannels(ClipTransform.DEFAULT, values).crop)
    }
}
