package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.AspectRatio
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clip transforms in the project file (schema v7). */
class ClipTransformStorageTest {

    private val video = MediaRef(
        id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4", mimeType = "video/mp4",
        durationUs = 10_000_000, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 90, sizeBytes = 1,
    )
    private val photo = MediaRef(
        id = MediaId("p"), uri = "content://p", kind = MediaKind.IMAGE, displayName = "p.jpg", mimeType = "image/jpeg",
        durationUs = 0, widthPx = 4_000, heightPx = 3_000, rotationDegrees = 0, sizeBytes = 1,
    )

    private val moved = ClipTransform(
        positionX = 0.25f,
        positionY = 1.3f,
        scale = 1.75f,
        rotationDegrees = -30f,
        anchorX = 0.1f,
        anchorY = 0.9f,
        crop = CropRect(0.1f, 0.2f, 0.7f, 0.95f),
        cropAspect = AspectRatio.PORTRAIT_4_5,
        fit = FitMode.FILL,
        opacity = 0.4f,
    )

    private fun project(transform: ClipTransform) = Project(
        ProjectId("x"), "Transforms", 1, 2, listOf(video, photo),
        Timeline(
            listOf(
                Track(
                    TrackId("main"), TrackKind.VIDEO,
                    listOf(
                        Clip(ClipId("a"), MediaId("v"), 0, 0, 2_000_000, transform = transform),
                        Clip(ClipId("b"), MediaId("p"), 2_000_000, 0, 3_000_000),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `every transform property round-trips`() {
        val project = project(moved)
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        assertTrue(json.contains("\"cropAspect\":\"4:5\""))
        assertTrue(json.contains("\"fit\":\"fill\""))
        assertEquals(DecodeResult.Ok(project), ProjectDocumentCodec.decode(json))
    }

    @Test
    fun `untouched clips store no transform`() {
        val json = ProjectDocumentCodec.encode(project(ClipTransform.DEFAULT))
        assertFalse(json.contains("\"transform\":{"))
        val decoded = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project
        assertTrue(decoded.timeline!!.tracks.single().clips.all { it.transform.isDefault })
    }

    @Test
    fun `v6 documents read with default transforms`() {
        val v6 = ProjectDocumentCodec.encode(project(ClipTransform.DEFAULT)).replace("\"schemaVersion\":$CURRENT_SCHEMA_VERSION", "\"schemaVersion\":6")
        val decoded = (ProjectDocumentCodec.decode(v6) as DecodeResult.Ok).project
        assertEquals(project(ClipTransform.DEFAULT), decoded)
    }

    @Test
    fun `hand-edited transform values are brought into range instead of failing the project`() {
        val json = ProjectDocumentCodec.encode(project(moved))
            .replace("\"scale\":1.75", "\"scale\":500.0")
            .replace("\"rotationDegrees\":-30.0", "\"rotationDegrees\":690.0")
            .replace("\"cropLeft\":0.1", "\"cropLeft\":0.9")
            .replace("\"cropRight\":0.7", "\"cropRight\":0.2")
            .replace("\"cropAspect\":\"4:5\"", "\"cropAspect\":\"wide\"")
            .replace("\"opacity\":0.4", "\"opacity\":-2.0")
            .replace("\"anchorX\":0.1", "\"anchorX\":7.0")
        val transform = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project.timeline!!.findClip(ClipId("a"))!!.transform
        assertEquals(ClipTransform.MAX_SCALE, transform.scale)
        assertEquals(-30f, transform.rotationDegrees, 1e-3f) // 690 = 2 turns - 30.
        assertEquals(0.2f, transform.crop.left) // Edges put in order.
        assertEquals(0.9f, transform.crop.right)
        assertNull(transform.cropAspect)
        assertEquals(0f, transform.opacity)
        assertEquals(1f, transform.anchorX)
    }

    @Test
    fun `a transform on an audio track in a hand-edited file is ignored`() {
        val audio = MediaRef(
            id = MediaId("m"), uri = "content://m", kind = MediaKind.AUDIO, displayName = "m.mp3", mimeType = "audio/mpeg",
            durationUs = 5_000_000, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = 1,
        )
        val base = Project(
            ProjectId("x"), "Audio", 1, 2, listOf(video, audio),
            Timeline(
                listOf(
                    Track(TrackId("main"), TrackKind.VIDEO, listOf(Clip(ClipId("a"), MediaId("v"), 0, 0, 2_000_000, transform = moved))),
                    Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("m"), MediaId("m"), 0, 0, 2_000_000))),
                ),
            ),
        )
        // Copy the video clip's transform onto the audio clip, as a hand edit might.
        val json = ProjectDocumentCodec.encode(base)
        val transformJson = Regex("\"transform\":\\{[^}]*\\}").find(json)!!.value
        // Only the audio clip has no transform written.
        assertEquals(1, Regex("\"transform\":null").findAll(json).count())
        val edited = json.replace("\"transform\":null", transformJson)
        assertTrue(edited != json)
        val decoded = (ProjectDocumentCodec.decode(edited) as DecodeResult.Ok).project
        assertTrue(decoded.timeline!!.findClip(ClipId("m"))!!.transform.isDefault)
        assertEquals(moved, decoded.timeline!!.findClip(ClipId("a"))!!.transform)
    }
}
