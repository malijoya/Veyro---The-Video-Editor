package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private fun media(id: String, kind: MediaKind, durationUs: Long = 2_000_000) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = id, mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 10, heightPx = if (kind == MediaKind.AUDIO) 0 else 10,
        rotationDegrees = 0, sizeBytes = null,
    )

    private fun project(vararg media: MediaRef) = Project(ProjectId("p"), "P", 0, 0, media.toList())

    @Test
    fun `default arrangement puts visuals and audio on separate tracks in order`() {
        val timeline = DefaultArrangement.build(
            project(
                media("v1", MediaKind.VIDEO, 2_000_000),
                media("song", MediaKind.AUDIO, 10_000_000),
                media("photo", MediaKind.IMAGE),
                media("v2", MediaKind.VIDEO, 1_500_000),
            ),
        )
        val video = timeline.tracks[0]
        assertEquals(TrackKind.VIDEO, video.kind)
        assertEquals(listOf(0L, 2_000_000L, 5_000_000L), video.clips.map { it.startUs })
        assertEquals(DefaultArrangement.PHOTO_DURATION_US, video.clips[1].durationUs)
        assertEquals(6_500_000L, video.endUs)

        val audio = timeline.tracks[1]
        assertEquals(TrackKind.AUDIO, audio.kind)
        assertEquals(0L, audio.clips.single().startUs)
        assertEquals(10_000_000L, timeline.durationUs)
        assertEquals(video, timeline.mainTrack)
    }

    @Test
    fun `arrangement is deterministic`() {
        val project = project(media("a", MediaKind.VIDEO), media("b", MediaKind.AUDIO))
        assertEquals(DefaultArrangement.build(project), DefaultArrangement.build(project))
        assertEquals(ClipId("clip-a"), DefaultArrangement.build(project).tracks[0].clips[0].id)
    }

    @Test
    fun `empty project gives empty tracks`() {
        val timeline = DefaultArrangement.build(project())
        assertEquals(0L, timeline.durationUs)
        assertTrue(timeline.tracks.all { it.clips.isEmpty() })
    }

    @Test
    fun `clip lookup by time and id`() {
        val timeline = DefaultArrangement.build(project(media("a", MediaKind.VIDEO, 1_000_000), media("b", MediaKind.VIDEO, 1_000_000)))
        val track = timeline.mainTrack!!
        assertEquals(ClipId("clip-a"), track.clipAt(0)?.id)
        assertEquals(ClipId("clip-a"), track.clipAt(999_999)?.id)
        assertEquals(ClipId("clip-b"), track.clipAt(1_000_000)?.id)
        assertNull(track.clipAt(2_000_000))
        assertEquals(1_000_000L, timeline.findClip(ClipId("clip-b"))?.startUs)
        assertEquals(track, timeline.trackOf(ClipId("clip-b")))
        assertNull(timeline.findClip(ClipId("nope")))
    }

    @Test
    fun `invalid structures are rejected`() {
        val clip = Clip(ClipId("c"), MediaId("m"), startUs = 0, sourceInUs = 0, durationUs = 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            Track(TrackId("t"), TrackKind.VIDEO, listOf(clip, clip.copy(id = ClipId("d"), startUs = 500)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline(listOf(Track(TrackId("t"), TrackKind.VIDEO, listOf(clip)), Track(TrackId("u"), TrackKind.AUDIO, listOf(clip))))
        }
        assertThrows(IllegalArgumentException::class.java) { clip.copy(durationUs = 0) }
    }
}
