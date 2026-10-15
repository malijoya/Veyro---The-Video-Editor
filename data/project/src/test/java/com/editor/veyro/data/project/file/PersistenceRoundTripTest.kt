package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.VideoTrackInfo
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.timeline.EditCommand
import com.editor.veyro.core.timeline.EditContext
import com.editor.veyro.core.timeline.EditResult
import com.editor.veyro.core.timeline.MediaBounds
import com.editor.veyro.core.timeline.TimelineEditor
import com.editor.veyro.data.project.InMemoryProjectIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Closes and reopens a project the way the app does after process death (a
 * new repository and a new, empty index on the same folder), then checks that
 * nothing changed. That covers edits, settings, effects and every clip's
 * timing, down to the microsecond.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ntsc = FrameRate.FPS_29_97

    private val video = MediaRef(
        id = MediaId("v"), uri = "content://media/external/video/1", kind = MediaKind.VIDEO, displayName = "beach.mp4",
        mimeType = "video/mp4", durationUs = 10_000_000, widthPx = 1920, heightPx = 1080, rotationDegrees = 90,
        sizeBytes = 25_000_000, video = VideoTrackInfo("video/hevc", ntsc, 20_000_000),
    )
    private val photo = MediaRef(
        id = MediaId("img"), uri = "content://media/external/images/2", kind = MediaKind.IMAGE, displayName = "sunset.jpg",
        mimeType = "image/jpeg", durationUs = 0, widthPx = 4000, heightPx = 3000, rotationDegrees = 0, sizeBytes = 3_000_000,
    )
    private val song = MediaRef(
        id = MediaId("song"), uri = "content://com.android.providers.downloads.documents/document/9", kind = MediaKind.AUDIO,
        displayName = "song.mp3", mimeType = "audio/mpeg", durationUs = 30_000_000, widthPx = 0, heightPx = 0,
        rotationDegrees = 0, sizeBytes = 7_000_000, accessPersisted = false,
    )

    private fun TestScope.repository(dir: java.io.File, id: String = "p1") = FileProjectRepository(
        rootDir = dir,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        clock = { 1_000 },
        newId = { id },
        index = InMemoryProjectIndex(),
    )

    private fun EditResult.timeline(): Timeline = (this as EditResult.Applied).timeline

    @Test
    fun `a project reopens with identical edits, settings, effects and clip timing`() = runTest {
        val dir = tmp.newFolder("projects")
        val first = repository(dir)
        val created = first.create("Trip", listOf(video, photo, song))
        // Settings match the first video: upright 1080x1920 at 29.97 fps.
        assertEquals(1_080, created.settings.canvasWidthPx)
        assertEquals(1_920, created.settings.canvasHeightPx)
        assertEquals(ntsc, created.settings.frameRate)

        // Edit with the real engine: split, trim, move, and add an effect.
        val context = EditContext(created.media.associate { it.id to MediaBounds(it.kind, it.durationUs) }) { ClipId("cut-1") }
        var timeline = DefaultArrangement.timelineFor(created)
        timeline = TimelineEditor.apply(timeline, EditCommand.Split(ClipId("clip-v"), ntsc.frameToUs(150)), context).timeline()
        timeline = TimelineEditor.apply(timeline, EditCommand.TrimEnd(ClipId("cut-1"), ntsc.frameToUs(240)), context).timeline()
        timeline = TimelineEditor.apply(timeline, EditCommand.Move(ClipId("clip-song"), ntsc.frameToUs(75)), context).timeline()
        val effect = ClipEffect("fx-1", "color.saturation", params = mapOf("amount" to 1.25))
        timeline = timeline.withTrack(
            timeline.mainTrack!!.let { track -> track.copy(clips = track.clips.map { if (it.id.value == "clip-img") it.copy(effects = listOf(effect)) else it }) },
        )

        first.updateSettings(created.id, created.settings.copy(canvasWidthPx = 1_080, canvasHeightPx = 1_080, snapToEdges = false, snapDistanceDp = 20, photoDurationUs = 2_000_000))
        first.updateTimeline(created.id, timeline)
        val beforeClose = first.project(created.id).first()!!

        // "Reopen": a fresh repository and index, as after the process is killed.
        val reopenedRepository = repository(dir)
        val reopened = reopenedRepository.project(created.id).first()!!

        assertEquals(beforeClose, reopened)
        assertEquals(timeline, reopened.timeline)
        assertEquals(1_080, reopened.settings.canvasHeightPx)
        assertEquals(false, reopened.settings.snapToEdges)
        assertEquals(20, reopened.settings.snapDistanceDp)
        assertEquals(false, reopened.media.single { it.id == song.id }.accessPersisted)
        assertEquals(listOf(effect), reopened.timeline!!.findClip(ClipId("clip-img"))!!.effects)

        // Clip timing exactly as edited, on NTSC frame boundaries.
        val main = reopened.timeline!!.mainTrack!!.clips
        assertEquals(listOf(0L, 5_005_000L, 8_008_000L), main.map { it.startUs })
        assertEquals(5_005_000L, main[1].sourceInUs) // The split's right half continues the source.
        assertEquals(3_003_000L, main[1].durationUs)
        assertEquals(2_502_500L, reopened.timeline!!.tracks[1].clips.single().startUs)

        // The index rebuilt itself from the document.
        val summary = reopenedRepository.summaries.first().single()
        assertEquals("Trip", summary.name)
        assertEquals(reopened.timeline!!.durationUs, summary.timelineDurationUs)
        assertEquals(setOf(video.uri, photo.uri, song.uri), summary.mediaUris)
    }

    @Test
    fun `an exported project file imports as an identical project under a new id`() = runTest {
        val dir = tmp.newFolder("projects")
        val repo = repository(dir)
        val created = repo.create("Trip", listOf(video, song))
        val split = TimelineEditor.apply(
            DefaultArrangement.timelineFor(created),
            EditCommand.Split(ClipId("clip-v"), ntsc.frameToUs(90)),
            EditContext(created.media.associate { it.id to MediaBounds(it.kind, it.durationUs) }) { ClipId("cut") },
        ).timeline()
        repo.updateTimeline(created.id, split)
        val original = repo.project(created.id).first()!!

        val file = ProjectInterchange.export(original, exportedAtEpochMs = 5)
        val parsed = (ProjectInterchange.import(file) as ProjectInterchange.ImportResult.Ok).project
        assertEquals(original, parsed)

        val importedRepo = repository(tmp.newFolder("other-device"), id = "p2")
        val imported = importedRepo.importProject(parsed)
        assertNotEquals(original.id, imported.id)
        assertEquals(original.copy(id = imported.id, updatedAtEpochMs = imported.updatedAtEpochMs), imported)
        assertEquals(imported, repository(tmp.root.resolve("other-device"), id = "x").project(imported.id).first())
    }

    @Test
    fun `changing the frame rate keeps every clip time`() = runTest {
        val repo = repository(tmp.newFolder("projects"))
        val created = repo.create("Trip", listOf(video))
        val split = TimelineEditor.apply(
            DefaultArrangement.timelineFor(created),
            EditCommand.Split(ClipId("clip-v"), ntsc.frameToUs(100)),
            EditContext(created.media.associate { it.id to MediaBounds(it.kind, it.durationUs) }) { ClipId("cut") },
        ).timeline()
        repo.updateTimeline(created.id, split)

        repo.updateSettings(created.id, created.settings.copy(frameRate = FrameRate.FPS_25))
        val updated = repo.project(created.id).first()!!
        assertEquals(FrameRate.FPS_25, updated.timeline!!.frameRate)
        assertEquals(split.tracks.map { t -> t.clips.map { Triple(it.startUs, it.sourceInUs, it.durationUs) } },
            updated.timeline!!.tracks.map { t -> t.clips.map { Triple(it.startUs, it.sourceInUs, it.durationUs) } })
        assertTrue(repo.storageBytes() > 0)
    }
}
