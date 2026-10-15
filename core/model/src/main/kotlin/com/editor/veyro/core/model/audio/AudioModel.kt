package com.editor.veyro.core.model.audio

/**
 * A point on a clip's volume automation, for future keyframe editing.
 *
 * Keyframes are anchored to **source** time ([sourceTimeUs] is a position in
 * the clip's media file), not to the clip's edges. Trimming or splitting a
 * clip therefore leaves every keyframe on the same moment of audio, and the
 * split parts simply see the keyframes inside their own source range.
 *
 * @property gain linear amplitude factor, 0..1.
 */
public data class GainKeyframe(
    val sourceTimeUs: Long,
    val gain: Float,
    /** How the gain moves from this keyframe to the next one. */
    val interpolation: GainInterpolation = GainInterpolation.LINEAR,
) {
    init {
        require(sourceTimeUs >= 0) { "Keyframe time must not be negative" }
        require(gain.isFinite() && gain in 0f..1f) { "Keyframe gain must be in 0..1: $gain" }
    }
}

public enum class GainInterpolation {
    /** Straight line in amplitude to the next keyframe. */
    LINEAR,

    /** Holds this value until the next keyframe. */
    HOLD,
}

/**
 * A clip's own sound settings. Applies to any clip whose media has audio
 * (video with sound, music, voiceover). Photos have none.
 *
 * @property volume linear amplitude factor, 0..1 (0% to 100%). There is no
 *   boost above 100%, so a single clip can never be pushed into clipping.
 * @property muted silences the clip without losing [volume].
 * @property fadeInUs ramp from silence at the clip's start, in timeline µs.
 * @property fadeOutUs ramp to silence at the clip's end.
 * @property keyframes volume automation, sorted by source time. Not editable
 *   in the UI yet, but stored, carried through edits, and rendered.
 */
public data class ClipAudio(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val keyframes: List<GainKeyframe> = emptyList(),
) {
    init {
        require(volume.isFinite() && volume in 0f..1f) { "Volume must be in 0..1: $volume" }
        require(fadeInUs >= 0 && fadeOutUs >= 0) { "Fades must not be negative" }
        require(keyframes.zipWithNext().all { (a, b) -> a.sourceTimeUs < b.sourceTimeUs }) { "Keyframes must be sorted by time, without duplicates" }
    }

    /** Whether this clip contributes no sound at all. */
    val isSilent: Boolean get() = muted || volume == 0f

    public companion object {
        public val DEFAULT: ClipAudio = ClipAudio()
    }
}

/** What an audio track is for. It sets the label and where new audio goes. */
public enum class AudioRole { MUSIC, VOICEOVER }

/**
 * A whole track's sound settings, applied on top of each clip's own.
 *
 * @property volume linear amplitude factor, 0..1.
 */
public data class TrackAudio(
    val volume: Float = 1f,
    val muted: Boolean = false,
) {
    init {
        require(volume.isFinite() && volume in 0f..1f) { "Volume must be in 0..1: $volume" }
    }

    val isSilent: Boolean get() = muted || volume == 0f

    public companion object {
        public val DEFAULT: TrackAudio = TrackAudio()
    }
}
