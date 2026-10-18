package com.editor.veyro.core.model.transition

import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.time.FrameRounding

/**
 * One side of a cut, as the resolver needs it.
 *
 * @property durationUs the clip's length on the timeline.
 * @property handleUs how much media the file has beyond the clip's edge at
 *   the cut: after the out-point for the outgoing clip, before the in-point
 *   for the incoming one. Null means unlimited (a photo has no source time).
 */
public data class CutSide(val durationUs: Long, val handleUs: Long?) {
    init {
        require(durationUs > 0) { "A clip has a length" }
        require(handleUs == null || handleUs >= 0) { "Handles can't be negative" }
    }
}

/** Why a transition is shorter than requested. */
public enum class TransitionLimit {
    /**
     * A clip's file has too little media past its edge to show both pictures
     * that long. Trimming the clips at the cut makes room
     * (the editor's "Trim to fit").
     */
    MEDIA_HANDLES,

    /**
     * A clip is too short: a transition may use at most half of each clip it
     * touches, so transitions at both ends of a clip never overlap.
     */
    CLIP_LENGTH,
}

/**
 * Where a transition actually happens on the timeline, in whole frames of
 * the timeline's rate.
 *
 * The cut stays where it is: before [cutUs] the main track shows the
 * outgoing clip, from [cutUs] the incoming one, exactly as without a
 * transition. The transition covers `[startUs, endUs)`:
 * [leadFrames] frames before the cut (frames starting before it) and
 * [trailFrames] from it. Frame `k` of the timeline belongs to the
 * transition exactly when `startUs <= frameToUs(k) < endUs`, and there are
 * exactly [frames] of them.
 *
 * During the window, the picture the main track isn't showing comes from
 * the clip's handle: the incoming clip is visible from [incomingFromUs]
 * and the outgoing one until [outgoingUntilUs].
 *
 * @property frames 0 when nothing fits: the cut stays a plain cut.
 * @property midUs for a fade, where the outgoing picture has gone fully to
 *   black and the incoming one starts to fade in.
 * @property outgoingHandleUs media read after the outgoing clip's
 *   out-point: from the cut to [outgoingUntilUs], but never less than one
 *   frame when any is needed, so the renderer never reads a stretch too
 *   short to hold a frame. 0 when none is needed.
 * @property incomingHandleUs media read before the incoming clip's
 *   in-point, ending at the cut. Same one-frame minimum.
 */
public data class TransitionWindow(
    val transition: Transition,
    val requestedFrames: Int,
    val frames: Int,
    val leadFrames: Int,
    val trailFrames: Int,
    val startUs: Long,
    val cutUs: Long,
    val endUs: Long,
    val midUs: Long,
    val outgoingUntilUs: Long,
    val incomingFromUs: Long,
    val outgoingHandleUs: Long,
    val incomingHandleUs: Long,
    val limit: TransitionLimit?,
) {
    val isActive: Boolean get() = frames > 0

    val durationUs: Long get() = endUs - startUs

    public operator fun contains(timeUs: Long): Boolean = isActive && timeUs >= startUs && timeUs < endUs

    /**
     * Linear progress at [timeUs], before easing: 0 up to [startUs], 1 from
     * [endUs], strictly between inside. The window's frames are spread
     * evenly, so with N frames, frame i of the window gets (i + 1) / (N + 1):
     * no frame inside repeats the picture of the frame before or after the
     * window, and the curve is symmetric about the middle.
     */
    public fun progress(timeUs: Long): Double = phase(timeUs, startUs, endUs, frames)

    /** Progress of a fade's first half (to black), over `[startUs, midUs)`. */
    public fun fadeOutProgress(timeUs: Long): Double = phase(timeUs, startUs, midUs, midFrames)

    /** Progress of a fade's second half (from black), over `[midUs, endUs)`. */
    public fun fadeInProgress(timeUs: Long): Double = phase(timeUs, midUs, endUs, frames - midFrames)

    private val midFrames: Int get() = frames / 2

    private fun phase(timeUs: Long, from: Long, to: Long, count: Int): Double {
        if (timeUs < from || count <= 0) return if (timeUs < from) 0.0 else 1.0
        if (timeUs >= to) return 1.0
        val span = (to - from).toDouble()
        val frame = span / count
        return ((timeUs - from + frame) / (span + frame)).coerceIn(0.0, 1.0)
    }
}

/**
 * Fits a transition to the cut it belongs to. Pure integer arithmetic on
 * the timeline's frame grid, so the editor, preview and export agree on
 * every frame.
 *
 * Rules:
 * - The length is rounded to whole frames (at least one). [Transition.alignment]
 *   splits them around the cut: centred (the extra frame of an odd count
 *   goes after the cut), all before, or all after.
 * - **Handles.** Where both pictures are on screen, the one the main track
 *   isn't showing comes from beyond its clip's edge. If the file doesn't
 *   have that much media, the transition is shortened, keeping its
 *   alignment, until it fits ([TransitionLimit.MEDIA_HANDLES]). Photos have
 *   unlimited handles. A centred fade needs none.
 * - **Clip lengths.** A transition uses at most half of each clip it
 *   touches ([TransitionLimit.CLIP_LENGTH]), so the transitions at the two
 *   ends of a clip can never overlap, however short the clip.
 * - **Nothing fits.** The window is empty and the cut stays a plain cut;
 *   the transition is kept, and comes back when there is room.
 * - **Source overlap.** Each clip reads only its own file and range, so two
 *   adjacent clips from the same file (a split, or overlapping ranges) are
 *   handled like any others: the outgoing clip's handle may show the same
 *   frames the incoming clip shows a moment later.
 */
public object TransitionResolver {

    public fun resolve(transition: Transition, cutUs: Long, outgoing: CutSide, incoming: CutSide, frameRate: FrameRate): TransitionWindow {
        // At most 5 s at 1000 fps, so this always fits an Int.
        val requested = frameRate.usToFrame(transition.durationUs, FrameRounding.NEAREST).coerceAtLeast(1).toInt()
        val cutFrame = frameRate.usToFrame(cutUs, FrameRounding.CEIL)

        fun window(frames: Int) = layout(transition, frames, requested, cutUs, cutFrame, frameRate)
        fun fitsLength(w: TransitionWindow) = (cutUs - w.startUs) * 2 <= outgoing.durationUs && (w.endUs - cutUs) * 2 <= incoming.durationUs
        fun fitsHandles(w: TransitionWindow) =
            (outgoing.handleUs == null || w.outgoingHandleUs <= outgoing.handleUs) &&
                (incoming.handleUs == null || w.incomingHandleUs <= incoming.handleUs)

        // Every constraint only gets easier as the window shrinks (each edge moves monotonically with the frame count), so a binary search finds the longest fit.
        fun longest(fits: (TransitionWindow) -> Boolean): Int {
            var lo = 0
            var hi = requested
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (fits(window(mid))) lo = mid else hi = mid - 1
            }
            return lo
        }

        val frames = longest { fitsLength(it) && fitsHandles(it) }
        val limit = when {
            frames == requested -> null
            longest(::fitsHandles) <= longest(::fitsLength) -> TransitionLimit.MEDIA_HANDLES
            else -> TransitionLimit.CLIP_LENGTH
        }
        return window(frames).copy(limit = limit)
    }

    private fun layout(transition: Transition, frames: Int, requested: Int, cutUs: Long, cutFrame: Long, frameRate: FrameRate): TransitionWindow {
        if (frames == 0) {
            return TransitionWindow(transition, requested, 0, 0, 0, cutUs, cutUs, cutUs, cutUs, cutUs, cutUs, 0, 0, limit = null)
        }
        val lead = when (transition.alignment) {
            TransitionAlignment.CENTER -> frames / 2
            TransitionAlignment.BEFORE_CUT -> frames
            TransitionAlignment.AFTER_CUT -> 0
        }
        val trail = frames - lead
        // Window edges sit on frame starts, except on the cut side of an empty half, which is the cut itself (no sliver before or after it).
        val startUs = if (lead == 0) cutUs else frameRate.frameToUs(cutFrame - lead)
        val endUs = if (trail == 0) cutUs else frameRate.frameToUs(cutFrame + trail)
        val midFrame = cutFrame - lead + frames / 2
        val midUs = if (midFrame == cutFrame) cutUs else frameRate.frameToUs(midFrame)
        val overlap = transition.type.overlapsPictures
        // A fade shows the outgoing picture until the middle and the incoming one from it; the others show both throughout.
        val outgoingUntilUs = if (overlap) endUs else maxOf(cutUs, midUs)
        val incomingFromUs = if (overlap) startUs else minOf(cutUs, midUs)
        val oneFrameUs = frameRate.frameToUs(1)
        return TransitionWindow(
            transition = transition,
            requestedFrames = requested,
            frames = frames,
            leadFrames = lead,
            trailFrames = trail,
            startUs = startUs,
            cutUs = cutUs,
            endUs = endUs,
            midUs = midUs,
            outgoingUntilUs = outgoingUntilUs,
            incomingFromUs = incomingFromUs,
            outgoingHandleUs = if (outgoingUntilUs > cutUs) maxOf(outgoingUntilUs - cutUs, oneFrameUs) else 0,
            incomingHandleUs = if (incomingFromUs < cutUs) maxOf(cutUs - incomingFromUs, oneFrameUs) else 0,
            limit = null,
        )
    }
}
