package com.editor.veyro.core.model.overlay

import kotlin.math.abs

/**
 * A color as `0xAARRGGBB`, the same packing Android and most image APIs use.
 * The alpha is straight (not premultiplied).
 */
@JvmInline
public value class Argb(public val value: Int) {
    public val alpha: Int get() = value ushr 24
    public val red: Int get() = (value shr 16) and 0xFF
    public val green: Int get() = (value shr 8) and 0xFF
    public val blue: Int get() = value and 0xFF

    public val isTransparent: Boolean get() = alpha == 0

    public fun withAlpha(alpha: Int): Argb = Argb((alpha.coerceIn(0, 255) shl 24) or (value and 0x00FFFFFF))

    /** `#AARRGGBB`, the form stored in project files. */
    public fun toHex(): String = "#" + (value.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    override fun toString(): String = toHex()

    public companion object {
        public val WHITE: Argb = Argb(0xFFFFFFFF.toInt())
        public val BLACK: Argb = Argb(0xFF000000.toInt())
        public val TRANSPARENT: Argb = Argb(0)

        public fun of(alpha: Int, red: Int, green: Int, blue: Int): Argb = Argb(
            (alpha.coerceIn(0, 255) shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255),
        )

        /** Parses `#AARRGGBB` or `#RRGGBB` (opaque). Null for anything else. */
        public fun parse(text: String): Argb? {
            val hex = text.removePrefix("#")
            if (hex.length != 6 && hex.length != 8) return null
            val raw = hex.toLongOrNull(16) ?: return null
            return Argb(if (hex.length == 6) (raw or 0xFF000000L).toInt() else raw.toInt())
        }
    }
}

/**
 * Typefaces offered for text. Each is a family every Android device has
 * shipped since Android 5 (Roboto, Noto Serif, Droid Sans Mono, Dancing
 * Script, Coming Soon, Carrois Gothic SC). A device that lacks one falls back
 * to [fallback], a generic family that always exists. Characters a font
 * doesn't cover (emoji, CJK, Arabic, ...) come from the system's fallback
 * fonts, so text never shows empty boxes because of the chosen font.
 *
 * [key] is what project files store. It doesn't depend on Android, so a
 * project opened elsewhere can map it to its own fonts.
 */
public enum class OverlayFont(public val key: String, public val fallback: GenericFont) {
    SANS("sans", GenericFont.SANS),
    SANS_CONDENSED("sans-condensed", GenericFont.SANS),
    SANS_LIGHT("sans-light", GenericFont.SANS),
    SANS_BLACK("sans-black", GenericFont.SANS),
    SERIF("serif", GenericFont.SERIF),
    MONOSPACE("mono", GenericFont.MONOSPACE),
    HANDWRITING("handwriting", GenericFont.SERIF),
    CASUAL("casual", GenericFont.SANS),
    SMALL_CAPS("small-caps", GenericFont.SANS),
    ;

    public companion object {
        /** The font stored as [key], or [SANS] for a key this version doesn't know. */
        public fun fromKey(key: String): OverlayFont = entries.firstOrNull { it.key == key } ?: SANS
    }
}

/** The families every platform has. Fonts fall back to these. */
public enum class GenericFont { SANS, SERIF, MONOSPACE }

/**
 * Horizontal alignment of lines inside a text box. Visual: LEFT is left in
 * right-to-left scripts too.
 */
public enum class TextAlign { LEFT, CENTER, RIGHT }

/** A stroke around each glyph. [widthFraction] is the stroke width as a fraction of the font size. */
public data class TextOutline(val color: Argb, val widthFraction: Float = 0.08f) {
    init {
        require(widthFraction.isFinite() && widthFraction in MIN_WIDTH..MAX_WIDTH) { "Invalid outline width $widthFraction" }
    }

    public companion object {
        public const val MIN_WIDTH: Float = 0.01f
        public const val MAX_WIDTH: Float = 0.3f
    }
}

/**
 * A drop shadow under the text (and its outline). Blur and offsets are
 * fractions of the font size, so the shadow scales with the text.
 */
public data class TextShadow(
    val color: Argb,
    val blurFraction: Float = 0.08f,
    val offsetXFraction: Float = 0.04f,
    val offsetYFraction: Float = 0.04f,
) {
    init {
        require(blurFraction.isFinite() && blurFraction in 0f..MAX_BLUR) { "Invalid shadow blur $blurFraction" }
        require(offsetXFraction.isFinite() && abs(offsetXFraction) <= MAX_OFFSET) { "Invalid shadow offset $offsetXFraction" }
        require(offsetYFraction.isFinite() && abs(offsetYFraction) <= MAX_OFFSET) { "Invalid shadow offset $offsetYFraction" }
    }

    public companion object {
        public const val MAX_BLUR: Float = 0.5f
        public const val MAX_OFFSET: Float = 0.5f
    }
}

/**
 * A filled box behind the text. Padding and corner radius are fractions of
 * the font size. A translucent [color] gives a see-through box.
 */
public data class TextBackground(
    val color: Argb,
    val paddingFraction: Float = 0.25f,
    val cornerFraction: Float = 0.2f,
) {
    init {
        require(paddingFraction.isFinite() && paddingFraction in 0f..MAX_PADDING) { "Invalid background padding $paddingFraction" }
        require(cornerFraction.isFinite() && cornerFraction in 0f..MAX_CORNER) { "Invalid background corner $cornerFraction" }
    }

    public companion object {
        public const val MAX_PADDING: Float = 1.5f
        public const val MAX_CORNER: Float = 1.5f
    }
}

/**
 * How text looks. Sizes are relative, so a project looks the same at every
 * output resolution:
 * - [sizeFraction]: font size as a fraction of the canvas's shorter side.
 * - Outline, shadow and background: fractions of the font size.
 *
 * Lines wrap at [MAX_LINE_WIDTH_FRACTION] of the canvas width, at the
 * unscaled size; resizing the overlay afterwards scales the wrapped block
 * as a whole.
 */
public data class TextStyle(
    val font: OverlayFont = OverlayFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizeFraction: Float = DEFAULT_SIZE,
    val color: Argb = Argb.WHITE,
    val align: TextAlign = TextAlign.CENTER,
    val outline: TextOutline? = null,
    val shadow: TextShadow? = null,
    val background: TextBackground? = null,
) {
    init {
        require(sizeFraction.isFinite() && sizeFraction in MIN_SIZE..MAX_SIZE) { "Invalid text size $sizeFraction" }
    }

    public companion object {
        public const val MIN_SIZE: Float = 0.01f
        public const val MAX_SIZE: Float = 0.5f
        public const val DEFAULT_SIZE: Float = 0.07f

        /** Text wraps at this fraction of the canvas width (before the overlay's own scale). */
        public const val MAX_LINE_WIDTH_FRACTION: Float = 0.9f
    }
}

/** What an overlay shows. */
public sealed interface OverlayContent {
    /** Text, laid out and drawn by the renderer. Newlines start new lines. */
    public data class Text(val text: String, val style: TextStyle = TextStyle()) : OverlayContent {
        init {
            require(text.length <= MAX_LENGTH) { "Text is longer than $MAX_LENGTH characters" }
        }

        public companion object {
            public const val MAX_LENGTH: Int = 2_000
        }
    }

    /**
     * An image from the project's media (the clip's media item), drawn with
     * its own transparency. PNG and WebP keep their alpha channel.
     *
     * At scale 1 its longer side is [BASE_SIZE_FRACTION] of the canvas's
     * shorter side.
     */
    public data object Sticker : OverlayContent {
        public const val BASE_SIZE_FRACTION: Float = 0.4f
    }
}

/**
 * Where and how an overlay sits on the canvas.
 *
 * @property centerX the overlay's centre, as a fraction of the canvas width
 *   (0 left edge, 1 right edge). It may go past the edges, so an overlay
 *   can be partly off screen.
 * @property centerY the same, from the top (0) to the bottom (1).
 * @property scale size multiplier on top of the content's own size.
 * @property rotationDegrees clockwise rotation about the centre, in (-180, 180].
 * @property opacity 0 (invisible) to 1, applied to the whole overlay.
 */
public data class OverlayTransform(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
) {
    init {
        require(centerX.isFinite() && centerX in MIN_CENTER..MAX_CENTER) { "Invalid centerX $centerX" }
        require(centerY.isFinite() && centerY in MIN_CENTER..MAX_CENTER) { "Invalid centerY $centerY" }
        require(scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) { "Invalid scale $scale" }
        require(rotationDegrees.isFinite() && rotationDegrees > -180f && rotationDegrees <= 180f) { "Invalid rotation $rotationDegrees" }
        require(opacity.isFinite() && opacity in 0f..1f) { "Invalid opacity $opacity" }
    }

    public companion object {
        public const val MIN_CENTER: Float = -0.5f
        public const val MAX_CENTER: Float = 1.5f
        public const val MIN_SCALE: Float = 0.05f
        public const val MAX_SCALE: Float = 20f

        /**
         * A transform from unchecked values (gestures, sliders, hand-edited
         * files): each is brought into range, and rotation is wrapped into
         * (-180, 180]. Non-finite values fall back to the defaults.
         */
        public fun coerced(
            centerX: Float = 0.5f,
            centerY: Float = 0.5f,
            scale: Float = 1f,
            rotationDegrees: Float = 0f,
            opacity: Float = 1f,
        ): OverlayTransform = OverlayTransform(
            centerX = finiteOr(centerX, 0.5f).coerceIn(MIN_CENTER, MAX_CENTER),
            centerY = finiteOr(centerY, 0.5f).coerceIn(MIN_CENTER, MAX_CENTER),
            scale = finiteOr(scale, 1f).coerceIn(MIN_SCALE, MAX_SCALE),
            rotationDegrees = normalizeDegrees(finiteOr(rotationDegrees, 0f)),
            opacity = finiteOr(opacity, 1f).coerceIn(0f, 1f),
        )

        /** [degrees] wrapped into (-180, 180]. */
        public fun normalizeDegrees(degrees: Float): Float {
            var d = degrees % 360f
            if (d <= -180f) d += 360f
            if (d > 180f) d -= 360f
            return d
        }

        private fun finiteOr(value: Float, default: Float) = if (value.isFinite()) value else default
    }
}

/**
 * A text or sticker drawn over the picture. It lives on a clip of an
 * overlay track, which gives it its time range and stacking order (see
 * [com.editor.veyro.core.model.timeline.TrackKind.OVERLAY]).
 *
 * This is the single description that the editor preview, the export and
 * the on-screen editing handles all render from.
 */
public data class Overlay(
    val content: OverlayContent,
    val transform: OverlayTransform = OverlayTransform(),
) {
    val isText: Boolean get() = content is OverlayContent.Text

    /** Whether rendering draws anything: visible, and not empty text. */
    val isVisible: Boolean
        get() = transform.opacity > 0f && (content !is OverlayContent.Text || content.text.isNotBlank())
}
