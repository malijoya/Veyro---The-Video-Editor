package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackId

/**
 * The clips one edit changed on one track.
 *
 * A clip that changed (trimmed, moved, repacked) appears in both lists:
 * its old value in [removed] and its new value in [added]. A split adds a
 * clip, and a delete removes one.
 */
public data class TrackPatch(
    val trackId: TrackId,
    val removed: List<Clip>,
    val added: List<Clip>,
)

/**
 * An exact, reversible description of one edit: only the clips it changed.
 *
 * History keeps these instead of whole timelines. A patch holds clip records
 * (IDs, times and a reference to the clip's effect list). It never holds
 * media: clips point to media by ID, so the files themselves are never
 * copied, and a trim on a 500-clip track stores only the clips that moved.
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
    public fun inverse(): TimelinePatch = TimelinePatch(tracks.map { TrackPatch(it.trackId, removed = it.added, added = it.removed) })

    /**
     * [timeline] with this patch applied, or null when the patch doesn't fit
     * it: a track is missing, a clip to remove is absent or different, an
     * added clip's ID is already in use, clips would overlap, or the magnetic
     * main track would be left with gaps.
     */
    public fun applyTo(timeline: Timeline): Timeline? {
        var result = timeline
        for (patch in tracks) {
            val track = result.track(patch.trackId) ?: return null
            val removedIds = HashSet<ClipId>(patch.removed.size)
            for (clip in patch.removed) {
                if (track.clips.firstOrNull { it.id == clip.id } != clip) return null
                removedIds += clip.id
            }
            val kept = track.clips.filterNot { it.id in removedIds }
            val clips = (kept + patch.added).sortedBy { it.startUs }
            val updated = try {
                result.withTrack(track.copy(clips = clips))
            } catch (e: IllegalArgumentException) {
                return null // Overlapping clips or a duplicate clip ID.
            }
            if (updated.isMagnetic(track.id) && TimelineEditor.hasGaps(updated.track(track.id)!!)) return null
            result = updated
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
         * same tracks: edits change clips, never the track list. Unchanged
         * tracks and clips are skipped, so the patch is as small as the edit.
         */
        public fun between(before: Timeline, after: Timeline): TimelinePatch {
            require(before.tracks.map { it.id } == after.tracks.map { it.id }) { "An edit must not add, remove or reorder tracks" }
            val patches = before.tracks.zip(after.tracks).mapNotNull { (old, new) ->
                if (old === new || old.clips == new.clips) return@mapNotNull null
                val newById = new.clips.associateBy { it.id }
                val oldById = old.clips.associateBy { it.id }
                TrackPatch(
                    trackId = old.id,
                    removed = old.clips.filter { newById[it.id] != it },
                    added = new.clips.filter { oldById[it.id] != it },
                )
            }
            return TimelinePatch(patches)
        }
    }
}
