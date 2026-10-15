package com.editor.veyro.data.project.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.data.project.ProjectSummary
import com.editor.veyro.data.project.file.FileProjectRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Room index and the file store together, on a device, through close and reopen. */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "persistence-test").apply { deleteRecursively(); mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun media(id: String, kind: MediaKind) = MediaRef(
        id = MediaId(id), uri = "content://test/$id", kind = kind, displayName = id, mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else 5_000_000,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 1920, heightPx = if (kind == MediaKind.AUDIO) 0 else 1080,
        rotationDegrees = 0, sizeBytes = 1,
    )

    @Test
    fun indexStoresSummariesAndMediaAndCascadesDeletes() = runBlocking {
        val index = RoomProjectIndex.inMemory(context)
        val a = ProjectSummary(ProjectId("a"), "A", 1, 10, 2, 5_000_000, 8_000_000, "content://x", MediaKind.VIDEO, true, setOf("content://x", "content://y"))
        val b = a.copy(id = ProjectId("b"), name = "B", updatedAtEpochMs = 20, mediaUris = setOf("content://y"))
        index.upsert(a)
        index.upsert(b)
        assertEquals(listOf("B", "A"), index.summaries.first().map { it.name }) // Most recently updated first.
        assertEquals(setOf("content://x", "content://y"), index.referencedMediaUris())

        index.upsert(a.copy(mediaUris = setOf("content://z"))) // Replacing a row replaces its media.
        assertEquals(setOf("content://y", "content://z"), index.referencedMediaUris())

        index.delete(ProjectId("b"))
        assertEquals(setOf("content://z"), index.referencedMediaUris()) // Cascade removed b's media rows.
        assertEquals(setOf(ProjectId("a")), index.ids())
        assertEquals(a.copy(mediaUris = setOf("content://z")), index.summaries.first().single())
    }

    @Test
    fun projectSurvivesCloseAndReopenWithRoomIndex() = runBlocking {
        val first = FileProjectRepository(dir, Dispatchers.IO, index = RoomProjectIndex.inMemory(context), newId = { "trip" })
        val created = first.create("Trip", listOf(media("v", MediaKind.VIDEO), media("song", MediaKind.AUDIO)))
        val edited = DefaultArrangement.timelineFor(created).let { timeline ->
            val main = timeline.mainTrack!!
            val clip = main.clips.single()
            timeline.withTrack(main.copy(clips = listOf(clip.copy(sourceInUs = 1_000_000, durationUs = 2_000_000))))
        }
        first.updateTimeline(created.id, edited)
        val saved = first.project(created.id).first()!!

        // Reopen with a brand-new (empty) database, as if the index were lost: it rebuilds from the document.
        val reopened = FileProjectRepository(dir, Dispatchers.IO, index = RoomProjectIndex.inMemory(context))
        assertEquals(saved, reopened.project(created.id).first())
        val summary = reopened.summaries.first().single()
        assertEquals("Trip", summary.name)
        assertEquals(setOf("content://test/v", "content://test/song"), summary.mediaUris)
        val clip = reopened.project(created.id).first()!!.timeline!!.findClip(ClipId("clip-v"))!!
        assertEquals(1_000_000L, clip.sourceInUs)
        assertEquals(2_000_000L, clip.durationUs)
        assertTrue(File(dir, "trip/project.json").isFile)
    }
}
