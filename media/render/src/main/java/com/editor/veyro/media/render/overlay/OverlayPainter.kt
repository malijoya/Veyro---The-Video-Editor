package com.editor.veyro.media.render.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayBox
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayGeometry
import com.editor.veyro.core.model.overlay.TextAlign
import com.editor.veyro.core.model.overlay.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws [Overlay]s onto an Android [Canvas], in canvas pixels. It is the one
 * drawing routine for overlays: export and the editor preview (through
 * [TimelineOverlay]) and the editor's live editing layer all call it, so
 * text wraps, sits and looks the same everywhere.
 *
 * Layout is resolution-independent: sizes are fractions of the canvas (see
 * [OverlayGeometry]), and text is measured with linear (unhinted) advances,
 * so a 720p preview and a 4K export break lines in the same places.
 *
 * How an overlay is drawn, about its centre:
 * - Moved to its centre, turned clockwise by its rotation, then scaled.
 * - Text: background box, then shadow, then outline, then fill.
 * - Sticker: the image scaled into its box, with its own transparency.
 * - Opacity applies to the whole overlay at once (text, outline, shadow and
 *   background fade together, without the overlapping parts adding up).
 *
 * Not thread-safe: it caches text layouts. Use one instance per thread.
 */
public class OverlayPainter(private val fonts: FontResolver = FontResolver()) {

    private val layouts = object : LinkedHashMap<LayoutKey, TextBlock>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, TextBlock>): Boolean = size > MAX_CACHED_LAYOUTS
    }
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The overlay's box on the canvas (its content at its scale and
     * rotation), for hit-testing and handles. [imageWidthPx] and
     * [imageHeightPx] are a sticker's upright size; ignored for text.
     */
    public fun box(overlay: Overlay, canvasWidthPx: Int, canvasHeightPx: Int, imageWidthPx: Int = 0, imageHeightPx: Int = 0): OverlayBox {
        val (width, height) = contentSize(overlay, canvasWidthPx, canvasHeightPx, imageWidthPx, imageHeightPx)
        return OverlayGeometry.box(overlay.transform, width, height, canvasWidthPx, canvasHeightPx)
    }

    /**
     * Every pixel drawing [overlay] may touch, as an axis-aligned rectangle
     * in canvas pixels: the box plus room for outline, shadow and glyphs
     * that reach outside their line (italics, script fonts).
     */
    public fun inkBounds(overlay: Overlay, canvasWidthPx: Int, canvasHeightPx: Int, imageWidthPx: Int = 0, imageHeightPx: Int = 0): RectF {
        val (width, height) = contentSize(overlay, canvasWidthPx, canvasHeightPx, imageWidthPx, imageHeightPx)
        val margin = localMargin(overlay, canvasWidthPx, canvasHeightPx)
        val local = OverlayGeometry.box(
            overlay.transform,
            width + 2 * margin,
            height + 2 * margin,
            canvasWidthPx,
            canvasHeightPx,
        )
        val (left, top, right, bottom) = local.bounds.toList()
        return RectF(left, top, right, bottom)
    }

    /**
     * Draws [overlay] on [canvas], whose drawing area is a
     * [canvasWidthPx] × [canvasHeightPx] canvas (scale the canvas first to
     * draw at another size). [image] is a sticker's decoded, upright image;
     * a sticker without one draws nothing.
     */
    public fun draw(
        canvas: Canvas,
        overlay: Overlay,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        image: Bitmap? = null,
        imageWidthPx: Int = image?.width ?: 0,
        imageHeightPx: Int = image?.height ?: 0,
    ) {
        if (!overlay.isVisible) return
        val transform = overlay.transform
        canvas.save()
        canvas.translate(transform.centerX * canvasWidthPx, transform.centerY * canvasHeightPx)
        canvas.rotate(transform.rotationDegrees) // Android canvases turn clockwise for positive angles.
        canvas.scale(transform.scale, transform.scale)
        when (val content = overlay.content) {
            is OverlayContent.Text -> drawText(canvas, content, transform.opacity, canvasWidthPx, canvasHeightPx)
            OverlayContent.Sticker -> if (image != null) {
                val (width, height) = OverlayGeometry.stickerSizePx(imageWidthPx, imageHeightPx, canvasWidthPx, canvasHeightPx)
                stickerPaint.alpha = (transform.opacity * 255).roundToInt()
                canvas.drawBitmap(image, null, RectF(-width / 2, -height / 2, width / 2, height / 2), stickerPaint)
            }
        }
        canvas.restore()
    }

    /** The content's size at scale 1, in canvas pixels. */
    internal fun contentSize(overlay: Overlay, canvasWidthPx: Int, canvasHeightPx: Int, imageWidthPx: Int, imageHeightPx: Int): Pair<Float, Float> =
        when (val content = overlay.content) {
            is OverlayContent.Text -> textBlock(content, canvasWidthPx, canvasHeightPx).let { it.boxWidth to it.boxHeight }
            OverlayContent.Sticker -> OverlayGeometry.stickerSizePx(imageWidthPx, imageHeightPx, canvasWidthPx, canvasHeightPx)
        }

    // ---- Text ----

    private data class LayoutKey(
        val text: String,
        val style: TextStyle,
        val canvasWidthPx: Int,
        val canvasHeightPx: Int,
    )

    /**
     * Laid-out text and its box. The box is the text's own extent (the
     * widest line by the layout's height), plus the background's padding
     * when there is a background.
     */
    private class TextBlock(val layout: StaticLayout, val paint: TextPaint, val padding: Float, val fontPx: Float) {
        val textWidth: Float = layout.width.toFloat()
        val textHeight: Float = layout.height.toFloat()
        val boxWidth: Float = max(textWidth, fontPx * MIN_BOX_EMS) + 2 * padding
        val boxHeight: Float = textHeight + 2 * padding
    }

    private fun textBlock(content: OverlayContent.Text, canvasWidthPx: Int, canvasHeightPx: Int): TextBlock {
        val key = LayoutKey(content.text, content.style, canvasWidthPx, canvasHeightPx)
        layouts[key]?.let { return it }
        val style = content.style
        val fontPx = OverlayGeometry.fontSizePx(style, canvasWidthPx, canvasHeightPx)
        // Linear advances: glyph widths scale exactly with the font size, so wrapping doesn't depend on the output resolution.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = fonts.typeface(style.font, style.bold, style.italic)
            textSize = fontPx
        }
        val text = content.text
        val wrapPx = ceil(OverlayGeometry.wrapWidthPx(canvasWidthPx)).toInt().coerceAtLeast(1)
        // First pass finds the widest line; the second lays out at exactly that width, so alignment works inside the box.
        val measured = staticLayout(text, paint, wrapPx, Layout.Alignment.ALIGN_NORMAL)
        val widest = (0 until measured.lineCount).maxOfOrNull { measured.getLineWidth(it) } ?: 0f
        val width = (ceil(widest).toInt() + 1).coerceIn(1, wrapPx)
        val layout = staticLayout(text, paint, width, alignmentFor(text, style.align))
        val padding = style.background?.let { it.paddingFraction * fontPx } ?: 0f
        return TextBlock(layout, paint, padding, fontPx).also { layouts[key] = it }
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .build()

    /** Layout alignments are relative to the paragraph direction; the model's are visual. */
    private fun alignmentFor(text: String, align: TextAlign): Layout.Alignment {
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
        return when (align) {
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.LEFT -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            TextAlign.RIGHT -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
        }
    }

    private fun drawText(canvas: Canvas, content: OverlayContent.Text, opacity: Float, canvasWidthPx: Int, canvasHeightPx: Int) {
        val block = textBlock(content, canvasWidthPx, canvasHeightPx)
        val style = content.style
        val halfWidth = block.boxWidth / 2
        val halfHeight = block.boxHeight / 2
        val faded = opacity < 1f
        if (faded) {
            // Opacity for the overlay as a whole: draw it into a layer, then blend the layer once.
            val margin = localMargin(Overlay(content), canvasWidthPx, canvasHeightPx)
            canvas.saveLayerAlpha(
                RectF(-halfWidth - margin, -halfHeight - margin, halfWidth + margin, halfHeight + margin),
                (opacity * 255).roundToInt(),
            )
        }

        style.background?.let { background ->
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = background.color.value
            val radius = background.cornerFraction * block.fontPx
            canvas.drawRoundRect(RectF(-halfWidth, -halfHeight, halfWidth, halfHeight), radius, radius, boxPaint)
        }

        canvas.save()
        canvas.translate(-block.textWidth / 2, -block.textHeight / 2)
        val paint = block.paint
        val shadow = style.shadow
        val outline = style.outline
        // The shadow goes under whatever is drawn first: the outline when there is one, else the fill.
        fun applyShadow() {
            if (shadow == null) return
            // A radius of exactly 0 would remove the shadow, so a sharp shadow uses the smallest blur instead.
            val radius = max(shadow.blurFraction * block.fontPx, MIN_SHADOW_RADIUS_PX)
            paint.setShadowLayer(radius, shadow.offsetXFraction * block.fontPx, shadow.offsetYFraction * block.fontPx, shadow.color.value)
        }
        if (outline != null) {
            applyShadow()
            paint.style = Paint.Style.STROKE
            // The stroke is centred on the glyph edge; the fill covers its inner half.
            paint.strokeWidth = 2 * outline.widthFraction * block.fontPx
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = outline.color.value
            block.layout.draw(canvas)
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        } else {
            applyShadow()
        }
        paint.color = style.color.value
        block.layout.draw(canvas)
        paint.clearShadowLayer()
        canvas.restore()

        if (faded) canvas.restore()
    }

    /** Extra room around the box (before scale) that drawing may cover. */
    private fun localMargin(overlay: Overlay, canvasWidthPx: Int, canvasHeightPx: Int): Float {
        val content = overlay.content as? OverlayContent.Text ?: return STICKER_MARGIN_PX
        val style = content.style
        val fontPx = OverlayGeometry.fontSizePx(style, canvasWidthPx, canvasHeightPx)
        val outline = style.outline?.widthFraction ?: 0f
        val shadow = style.shadow?.let { it.blurFraction + max(abs(it.offsetXFraction), abs(it.offsetYFraction)) } ?: 0f
        return fontPx * (GLYPH_OVERHANG_EMS + outline + shadow) + MIN_SHADOW_RADIUS_PX + 2f
    }

    private companion object {
        const val MAX_CACHED_LAYOUTS = 32

        /** Empty or tiny text still gets a box this many font sizes wide, so it can be grabbed. */
        const val MIN_BOX_EMS = 0.5f

        /** Room for glyphs that reach outside their line box (italics, swashes in script fonts). */
        const val GLYPH_OVERHANG_EMS = 0.35f

        const val MIN_SHADOW_RADIUS_PX = 0.5f
        const val STICKER_MARGIN_PX = 2f
    }
}
