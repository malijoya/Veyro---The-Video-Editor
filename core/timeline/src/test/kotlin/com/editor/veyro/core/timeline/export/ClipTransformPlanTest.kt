package com.editor.veyro.core.timeline.export

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
import com.editor.veyro.core.model.transform.ClipPlacement
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the render plan carries clip transforms. */
class ClipTransformPlanTest {

    private val sec = 1_000_000L

    /** A phone's portrait clip: stored landscape, with 90 degrees of rotation metadata. */
    private val portrait = MediaRef(
        id = MediaId("phone"), uri = "content://phone", kind = MediaKind.VIDEO, displayName = "phone.mp4", mimeType = "video/mp4",
        durationUs = 10 * sec, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 90, sizeBytes = null,
    )
    private val photo = MediaRef(
        id = MediaId("photo"), uri = "content://photo", kind = MediaKind.IMAGE, displayName = "photo.jpg", mimeType = "image/jpeg",
        durationUs = 0, widthPx = 4_000, heightPx = 3_000, rotationDegrees = 0, sizeBytes = null,
    )

    private val moved = ClipTransform(positionX = 0.2f, positionY = 0.7f, scale = 0.5f, rotationDegrees = -20f, crop = CropRect(0f, 0.25f, 1f, 0.75f), opacity = 0.6f)

    private fun timeline() = Timeline(
        listOf(
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("a"), MediaId("phone"), 0, sec, 2 * sec, transform = moved),
                    Clip(ClipId("b"), MediaId("photo"), 2 * sec, 0, 3 * sec),
                ),
            ),
        ),
        FrameRate.FPS_30,
    )

    private fun plan(width: Int, height: Int): ExportPlan {
        val result = ExportPlanner.plan(timeline(), listOf(portrait, photo), ExportSettings(width, height, FrameRate.FPS_30))
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan
    }

    @Test
    fun `each segment carries its clip's transform and upright source size`() {
        val video = plan(1_080, 1_920).video
        assertEquals(listOf(moved, ClipTransform.DEFAULT), video.map { it.transform })
        // The rotation metadata is applied: the phone clip is portrait.
        assertEquals(1_080 to 1_920, video[0].sourceWidthPx to video[0].sourceHeightPx)
        assertEquals(4_000 to 3_000, video[1].sourceWidthPx to video[1].sourceHeightPx)
    }

    @Test
    fun `the transform is the same at every export resolution, so the layout scales with it`() {
        val full = plan(1_080, 1_920)
        val small = plan(540, 960)
        assertEquals(full.video.map { it.transform }, small.video.map { it.transform })
        val segment = full.video[0]
        val fullPlacement = ClipPlacement(segment.transform, segment.sourceWidthPx, segment.sourceHeightPx, 1_080, 1_920)
        val smallPlacement = ClipPlacement(segment.transform, segment.sourceWidthPx, segment.sourceHeightPx, 540, 960)
        fullPlacement.corners.zip(smallPlacement.corners).forEach { (f, s) ->
            assertEquals(f.x / 2, s.x, 0.01f)
            assertEquals(f.y / 2, s.y, 0.01f)
        }
    }

    @Test
    fun `a portrait phone clip fills a portrait canvas by default`() {
        val segment = plan(1_080, 1_920).video[0].copy(transform = ClipTransform.DEFAULT)
        val placement = ClipPlacement(segment.transform, segment.sourceWidthPx, segment.sourceHeightPx, 1_080, 1_920)
        assertEquals(1_080f, placement.width, 0.01f)
        assertEquals(1_920f, placement.height, 0.01f)
    }
}
