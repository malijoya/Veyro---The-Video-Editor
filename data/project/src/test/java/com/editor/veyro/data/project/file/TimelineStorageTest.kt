package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun media(id: String, kind: MediaKind, durationUs: Long = 4_000_000) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = id, mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 10, heightPx = if (kind == MediaKind.AUDIO) 0 else 10,
        rotationDegrees = 0, sizeBytes = null,
    )

    private val v = media("v", MediaKind.VIDEO)
    private val song = media("song", MediaKind.AUDIO, 20_000_000)

    /** An edited timeline: v split in two, the song moved to 2 s. */
    private val edited = Timeline(
        tracks = listOf(
            Track(DefaultArrangement.VIDEO_TRACK_ID, TrackKind.VIDEO, listOf(
                Clip(ClipId("clip-v"), v.id, 0, 0, 1_500_000),
                Clip(ClipId("split-1"), v.id, 1_500_000, 1_500_000, 2_500_000),
            )),
            Track(DefaultArrangement.AUDIO_TRACK_ID, TrackKind.AUDIO, listOf(
                Clip(ClipId("clip-song"), song.id, 2_000_000, 0, 20_000_000),
            )),
        ),
        frameRate = FrameRate.FPS_29_97,
    )

    @Test
    fun `timeline round-trips through the v3 document`() {
        val project = Project(ProjectId("p"), "P", 1, 2, listOf(v, song), edited)
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":3"))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
    }

    @Test
    fun `unedited projects store no timeline`() {
        val project = Project(ProjectId("p"), "P", 1, 2, listOf(v))
        val decoded = (ProjectDocumentCodec.decode(ProjectDocumentCodec.encode(project)) as DecodeResult.Ok).project
        assertNull(decoded.timeline)
    }

    @Test
    fun `v2 documents migrate with no timeline`() {
        val v2 = """{"schemaVersion":2,"id":"p","name":"Old","createdAtEpochMs":1,"updatedAtEpochMs":2,
            "media":[{"id":"v","uri":"content://v","kind":"video","durationUs":4000000,"widthPx":10,"heightPx":10,
            "rotationDegrees":0,"video":{"codecMime":"video/avc","frameRateNum":30000,"frameRateDen":1001}}]}"""
        val project = (ProjectDocumentCodec.decode(v2) as DecodeResult.Ok).project
        assertNull(project.timeline)
        // The editor then starts from the default arrangement, at the video's frame rate.
        assertEquals(FrameRate.FPS_29_97, DefaultArrangement.timelineFor(project).frameRate)
    }

    @Test
    fun `documents whose clips reference unknown media are corrupt`() {
        val project = Project(ProjectId("p"), "P", 1, 2, listOf(v), edited) // The song is missing from media.
        val result = ProjectDocumentCodec.decode(ProjectDocumentCodec.encode(project))
        assertTrue(result is DecodeResult.Corrupt)
    }

    @Test
    fun `updateTimeline saves edits and rejects foreign media`() = runTest {
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), clock = { 50 }, newId = { "p" })
        val created = repo.create("Trip", listOf(v, song))
        assertNull(created.timeline)

        assertTrue(repo.updateTimeline(created.id, edited))
        val reloaded = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler)).project(created.id).first()!!
        assertEquals(edited, reloaded.timeline)

        val foreign = edited.withTrack(
            Track(DefaultArrangement.AUDIO_TRACK_ID, TrackKind.AUDIO, listOf(Clip(ClipId("x"), MediaId("ghost"), 0, 0, 1_000))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.updateTimeline(created.id, foreign) }
        }
        assertFalse(repo.updateTimeline(ProjectId("missing"), edited))
    }

    @Test
    fun `adding media to an edited project appends clips and keeps the edits`() = runTest {
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), newId = { "p" })
        val created = repo.create("Trip", listOf(v, song))
        repo.updateTimeline(created.id, edited)

        val photo = media("photo", MediaKind.IMAGE)
        repo.addMedia(created.id, listOf(photo))
        val timeline = repo.project(created.id).first()!!.timeline!!
        val main = timeline.mainTrack!!
        assertEquals(listOf("clip-v", "split-1", "clip-photo"), main.clips.map { it.id.value })
        assertEquals(4_000_000L, main.clips.last().startUs) // Appended at the end of the edited track.
        assertEquals(DefaultArrangement.PHOTO_DURATION_US, main.clips.last().durationUs)
        assertEquals(2_000_000L, timeline.tracks[1].clips.single().startUs) // Other edits untouched.
    }
}
