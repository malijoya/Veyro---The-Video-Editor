package com.editor.veyro.core.model.project

import com.editor.veyro.core.model.time.FrameRate

/** Stable identifier of a media item inside a project. */
@JvmInline
public value class MediaId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MediaId must not be blank" }
    }
}

public enum class MediaKind { VIDEO, IMAGE, AUDIO }

/** Details of a file's primary video track. Every field is optional because containers often omit them. */
public data class VideoTrackInfo(
    /** Codec MIME type, such as `video/avc` or `video/hevc`. */
    val codecMime: String?,
    /** Nominal frame rate. Null if the container doesn't declare one and it can't be derived. */
    val frameRate: FrameRate?,
    /** Average bitrate in bits per second. */
    val bitrate: Int?,
)

/** Details of a file's primary audio track. Every field is optional because containers often omit them. */
public data class AudioTrackInfo(
    /** Codec MIME type, such as `audio/mp4a-latm` or `audio/opus`. */
    val codecMime: String?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    /** Average bitrate in bits per second. */
    val bitrate: Int?,
)

/** Descriptive tags embedded in a file (ID3, MP4 metadata). Mostly present on music. */
public data class MediaTags(
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    public val isEmpty: Boolean get() = title == null && artist == null && album == null
}

/**
 * A reference to a source media file the user imported. The file itself is
 * never copied or modified, so importing is instant and uses no extra storage.
 *
 * [uri] is a platform URI string. On Android it is a `content://` URI whose read
 * access is persisted where the provider allows it. The other fields are
 * metadata probed at import time. They are shown before the file is opened
 * again, and later they drive timeline defaults.
 */
public data class MediaRef(
    val id: MediaId,
    val uri: String,
    val kind: MediaKind,
    val displayName: String?,
    val mimeType: String?,
    /** Duration in microseconds. Always 0 for [MediaKind.IMAGE]. */
    val durationUs: Long,
    /** Stored (encoded) width before rotation. 0 if unknown, and always 0 for [MediaKind.AUDIO]. */
    val widthPx: Int,
    /** Stored (encoded) height before rotation. 0 if unknown, and always 0 for [MediaKind.AUDIO]. */
    val heightPx: Int,
    /** Clockwise rotation to apply for display: 0, 90, 180 or 270. */
    val rotationDegrees: Int,
    val sizeBytes: Long?,
    /** Present for [MediaKind.VIDEO] only. */
    val video: VideoTrackInfo? = null,
    /** Present for [MediaKind.AUDIO], and for [MediaKind.VIDEO] files that have a sound track. */
    val audio: AudioTrackInfo? = null,
    val tags: MediaTags? = null,
) {
    init {
        require(durationUs >= 0) { "durationUs must not be negative: $durationUs" }
        require(widthPx >= 0 && heightPx >= 0) { "Dimensions must not be negative" }
        require(rotationDegrees in VALID_ROTATIONS) { "Unsupported rotation: $rotationDegrees" }
        when (kind) {
            MediaKind.IMAGE -> {
                require(durationUs == 0L) { "Images have no duration" }
                require(video == null && audio == null) { "Images have no tracks" }
            }
            MediaKind.AUDIO -> {
                require(widthPx == 0 && heightPx == 0 && rotationDegrees == 0) { "Audio has no picture" }
                require(video == null) { "Audio has no video track" }
            }
            MediaKind.VIDEO -> Unit
        }
    }

    /** Whether this media contributes sound when placed on the timeline. */
    val hasAudio: Boolean get() = kind == MediaKind.AUDIO || audio != null

    /** Width as displayed, after applying [rotationDegrees]. */
    val displayWidthPx: Int get() = if (rotationDegrees % 180 == 0) widthPx else heightPx

    /** Height as displayed, after applying [rotationDegrees]. */
    val displayHeightPx: Int get() = if (rotationDegrees % 180 == 0) heightPx else widthPx

    public companion object {
        private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

        /** Maps any angle onto 0, 90, 180 or 270 (rounding to the nearest quarter turn). */
        public fun normalizeRotation(degrees: Int): Int {
            val positive = ((degrees % 360) + 360) % 360
            return ((positive + 45) / 90 % 4) * 90
        }
    }
}
