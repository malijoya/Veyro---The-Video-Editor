package com.editor.veyro.core.model.transition

import com.editor.veyro.core.model.animation.Easing

/**
 * How one picture gives way to the next at a cut on the main track.
 *
 * Every type is defined once, in [TransitionLook], as what each of the two
 * pictures looks like at a given progress; preview and export both draw
 * exactly that.
 */
public enum class TransitionType(
    /** Portable name stored in project files. Never change a released key. */
    public val key: String,
) {
    /** Dip to black: the outgoing picture fades to black, then the incoming one fades in from black. */
    FADE("fade"),

    /** Cross-dissolve: the incoming picture fades in over the outgoing one. */
    DISSOLVE("dissolve"),

    /** A soft edge travels right to left, revealing the incoming picture behind it. */
    WIPE_LEFT("wipe-left"),

    /** A soft edge travels left to right. */
    WIPE_RIGHT("wipe-right"),

    /** A soft edge travels bottom to top. */
    WIPE_UP("wipe-up"),

    /** A soft edge travels top to bottom. */
    WIPE_DOWN("wipe-down"),

    /** Both pictures move left: the incoming one pushes the outgoing one off the canvas. */
    SLIDE_LEFT("slide-left"),

    /** Both pictures move right. */
    SLIDE_RIGHT("slide-right"),

    /** Both pictures move up. */
    SLIDE_UP("slide-up"),

    /** Both pictures move down. */
    SLIDE_DOWN("slide-down"),

    /** The outgoing picture grows while the incoming one grows into place, dissolving between them. */
    ZOOM_IN("zoom-in"),

    /** The outgoing picture shrinks while the incoming one shrinks into place, dissolving between them. */
    ZOOM_OUT("zoom-out"),

    /** Half a turn clockwise out and half a turn clockwise in, dissolving between them. */
    SPIN_CLOCKWISE("spin-cw"),

    /** The same, counter-clockwise. */
    SPIN_COUNTERCLOCKWISE("spin-ccw"),
    ;

    /**
     * Whether both pictures are on screen at the same moment. A fade shows
     * one at a time (each fades against black), so centred on the cut it
     * needs no media beyond the clips' edges.
     */
    public val overlapsPictures: Boolean get() = this != FADE

    /** The timing curve the editor starts a new transition of this type with. */
    public val defaultEasing: Easing
        get() = when (this) {
            FADE, DISSOLVE -> Easing.Linear
            else -> Easing.EASE_IN_OUT
        }

    public companion object {
        public fun fromKey(key: String): TransitionType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where the transition sits relative to the cut.
 *
 * The cut itself never moves: the main track stays packed, so adding a
 * transition never changes the timeline's length, its sound or anything
 * placed on other tracks. The pictures the transition overlaps come from
 * the media beyond each clip's edge (its *handle*), see
 * [TransitionResolver].
 */
public enum class TransitionAlignment(public val key: String) {
    /** Half before the cut, half after. Uses both clips' handles. */
    CENTER("center"),

    /** Ends on the cut: the incoming clip starts early, using its media before its in-point. */
    BEFORE_CUT("before-cut"),

    /** Starts on the cut: the outgoing clip runs on, using its media after its out-point. */
    AFTER_CUT("after-cut"),
    ;

    public companion object {
        public fun fromKey(key: String): TransitionAlignment? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A transition from a clip on the main track to the clip right after it.
 * It is stored on the outgoing clip ([com.editor.veyro.core.model.timeline.Clip.transitionOut]).
 *
 * @property durationUs the requested length. It is rounded to whole frames
 *   of the timeline's rate, and may be shortened where the clips are too
 *   short or have too little media past their edges
 *   ([TransitionResolver]); the requested value is kept, so the full length
 *   comes back when there is room again.
 * @property easing the timing curve: how progress moves from 0 to 1 over
 *   the transition.
 */
public data class Transition(
    val type: TransitionType,
    val durationUs: Long = DEFAULT_DURATION_US,
    val easing: Easing = type.defaultEasing,
    val alignment: TransitionAlignment = TransitionAlignment.CENTER,
) {
    init {
        require(durationUs in MIN_DURATION_US..MAX_DURATION_US) { "Transition length must be within $MIN_DURATION_US..$MAX_DURATION_US µs" }
    }

    public companion object {
        public const val DEFAULT_DURATION_US: Long = 500_000

        /** Shorter than any real frame, so one frame at any rate is a valid request. */
        public const val MIN_DURATION_US: Long = 1_000

        public const val MAX_DURATION_US: Long = 5_000_000

        /** The curves the editor offers. Hold is left out: it would turn the transition into a late cut. */
        public val EASINGS: List<Easing> = listOf(Easing.Linear, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT)
    }
}
