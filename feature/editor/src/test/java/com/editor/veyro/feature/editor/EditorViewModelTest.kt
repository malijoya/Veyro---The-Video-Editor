package com.editor.veyro.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.TrackKind
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingEngine : PlaybackEngine {
        override val player: Player? = null
        override var status = EngineStatus.IDLE
        override val isPlaying = false
        override var playWhenReady = false
        override val currentItemIndex = 0
        override val currentPositionMs = 0L
        override val currentItemDurationMs: Long? = null
        override val hasVideo = true
        override val errorCode: Int? = null
        val loads = mutableListOf<List<EngineItem>>()
        override fun setListener(listener: (() -> Unit)?) = Unit
        override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
            loads += items
        }
        override fun play() {
            playWhenReady = true
        }
        override fun pause() {
            playWhenReady = false
        }
        override fun seekTo(itemIndex: Int, positionMs: Long) = Unit
        override fun release() = Unit
    }

    private class FakeRepository(project: Project) : ProjectRepository {
        val saved = MutableStateFlow(listOf(project))
        override val projects: Flow<List<Project>> = saved
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int? {
            saved.value = saved.value.map { if (it.id == id) it.copy(media = it.media + media) else it }
            return media.size
        }
        override suspend fun rename(id: ProjectId, name: String) = false
        override suspend fun delete(id: ProjectId) = false
        override suspend fun referencedMediaUris() = emptySet<String>()
        override suspend fun storageBytes() = 0L
    }

    private fun media(id: String, kind: MediaKind, durationUs: Long = 2_000_000) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = "$id.file", mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 1920, heightPx = if (kind == MediaKind.AUDIO) 0 else 1080,
        rotationDegrees = 0, sizeBytes = null,
    )

    private val engines = mutableListOf<RecordingEngine>()

    private fun viewModel(repository: ProjectRepository, saved: SavedStateHandle = SavedStateHandle()) = EditorViewModel(
        savedState = saved,
        projectId = ProjectId("p"),
        repository = repository,
        createController = { scope -> PreviewController({ RecordingEngine().also { engines += it } }, scope) },
    )

    private fun project(vararg media: MediaRef) = Project(ProjectId("p"), "Trip", 0, 0, media.toList())

    @Test
    fun `state mirrors the project's arrangement`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO), media("img", MediaKind.IMAGE), media("song", MediaKind.AUDIO, 9_000_000))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.uiState.value as EditorUiState.Ready
        assertEquals("Trip", state.projectName)
        assertEquals(listOf(TrackKind.VIDEO, TrackKind.AUDIO), state.tracks.map { it.kind })
        assertEquals(listOf("v.file", "img.file"), state.tracks[0].clips.map { it.title })
        assertEquals(9_000_000L, state.durationUs)
        assertEquals(TrackKind.VIDEO, state.previewTrackKind)
    }

    @Test
    fun `preview plays the main track with photos as stills`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 2_000_000), media("img", MediaKind.IMAGE))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.controller.attach()

        assertEquals(
            listOf(
                EngineItem("content://v", clipStartMs = 0, clipEndMs = 2_000),
                EngineItem("content://img", clipStartMs = 0, clipEndMs = null, stillDurationMs = DefaultArrangement.PHOTO_DURATION_US / 1_000),
            ),
            engines.single().loads.single(),
        )
    }

    @Test
    fun `audio-only projects preview the audio track`() = runTest {
        val vm = viewModel(FakeRepository(project(media("song", MediaKind.AUDIO))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(TrackKind.AUDIO, (vm.uiState.value as EditorUiState.Ready).previewTrackKind)
    }

    @Test
    fun `empty projects have nothing to preview`() = runTest {
        val vm = viewModel(FakeRepository(project()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val state = vm.uiState.value as EditorUiState.Ready
        assertNull(state.previewTrackKind)
        assertEquals(0L, state.durationUs)
    }

    @Test
    fun `selection is kept, survives process death, and clears for unknown clips`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO)))
        val saved = SavedStateHandle()
        val vm = viewModel(repository, saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.selectClip(ClipId("clip-v"))
        assertEquals(ClipId("clip-v"), (vm.uiState.value as EditorUiState.Ready).selectedClip?.id)

        val restored = viewModel(repository, saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }
        assertEquals(ClipId("clip-v"), (restored.uiState.value as EditorUiState.Ready).selectedClip?.id)

        vm.selectClip(ClipId("gone"))
        assertNull((vm.uiState.value as EditorUiState.Ready).selectedClip)
        vm.selectClip(null)
        assertNull((vm.uiState.value as EditorUiState.Ready).selectedClip)
    }

    @Test
    fun `adding media updates the timeline and the preview plan in place`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO)))
        val vm = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.controller.attach()

        repository.addMedia(ProjectId("p"), listOf(media("w", MediaKind.VIDEO, 1_000_000)))
        val state = vm.uiState.value as EditorUiState.Ready
        assertEquals(3_000_000L, state.durationUs)
        assertEquals(1, engines.size) // Same player, new plan.
        assertEquals(2, engines.single().loads.last().size)
    }

    @Test
    fun `missing project shows not found`() = runTest {
        val repository = FakeRepository(project())
        repository.saved.value = emptyList()
        val vm = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(EditorUiState.NotFound, vm.uiState.value)
    }
}
