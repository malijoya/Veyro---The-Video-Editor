package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.animation.ClipAnimation
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import kotlin.math.abs

/**
 * Applies [EditCommand]s to a [Timeline]. Pure and deterministic, with no
 * Android or UI dependency.
 *
 * Every result is a valid timeline. Requested times are clamped to what is
 * legal:
 * - No clip is shorter than one frame ([Timeline.minClipDurationUs]).
 * - Video and audio clips stay within their source: `0 <= sourceIn` and
 *   `sourceOut <= media duration`. Photos have no source limit.
 * - Clips never overlap, and nothing starts before 0.
 * - The magnetic main track stays packed from 0 after every edit.
 * - Overlay tracks that end up empty are removed, so the stack of overlay
 *   lanes never fills with blank rows. (Undo brings them back: history
 *   records track changes too.)
 *
 * A request beyond a limit stops at the limit (a drag stops at the
 * neighbour). Only edits that can't happen at all are rejected.
 */
public object TimelineEditor {

    public fun apply(timeline: Timeline, command: EditCommand, context: EditContext): EditResult {
        val result = when (command) {
            is EditCommand.TrimStart -> trimStart(timeline, command, context)
            is EditCommand.TrimEnd -> trimEnd(timeline, command, context)
            is EditCommand.Split -> split(timeline, command, context)
            is EditCommand.Move -> move(timeline, command)
            is EditCommand.Delete -> delete(timeline, command)
            is EditCommand.CloseGaps -> closeGaps(timeline, command)
            is EditCommand.SetClipAudio -> setClipAudio(timeline, command, context)
            is EditCommand.SetTrackAudio -> setTrackAudio(timeline, command)
            is EditCommand.MoveToTrack -> moveToTrack(timeline, command)
            is EditCommand.AddOverlay -> addOverlay(timeline, command, context)
            is EditCommand.SetOverlay -> setOverlay(timeline, command)
            is EditCommand.ArrangeLayer -> arrangeLayer(timeline, command)
            is EditCommand.SetClipTransform -> setClipTransform(timeline, command, context)
            is EditCommand.SetClipAnimation -> setClipAnimation(timeline, command)
        }
        return if (result is EditResult.Applied) result.copy(timeline = withoutEmptyOverlayTracks(result.timeline)) else result
    }

    /** The bounds an edit checks a clip against. Text overlays have no file and behave like photos. */
    private fun boundsOf(clip: Clip, context: EditContext): MediaBounds? =
        clip.mediaId?.let { context.media[it] } ?: MediaBounds.TEXT.takeIf { clip.mediaId == null }

    // ---- Trim ----

    private fun trimStart(timeline: Timeline, command: EditCommand.TrimStart, context: EditContext): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val bounds = boundsOf(clip, context) ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
        val magnetic = timeline.isMagnetic(track.id)
        val minDuration = timeline.minClipDurationUs

        // How far the left edge moves. Positive shortens the clip, negative lengthens it.
        var delta = command.newStartUs - clip.startUs
        delta = minOf(delta, clip.durationUs - minDuration) // Keep at least one frame.
        if (!bounds.isStill) delta = maxOf(delta, -clip.sourceInUs) // Can't reveal footage before the file starts.
        if (!magnetic) {
            val previousEnd = track.clips.getOrNull(index - 1)?.endUs ?: 0L
            delta = maxOf(delta, previousEnd - clip.startUs) // Can't overlap the previous clip or start before 0.
        }

        val trimmed = clip.copy(
            // On the magnetic track the left edge stays put. The clip shrinks and the track repacks.
            startUs = if (magnetic) clip.startUs else clip.startUs + delta,
            sourceInUs = if (bounds.isStill) 0 else clip.sourceInUs + delta,
            durationUs = clip.durationUs - delta,
            // Clip-local time now starts `delta` later in the content, so keyframes move back by it and stay on their moment.
            animation = clip.animation.shifted(-delta),
        )
        return replaceClip(timeline, track, index, trimmed)
    }

    private fun trimEnd(timeline: Timeline, command: EditCommand.TrimEnd, context: EditContext): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val bounds = boundsOf(clip, context) ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
        val magnetic = timeline.isMagnetic(track.id)

        var newDuration = command.newEndUs - clip.startUs
        newDuration = maxOf(newDuration, timeline.minClipDurationUs)
        if (!bounds.isStill) newDuration = minOf(newDuration, bounds.durationUs - clip.sourceInUs) // Can't pass the file's end.
        if (!magnetic) {
            track.clips.getOrNull(index + 1)?.let { next -> newDuration = minOf(newDuration, next.startUs - clip.startUs) }
        }
        // A clip already longer than its (re-probed) source keeps at least its minimum length.
        newDuration = maxOf(newDuration, minOf(timeline.minClipDurationUs, clip.durationUs))
        return replaceClip(timeline, track, index, clip.copy(durationUs = newDuration))
    }

    // ---- Split ----

    private fun split(timeline: Timeline, command: EditCommand.Split, context: EditContext): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val bounds = boundsOf(clip, context) ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
        val at = command.atUs
        if (at <= clip.startUs || at >= clip.endUs) return EditResult.Rejected(EditRejection.OUTSIDE_CLIP)
        val minDuration = timeline.minClipDurationUs
        if (at - clip.startUs < minDuration || clip.endUs - at < minDuration) {
            return EditResult.Rejected(EditRejection.TOO_CLOSE_TO_EDGE)
        }

        val leftDuration = at - clip.startUs
        // The fade-in stays at the start and the fade-out at the end. Keyframes are in source time, so each part keeps its own.
        // Each part keeps the keyframes that shape its own range (re-based to its own start), so playback across the cut is unchanged.
        val left = clip.copy(durationUs = leftDuration, audio = clip.audio.copy(fadeOutUs = 0), animation = clip.animation.part(0, leftDuration))
        val right = Clip(
            id = context.newClipId(),
            mediaId = clip.mediaId,
            startUs = at,
            // The right part continues the source exactly where the left part stops.
            sourceInUs = if (bounds.isStill) 0 else clip.sourceInUs + leftDuration,
            durationUs = clip.endUs - at,
            effects = clip.effects, // Both parts keep the clip's effects.
            audio = clip.audio.copy(fadeInUs = 0),
            overlay = clip.overlay, // Both parts show the same text or sticker.
            transform = clip.transform, // And sit in the same place.
            animation = clip.animation.part(leftDuration, clip.durationUs),
        )
        val clips = track.clips.toMutableList().apply {
            set(index, left)
            add(index + 1, right)
        }
        return EditResult.Applied(timeline.withTrack(track.copy(clips = clips)), right.id)
    }

    // ---- Move ----

    private fun move(timeline: Timeline, command: EditCommand.Move): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val others = track.clips.toMutableList().apply { removeAt(index) }

        if (timeline.isMagnetic(track.id)) {
            // Reorder: insert before the first clip whose middle (in the packed track without this clip) is at or after the dragged clip's middle.
            val packedOthers = pack(others)
            val draggedMiddle = command.toStartUs + clip.durationUs / 2
            val slot = packedOthers.indexOfFirst { it.startUs + it.durationUs / 2 >= draggedMiddle }.let { if (it < 0) others.size else it }
            val reordered = others.toMutableList().apply { add(slot, clip) }
            return EditResult.Applied(timeline.withTrack(track.copy(clips = pack(reordered))), clip.id)
        }

        val start = nearestFreeStart(others, command.toStartUs.coerceAtLeast(0), clip.durationUs)
        val moved = clip.copy(startUs = start)
        val clips = (others + moved).sortedBy { it.startUs }
        return EditResult.Applied(timeline.withTrack(track.copy(clips = clips)), clip.id)
    }

    /**
     * The start closest to [desired] where a clip of [duration] fits between
     * [others] (sorted, non-overlapping). The space after the last clip is
     * unbounded, so a slot always exists.
     */
    internal fun nearestFreeStart(others: List<Clip>, desired: Long, duration: Long): Long {
        var best = Long.MAX_VALUE
        var bestDistance = Long.MAX_VALUE
        var gapStart = 0L
        for (index in 0..others.size) {
            val gapEnd = others.getOrNull(index)?.startUs ?: Long.MAX_VALUE
            if (gapEnd - gapStart >= duration) {
                val candidate = desired.coerceIn(gapStart, gapEnd - duration)
                val distance = abs(candidate - desired)
                if (distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
            }
            gapStart = others.getOrNull(index)?.endUs ?: break
        }
        return best
    }

    // ---- Delete and gaps ----

    private fun delete(timeline: Timeline, command: EditCommand.Delete): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val remaining = track.clips.toMutableList().apply { removeAt(index) }
        val clips = when {
            timeline.isMagnetic(track.id) -> pack(remaining)
            command.closeGap -> remaining.mapIndexed { i, c -> if (i >= index) c.copy(startUs = c.startUs - clip.durationUs) else c }
            else -> remaining
        }
        return EditResult.Applied(timeline.withTrack(track.copy(clips = clips)), affectedClipId = null)
    }

    private fun closeGaps(timeline: Timeline, command: EditCommand.CloseGaps): EditResult {
        val track = timeline.track(command.trackId) ?: return EditResult.Rejected(EditRejection.TRACK_NOT_FOUND)
        val packed = pack(track.clips)
        if (packed == track.clips) return EditResult.Rejected(EditRejection.NO_CHANGE)
        return EditResult.Applied(timeline.withTrack(track.copy(clips = packed)), affectedClipId = null)
    }

    // ---- Audio ----

    private fun setClipAudio(timeline: Timeline, command: EditCommand.SetClipAudio, context: EditContext): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val bounds = boundsOf(clip, context) ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
        if (!bounds.hasAudio || bounds.isStill) return EditResult.Rejected(EditRejection.NO_AUDIO)
        val audio = command.audio.copy(
            fadeInUs = command.audio.fadeInUs.coerceAtMost(clip.durationUs),
            fadeOutUs = command.audio.fadeOutUs.coerceAtMost(clip.durationUs),
        )
        if (audio == clip.audio) return EditResult.Rejected(EditRejection.NO_CHANGE)
        val clips = track.clips.toMutableList().apply { set(index, clip.copy(audio = audio)) }
        return EditResult.Applied(timeline.withTrack(track.copy(clips = clips)), clip.id)
    }

    private fun setTrackAudio(timeline: Timeline, command: EditCommand.SetTrackAudio): EditResult {
        val track = timeline.track(command.trackId) ?: return EditResult.Rejected(EditRejection.TRACK_NOT_FOUND)
        if (track.audio == command.audio) return EditResult.Rejected(EditRejection.NO_CHANGE)
        return EditResult.Applied(timeline.withTrack(track.copy(audio = command.audio)), affectedClipId = null)
    }

    private fun moveToTrack(timeline: Timeline, command: EditCommand.MoveToTrack): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (from, index, clip) = located
        val target = timeline.track(command.trackId) ?: return EditResult.Rejected(EditRejection.TRACK_NOT_FOUND)
        if (target.id == from.id) return move(timeline, EditCommand.Move(clip.id, command.toStartUs))
        if (target.kind != from.kind || timeline.isMagnetic(target.id) || timeline.isMagnetic(from.id)) {
            return EditResult.Rejected(EditRejection.INCOMPATIBLE_TRACK)
        }
        val start = nearestFreeStart(target.clips, command.toStartUs.coerceAtLeast(0), clip.durationUs)
        val moved = clip.copy(startUs = start)
        val source = from.copy(clips = from.clips.toMutableList().apply { removeAt(index) })
        val destination = target.copy(clips = (target.clips + moved).sortedBy { it.startUs })
        return EditResult.Applied(timeline.withTrack(source).withTrack(destination), clip.id)
    }

    // ---- Overlays ----

    private fun addOverlay(timeline: Timeline, command: EditCommand.AddOverlay, context: EditContext): EditResult {
        val isText = command.overlay.content is OverlayContent.Text
        if (isText != (command.mediaId == null)) return EditResult.Rejected(EditRejection.UNSUPPORTED_MEDIA)
        command.mediaId?.let { id ->
            val bounds = context.media[id] ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
            if (bounds.kind != MediaKind.IMAGE) return EditResult.Rejected(EditRejection.UNSUPPORTED_MEDIA)
        }
        val clip = Clip(
            id = context.newClipId(),
            mediaId = command.mediaId,
            startUs = command.startUs.coerceAtLeast(0),
            sourceInUs = 0,
            durationUs = command.durationUs.coerceAtLeast(timeline.minClipDurationUs),
            overlay = command.overlay,
            // Overlays have no effects yet, so only placement keyframes can come along.
            animation = command.animation.keepingEffects(emptySet()),
        )
        val stack = timeline.overlayTracks
        val topmost = stack.indexOfFirst { it.overlaps(clip) }
        val placed = when {
            topmost >= 0 -> placeAbove(timeline, clip, stack[topmost].id)
            // Nothing overlaps, so every overlay track has room. Use the top one, or start the stack above the main track.
            stack.isNotEmpty() -> timeline.withTrack(stack.first().withClip(clip))
            else -> insertTrack(timeline, clip, at = timeline.mainTrack?.let { timeline.tracks.indexOf(it) } ?: 0)
        }
        return EditResult.Applied(placed, clip.id)
    }

    private fun setClipTransform(timeline: Timeline, command: EditCommand.SetClipTransform, context: EditContext): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        if (track.kind != TrackKind.VIDEO) return EditResult.Rejected(EditRejection.NOT_A_PICTURE)
        val bounds = boundsOf(clip, context) ?: return EditResult.Rejected(EditRejection.MEDIA_NOT_FOUND)
        if (bounds.kind == MediaKind.AUDIO) return EditResult.Rejected(EditRejection.NOT_A_PICTURE)
        val animation = command.animation ?: clip.animation
        if (clip.transform == command.transform && clip.animation == animation) return EditResult.Rejected(EditRejection.NO_CHANGE)
        if (!animatable(clip, track, animation)) return EditResult.Rejected(EditRejection.NOT_ANIMATABLE)
        return replaceClip(timeline, track, index, clip.copy(transform = command.transform, animation = animation))
    }

    private fun setClipAnimation(timeline: Timeline, command: EditCommand.SetClipAnimation): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        if (!animatable(clip, track, command.animation)) return EditResult.Rejected(EditRejection.NOT_ANIMATABLE)
        if (clip.animation == command.animation) return EditResult.Rejected(EditRejection.NO_CHANGE)
        return replaceClip(timeline, track, index, clip.copy(animation = command.animation))
    }

    /** Placement keyframes need a picture or an overlay; effect keyframes need the effect. */
    private fun animatable(clip: Clip, track: Track, animation: ClipAnimation): Boolean =
        (!animation.animatesTransform || track.kind != TrackKind.AUDIO) && clip.effects.map { it.id }.containsAll(animation.effectIds)

    private fun setOverlay(timeline: Timeline, command: EditCommand.SetOverlay): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        val current = clip.overlay ?: return EditResult.Rejected(EditRejection.NOT_AN_OVERLAY)
        if ((current.content is OverlayContent.Text) != (command.overlay.content is OverlayContent.Text)) {
            return EditResult.Rejected(EditRejection.NOT_AN_OVERLAY)
        }
        val animation = command.animation ?: clip.animation
        if (current == command.overlay && clip.animation == animation) return EditResult.Rejected(EditRejection.NO_CHANGE)
        if (!animatable(clip, track, animation)) return EditResult.Rejected(EditRejection.NOT_ANIMATABLE)
        return replaceClip(timeline, track, index, clip.copy(overlay = command.overlay, animation = animation))
    }

    /**
     * Moves an overlay to another lane so it sits above or below the
     * overlays it overlaps in time. Its time and look don't change. A lane is
     * reused when it has room at that layer; otherwise one is inserted.
     */
    private fun arrangeLayer(timeline: Timeline, command: EditCommand.ArrangeLayer): EditResult {
        val located = locate(timeline, command.clipId) ?: return EditResult.Rejected(EditRejection.CLIP_NOT_FOUND)
        val (track, index, clip) = located
        if (track.kind != TrackKind.OVERLAY) return EditResult.Rejected(EditRejection.NOT_AN_OVERLAY)
        // Take the clip off its lane first. The lane stays (possibly empty) so positions are stable, and is pruned afterwards.
        val without = timeline.withTrack(track.copy(clips = track.clips.toMutableList().apply { removeAt(index) }))
        val stack = without.overlayTracks
        val own = stack.indexOfFirst { it.id == track.id }
        val overlapping = stack.indices.filter { it != own && stack[it].overlaps(clip) }
        val placed = when (command.move) {
            LayerMove.FORWARD -> overlapping.lastOrNull { it < own }?.let { placeAbove(without, clip, stack[it].id) }
            LayerMove.TO_FRONT -> overlapping.firstOrNull()?.takeIf { it < own }?.let { placeAbove(without, clip, stack[it].id) }
            LayerMove.BACKWARD -> overlapping.firstOrNull { it > own }?.let { placeBelow(without, clip, stack[it].id) }
            LayerMove.TO_BACK -> overlapping.lastOrNull()?.takeIf { it > own }?.let { placeBelow(without, clip, stack[it].id) }
        } ?: return EditResult.Rejected(EditRejection.NO_CHANGE) // Already at that end of its stack.
        return EditResult.Applied(placed, clip.id)
    }

    /**
     * [clip] stacked directly above the overlay track [anchor]: on the lane
     * just above it when that lane is free at the clip's time, else on a new
     * lane inserted between them.
     */
    private fun placeAbove(timeline: Timeline, clip: Clip, anchor: TrackId): Timeline {
        val stack = timeline.overlayTracks
        val position = stack.indexOfFirst { it.id == anchor }
        val above = stack.getOrNull(position - 1)
        if (above != null && !above.overlaps(clip)) return timeline.withTrack(above.withClip(clip))
        return insertTrack(timeline, clip, at = timeline.tracks.indexOfFirst { it.id == anchor })
    }

    /** [clip] stacked directly below the overlay track [anchor], reusing the lane below when it has room. */
    private fun placeBelow(timeline: Timeline, clip: Clip, anchor: TrackId): Timeline {
        val stack = timeline.overlayTracks
        val position = stack.indexOfFirst { it.id == anchor }
        val below = stack.getOrNull(position + 1)
        if (below != null && !below.overlaps(clip)) return timeline.withTrack(below.withClip(clip))
        return insertTrack(timeline, clip, at = timeline.tracks.indexOfFirst { it.id == anchor } + 1)
    }

    /** A new overlay lane holding [clip], inserted at list position [at]. */
    private fun insertTrack(timeline: Timeline, clip: Clip, at: Int): Timeline {
        val ids = timeline.tracks.map { it.id.value }.toSet()
        var n = 1
        while ("$OVERLAY_TRACK_PREFIX$n" in ids) n++
        val lane = Track(TrackId("$OVERLAY_TRACK_PREFIX$n"), TrackKind.OVERLAY, listOf(clip))
        return timeline.copy(tracks = timeline.tracks.toMutableList().apply { add(at.coerceIn(0, size), lane) })
    }

    private fun Track.overlaps(clip: Clip): Boolean = clips.any { it.startUs < clip.endUs && clip.startUs < it.endUs }

    private fun Track.withClip(clip: Clip): Track = copy(clips = (clips + clip).sortedBy { it.startUs })

    /** Removes overlay tracks without clips. Other tracks are kept even when empty. */
    internal fun withoutEmptyOverlayTracks(timeline: Timeline): Timeline =
        if (timeline.tracks.none { it.kind == TrackKind.OVERLAY && it.clips.isEmpty() }) {
            timeline
        } else {
            timeline.copy(tracks = timeline.tracks.filterNot { it.kind == TrackKind.OVERLAY && it.clips.isEmpty() })
        }

    /** IDs of overlay lanes the editor creates: "overlay-1", "overlay-2", ... */
    internal const val OVERLAY_TRACK_PREFIX: String = "overlay-"

    // ---- Helpers ----

    /** Whether a track has empty space between 0 and its last clip. */
    public fun hasGaps(track: Track): Boolean = pack(track.clips) != track.clips

    /** Lays clips back to back from 0, keeping their order, lengths and source ranges. */
    internal fun pack(clips: List<Clip>): List<Clip> {
        var cursor = 0L
        return clips.map { clip ->
            val placed = if (clip.startUs == cursor) clip else clip.copy(startUs = cursor)
            cursor += clip.durationUs
            placed
        }
    }

    private fun replaceClip(timeline: Timeline, track: Track, index: Int, clip: Clip): EditResult {
        val clips = track.clips.toMutableList().apply { set(index, clip) }
        val updated = if (timeline.isMagnetic(track.id)) pack(clips) else clips
        return EditResult.Applied(timeline.withTrack(track.copy(clips = updated)), clip.id)
    }

    private data class Located(val track: Track, val index: Int, val clip: Clip)

    private fun locate(timeline: Timeline, clipId: com.editor.veyro.core.model.timeline.ClipId): Located? {
        for (track in timeline.tracks) {
            val index = track.clips.indexOfFirst { it.id == clipId }
            if (index >= 0) return Located(track, index, track.clips[index])
        }
        return null
    }
}
