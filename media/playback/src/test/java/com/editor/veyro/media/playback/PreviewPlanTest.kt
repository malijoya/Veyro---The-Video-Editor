package com.editor.veyro.media.playback

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewPlanTest {

    private fun media(kind: MediaKind, durationUs: Long = 4_000_000) = MediaRef(
        id = MediaId("m"), uri = "content://m", kind = kind, displayName = null, mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 100, heightPx = if (kind == MediaKind.AUDIO) 0 else 100,
        rotationDegrees = 0, sizeBytes = null,
    )

    /** Two clips back to back: 2 s from 10 s into a.mp4, then 3 s of b.mp4. */
    private val sequence = PreviewPlan(
        listOf(
            PreviewSegment("a.mp4", sourceStartUs = 10_000_000, durationUs = 2_000_000, clipped = true),
            PreviewSegment("b.mp4", sourceStartUs = 0, durationUs = 3_000_000, clipped = true),
        ),
    )

    @Test
    fun `single asset plays whole file unclipped`() {
        val plan = PreviewPlan.forAsset(media(MediaKind.VIDEO))
        assertEquals(4_000_000L, plan.durationUs)
        assertFalse(plan.segments.single().clipped)
        assertEquals(1, PreviewPlan.forAsset(media(MediaKind.AUDIO)).segments.size)
    }

    @Test
    fun `images cannot be planned`() {
        assertThrows(IllegalArgumentException::class.java) { PreviewPlan.forAsset(media(MediaKind.IMAGE)) }
    }

    @Test
    fun `sequence duration and segment starts add up`() {
        assertEquals(5_000_000L, sequence.durationUs)
        assertEquals(0L, sequence.startOfSegmentUs(0))
        assertEquals(2_000_000L, sequence.startOfSegmentUs(1))
    }

    @Test
    fun `locate maps plan time to segment and offset`() {
        assertEquals(PreviewPlan.Location(0, 0), sequence.locate(0))
        assertEquals(PreviewPlan.Location(0, 1_999_999), sequence.locate(1_999_999))
        // A boundary belongs to the following segment.
        assertEquals(PreviewPlan.Location(1, 0), sequence.locate(2_000_000))
        assertEquals(PreviewPlan.Location(1, 500_000), sequence.locate(2_500_000))
        // Out-of-range times clamp.
        assertEquals(PreviewPlan.Location(0, 0), sequence.locate(-5))
        assertEquals(PreviewPlan.Location(1, 3_000_000), sequence.locate(9_000_000))
    }

    @Test
    fun `plan time from segment offset is clamped to the segment`() {
        assertEquals(2_500_000L, sequence.planTimeUs(1, 500_000))
        assertEquals(2_000_000L, sequence.planTimeUs(0, 7_000_000))
        assertEquals(2_000_000L, sequence.planTimeUs(1, -1))
    }

    @Test
    fun `plans compare by content`() {
        assertEquals(PreviewPlan.forAsset(media(MediaKind.VIDEO)), PreviewPlan.forAsset(media(MediaKind.VIDEO)))
        assertNotEquals(PreviewPlan.forAsset(media(MediaKind.VIDEO)), PreviewPlan.forAsset(media(MediaKind.VIDEO, 5_000_000)))
    }

    @Test
    fun `invalid plans are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PreviewPlan(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PreviewSegment("x", 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { PreviewSegment("x", -1, 10, false) }
    }
}
