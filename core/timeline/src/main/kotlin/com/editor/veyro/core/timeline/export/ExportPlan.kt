package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.time.FrameRate
import kotlin.math.roundToLong

/**
 * What the user chose for an export.
 *
 * @property widthPx output width as displayed (upright). Even, 16..7680.
 * @property heightPx output height as displayed. Even, 16..7680.
 * @property frameRate the requested rate. See [outputFps].
 */
public data class ExportSettings(
    val widthPx: Int,
    val heightPx: Int,
    val frameRate: FrameRate,
) {
    init {
        require(widthPx in MIN_DIMENSION_PX..MAX_DIMENSION_PX && widthPx % 2 == 0) { "Invalid export width $widthPx" }
        require(heightPx in MIN_DIMENSION_PX..MAX_DIMENSION_PX && heightPx % 2 == 0) { "Invalid export height $heightPx" }
    }

    /**
     * The whole-number rate passed to the encoder pipeline.
     *
     * Media3 Transformer takes frame rates as integers
     * (`EditedMediaItem.Builder.setFrameRate(int)`), so NTSC rates round to
     * the nearest whole number (29.97 becomes 30, 23.976 becomes 24). Photos
     * are rendered at exactly this rate. For video it is a maximum: faster
     * sources drop frames to reach it, and slower sources keep their own
     * rate, because Transformer never duplicates frames.
     */
    val outputFps: Int get() = frameRate.fps.roundToLong().toInt().coerceAtLeast(1)

    public companion object {
        public const val MIN_DIMENSION_PX: Int = 16
        public const val MAX_DIMENSION_PX: Int = 7_680
    }
}

/**
 * One piece of the main (picture) track, in timeline order. Segments are
 * back to back: segment n+1 starts where segment n ends.
 *
 * @property sourceStartUs where playback starts in the source file. Always 0
 *   for photos.
 * @property durationUs how long the segment lasts in the output.
 * @property sourceDurationUs the whole source file's length (Media3's
 *   `EditedMediaItem.setDurationUs` wants the length before clipping).
 * @property audio the gain for the clip's own sound, or null when it has none
 *   to contribute (photos, videos without an audio stream, muted clips).
 */
public data class VideoSegment(
    val uri: String,
    val kind: MediaKind,
    val sourceStartUs: Long,
    val durationUs: Long,
    val sourceDurationUs: Long,
    val audio: SegmentGain? = null,
) {
    init {
        require(kind != MediaKind.AUDIO) { "Audio files can't be picture segments" }
        require(sourceStartUs >= 0 && durationUs > 0) { "Invalid segment range" }
        require(sourceDurationUs > 0) { "Unknown source length" }
    }

    val sourceEndUs: Long get() = sourceStartUs + durationUs

    val hasAudio: Boolean get() = audio != null
}

/** One piece of an audio track, in time order. */
public sealed interface AudioSegment {
    public val durationUs: Long

    /** Part of an audio file, played from [sourceStartUs] with [gain]. */
    public data class Clip(
        val uri: String,
        val sourceStartUs: Long,
        override val durationUs: Long,
        val sourceDurationUs: Long,
        val gain: SegmentGain,
    ) : AudioSegment {
        init {
            require(sourceStartUs >= 0 && durationUs > 0) { "Invalid audio range" }
            require(sourceDurationUs > 0) { "Unknown source length" }
        }

        val sourceEndUs: Long get() = sourceStartUs + durationUs
    }

    /** Silence. */
    public data class Gap(override val durationUs: Long) : AudioSegment {
        init {
            require(durationUs > 0) { "Gaps must have a length" }
        }
    }
}

/**
 * Things the export leaves out. The export still runs; the UI lists these
 * before it starts.
 */
public enum class ExportWarning {
    /** Video tracks beyond the main track (overlays) aren't exported. */
    EXTRA_TRACKS_IGNORED,

    /** Clip effects are saved in the project but not rendered yet. */
    EFFECTS_NOT_RENDERED,

    /** Audio that runs past the end of the picture is cut at the end of the video. */
    AUDIO_TRIMMED_TO_VIDEO,

    /** Some audio is turned down where sounds overlap, so the mix doesn't clip. */
    MIX_LOWERED_TO_PREVENT_CLIPPING,

    /** Some audio hasn't been analysed, so its peaks are assumed to be full scale where it overlaps other sound. */
    AUDIO_NOT_ANALYSED,
}

/** Why an export can't run at all. */
public enum class ExportBlocker {
    /** The main track has no clips. Audio-only export isn't supported. */
    NO_VIDEO,

    /** A clip's source file is missing, unreadable, or not in the project. */
    MEDIA_UNAVAILABLE,

    /** A clip reaches past the end of its source (the file probably changed). */
    SOURCE_RANGE_INVALID,
}

/**
 * The canonical render plan: everything a renderer needs, resolved from the
 * project (URIs, exact source ranges, order, gains, output format). Export
 * renders it with Transformer, and the editor preview plays the same plan
 * with CompositionPlayer, so both hear and see the same thing.
 *
 * @property video the main track, in order. Empty only for an audio-only
 *   preview; exports always have video.
 * @property audioTracks each audible audio track (music, voiceover), from 0
 *   to [durationUs] with gaps as silence. Muted tracks and tracks with
 *   nothing audible are left out.
 */
public data class ExportPlan(
    val settings: ExportSettings,
    val video: List<VideoSegment>,
    val audioTracks: List<List<AudioSegment>>,
    val warnings: Set<ExportWarning> = emptySet(),
) {
    init {
        require(video.isNotEmpty() || audioTracks.isNotEmpty()) { "A plan needs something to play" }
        require(audioTracks.all { it.isNotEmpty() && it.any { s -> s is AudioSegment.Clip } }) { "Audio tracks must contain sound" }
        val length = video.sumOf { it.durationUs }.takeIf { video.isNotEmpty() } ?: audioTracks.first().sumOf { it.durationUs }
        require(audioTracks.all { it.sumOf { s -> s.durationUs } == length }) { "Every audio track must match the plan's length" }
    }

    val durationUs: Long
        get() = if (video.isNotEmpty()) video.sumOf { it.durationUs } else audioTracks.first().sumOf { it.durationUs }

    val includeClipAudio: Boolean get() = video.any { it.hasAudio }

    val hasAudio: Boolean get() = includeClipAudio || audioTracks.isNotEmpty()
}

/** The result of planning: a plan, or the reasons there can't be one. */
public sealed interface ExportPlanResult {
    public data class Ready(val plan: ExportPlan) : ExportPlanResult

    /**
     * @property blockers why the export can't run.
     * @property unavailableMedia display names (or URIs) of the files behind
     *   [ExportBlocker.MEDIA_UNAVAILABLE] and [ExportBlocker.SOURCE_RANGE_INVALID].
     */
    public data class Blocked(val blockers: Set<ExportBlocker>, val unavailableMedia: List<String> = emptyList()) : ExportPlanResult
}
