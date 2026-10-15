package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.audio.ClipAudio
import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
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

    /**
     * Text and stickers drawn over the picture. Every clip carries an
     * [Overlay]. Overlay tracks keep gaps, like audio tracks.
     *
     * Stacking: overlay tracks are listed above the main track, and the one
     * listed first is drawn on top. Overlays on the same track never overlap
     * in time, so the track order is the whole stacking order.
     */
    OVERLAY,
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
 * [durationUs] is how long the photo stays on screen. Text and stickers
 * behave like photos.
 *
 * [mediaId] is the source file. Only text overlays have none: their content
 * is the text itself.
 */
public data class Clip(
    val id: ClipId,
    val mediaId: MediaId?,
    val startUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
    /** Effects applied to this clip, in order. Edits (trim, split, move) carry them along unchanged. */
    val effects: List<ClipEffect> = emptyList(),
    /** Volume, mute and fades for the clip's sound. Ignored for photos, and for videos without an audio stream. */
    val audio: ClipAudio = ClipAudio.DEFAULT,
    /** The text or sticker this clip shows, on overlay tracks. Null on every other track. */
    val overlay: Overlay? = null,
) {
    init {
        require((mediaId == null) == (overlay?.content is OverlayContent.Text)) {
            "Clip ${id.value}: text overlays have no media, and every other clip needs media"
        }
        require(startUs >= 0) { "startUs must not be negative" }
        require(sourceInUs >= 0) { "sourceInUs must not be negative" }
        require(durationUs > 0) { "durationUs must be positive" }
        require(effects.map { it.id }.toSet().size == effects.size) { "Effect IDs must be unique within a clip" }
    }

    val endUs: Long get() = startUs + durationUs

    /** End of the played part of the source file (exclusive). */
    val sourceOutUs: Long get() = sourceInUs + durationUs

    /** Whether [timeUs] falls in `[startUs, endUs)`. */
    public operator fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}

/**
 * An effect attached to a clip, in a portable form: a [type] name and numeric
 * [params]. The project format stores effects now. Rendering them and an
 * effects UI come with the effects milestone (M7), so for now effects are
 * only saved, loaded and carried through edits.
 */
public data class ClipEffect(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, Double> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Effect id must not be blank" }
        require(type.isNotBlank()) { "Effect type must not be blank" }
        require(params.values.all { it.isFinite() }) { "Effect parameters must be finite" }
    }
}

/**
 * An ordered lane of clips. Clips never overlap and are sorted by start time.
 *
 * @property audio volume and mute for the whole track, applied on top of each
 *   clip's own. On the main track it controls the videos' own sound.
 * @property role for audio tracks: music or voiceover. Null for video tracks.
 */
public data class Track(
    val id: TrackId,
    val kind: TrackKind,
    val clips: List<Clip>,
    val audio: TrackAudio = TrackAudio.DEFAULT,
    val role: AudioRole? = null,
) {
    init {
        clips.zipWithNext().forEach { (a, b) ->
            require(a.endUs <= b.startUs) { "Clips ${a.id.value} and ${b.id.value} overlap or are out of order" }
        }
        // Overlay tracks hold only overlays, and overlays live only there.
        clips.forEach { clip ->
            require((clip.overlay != null) == (kind == TrackKind.OVERLAY)) { "Clip ${clip.id.value} doesn't belong on a ${kind.name} track" }
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
 * [TrackKind.OVERLAY] tracks are drawn over the picture, the first listed on
 * top (see [overlayTracks]).
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

    /** Overlay tracks, top layer first. */
    val overlayTracks: List<Track> get() = tracks.filter { it.kind == TrackKind.OVERLAY }

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
