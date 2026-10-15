package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.time.FrameRate

/** Stable identifier of a clip. */
@JvmInline
public value class ClipId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ClipId must not be blank" }
    }
}

/** Stable identifier of a track. */
@JvmInline
public value class TrackId(public val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId must not be blank" }
    }
}

public enum class TrackKind {
    /** Pictures: video and photo clips. */
    VIDEO,

    /** Sound only: music, voice-over, effects. */
    AUDIO,
}

/**
 * A stretch of a source media item placed on a track.
 *
 * All times are integer microseconds, and two ranges describe a clip:
 * - Timeline range `[startUs, endUs)`: where the clip sits in the edit.
 * - Source range `[sourceInUs, sourceOutUs)`: which part of the file plays.
 *
 * At normal speed, which is all there is so far, both ranges have length
 * [durationUs]. Photos have no source time: [sourceInUs] is always 0, and
 * [durationUs] is how long the photo stays on screen.
 */
public data class Clip(
    val id: ClipId,
    val mediaId: MediaId,
    val startUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
) {
    init {
        require(startUs >= 0) { "startUs must not be negative" }
        require(sourceInUs >= 0) { "sourceInUs must not be negative" }
        require(durationUs > 0) { "durationUs must be positive" }
    }

    val endUs: Long get() = startUs + durationUs

    /** End of the played part of the source file (exclusive). */
    val sourceOutUs: Long get() = sourceInUs + durationUs

    /** Whether [timeUs] falls in `[startUs, endUs)`. */
    public operator fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}

/** An ordered lane of clips. Clips never overlap and are sorted by start time. */
public data class Track(
    val id: TrackId,
    val kind: TrackKind,
    val clips: List<Clip>,
) {
    init {
        clips.zipWithNext().forEach { (a, b) ->
            require(a.endUs <= b.startUs) { "Clips ${a.id.value} and ${b.id.value} overlap or are out of order" }
        }
    }

    val endUs: Long get() = clips.lastOrNull()?.endUs ?: 0

    /** The clip at [timeUs], or null in a gap or past the end. */
    public fun clipAt(timeUs: Long): Clip? = clips.firstOrNull { timeUs in it }
}

/**
 * The arrangement of clips in time. Tracks are listed top to bottom.
 *
 * The first [TrackKind.VIDEO] track is the main track, and it is
 * *magnetic*: its clips always sit back to back from 0, so moves become
 * reorders and trims or deletes close the gap. Other tracks keep their gaps.
 *
 * [frameRate] is the edit's frame grid. Snapping and the minimum clip
 * length use it.
 */
public data class Timeline(
    val tracks: List<Track>,
    val frameRate: FrameRate = FrameRate.FPS_30,
) {
    init {
        require(tracks.map { it.id }.toSet().size == tracks.size) { "Track IDs must be unique" }
        val clipIds = tracks.flatMap { track -> track.clips.map { it.id } }
        require(clipIds.toSet().size == clipIds.size) { "Clip IDs must be unique" }
    }

    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0

    val mainTrack: Track? get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    /** No clip may be shorter than one frame of [frameRate]. */
    val minClipDurationUs: Long get() = frameRate.frameToUs(1)

    public fun isMagnetic(trackId: TrackId): Boolean = mainTrack?.id == trackId

    public fun track(id: TrackId): Track? = tracks.firstOrNull { it.id == id }

    /** A copy with [track] replacing the track with the same ID. */
    public fun withTrack(track: Track): Timeline = copy(tracks = tracks.map { if (it.id == track.id) track else it })

    public fun findClip(id: ClipId): Clip? = tracks.firstNotNullOfOrNull { track -> track.clips.firstOrNull { it.id == id } }

    public fun trackOf(id: ClipId): Track? = tracks.firstOrNull { track -> track.clips.any { it.id == id } }

    public companion object {
        public val EMPTY: Timeline = Timeline(emptyList())
    }
}
