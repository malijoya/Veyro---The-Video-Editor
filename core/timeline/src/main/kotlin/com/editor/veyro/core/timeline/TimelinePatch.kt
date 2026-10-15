package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.audio.TrackAudio
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId

/**
 * What one edit changed on one track.
 *
 * A clip that changed (trimmed, moved, repacked, new volume) appears in both
 * lists: its old value in [removed] and its new value in [added]. A split
 * adds a clip, a delete removes one, and moving a clip to another track
 * removes it here and adds it there.
 *
 * [oldAudio] and [newAudio] are set only when the track's own volume or mute
 * changed.
 */
public data class TrackPatch(
    val trackId: TrackId,
    val removed: List<Clip>,
    val added: List<Clip>,
    val oldAudio: TrackAudio? = null,
    val newAudio: TrackAudio? = null,
)

/**
 * An exact, reversible description of one edit: only the clips (and track
 * settings) it changed.
 *
 * History keeps these instead of whole timelines. A patch holds clip records
 * (IDs, times and references to the clip's effect and audio settings). It
 * never holds media: clips point to media by ID, so the files themselves are
 * never copied, and a trim on a 500-clip track stores only the clips that
 * moved.
 *
 * [applyTo] checks that the timeline is in the state the patch expects
 * (every clip to remove is there, unchanged) and returns null otherwise, so
 * a stale undo can never corrupt the timeline.
 */
public class TimelinePatch internal constructor(public val tracks: List<TrackPatch>) {

    public val isEmpty: Boolean get() = tracks.isEmpty()

    /** How many clip records this patch keeps. History budgets its memory with this. */
    public val clipRecordCount: Int get() = tracks.sumOf { it.removed.size + it.added.size }

    /** The patch that undoes this one. */
    public fun inverse(): TimelinePatch =
        TimelinePatch(tracks.map { TrackPatch(it.trackId, removed = it.added, added = it.removed, oldAudio = it.newAudio, newAudio = it.oldAudio) })

    /**
     * [timeline] with this patch applied, or null when the patch doesn't fit
     * it: a track is missing, a clip to remove is absent or different, track
     * settings differ, an added clip's ID is already in use, clips would
     * overlap, or the magnetic main track would be left with gaps.
     *
     * All removals happen before any addition, so a clip moving between
     * tracks is never on both at once.
     */
    public fun applyTo(timeline: Timeline): Timeline? {
        val updated = LinkedHashMap<TrackId, Pair<Track, List<Clip>>>()
        for (patch in tracks) {
            val track = timeline.track(patch.trackId) ?: return null
            if (patch.oldAudio != null && track.audio != patch.oldAudio) return null
            val removedIds = HashSet<ClipId>(patch.removed.size)
            for (clip in patch.removed) {
                if (track.clips.firstOrNull { it.id == clip.id } != clip) return null
                removedIds += clip.id
            }
            updated[track.id] = track to track.clips.filterNot { it.id in removedIds }
        }
        val result = try {
            val replaced = tracks.associate { patch ->
                val (track, kept) = updated.getValue(patch.trackId)
                patch.trackId to track.copy(
                    clips = (kept + patch.added).sortedBy { it.startUs },
                    audio = patch.newAudio ?: track.audio,
                )
            }
            timeline.copy(tracks = timeline.tracks.map { replaced[it.id] ?: it })
        } catch (e: IllegalArgumentException) {
            return null // Overlapping clips or a duplicate clip ID.
        }
        for (patch in tracks) {
            if (result.isMagnetic(patch.trackId) && TimelineEditor.hasGaps(result.track(patch.trackId)!!)) return null
        }
        return result
    }

    override fun equals(other: Any?): Boolean = other is TimelinePatch && tracks == other.tracks

    override fun hashCode(): Int = tracks.hashCode()

    override fun toString(): String = "TimelinePatch($tracks)"

    public companion object {
        public val EMPTY: TimelinePatch = TimelinePatch(emptyList())

        /**
         * The patch that turns [before] into [after]. Both must have the
         * same tracks: edits change clips and track settings, never the track
         * list. Unchanged tracks and clips are skipped, so the patch is as
         * small as the edit.
         */
        public fun between(before: Timeline, after: Timeline): TimelinePatch {
            require(before.tracks.map { it.id } == after.tracks.map { it.id }) { "An edit must not add, remove or reorder tracks" }
            val patches = before.tracks.zip(after.tracks).mapNotNull { (old, new) ->
                if (old === new) return@mapNotNull null
                val clipsChanged = old.clips != new.clips
                val audioChanged = old.audio != new.audio
                if (!clipsChanged && !audioChanged) return@mapNotNull null
                val newById = new.clips.associateBy { it.id }
                val oldById = old.clips.associateBy { it.id }
                TrackPatch(
                    trackId = old.id,
                    removed = if (clipsChanged) old.clips.filter { newById[it.id] != it } else emptyList(),
                    added = if (clipsChanged) new.clips.filter { oldById[it.id] != it } else emptyList(),
                    oldAudio = if (audioChanged) old.audio else null,
                    newAudio = if (audioChanged) new.audio else null,
                )
            }
            return TimelinePatch(patches)
        }
    }
}
