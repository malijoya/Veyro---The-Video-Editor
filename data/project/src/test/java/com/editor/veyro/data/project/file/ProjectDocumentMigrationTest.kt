package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.MediaTags
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.VideoTrackInfo
import com.editor.veyro.core.model.time.FrameRate
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDocumentMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A document exactly as the v1 build wrote it. Kept verbatim as a migration fixture. */
    private val v1Fixture = """
        {"schemaVersion":1,"id":"p1","name":"Old trip","createdAtEpochMs":10,"updatedAtEpochMs":20,
         "media":[
           {"id":"m1","uri":"content://media/1","kind":"video","displayName":"a.mp4","mimeType":"video/mp4",
            "durationUs":3000000,"widthPx":1920,"heightPx":1080,"rotationDegrees":90,"sizeBytes":1234},
           {"id":"m2","uri":"content://media/2","kind":"image","displayName":null,"mimeType":"image/jpeg",
            "durationUs":0,"widthPx":4000,"heightPx":3000,"rotationDegrees":0,"sizeBytes":null}
         ]}
    """.trimIndent()

    @Test
    fun `v1 documents migrate without losing data`() {
        val project = (ProjectDocumentCodec.decode(v1Fixture) as DecodeResult.Ok).project
        assertEquals("Old trip", project.name)
        assertEquals(2, project.media.size)
        val video = project.media[0]
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals(3_000_000L, video.durationUs)
        assertEquals(90, video.rotationDegrees)
        assertNull(video.video)
        assertNull(video.audio)
        assertEquals(MediaKind.IMAGE, project.media[1].kind)
    }

    @Test
    fun `v1 documents are rewritten as v2 on the next save`() = runTest {
        File(tmp.root, "p1").apply { mkdirs() }.resolve("project.json").writeText(v1Fixture)
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), clock = { 99 })
        assertEquals("Old trip", repo.projects.first().single().name)

        repo.rename(ProjectId("p1"), "Renamed")
        val text = File(tmp.root, "p1/project.json").readText()
        assertTrue(text.contains("\"schemaVersion\":2"))
        assertEquals("Renamed", (ProjectDocumentCodec.decode(text) as DecodeResult.Ok).project.name)
    }

    @Test
    fun `v2 round-trips audio media and track details`() {
        val project = Project(
            id = ProjectId("p"), name = "Mix", createdAtEpochMs = 1, updatedAtEpochMs = 2,
            media = listOf(
                MediaRef(
                    id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4",
                    mimeType = "video/mp4", durationUs = 10_010_000, widthPx = 3840, heightPx = 2160,
                    rotationDegrees = 0, sizeBytes = 99,
                    video = VideoTrackInfo("video/hevc", FrameRate.FPS_29_97, 40_000_000),
                    audio = AudioTrackInfo("audio/mp4a-latm", 48_000, 2, 256_000),
                ),
                MediaRef(
                    id = MediaId("a"), uri = "content://a", kind = MediaKind.AUDIO, displayName = "song.mp3",
                    mimeType = "audio/mpeg", durationUs = 200_000_000, widthPx = 0, heightPx = 0,
                    rotationDegrees = 0, sizeBytes = 5_000_000,
                    audio = AudioTrackInfo("audio/mpeg", 44_100, 2, 320_000),
                    tags = MediaTags("Title", "Artist", null),
                ),
                MediaRef(
                    id = MediaId("s"), uri = "content://s", kind = MediaKind.VIDEO, displayName = null,
                    mimeType = null, durationUs = 1, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = null,
                    video = VideoTrackInfo(null, null, null),
                ),
            ),
        )
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"kind\":\"audio\""))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
    }

    @Test
    fun `addMedia appends new sources and skips duplicates`() = runTest {
        var now = 1L
        val repo = FileProjectRepository(
            tmp.root, UnconfinedTestDispatcher(testScheduler), clock = { now }, newId = { "p" },
        )
        fun audio(name: String) = MediaRef(
            id = MediaId(name), uri = "content://$name", kind = MediaKind.AUDIO, displayName = name,
            mimeType = "audio/mpeg", durationUs = 1_000, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = null,
        )
        repo.create("Base", listOf(audio("a")))
        now = 5

        assertEquals(1, repo.addMedia(ProjectId("p"), listOf(audio("a"), audio("b"), audio("b"))))
        val project = repo.project(ProjectId("p")).first()!!
        assertEquals(listOf("content://a", "content://b"), project.media.map { it.uri })
        assertEquals(5L, project.updatedAtEpochMs)

        now = 9
        assertEquals(0, repo.addMedia(ProjectId("p"), listOf(audio("a"))))
        assertEquals(5L, repo.project(ProjectId("p")).first()!!.updatedAtEpochMs)
        assertNull(repo.addMedia(ProjectId("missing"), listOf(audio("c"))))
    }
}
