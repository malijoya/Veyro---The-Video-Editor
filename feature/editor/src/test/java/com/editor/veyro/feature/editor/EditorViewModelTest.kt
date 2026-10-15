package com.editor.veyro.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Stores projects in memory, including saved timelines. */
    private class FakeRepository(project: Project) : ProjectRepository {
        val saved = MutableStateFlow(listOf(project))
        var timelineSaves = 0
        override val projects: Flow<List<Project>> = saved
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int? {
            saved.value = saved.value.map {
                if (it.id == id) it.copy(media = it.media + media, timeline = it.timeline?.let { t -> DefaultArrangement.append(t, media) }) else it
            }
            return media.size
        }
        override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean {
            timelineSaves++
            saved.value = saved.value.map { if (it.id == id) it.copy(timeline = timeline) else it }
            return true
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
    private var nextClip = 0

    private fun TestScope.viewModel(repository: ProjectRepository, saved: SavedStateHandle = SavedStateHandle()): EditorViewModel {
        val vm = EditorViewModel(
            savedState = saved,
            projectId = ProjectId("p"),
            repository = repository,
            createController = { scope -> PreviewController({ RecordingEngine().also { engines += it } }, scope) },
            newClipId = { ClipId("split-${nextClip++}") },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun project(vararg media: MediaRef) = Project(ProjectId("p"), "Trip", 0, 0, media.toList())
    private fun EditorViewModel.ready() = uiState.value as EditorUiState.Ready
    private fun EditorUiState.Ready.main() = tracks.first { it.kind == TrackKind.VIDEO }
    private fun EditorUiState.Ready.audio() = tracks.first { it.kind == TrackKind.AUDIO }

    // ---------------- Display ----------------

    @Test
    fun `unedited projects show the default arrangement and play the main track`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO), media("img", MediaKind.IMAGE), media("song", MediaKind.AUDIO, 9_000_000))))
        val state = vm.ready()
        assertEquals(listOf("v.file", "img.file"), state.main().clips.map { it.title })
        assertTrue(state.main().magnetic)
        assertFalse(state.audio().magnetic)
        assertEquals(9_000_000L, state.durationUs)
        assertEquals(TrackKind.VIDEO, state.previewTrackKind)

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
    fun `audio-only and empty projects`() = runTest {
        assertEquals(TrackKind.AUDIO, viewModel(FakeRepository(project(media("song", MediaKind.AUDIO)))).ready().previewTrackKind)
        val empty = viewModel(FakeRepository(project())).ready()
        assertNull(empty.previewTrackKind)
        assertEquals(0L, empty.durationUs)
    }

    @Test
    fun `missing project shows not found`() = runTest {
        val repository = FakeRepository(project())
        repository.saved.value = emptyList()
        assertEquals(EditorUiState.NotFound, viewModel(repository).uiState.value)
    }

    // ---------------- Split ----------------

    @Test
    fun `split at the playhead saves, selects the right part, and plays continuously`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.controller.attach()

        vm.split(playheadUs = 1_500_000)
        val clips = vm.ready().main().clips
        assertEquals(listOf("clip-v", "split-0"), clips.map { it.id.value })
        assertEquals(clips[0].endUs, clips[1].startUs)
        assertEquals(clips[0].sourceOutUs, clips[1].sourceInUs)
        assertEquals(ClipId("split-0"), vm.ready().selectedClip?.id)
        assertEquals(1, repository.timelineSaves)
        assertTrue(vm.ready().canUndo)

        // The preview plays both parts back to back, covering the same source range.
        val items = engines.single().loads.last()
        assertEquals(2, items.size)
        assertEquals(items[0].clipEndMs, items[1].clipStartMs)
    }

    @Test
    fun `split snaps the playhead to the frame grid`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000))))
        vm.split(playheadUs = 1_010_000) // 30 fps (default): nearest frame start is frame 30 = 1 000 000 us.
        assertEquals(1_000_000L, vm.ready().main().clips[1].startUs)
    }

    @Test
    fun `split with nothing under the playhead reports it`() = runTest {
        val vm = viewModel(FakeRepository(project(media("song", MediaKind.AUDIO))))
        vm.split(playheadUs = 500_000)
        assertEquals(EditorMessage.NOTHING_TO_SPLIT, vm.ready().message)
        vm.messageShown()
        assertNull(vm.ready().message)
    }

    // ---------------- Undo / redo ----------------

    @Test
    fun `undo and redo restore saved timelines`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.split(2_000_000)
        assertEquals(2, vm.ready().main().clips.size)

        vm.undo()
        assertEquals(1, vm.ready().main().clips.size)
        assertFalse(vm.ready().canUndo)
        assertTrue(vm.ready().canRedo)

        vm.redo()
        assertEquals(2, vm.ready().main().clips.size)
        assertEquals(2, repository.saved.value.single().timeline!!.mainTrack!!.clips.size)
    }

    // ---------------- Drags ----------------

    @Test
    fun `trim drag shows a draft and saves once on release`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000), media("w", MediaKind.VIDEO, 3_000_000)))
        val vm = viewModel(repository)
        vm.controller.attach()
        val loadsBefore = engines.single().loads.size

        vm.beginDrag(ClipId("clip-v"), DragHandle.END)
        assertEquals(ClipId("clip-v"), vm.ready().selectedClip?.id) // Starting a drag selects the clip.
        vm.updateDrag(deltaUs = -1_000_000, snapThresholdUs = 0, playheadUs = 0)
        vm.updateDrag(deltaUs = -1_500_000, snapThresholdUs = 0, playheadUs = 0)
        val draft = vm.ready()
        assertTrue(draft.isDragging)
        assertEquals(2_500_000L, draft.main().clips[0].durationUs)
        assertEquals(2_500_000L, draft.main().clips[1].startUs) // The magnetic track follows live.
        assertEquals(0, repository.timelineSaves) // Nothing is saved mid-drag...
        assertEquals(loadsBefore, engines.single().loads.size) // ...and the player isn't reloaded.

        vm.endDrag()
        assertFalse(vm.ready().isDragging)
        assertEquals(1, repository.timelineSaves)
        assertEquals(2_500_000L, repository.saved.value.single().timeline!!.mainTrack!!.clips[0].durationUs)
        assertTrue(vm.ready().canUndo)
    }

    @Test
    fun `trim drag snaps to the playhead and reports the guide`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000))))
        vm.beginDrag(ClipId("clip-v"), DragHandle.END)
        // The end edge is dragged to 2.98 s. The playhead at 3 s is within the 50 ms threshold.
        vm.updateDrag(deltaUs = -1_020_000, snapThresholdUs = 50_000, playheadUs = 3_000_000)
        assertEquals(3_000_000L, vm.ready().main().clips[0].endUs)
        assertEquals(3_000_000L, vm.ready().snapGuideUs)
        // Outside the threshold, it rounds to the frame grid with no guide.
        vm.updateDrag(deltaUs = -1_510_000, snapThresholdUs = 50_000, playheadUs = 3_000_000)
        assertNull(vm.ready().snapGuideUs)
        assertEquals(FrameRate.FPS_30.snapUs(2_490_000), vm.ready().main().clips[0].endUs)
    }

    @Test
    fun `cancelled drags change nothing`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.beginDrag(ClipId("clip-v"), DragHandle.START)
        vm.updateDrag(1_000_000, 0, 0)
        vm.cancelDrag()
        assertEquals(4_000_000L, vm.ready().main().clips[0].durationUs)
        assertEquals(0, repository.timelineSaves)
        assertFalse(vm.ready().canUndo)
    }

    @Test
    fun `dragging a clip body on the main track reorders it`() = runTest {
        val vm = viewModel(FakeRepository(project(media("a", MediaKind.VIDEO, 2_000_000), media("b", MediaKind.VIDEO, 2_000_000))))
        vm.beginDrag(ClipId("clip-b"), DragHandle.BODY)
        vm.updateDrag(deltaUs = -2_000_000, snapThresholdUs = 0, playheadUs = 0)
        vm.endDrag()
        assertEquals(listOf("clip-b", "clip-a"), vm.ready().main().clips.map { it.id.value })
    }

    // ---------------- Delete and gaps ----------------

    @Test
    fun `deleting on the audio track keeps or closes the gap and clears the selection`() = runTest {
        val vm = viewModel(FakeRepository(project(media("s1", MediaKind.AUDIO, 3_000_000), media("s2", MediaKind.AUDIO, 2_000_000))))
        vm.selectClip(ClipId("clip-s1"))
        vm.deleteSelected(closeGap = false)
        assertNull(vm.ready().selectedClip)
        assertEquals(3_000_000L, vm.ready().audio().clips.single().startUs)
        assertTrue(vm.ready().audio().hasGaps)

        vm.closeGaps(DefaultArrangement.AUDIO_TRACK_ID)
        assertEquals(0L, vm.ready().audio().clips.single().startUs)
        assertFalse(vm.ready().audio().hasGaps)

        vm.closeGaps(DefaultArrangement.AUDIO_TRACK_ID)
        assertEquals(EditorMessage.NO_GAPS, vm.ready().message)
    }

    @Test
    fun `media added after editing is appended without losing edits`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.split(2_000_000)
        repository.addMedia(ProjectId("p"), listOf(media("w", MediaKind.VIDEO, 1_000_000)))
        assertEquals(listOf("clip-v", "split-0", "clip-w"), vm.ready().main().clips.map { it.id.value })
    }

    @Test
    fun `selection survives process death`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO)))
        val saved = SavedStateHandle()
        viewModel(repository, saved).selectClip(ClipId("clip-v"))
        assertNotNull(viewModel(repository, saved).ready().selectedClip)
    }
}
