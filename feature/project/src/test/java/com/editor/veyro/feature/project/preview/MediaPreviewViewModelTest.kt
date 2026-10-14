package com.editor.veyro.feature.project.preview

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.EngineItem
import com.editor.veyro.media.playback.EngineStatus
import com.editor.veyro.media.playback.PlaybackEngine
import com.editor.veyro.media.playback.PreviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPreviewViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingEngine : PlaybackEngine {
        override val player: Player? = null
        override var status = EngineStatus.IDLE
        override var isPlaying = false
        override var playWhenReady = false
        override val currentItemIndex = 0
        override var currentPositionMs = 0L
        override val currentItemDurationMs: Long? = null
        override val hasVideo = true
        override val errorCode: Int? = null
        val loads = mutableListOf<List<EngineItem>>()

        override fun setListener(listener: (() -> Unit)?) = Unit
        override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
            loads += items
            status = EngineStatus.READY
        }
        override fun play() {
            playWhenReady = true
            isPlaying = true
        }
        override fun pause() {
            playWhenReady = false
            isPlaying = false
        }
        override fun seekTo(itemIndex: Int, positionMs: Long) = Unit
        override fun release() = Unit
    }

    private class FakeRepository(project: Project) : ProjectRepository {
        val saved = MutableStateFlow(listOf(project))
        override val projects: Flow<List<Project>> = saved
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int? = error("unused")
        override suspend fun rename(id: ProjectId, name: String): Boolean {
            saved.value = saved.value.map { if (it.id == id) it.copy(name = name) else it }
            return true
        }
        override suspend fun delete(id: ProjectId): Boolean {
            saved.value = emptyList()
            return true
        }
        override suspend fun referencedMediaUris() = emptySet<String>()
        override suspend fun storageBytes() = 0L
    }

    private fun media(id: String, kind: MediaKind) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = "$id file", mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else 3_000_000,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 1920, heightPx = if (kind == MediaKind.AUDIO) 0 else 1080,
        rotationDegrees = 0, sizeBytes = null,
    )

    private val project = Project(
        id = ProjectId("p"), name = "Trip", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        media = listOf(media("clip", MediaKind.VIDEO), media("song", MediaKind.AUDIO), media("photo", MediaKind.IMAGE)),
    )
    private val repository = FakeRepository(project)
    private val engines = mutableListOf<RecordingEngine>()

    private fun viewModel(initial: String = "clip", saved: SavedStateHandle = SavedStateHandle()) = MediaPreviewViewModel(
        savedState = saved,
        projectId = ProjectId("p"),
        initialMediaId = MediaId(initial),
        repository = repository,
        createController = { scope -> PreviewController({ RecordingEngine().also { engines += it } }, scope) },
    )

    @Test
    fun `selected video is planned and loaded once attached`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.uiState.value as MediaPreviewUiState.Ready
        assertEquals(MediaId("clip"), state.media.id)
        assertEquals(3, state.projectMedia.size)

        vm.controller.attach()
        assertEquals(listOf(EngineItem("content://clip", 0, null)), engines.single().loads.single())
    }

    @Test
    fun `switching media reuses the same player`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.controller.attach()

        vm.select(MediaId("song"))
        assertEquals(MediaId("song"), (vm.uiState.value as MediaPreviewUiState.Ready).media.id)
        assertEquals(1, engines.size)
        assertEquals("content://song", engines.single().loads.last().single().uri)
    }

    @Test
    fun `selecting a photo pauses playback and keeps the player`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.controller.attach()
        vm.controller.play()
        assertTrue(engines.single().playWhenReady)

        vm.select(MediaId("photo"))
        assertEquals(MediaKind.IMAGE, (vm.uiState.value as MediaPreviewUiState.Ready).media.kind)
        assertFalse(engines.single().playWhenReady)
        assertEquals(1, engines.single().loads.size) // No plan for stills.
    }

    @Test
    fun `renaming the project does not reload the media`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.controller.attach()
        repository.rename(ProjectId("p"), "Renamed")
        assertEquals("Renamed", (vm.uiState.value as MediaPreviewUiState.Ready).projectName)
        assertEquals(1, engines.single().loads.size)
    }

    @Test
    fun `unknown media or deleted project shows not found`() = runTest {
        val vm = viewModel(initial = "nope")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(MediaPreviewUiState.NotFound, vm.uiState.value)

        val other = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { other.uiState.collect {} }
        repository.delete(ProjectId("p"))
        assertEquals(MediaPreviewUiState.NotFound, other.uiState.value)
    }

    @Test
    fun `selection survives process death`() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved = saved).select(MediaId("song"))
        val restored = viewModel(saved = saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }
        assertEquals(MediaId("song"), (restored.uiState.value as MediaPreviewUiState.Ready).media.id)
    }
}
