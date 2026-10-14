package com.editor.veyro.media.importer

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaMetadataAssemblerTest {

    private val id = MediaId("m")
    private fun file(mime: String?, name: String = "clip") = RawFileInfo("content://x/$name", name, mime, 1_234)

    private fun success(result: ProbeResult): MediaRef = (result as ProbeResult.Success).media
    private fun failure(result: ProbeResult): ProbeFailure = (result as ProbeResult.Failure).reason

    @Test
    fun `phone video with sound uses extractor values`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.VIDEO, file("video/mp4"),
                tracks = listOf(
                    RawTrack("video/avc", durationUs = 10_010_000, width = 1920, height = 1080,
                        rotationDegrees = 90, frameRate = 29.97, bitrate = 17_000_000),
                    RawTrack("audio/mp4a-latm", durationUs = 10_005_000, sampleRateHz = 48_000,
                        channelCount = 2, bitrate = 256_000),
                ),
                retriever = RawRetrieverInfo(durationMs = 10_010, rotationDegrees = 0, hasVideo = true, hasAudio = true),
            ),
        )
        assertEquals(MediaKind.VIDEO, media.kind)
        assertEquals(10_010_000L, media.durationUs) // Longest track, microsecond precision.
        assertEquals(90, media.rotationDegrees) // Extractor wins over retriever.
        assertEquals(1080, media.displayWidthPx)
        assertEquals("video/avc", media.video?.codecMime)
        assertEquals(FrameRate.FPS_29_97, media.video?.frameRate)
        assertEquals(17_000_000, media.video?.bitrate)
        assertEquals(48_000, media.audio?.sampleRateHz)
        assertEquals(2, media.audio?.channelCount)
        assertEquals(1_234L, media.sizeBytes)
    }

    @Test
    fun `frame rate falls back to frame count over duration`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.VIDEO, file("video/webm"),
                tracks = listOf(RawTrack("video/x-vnd.on2.vp9", durationUs = 4_000_000, width = 640, height = 360)),
                retriever = RawRetrieverInfo(videoFrameCount = 100, hasVideo = true),
            ),
        )
        assertEquals(FrameRate.FPS_25, media.video?.frameRate)
        assertNull(media.audio)
    }

    @Test
    fun `retriever fills in when the extractor can't read the container`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.VIDEO, file("video/3gpp"),
                tracks = emptyList(),
                retriever = RawRetrieverInfo(durationMs = 2_500, width = 176, height = 144, rotationDegrees = 270,
                    hasVideo = true),
            ),
        )
        assertEquals(2_500_000L, media.durationUs)
        assertEquals(176, media.widthPx)
        assertEquals(270, media.rotationDegrees)
        assertNull(media.video?.frameRate)
        assertNull(media.video?.codecMime)
    }

    @Test
    fun `song keeps tags and uses overall bitrate when the track has none`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.AUDIO, file("audio/mpeg", "song.mp3"),
                tracks = listOf(RawTrack("audio/mpeg", durationUs = 215_000_000, sampleRateHz = 44_100, channelCount = 2)),
                retriever = RawRetrieverInfo(durationMs = 215_000, bitrate = 320_000, hasAudio = true,
                    title = "  Night Drive ", artist = "Someone", album = ""),
            ),
        )
        assertEquals(MediaKind.AUDIO, media.kind)
        assertEquals(0, media.widthPx)
        assertEquals(320_000, media.audio?.bitrate)
        assertEquals("Night Drive", media.tags?.title)
        assertEquals("Someone", media.tags?.artist)
        assertNull(media.tags?.album)
    }

    @Test
    fun `audio-only mp4 is imported as audio`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.VIDEO, file("video/mp4", "memo.mp4"),
                tracks = listOf(RawTrack("audio/mp4a-latm", durationUs = 30_000_000, sampleRateHz = 16_000, channelCount = 1)),
                retriever = RawRetrieverInfo(hasAudio = true),
            ),
        )
        assertEquals(MediaKind.AUDIO, media.kind)
        assertNull(media.video)
        assertEquals(1, media.audio?.channelCount)
    }

    @Test
    fun `empty tags are dropped`() {
        val media = success(
            MediaMetadataAssembler.assembleTimed(
                id, MediaKind.AUDIO, file("audio/x-wav"),
                tracks = listOf(RawTrack("audio/raw", durationUs = 1_000_000)),
                retriever = RawRetrieverInfo(title = " ", hasAudio = true),
            ),
        )
        assertNull(media.tags)
    }

    @Test
    fun `files without usable tracks or duration are unreadable`() {
        assertEquals(
            ProbeFailure.UNREADABLE,
            failure(MediaMetadataAssembler.assembleTimed(id, MediaKind.VIDEO, file("video/mp4"), emptyList(), RawRetrieverInfo())),
        )
        assertEquals(
            ProbeFailure.UNREADABLE,
            failure(
                MediaMetadataAssembler.assembleTimed(
                    id, MediaKind.AUDIO, file("audio/mpeg"),
                    listOf(RawTrack("video/avc", durationUs = 1_000)), null,
                ),
            ),
        )
        assertEquals(
            ProbeFailure.UNREADABLE,
            failure(
                MediaMetadataAssembler.assembleTimed(
                    id, MediaKind.VIDEO, file("video/mp4"),
                    listOf(RawTrack("video/avc", durationUs = 0)), RawRetrieverInfo(durationMs = 0, hasVideo = true),
                ),
            ),
        )
    }

    @Test
    fun `images are rejected by the timed assembler`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaMetadataAssembler.assembleTimed(id, MediaKind.IMAGE, file("image/png"), emptyList(), null)
        }
    }
}
