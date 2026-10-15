package com.editor.veyro.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.TransformChannel
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
import com.editor.veyro.data.project.ProjectAutosave
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.media.playback.EngineItem
import com.editor.veyro.media.playback.EngineStatus
import com.editor.veyro.media.playback.PlaybackEngine
import com.editor.veyro.media.playback.PreviewController
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.AudioSegment
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformBuilder
import com.editor.veyro.core.model.audio.WaveformSource
import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.timeline.LayerMove
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
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
        override var isPlaying = false
        override var playWhenReady = false

        /** The overlay the editor asked the player to leave out. */
        var hidden: String? = null
        override fun setHiddenOverlay(clipId: String?) {
            hidden = clipId
        }
        override val currentItemIndex = 0
        override val currentPositionMs = 0L
        override val currentItemDurationMs: Long? = null
        override val hasVideo = true
        override val errorCode: Int? = null
        val loads = mutableListOf<List<EngineItem>>()

        /** Timelines loaded for playback, as the composition engine would get them. */
        val timelines = mutableListOf<ExportPlan>()
        val timelineStarts = mutableListOf<Long>()
        override fun setListener(listener: (() -> Unit)?) = Unit
        override fun load(items: List<EngineItem>, startIndex: Int, startPositionMs: Long) {
            loads += items
        }
        override fun loadTimeline(plan: ExportPlan, startPositionMs: Long) {
            timelines += plan
            timelineStarts += startPositionMs
        }

        /** Whether this engine applies transform-only changes in place, as the composition engine does. */
        var inPlace = false

        /** Plans applied in place, without a reload. */
        val inPlaceUpdates = mutableListOf<ExportPlan>()
        override fun updateInPlace(plan: ExportPlan): Boolean {
            if (!inPlace) return false
            inPlaceUpdates += plan
            return true
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

    /** Stores projects in memory, including saved timelines. */
    private class FakeRepository(project: Project) : ProjectRepository {
        val saved = MutableStateFlow(listOf(project))
        var timelineSaves = 0
        override val summaries: Flow<List<com.editor.veyro.data.project.ProjectSummary>> = saved.map { all -> all.map(com.editor.veyro.data.project.ProjectSummary::of) }
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>, settings: com.editor.veyro.core.model.project.ProjectSettings?) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>, arrange: Boolean): Int? {
            saved.value = saved.value.map {
                if (it.id != id) return@map it
                val timeline = if (arrange) it.timeline?.let { t -> DefaultArrangement.append(t, media) } else it.timeline ?: DefaultArrangement.timelineFor(it)
                it.copy(media = it.media + media, timeline = timeline)
            }
            return media.size
        }
        override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean {
            timelineSaves++
            saved.value = saved.value.map { if (it.id == id) it.copy(timeline = timeline) else it }
            return true
        }
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

    private fun TestScope.viewModel(
        repository: ProjectRepository,
        saved: SavedStateHandle = SavedStateHandle(),
        waveforms: WaveformSource = WaveformSource.NONE,
        images: ImageImporter = ImageImporter.NONE,
    ): EditorViewModel {
        val vm = EditorViewModel(
            savedState = saved,
            projectId = ProjectId("p"),
            repository = repository,
            createController = { scope -> PreviewController({ RecordingEngine().also { engines += it } }, scope) },
            // No debounce in tests: each edit is written as soon as it's made.
            autosave = ProjectAutosave(repository, kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)), delayMs = 0),
            waveforms = waveforms,
            newClipId = { ClipId("split-${nextClip++}") },
            images = images,
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
        // The whole timeline is played as one composition: the same plan export renders.
        val plan = engines.single().timelines.single()
        assertEquals(listOf("content://v", "content://img"), plan.video.map { it.uri })
        assertEquals(listOf(2_000_000L, DefaultArrangement.PHOTO_DURATION_US), plan.video.map { it.durationUs })
        // The song is mixed in on the music track, cut at the end of the picture.
        assertEquals(1, plan.audioTracks.size)
        assertEquals(plan.durationUs, plan.audioTracks.single().sumOf { it.durationUs })
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
        val parts = engines.single().timelines.last().video
        assertEquals(2, parts.size)
        assertEquals(parts[0].sourceEndUs, parts[1].sourceStartUs)
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
        val loadsBefore = engines.single().timelines.size

        vm.beginDrag(ClipId("clip-v"), DragHandle.END)
        assertEquals(ClipId("clip-v"), vm.ready().selectedClip?.id) // Starting a drag selects the clip.
        vm.updateDrag(deltaUs = -1_000_000, pxPerSecond = MAX_ZOOM, pxPerDp = 1f, playheadUs = 0)
        vm.updateDrag(deltaUs = -1_500_000, pxPerSecond = MAX_ZOOM, pxPerDp = 1f, playheadUs = 0)
        val draft = vm.ready()
        assertTrue(draft.isDragging)
        assertEquals(2_500_000L, draft.main().clips[0].durationUs)
        assertEquals(2_500_000L, draft.main().clips[1].startUs) // The magnetic track follows live.
        assertEquals(0, repository.timelineSaves) // Nothing is saved mid-drag...
        assertEquals(loadsBefore, engines.single().timelines.size) // ...and the player isn't reloaded.

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
        // At 240 px/s the default 12 dp snap distance (density 1) is 50 ms. The end edge is dragged to 2.98 s, within 50 ms of the playhead at 3 s.
        vm.updateDrag(deltaUs = -1_020_000, pxPerSecond = 240f, pxPerDp = 1f, playheadUs = 3_000_000)
        assertEquals(3_000_000L, vm.ready().main().clips[0].endUs)
        assertEquals(3_000_000L, vm.ready().snapGuideUs)
        // Outside the threshold, it rounds to the frame grid with no guide.
        vm.updateDrag(deltaUs = -1_510_000, pxPerSecond = 240f, pxPerDp = 1f, playheadUs = 3_000_000)
        assertNull(vm.ready().snapGuideUs)
        assertEquals(FrameRate.FPS_30.snapUs(2_490_000), vm.ready().main().clips[0].endUs)
    }

    @Test
    fun `cancelled drags change nothing`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.beginDrag(ClipId("clip-v"), DragHandle.START)
        vm.updateDrag(1_000_000, MAX_ZOOM, 1f, 0)
        vm.cancelDrag()
        assertEquals(4_000_000L, vm.ready().main().clips[0].durationUs)
        assertEquals(0, repository.timelineSaves)
        assertFalse(vm.ready().canUndo)
    }

    @Test
    fun `dragging a clip body on the main track reorders it`() = runTest {
        val vm = viewModel(FakeRepository(project(media("a", MediaKind.VIDEO, 2_000_000), media("b", MediaKind.VIDEO, 2_000_000))))
        vm.beginDrag(ClipId("clip-b"), DragHandle.BODY)
        vm.updateDrag(deltaUs = -2_000_000, pxPerSecond = MAX_ZOOM, pxPerDp = 1f, playheadUs = 0)
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

    // ---------------- History across edits ----------------

    @Test
    fun `undo all and redo all across split, trim and delete`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000), media("w", MediaKind.VIDEO, 3_000_000)))
        val vm = viewModel(repository)
        val original = vm.ready().main().clips

        vm.split(2_000_000) // v -> v | split-0
        vm.beginDrag(ClipId("clip-w"), DragHandle.END)
        vm.updateDrag(deltaUs = -1_000_000, pxPerSecond = MAX_ZOOM, pxPerDp = 1f, playheadUs = 0)
        vm.endDrag() // w trimmed to 2 s
        vm.selectClip(ClipId("split-0"))
        vm.deleteSelected(closeGap = true)
        val edited = vm.ready().main().clips
        assertEquals(listOf("clip-v", "clip-w"), edited.map { it.id.value })

        repeat(3) { vm.undo() }
        assertEquals(original, vm.ready().main().clips)
        assertFalse(vm.ready().canUndo)
        assertEquals(original.map { it.id }, repository.saved.value.single().timeline!!.mainTrack!!.clips.map { it.id })

        repeat(3) { vm.redo() }
        assertEquals(edited, vm.ready().main().clips)
        assertFalse(vm.ready().canRedo)
    }

    @Test
    fun `undoing a split clears a selection on the removed part`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000))))
        vm.split(2_000_000)
        assertEquals(ClipId("split-0"), vm.ready().selectedClip?.id)
        vm.undo()
        assertNull(vm.ready().selectedClip)
    }

    @Test
    fun `undo still works after media is appended`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.split(2_000_000)
        repository.addMedia(ProjectId("p"), listOf(media("w", MediaKind.VIDEO, 1_000_000)))
        vm.undo()
        assertEquals(listOf("clip-v", "clip-w"), vm.ready().main().clips.map { it.id.value })
        assertEquals(4_000_000L, vm.ready().main().clips[0].durationUs)
    }

    @Test
    fun `undo that no longer fits the timeline reports it and changes nothing`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 4_000_000)))
        val vm = viewModel(repository)
        vm.split(2_000_000)
        // Another writer replaces the timeline: the split's clips are gone.
        val replaced = repository.saved.value.single().timeline!!.let { t ->
            t.withTrack(t.mainTrack!!.copy(clips = t.mainTrack!!.clips.take(1)))
        }
        repository.updateTimeline(ProjectId("p"), replaced)
        vm.undo()
        assertEquals(EditorMessage.HISTORY_OUT_OF_DATE, vm.ready().message)
        assertFalse(vm.ready().canUndo)
        assertEquals(listOf("clip-v"), vm.ready().main().clips.map { it.id.value })
    }

    // ---------------- Snapping at different zoom levels ----------------

    @Test
    fun `snap distance is the same on screen at every zoom`() = runTest {
        // Audio track: s1 [0..3 s), s2 [3..12 s). s2's end is dragged back towards the playhead at 6 s.
        val vm = viewModel(FakeRepository(project(media("s1", MediaKind.AUDIO, 3_000_000), media("s2", MediaKind.AUDIO, 9_000_000))))
        val s2 = ClipId("clip-s2")
        for (pxPerSecond in listOf(20f, 60f, 300f, 1_200f)) {
            val usPerPx = 1_000_000.0 / pxPerSecond
            val endUs = vm.ready().audio().clips[1].endUs
            fun dragEndTo(pxFromPlayhead: Double): Long? {
                vm.beginDrag(s2, DragHandle.END)
                val target = 6_000_000L - (pxFromPlayhead * usPerPx).toLong()
                vm.updateDrag(target - endUs, pxPerSecond, pxPerDp = 2f, playheadUs = 6_000_000)
                val guide = vm.ready().snapGuideUs
                vm.cancelDrag()
                return guide
            }
            // 12 dp at 2 px/dp is 24 px. 20 px away snaps; 30 px away doesn't.
            assertEquals("zoom $pxPerSecond", 6_000_000L, dragEndTo(20.0))
            assertNull("zoom $pxPerSecond", dragEndTo(30.0))
        }
    }

    // ---------------- Audio ----------------

    private fun music() = media("song", MediaKind.AUDIO, 6_000_000)
    private fun camera(durationUs: Long = 4_000_000) = media("cam", MediaKind.VIDEO, durationUs).copy(audio = AudioTrackInfo("audio/mp4a-latm", 48_000, 2, null))
    private fun EditorUiState.Ready.music() = tracks.first { it.role == AudioRole.MUSIC }
    private fun EditorUiState.Ready.voice() = tracks.first { it.role == AudioRole.VOICEOVER }

    @Test
    fun `clip volume and fades change what the preview plays, and undo restores it`() = runTest {
        val repository = FakeRepository(project(camera(), music()))
        val vm = viewModel(repository)
        vm.controller.attach()
        val song = vm.ready().music().clips.single()

        vm.setClipAudio(song.id, ClipAudio(volume = 0.5f, fadeInUs = 1_000_000))
        assertEquals(ClipAudio(volume = 0.5f, fadeInUs = 1_000_000), vm.ready().music().clips.single().audio)
        val gain = (engines.single().timelines.last().audioTracks.single().first() as AudioSegment.Clip).gain
        assertEquals(0f, gain.gainAt(0), 0f)
        assertEquals(0.5f, gain.envelope.gainAt(2_000_000), 1e-4f)
        assertEquals(ClipAudio(volume = 0.5f, fadeInUs = 1_000_000), repository.saved.value.single().timeline!!.findClip(song.id)!!.audio)

        vm.undo()
        assertEquals(ClipAudio.DEFAULT, vm.ready().music().clips.single().audio)
        // Back to the clip's own unity envelope. (The mix headroom still applies, since camera sound and music overlap.)
        assertTrue((engines.single().timelines.last().audioTracks.single().first() as AudioSegment.Clip).gain.envelope.isUnity)
    }

    @Test
    fun `muting a track takes it out of the preview mix`() = runTest {
        val vm = viewModel(FakeRepository(project(camera(), music())))
        vm.controller.attach()
        assertEquals(1, engines.single().timelines.last().audioTracks.size)
        vm.setTrackAudio(vm.ready().music().id, TrackAudio(muted = true))
        assertTrue(vm.ready().music().audio.muted)
        assertTrue(engines.single().timelines.last().audioTracks.isEmpty())
        // The camera's own sound is still there.
        assertTrue(engines.single().timelines.last().includeClipAudio)
    }

    @Test
    fun `a sound edit keeps the playhead where it was`() = runTest {
        val vm = viewModel(FakeRepository(project(camera(), music())))
        vm.controller.attach()
        vm.controller.seekTo(1_500_000)
        vm.setClipAudio(vm.ready().music().clips.single().id, ClipAudio(volume = 0.3f))
        assertEquals(1_500L, engines.single().timelineStarts.last())
    }

    @Test
    fun `audio moves to the voiceover track and plays from there at the same time`() = runTest {
        val vm = viewModel(FakeRepository(project(camera(6_000_000), music())))
        vm.controller.attach()
        val song = vm.ready().music().clips.single()
        vm.moveClipToTrack(song.id, vm.ready().voice().id)
        assertTrue(vm.ready().music().clips.isEmpty())
        assertEquals(song.startUs, vm.ready().voice().clips.single().startUs)
        assertEquals(1, engines.single().timelines.last().audioTracks.size)
        vm.undo()
        assertEquals(listOf(song.id), vm.ready().music().clips.map { it.id })
    }

    @Test
    fun `clips without sound get no audio controls and audio edits on them are refused`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO), media("img", MediaKind.IMAGE))))
        val clips = vm.ready().main().clips
        assertTrue(clips.none { it.hasAudio })
        vm.setClipAudio(clips[0].id, ClipAudio(volume = 0.5f))
        assertEquals(EditorMessage.CLIP_HAS_NO_AUDIO, vm.ready().message)
        assertFalse(vm.ready().canUndo)
    }

    @Test
    fun `measured waveforms reach the timeline and the preview's mix headroom`() = runTest {
        val peaks = WaveformBuilder().apply { for (i in 0 until 300) addFloat(floatArrayOf(0.9f), 1, 1, 50, i * 20_000L) }.build()
        val requested = mutableListOf<String>()
        val source = object : WaveformSource {
            override fun cached(media: MediaRef): Waveform? = null
            override suspend fun load(media: MediaRef): Waveform? {
                requested += media.id.value
                return peaks
            }
        }
        val vm = viewModel(FakeRepository(project(camera(), music(), media("img", MediaKind.IMAGE))), waveforms = source)
        vm.controller.attach()
        // Only files with sound are analysed, once each.
        assertEquals(setOf("cam", "song"), requested.toSet())
        assertEquals(2, requested.size)
        assertEquals(peaks, vm.ready().music().clips.single().waveform)
        assertEquals(peaks, vm.ready().main().clips.first { it.title == "cam.file" }.waveform)
        // Camera sound and music overlap at 0.9 each: the preview mix is turned down there.
        val plan = engines.single().timelines.last()
        assertFalse(plan.video.first().audio!!.isUnity)
    }

    // ---------------- Text and stickers ----------------

    @Test
    fun `new text goes at the playhead above the video, is selected, and is drawn by the editing canvas`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000))))
        vm.controller.attach()
        vm.addText("Hello", atUs = 1_010_000) // Snapped to frame 30 at 30 fps.

        val state = vm.ready()
        val overlay = state.overlays.single()
        assertEquals(1_000_000L, overlay.startUs)
        assertEquals(1_000_000L + OVERLAY_DURATION_US, overlay.endUs)
        assertEquals(OverlayContent.Text("Hello", (overlay.overlay.content as OverlayContent.Text).style), overlay.overlay.content)
        assertEquals(overlay.clipId, state.selectedOverlay?.clipId)
        assertEquals(TrackKind.OVERLAY, state.tracks.first().kind) // A lane above the main track.
        assertEquals("Hello", state.tracks.first().clips.single().title)
        assertTrue(vm.ready().canUndo)

        // The plan always has it. While selected and paused, the player hides it live (the canvas draws it);
        // while playing, the player draws it, so its animation is frame-exact; once deselected, the player draws it.
        val engine = engines.single()
        assertEquals(listOf(overlay.clipId.value), engine.timelines.last().overlays.map { it.clipId })
        assertEquals(overlay.clipId.value, engine.hidden)
        val loads = engine.timelines.size
        vm.controller.play()
        assertNull(engine.hidden)
        vm.controller.pause()
        assertEquals(overlay.clipId.value, engine.hidden)
        vm.selectClip(null)
        assertNull(engine.hidden)
        assertEquals(loads, engine.timelines.size) // None of this reloads the player.
    }

    @Test
    fun `text added at the end of the video is placed to end there`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000))))
        vm.addText("End", atUs = 6_000_000)
        assertEquals(6_000_000L, vm.ready().overlays.single().endUs)
    }

    @Test
    fun `overlay changes in progress show at once and save as one undo step`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
        val vm = viewModel(repository)
        vm.controller.attach()
        vm.addText("Hi", atUs = 0)
        val added = vm.ready().overlays.single()
        val saves = repository.timelineSaves
        val loads = engines.single().timelines.size

        // A drag: many changes, shown at once, nothing saved and no player reload.
        val steps = (1..5).map { added.overlay.copy(transform = OverlayTransform(centerX = 0.5f + it * 0.05f)) }
        steps.forEach { vm.previewOverlay(added.clipId, it) }
        assertEquals(steps.last(), vm.ready().selectedOverlay!!.overlay)
        assertEquals(saves, repository.timelineSaves)
        assertEquals(loads, engines.single().timelines.size)

        vm.commitOverlay()
        assertEquals(saves + 1, repository.timelineSaves)
        assertEquals(steps.last(), repository.saved.value.single().timeline!!.findClip(added.clipId)!!.overlay)

        vm.undo()
        assertEquals(added.overlay, vm.ready().overlays.single().overlay)
        vm.undo()
        assertTrue(vm.ready().overlays.isEmpty())
        assertTrue(vm.ready().tracks.none { it.kind == TrackKind.OVERLAY }) // The empty lane is gone too.
        vm.redo()
        vm.redo()
        assertEquals(steps.last(), vm.ready().overlays.single().overlay)
    }

    @Test
    fun `selecting something else saves an overlay change in progress`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
        val vm = viewModel(repository)
        vm.addText("Hi", atUs = 0)
        val added = vm.ready().overlays.single()
        val moved = added.overlay.copy(transform = OverlayTransform(centerY = 0.9f))
        vm.previewOverlay(added.clipId, moved)
        vm.selectClip(ClipId("clip-v"))
        assertEquals(moved, repository.saved.value.single().timeline!!.findClip(added.clipId)!!.overlay)
        assertEquals(ClipId("clip-v"), vm.ready().selectedClip?.id)
    }

    @Test
    fun `stickers come from the project's images or the device, and stay off the main track`() = runTest {
        val picked = media("pic", MediaKind.IMAGE)
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
        val vm = viewModel(repository, images = ImageImporter { uri -> picked.takeIf { uri == "content://pic" } })
        vm.importSticker("content://pic", atUs = 0)

        val sticker = vm.ready().overlays.single()
        assertEquals("content://pic", sticker.imageUri)
        assertEquals(1920, sticker.imageWidthPx)
        assertTrue(repository.saved.value.single().media.any { it.id == MediaId("pic") })
        assertEquals(listOf("clip-v"), vm.ready().main().clips.map { it.id.value })
        assertEquals(listOf(StickerChoice(MediaId("pic"), "content://pic", "pic.file")), vm.ready().stickerChoices)

        // Again from the project: the same image, no second copy in the media.
        vm.addSticker(MediaId("pic"), atUs = 0)
        assertEquals(2, vm.ready().overlays.size)
        assertEquals(1, repository.saved.value.single().media.count { it.uri == "content://pic" })

        // An image that can't be used is reported.
        vm.importSticker("content://unreadable", atUs = 0)
        assertEquals(EditorMessage.STICKER_UNAVAILABLE, vm.ready().message)
    }

    @Test
    fun `layer controls, duplicates and their messages`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000))))
        vm.addText("Under", atUs = 0)
        vm.addText("Over", atUs = 0)
        val over = vm.ready().selectedOverlay!!
        assertEquals(listOf("Under", "Over"), vm.ready().overlays.map { (it.overlay.content as OverlayContent.Text).text }) // Bottom first.

        vm.arrangeLayer(over.clipId, LayerMove.FORWARD)
        assertEquals(EditorMessage.LAYER_ALREADY_FRONT, vm.ready().message)
        vm.messageShown()
        vm.arrangeLayer(over.clipId, LayerMove.TO_BACK)
        assertEquals(listOf("Over", "Under"), vm.ready().overlays.map { (it.overlay.content as OverlayContent.Text).text })
        vm.arrangeLayer(over.clipId, LayerMove.BACKWARD)
        assertEquals(EditorMessage.LAYER_ALREADY_BACK, vm.ready().message)

        vm.duplicateOverlay(over.clipId)
        val copy = vm.ready().selectedOverlay!!
        assertEquals(3, vm.ready().overlays.size)
        assertEquals(vm.ready().overlays.last().clipId, copy.clipId) // The copy is on top, and selected.
        assertEquals(over.overlay.transform.centerX + 0.03f, copy.overlay.transform.centerX, 0.0001f)
    }


    // ---------------- Clip transforms ----------------

    @Test
    fun `main-track pictures are listed with their upright size and transform`() = runTest {
        val portrait = media("phone", MediaKind.VIDEO).copy(rotationDegrees = 90)
        val vm = viewModel(FakeRepository(project(portrait, media("img", MediaKind.IMAGE), media("song", MediaKind.AUDIO))))
        val pictures = vm.ready().pictures
        assertEquals(listOf(ClipId("clip-phone"), ClipId("clip-img")), pictures.map { it.clipId })
        assertEquals(1_080 to 1_920, pictures[0].sourceWidthPx to pictures[0].sourceHeightPx)
        assertEquals(1_920 to 1_080, pictures[1].sourceWidthPx to pictures[1].sourceHeightPx)
        assertTrue(pictures.all { it.transform.isDefault })
        assertNull(vm.ready().selectedPicture)
        vm.selectClip(ClipId("clip-img"))
        assertEquals(ClipId("clip-img"), vm.ready().selectedPicture?.clipId)
    }

    @Test
    fun `a transform gesture renders live without a reload and saves as one undo step`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000), media("img", MediaKind.IMAGE)))
        val vm = viewModel(repository)
        vm.controller.attach()
        val engine = engines.single()
        engine.inPlace = true
        val clip = ClipId("clip-v")
        vm.selectClip(clip)
        val saves = repository.timelineSaves
        val loads = engine.timelines.size

        // A pinch and twist: many changes, each rendered by the player in place.
        val steps = (1..5).map { ClipTransform(scale = 1f + it * 0.1f, rotationDegrees = it * 10f) }
        steps.forEach { vm.previewTransform(clip, it) }
        assertEquals(steps.last(), vm.ready().selectedPicture!!.transform)
        assertEquals(steps.last(), engine.inPlaceUpdates.last().video.first().transform)
        assertEquals(ClipTransform.DEFAULT, engine.inPlaceUpdates.last().video[1].transform)
        assertEquals(loads, engine.timelines.size) // No reload.
        assertEquals(saves, repository.timelineSaves) // Nothing saved yet.

        vm.commitTransform()
        assertEquals(saves + 1, repository.timelineSaves)
        assertEquals(steps.last(), repository.saved.value.single().timeline!!.findClip(clip)!!.transform)
        assertEquals(loads, engine.timelines.size) // Saving the same transform doesn't reload either.

        vm.undo()
        assertEquals(ClipTransform.DEFAULT, repository.saved.value.single().timeline!!.findClip(clip)!!.transform)
        assertEquals(ClipTransform.DEFAULT, engine.inPlaceUpdates.last().video.first().transform)
        vm.redo()
        assertEquals(steps.last(), vm.ready().selectedPicture!!.transform)
    }

    @Test
    fun `without in-place support the preview reloads with the new transform`() = runTest {
        val vm = viewModel(FakeRepository(project(media("v", MediaKind.VIDEO))))
        vm.controller.attach()
        val engine = engines.single()
        val moved = ClipTransform(positionX = 0.2f, crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        vm.previewTransform(ClipId("clip-v"), moved)
        assertEquals(moved, engine.timelines.last().video.single().transform)
    }

    @Test
    fun `fit, fill and reset save at once`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO)))
        val vm = viewModel(repository)
        val clip = ClipId("clip-v")
        fun saved() = repository.saved.value.single().timeline!!.findClip(clip)!!.transform

        vm.setTransform(clip, ClipTransform(positionX = 0.1f, scale = 3f))
        vm.fitTransform(clip, FitMode.FILL)
        assertEquals(FitMode.FILL, saved().fit)
        assertEquals(1f, saved().scale, 1e-5f)
        assertEquals(0.5f, saved().positionX)

        // A landscape clip turned on its side in the default portrait canvas: fitted, it now fills the width,
        // 1920/1080 times its upright fitted size.
        vm.setTransform(clip, saved().with(rotationDegrees = 90f, fit = FitMode.FIT))
        vm.fitTransform(clip, FitMode.FIT)
        assertEquals(90f, saved().rotationDegrees)
        assertEquals(1_920f / 1_080f, saved().scale, 1e-3f)

        vm.resetTransform(clip)
        assertTrue(saved().isDefault)
        assertTrue(vm.ready().canUndo)
    }

    @Test
    fun `transforms only apply to pictures, and selecting something else saves one in progress`() = runTest {
        val repository = FakeRepository(project(media("v", MediaKind.VIDEO), media("img", MediaKind.IMAGE), media("song", MediaKind.AUDIO)))
        val vm = viewModel(repository)
        vm.previewTransform(ClipId("clip-song"), ClipTransform(scale = 2f))
        assertTrue(vm.ready().pictures.all { it.transform.isDefault })

        val moved = ClipTransform(positionY = 0.8f)
        vm.selectClip(ClipId("clip-v"))
        vm.previewTransform(ClipId("clip-v"), moved)
        vm.selectClip(ClipId("clip-img"))
        assertEquals(moved, repository.saved.value.single().timeline!!.findClip(ClipId("clip-v"))!!.transform)
    }


    // ---------------- Keyframes ----------------

    @Test
    fun `keyframes are added at the playhead, and edits there become keyframes`() {
        runTest {
            val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
            val vm = viewModel(repository)
            vm.controller.attach()
            val engine = engines.single()
            engine.inPlace = true
            val clip = ClipId("clip-v")
            vm.selectClip(clip)
            fun saved() = repository.saved.value.single().timeline!!.findClip(clip)!!

            // A keyframe at 0 s (snapped from mid-frame) and one at 2 s; nothing moves yet.
            vm.addKeyframe(clip, atUs = 10_000)
            vm.addKeyframe(clip, atUs = 2_000_000)
            assertEquals(listOf(0L, 2_000_000L), saved().animation.transformKeyframeTimes())
            assertEquals(ClipTransform.DEFAULT, vm.ready().selectedPicture!!.at(1_000_000).transform)
            assertEquals(listOf(0L, 2_000_000L), vm.ready().main().clips.first().keyframeTimesUs)

            // A drag at 2 s, as the canvas sends it (the picture as shown there, moved): it keys 2 s, live and as one undo step.
            val shown = vm.ready().selectedPicture!!.at(2_000_000).transform
            val loads = engine.timelines.size
            listOf(0.6f, 0.7f, 0.8f).forEach { x -> vm.previewTransform(clip, shown.with(positionX = x), atUs = 2_000_000) }
            assertEquals(loads, engine.timelines.size) // Rendered in place.
            assertEquals(0.65f, engine.inPlaceUpdates.last().segmentTransforms().first().at(1_000_000).positionX, 1e-6f)
            vm.commitTransform()
            assertEquals(ClipTransform.DEFAULT, saved().transform) // The static placement is untouched...
            assertEquals(0.8, saved().animation[TransformChannel.POSITION_X]!!.at(2_000_000)!!.value, 1e-6) // ...the keyframe holds the move.
            assertEquals(0.65f, vm.ready().selectedPicture!!.at(1_000_000).transform.positionX, 1e-6f)

            // Between keyframes, an edit adds one at the frame under the playhead, for the changed channel only.
            vm.setTransform(clip, vm.ready().selectedPicture!!.at(1_010_000).transform.with(scale = 2f), atUs = 1_010_000)
            assertEquals(listOf(0L, 1_000_000L, 2_000_000L), saved().animation[TransformChannel.SCALE]!!.keyframes.map { it.timeUs })
            assertEquals(listOf(0L, 2_000_000L), saved().animation[TransformChannel.POSITION_X]!!.keyframes.map { it.timeUs })

            vm.undo()
            vm.undo()
            assertEquals(listOf(0L, 2_000_000L), saved().animation.transformKeyframeTimes())
            assertEquals(0.5, saved().animation[TransformChannel.POSITION_X]!!.at(2_000_000)!!.value, 1e-6)
        }
    }

    @Test
    fun `the keyframe at the playhead can be eased, moved and deleted, clearing keeps what is shown`() {
        runTest {
            val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
            val vm = viewModel(repository)
            val clip = ClipId("clip-v")
            fun saved() = repository.saved.value.single().timeline!!.findClip(clip)!!
            vm.addKeyframe(clip, atUs = 0)
            vm.addKeyframe(clip, atUs = 2_000_000)
            vm.setTransform(clip, ClipTransform(scale = 3f), atUs = 2_000_000)

            // Within half a frame of the playhead counts as on the keyframe.
            vm.setKeyframeEasing(clip, atUs = 10_000, Easing.EASE_IN)
            assertEquals(Easing.EASE_IN, saved().animation[TransformChannel.SCALE]!!.at(0)!!.easing)
            assertEquals(1f + 2f * Easing.EASE_IN.ease(0.5).toFloat(), vm.ready().selectedPicture!!.at(1_000_000).transform.scale, 1e-5f)

            // One frame later; the playhead follows.
            assertEquals(2_033_334L, vm.nudgeKeyframe(clip, atUs = 2_000_000, frames = 1))
            assertEquals(listOf(0L, 2_033_334L), saved().animation.transformKeyframeTimes())
            assertNull(vm.nudgeKeyframe(clip, atUs = 1_000_000, frames = 1)) // No keyframe there.

            vm.removeKeyframe(clip, atUs = 2_033_334)
            assertEquals(listOf(0L), saved().animation.transformKeyframeTimes())

            vm.addKeyframe(clip, atUs = 3_000_000)
            vm.setTransform(clip, ClipTransform(positionX = 0.9f), atUs = 3_000_000)
            vm.clearKeyframes(clip, atUs = 1_500_000)
            assertFalse(saved().animation.animatesTransform)
            // What was shown at 1.5 s: halfway from 0.5 to 0.9 in time, on the ease-in curve set at 0 s.
            assertEquals(0.5f + 0.4f * Easing.EASE_IN.ease(0.5).toFloat(), saved().transform.positionX, 1e-5f)

            // Reset also clears keyframes.
            vm.addKeyframe(clip, atUs = 0)
            vm.resetTransform(clip)
            assertTrue(saved().animation.isEmpty)
            assertTrue(saved().transform.isDefault)
        }
    }

    @Test
    fun `overlay keyframes work the same, and the canvas sees the overlay as it is at the playhead`() {
        runTest {
            val repository = FakeRepository(project(media("v", MediaKind.VIDEO, 6_000_000)))
            val vm = viewModel(repository)
            vm.addText("Hi", atUs = 0)
            val text = vm.ready().selectedOverlay!!
            vm.addKeyframe(text.clipId, atUs = 0)
            vm.addKeyframe(text.clipId, atUs = 2_000_000)
            val at2 = vm.ready().selectedOverlay!!.at(2_000_000).overlay
            vm.previewOverlay(text.clipId, at2.copy(transform = at2.transform.copy(centerY = 0.1f)), atUs = 2_000_000)
            vm.commitOverlay()
            val shown = vm.ready().selectedOverlay!!
            assertEquals(0.5f, shown.overlay.transform.centerY, 0f) // Static value unchanged.
            assertEquals(0.3f, shown.at(1_000_000).overlay.transform.centerY, 1e-6f)
            assertEquals(listOf(0L, 2_000_000L), vm.ready().tracks.first().clips.single().keyframeTimesUs)

            // A duplicate keeps the animation, nudged.
            vm.duplicateOverlay(text.clipId)
            assertEquals(0.33f, vm.ready().selectedOverlay!!.at(1_000_000).overlay.transform.centerY, 1e-5f)
        }
    }

    private companion object {
        /** The deepest zoom. The snap distance there is only half a frame, so test drags land where they're aimed. */
        const val MAX_ZOOM = com.editor.veyro.feature.editor.timeline.TimelineScale.MAX_PX_PER_SECOND
    }
}
