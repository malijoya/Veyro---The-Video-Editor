package com.editor.veyro.core.model.project

import com.editor.veyro.core.model.time.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectSettingsTest {

    private fun media(kind: MediaKind, width: Int, height: Int, rotation: Int = 0, fps: FrameRate? = null) = MediaRef(
        id = MediaId("m$width"), uri = "content://m", kind = kind, displayName = null, mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else 1_000_000, widthPx = width, heightPx = height,
        rotationDegrees = rotation, sizeBytes = null, video = if (kind == MediaKind.VIDEO) VideoTrackInfo(null, fps, null) else null,
    )

    @Test
    fun `settings match the first visual's upright size and frame rate`() {
        val settings = ProjectSettings.matching(listOf(media(MediaKind.VIDEO, 1920, 1080, rotation = 90, fps = FrameRate.FPS_59_94)))
        assertEquals(1_080, settings.canvasWidthPx)
        assertEquals(1_920, settings.canvasHeightPx)
        assertEquals(FrameRate.FPS_59_94, settings.frameRate)
    }

    @Test
    fun `huge or odd sizes are scaled to 4K with even sides`() {
        assertEquals(3_840 to 2_880, ProjectSettings.fitWithin(4_000, 3_000, 3_840))
        assertEquals(1_280 to 720, ProjectSettings.fitWithin(1_280, 720, 3_840))
        // Odd sides round to the nearest even number (half rounds up): 1081 -> 1082, 1351 -> 1352.
        assertEquals(1_082 to 1_352, ProjectSettings.fitWithin(1_081, 1_351, 3_840))
        val settings = ProjectSettings.matching(listOf(media(MediaKind.IMAGE, 8_000, 6_000)))
        assertEquals(3_840, settings.canvasWidthPx)
        assertEquals(2_880, settings.canvasHeightPx)
    }

    @Test
    fun `audio-only and unknown sizes fall back to defaults`() {
        assertEquals(ProjectSettings.DEFAULT, ProjectSettings.matching(emptyList()))
        assertEquals(ProjectSettings.DEFAULT, ProjectSettings.matching(listOf(media(MediaKind.VIDEO, 0, 0))))
    }

    @Test
    fun `invalid settings are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(1_081, 1_920, FrameRate.FPS_30) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(0, 1_920, FrameRate.FPS_30) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(10_000, 1_920, FrameRate.FPS_30) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(1_080, 1_920, FrameRate.FPS_30, photoDurationUs = 0) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(1_080, 1_920, FrameRate.FPS_30, snapDistanceDp = 0) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(1_080, 1_920, FrameRate.FPS_30, snapDistanceDp = 100) }
    }

    @Test
    fun `presets are valid canvases`() {
        CanvasPreset.entries.forEach { ProjectSettings(it.width, it.height, FrameRate.FPS_30) }
    }
}
