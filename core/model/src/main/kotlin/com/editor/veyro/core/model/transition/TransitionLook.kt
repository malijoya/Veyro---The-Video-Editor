package com.editor.veyro.core.model.transition

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Which of the two pictures a layer shows. */
public enum class TransitionRole { OUTGOING, INCOMING }

/**
 * Which layer a picture is drawn on. The renderer draws the main track on
 * [TOP] and the other picture (from a clip's handle) on [BOTTOM]. Which
 * picture is on top changes at the cut, so every look is defined to give
 * the same result either way round (see [TransitionComposite]).
 */
public enum class TransitionLayer { TOP, BOTTOM }

/**
 * An affine map of canvas fractions (0..1, y down): `x' = a·x + b·y + tx`,
 * `y' = c·x + d·y + ty`.
 */
public data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    public fun mapX(x: Double, y: Double): Double = a * x + b * y + tx
    public fun mapY(x: Double, y: Double): Double = c * x + d * y + ty

    public companion object {
        public val IDENTITY: Affine = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** The affine map that agrees with [f] (which must be affine) on the unit square. */
        internal fun of(f: (Double, Double) -> Pair<Double, Double>): Affine {
            val (ox, oy) = f(0.0, 0.0)
            val (xx, xy) = f(1.0, 0.0)
            val (yx, yy) = f(0.0, 1.0)
            return Affine(xx - ox, yx - ox, xy - oy, yy - oy, ox, oy)
        }
    }
}

/**
 * A wipe's soft edge. With `s = dirX·x + dirY·y + offset` (0 on the side
 * the wipe starts from, 1 on the far side, x and y canvas fractions), the
 * outgoing picture covers `smoothstep(low, high, s)` of each point, where
 * `smoothstep` is GLSL's Hermite step. The incoming picture shows through
 * the rest.
 *
 * @property revealed true on the incoming picture's layer (it keeps the
 *   revealed part, `1 - coverage`), false on the outgoing one's.
 */
public data class WipeMask(
    val dirX: Double,
    val dirY: Double,
    val offset: Double,
    val low: Double,
    val high: Double,
    val revealed: Boolean,
) {
    /** How much of the point the outgoing picture still covers. */
    public fun coverage(x: Double, y: Double): Double = smoothstep(low, high, dirX * x + dirY * y + offset)

    /** This layer's alpha at the point. */
    public fun alpha(x: Double, y: Double): Double = coverage(x, y).let { if (revealed) 1 - it else it }

    public companion object {
        /** GLSL `smoothstep`, exactly. */
        public fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}

/**
 * How one layer is drawn on one frame. Each layer is a canvas-sized
 * picture (the clip already placed by its transform, black where the clip
 * doesn't reach):
 * - Each output point samples the picture at `inverse(point)`. Points that
 *   land outside it are black, opaque when [opaqueOutside], else
 *   transparent (a slide leaves room for the other picture).
 * - The colour is multiplied by [brightness] (fade to black).
 * - The alpha is [opacity] times the wipe's alpha, if any.
 *
 * The layers are then blended with straight alpha, top over bottom over
 * black: `top·αt + bottom·αb·(1 − αt)`.
 */
public data class LayerLook(
    val opacity: Double = 1.0,
    val brightness: Double = 1.0,
    val wipe: WipeMask? = null,
    val inverse: Affine = Affine.IDENTITY,
    val opaqueOutside: Boolean = true,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    public companion object {
        /** The picture as it is, opaque. Outside a transition, every layer looks like this. */
        public val IDENTITY: LayerLook = LayerLook()

        /** Not drawn at all. */
        public val HIDDEN: LayerLook = LayerLook(opacity = 0.0)
    }
}

/**
 * The one definition of what every transition looks like: for a frame at
 * timeline time t, the [LayerLook] of each picture on each layer. The GPU
 * stage in preview and export draws exactly this, and [TransitionComposite]
 * computes it on the CPU for tests.
 *
 * Let p be the eased progress ([TransitionWindow.progress] through
 * [Transition.easing]):
 * - Fade: the outgoing picture's brightness goes 1 → 0 over the first half
 *   ([TransitionWindow.midUs]), then the incoming one's 0 → 1.
 * - Dissolve: the top picture's opacity is 1 − p if it is the outgoing
 *   one, p if the incoming; the bottom is opaque.
 * - Wipe: a soft edge ([WIPE_SOFTNESS] of the canvas) travels across; the
 *   top picture keeps its own side of it.
 * - Slide: the outgoing picture moves by p canvas widths (or heights), the
 *   incoming one follows from one canvas behind. They never overlap, and
 *   the space they leave is transparent.
 * - Zoom: the outgoing picture scales by Z^p, the incoming one from Z^(p−1)
 *   to 1 (zoom in; zoom out is the reverse), with a dissolve.
 * - Spin: the outgoing picture turns by p half-turns, the incoming one from
 *   −1 half-turn to 0, each scaled just enough to cover the canvas, with a
 *   dissolve.
 *
 * With opaque pictures, the blend is the same whichever picture is on top
 * (tested), so the swap of layers at the cut can't be seen.
 */
public object TransitionLook {

    /** Width of a wipe's soft edge, as a fraction of the canvas. */
    public const val WIPE_SOFTNESS: Double = 0.04

    /** How far a zoom goes. */
    public const val ZOOM_FACTOR: Double = 2.0

    /** How far each picture turns during a spin. */
    public const val SPIN_DEGREES: Double = 180.0

    public fun of(
        window: TransitionWindow,
        role: TransitionRole,
        layer: TransitionLayer,
        timeUs: Long,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
    ): LayerLook {
        if (!window.isActive) return if (shows(role, window.cutUs, timeUs)) LayerLook.IDENTITY else LayerLook.HIDDEN
        val transition = window.transition
        val easing = transition.easing
        val p = easing.ease(window.progress(timeUs))
        val outgoing = role == TransitionRole.OUTGOING
        val crossfade = when (layer) {
            TransitionLayer.TOP -> if (outgoing) 1 - p else p
            TransitionLayer.BOTTOM -> 1.0
        }
        return when (transition.type) {
            TransitionType.FADE -> if (outgoing) {
                if (timeUs < window.midUs) LayerLook(brightness = 1 - easing.ease(window.fadeOutProgress(timeUs))) else LayerLook.HIDDEN
            } else {
                if (timeUs >= window.midUs) LayerLook(brightness = easing.ease(window.fadeInProgress(timeUs))) else LayerLook.HIDDEN
            }
            TransitionType.DISSOLVE -> LayerLook(opacity = crossfade)
            TransitionType.WIPE_LEFT -> wipe(-1.0, 0.0, 1.0, p, layer, outgoing)
            TransitionType.WIPE_RIGHT -> wipe(1.0, 0.0, 0.0, p, layer, outgoing)
            TransitionType.WIPE_UP -> wipe(0.0, -1.0, 1.0, p, layer, outgoing)
            TransitionType.WIPE_DOWN -> wipe(0.0, 1.0, 0.0, p, layer, outgoing)
            TransitionType.SLIDE_LEFT -> slide(-1.0, 0.0, p, outgoing)
            TransitionType.SLIDE_RIGHT -> slide(1.0, 0.0, p, outgoing)
            TransitionType.SLIDE_UP -> slide(0.0, -1.0, p, outgoing)
            TransitionType.SLIDE_DOWN -> slide(0.0, 1.0, p, outgoing)
            TransitionType.ZOOM_IN -> zoom(if (outgoing) ZOOM_FACTOR.pow(p) else ZOOM_FACTOR.pow(p - 1), crossfade)
            TransitionType.ZOOM_OUT -> zoom(if (outgoing) ZOOM_FACTOR.pow(-p) else ZOOM_FACTOR.pow(1 - p), crossfade)
            TransitionType.SPIN_CLOCKWISE -> spin(SPIN_DEGREES * (if (outgoing) p else p - 1), crossfade, canvasWidthPx, canvasHeightPx)
            TransitionType.SPIN_COUNTERCLOCKWISE -> spin(-SPIN_DEGREES * (if (outgoing) p else p - 1), crossfade, canvasWidthPx, canvasHeightPx)
        }
    }

    /** Whether the main track would show [role] at [timeUs] with a plain cut. */
    private fun shows(role: TransitionRole, cutUs: Long, timeUs: Long): Boolean =
        (role == TransitionRole.OUTGOING) == (timeUs < cutUs)

    private fun wipe(dirX: Double, dirY: Double, offset: Double, p: Double, layer: TransitionLayer, outgoing: Boolean): LayerLook {
        if (layer == TransitionLayer.BOTTOM) return LayerLook.IDENTITY
        // The edge's centre runs from just before the start side to just past the far side, so p = 0 and 1 show one picture only.
        val edge = -WIPE_SOFTNESS / 2 + p * (1 + WIPE_SOFTNESS)
        return LayerLook(wipe = WipeMask(dirX, dirY, offset, edge - WIPE_SOFTNESS / 2, edge + WIPE_SOFTNESS / 2, revealed = !outgoing))
    }

    private fun slide(dirX: Double, dirY: Double, p: Double, outgoing: Boolean): LayerLook {
        val shift = if (outgoing) p else p - 1
        return LayerLook(inverse = Affine(1.0, 0.0, 0.0, 1.0, -dirX * shift, -dirY * shift), opaqueOutside = false)
    }

    private fun zoom(scale: Double, opacity: Double): LayerLook =
        LayerLook(opacity = opacity, inverse = Affine.of { x, y -> CENTER + (x - CENTER) / scale to CENTER + (y - CENTER) / scale })

    /**
     * Turns the picture by [degrees] clockwise about the canvas centre, in
     * pixels (so nothing shears on a non-square canvas), scaled so the
     * turned picture still covers the whole canvas.
     */
    private fun spin(degrees: Double, opacity: Double, widthPx: Int, heightPx: Int): LayerLook {
        val radians = Math.toRadians(degrees)
        val cos = cos(radians)
        val sin = sin(radians)
        val w = widthPx.toDouble()
        val h = heightPx.toDouble()
        val scale = coverScale(degrees, widthPx, heightPx)
        // Inverse of "scale and turn clockwise about the centre" (y down), in pixels, then back to fractions.
        val inverse = Affine.of { x, y ->
            val dx = (x - CENTER) * w / scale
            val dy = (y - CENTER) * h / scale
            (CENTER + (dx * cos + dy * sin) / w) to (CENTER + (-dx * sin + dy * cos) / h)
        }
        return LayerLook(opacity = opacity, inverse = inverse)
    }

    /** The smallest scale at which a canvas turned by [degrees] still covers the unturned canvas. */
    public fun coverScale(degrees: Double, widthPx: Int, heightPx: Int): Double {
        val radians = Math.toRadians(degrees)
        val aspect = max(widthPx.toDouble() / heightPx, heightPx.toDouble() / widthPx)
        return abs(cos(radians)) + aspect * abs(sin(radians))
    }

    private const val CENTER = 0.5
}

/** A straight-alpha colour with components in 0..1. */
public data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double)

/** A colour with components in 0..1. */
public data class Rgb(val r: Double, val g: Double, val b: Double) {
    public companion object {
        public val BLACK: Rgb = Rgb(0.0, 0.0, 0.0)
    }
}

/**
 * The transition blend on the CPU: what the GPU draws for one output point,
 * from the two layers' looks. It mirrors the renderer's shader and Media3's
 * compositor blend (`src·α + dst·(1 − α)` over a cleared, transparent
 * canvas), so tests can check every transition point by point, and
 * instrumented tests can predict exported pixels.
 */
public object TransitionComposite {

    /** A layer's colour at canvas point ([x], [y]), drawing [picture] (canvas fractions in, colour out) with [look]. */
    public fun layer(look: LayerLook, picture: (Double, Double) -> Rgb, x: Double, y: Double): Rgba {
        val px = look.inverse.mapX(x, y)
        val py = look.inverse.mapY(x, y)
        if (px < 0 || px > 1 || py < 0 || py > 1) {
            return Rgba(0.0, 0.0, 0.0, if (look.opaqueOutside) look.opacity else 0.0)
        }
        val color = picture(px, py)
        val alpha = look.opacity * (look.wipe?.alpha(x, y) ?: 1.0)
        return Rgba(color.r * look.brightness, color.g * look.brightness, color.b * look.brightness, alpha)
    }

    /** [top] over [bottom] over black. */
    public fun blend(top: Rgba, bottom: Rgba): Rgb {
        val under = 1 - top.a
        return Rgb(
            top.r * top.a + bottom.r * bottom.a * under,
            top.g * top.a + bottom.g * bottom.a * under,
            top.b * top.a + bottom.b * bottom.a * under,
        )
    }

    /**
     * The output at ([x], [y]) at [timeUs], with the main track on top:
     * the outgoing picture before the cut, the incoming one from it, and
     * the other one below (when the window needs it).
     */
    public fun frame(
        window: TransitionWindow,
        outgoing: (Double, Double) -> Rgb,
        incoming: (Double, Double) -> Rgb,
        timeUs: Long,
        x: Double,
        y: Double,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
    ): Rgb {
        val beforeCut = timeUs < window.cutUs
        val topRole = if (beforeCut) TransitionRole.OUTGOING else TransitionRole.INCOMING
        val bottomRole = if (beforeCut) TransitionRole.INCOMING else TransitionRole.OUTGOING
        val topPicture = if (beforeCut) outgoing else incoming
        val bottomPicture = if (beforeCut) incoming else outgoing
        val top = layer(TransitionLook.of(window, topRole, TransitionLayer.TOP, timeUs, canvasWidthPx, canvasHeightPx), topPicture, x, y)
        val bottom = if (timeUs in window && needsBottom(window, bottomRole, timeUs)) {
            layer(TransitionLook.of(window, bottomRole, TransitionLayer.BOTTOM, timeUs, canvasWidthPx, canvasHeightPx), bottomPicture, x, y)
        } else {
            Rgba(0.0, 0.0, 0.0, 0.0)
        }
        return blend(top, bottom)
    }

    /** Whether the renderer has the bottom picture at [timeUs]: only where the window takes it from a handle. */
    private fun needsBottom(window: TransitionWindow, role: TransitionRole, timeUs: Long): Boolean = when (role) {
        TransitionRole.INCOMING -> timeUs >= window.incomingFromUs
        TransitionRole.OUTGOING -> timeUs < window.outgoingUntilUs
    }
}
