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
    fun `v1 documents are rewritten in the current schema on the next save`() = runTest {
        File(tmp.root, "p1").apply { mkdirs() }.resolve("project.json").writeText(v1Fixture)
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), clock = { 99 })
        assertEquals("Old trip", repo.projects.first().single().name)

        repo.rename(ProjectId("p1"), "Renamed")
        val text = File(tmp.root, "p1/project.json").readText()
        assertTrue(text.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
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
    fun `audio settings, track roles and gain keyframes round-trip in v5`() {
        val song = MediaRef(
            id = MediaId("s"), uri = "content://s", kind = MediaKind.AUDIO, displayName = "song.mp3", mimeType = "audio/mpeg",
            durationUs = 60_000_000, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = 1,
            audio = AudioTrackInfo("audio/mpeg", 44_100, 2, null),
        )
        val clipAudio = com.editor.veyro.core.model.audio.ClipAudio(
            volume = 0.35f, muted = true, fadeInUs = 1_500_000, fadeOutUs = 2_250_000,
            keyframes = listOf(
                com.editor.veyro.core.model.audio.GainKeyframe(1_000_000, 0.5f, com.editor.veyro.core.model.audio.GainInterpolation.HOLD),
                com.editor.veyro.core.model.audio.GainKeyframe(9_000_000, 1f),
            ),
        )
        val timeline = com.editor.veyro.core.model.timeline.Timeline(
            listOf(
                com.editor.veyro.core.model.timeline.Track(com.editor.veyro.core.model.timeline.TrackId("v"), com.editor.veyro.core.model.timeline.TrackKind.VIDEO, emptyList(), audio = com.editor.veyro.core.model.audio.TrackAudio(volume = 0.6f)),
                com.editor.veyro.core.model.timeline.Track(
                    com.editor.veyro.core.model.timeline.TrackId("m"), com.editor.veyro.core.model.timeline.TrackKind.AUDIO,
                    listOf(com.editor.veyro.core.model.timeline.Clip(com.editor.veyro.core.model.timeline.ClipId("c"), song.id, 0, 0, 10_000_000, audio = clipAudio)),
                    audio = com.editor.veyro.core.model.audio.TrackAudio(muted = true),
                    role = com.editor.veyro.core.model.audio.AudioRole.MUSIC,
                ),
                com.editor.veyro.core.model.timeline.Track(com.editor.veyro.core.model.timeline.TrackId("vo"), com.editor.veyro.core.model.timeline.TrackKind.AUDIO, emptyList(), role = com.editor.veyro.core.model.audio.AudioRole.VOICEOVER),
            ),
        )
        val project = Project(ProjectId("p"), "Mix", 1, 2, listOf(song), timeline)
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
        // Default sound isn't written out.
        val plain = project.copy(timeline = timeline.copy(tracks = timeline.tracks.map { t -> t.copy(audio = com.editor.veyro.core.model.audio.TrackAudio.DEFAULT, clips = t.clips.map { it.copy(audio = com.editor.veyro.core.model.audio.ClipAudio.DEFAULT) }) }))
        assertTrue(!ProjectDocumentCodec.encode(plain).contains("\"fadeInUs\""))
    }

    @Test
    fun `hand-edited audio values are brought into range instead of failing the project`() {
        val project = Project(ProjectId("p"), "Mix", 1, 2, emptyList())
        val json = ProjectDocumentCodec.encode(project).replace(
            "\"timeline\":null",
            """"timeline":{"tracks":[{"id":"m","kind":"audio","clips":[],"audio":{"volume":7.5,"muted":false}}]}""",
        )
        val decoded = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project
        assertEquals(1f, decoded.timeline!!.tracks.single().audio.volume)
    }

    @Test
    fun `v4 files written before the snap distance setting read with the default`() {
        val project = Project(ProjectId("p"), "Snap", 1, 2, emptyList())
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"snapDistanceDp\":12"))
        val withoutField = json.replace(",\"snapDistanceDp\":12", "").replace("\"snapDistanceDp\":12,", "")
        val decoded = (ProjectDocumentCodec.decode(withoutField) as DecodeResult.Ok).project
        assertEquals(com.editor.veyro.core.model.project.ProjectSettings.DEFAULT_SNAP_DISTANCE_DP, decoded.settings.snapDistanceDp)
        // An out-of-range value (a hand-edited file) also falls back instead of failing the load.
        val outOfRange = json.replace("\"snapDistanceDp\":12", "\"snapDistanceDp\":999")
        assertEquals(12, (ProjectDocumentCodec.decode(outOfRange) as DecodeResult.Ok).project.settings.snapDistanceDp)
    }

    // ---------------- v6: text and stickers ----------------

    private fun overlayProject(): Project {
        val video = MediaRef(
            id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4", mimeType = "video/mp4",
            durationUs = 5_000_000, widthPx = 1920, heightPx = 1080, rotationDegrees = 0, sizeBytes = 1,
        )
        val logo = MediaRef(
            id = MediaId("logo"), uri = "content://logo", kind = MediaKind.IMAGE, displayName = "logo.png", mimeType = "image/png",
            durationUs = 0, widthPx = 512, heightPx = 256, rotationDegrees = 0, sizeBytes = 1,
        )
        val styled = com.editor.veyro.core.model.overlay.Overlay(
            com.editor.veyro.core.model.overlay.OverlayContent.Text(
                "Hello\nمرحبا 漢字 😀",
                com.editor.veyro.core.model.overlay.TextStyle(
                    font = com.editor.veyro.core.model.overlay.OverlayFont.HANDWRITING,
                    bold = true,
                    italic = true,
                    sizeFraction = 0.12f,
                    color = com.editor.veyro.core.model.overlay.Argb(0xCCFF8800.toInt()),
                    align = com.editor.veyro.core.model.overlay.TextAlign.RIGHT,
                    outline = com.editor.veyro.core.model.overlay.TextOutline(com.editor.veyro.core.model.overlay.Argb.BLACK, 0.1f),
                    shadow = com.editor.veyro.core.model.overlay.TextShadow(com.editor.veyro.core.model.overlay.Argb(0x80000000.toInt()), 0.2f, -0.05f, 0.07f),
                    background = com.editor.veyro.core.model.overlay.TextBackground(com.editor.veyro.core.model.overlay.Argb(0x66FFFFFF), 0.3f, 0.5f),
                ),
            ),
            com.editor.veyro.core.model.overlay.OverlayTransform(centerX = 0.2f, centerY = 1.2f, scale = 2.5f, rotationDegrees = -45f, opacity = 0.75f),
        )
        val stickerOverlay = com.editor.veyro.core.model.overlay.Overlay(
            com.editor.veyro.core.model.overlay.OverlayContent.Sticker,
            com.editor.veyro.core.model.overlay.OverlayTransform(opacity = 0.5f),
        )
        val timeline = com.editor.veyro.core.model.timeline.Timeline(
            listOf(
                com.editor.veyro.core.model.timeline.Track(
                    com.editor.veyro.core.model.timeline.TrackId("overlay-2"), com.editor.veyro.core.model.timeline.TrackKind.OVERLAY,
                    listOf(com.editor.veyro.core.model.timeline.Clip(com.editor.veyro.core.model.timeline.ClipId("t"), null, 500_000, 0, 2_000_000, overlay = styled)),
                ),
                com.editor.veyro.core.model.timeline.Track(
                    com.editor.veyro.core.model.timeline.TrackId("overlay-1"), com.editor.veyro.core.model.timeline.TrackKind.OVERLAY,
                    listOf(com.editor.veyro.core.model.timeline.Clip(com.editor.veyro.core.model.timeline.ClipId("s"), MediaId("logo"), 0, 0, 4_000_000, overlay = stickerOverlay)),
                ),
                com.editor.veyro.core.model.timeline.Track(
                    com.editor.veyro.core.model.timeline.TrackId("main"), com.editor.veyro.core.model.timeline.TrackKind.VIDEO,
                    listOf(com.editor.veyro.core.model.timeline.Clip(com.editor.veyro.core.model.timeline.ClipId("c"), MediaId("v"), 0, 0, 5_000_000)),
                ),
            ),
        )
        return Project(ProjectId("p"), "Titles", 1, 2, listOf(video, logo), timeline)
    }

    @Test
    fun `text and stickers round-trip in v6 with every style and placement property`() {
        val project = overlayProject()
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":6"))
        assertTrue(json.contains("\"kind\":\"overlay\""))
        assertTrue(json.contains("\"font\":\"handwriting\""))
        assertTrue(json.contains("\"color\":\"#CCFF8800\""))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
    }

    @Test
    fun `hand-edited overlay values are brought into range instead of failing the project`() {
        val json = ProjectDocumentCodec.encode(overlayProject())
            .replace("\"font\":\"handwriting\"", "\"font\":\"not-a-font\"")
            .replace("\"color\":\"#CCFF8800\"", "\"color\":\"orange\"")
            .replace("\"size\":0.12", "\"size\":9.0")
            .replace("\"scale\":2.5", "\"scale\":-3.0")
            .replace("\"rotationDegrees\":-45.0", "\"rotationDegrees\":315.0")
        val decoded = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project
        val overlay = decoded.timeline!!.findClip(com.editor.veyro.core.model.timeline.ClipId("t"))!!.overlay!!
        val style = (overlay.content as com.editor.veyro.core.model.overlay.OverlayContent.Text).style
        assertEquals(com.editor.veyro.core.model.overlay.OverlayFont.SANS, style.font)
        assertEquals(com.editor.veyro.core.model.overlay.Argb.WHITE, style.color)
        assertEquals(com.editor.veyro.core.model.overlay.TextStyle.MAX_SIZE, style.sizeFraction)
        assertEquals(com.editor.veyro.core.model.overlay.OverlayTransform.MIN_SCALE, overlay.transform.scale)
        assertEquals(-45f, overlay.transform.rotationDegrees)
    }

    @Test
    fun `v5 documents, where every clip had media, still read`() {
        val v5 = ProjectDocumentCodec.encode(overlayProject().copy(timeline = overlayProject().timeline!!.let { t -> t.copy(tracks = t.tracks.drop(2)) }))
            .replace("\"schemaVersion\":$CURRENT_SCHEMA_VERSION", "\"schemaVersion\":5")
        val decoded = (ProjectDocumentCodec.decode(v5) as DecodeResult.Ok).project
        assertEquals(MediaId("v"), decoded.timeline!!.tracks.single().clips.single().mediaId)
    }

    @Test
    fun `a sticker added to a project stays off its timeline`() = runTest {
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), clock = { 1 }, newId = { "p" })
        fun item(name: String, kind: MediaKind) = MediaRef(
            id = MediaId(name), uri = "content://$name", kind = kind, displayName = name, mimeType = null,
            durationUs = if (kind == MediaKind.VIDEO) 2_000_000 else 0, widthPx = 100, heightPx = 100, rotationDegrees = 0, sizeBytes = null,
        )
        repo.create("Base", listOf(item("clip", MediaKind.VIDEO)))
        // Never edited yet: the timeline is the default arrangement, and stays exactly that.
        assertEquals(1, repo.addMedia(ProjectId("p"), listOf(item("logo", MediaKind.IMAGE)), arrange = false))
        val project = repo.project(ProjectId("p")).first()!!
        assertEquals(listOf("content://clip", "content://logo"), project.media.map { it.uri })
        val main = project.timeline!!.mainTrack!!
        assertEquals(listOf(MediaId("clip")), main.clips.map { it.mediaId })

        // Imports (the default) still place new media on the timeline.
        repo.addMedia(ProjectId("p"), listOf(item("more", MediaKind.IMAGE)))
        assertEquals(listOf(MediaId("clip"), MediaId("more")), repo.project(ProjectId("p")).first()!!.timeline!!.mainTrack!!.clips.map { it.mediaId })
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
