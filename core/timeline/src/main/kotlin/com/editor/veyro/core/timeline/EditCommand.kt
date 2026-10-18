package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.transform.ClipTransform
import com.editor.veyro.core.model.transition.Transition

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

    /**
     * Adds a text or sticker overlay over `[startUs, startUs + durationUs)`,
     * stacked above every overlay it overlaps in time. It goes on an existing
     * overlay track when one has room at that layer; otherwise a new overlay
     * track is inserted.
     *
     * [mediaId] is the sticker's image, and must be null for text.
     * [animation] gives it keyframes from the start (a duplicate keeps the
     * original's); only placement keyframes apply to overlays.
     */
    public data class AddOverlay(
        val overlay: Overlay,
        val mediaId: MediaId?,
        val startUs: Long,
        val durationUs: Long,
        val animation: ClipAnimation = ClipAnimation.NONE,
    ) : EditCommand

    /**
     * Replaces an overlay's content and placement: text, style, position,
     * size, rotation, opacity. Text stays text and a sticker stays a sticker.
     * A non-null [animation] replaces its keyframes in the same edit.
     */
    public data class SetOverlay(val clipId: ClipId, val overlay: Overlay, val animation: ClipAnimation? = null) : EditCommand

    /** Changes where an overlay sits in the stack of overlays it overlaps in time. */
    public data class ArrangeLayer(val clipId: ClipId, val move: LayerMove) : EditCommand

    /**
     * Replaces a video or photo clip's placement on the canvas: position,
     * scale, rotation, anchor, crop, fit and opacity. A non-null [animation]
     * replaces its keyframes in the same edit.
     */
    public data class SetClipTransform(val clipId: ClipId, val transform: ClipTransform, val animation: ClipAnimation? = null) : EditCommand

    /**
     * Replaces a clip's keyframes (adding, moving, easing or deleting them).
     * Placement keyframes are allowed on video and overlay tracks; effect
     * parameter keyframes need the effect to be on the clip.
     */
    public data class SetClipAnimation(val clipId: ClipId, val animation: ClipAnimation) : EditCommand

    /**
     * Sets (or, with null, removes) the transition at the cut after
     * [clipId] on the main track. The clip must be followed by another clip.
     */
    public data class SetTransition(val clipId: ClipId, val transition: Transition?) : EditCommand

    /**
     * Makes room for the transition after [clipId] when its clips lack
     * media past their edges: trims the outgoing clip's end and the
     * incoming clip's start, in whole frames, by just enough that the
     * transition plays at its requested length (or as long as the clips'
     * lengths allow). The cut moves earlier and the main track gets shorter,
     * exactly as with two ordinary trims; it is one undoable edit.
     */
    public data class FitTransition(val clipId: ClipId) : EditCommand
}

/**
 * Stacking moves. They only consider overlays that overlap the clip in
 * time; overlays elsewhere on the timeline don't affect what is visible.
 */
public enum class LayerMove {
    /** One step up: above the nearest overlapping overlay that is above it. */
    FORWARD,

    /** One step down: below the nearest overlapping overlay that is below it. */
    BACKWARD,

    /** Above every overlapping overlay. */
    TO_FRONT,

    /** Below every overlapping overlay (still above the main video). */
    TO_BACK,
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

    /** An overlay edit on a clip that isn't an overlay, or one that would turn text into a sticker (or back). */
    NOT_AN_OVERLAY,

    /** A sticker whose media isn't an image, or a text overlay given media. */
    UNSUPPORTED_MEDIA,

    /** A transform for a clip that isn't a picture on a video track (audio, text, stickers). */
    NOT_A_PICTURE,

    /** Keyframes for something the clip doesn't have (placement on a sound clip, a missing effect). */
    NOT_ANIMATABLE,

    /** A transition on a clip that isn't followed by another clip on the main track. */
    NO_CUT,
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

    public companion object {
        /** Text overlays: no file, no sound, and no source time, like a photo. */
        public val TEXT: MediaBounds = MediaBounds(MediaKind.IMAGE, durationUs = 0, hasAudio = false)
    }
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
