package com.editor.veyro.data.project.file

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
 */

internal const val CURRENT_SCHEMA_VERSION = 4

// ======================= v4 (current) =======================

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
internal data class TrackDocumentV4(val id: String, val kind: TrackKindV3, val clips: List<ClipDocumentV4>)

@Serializable
internal data class ClipDocumentV4(
    val id: String,
    val mediaId: String,
    val startUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
    val effects: List<EffectDocumentV4> = emptyList(),
)

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
            version == 4 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV4.serializer(), element).toModel().validated())
            version == 3 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV3.serializer(), element).toModel().validated())
            version == 2 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV2.serializer(), element).toModel())
            version == 1 -> DecodeResult.Ok(json.decodeFromJsonElement(ProjectDocumentV1.serializer(), element).toModel())
            else -> error("Unknown schemaVersion $version")
        }
    } catch (e: Exception) {
        DecodeResult.Corrupt(e)
    }

    /** Every clip must reference media in the project. Otherwise the document is corrupt. */
    private fun Project.validated(): Project {
        val mediaIds = media.map { it.id }.toSet()
        timeline?.tracks?.forEach { track ->
            track.clips.forEach { clip -> require(clip.mediaId in mediaIds) { "Clip ${clip.id.value} references unknown media" } }
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
                        kind = if (track.kind == TrackKind.VIDEO) TrackKindV3.VIDEO else TrackKindV3.AUDIO,
                        clips = track.clips.map { clip ->
                            ClipDocumentV4(
                                id = clip.id.value,
                                mediaId = clip.mediaId.value,
                                startUs = clip.startUs,
                                sourceInUs = clip.sourceInUs,
                                durationUs = clip.durationUs,
                                effects = clip.effects.map { EffectDocumentV4(it.id, it.type, it.enabled, it.params) },
                            )
                        },
                    )
                },
            )
        },
    )

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
                            clips = track.clips.map { clip ->
                                Clip(
                                    id = ClipId(clip.id),
                                    mediaId = MediaId(clip.mediaId),
                                    startUs = clip.startUs,
                                    sourceInUs = clip.sourceInUs,
                                    durationUs = clip.durationUs,
                                    effects = clip.effects.map { ClipEffect(it.id, it.type, it.enabled, it.params) },
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

    private fun TrackKindV3.toModel() = if (this == TrackKindV3.VIDEO) TrackKind.VIDEO else TrackKind.AUDIO

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
