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
 * The track list before and after an edit that added, removed or reordered
 * tracks (adding an overlay lane, or removing an empty one). Each list holds
 * track shells: ID, kind, role and track settings, without clips. The clips
 * themselves move through the [TrackPatch]es.
 */
public data class TrackLayout(val before: List<Track>, val after: List<Track>) {
    init {
        require((before + after).all { it.clips.isEmpty() }) { "Track shells carry no clips" }
    }
}

/**
 * An exact, reversible description of one edit: only the clips (and track
 * settings) it changed, plus the track list when the edit changed it
 * ([layout]).
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
public class TimelinePatch internal constructor(
    public val tracks: List<TrackPatch>,
    public val layout: TrackLayout? = null,
) {

    public val isEmpty: Boolean get() = tracks.isEmpty() && layout == null

    /** How many clip (and track shell) records this patch keeps. History budgets its memory with this. */
    public val clipRecordCount: Int
        get() = tracks.sumOf { it.removed.size + it.added.size } + (layout?.let { it.before.size + it.after.size } ?: 0)

    /** The patch that undoes this one. */
    public fun inverse(): TimelinePatch = TimelinePatch(
        tracks.map { TrackPatch(it.trackId, removed = it.added, added = it.removed, oldAudio = it.newAudio, newAudio = it.oldAudio) },
        layout?.let { TrackLayout(before = it.after, after = it.before) },
    )

    /**
     * [timeline] with this patch applied, or null when the patch doesn't fit
     * it: a track is missing, a clip to remove is absent or different, track
     * settings differ, an added clip's ID is already in use, clips would
     * overlap, or the magnetic main track would be left with gaps.
     *
     * All removals happen before any addition, so a clip moving between
     * tracks is never on both at once. With a [layout], the track list must
     * match its `before` list; tracks it removes must be empty once the
     * removals are done, and tracks it adds start empty.
     */
    public fun applyTo(timeline: Timeline): Timeline? {
        val trackLayout = layout
        if (trackLayout != null && timeline.tracks.map { it.id } != trackLayout.before.map { it.id }) return null
        val created = trackLayout?.after.orEmpty().filter { shell -> trackLayout?.before.orEmpty().none { it.id == shell.id } }.associateBy { it.id }

        // Removals, on the tracks as they are now.
        val kept = LinkedHashMap<TrackId, Track>()
        for (patch in tracks) {
            val track = timeline.track(patch.trackId)
            if (track == null) {
                // Only a track this patch creates may be missing, and there is nothing to remove from it yet.
                if (patch.trackId !in created || patch.removed.isNotEmpty() || patch.oldAudio != null) return null
                continue
            }
            if (patch.oldAudio != null && track.audio != patch.oldAudio) return null
            val removedIds = HashSet<ClipId>(patch.removed.size)
            for (clip in patch.removed) {
                if (track.clips.firstOrNull { it.id == clip.id } != clip) return null
                removedIds += clip.id
            }
            kept[track.id] = track.copy(clips = track.clips.filterNot { it.id in removedIds })
        }

        // The new track list: existing tracks as they now are, created ones empty.
        val current = timeline.tracks.map { kept[it.id] ?: it }
        val arranged = if (trackLayout == null) {
            current
        } else {
            val byId = current.associateBy { it.id }
            val dropped = current.filter { track -> trackLayout.after.none { it.id == track.id } }
            if (dropped.any { it.clips.isNotEmpty() }) return null
            trackLayout.after.map { shell -> byId[shell.id] ?: shell }
        }

        // Additions.
        val result = try {
            val patchById = tracks.associateBy { it.trackId }
            timeline.copy(
                tracks = arranged.map { track ->
                    val patch = patchById[track.id] ?: return@map track
                    track.copy(
                        clips = (track.clips + patch.added).sortedBy { it.startUs },
                        audio = patch.newAudio ?: track.audio,
                    )
                },
            )
        } catch (e: IllegalArgumentException) {
            return null // Overlapping clips, a duplicate clip ID, or a clip on the wrong kind of track.
        }
        for (patch in tracks) {
            val track = result.track(patch.trackId) ?: continue // A track this patch removed.
            if (result.isMagnetic(patch.trackId) && TimelineEditor.hasGaps(track)) return null
        }
        return result
    }

    override fun equals(other: Any?): Boolean = other is TimelinePatch && tracks == other.tracks && layout == other.layout

    override fun hashCode(): Int = tracks.hashCode() * 31 + layout.hashCode()

    override fun toString(): String = "TimelinePatch($tracks, layout=$layout)"

    public companion object {
        public val EMPTY: TimelinePatch = TimelinePatch(emptyList())

        /**
         * The patch that turns [before] into [after]. Unchanged tracks and
         * clips are skipped, so the patch is as small as the edit. When the
         * track list itself changed (an overlay lane added or removed), the
         * patch also records both lists as shells, and the clips of added or
         * removed tracks as added or removed clips.
         */
        public fun between(before: Timeline, after: Timeline): TimelinePatch {
            val layout = if (before.tracks.map { it.id } == after.tracks.map { it.id }) {
                null
            } else {
                TrackLayout(before.tracks.map { it.shell() }, after.tracks.map { it.shell() })
            }
            val afterById = after.tracks.associateBy { it.id }
            val beforeById = before.tracks.associateBy { it.id }
            val gone = before.tracks.filter { it.id !in afterById }.map { TrackPatch(it.id, removed = it.clips, added = emptyList()) }
            val new = after.tracks.filter { it.id !in beforeById }.map { TrackPatch(it.id, removed = emptyList(), added = it.clips) }
            val shared = before.tracks.mapNotNull { old -> afterById[old.id]?.let { old to it } }
            val patches = shared.mapNotNull { (old, new) ->
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
            return TimelinePatch(patches + gone.filter { it.removed.isNotEmpty() } + new.filter { it.added.isNotEmpty() }, layout)
        }

        /** The track without its clips: what [TrackLayout] keeps. */
        private fun Track.shell(): Track = if (clips.isEmpty()) this else copy(clips = emptyList())
    }
}
