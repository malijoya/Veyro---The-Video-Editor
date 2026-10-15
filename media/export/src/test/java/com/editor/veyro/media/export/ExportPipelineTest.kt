package com.editor.veyro.media.export

import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.ExportSettings
import com.editor.veyro.core.timeline.export.SegmentGain
import com.editor.veyro.core.timeline.export.VideoSegment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExportPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val plan = ExportPlan(
        settings = ExportSettings(1_280, 720, FrameRate.FPS_30),
        video = listOf(
            VideoSegment(
                "content://v", MediaKind.VIDEO, 0, 2_000_000, sourceDurationUs = 2_000_000,
                audio = SegmentGain(GainEnvelope(2_000_000, 0, ClipAudio.DEFAULT), timelineStartUs = 0),
            ),
        ),
        audioTracks = emptyList(),
    )
    private val verified = VerifiedVideo(2_000_000, 1_280, 720, "video/avc", "audio/mp4a-latm", 1_234)

    /** Writes [bytes] as "the video", reporting [progress] on the way. */
    private fun writingRenderer(bytes: ByteArray = ByteArray(1_234) { 7 }, progress: List<Int?> = listOf(null, 50, 100)) =
        VideoRenderer { _, output, onProgress ->
            progress.forEach(onProgress)
            output.writeBytes(bytes)
            RenderOutcome.Finished
        }

    private val published = mutableListOf<Pair<ByteArray, ExportDestination>>()
    private val copyingPublisher = ExportPublisher { file, destination, name ->
        published += file.readBytes() to destination
        PublishedFile("content://saved/$name", name)
    }
    private var verifyCalls = 0
    private val validVerifier = OutputVerifier { file, _ ->
        verifyCalls++
        assertTrue("Verifier must see the rendered file", file.isFile)
        VerifyOutcome.Valid(verified.copy(sizeBytes = file.length()))
    }

    private fun pipeline(
        renderer: VideoRenderer = writingRenderer(),
        verifier: OutputVerifier = validVerifier,
        publisher: ExportPublisher = copyingPublisher,
        free: Long = Long.MAX_VALUE,
    ) = ExportPipeline(renderer, verifier, publisher, File(tmp.root, "work"), FreeSpace { free })

    private fun workFiles() = File(tmp.root, "work").listFiles().orEmpty().toList()

    @Test
    fun `a verified and saved file completes, with progress exactly as reported`() = runTest {
        val statuses = pipeline().run(plan, ExportDestination.Gallery, "Trip.mp4").toList()
        assertEquals(
            listOf(
                ExportStatus.Preparing,
                ExportStatus.Rendering(null),
                ExportStatus.Rendering(50),
                ExportStatus.Rendering(100),
                ExportStatus.Verifying,
                ExportStatus.Saving,
                ExportStatus.Completed(ExportedVideo("content://saved/Trip.mp4", "Trip.mp4", ExportDestination.Gallery, verified)),
            ),
            statuses,
        )
        assertEquals(1_234, published.single().first.size)
        assertTrue("Temporary file is deleted", workFiles().isEmpty())
    }

    @Test
    fun `progress is clamped and repeated values are dropped`() = runTest {
        val statuses = pipeline(renderer = writingRenderer(progress = listOf(10, 10, 150, 150))).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(
            listOf(ExportStatus.Rendering(null), ExportStatus.Rendering(10), ExportStatus.Rendering(100)),
            statuses.filterIsInstance<ExportStatus.Rendering>(),
        )
    }

    @Test
    fun `a renderer error fails without verifying or saving`() = runTest {
        val failing = VideoRenderer { _, output, _ ->
            output.writeBytes(ByteArray(10))
            RenderOutcome.Failed(ExportError.ENCODER_UNSUPPORTED, "ERROR_CODE_ENCODER_INIT_FAILED")
        }
        val statuses = pipeline(renderer = failing).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(ExportStatus.Failed(ExportError.ENCODER_UNSUPPORTED, "ERROR_CODE_ENCODER_INIT_FAILED"), statuses.last())
        assertEquals(0, verifyCalls)
        assertTrue(published.isEmpty())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `reported success with no file is never a success`() = runTest {
        val liar = VideoRenderer { _, _, _ -> RenderOutcome.Finished }
        val statuses = pipeline(renderer = liar).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(ExportError.INVALID_OUTPUT, (statuses.last() as ExportStatus.Failed).error)
        assertFalse(statuses.any { it is ExportStatus.Completed })
        assertEquals(0, verifyCalls)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `an empty file is never a success`() = runTest {
        val statuses = pipeline(renderer = writingRenderer(bytes = ByteArray(0))).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(ExportError.INVALID_OUTPUT, (statuses.last() as ExportStatus.Failed).error)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a file that fails verification is not saved`() = runTest {
        val rejecting = OutputVerifier { _, _ -> VerifyOutcome.Invalid("No video track") }
        val statuses = pipeline(verifier = rejecting).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(ExportStatus.Failed(ExportError.INVALID_OUTPUT, "No video track"), statuses.last())
        assertFalse(statuses.contains(ExportStatus.Saving))
        assertTrue(published.isEmpty())
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `a failed copy is reported, with full storage told apart`() = runTest {
        val broken = ExportPublisher { _, _, _ -> throw IOException("Permission denied") }
        val full = ExportPublisher { _, _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") }
        val document = ExportDestination.Document("content://docs/1")
        assertEquals(ExportError.SAVE_FAILED, (pipeline(publisher = broken).run(plan, document, "a.mp4").toList().last() as ExportStatus.Failed).error)
        assertEquals(ExportError.STORAGE_FULL, (pipeline(publisher = full).run(plan, document, "a.mp4").toList().last() as ExportStatus.Failed).error)
        assertTrue(workFiles().isEmpty())
    }

    @Test
    fun `too little free space fails before rendering`() = runTest {
        var rendered = false
        val renderer = VideoRenderer { _, _, _ ->
            rendered = true
            RenderOutcome.Finished
        }
        val statuses = pipeline(renderer = renderer, free = 1_000).run(plan, ExportDestination.Gallery, "a.mp4").toList()
        assertEquals(ExportError.STORAGE_FULL, (statuses.last() as ExportStatus.Failed).error)
        assertFalse(rendered)
    }

    @Test
    fun `cancelling mid-render stops the renderer and deletes the partial file`() = runTest {
        val started = CompletableDeferred<File>()
        var rendererCancelled = false
        val slow = VideoRenderer { _, output, onProgress ->
            output.writeBytes(ByteArray(100))
            onProgress(12)
            started.complete(output)
            try {
                awaitCancellation()
            } finally {
                rendererCancelled = true
            }
        }
        val statuses = mutableListOf<ExportStatus>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(renderer = slow).run(plan, ExportDestination.Gallery, "a.mp4").collect { statuses += it }
        }
        val partial = started.await()
        assertTrue(partial.exists())
        job.cancel()
        job.join()

        assertTrue(rendererCancelled)
        assertFalse(partial.exists())
        assertTrue(workFiles().isEmpty())
        assertEquals(ExportStatus.Rendering(12), statuses.last())
        assertFalse(statuses.any { it is ExportStatus.Completed || it is ExportStatus.Verifying })
        assertTrue(published.isEmpty())
    }

    @Test
    fun `the size estimate grows with resolution, frame rate and length`() {
        val small = ExportPipeline.estimatedBytes(plan)
        val big = ExportPipeline.estimatedBytes(plan.copy(settings = ExportSettings(3_840, 2_160, FrameRate.FPS_60)))
        assertTrue(small > 0)
        assertTrue(big > small * 8)
    }
}
