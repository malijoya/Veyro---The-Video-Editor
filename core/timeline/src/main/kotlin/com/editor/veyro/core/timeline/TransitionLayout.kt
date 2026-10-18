package com.editor.veyro.core.timeline

import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.transition.CutSide
import com.editor.veyro.core.model.transition.TransitionResolver
import com.editor.veyro.core.model.transition.TransitionWindow

/**
 * A transition placed on a cut of the main track.
 *
 * @property outgoingIndex the outgoing clip's position on the main track;
 *   the incoming clip is the next one.
 */
public data class ResolvedCut(val outgoingIndex: Int, val outgoing: Clip, val incoming: Clip, val window: TransitionWindow)

/**
 * Where the main track's transitions happen, for the editor (timeline
 * marks, the transition panel) and the export planner alike.
 */
public object TransitionLayout {

    /**
     * Every transition on the main track, in order, fitted to its cut
     * ([TransitionResolver]). Transitions on the last clip, which has no cut
     * after it, are left out.
     *
     * @param sourceLengthUs the length of a clip's source file, or null for
     *   a still (photo), whose handles are unlimited. Unknown lengths should
     *   be reported as the clip's out-point, which allows no handle after
     *   it.
     */
    public fun resolve(timeline: Timeline, sourceLengthUs: (Clip) -> Long?): List<ResolvedCut> {
        val clips = timeline.mainTrack?.clips.orEmpty()
        return clips.zipWithNext().mapIndexedNotNull { index, (outgoing, incoming) ->
            val transition = outgoing.transitionOut ?: return@mapIndexedNotNull null
            ResolvedCut(
                outgoingIndex = index,
                outgoing = outgoing,
                incoming = incoming,
                window = TransitionResolver.resolve(
                    transition = transition,
                    cutUs = outgoing.endUs,
                    outgoing = CutSide(outgoing.durationUs, sourceLengthUs(outgoing)?.let { (it - outgoing.sourceOutUs).coerceAtLeast(0) }),
                    incoming = CutSide(incoming.durationUs, sourceLengthUs(incoming)?.let { incoming.sourceInUs }),
                    frameRate = timeline.frameRate,
                ),
            )
        }
    }

    /** The cut at the end of [clipId], or null when it has no transition or isn't followed by a clip on the main track. */
    public fun cutAfter(timeline: Timeline, clipId: ClipId, sourceLengthUs: (Clip) -> Long?): ResolvedCut? =
        resolve(timeline, sourceLengthUs).firstOrNull { it.outgoing.id == clipId }

    /** Source length as [resolve] wants it, from the editor's media bounds. */
    public fun sourceLengthFrom(context: EditContext): (Clip) -> Long? = { clip ->
        val bounds = clip.mediaId?.let { context.media[it] }
        when {
            bounds == null -> clip.sourceOutUs
            bounds.isStill -> null
            bounds.durationUs <= 0 -> clip.sourceOutUs
            else -> bounds.durationUs
        }
    }
}
