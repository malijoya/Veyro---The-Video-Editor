package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.Keyframe
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.ClipTransform
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render plan's keyframes: the placement a renderer reads for a frame at
 * composition time t is the clip's animation at clip-local time t - start,
 * for preview and export alike, in any order.
 */
class KeyframePlanTest {

    private val sec = 1_000_000L
    private val rate = FrameRate.FPS_30

    private val video = MediaRef(
        id = MediaId("cam"), uri = "content://cam", kind = MediaKind.VIDEO, displayName = "cam.mp4", mimeType = "video/mp4",
        durationUs = 20 * sec, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 0, sizeBytes = null,
    )
    private val photo = MediaRef(
        id = MediaId("photo"), uri = "content://photo", kind = MediaKind.IMAGE, displayName = "photo.jpg", mimeType = "image/jpeg",
        durationUs = 0, widthPx = 4_000, heightPx = 3_000, rotationDegrees = 0, sizeBytes = null,
    )

    private val zoom = ClipAnimation()
        .with(TransformChannel.SCALE, Keyframe(0, 1.0, Easing.EASE_IN_OUT))
        .with(TransformChannel.SCALE, Keyframe(2 * sec, 2.0))
        .with(TransformChannel.ROTATION, Keyframe(sec, 0.0))
        .with(TransformChannel.ROTATION, Keyframe(3 * sec, -90.0))

    private val fadeIn = ClipAnimation()
        .with(TransformChannel.OPACITY, Keyframe(0, 0.0, Easing.EASE_OUT))
        .with(TransformChannel.OPACITY, Keyframe(500_000, 1.0))
        .with(TransformChannel.POSITION_Y, Keyframe(0, 1.2))
        .with(TransformChannel.POSITION_Y, Keyframe(sec, 0.8))

    /** Video 0..3 s (source from 5 s, zooming), photo 3..6 s (zooming too), video again 6..8 s (static). Text 1..5 s fading in. */
    private fun timeline() = Timeline(
        listOf(
            Track(
                TrackId("overlay-1"), TrackKind.OVERLAY,
                listOf(
                    Clip(ClipId("T"), null, sec, 0, 4 * sec, overlay = Overlay(OverlayContent.Text("Title"), OverlayTransform(opacity = 0f)), animation = fadeIn),
                    Clip(ClipId("Ghost"), null, 6 * sec, 0, sec, overlay = Overlay(OverlayContent.Text("Never"), OverlayTransform(opacity = 0f))),
                ),
            ),
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("a"), MediaId("cam"), 0, 5 * sec, 3 * sec, transform = ClipTransform(positionX = 0.4f), animation = zoom),
                    Clip(ClipId("b"), MediaId("photo"), 3 * sec, 0, 3 * sec, animation = zoom),
                    Clip(ClipId("c"), MediaId("cam"), 6 * sec, 0, 2 * sec),
                ),
            ),
        ),
        rate,
    )

    private fun plan(): ExportPlan {
        val result = ExportPlanner.plan(timeline(), listOf(video, photo), ExportSettings(1_280, 720, rate))
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan
    }

    /** Every frame of the plan, at the project rate. */
    private fun frames(plan: ExportPlan) = (0 until rate.usToFrame(plan.durationUs)).map { rate.frameToUs(it) }

    @Test
    fun `segments carry their clip's keyframes and start where the clip does`() {
        val plan = plan()
        assertEquals(listOf(zoom, zoom, ClipAnimation.NONE), plan.video.map { it.animation })
        assertEquals(listOf(0L, 3 * sec, 6 * sec), plan.segmentTransforms().map { it.startUs })
        assertEquals(listOf(false, false, true), plan.segmentTransforms().map { it.isStatic })
    }

    @Test
    fun `the placement for every frame is the clip's animation at the frame's clip-local time`() {
        val plan = plan()
        val transforms = plan.segmentTransforms()
        val main = timeline().mainTrack!!
        frames(plan).forEach { t ->
            val index = main.clips.indexOfFirst { t in it }
            val clip = main.clips[index]
            assertEquals("frame at $t", clip.animation.transformAt(clip.transform, clip.localTimeOf(t)), transforms[index].at(t))
        }
        // Spot checks: halfway through the eased zoom, and the photo starting its own zoom at its own first frame.
        assertEquals(1.5f, transforms[0].at(sec).scale, 1e-6f)
        assertEquals(1f, transforms[1].at(3 * sec).scale, 0f)
        assertEquals(0.4f, transforms[0].at(2 * sec).positionX, 0f)
        assertSame(ClipTransform.DEFAULT, transforms[2].at(7 * sec))
    }

    @Test
    fun `frames rendered in any order get identical placements, as in scrubbing back and export`() {
        val plan = plan()
        val forward = frames(plan).associateWith { t -> plan.segmentTransforms()[segmentIndex(plan, t)].at(t) }
        // A fresh evaluation (as a new composition would make) in reverse and in random order.
        val fresh = plan.copy().segmentTransforms()
        frames(plan).reversed().forEach { t -> assertEquals(forward.getValue(t), fresh[segmentIndex(plan, t)].at(t)) }
        frames(plan).shuffled(Random(3)).forEach { t -> assertEquals(forward.getValue(t), fresh[segmentIndex(plan, t)].at(t)) }
    }

    @Test
    fun `overlays are evaluated per frame, and only never-visible ones are left out`() {
        val plan = plan()
        // The title starts transparent but fades in, so it is kept; the ghost is transparent throughout, so it isn't.
        assertEquals(listOf("T"), plan.overlays.map { it.clipId })
        val title = plan.overlays.single()
        assertEquals(fadeIn, title.animation)
        assertEquals(0f, title.overlayAt(sec).transform.opacity, 0f)
        assertEquals(1f, title.overlayAt(sec + 500_000).transform.opacity, 0f)
        assertEquals(1f, title.overlayAt(4 * sec).transform.opacity, 0f)
        assertEquals(1.0f, title.overlayAt(sec + sec / 2).transform.centerY, 1e-6f)
        // The content never changes.
        assertEquals(title.overlay.content, title.overlayAt(2 * sec).content)
    }

    private fun segmentIndex(plan: ExportPlan, t: Long): Int {
        var start = 0L
        plan.video.forEachIndexed { index, segment ->
            if (t < start + segment.durationUs) return index
            start += segment.durationUs
        }
        return plan.video.lastIndex
    }
}
