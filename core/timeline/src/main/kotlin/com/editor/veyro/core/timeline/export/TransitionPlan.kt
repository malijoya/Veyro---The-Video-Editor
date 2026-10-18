package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.transition.LayerLook
import com.editor.veyro.core.model.transition.TransitionLayer
import com.editor.veyro.core.model.transition.TransitionLook
import com.editor.veyro.core.model.transition.TransitionRole

/**
 * One stretch of the handle layer: media from beyond a clip's edge that a
 * transition shows under the main track.
 *
 * @property transitionIndex the transition in [ExportPlan.transitions].
 * @property role which of the transition's two pictures this is: the
 *   incoming clip's media before its in-point, or the outgoing clip's after
 *   its out-point.
 * @property segmentIndex the clip's video segment, whose transform and
 *   source it uses.
 * @property startUs where it plays on the timeline.
 * @property sourceStartUs where it starts in the source file (0 for photos).
 */
public data class HandlePiece(
    val transitionIndex: Int,
    val role: TransitionRole,
    val segmentIndex: Int,
    val startUs: Long,
    val durationUs: Long,
    val sourceStartUs: Long,
) {
    init {
        require(startUs >= 0 && durationUs > 0 && sourceStartUs >= 0) { "Invalid handle piece" }
    }

    val endUs: Long get() = startUs + durationUs

    val sourceEndUs: Long get() = sourceStartUs + durationUs

    public operator fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}

/**
 * How a plan's transitions are drawn. Preview and export both render from
 * this, so they draw every frame of a transition alike.
 *
 * The renderer has two picture layers, composited top over bottom:
 * - **Main** (top): the main track, unchanged. A clip with a transition
 *   is drawn through [mainLook].
 * - **Handles** (bottom): only during transitions, the other picture,
 *   from the media beyond its clip's edge ([pieces]), drawn through
 *   [pieceLook]. Everywhere else the layer is empty ([handlesVisibleAt]).
 *
 * Before the cut the main track shows the outgoing clip and the handle the
 * incoming one; from the cut it is the other way round. [TransitionLook]
 * gives the same picture either way, so the swap can't be seen.
 *
 * Every look is a function of the frame's own timeline time only, so
 * scrubbing, seeking and exporting draw the same frame for the same time.
 */
public class TransitionPlan(private val plan: ExportPlan) {

    private val starts: List<Long> = plan.segmentStarts()
    private val outgoingOf = plan.transitions.associateBy { it.outgoingIndex }
    private val incomingOf = plan.transitions.associateBy { it.incomingIndex }

    /** The handle layer, in time order, never overlapping. Empty without transitions. */
    public val pieces: List<HandlePiece> = buildPieces()

    public val isEmpty: Boolean get() = plan.transitions.isEmpty()

    /** Whether video segment [index] takes part in a transition (and so needs the transition stage). */
    public fun hasTransition(index: Int): Boolean = index in outgoingOf || index in incomingOf

    /** Where segment [index] starts on the timeline. */
    public fun segmentStartUs(index: Int): Long = starts[index]

    /** How the main track draws segment [index] at timeline time [timeUs]. */
    public fun mainLook(index: Int, timeUs: Long, canvasWidthPx: Int, canvasHeightPx: Int): LayerLook {
        outgoingOf[index]?.window?.let { w ->
            if (timeUs >= w.startUs && timeUs < w.cutUs) return TransitionLook.of(w, TransitionRole.OUTGOING, TransitionLayer.TOP, timeUs, canvasWidthPx, canvasHeightPx)
        }
        incomingOf[index]?.window?.let { w ->
            if (timeUs >= w.cutUs && timeUs < w.endUs) return TransitionLook.of(w, TransitionRole.INCOMING, TransitionLayer.TOP, timeUs, canvasWidthPx, canvasHeightPx)
        }
        return LayerLook.IDENTITY
    }

    /** How the handle layer draws [piece] at timeline time [timeUs]. */
    public fun pieceLook(piece: HandlePiece, timeUs: Long, canvasWidthPx: Int, canvasHeightPx: Int): LayerLook =
        TransitionLook.of(plan.transitions[piece.transitionIndex].window, piece.role, TransitionLayer.BOTTOM, timeUs, canvasWidthPx, canvasHeightPx)

    /** Whether the handle layer has a picture at [timeUs]. Its gaps are never drawn. */
    public fun handlesVisibleAt(timeUs: Long): Boolean {
        // Pieces are sorted and disjoint: the last one starting at or before the time is the only candidate.
        var lo = 0
        var hi = pieces.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val piece = pieces[mid]
            when {
                timeUs < piece.startUs -> hi = mid - 1
                timeUs >= piece.endUs -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * The time range of segment [index]'s own clip, for evaluating its
     * transform and keyframes on handle frames: media beyond the clip's edge
     * keeps the placement the clip has at that edge.
     */
    public fun clipRangeUs(index: Int): LongRange = starts[index] until starts[index] + plan.video[index].durationUs

    private fun buildPieces(): List<HandlePiece> {
        // Each piece with the part of it the transition actually shows; the rest is the one-frame minimum's padding.
        val raw = plan.transitions.flatMapIndexed { index, transition ->
            val window = transition.window
            val incoming = plan.video[transition.incomingIndex]
            val outgoing = plan.video[transition.outgoingIndex]
            listOfNotNull(
                window.incomingHandleUs.takeIf { it > 0 }?.let { handle ->
                    HandlePiece(
                        transitionIndex = index,
                        role = TransitionRole.INCOMING,
                        segmentIndex = transition.incomingIndex,
                        startUs = window.cutUs - handle,
                        durationUs = handle,
                        sourceStartUs = if (incoming.kind == MediaKind.IMAGE) 0 else incoming.sourceStartUs - handle,
                    ) to (window.incomingFromUs until window.cutUs)
                },
                window.outgoingHandleUs.takeIf { it > 0 }?.let { handle ->
                    HandlePiece(
                        transitionIndex = index,
                        role = TransitionRole.OUTGOING,
                        segmentIndex = transition.outgoingIndex,
                        startUs = window.cutUs,
                        durationUs = handle,
                        sourceStartUs = if (outgoing.kind == MediaKind.IMAGE) 0 else outgoing.sourceEndUs,
                    ) to (window.cutUs until window.outgoingUntilUs)
                },
            )
        }
        // Padding can reach into a neighbour's piece (only around clips a couple of frames long). The shown parts never
        // overlap (windows don't), so trimming padding, the earlier piece's tail first, always resolves it.
        val pieces = mutableListOf<HandlePiece>()
        for ((piece, shown) in raw) {
            var current = piece
            val previous = pieces.lastOrNull()
            if (previous != null && previous.endUs > current.startUs) {
                val keepUntil = maxOf(current.startUs, previous.startUs + 1, shownEndOf(previous, raw))
                pieces[pieces.lastIndex] = previous.copy(durationUs = keepUntil - previous.startUs)
                val overlap = keepUntil - current.startUs
                if (overlap > 0) {
                    if (overlap >= current.durationUs) continue
                    current = current.copy(
                        startUs = current.startUs + overlap,
                        durationUs = current.durationUs - overlap,
                        sourceStartUs = if (plan.video[current.segmentIndex].kind == MediaKind.IMAGE) 0 else current.sourceStartUs + overlap,
                    )
                }
            }
            check(shown.isEmpty() || current.startUs <= shown.first) { "A handle piece lost part of what it shows" }
            pieces += current
        }
        return pieces
    }

    /** Where the shown part of [piece] ends (its padding may run on after it). */
    private fun shownEndOf(piece: HandlePiece, raw: List<Pair<HandlePiece, LongRange>>): Long {
        val shown = raw.first { (candidate, _) -> candidate.transitionIndex == piece.transitionIndex && candidate.role == piece.role }.second
        return if (shown.isEmpty()) piece.startUs + 1 else shown.last + 1
    }
}
