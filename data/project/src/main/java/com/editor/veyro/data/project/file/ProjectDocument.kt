package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.GainInterpolation
import com.editor.veyro.core.model.audio.GainKeyframe
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayFont
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.animation.AnimatedProperty
import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.animation.Easing
import com.editor.veyro.core.model.animation.EffectParam
import com.editor.veyro.core.model.animation.Keyframe
import com.editor.veyro.core.model.animation.KeyframeTrack
import com.editor.veyro.core.model.animation.TransformChannel
import com.editor.veyro.core.model.overlay.TextAlign
import com.editor.veyro.core.model.overlay.TextBackground
import com.editor.veyro.core.model.overlay.TextOutline
import com.editor.veyro.core.model.overlay.TextShadow
import com.editor.veyro.core.model.overlay.TextStyle
import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.MediaTags
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.project.VideoTrackInfo
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transform.AspectRatio
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transform.CropRect
import com.editor.veyro.core.model.transform.FitMode
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionAlignment
import com.editor.veyro.core.model.transition.TransitionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * On-disk JSON format of a project. It is also the payload of exported
 * project files (see ProjectInterchange).
 *
 * These classes are the storage contract and are kept apart from the domain
 * model so the model can evolve. Never change a released version's shape.
 * Add a new version, decode the old one, and map it forward in
 * [ProjectDocumentCodec.decode]. Documents are always written in
 * [CURRENT_SCHEMA_VERSION].
 *
 * All times are integer microseconds, and frame rates are exact fractions.
 * Media is referenced by platform URI plus a fingerprint (name, size,
 * duration), never by a cache path.
 *
 * History:
 * - v1: project name and timestamps, plus video and image media with basic
 *   metadata.
 * - v2: audio media, video track info (codec, frame rate, bitrate), audio
 *   track info, and tags.
 * - v3: the edited timeline (tracks, clips, frame rate).
 * - v4 (2026-10-15): project settings (canvas size, frame rate, snapping,
 *   photo duration), clip effects, and a per-media flag for persisted
 *   access. The frame rate moved from the timeline into settings.
 * - v5 (2026-10-15): clip audio (volume, mute, fades, gain keyframes), track
 *   audio (volume, mute) and audio track roles (music, voiceover). These are
 *   optional fields added to the v4 shape, so v4 documents decode with the
 *   same classes and get defaults. The version still went up, so older app
 *   builds refuse v5 files instead of silently dropping audio settings.
 * - v6 (2026-10-15): overlay tracks (kind "overlay") whose clips carry text
 *   or a sticker: content, style and placement. Text clips have no media,
 *   so a clip's mediaId became optional. Again only optional fields on the
 *   v4 shape; older builds refuse v6 files rather than lose overlays.
 * - v7 (2026-10-15): clip transforms on video-track clips (position, scale,
 *   rotation, anchor, crop, crop shape lock, fit or fill, opacity), as
 *   fractions of the canvas and of the upright source. An optional field on
 *   the v4 shape; absent means the default (whole picture, fitted, centred).
 * - v8 (2026-10-15): clip keyframes ("animation"): per property (placement
 *   channel or effect parameter), keyframes with a clip-local time in µs
 *   (since the clip's first frame), a value and the easing to the next
 *   keyframe. An optional field on the v4 shape; absent means static.
 * - v9 (2026-10-18): transitions ("transition") on main-track clips: the
 *   transition into the next clip, with a type key, requested length in
 *   µs, easing and alignment. An optional field on the v4 shape; absent
 *   means a plain cut.
 */

internal const val CURRENT_SCHEMA_VERSION = 9

// ======================= v4 / v5 / v6 / v7 / v8 / v9 (current) =======================

@Serializable
internal data class ProjectDocumentV4(
    val schemaVersion: Int = 4,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val settings: SettingsDocumentV4,
    val media: List<MediaDocumentV4>,
    val timeline: TimelineDocumentV4? = null,
)

@Serializable
internal data class SettingsDocumentV4(
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val frameRateNum: Long,
    val frameRateDen: Long,
    val snapToEdges: Boolean = true,
    val photoDurationUs: Long = ProjectSettings.DEFAULT_PHOTO_DURATION_US,
    /** Added after v4 shipped. Optional, so older v4 files read with the default. */
    val snapDistanceDp: Int = ProjectSettings.DEFAULT_SNAP_DISTANCE_DP,
)

@Serializable
internal data class MediaDocumentV4(
    val id: String,
    val uri: String,
    val kind: MediaKindV2,
    val displayName: String? = null,
    val mimeType: String? = null,
    val durationUs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val sizeBytes: Long? = null,
    val video: VideoTrackDocumentV2? = null,
    val audio: AudioTrackDocumentV2? = null,
    val tags: TagsDocumentV2? = null,
    val accessPersisted: Boolean = true,
)

@Serializable
internal data class TimelineDocumentV4(val tracks: List<TrackDocumentV4>)

@Serializable
internal data class TrackDocumentV4(
    val id: String,
    val kind: TrackKindV3,
    val clips: List<ClipDocumentV4>,
    /** v5. */
    val audio: TrackAudioDocumentV5? = null,
    /** v5. */
    val role: AudioRoleV5? = null,
)

@Serializable
internal data class TrackAudioDocumentV5(val volume: Float = 1f, val muted: Boolean = false)

@Serializable
internal enum class AudioRoleV5 {
    @SerialName("music") MUSIC,
    @SerialName("voiceover") VOICEOVER,
}

@Serializable
internal data class ClipAudioDocumentV5(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val keyframes: List<GainKeyframeDocumentV5> = emptyList(),
)

@Serializable
internal data class GainKeyframeDocumentV5(
    val sourceTimeUs: Long,
    val gain: Float,
    val interpolation: GainInterpolationV5 = GainInterpolationV5.LINEAR,
)

@Serializable
internal enum class GainInterpolationV5 {
    @SerialName("linear") LINEAR,
    @SerialName("hold") HOLD,
}

@Serializable
internal data class ClipDocumentV4(
    val id: String,
    /** Required before v6. Absent only for text overlays. */
    val mediaId: String? = null,
    val startUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
    val effects: List<EffectDocumentV4> = emptyList(),
    /** v5. Absent means default sound (100%, no fades). */
    val audio: ClipAudioDocumentV5? = null,
    /** v6. Present exactly on overlay tracks. */
    val overlay: OverlayDocumentV6? = null,
    /** v7. Video tracks only. Absent means the default transform. */
    val transform: TransformDocumentV7? = null,
    /** v8. Absent or empty means no keyframes. */
    val animation: List<PropertyAnimationDocumentV8>? = null,
    /** v9. Video tracks only. Absent means a plain cut into the next clip. */
    val transition: TransitionDocumentV9? = null,
)

/**
 * v9. The transition from a clip into the next one. [type] is a
 * `TransitionType` key ("fade", "dissolve", "wipe-left", "slide-up",
 * "zoom-in", "spin-cw", ...); [durationUs] the requested length (the
 * player fits it to the clips); [easing] as for keyframes; [alignment]
 * "center", "before-cut" or "after-cut".
 */
@Serializable
internal data class TransitionDocumentV9(
    val type: String,
    val durationUs: Long,
    val easing: String = "linear",
    val alignment: String = "center",
)

/**
 * v8. One animated property: [property] is a placement channel
 * ("positionX", "positionY", "scale", "rotation", "opacity") or "effect",
 * with [effectId] and [param] naming the effect parameter.
 */
@Serializable
internal data class PropertyAnimationDocumentV8(
    val property: String,
    val effectId: String? = null,
    val param: String? = null,
    val keyframes: List<KeyframeDocumentV8> = emptyList(),
)

/**
 * v8. [timeUs] is clip-local (µs since the clip's first frame; may be
 * negative or past the end). [easing] shapes the way to the next keyframe:
 * "linear", "hold", "ease-in", "ease-out", "ease-in-out" or
 * "cubic-bezier(x1,y1,x2,y2)".
 */
@Serializable
internal data class KeyframeDocumentV8(
    val timeUs: Long,
    val value: Double,
    val easing: String = "linear",
)

/**
 * v7. Position is where the anchor sits, as fractions of the canvas; the
 * anchor and crop are fractions of the picture and of the upright source,
 * y down; rotation is clockwise degrees about the anchor. The aspect lock
 * is "W:H" or absent for a free crop.
 */
@Serializable
internal data class TransformDocumentV7(
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val cropAspect: String? = null,
    val fit: FitModeV7 = FitModeV7.FIT,
    val opacity: Float = 1f,
)

@Serializable
internal enum class FitModeV7 {
    @SerialName("fit") FIT,
    @SerialName("fill") FILL,
}

/**
 * v6. Colors are "#AARRGGBB". Sizes are fractions (of the canvas's shorter
 * side for the font, of the font size for outline, shadow and background),
 * positions fractions of the canvas, rotation clockwise degrees.
 */
@Serializable
internal data class OverlayDocumentV6(
    val type: OverlayTypeV6,
    /** Text overlays only. */
    val text: String? = null,
    val style: TextStyleDocumentV6? = null,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
internal enum class OverlayTypeV6 {
    @SerialName("text") TEXT,
    @SerialName("sticker") STICKER,
}

@Serializable
internal data class TextStyleDocumentV6(
    /** An [OverlayFont] key. Unknown keys read as the default sans-serif. */
    val font: String = "sans",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val size: Float = TextStyle.DEFAULT_SIZE,
    val color: String = "#FFFFFFFF",
    val align: TextAlignV6 = TextAlignV6.CENTER,
    val outline: OutlineDocumentV6? = null,
    val shadow: ShadowDocumentV6? = null,
    val background: BackgroundDocumentV6? = null,
)

@Serializable
internal enum class TextAlignV6 {
    @SerialName("left") LEFT,
    @SerialName("center") CENTER,
    @SerialName("right") RIGHT,
}

@Serializable
internal data class OutlineDocumentV6(val color: String, val width: Float)

@Serializable
internal data class ShadowDocumentV6(val color: String, val blur: Float, val offsetX: Float, val offsetY: Float)

@Serializable
internal data class BackgroundDocumentV6(val color: String, val padding: Float, val corner: Float)

@Serializable
internal data class EffectDocumentV4(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, Double> = emptyMap(),
)

// ======================= v3 (read-only) =======================

@Serializable
internal data class ProjectDocumentV3(
    val schemaVersion: Int = 3,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val media: List<MediaDocumentV2>,
    val timeline: TimelineDocumentV3? = null,
)

@Serializable
internal data class TimelineDocumentV3(
    val frameRateNum: Long,
    val frameRateDen: Long,
    val tracks: List<TrackDocumentV3>,
)

@Serializable
internal data class TrackDocumentV3(val id: String, val kind: TrackKindV3, val clips: List<ClipDocumentV3>)

@Serializable
internal enum class TrackKindV3 {
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,

    /** v6. */
    @SerialName("overlay") OVERLAY,
}

@Serializable
internal data class ClipDocumentV3(
    val id: String,
    val mediaId: String,
    val startUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
)

// ======================= v2 (read-only; its media parts are reused by later versions) =======================

@Serializable
internal data class ProjectDocumentV2(
    val schemaVersion: Int = 2,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val media: List<MediaDocumentV2>,
)

@Serializable
internal data class MediaDocumentV2(
    val id: String,
    val uri: String,
    val kind: MediaKindV2,
    val displayName: String? = null,
    val mimeType: String? = null,
    val durationUs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val sizeBytes: Long? = null,
    val video: VideoTrackDocumentV2? = null,
    val audio: AudioTrackDocumentV2? = null,
    val tags: TagsDocumentV2? = null,
)

@Serializable
internal enum class MediaKindV2 {
    @SerialName("video") VIDEO,
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
}

@Serializable
internal data class VideoTrackDocumentV2(
    val codecMime: String? = null,
    /** Exact frame rate as numerator and denominator. Both are absent if unknown. */
    val frameRateNum: Long? = null,
    val frameRateDen: Long? = null,
    val bitrate: Int? = null,
)

@Serializable
internal data class AudioTrackDocumentV2(
    val codecMime: String? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val bitrate: Int? = null,
)

@Serializable
internal data class TagsDocumentV2(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

// ======================= v1 (read-only) =======================

@Serializable
internal data class ProjectDocumentV1(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val media: List<MediaDocumentV1>,
)

@Serializable
internal data class MediaDocumentV1(
    val id: String,
    val uri: String,
    val kind: MediaKindV1,
    val displayName: String? = null,
    val mimeType: String? = null,
    val durationUs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val sizeBytes: Long? = null,
)

@Serializable
internal enum class MediaKindV1 {
    @SerialName("video") VIDEO,
    @SerialName("image") IMAGE,
}

// ======================= Codec =======================

/** Why a project document could not be read. */
internal sealed class DecodeResult {
    data class Ok(val project: Project) : DecodeResult()
    data class NewerSchema(val version: Int) : DecodeResult()
    data class Corrupt(val cause: Throwable) : DecodeResult()
}

internal object ProjectDocumentCodec {
    /** v8: the property name of an animated effect parameter. */
    private const val EFFECT_PROPERTY = "effect"

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String = json.encodeToString(ProjectDocumentV4.serializer(), project.toDocument())

    fun encodeToElement(project: Project): JsonObject =
        json.encodeToJsonElement(ProjectDocumentV4.serializer(), project.toDocument()).jsonObject

    fun decode(text: String): DecodeResult = try {
        decode(json.parseToJsonElement(text).jsonObject)
    } catch (e: Exception) {
        DecodeResult.Corrupt(e)
    }

    /** Decodes any supported version and migrates it to the current model. Model invariants count as corruption. */
    fun decode(element: JsonObject): DecodeResult = try {
        val version = element["schemaVersion"]?.jsonPrimitive?.int ?: error("Missing schemaVersion")
        when {
            version > CURRENT_SCHEMA_VERSION -> DecodeResult.NewerSchema(version)
            // v5 to v8 only added optional fields to the v4 shape.
            version in 4..9 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV4.serializer(), element).toModel().validated())
            version == 3 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV3.serializer(), element).toModel().validated())
            version == 2 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV2.serializer(), element).toModel())
            version == 1 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV1.serializer(), element).toModel())
            else -> error("Unknown schemaVersion $version")
        }
    } catch (e: Exception) {
        DecodeResult.Corrupt(e)
    }

    /** Every clip with media must reference media in the project. Otherwise the document is corrupt. */
    private fun Project.validated(): Project {
        val mediaIds = media.map { it.id }.toSet()
        timeline?.tracks?.forEach { track ->
            track.clips.forEach { clip ->
                clip.mediaId?.let { id -> require(id in mediaIds) { "Clip ${clip.id.value} references unknown media" } }
            }
        }
        return this
    }

    // ---- model -> v4 ----

    private fun Project.toDocument() = ProjectDocumentV4(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        id = id.value,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        settings = SettingsDocumentV4(
            canvasWidthPx = settings.canvasWidthPx,
            canvasHeightPx = settings.canvasHeightPx,
            frameRateNum = settings.frameRate.numerator,
            frameRateDen = settings.frameRate.denominator,
            snapToEdges = settings.snapToEdges,
            photoDurationUs = settings.photoDurationUs,
            snapDistanceDp = settings.snapDistanceDp,
        ),
        media = media.map { it.toDocument() },
        timeline = timeline?.let { timeline ->
            TimelineDocumentV4(
                tracks = timeline.tracks.map { track ->
                    TrackDocumentV4(
                        id = track.id.value,
                        kind = when (track.kind) {
                            TrackKind.VIDEO -> TrackKindV3.VIDEO
                            TrackKind.AUDIO -> TrackKindV3.AUDIO
                            TrackKind.OVERLAY -> TrackKindV3.OVERLAY
                        },
                        audio = track.audio.takeIf { it != TrackAudio.DEFAULT }?.let { TrackAudioDocumentV5(it.volume, it.muted) },
                        role = when (track.role) {
                            AudioRole.MUSIC -> AudioRoleV5.MUSIC
                            AudioRole.VOICEOVER -> AudioRoleV5.VOICEOVER
                            null -> null
                        },
                        clips = track.clips.map { clip ->
                            ClipDocumentV4(
                                id = clip.id.value,
                                mediaId = clip.mediaId?.value,
                                startUs = clip.startUs,
                                sourceInUs = clip.sourceInUs,
                                durationUs = clip.durationUs,
                                effects = clip.effects.map { EffectDocumentV4(it.id, it.type, it.enabled, it.params) },
                                audio = clip.audio.takeIf { it != ClipAudio.DEFAULT }?.toDocument(),
                                overlay = clip.overlay?.toDocument(),
                                transform = clip.transform.takeUnless { it.isDefault }?.toDocument(),
                                animation = clip.animation.takeUnless { it.isEmpty }?.toDocument(),
                                transition = clip.transitionOut?.let {
                                    TransitionDocumentV9(it.type.key, it.durationUs, Easing.key(it.easing), it.alignment.key)
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun ClipAnimation.toDocument(): List<PropertyAnimationDocumentV8> =
        tracks.entries.sortedBy { it.key.key }.map { (property, track) ->
            PropertyAnimationDocumentV8(
                property = if (property is EffectParam) EFFECT_PROPERTY else property.key,
                effectId = (property as? EffectParam)?.effectId,
                param = (property as? EffectParam)?.param,
                keyframes = track.keyframes.map { KeyframeDocumentV8(it.timeUs, it.value, Easing.key(it.easing)) },
            )
        }

    /**
     * Lenient: non-finite values and repeated times are dropped, an unknown
     * easing reads as linear, and keyframes for properties the clip can't
     * have (an unknown name, placement on a sound clip, a missing effect) are
     * ignored instead of failing the project. Crop keyframes count only on
     * video tracks.
     */
    private fun List<PropertyAnimationDocumentV8>.toModel(kind: TrackKind, effectIds: Set<String>): ClipAnimation {
        val tracks = LinkedHashMap<AnimatedProperty, KeyframeTrack>()
        forEach { doc ->
            val property: AnimatedProperty = when {
                doc.property == EFFECT_PROPERTY -> {
                    val effectId = doc.effectId?.takeIf { it in effectIds } ?: return@forEach
                    EffectParam(effectId, doc.param?.takeIf { it.isNotBlank() } ?: return@forEach)
                }
                kind == TrackKind.AUDIO -> return@forEach
                else -> {
                    val channel = TransformChannel.fromKey(doc.property) ?: return@forEach
                    // Only pictures have a crop.
                    if (channel.isCrop && kind != TrackKind.VIDEO) return@forEach
                    channel
                }
            }
            val keyframes = doc.keyframes
                .filter { it.value.isFinite() }
                .sortedBy { it.timeUs }
                .distinctBy { it.timeUs }
                .map { Keyframe(it.timeUs, it.value, Easing.parse(it.easing) ?: Easing.Linear) }
            if (keyframes.isNotEmpty()) tracks[property] = KeyframeTrack(keyframes)
        }
        return if (tracks.isEmpty()) ClipAnimation.NONE else ClipAnimation(tracks)
    }

    /**
     * Lenient: an unknown type drops the transition (a plain cut), the length
     * is clamped to the allowed range, an unknown easing reads as the type's
     * default, and an unknown alignment as centred.
     */
    private fun TransitionDocumentV9.toModel(): Transition? {
        val type = TransitionType.fromKey(type) ?: return null
        return Transition(
            type = type,
            durationUs = durationUs.coerceIn(Transition.MIN_DURATION_US, Transition.MAX_DURATION_US),
            easing = Easing.parse(easing) ?: type.defaultEasing,
            alignment = TransitionAlignment.fromKey(alignment) ?: TransitionAlignment.CENTER,
        )
    }

    private fun ClipTransform.toDocument() = TransformDocumentV7(
        positionX = positionX,
        positionY = positionY,
        scale = scale,
        rotationDegrees = rotationDegrees,
        anchorX = anchorX,
        anchorY = anchorY,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
        cropAspect = cropAspect?.toString(),
        fit = when (fit) {
            FitMode.FIT -> FitModeV7.FIT
            FitMode.FILL -> FitModeV7.FILL
        },
        opacity = opacity,
    )

    /** Lenient, like overlays: out-of-range or unreadable values are brought into range or dropped. */
    private fun TransformDocumentV7.toModel() = ClipTransform.coerced(
        positionX = positionX,
        positionY = positionY,
        scale = scale,
        rotationDegrees = rotationDegrees,
        anchorX = anchorX,
        anchorY = anchorY,
        crop = CropRect.coerced(cropLeft, cropTop, cropRight, cropBottom),
        cropAspect = cropAspect?.let(::aspectRatio),
        fit = when (fit) {
            FitModeV7.FIT -> FitMode.FIT
            FitModeV7.FILL -> FitMode.FILL
        },
        opacity = opacity,
    )

    /** "W:H" as an aspect ratio, or null if it isn't one. */
    private fun aspectRatio(text: String): AspectRatio? {
        val parts = text.split(':')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return AspectRatio.of(w, h)
    }

    private fun Overlay.toDocument(): OverlayDocumentV6 {
        val text = content as? OverlayContent.Text
        return OverlayDocumentV6(
            type = if (text != null) OverlayTypeV6.TEXT else OverlayTypeV6.STICKER,
            text = text?.text,
            style = text?.style?.let { style ->
                TextStyleDocumentV6(
                    font = style.font.key,
                    bold = style.bold,
                    italic = style.italic,
                    size = style.sizeFraction,
                    color = style.color.toHex(),
                    align = when (style.align) {
                        TextAlign.LEFT -> TextAlignV6.LEFT
                        TextAlign.CENTER -> TextAlignV6.CENTER
                        TextAlign.RIGHT -> TextAlignV6.RIGHT
                    },
                    outline = style.outline?.let { OutlineDocumentV6(it.color.toHex(), it.widthFraction) },
                    shadow = style.shadow?.let { ShadowDocumentV6(it.color.toHex(), it.blurFraction, it.offsetXFraction, it.offsetYFraction) },
                    background = style.background?.let { BackgroundDocumentV6(it.color.toHex(), it.paddingFraction, it.cornerFraction) },
                )
            },
            centerX = transform.centerX,
            centerY = transform.centerY,
            scale = transform.scale,
            rotationDegrees = transform.rotationDegrees,
            opacity = transform.opacity,
        )
    }

    /**
     * Lenient, like audio: out-of-range numbers are brought into range, an
     * unreadable color becomes the default, and an unknown font reads as
     * sans-serif, instead of failing the whole project.
     */
    private fun OverlayDocumentV6.toModel(): Overlay {
        val content = when (type) {
            OverlayTypeV6.TEXT -> OverlayContent.Text(
                text = text.orEmpty().take(OverlayContent.Text.MAX_LENGTH),
                style = style?.toModel() ?: TextStyle(),
            )
            OverlayTypeV6.STICKER -> OverlayContent.Sticker
        }
        return Overlay(content, OverlayTransform.coerced(centerX, centerY, scale, rotationDegrees, opacity))
    }

    private fun TextStyleDocumentV6.toModel() = TextStyle(
        font = OverlayFont.fromKey(font),
        bold = bold,
        italic = italic,
        sizeFraction = fraction(size, TextStyle.MIN_SIZE, TextStyle.MAX_SIZE, TextStyle.DEFAULT_SIZE),
        color = Argb.parse(color) ?: Argb.WHITE,
        align = when (align) {
            TextAlignV6.LEFT -> TextAlign.LEFT
            TextAlignV6.CENTER -> TextAlign.CENTER
            TextAlignV6.RIGHT -> TextAlign.RIGHT
        },
        outline = outline?.let { TextOutline(Argb.parse(it.color) ?: Argb.BLACK, fraction(it.width, TextOutline.MIN_WIDTH, TextOutline.MAX_WIDTH, 0.08f)) },
        shadow = shadow?.let {
            TextShadow(
                color = Argb.parse(it.color) ?: Argb.BLACK,
                blurFraction = fraction(it.blur, 0f, TextShadow.MAX_BLUR, 0.08f),
                offsetXFraction = fraction(it.offsetX, -TextShadow.MAX_OFFSET, TextShadow.MAX_OFFSET, 0f),
                offsetYFraction = fraction(it.offsetY, -TextShadow.MAX_OFFSET, TextShadow.MAX_OFFSET, 0f),
            )
        },
        background = background?.let {
            TextBackground(
                color = Argb.parse(it.color) ?: Argb.BLACK,
                paddingFraction = fraction(it.padding, 0f, TextBackground.MAX_PADDING, 0.25f),
                cornerFraction = fraction(it.corner, 0f, TextBackground.MAX_CORNER, 0.2f),
            )
        },
    )

    private fun fraction(value: Float, min: Float, max: Float, default: Float): Float = if (value.isFinite()) value.coerceIn(min, max) else default

    private fun ClipAudio.toDocument() = ClipAudioDocumentV5(
        volume = volume,
        muted = muted,
        fadeInUs = fadeInUs,
        fadeOutUs = fadeOutUs,
        keyframes = keyframes.map {
            GainKeyframeDocumentV5(
                it.sourceTimeUs,
                it.gain,
                if (it.interpolation == GainInterpolation.HOLD) GainInterpolationV5.HOLD else GainInterpolationV5.LINEAR,
            )
        },
    )

    /** Lenient: out-of-range values from hand-edited files are brought into range instead of failing the whole project. */
    private fun ClipAudioDocumentV5.toModel() = ClipAudio(
        volume = unitGain(volume),
        muted = muted,
        fadeInUs = fadeInUs.coerceAtLeast(0),
        fadeOutUs = fadeOutUs.coerceAtLeast(0),
        keyframes = keyframes
            .filter { it.sourceTimeUs >= 0 }
            .sortedBy { it.sourceTimeUs }
            .distinctBy { it.sourceTimeUs }
            .map {
                GainKeyframe(it.sourceTimeUs, unitGain(it.gain), if (it.interpolation == GainInterpolationV5.HOLD) GainInterpolation.HOLD else GainInterpolation.LINEAR)
            },
    )

    private fun unitGain(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f

    private fun MediaRef.toDocument() = MediaDocumentV4(
        id = id.value, uri = uri,
        kind = when (kind) {
            MediaKind.VIDEO -> MediaKindV2.VIDEO
            MediaKind.IMAGE -> MediaKindV2.IMAGE
            MediaKind.AUDIO -> MediaKindV2.AUDIO
        },
        displayName = displayName, mimeType = mimeType, durationUs = durationUs,
        widthPx = widthPx, heightPx = heightPx, rotationDegrees = rotationDegrees, sizeBytes = sizeBytes,
        video = video?.let {
            VideoTrackDocumentV2(it.codecMime, it.frameRate?.numerator, it.frameRate?.denominator, it.bitrate)
        },
        audio = audio?.let { AudioTrackDocumentV2(it.codecMime, it.sampleRateHz, it.channelCount, it.bitrate) },
        tags = tags?.takeUnless { it.isEmpty }?.let { TagsDocumentV2(it.title, it.artist, it.album) },
        accessPersisted = accessPersisted,
    )

    // ---- v4 -> model ----

    private fun ProjectDocumentV4.toModel(): Project {
        val frameRate = FrameRate.of(settings.frameRateNum, settings.frameRateDen)
        return Project(
            id = ProjectId(id),
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            media = media.map { it.toModel() },
            timeline = timeline?.let { doc ->
                Timeline(
                    tracks = doc.tracks.map { track ->
                        Track(
                            id = TrackId(track.id),
                            kind = track.kind.toModel(),
                            audio = track.audio?.let { TrackAudio(unitGain(it.volume), it.muted) } ?: TrackAudio.DEFAULT,
                            role = when (track.role) {
                                AudioRoleV5.MUSIC -> AudioRole.MUSIC
                                AudioRoleV5.VOICEOVER -> AudioRole.VOICEOVER
                                null -> null
                            },
                            clips = track.clips.map { clip ->
                                val overlay = clip.overlay?.toModel()
                                val kind = track.kind.toModel()
                                Clip(
                                    id = ClipId(clip.id),
                                    // A text overlay never uses media, even if a hand-edited file names some.
                                    mediaId = clip.mediaId?.takeUnless { overlay?.isText == true }?.let(::MediaId),
                                    startUs = clip.startUs,
                                    sourceInUs = clip.sourceInUs,
                                    durationUs = clip.durationUs,
                                    effects = clip.effects.map { ClipEffect(it.id, it.type, it.enabled, it.params) },
                                    audio = clip.audio?.toModel() ?: ClipAudio.DEFAULT,
                                    overlay = overlay,
                                    // Only pictures on video tracks are placed; a transform anywhere else is ignored.
                                    transform = clip.transform?.takeIf { kind == TrackKind.VIDEO }?.toModel() ?: ClipTransform.DEFAULT,
                                    animation = clip.animation?.toModel(kind, clip.effects.map { it.id }.toSet()) ?: ClipAnimation.NONE,
                                    // Only pictures on video tracks have transitions; one anywhere else is ignored.
                                    transitionOut = clip.transition?.takeIf { kind == TrackKind.VIDEO }?.toModel(),
                                )
                            },
                        )
                    },
                    frameRate = frameRate,
                )
            },
            settings = ProjectSettings(
                canvasWidthPx = settings.canvasWidthPx,
                canvasHeightPx = settings.canvasHeightPx,
                frameRate = frameRate,
                snapToEdges = settings.snapToEdges,
                // Out-of-range values (hand-edited files) fall back to the default instead of failing the load.
                snapDistanceDp = settings.snapDistanceDp.takeIf {
                    it in ProjectSettings.MIN_SNAP_DISTANCE_DP..ProjectSettings.MAX_SNAP_DISTANCE_DP
                } ?: ProjectSettings.DEFAULT_SNAP_DISTANCE_DP,
                photoDurationUs = settings.photoDurationUs,
            ),
        )
    }

    private fun MediaDocumentV4.toModel() = mediaModel(
        id, uri, kind, displayName, mimeType, durationUs, widthPx, heightPx, rotationDegrees, sizeBytes, video, audio, tags, accessPersisted,
    )

    // ---- v3 -> model (migration: settings are derived from the media; the timeline's frame rate becomes the project's) ----

    private fun ProjectDocumentV3.toModel(): Project {
        val mediaModels = media.map { it.toModel() }
        val timelineModel = timeline?.let { doc ->
            Timeline(
                tracks = doc.tracks.map { track ->
                    Track(
                        id = TrackId(track.id),
                        kind = track.kind.toModel(),
                        clips = track.clips.map { Clip(ClipId(it.id), MediaId(it.mediaId), it.startUs, it.sourceInUs, it.durationUs) },
                    )
                },
                frameRate = FrameRate.of(doc.frameRateNum, doc.frameRateDen),
            )
        }
        val derived = ProjectSettings.matching(mediaModels)
        val settings = timelineModel?.let { derived.copy(frameRate = it.frameRate) } ?: derived
        return Project(ProjectId(id), name, createdAtEpochMs, updatedAtEpochMs, mediaModels, timelineModel, settings)
    }

    // ---- v2 -> model (migration: no timeline yet; settings derived from the media) ----

    private fun ProjectDocumentV2.toModel(): Project {
        val mediaModels = media.map { it.toModel() }
        return Project(ProjectId(id), name, createdAtEpochMs, updatedAtEpochMs, mediaModels, null, ProjectSettings.matching(mediaModels))
    }

    private fun MediaDocumentV2.toModel() = mediaModel(
        id, uri, kind, displayName, mimeType, durationUs, widthPx, heightPx, rotationDegrees, sizeBytes, video, audio, tags, accessPersisted = true,
    )

    // ---- v1 -> model (migration: v1 had no track info, tags or audio media) ----

    private fun ProjectDocumentV1.toModel(): Project {
        val mediaModels = media.map {
            MediaRef(
                id = MediaId(it.id), uri = it.uri,
                kind = if (it.kind == MediaKindV1.VIDEO) MediaKind.VIDEO else MediaKind.IMAGE,
                displayName = it.displayName, mimeType = it.mimeType, durationUs = it.durationUs,
                widthPx = it.widthPx, heightPx = it.heightPx, rotationDegrees = it.rotationDegrees, sizeBytes = it.sizeBytes,
            )
        }
        return Project(ProjectId(id), name, createdAtEpochMs, updatedAtEpochMs, mediaModels, null, ProjectSettings.matching(mediaModels))
    }

    // ---- Shared ----

    private fun TrackKindV3.toModel() = when (this) {
        TrackKindV3.VIDEO -> TrackKind.VIDEO
        TrackKindV3.AUDIO -> TrackKind.AUDIO
        TrackKindV3.OVERLAY -> TrackKind.OVERLAY
    }

    private fun mediaModel(
        id: String,
        uri: String,
        kind: MediaKindV2,
        displayName: String?,
        mimeType: String?,
        durationUs: Long,
        widthPx: Int,
        heightPx: Int,
        rotationDegrees: Int,
        sizeBytes: Long?,
        video: VideoTrackDocumentV2?,
        audio: AudioTrackDocumentV2?,
        tags: TagsDocumentV2?,
        accessPersisted: Boolean,
    ) = MediaRef(
        id = MediaId(id), uri = uri,
        kind = when (kind) {
            MediaKindV2.VIDEO -> MediaKind.VIDEO
            MediaKindV2.IMAGE -> MediaKind.IMAGE
            MediaKindV2.AUDIO -> MediaKind.AUDIO
        },
        displayName = displayName, mimeType = mimeType, durationUs = durationUs,
        widthPx = widthPx, heightPx = heightPx, rotationDegrees = rotationDegrees, sizeBytes = sizeBytes,
        video = video?.let {
            val num = it.frameRateNum
            val den = it.frameRateDen
            VideoTrackInfo(it.codecMime, if (num != null && den != null) FrameRate.of(num, den) else null, it.bitrate)
        },
        audio = audio?.let { AudioTrackInfo(it.codecMime, it.sampleRateHz, it.channelCount, it.bitrate) },
        tags = tags?.let { MediaTags(it.title, it.artist, it.album) },
        accessPersisted = accessPersisted,
    )
}
