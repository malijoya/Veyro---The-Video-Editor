package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackId

/**
 * One edit to a [Timeline]. All times are timeline microseconds.
 *
 * Commands take the time the user aimed at. Snapping (to other edges, the
 * playhead or the frame grid) is done before building the command, with
 * [Snapper]. The engine then clamps that time to what is legal (neighbours,
 * source bounds, minimum length) and applies it.
 */
public sealed interface EditCommand {
    /**
     * Moves a clip so it starts near [toStartUs].
     * - On the magnetic main track, the clip is reordered to the slot nearest
     *   its new position, and the track stays packed.
     * - On other tracks, it moves to the nearest position where it fits
     *   without overlapping.
     */
    public data class Move(val clipId: ClipId, val toStartUs: Long) : EditCommand

    /** Moves the clip's left edge to [newStartUs], changing its source in-point by the same amount. */
    public data class TrimStart(val clipId: ClipId, val newStartUs: Long) : EditCommand

    /** Moves the clip's right edge to [newEndUs], changing its source out-point by the same amount. */
    public data class TrimEnd(val clipId: ClipId, val newEndUs: Long) : EditCommand

    /**
     * Cuts a clip in two at [atUs]. The parts are adjacent on the timeline and
     * contiguous in the source, so playback across the cut is unchanged.
     */
    public data class Split(val clipId: ClipId, val atUs: Long) : EditCommand

    /**
     * Removes a clip.
     * - [closeGap] shifts later clips on the same track left by its length.
     * - The magnetic main track always closes the gap.
     */
    public data class Delete(val clipId: ClipId, val closeGap: Boolean) : EditCommand

    /** Packs a track's clips back to back from 0, removing every gap. */
    public data class CloseGaps(val trackId: TrackId) : EditCommand

    /**
     * Sets a clip's volume, mute and fades. Fades longer than the clip are
     * shortened to it. Rejected for clips without sound (photos, silent
     * videos).
     */
    public data class SetClipAudio(val clipId: ClipId, val audio: ClipAudio) : EditCommand

    /** Sets a whole track's volume and mute. */
    public data class SetTrackAudio(val trackId: TrackId, val audio: TrackAudio) : EditCommand

    /**
     * Moves a clip to another track of the same kind (for example from Music
     * to Voiceover), at the free position nearest [toStartUs]. The old track
     * keeps the gap. Only between tracks that keep gaps; the magnetic main
     * track reorders with [Move] instead.
     */
    public data class MoveToTrack(val clipId: ClipId, val trackId: TrackId, val toStartUs: Long) : EditCommand
}

/** Why the engine refused an edit. The UI turns these into messages. */
public enum class EditRejection {
    CLIP_NOT_FOUND,
    TRACK_NOT_FOUND,

    /** The clip's media is unknown, so its source limits can't be checked. */
    MEDIA_NOT_FOUND,

    /** A split point is outside the clip. */
    OUTSIDE_CLIP,

    /** A split would leave a part shorter than one frame. */
    TOO_CLOSE_TO_EDGE,

    /** The edit is valid but changes nothing (for example closing gaps on a track without gaps). */
    NO_CHANGE,

    /** An audio edit on a clip whose media has no sound. */
    NO_AUDIO,

    /** The clip can't go on that track (different kind, or the magnetic main track). */
    INCOMPATIBLE_TRACK,
}

public sealed interface EditResult {
    /**
     * @property timeline the edited timeline.
     * @property affectedClipId the clip to keep selected. For a split, it's
     *   the right-hand part, matching where the playhead is.
     */
    public data class Applied(val timeline: Timeline, val affectedClipId: ClipId?) : EditResult

    public data class Rejected(val reason: EditRejection) : EditResult
}

/**
 * What the engine needs to know about each media item to keep edits within
 * the source.
 *
 * @property hasAudio whether the file has an audio stream. Audio edits on
 *   clips without one are rejected.
 */
public data class MediaBounds(val kind: MediaKind, val durationUs: Long, val hasAudio: Boolean = kind != MediaKind.IMAGE) {
    /** Photos have no source time, so they can be any length. */
    val isStill: Boolean get() = kind == MediaKind.IMAGE
}

/**
 * Inputs an edit needs besides the timeline.
 *
 * [newClipId] creates IDs for split-off clips. It's injected so tests are
 * deterministic.
 */
public class EditContext(
    public val media: Map<MediaId, MediaBounds>,
    public val newClipId: () -> ClipId,
)
