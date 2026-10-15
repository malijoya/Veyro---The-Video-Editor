package com.editor.veyro.data.project

import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Timeline
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAutosaveTest {

    /** Records timeline writes. It can be told to fail. */
    private class RecordingRepository : ProjectRepository {
        val writes = mutableListOf<Pair<ProjectId, Timeline>>()
        var failWrites = false
        override val summaries: Flow<List<ProjectSummary>> = emptyFlow()
        override fun project(id: ProjectId): Flow<Project?> = emptyFlow()
        override suspend fun create(name: String, media: List<MediaRef>, settings: ProjectSettings?) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int? = error("unused")
        override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean {
            if (failWrites) throw IOException("disk full")
            writes += id to timeline
            return true
        }
        override suspend fun updateSettings(id: ProjectId, settings: ProjectSettings) = error("unused")
        override suspend fun importProject(project: Project) = error("unused")
        override suspend fun rename(id: ProjectId, name: String) = error("unused")
        override suspend fun delete(id: ProjectId) = error("unused")
        override suspend fun referencedMediaUris() = emptySet<String>()
        override suspend fun storageBytes() = 0L
    }

    private val p = ProjectId("p")
    private fun timeline(n: Long) = Timeline(emptyList(), FrameRate.of(n))

    private fun TestScope.autosave(repo: ProjectRepository) =
        ProjectAutosave(repo, kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler)), delayMs = 500, clock = { 42 })

    @Test
    fun `a burst of edits becomes one write of the latest timeline`() = runTest {
        val repo = RecordingRepository()
        val autosave = autosave(repo)
        autosave.schedule(p, timeline(1))
        advanceTimeBy(200)
        autosave.schedule(p, timeline(2))
        advanceTimeBy(200)
        autosave.schedule(p, timeline(3))
        assertEquals(ProjectAutosave.Status.Pending, autosave.status.value)
        advanceTimeBy(499)
        runCurrent()
        assertTrue(repo.writes.isEmpty()) // Not until 500 ms after the last edit.
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(p to timeline(3)), repo.writes)
        assertEquals(ProjectAutosave.Status.Saved(42), autosave.status.value)
    }

    @Test
    fun `flush writes immediately and the pending timer then does nothing`() = runTest {
        val repo = RecordingRepository()
        val autosave = autosave(repo)
        autosave.schedule(p, timeline(1))
        autosave.flush()
        assertEquals(1, repo.writes.size)
        assertFalse(autosave.hasPendingChanges())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, repo.writes.size)
    }

    @Test
    fun `a failed write is kept and retried by the next save`() = runTest {
        val repo = RecordingRepository()
        val autosave = autosave(repo)
        repo.failWrites = true
        autosave.schedule(p, timeline(1))
        advanceTimeBy(600)
        runCurrent()
        assertEquals(ProjectAutosave.Status.Failed, autosave.status.value)
        assertTrue(autosave.hasPendingChanges())

        repo.failWrites = false
        autosave.flush()
        assertEquals(listOf(p to timeline(1)), repo.writes)
        assertEquals(ProjectAutosave.Status.Saved(42), autosave.status.value)
    }

    @Test
    fun `a newer edit wins over a failed older one`() = runTest {
        val repo = RecordingRepository()
        val autosave = autosave(repo)
        repo.failWrites = true
        autosave.schedule(p, timeline(1))
        advanceTimeBy(600)
        runCurrent()
        repo.failWrites = false
        autosave.schedule(p, timeline(2))
        autosave.flush()
        assertEquals(listOf(p to timeline(2)), repo.writes)
    }

    @Test
    fun `edits to different projects are all written`() = runTest {
        val repo = RecordingRepository()
        val autosave = autosave(repo)
        autosave.schedule(p, timeline(1))
        autosave.schedule(ProjectId("q"), timeline(2))
        autosave.flush()
        assertEquals(setOf(p, ProjectId("q")), repo.writes.map { it.first }.toSet())
    }
}
