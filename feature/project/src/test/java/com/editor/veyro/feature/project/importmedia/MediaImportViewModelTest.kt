package com.editor.veyro.feature.project.importmedia

import androidx.lifecycle.SavedStateHandle
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaImportViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeGateway : ImportMediaGateway {
        val persisted = mutableListOf<String>()
        val refusePersist = mutableSetOf<String>()
        val releasedKeeping = mutableListOf<Set<String>>()
        private val pending = mutableMapOf<String, CompletableDeferred<ImportMediaGateway.Result>>()

        override suspend fun persistAccess(uri: String): Boolean {
            persisted += uri
            return uri !in refusePersist
        }

        override suspend fun probe(uri: String) = pending.getOrPut(uri) { CompletableDeferred() }.await()

        override suspend fun releaseAllExcept(referencedUris: Set<String>) {
            releasedKeeping += referencedUris
        }

        fun complete(uri: String, kind: MediaKind = MediaKind.VIDEO) {
            pending.getOrPut(uri) { CompletableDeferred() }.complete(ImportMediaGateway.Result.Ok(media(uri, kind)))
        }

        fun fail(uri: String, failure: ImportFailure) {
            pending.getOrPut(uri) { CompletableDeferred() }.complete(ImportMediaGateway.Result.Err(failure))
        }
    }

    private class FakeRepository : ProjectRepository {
        val saved = MutableStateFlow<List<Project>>(emptyList())
        var failNextWrite = false
        override val summaries: Flow<List<com.editor.veyro.data.project.ProjectSummary>> = saved.map { all -> all.map(com.editor.veyro.data.project.ProjectSummary::of) }
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>, settings: com.editor.veyro.core.model.project.ProjectSettings?): Project {
            failIfRequested()
            val project = Project(ProjectId("p${saved.value.size}"), name.trim(), 0, 0, media)
            saved.value = saved.value + project
            return project
        }
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>, arrange: Boolean): Int? {
            failIfRequested()
            val project = saved.value.firstOrNull { it.id == id } ?: return null
            val fresh = media.filterNot { project.containsUri(it.uri) }
            saved.value = saved.value.map { if (it.id == id) it.copy(media = it.media + fresh) else it }
            return fresh.size
        }
        override suspend fun updateTimeline(id: ProjectId, timeline: com.editor.veyro.core.model.timeline.Timeline) = false
        override suspend fun updateSettings(id: ProjectId, settings: com.editor.veyro.core.model.project.ProjectSettings): Boolean {
            saved.value = saved.value.map { if (it.id == id) it.copy(settings = settings, timeline = it.timeline?.copy(frameRate = settings.frameRate)) else it }
            return saved.value.any { it.id == id }
        }
        override suspend fun importProject(project: Project): Project {
            val imported = project.copy(id = ProjectId("imported-${saved.value.size}"))
            saved.value = saved.value + imported
            return imported
        }
        override suspend fun rename(id: ProjectId, name: String) = false
        override suspend fun delete(id: ProjectId): Boolean {
            val before = saved.value.size
            saved.value = saved.value.filterNot { it.id == id }
            return saved.value.size != before
        }
        override suspend fun referencedMediaUris() = saved.value.flatMap { p -> p.media.map { it.uri } }.toSet()
        override suspend fun storageBytes() = 0L

        private fun failIfRequested() {
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("disk full")
            }
        }
    }

    private val gateway = FakeGateway()
    private val repository = FakeRepository()

    private fun newProject(saved: SavedStateHandle = SavedStateHandle()) =
        MediaImportViewModel(saved, ImportTarget.NewProject, repository, gateway, defaultName = "Project Oct 14")

    private fun addTo(id: ProjectId) =
        MediaImportViewModel(SavedStateHandle(), ImportTarget.ExistingProject(id), repository, gateway, defaultName = "")

    @Test
    fun `picked files are persisted, probed and selected when ready`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("v", "a", "v"))
        assertEquals(listOf("v", "a"), gateway.persisted)
        assertTrue(vm.uiState.value.isProbing)
        assertFalse(vm.uiState.value.canImport)

        gateway.complete("v")
        gateway.complete("a", MediaKind.AUDIO)
        val state = vm.uiState.value
        assertFalse(state.isProbing)
        assertEquals(listOf("v", "a"), state.selectedMedia.map { it.uri })
        assertTrue(state.canImport)
    }

    @Test
    fun `failed files are shown but can't be selected`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("ok", "bad"))
        gateway.complete("ok")
        gateway.fail("bad", ImportFailure.UNSUPPORTED_TYPE)

        vm.toggle("bad")
        val state = vm.uiState.value
        assertEquals(2, state.items.size)
        assertEquals(1, state.selectableCount)
        assertEquals(listOf("ok"), state.selectedMedia.map { it.uri })
        assertFalse(state.isSelected(state.items[1]))
    }

    @Test
    fun `toggle, select all and clear change the selection`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("a", "b", "c"))
        listOf("a", "b", "c").forEach { gateway.complete(it) }

        vm.toggle("b")
        assertEquals(listOf("a", "c"), vm.uiState.value.selectedMedia.map { it.uri })
        vm.clearSelection()
        assertTrue(vm.uiState.value.selectedMedia.isEmpty())
        assertFalse(vm.uiState.value.canImport)
        vm.selectAll()
        assertEquals(3, vm.uiState.value.selectedMedia.size)
    }

    @Test
    fun `creating a project saves only selected media and releases the rest`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("keep", "skip", "broken"))
        gateway.complete("keep")
        gateway.complete("skip", MediaKind.IMAGE)
        gateway.fail("broken", ImportFailure.UNREADABLE)
        vm.toggle("skip")
        vm.setName("  Holiday ")
        vm.importSelected()

        val project = repository.saved.value.single()
        assertEquals("Holiday", project.name)
        assertEquals(listOf("keep"), project.media.map { it.uri })
        assertEquals(ImportOutcome.Created(project.id), vm.uiState.value.outcome)
        assertEquals(listOf(setOf("keep")), gateway.releasedKeeping)
    }

    @Test
    fun `adding to a project skips media it already has`() = runTest {
        repository.create("Existing", listOf(media("old", MediaKind.VIDEO)))
        val id = repository.saved.value.single().id
        val vm = addTo(id)
        vm.onPicked(listOf("old", "song"))
        gateway.complete("old")
        gateway.complete("song", MediaKind.AUDIO)

        val state = vm.uiState.value
        assertTrue(state.isAlreadyInProject(state.items[0]))
        assertFalse(state.isSelectable(state.items[0]))
        assertEquals(listOf("song"), state.selectedMedia.map { it.uri })

        vm.importSelected()
        assertEquals(ImportOutcome.Added(id, 1), vm.uiState.value.outcome)
        assertEquals(listOf("old", "song"), repository.saved.value.single().media.map { it.uri })
    }

    @Test
    fun `adding to a deleted project reports it instead of importing`() = runTest {
        repository.create("Gone soon", listOf(media("x", MediaKind.VIDEO)))
        val id = repository.saved.value.single().id
        val vm = addTo(id)
        vm.onPicked(listOf("y"))
        gateway.complete("y")
        repository.delete(id)

        vm.importSelected()
        assertTrue(vm.uiState.value.targetMissing)
        assertFalse(vm.uiState.value.canImport)
        assertNull(vm.uiState.value.outcome)
    }

    @Test
    fun `write failures are reported and can be retried`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("a"))
        gateway.complete("a")
        repository.failNextWrite = true
        vm.importSelected()
        assertTrue(vm.uiState.value.importFailed)
        assertFalse(vm.uiState.value.isImporting)

        vm.importSelected()
        assertEquals(1, repository.saved.value.size)
    }

    @Test
    fun `a blank name blocks creating a project`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("a"))
        gateway.complete("a")
        vm.setName("   ")
        assertFalse(vm.uiState.value.canImport)
        vm.importSelected()
        assertTrue(repository.saved.value.isEmpty())
    }

    @Test
    fun `providers that refuse persistent access are flagged`() = runTest {
        gateway.refusePersist += "cloud"
        val vm = newProject()
        vm.onPicked(listOf("cloud", "local"))
        assertEquals(setOf("cloud"), vm.uiState.value.temporaryAccess)
    }

    @Test
    fun `cancelling the first picker closes and releases grants`() = runTest {
        val vm = newProject()
        vm.onPicked(emptyList())
        assertTrue(vm.uiState.value.closed)
        assertEquals(listOf(emptySet<String>()), gateway.releasedKeeping)
    }

    @Test
    fun `cancelling a later picker keeps the current selection`() = runTest {
        val vm = newProject()
        vm.onPicked(listOf("a"))
        vm.onPicked(emptyList())
        assertFalse(vm.uiState.value.closed)
        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test
    fun `import is restored after process death`() = runTest {
        val saved = SavedStateHandle()
        newProject(saved).apply {
            onInitialPickerLaunched()
            setName("Restored")
            onPicked(listOf("a", "b"))
            gateway.complete("a")
            gateway.complete("b")
            toggle("b")
        }

        val restored = newProject(saved)
        val state = restored.uiState.value
        assertEquals("Restored", state.name)
        assertTrue(state.initialPickerLaunched)
        assertEquals(listOf("a", "b"), state.items.map { it.uri })
        // The probes are cached in the fake, so the restored items are ready again.
        assertEquals(listOf("a"), state.selectedMedia.map { it.uri })
    }

    private companion object {
        fun media(uri: String, kind: MediaKind) = MediaRef(
            id = MediaId("id-$uri"), uri = uri, kind = kind, displayName = uri, mimeType = null,
            durationUs = if (kind == MediaKind.IMAGE) 0 else 1_000_000,
            widthPx = if (kind == MediaKind.AUDIO) 0 else 1920,
            heightPx = if (kind == MediaKind.AUDIO) 0 else 1080,
            rotationDegrees = 0, sizeBytes = null,
        )
    }
}
