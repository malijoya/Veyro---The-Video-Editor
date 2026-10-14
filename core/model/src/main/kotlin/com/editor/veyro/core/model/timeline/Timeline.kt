package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.project.MediaId

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
 * All times are integer microseconds:
 * - [startUs] is where the clip begins on the timeline.
 * - [sourceInUs] is where it begins inside the source file.
 * - [durationUs] is how long it lasts on the timeline. Speed changes come later.
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
 * The arrangement of clips in time. Tracks are listed top to bottom. The
 * first [TrackKind.VIDEO] track is the main track.
 *
 * This is the first piece of the timeline core. Editing commands, undo and
 * storage in the project document come with the timeline milestone (M1).
 */
public data class Timeline(val tracks: List<Track>) {
    init {
        require(tracks.map { it.id }.toSet().size == tracks.size) { "Track IDs must be unique" }
        val clipIds = tracks.flatMap { track -> track.clips.map { it.id } }
        require(clipIds.toSet().size == clipIds.size) { "Clip IDs must be unique" }
    }

    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0

    val mainTrack: Track? get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    public fun findClip(id: ClipId): Clip? = tracks.firstNotNullOfOrNull { track -> track.clips.firstOrNull { it.id == id } }

    public fun trackOf(id: ClipId): Track? = tracks.firstOrNull { track -> track.clips.any { it.id == id } }

    public companion object {
        public val EMPTY: Timeline = Timeline(emptyList())
    }
}
