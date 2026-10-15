package com.editor.veyro.feature.export

import androidx.lifecycle.SavedStateHandle
import com.editor.veyro.core.model.project.MediaHealth
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.timeline.export.ExportBlocker
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportResolution
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.data.project.ProjectSummary
import com.editor.veyro.media.export.ExportDestination
import com.editor.veyro.media.export.ExportError
import com.editor.veyro.media.export.ExportPipeline
import com.editor.veyro.media.export.FreeSpace
import com.editor.veyro.media.export.OutputVerifier
import com.editor.veyro.media.export.PublishedFile
import com.editor.veyro.media.export.RenderOutcome
import com.editor.veyro.media.export.VerifiedVideo
import com.editor.veyro.media.export.VerifyOutcome
import com.editor.veyro.media.export.VideoRenderer
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(project: Project) : ProjectRepository {
        val saved = MutableStateFlow(listOf(project))
        override val summaries: Flow<List<ProjectSummary>> = saved.map { all -> all.map(ProjectSummary::of) }
        override fun project(id: ProjectId) = saved.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun create(name: String, media: List<MediaRef>, settings: ProjectSettings?) = error("unused")
        override suspend fun addMedia(id: ProjectId, media: List<MediaRef>, arrange: Boolean): Int? = error("unused")
        override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean = error("unused")
        override suspend fun updateSettings(id: ProjectId, settings: ProjectSettings): Boolean = error("unused")
        override suspend fun importProject(project: Project): Project = error("unused")
        override suspend fun rename(id: ProjectId, name: String) = false
        override suspend fun delete(id: ProjectId) = false
        override suspend fun referencedMediaUris() = emptySet<String>()
        override suspend fun storageBytes() = 0L
    }

    private fun media(id: String, kind: MediaKind, durationUs: Long = 2_000_000) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = "$id.mp4", mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else durationUs,
        widthPx = if (kind == MediaKind.AUDIO) 0 else 1_920, heightPx = if (kind == MediaKind.AUDIO) 0 else 1_080,
        rotationDegrees = 0, sizeBytes = null,
    )

    private fun project(name: String = "Trip", vararg media: MediaRef) =
        Project(ProjectId("p"), name, 0, 0, media.toList(), settings = ProjectSettings(1_080, 1_920, FrameRate.FPS_29_97))

    private val renderedPlans = mutableListOf<ExportPlan>()
    private val writingRenderer = VideoRenderer { plan, output, onProgress ->
        renderedPlans += plan
        onProgress(40)
        output.writeBytes(ByteArray(500))
        RenderOutcome.Finished
    }
    private val verifier = OutputVerifier { file, plan ->
        VerifyOutcome.Valid(VerifiedVideo(plan.durationUs, plan.settings.widthPx, plan.settings.heightPx, "video/avc", null, file.length()))
    }
    private var flushes = 0

    private fun TestScope.viewModel(
        project: Project = project("Trip", media("v", MediaKind.VIDEO)),
        renderer: VideoRenderer = writingRenderer,
        health: (MediaRef) -> MediaHealth = { MediaHealth.AVAILABLE },
        saved: SavedStateHandle = SavedStateHandle(),
        galleryAvailable: Boolean = true,
    ): ExportViewModel {
        val pipeline = ExportPipeline(
            renderer = renderer,
            verifier = verifier,
            publisher = { _, _, name -> PublishedFile("content://saved/$name", name) },
            workDir = File(tmp.root, "work"),
            freeSpace = FreeSpace { Long.MAX_VALUE },
        )
        val vm = ExportViewModel(
            savedState = saved,
            projectId = ProjectId("p"),
            repository = FakeRepository(project),
            checkMedia = { health(it) },
            pipeline = pipeline,
            galleryAvailable = galleryAvailable,
            flushPendingEdits = { flushes++ },
            clock = { 1_760_536_800_000 }, // 2025-10-15 14:00:00 UTC
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun ExportViewModel.ready() = uiState.value as ExportUiState.Ready

    @Test
    fun `setup offers the canvas sizes and the project's frame rate first`() = runTest {
        val state = viewModel().ready()
        assertEquals(ExportResolution(1_080, 1_920), state.selectedResolution)
        assertEquals(listOf(ExportResolution(1_080, 1_920), ExportResolution(720, 1_280), ExportResolution(480, 854)), state.resolutions)
        assertEquals(FrameRate.FPS_29_97, state.selectedFrameRate)
        assertFalse(state.checkingMedia)
        assertTrue(state.canStart)
        assertEquals(ExportPhase.Setup(), state.phase)
    }

    @Test
    fun `a successful export ends on the completion screen with the verified file`() = runTest {
        val vm = viewModel()
        vm.selectResolution(ExportResolution(720, 1_280))
        vm.selectFrameRate(FrameRate.FPS_25)
        vm.start(ExportDestination.Gallery)

        val done = vm.ready().phase as ExportPhase.Done
        assertTrue(done.video.displayName, done.video.displayName.matches(Regex("Trip_\\d{8}_\\d{6}\\.mp4")))
        assertEquals("content://saved/${done.video.displayName}", done.video.uri)
        assertEquals(ExportDestination.Gallery, done.video.destination)
        assertEquals(720, done.video.video.widthPx)
        assertEquals(1_280, done.video.video.heightPx)
        assertEquals(500L, done.video.video.sizeBytes)
        // The chosen options reached the renderer.
        assertEquals(720, renderedPlans.single().settings.widthPx)
        assertEquals(FrameRate.FPS_25, renderedPlans.single().settings.frameRate)
        assertEquals(1, flushes)

        vm.backToSetup()
        assertEquals(ExportPhase.Setup(), vm.ready().phase)
    }

    @Test
    fun `progress shows only what the renderer reports`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val renderer = VideoRenderer { _, output, onProgress ->
            onProgress(null)
            gate.await()
            onProgress(63)
            awaitCancellation()
        }
        val vm = viewModel(renderer = renderer)
        vm.start(ExportDestination.Gallery)
        assertEquals(ExportPhase.Running(ExportStage.RENDERING, null), vm.ready().phase)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ExportPhase.Running(ExportStage.RENDERING, 63), vm.ready().phase)
        vm.cancel()
    }

    @Test
    fun `cancel stops the export, deletes the partial file and returns to setup`() = runTest {
        val started = CompletableDeferred<File>()
        val renderer = VideoRenderer { _, output, _ ->
            output.writeBytes(ByteArray(100))
            started.complete(output)
            awaitCancellation()
        }
        val vm = viewModel(renderer = renderer)
        vm.start(ExportDestination.Document("content://docs/out"))
        val partial = started.await()
        assertTrue(vm.ready().phase is ExportPhase.Running)

        vm.cancel()
        advanceUntilIdle()
        assertEquals(ExportPhase.Setup(cancelled = true), vm.ready().phase)
        assertFalse(partial.exists())
    }

    @Test
    fun `a renderer failure is shown and nothing completes`() = runTest {
        val renderer = VideoRenderer { _, _, _ -> RenderOutcome.Failed(ExportError.DECODER_UNSUPPORTED, "ERROR_CODE_DECODING_FAILED") }
        val vm = viewModel(renderer = renderer)
        vm.start(ExportDestination.Gallery)
        assertEquals(ExportPhase.Failed(ExportError.DECODER_UNSUPPORTED, "ERROR_CODE_DECODING_FAILED"), vm.ready().phase)
    }

    @Test
    fun `missing source files block the export before it starts`() = runTest {
        val vm = viewModel(health = { if (it.id == MediaId("v")) MediaHealth.MISSING else MediaHealth.AVAILABLE })
        val state = vm.ready()
        assertEquals(setOf(ExportBlocker.MEDIA_UNAVAILABLE), state.blockers)
        assertEquals(listOf("v.mp4"), state.problemFiles)
        assertFalse(state.canStart)
        vm.start(ExportDestination.Gallery)
        assertEquals(ExportPhase.Setup(), vm.ready().phase)
        assertTrue(renderedPlans.isEmpty())
    }

    @Test
    fun `a file that goes missing after opening the screen stops the export`() = runTest {
        var missing = false
        val vm = viewModel(health = { if (missing) MediaHealth.MISSING else MediaHealth.AVAILABLE })
        assertTrue(vm.ready().canStart)
        missing = true
        vm.start(ExportDestination.Gallery)
        assertTrue(renderedPlans.isEmpty())
        assertEquals(ExportPhase.Setup(), vm.ready().phase)
        assertEquals(setOf(ExportBlocker.MEDIA_UNAVAILABLE), vm.ready().blockers)
    }

    @Test
    fun `a project with only audio can't be exported`() = runTest {
        val state = viewModel(project = project("Song", media("s", MediaKind.AUDIO))).ready()
        assertEquals(setOf(ExportBlocker.NO_VIDEO), state.blockers)
        assertFalse(state.canStart)
    }

    @Test
    fun `chosen options survive process death`() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved = saved).selectResolution(ExportResolution(480, 854))
        assertEquals(ExportResolution(480, 854), viewModel(saved = saved).ready().selectedResolution)
    }

    @Test
    fun `the suggested file name is safe and timestamped`() = runTest {
        val vm = viewModel(project = project("Beach / Day: 1?", media("v", MediaKind.VIDEO)))
        val name = vm.suggestedFileName()
        assertTrue(name, name.matches(Regex("Beach _ Day_ 1__\\d{8}_\\d{6}\\.mp4")))
    }

    @Test
    fun `audio is measured before rendering, so the mix gets real headroom`() = runTest {
        val loaded = mutableListOf<String>()
        val source = object : com.editor.veyro.core.model.audio.WaveformSource {
            override fun cached(media: MediaRef): com.editor.veyro.core.model.audio.Waveform? = null
            override suspend fun load(media: MediaRef): com.editor.veyro.core.model.audio.Waveform? {
                loaded += media.id.value
                return com.editor.veyro.core.model.audio.WaveformBuilder().apply {
                    for (i in 0 until 200) addFloat(floatArrayOf(0.2f), 1, 1, 50, i * 20_000L)
                }.build()
            }
        }
        var loadedBeforeRender: List<String>? = null
        val renderer = VideoRenderer { plan, output, _ ->
            loadedBeforeRender = loaded.toList()
            renderedPlans += plan
            output.writeBytes(ByteArray(10))
            RenderOutcome.Finished
        }
        val pipeline = ExportPipeline(renderer, verifier, { _, _, name -> PublishedFile("content://saved/$name", name) }, File(tmp.root, "work"), FreeSpace { Long.MAX_VALUE })
        val vm = ExportViewModel(
            savedState = SavedStateHandle(),
            projectId = ProjectId("p"),
            repository = FakeRepository(project("Trip", media("v", MediaKind.VIDEO), media("song", MediaKind.AUDIO, 3_000_000))),
            checkMedia = { MediaHealth.AVAILABLE },
            pipeline = pipeline,
            galleryAvailable = true,
            waveforms = source,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.start(ExportDestination.Gallery)

        // Only files with sound are measured (the video here has none), and before rendering starts.
        assertEquals(listOf("song"), loadedBeforeRender)
        assertTrue(vm.ready().phase is ExportPhase.Done)
        // The music clip is in the rendered plan, on its own track.
        assertEquals(1, renderedPlans.single().audioTracks.size)
    }

    @Test
    fun `older devices only offer the file picker`() = runTest {
        assertFalse(viewModel(galleryAvailable = false).ready().galleryAvailable)
    }
}
