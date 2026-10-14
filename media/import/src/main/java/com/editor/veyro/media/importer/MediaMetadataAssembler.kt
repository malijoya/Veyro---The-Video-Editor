package com.editor.veyro.media.importer

import com.editor.veyro.core.model.project.AudioTrackInfo
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.MediaTags
import com.editor.veyro.core.model.project.VideoTrackInfo
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.TimeUs

/** What the content provider reports about a file. */
public data class RawFileInfo(
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

/** One track as reported by `MediaExtractor`. Absent keys are null. */
public data class RawTrack(
    val mime: String,
    val durationUs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    /** `KEY_FRAME_RATE`. Containers report it as an int or a float, and it may be approximate. */
    val frameRate: Double? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val bitrate: Int? = null,
)

/** What `MediaMetadataRetriever` reports. Used to fill gaps the extractor leaves. */
public data class RawRetrieverInfo(
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    /** `METADATA_KEY_VIDEO_FRAME_COUNT` (API 28+). */
    val videoFrameCount: Int? = null,
    /** Overall bitrate of the file, all tracks combined. */
    val bitrate: Int? = null,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Combines extractor and retriever output into a [MediaRef] for time-based
 * media (video and audio).
 *
 * Kept separate from the Android calls so every rule can be unit-tested:
 * - Extractor values win, because they are per-track and in microseconds.
 *   The retriever fills the gaps.
 * - Frame rate comes from the container's declared rate. Failing that, it is
 *   frame count ÷ duration. Either way it snaps to standard rates.
 * - A "video" file with no video track but an audio track is imported as
 *   audio (common for `.mp4` and `.3gp` voice recordings).
 */
public object MediaMetadataAssembler {

    public fun assembleTimed(
        id: MediaId,
        declaredKind: MediaKind,
        file: RawFileInfo,
        tracks: List<RawTrack>,
        retriever: RawRetrieverInfo?,
    ): ProbeResult {
        require(declaredKind != MediaKind.IMAGE) { "Images are probed separately" }
        val videoTrack = tracks.firstOrNull { it.mime.startsWith("video/") }
        val audioTrack = tracks.firstOrNull { it.mime.startsWith("audio/") }
        val hasVideo = videoTrack != null || retriever?.hasVideo == true
        val hasAudio = audioTrack != null || retriever?.hasAudio == true

        val kind = when {
            declaredKind == MediaKind.VIDEO && hasVideo -> MediaKind.VIDEO
            declaredKind == MediaKind.VIDEO && hasAudio -> MediaKind.AUDIO
            declaredKind == MediaKind.AUDIO && hasAudio -> MediaKind.AUDIO
            else -> return ProbeResult.Failure(file.uri, ProbeFailure.UNREADABLE)
        }

        val durationUs = tracks.mapNotNull { it.durationUs }.filter { it > 0 }.maxOrNull()
            ?: retriever?.durationMs?.takeIf { it > 0 }?.let(TimeUs::fromMillis)
            ?: return ProbeResult.Failure(file.uri, ProbeFailure.UNREADABLE)

        val audio = if (hasAudio) {
            AudioTrackInfo(
                codecMime = audioTrack?.mime,
                sampleRateHz = audioTrack?.sampleRateHz?.takeIf { it > 0 },
                channelCount = audioTrack?.channelCount?.takeIf { it > 0 },
                // For an audio-only file the overall bitrate is the audio bitrate.
                bitrate = audioTrack?.bitrate?.takeIf { it > 0 }
                    ?: retriever?.bitrate?.takeIf { it > 0 && kind == MediaKind.AUDIO && !hasVideo },
            )
        } else {
            null
        }
        val tags = MediaTags(
            title = retriever?.title.clean(),
            artist = retriever?.artist.clean(),
            album = retriever?.album.clean(),
        ).takeUnless { it.isEmpty }

        if (kind == MediaKind.AUDIO) {
            return ProbeResult.Success(
                MediaRef(
                    id = id, uri = file.uri, kind = MediaKind.AUDIO,
                    displayName = file.displayName, mimeType = file.mimeType, durationUs = durationUs,
                    widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = file.sizeBytes,
                    audio = audio, tags = tags,
                ),
            )
        }

        val frameRate = videoTrack?.frameRate?.let(FrameRate::fromApproximateFps)
            ?: retriever?.videoFrameCount?.takeIf { it > 0 }?.let { frames ->
                FrameRate.fromApproximateFps(frames * TimeUs.PER_SECOND.toDouble() / durationUs)
            }
        return ProbeResult.Success(
            MediaRef(
                id = id, uri = file.uri, kind = MediaKind.VIDEO,
                displayName = file.displayName, mimeType = file.mimeType, durationUs = durationUs,
                widthPx = (videoTrack?.width ?: retriever?.width)?.coerceAtLeast(0) ?: 0,
                heightPx = (videoTrack?.height ?: retriever?.height)?.coerceAtLeast(0) ?: 0,
                rotationDegrees = MediaRef.normalizeRotation(
                    videoTrack?.rotationDegrees ?: retriever?.rotationDegrees ?: 0,
                ),
                sizeBytes = file.sizeBytes,
                video = VideoTrackInfo(
                    codecMime = videoTrack?.mime,
                    frameRate = frameRate,
                    bitrate = videoTrack?.bitrate?.takeIf { it > 0 },
                ),
                audio = audio,
                tags = tags,
            ),
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
