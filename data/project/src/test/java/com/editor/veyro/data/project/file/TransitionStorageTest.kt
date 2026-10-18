package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.animation.Easing
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
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionAlignment
import com.editor.veyro.core.model.transition.TransitionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transitions in the project file (schema v9). */
class TransitionStorageTest {

    private val video = MediaRef(
        id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4", mimeType = "video/mp4",
        durationUs = 10_000_000, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 0, sizeBytes = 1,
    )
    private val song = MediaRef(
        id = MediaId("s"), uri = "content://s", kind = MediaKind.AUDIO, displayName = "s.mp3", mimeType = "audio/mpeg",
        durationUs = 60_000_000, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = 1,
    )

    private fun project(first: Transition?, second: Transition?) = Project(
        ProjectId("x"), "Transitions", 1, 2, listOf(video, song),
        Timeline(
            listOf(
                Track(
                    TrackId("main"), TrackKind.VIDEO,
                    listOf(
                        Clip(ClipId("a"), MediaId("v"), 0, 0, 3_000_000, transitionOut = first),
                        Clip(ClipId("b"), MediaId("v"), 3_000_000, 4_000_000, 3_000_000, transitionOut = second),
                        Clip(ClipId("c"), MediaId("v"), 6_000_000, 1_000_000, 2_000_000),
                    ),
                ),
                Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("m"), MediaId("s"), 0, 0, 3_000_000))),
            ),
        ),
    )

    private fun decoded(json: String): Project = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project

    @Test
    fun `every type, length, curve and alignment round-trips exactly`() {
        TransitionType.entries.forEach { type ->
            val project = project(
                Transition(type, 733_367, Easing.CubicBezier(0.1, -0.2, 0.7, 1.3), TransitionAlignment.BEFORE_CUT),
                Transition(type, Transition.MAX_DURATION_US, Easing.EASE_OUT, TransitionAlignment.AFTER_CUT),
            )
            val json = ProjectDocumentCodec.encode(project)
            assertTrue(json.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
            assertTrue(json.contains("\"type\":\"${type.key}\""))
            assertEquals(project, decoded(json))
            assertEquals(json, ProjectDocumentCodec.encode(decoded(json)))
        }
    }

    @Test
    fun `plain cuts store nothing, and v8 documents read as plain cuts`() {
        val plain = project(null, null)
        val json = ProjectDocumentCodec.encode(plain)
        // Like the file's other optional fields, an absent transition is written as null.
        assertFalse(json.contains("\"transition\":{"))
        val v8 = json.replace("\"schemaVersion\":$CURRENT_SCHEMA_VERSION", "\"schemaVersion\":8")
        assertEquals(plain, decoded(v8))
    }

    @Test
    fun `hand-edited transitions are cleaned up instead of failing the project`() {
        val root = ProjectDocumentCodec.encodeToElement(project(Transition(TransitionType.DISSOLVE), Transition(TransitionType.FADE)))
        val timeline = root["timeline"]!!.jsonObject
        val tracks = timeline["tracks"]!!.jsonArray
        fun transition(type: String, durationUs: Long, easing: String, alignment: String) = JsonObject(
            mapOf("type" to JsonPrimitive(type), "durationUs" to JsonPrimitive(durationUs), "easing" to JsonPrimitive(easing), "alignment" to JsonPrimitive(alignment)),
        )
        fun withTransition(clip: JsonElement, value: JsonObject) = JsonObject(clip.jsonObject + ("transition" to value))
        fun withClips(track: JsonElement, clips: List<JsonElement>) = JsonObject(track.jsonObject + ("clips" to JsonArray(clips)))

        val mainClips = tracks[0].jsonObject["clips"]!!.jsonArray
        val musicClips = tracks[1].jsonObject["clips"]!!.jsonArray
        val editedTracks = JsonArray(
            listOf(
                withClips(
                    tracks[0],
                    listOf(
                        // Too long, an unknown curve and alignment: clamped, the type's default curve, centred.
                        withTransition(mainClips[0], transition("spin-cw", 99_000_000, "bounce", "sideways")),
                        // A type this version doesn't know: a plain cut.
                        withTransition(mainClips[1], transition("page-curl", 500_000, "linear", "center")),
                        mainClips[2],
                    ),
                ),
                // A transition on a sound clip is ignored.
                withClips(tracks[1], listOf(withTransition(musicClips[0], transition("fade", 500_000, "linear", "center")))),
            ),
        )
        val edited = JsonObject(root + ("timeline" to JsonObject(timeline + ("tracks" to editedTracks))))

        val clips = decoded(edited.toString()).timeline!!.tracks.flatMap { it.clips }.associateBy { it.id.value }
        assertEquals(
            Transition(TransitionType.SPIN_CLOCKWISE, Transition.MAX_DURATION_US, TransitionType.SPIN_CLOCKWISE.defaultEasing, TransitionAlignment.CENTER),
            clips["a"]!!.transitionOut,
        )
        assertNull(clips["b"]!!.transitionOut)
        assertNull(clips["m"]!!.transitionOut)
    }
}
