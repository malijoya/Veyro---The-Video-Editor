package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.EffectParam
import com.editor.veyro.core.model.animation.Keyframe
import com.editor.veyro.core.model.animation.KeyframeTrack
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clip keyframes in the project file (schema v8). */
class KeyframeStorageTest {

    private val video = MediaRef(
        id = MediaId("v"), uri = "content://v", kind = MediaKind.VIDEO, displayName = "v.mp4", mimeType = "video/mp4",
        durationUs = 10_000_000, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 0, sizeBytes = 1,
    )
    private val song = MediaRef(
        id = MediaId("s"), uri = "content://s", kind = MediaKind.AUDIO, displayName = "s.mp3", mimeType = "audio/mpeg",
        durationUs = 60_000_000, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = 1,
    )

    private val blur = ClipEffect("fx1", "blur", params = mapOf("radius" to 1.0))

    /** Every easing, a negative (trimmed-away) time, multi-turn rotation and an effect parameter. */
    private val animation = ClipAnimation()
        .with(TransformChannel.POSITION_X, Keyframe(-250_000, 0.1, Easing.EASE_IN))
        .with(TransformChannel.POSITION_X, Keyframe(1_000_000, 0.9, Easing.EASE_OUT))
        .with(TransformChannel.POSITION_Y, Keyframe(0, 0.5, Easing.EASE_IN_OUT))
        .with(TransformChannel.SCALE, Keyframe(0, 1.0, Easing.Hold))
        .with(TransformChannel.SCALE, Keyframe(1_500_000, 2.5))
        .with(TransformChannel.ROTATION, Keyframe(0, 0.0, Easing.CubicBezier(0.2, -0.4, 0.6, 1.4)))
        .with(TransformChannel.ROTATION, Keyframe(2_000_000, 1_080.0))
        .with(TransformChannel.OPACITY, Keyframe(33_367, 0.25))
        .with(TransformChannel.CROP_LEFT, Keyframe(0, 0.0, Easing.EASE_OUT))
        .with(TransformChannel.CROP_LEFT, Keyframe(1_000_000, 0.3))
        .with(EffectParam("fx1", "radius"), Keyframe(0, 0.0))
        .with(EffectParam("fx1", "radius"), Keyframe(2_000_000, 12.0))

    private val textAnimation = ClipAnimation().with(TransformChannel.OPACITY, Keyframe(0, 0.0)).with(TransformChannel.OPACITY, Keyframe(500_000, 1.0))

    private fun project(clipAnimation: ClipAnimation = animation) = Project(
        ProjectId("x"), "Keyframes", 1, 2, listOf(video, song),
        Timeline(
            listOf(
                Track(
                    TrackId("overlay-1"), TrackKind.OVERLAY,
                    listOf(Clip(ClipId("t"), null, 0, 0, 1_000_000, overlay = Overlay(OverlayContent.Text("Hi")), animation = textAnimation)),
                ),
                Track(TrackId("main"), TrackKind.VIDEO, listOf(Clip(ClipId("a"), MediaId("v"), 0, 0, 3_000_000, effects = listOf(blur), animation = clipAnimation))),
                Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("m"), MediaId("s"), 0, 0, 3_000_000))),
            ),
        ),
    )

    private fun decoded(json: String): Project = (ProjectDocumentCodec.decode(json) as DecodeResult.Ok).project

    @Test
    fun `every keyframe, easing and property round-trips exactly`() {
        val project = project()
        val json = ProjectDocumentCodec.encode(project)
        assertTrue(json.contains("\"schemaVersion\":$CURRENT_SCHEMA_VERSION"))
        assertTrue(json.contains("\"easing\":\"ease-in-out\""))
        assertTrue(json.contains("\"easing\":\"cubic-bezier(0.2,-0.4,0.6,1.4)\""))
        assertTrue(json.contains("\"property\":\"effect\""))
        assertEquals(project, decoded(json))
        // Saved again, the document is identical: nothing drifts across saves.
        assertEquals(json, ProjectDocumentCodec.encode(decoded(json)))
    }

    @Test
    fun `static clips store no animation, and v7 documents read as static`() {
        val static = project(ClipAnimation.NONE).let { p ->
            p.copy(timeline = p.timeline!!.copy(tracks = p.timeline!!.tracks.map { t -> t.copy(clips = t.clips.map { it.copy(animation = ClipAnimation.NONE) }) }))
        }
        val json = ProjectDocumentCodec.encode(static)
        assertFalse(json.contains("\"animation\":["))
        val v7 = json.replace("\"schemaVersion\":$CURRENT_SCHEMA_VERSION", "\"schemaVersion\":7")
        assertEquals(static, decoded(v7))
    }

    @Test
    fun `hand-edited keyframes are cleaned up instead of failing the project`() {
        val root = ProjectDocumentCodec.encodeToElement(project())
        val tracks = root["timeline"]!!.jsonObject["tracks"]!!.jsonArray
        fun keyframe(time: Long, value: Double, easing: String) =
            JsonObject(mapOf("timeUs" to JsonPrimitive(time), "value" to JsonPrimitive(value), "easing" to JsonPrimitive(easing)))
        fun property(name: String, vararg keyframes: JsonObject, extra: Map<String, JsonPrimitive> = emptyMap()) =
            JsonObject(mapOf("property" to JsonPrimitive(name), "keyframes" to JsonArray(keyframes.toList())) + extra)
        val messy = JsonArray(
            listOf(
                // Out of order, a repeated time (the first is kept), and an easing this version doesn't know (read as linear).
                property("scale", keyframe(2_000_000, 3.0, "linear"), keyframe(0, 1.0, "bounce"), keyframe(0, 9.0, "hold")),
                property("wobble", keyframe(0, 1.0, "linear")), // Unknown property.
                property("effect", keyframe(0, 1.0, "linear"), extra = mapOf("effectId" to JsonPrimitive("gone"), "param" to JsonPrimitive("x"))), // Missing effect.
                property("opacity"), // No keyframes.
            ),
        )
        fun withAnimation(track: Int, value: JsonArray) = tracks[track].jsonObject.let { t ->
            JsonObject(t + ("clips" to JsonArray(t["clips"]!!.jsonArray.map { c -> JsonObject(c.jsonObject + ("animation" to value)) })))
        }
        val edited = JsonObject(
            root + ("timeline" to JsonObject(root["timeline"]!!.jsonObject + ("tracks" to JsonArray(listOf(tracks[0], withAnimation(1, messy), withAnimation(2, messy)))))),
        )
        val project = (ProjectDocumentCodec.decode(edited) as DecodeResult.Ok).project
        val main = project.timeline!!.findClip(ClipId("a"))!!.animation
        assertEquals(setOf(TransformChannel.SCALE), main.tracks.keys)
        assertEquals(KeyframeTrack.of(Keyframe(0, 1.0, Easing.Linear), Keyframe(2_000_000, 3.0)), main[TransformChannel.SCALE])
        // Placement keyframes on a sound clip are dropped.
        assertTrue(project.timeline!!.findClip(ClipId("m"))!!.animation.isEmpty)
    }

    @Test
    fun `crop keyframes are kept on pictures and dropped elsewhere`() {
        val project = project()
        val withTextCrop = project.copy(
            timeline = project.timeline!!.copy(
                tracks = project.timeline!!.tracks.map { track ->
                    track.copy(clips = track.clips.map { if (it.id == ClipId("t")) it.copy(animation = textAnimation.with(TransformChannel.CROP_TOP, Keyframe(0, 0.5))) else it })
                },
            ),
        )
        val decoded = decoded(ProjectDocumentCodec.encode(withTextCrop))
        assertEquals(textAnimation, decoded.timeline!!.findClip(ClipId("t"))!!.animation)
        assertEquals(0.3, decoded.timeline!!.findClip(ClipId("a"))!!.animation[TransformChannel.CROP_LEFT]!!.at(1_000_000)!!.value, 0.0)
    }
}
