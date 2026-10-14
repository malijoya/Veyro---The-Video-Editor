package com.editor.veyro.core.model.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectModelTest {

    private fun video(id: String, durationUs: Long, rotation: Int = 0) = MediaRef(
        id = MediaId(id), uri = "content://media/$id", kind = MediaKind.VIDEO,
        displayName = "$id.mp4", mimeType = "video/mp4", durationUs = durationUs,
        widthPx = 1920, heightPx = 1080, rotationDegrees = rotation, sizeBytes = 1_000,
    )

    private fun image(id: String) = MediaRef(
        id = MediaId(id), uri = "content://media/$id", kind = MediaKind.IMAGE,
        displayName = "$id.jpg", mimeType = "image/jpeg", durationUs = 0,
        widthPx = 4000, heightPx = 3000, rotationDegrees = 0, sizeBytes = null,
    )

    @Test
    fun `project sums video durations and uses first media as cover`() {
        val project = Project(
            id = ProjectId("p1"), name = "Trip", createdAtEpochMs = 1, updatedAtEpochMs = 2,
            media = listOf(video("a", 2_000_000), image("b"), video("c", 500_000)),
        )
        assertEquals(2_500_000L, project.mediaDurationUs)
        assertEquals(MediaId("a"), project.coverMedia?.id)
        assertNull(project.copy(media = emptyList()).coverMedia)
    }

    @Test
    fun `display size accounts for rotation`() {
        val portrait = video("a", 1, rotation = 90)
        assertEquals(1080, portrait.displayWidthPx)
        assertEquals(1920, portrait.displayHeightPx)
        assertEquals(1920, video("b", 1, rotation = 180).displayWidthPx)
    }

    @Test
    fun `invalid media is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { video("a", -1) }
        assertThrows(IllegalArgumentException::class.java) { video("a", 1, rotation = 45) }
        assertThrows(IllegalArgumentException::class.java) { image("a").copy(durationUs = 5) }
        assertThrows(IllegalArgumentException::class.java) { ProjectId(" ") }
    }

    @Test
    fun `rotation normalizes to quarter turns`() {
        assertEquals(0, MediaRef.normalizeRotation(0))
        assertEquals(90, MediaRef.normalizeRotation(90))
        assertEquals(270, MediaRef.normalizeRotation(-90))
        assertEquals(0, MediaRef.normalizeRotation(360))
        assertEquals(180, MediaRef.normalizeRotation(170))
        assertEquals(90, MediaRef.normalizeRotation(450))
    }

    @Test
    fun `project names are trimmed collapsed and bounded`() {
        assertEquals("Summer trip", ProjectName.normalize("  Summer \n\t trip  "))
        assertNull(ProjectName.normalize("   \n "))
        val long = "x".repeat(200)
        assertEquals(ProjectName.MAX_LENGTH, ProjectName.normalize(long)!!.length)
        // Truncation never splits a surrogate pair.
        val emoji = "🎬".repeat(100)
        val normalized = ProjectName.normalize(emoji)!!
        assertEquals(ProjectName.MAX_LENGTH, normalized.codePointCount(0, normalized.length))
    }
}
