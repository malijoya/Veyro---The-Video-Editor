package com.editor.veyro.core.model.project

import com.editor.veyro.core.model.time.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKindsTest {

    private fun media(
        id: String,
        kind: MediaKind,
        durationUs: Long = if (kind == MediaKind.IMAGE) 0 else 1_000_000,
        width: Int = if (kind == MediaKind.AUDIO) 0 else 1920,
        height: Int = if (kind == MediaKind.AUDIO) 0 else 1080,
        video: VideoTrackInfo? = null,
        audio: AudioTrackInfo? = null,
    ) = MediaRef(
        id = MediaId(id), uri = "content://test/$id", kind = kind, displayName = id, mimeType = null,
        durationUs = durationUs, widthPx = width, heightPx = height, rotationDegrees = 0, sizeBytes = null,
        video = video, audio = audio,
    )

    private val aac = AudioTrackInfo(codecMime = "audio/mp4a-latm", sampleRateHz = 48_000, channelCount = 2, bitrate = 128_000)

    @Test
    fun `audio media has duration but no picture`() {
        val song = media("song", MediaKind.AUDIO, audio = aac)
        assertTrue(song.hasAudio)
        assertEquals(0, song.displayWidthPx)
        assertThrows(IllegalArgumentException::class.java) { media("bad", MediaKind.AUDIO, width = 10, height = 10) }
        assertThrows(IllegalArgumentException::class.java) {
            media("bad", MediaKind.AUDIO, video = VideoTrackInfo("video/avc", null, null))
        }
    }

    @Test
    fun `images carry no tracks`() {
        assertThrows(IllegalArgumentException::class.java) { media("img", MediaKind.IMAGE, audio = aac) }
    }

    @Test
    fun `video reports audio only when it has a sound track`() {
        assertFalse(media("silent", MediaKind.VIDEO).hasAudio)
        assertTrue(media("talk", MediaKind.VIDEO, audio = aac).hasAudio)
    }

    @Test
    fun `cover prefers visual media and duration counts video only`() {
        val project = Project(
            id = ProjectId("p"), name = "Mix", createdAtEpochMs = 0, updatedAtEpochMs = 0,
            media = listOf(
                media("song", MediaKind.AUDIO, durationUs = 180_000_000),
                media("clip", MediaKind.VIDEO, durationUs = 5_000_000),
            ),
        )
        assertEquals(MediaId("clip"), project.coverMedia?.id)
        assertEquals(5_000_000L, project.mediaDurationUs)
        assertTrue(project.containsUri("content://test/song"))
        assertFalse(project.containsUri("content://test/other"))

        val audioOnly = project.copy(media = listOf(media("song", MediaKind.AUDIO)))
        assertEquals(MediaId("song"), audioOnly.coverMedia?.id)
        assertEquals(0L, audioOnly.mediaDurationUs)
    }

    @Test
    fun `measured frame rates snap to standard rates`() {
        assertEquals(FrameRate.FPS_29_97, FrameRate.fromApproximateFps(29.97002997))
        assertEquals(FrameRate.FPS_29_97, FrameRate.fromApproximateFps(29.97))
        assertEquals(FrameRate.FPS_30, FrameRate.fromApproximateFps(30.0))
        assertEquals(FrameRate.FPS_30, FrameRate.fromApproximateFps(30.004))
        assertEquals(FrameRate.FPS_23_976, FrameRate.fromApproximateFps(23.976))
        assertEquals(FrameRate.FPS_59_94, FrameRate.fromApproximateFps(59.94006))
        assertEquals(FrameRate.of(120), FrameRate.fromApproximateFps(120.0))
    }

    @Test
    fun `non-standard rates keep millisecond-fps precision`() {
        assertEquals(FrameRate.of(15), FrameRate.fromApproximateFps(15.0))
        assertEquals(FrameRate.of(27_500, 1_000), FrameRate.fromApproximateFps(27.5))
        assertEquals(FrameRate.of(12_346, 1_000), FrameRate.fromApproximateFps(12.3456))
    }

    @Test
    fun `unusable rates return null`() {
        assertNull(FrameRate.fromApproximateFps(0.0))
        assertNull(FrameRate.fromApproximateFps(-24.0))
        assertNull(FrameRate.fromApproximateFps(Double.NaN))
        assertNull(FrameRate.fromApproximateFps(Double.POSITIVE_INFINITY))
        assertNull(FrameRate.fromApproximateFps(5_000.0))
        assertNull(FrameRate.fromApproximateFps(0.0001))
    }
}
