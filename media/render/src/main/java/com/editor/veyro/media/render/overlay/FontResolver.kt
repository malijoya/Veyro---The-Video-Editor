package com.editor.veyro.media.render.overlay

import android.graphics.Typeface
import com.editor.veyro.core.model.overlay.GenericFont
import com.editor.veyro.core.model.overlay.OverlayFont
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps [OverlayFont]s to Android typefaces, with fallback at two levels:
 *
 * 1. **Family.** Each font names a system family (for example
 *    `sans-serif-condensed`). Android resolves an unknown family name to
 *    the default typeface without any error, so a missing family is
 *    detected by that identity and replaced with the font's generic
 *    [OverlayFont.fallback] (sans-serif, serif or monospace), which every
 *    device has.
 * 2. **Glyphs.** Characters the chosen family doesn't cover (emoji, CJK,
 *    Arabic, Devanagari, ...) are drawn from the system's fallback fonts by
 *    Android's text stack, per character. Nothing extra is needed here, and
 *    it applies equally to the preview and the export.
 *
 * Bold and italic use the family's real weights when it has them, and
 * synthetic emboldening or slanting otherwise.
 *
 * Thread-safe.
 */
public class FontResolver internal constructor(private val familyName: (OverlayFont) -> String?) {

    public constructor() : this({ font -> systemFamilyName(font) })

    private val families = ConcurrentHashMap<OverlayFont, Resolved>()

    private class Resolved(val typeface: Typeface, val isFallback: Boolean)

    /** The typeface for [font] in the given style. On a device without the font's family, its generic fallback. */
    public fun typeface(font: OverlayFont, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(resolve(font).typeface, style)
    }

    /** Whether [font] is drawn with its own family (true) or replaced by its generic fallback (false). */
    public fun isAvailable(font: OverlayFont): Boolean = !resolve(font).isFallback

    private fun resolve(font: OverlayFont): Resolved = families.getOrPut(font) {
        val name = familyName(font) ?: return@getOrPut Resolved(generic(font.fallback), isFallback = false)
        val typeface = Typeface.create(name, Typeface.NORMAL)
        // An unknown name comes back as exactly Typeface.DEFAULT.
        if (typeface === Typeface.DEFAULT) Resolved(generic(font.fallback), isFallback = true) else Resolved(typeface, isFallback = false)
    }

    private fun generic(font: GenericFont): Typeface = when (font) {
        GenericFont.SANS -> Typeface.SANS_SERIF
        GenericFont.SERIF -> Typeface.SERIF
        GenericFont.MONOSPACE -> Typeface.MONOSPACE
    }

    internal companion object {
        /** The Android family behind each font. Null for the generic families, which are used directly. */
        fun systemFamilyName(font: OverlayFont): String? = when (font) {
            OverlayFont.SANS, OverlayFont.SERIF, OverlayFont.MONOSPACE -> null
            OverlayFont.SANS_CONDENSED -> "sans-serif-condensed"
            OverlayFont.SANS_LIGHT -> "sans-serif-light"
            OverlayFont.SANS_BLACK -> "sans-serif-black"
            OverlayFont.HANDWRITING -> "cursive"
            OverlayFont.CASUAL -> "casual"
            OverlayFont.SMALL_CAPS -> "sans-serif-smallcaps"
        }
    }
}
