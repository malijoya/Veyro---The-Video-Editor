package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.data.project.InMemoryProjectIndex
import com.editor.veyro.data.project.ProjectSummary
import com.editor.veyro.data.project.SourceReferencePolicy
import java.io.File
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
class SafeWriteAndMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun video(id: String = "v") = MediaRef(
        id = MediaId(id), uri = "content://v/$id", kind = MediaKind.VIDEO, displayName = id, mimeType = null,
        durationUs = 4_000_000, widthPx = 1920, heightPx = 1080, rotationDegrees = 0, sizeBytes = 10,
    )

    private fun project(name: String) = Project(ProjectId("p"), name, 1, 2, listOf(video()), null, ProjectSettings.DEFAULT)

    // ---------------- Safe writes ----------------

    @Test
    fun `each save keeps the previous version as a backup and leaves no temp file`() {
        val store = ProjectDocumentStore(tmp.root)
        store.write(project("First"))
        assertFalse(File(tmp.root, "p/project.json.bak").exists())
        store.write(project("Second"))
        assertTrue(File(tmp.root, "p/project.json.bak").readText().contains("\"First\""))
        assertTrue(File(tmp.root, "p/project.json").readText().contains("\"Second\""))
        assertFalse(File(tmp.root, "p/project.json.tmp").exists())
    }

    @Test
    fun `a damaged main document is recovered from the backup`() {
        val store = ProjectDocumentStore(tmp.root)
        store.write(project("First"))
        store.write(project("Second"))
        File(tmp.root, "p/project.json").writeText("{ truncated")
        val read = store.read(ProjectId("p")) as StoreRead.Loaded
        assertEquals("First", read.project.name)
        assertEquals(ProjectDocumentStore.BACKUP, read.recoveredFrom)
    }

    @Test
    fun `a crash between renames recovers the complete temp file`() {
        val store = ProjectDocumentStore(tmp.root)
        store.write(project("Old"))
        // Simulate the crash window: main renamed to .bak, the synced .tmp not yet renamed.
        File(tmp.root, "p/project.json").renameTo(File(tmp.root, "p/project.json.bak"))
        File(tmp.root, "p/project.json.tmp").writeText(ProjectDocumentCodec.encode(project("New")))
        val read = store.read(ProjectId("p")) as StoreRead.Loaded
        assertEquals("New", read.project.name)
        assertEquals(ProjectDocumentStore.TEMP, read.recoveredFrom)
    }

    @Test
    fun `newer-version documents are not replaced by an older backup`() {
        val store = ProjectDocumentStore(tmp.root)
        store.write(project("Old"))
        store.write(project("Old again"))
        File(tmp.root, "p/project.json").writeText("""{"schemaVersion": 99, "id": "p"}""")
        assertTrue(store.read(ProjectId("p")) is StoreRead.Unreadable)
    }

    @Test
    fun `the repository reports recovery and keeps working`() = runTest {
        val recovered = mutableListOf<String>()
        val store = ProjectDocumentStore(tmp.root)
        store.write(project("First"))
        store.write(project("Second"))
        File(tmp.root, "p/project.json").writeText("garbage")
        val repo = FileProjectRepository(
            tmp.root, UnconfinedTestDispatcher(testScheduler), onRecovered = { id, file -> recovered += "$id:$file" },
        )
        assertEquals("First", repo.project(ProjectId("p")).first()?.name)
        assertEquals(listOf("p:${ProjectDocumentStore.BACKUP}"), recovered)
        // The next save writes a clean main document again.
        repo.rename(ProjectId("p"), "Fixed")
        assertEquals("Fixed", (store.read(ProjectId("p")) as StoreRead.Loaded).project.name)
    }

    // ---------------- Migrations ----------------

    @Test
    fun `v3 documents migrate their timeline frame rate into settings`() {
        val v3 = """{"schemaVersion":3,"id":"p","name":"Edited","createdAtEpochMs":1,"updatedAtEpochMs":2,
            "media":[{"id":"v","uri":"content://v","kind":"video","durationUs":4000000,"widthPx":1280,"heightPx":720,"rotationDegrees":0}],
            "timeline":{"frameRateNum":25,"frameRateDen":1,"tracks":[
              {"id":"video-main","kind":"video","clips":[
                {"id":"clip-v","mediaId":"v","startUs":0,"sourceInUs":400000,"durationUs":1200000},
                {"id":"cut","mediaId":"v","startUs":1200000,"sourceInUs":1600000,"durationUs":800000}]},
              {"id":"audio-1","kind":"audio","clips":[]}]}}"""
        val project = (ProjectDocumentCodec.decode(v3) as DecodeResult.Ok).project
        assertEquals(FrameRate.FPS_25, project.settings.frameRate)
        assertEquals(FrameRate.FPS_25, project.timeline!!.frameRate)
        assertEquals(1_280, project.settings.canvasWidthPx)
        val clips = project.timeline!!.mainTrack!!.clips
        assertEquals(listOf(0L, 1_200_000L), clips.map { it.startUs })
        assertEquals(listOf(400_000L, 1_600_000L), clips.map { it.sourceInUs })
        assertEquals(listOf(1_200_000L, 800_000L), clips.map { it.durationUs })
        assertTrue(clips.all { it.effects.isEmpty() })
    }

    @Test
    fun `every older document is rewritten as the current schema on its next save`() = runTest {
        File(tmp.root, "p").mkdirs()
        File(tmp.root, "p/project.json").writeText(
            """{"schemaVersion":2,"id":"p","name":"Old","createdAtEpochMs":1,"updatedAtEpochMs":2,
               "media":[{"id":"v","uri":"content://v","kind":"video","durationUs":4000000,"widthPx":10,"heightPx":10,"rotationDegrees":0}]}""",
        )
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler))
        repo.rename(ProjectId("p"), "New")
        assertTrue(File(tmp.root, "p/project.json").readText().contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        // The original v2 text is kept as the backup.
        assertTrue(File(tmp.root, "p/project.json.bak").readText().contains("\"schemaVersion\":2"))
    }

    // ---------------- Index ----------------

    @Test
    fun `the index is reconciled with documents on disk`() = runTest {
        ProjectDocumentStore(tmp.root).write(project("On disk only"))
        val index = InMemoryProjectIndex()
        // A stale row for a project whose folder was removed.
        index.upsert(ProjectSummary.of(project("Ghost").copy(id = ProjectId("ghost"))))
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), index = index)
        assertEquals(listOf("On disk only"), repo.summaries.first().map { it.name })
        assertEquals(setOf(ProjectId("p")), index.ids())
    }

    @Test
    fun `index failures never fail a save`() = runTest {
        val errors = mutableListOf<Throwable>()
        val broken = object : com.editor.veyro.data.project.ProjectIndex by InMemoryProjectIndex() {
            override suspend fun upsert(summary: ProjectSummary) = throw IllegalStateException("database locked")
        }
        val repo = FileProjectRepository(tmp.root, UnconfinedTestDispatcher(testScheduler), newId = { "p" }, index = broken, onIndexError = { errors += it })
        val created = repo.create("Saved anyway", listOf(video()))
        assertEquals("Saved anyway", repo.project(created.id).first()?.name)
        assertTrue(File(tmp.root, "p/project.json").exists())
        assertEquals(1, errors.size)
    }

    // ---------------- Source references ----------------

    @Test
    fun `temporary cache paths are never accepted as sources`() = runTest {
        val cache = tmp.newFolder("cache")
        val policy = SourceReferencePolicy(listOf(cache))
        assertTrue(policy.isPermanent("content://media/external/video/media/12"))
        assertTrue(policy.isPermanent(File(tmp.root, "Movies/clip.mp4").toURI().toString()))
        assertFalse(policy.isPermanent(File(cache, "picked/clip.mp4").toURI().toString()))
        assertFalse(policy.isPermanent("file:///tmp/clip.mp4"))
        assertFalse(policy.isPermanent("file:///data/local/tmp/clip.mp4"))

        val repo = FileProjectRepository(tmp.newFolder("projects"), UnconfinedTestDispatcher(testScheduler), sourcePolicy = policy)
        val cached = video().copy(uri = File(cache, "clip.mp4").toURI().toString())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.create("Cache", listOf(cached)) }
        }
        assertNull(repo.summaries.first().firstOrNull())
    }
}
