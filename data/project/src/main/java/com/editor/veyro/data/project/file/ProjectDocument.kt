package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.MediaTags
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.VideoTrackInfo
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * On-disk JSON format of a project.
 *
 * These classes are the storage contract and are kept apart from the domain
 * model so the model can evolve. Never change a released version's shape.
 * Add a new version, decode the old one, and map it forward in
 * [ProjectDocumentCodec.decode]. Documents are always written in
 * [CURRENT_SCHEMA_VERSION].
 *
 * History:
 * - v1 (2026-10-14): project name and timestamps, plus video and image media
 *   with basic metadata.
 * - v2 (2026-10-14): audio media, video track info (codec, frame rate,
 *   bitrate), audio track info, and tags.
 * - v3 (2026-10-14): the edited timeline (tracks, clips, frame rate). Media
 *   entries keep the v2 shape. A missing timeline means "not edited yet".
 */

internal const val CURRENT_SCHEMA_VERSION = 3

// ---- v3 (current) ----

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
internal data class TrackDocumentV3(
    val id: String,
    val kind: TrackKindV3,
    val clips: List<ClipDocumentV3>,
)

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

// ---- v2 (read-only; its media shape is reused by v3) ----

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

// ---- v1 (read-only) ----

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

/** Why a project document could not be read. */
internal sealed class DecodeResult {
    data class Ok(val project: Project) : DecodeResult()
    data class NewerSchema(val version: Int) : DecodeResult()
    data class Corrupt(val cause: Throwable) : DecodeResult()
}

internal object ProjectDocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String = json.encodeToString(ProjectDocumentV3.serializer(), project.toDocument())

    fun decode(text: String): DecodeResult = try {
        val version = json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
            ?: error("Missing schemaVersion")
        when {
            version > CURRENT_SCHEMA_VERSION -> DecodeResult.NewerSchema(version)
            version == 3 -> DecodeResult.Ok(json.decodeFromString(ProjectDocumentV3.serializer(), text).toModel())
            version == 2 -> DecodeResult.Ok(json.decodeFromString(ProjectDocumentV2.serializer(), text).toModel())
            version == 1 -> DecodeResult.Ok(json.decodeFromString(ProjectDocumentV1.serializer(), text).toModel())
            else -> error("Unknown schemaVersion $version")
        }
    } catch (e: Exception) {
        // Covers malformed JSON, missing fields and model invariants (IllegalArgumentException).
        DecodeResult.Corrupt(e)
    }

    // ---- model -> v3 ----

    private fun Project.toDocument() = ProjectDocumentV3(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        id = id.value,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        media = media.map { it.toDocument() },
        timeline = timeline?.let { timeline ->
            TimelineDocumentV3(
                frameRateNum = timeline.frameRate.numerator,
                frameRateDen = timeline.frameRate.denominator,
                tracks = timeline.tracks.map { track ->
                    TrackDocumentV3(
                        id = track.id.value,
                        kind = when (track.kind) {
                            TrackKind.VIDEO -> TrackKindV3.VIDEO
                            TrackKind.AUDIO -> TrackKindV3.AUDIO
                        },
                        clips = track.clips.map { ClipDocumentV3(it.id.value, it.mediaId.value, it.startUs, it.sourceInUs, it.durationUs) },
                    )
                },
            )
        },
    )

    // ---- v3 -> model ----

    private fun ProjectDocumentV3.toModel(): Project {
        val project = Project(
            id = ProjectId(id),
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            media = media.map { it.toModel() },
            timeline = timeline?.let { doc ->
                Timeline(
                    frameRate = FrameRate.of(doc.frameRateNum, doc.frameRateDen),
                    tracks = doc.tracks.map { track ->
                        Track(
                            id = TrackId(track.id),
                            kind = when (track.kind) {
                                TrackKindV3.VIDEO -> TrackKind.VIDEO
                                TrackKindV3.AUDIO -> TrackKind.AUDIO
                            },
                            clips = track.clips.map { Clip(ClipId(it.id), MediaId(it.mediaId), it.startUs, it.sourceInUs, it.durationUs) },
                        )
                    },
                )
            },
        )
        // Every clip must point at media in the project. Otherwise the document is corrupt.
        val mediaIds = project.media.map { it.id }.toSet()
        project.timeline?.tracks?.forEach { track ->
            track.clips.forEach { clip -> require(clip.mediaId in mediaIds) { "Clip ${clip.id.value} references unknown media" } }
        }
        return project
    }

    private fun MediaRef.toDocument() = MediaDocumentV2(
        id = id.value, uri = uri,
        kind = when (kind) {
            MediaKind.VIDEO -> MediaKindV2.VIDEO
            MediaKind.IMAGE -> MediaKindV2.IMAGE
            MediaKind.AUDIO -> MediaKindV2.AUDIO
        },
        displayName = displayName, mimeType = mimeType, durationUs = durationUs,
        widthPx = widthPx, heightPx = heightPx, rotationDegrees = rotationDegrees, sizeBytes = sizeBytes,
        video = video?.let {
            VideoTrackDocumentV2(
                codecMime = it.codecMime,
                frameRateNum = it.frameRate?.numerator,
                frameRateDen = it.frameRate?.denominator,
                bitrate = it.bitrate,
            )
        },
        audio = audio?.let { AudioTrackDocumentV2(it.codecMime, it.sampleRateHz, it.channelCount, it.bitrate) },
        tags = tags?.takeUnless { it.isEmpty }?.let { TagsDocumentV2(it.title, it.artist, it.album) },
    )

    // ---- v2 -> model (migration: v2 had no timeline, so it starts unedited) ----

    private fun ProjectDocumentV2.toModel() = Project(
        id = ProjectId(id),
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        media = media.map { it.toModel() },
    )

    private fun MediaDocumentV2.toModel() = MediaRef(
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
            VideoTrackInfo(
                codecMime = it.codecMime,
                frameRate = if (num != null && den != null) FrameRate.of(num, den) else null,
                bitrate = it.bitrate,
            )
        },
        audio = audio?.let { AudioTrackInfo(it.codecMime, it.sampleRateHz, it.channelCount, it.bitrate) },
        tags = tags?.let { MediaTags(it.title, it.artist, it.album) },
    )

    // ---- v1 -> model (migration: v1 had no track info, tags or audio media) ----

    private fun ProjectDocumentV1.toModel() = Project(
        id = ProjectId(id),
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        media = media.map { it.toModel() },
    )

    private fun MediaDocumentV1.toModel() = MediaRef(
        id = MediaId(id), uri = uri,
        kind = when (kind) {
            MediaKindV1.VIDEO -> MediaKind.VIDEO
            MediaKindV1.IMAGE -> MediaKind.IMAGE
        },
        displayName = displayName, mimeType = mimeType, durationUs = durationUs,
        widthPx = widthPx, heightPx = heightPx, rotationDegrees = rotationDegrees, sizeBytes = sizeBytes,
    )
}
