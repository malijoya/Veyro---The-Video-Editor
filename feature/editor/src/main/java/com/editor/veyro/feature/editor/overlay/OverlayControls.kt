package com.editor.veyro.feature.editor.overlay

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.overlay.Argb
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayFont
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.overlay.TextAlign
import com.editor.veyro.core.model.overlay.TextBackground
import com.editor.veyro.core.model.overlay.TextOutline
import com.editor.veyro.core.model.overlay.TextShadow
import com.editor.veyro.core.model.overlay.TextStyle
import com.editor.veyro.core.timeline.LayerMove
import com.editor.veyro.feature.editor.OverlayUi
import com.editor.veyro.feature.editor.R
import com.editor.veyro.media.render.overlay.FontResolver
import kotlin.math.roundToInt

/** What the overlay panel can do. Each maps to one ViewModel call. */
internal class OverlayActions(
    /** Shows a change at once without saving it (sliders, typing). */
    val onPreview: (Overlay) -> Unit,
    /** Saves the change shown by [onPreview] as one undo step. */
    val onCommit: () -> Unit,
    /** Saves a change at once (a color tap, a switch). */
    val onSet: (Overlay) -> Unit,
    val onArrange: (LayerMove) -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
)

private enum class OverlayTab(@StringRes val label: Int) {
    TEXT(R.string.overlay_tab_text),
    FONT(R.string.overlay_tab_font),
    COLOR(R.string.overlay_tab_color),
    OUTLINE(R.string.overlay_tab_outline),
    SHADOW(R.string.overlay_tab_shadow),
    BOX(R.string.overlay_tab_box),
    ADJUST(R.string.overlay_tab_adjust),
    LAYER(R.string.overlay_tab_layer),
}

/**
 * The text and sticker panel, shown while an overlay is selected. Tabs keep
 * each screen short, so the preview keeps its room on a phone. Sliders show
 * their effect live and save once when released; color taps and switches
 * save at once. Everything is also reachable without gestures on the
 * preview (position, size and rotation are under Adjust).
 *
 * @param focusText open the Text tab and put the cursor in it (a new text,
 *   or a tap on the selected text in the preview). [onFocusHandled] clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverlayControls(
    overlay: OverlayUi,
    enabled: Boolean,
    focusText: Boolean,
    onFocusHandled: () -> Unit,
    actions: OverlayActions,
) {
    val text = overlay.overlay.content as? OverlayContent.Text
    val tabs = if (text != null) OverlayTab.entries else listOf(OverlayTab.ADJUST, OverlayTab.LAYER)
    var chosen by rememberSaveable(overlay.clipId.value) { mutableStateOf(tabs.first()) }
    LaunchedEffect(focusText) { if (focusText && text != null) chosen = OverlayTab.TEXT }
    val tab = if (chosen in tabs) chosen else tabs.first()

    Column(Modifier.fillMaxWidth()) {
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 0.dp, containerColor = Color.Transparent) {
            tabs.forEach { candidate ->
                Tab(
                    selected = candidate == tab,
                    onClick = {
                        actions.onCommit() // Leaving a tab saves what was being typed or dragged there.
                        chosen = candidate
                    },
                    text = { Text(stringResource(candidate.label)) },
                )
            }
        }
        Box(Modifier.padding(top = Spacing.sm)) {
            val current = overlay.overlay
            when (tab) {
                OverlayTab.TEXT -> if (text != null) TextTab(current, text, enabled, focusText, onFocusHandled, actions)
                OverlayTab.FONT -> if (text != null) FontTab(current, text, enabled, actions)
                OverlayTab.COLOR -> if (text != null) ColorTab(current, text, enabled, actions)
                OverlayTab.OUTLINE -> if (text != null) OutlineTab(current, text, enabled, actions)
                OverlayTab.SHADOW -> if (text != null) ShadowTab(current, text, enabled, actions)
                OverlayTab.BOX -> if (text != null) BoxTab(current, text, enabled, actions)
                OverlayTab.ADJUST -> AdjustTab(current, enabled, actions)
                OverlayTab.LAYER -> LayerTab(enabled, actions)
            }
        }
    }
}

private fun Overlay.withStyle(text: OverlayContent.Text, style: TextStyle) = copy(content = text.copy(style = style))

// ---- Tabs ----

@Composable
private fun TextTab(
    overlay: Overlay,
    text: OverlayContent.Text,
    enabled: Boolean,
    focusText: Boolean,
    onFocusHandled: () -> Unit,
    actions: OverlayActions,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(text.text, TextRange(text.text.length))) }
    // Undo, redo or another device changed the text: show it.
    LaunchedEffect(text.text) { if (field.text != text.text) field = TextFieldValue(text.text, TextRange(text.text.length)) }
    LaunchedEffect(focusText) {
        if (focusText) {
            // Everything selected, so typing replaces the placeholder.
            field = field.copy(selection = TextRange(0, field.text.length))
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = field,
            onValueChange = { value ->
                val limited = if (value.text.length > OverlayContent.Text.MAX_LENGTH) value.copy(text = value.text.take(OverlayContent.Text.MAX_LENGTH)) else value
                field = limited
                if (limited.text != text.text) actions.onPreview(overlay.copy(content = text.copy(text = limited.text)))
            },
            enabled = enabled,
            label = { Text(stringResource(R.string.overlay_text_label)) },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (!it.isFocused) actions.onCommit() },
        )
        TextButton(
            onClick = {
                actions.onCommit()
                focusManager.clearFocus()
            },
            enabled = enabled,
        ) { Text(stringResource(R.string.overlay_done)) }
    }
}

@Composable
private fun FontTab(overlay: Overlay, text: OverlayContent.Text, enabled: Boolean, actions: OverlayActions) {
    val style = text.style
    val fonts = remember { FontResolver() }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OverlayFont.entries.forEach { font ->
                FilterChip(
                    selected = style.font == font,
                    onClick = { actions.onSet(overlay.withStyle(text, style.copy(font = font))) },
                    enabled = enabled,
                    // Each name is shown in its own font (or what the device falls back to).
                    label = { Text(stringResource(fontLabel(font)), fontFamily = FontFamily(fonts.typeface(font, bold = false, italic = false))) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = style.bold,
                onClick = { actions.onSet(overlay.withStyle(text, style.copy(bold = !style.bold))) },
                enabled = enabled,
                label = { Text(stringResource(R.string.overlay_bold), fontWeight = FontWeight.Bold) },
            )
            FilterChip(
                selected = style.italic,
                onClick = { actions.onSet(overlay.withStyle(text, style.copy(italic = !style.italic))) },
                enabled = enabled,
                label = { Text(stringResource(R.string.overlay_italic), fontStyle = FontStyle.Italic) },
            )
            Box(Modifier.weight(1f))
            AlignButton(TextAlign.LEFT, VeyroIcons.AlignLeft, R.string.overlay_align_left, style, overlay, text, enabled, actions)
            AlignButton(TextAlign.CENTER, VeyroIcons.AlignCenter, R.string.overlay_align_center, style, overlay, text, enabled, actions)
            AlignButton(TextAlign.RIGHT, VeyroIcons.AlignRight, R.string.overlay_align_right, style, overlay, text, enabled, actions)
        }
        LabeledSlider(
            label = stringResource(R.string.overlay_size),
            valueText = stringResource(R.string.editor_percent, (style.sizeFraction * 100).roundToInt()),
            value = style.sizeFraction,
            range = MIN_UI_TEXT_SIZE..MAX_UI_TEXT_SIZE,
            enabled = enabled,
            onChange = { actions.onPreview(overlay.withStyle(text, style.copy(sizeFraction = it))) },
            onDone = actions.onCommit,
        )
    }
}

@Composable
private fun AlignButton(
    align: TextAlign,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    @StringRes label: Int,
    style: TextStyle,
    overlay: Overlay,
    text: OverlayContent.Text,
    enabled: Boolean,
    actions: OverlayActions,
) {
    IconToggleButton(
        checked = style.align == align,
        onCheckedChange = { actions.onSet(overlay.withStyle(text, style.copy(align = align))) },
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun ColorTab(overlay: Overlay, text: OverlayContent.Text, enabled: Boolean, actions: OverlayActions) {
    val style = text.style
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.overlay_text_color), style = MaterialTheme.typography.labelLarge)
        ColorSwatches(style.color, enabled) { actions.onSet(overlay.withStyle(text, style.copy(color = it.withAlpha(style.color.alpha.coerceAtLeast(1))))) }
        LabeledSlider(
            label = stringResource(R.string.overlay_text_opacity),
            valueText = stringResource(R.string.editor_percent, (style.color.alpha * 100 / 255f).roundToInt()),
            value = style.color.alpha / 255f,
            range = 0f..1f,
            enabled = enabled,
            onChange = { actions.onPreview(overlay.withStyle(text, style.copy(color = style.color.withAlpha((it * 255).roundToInt())))) },
            onDone = actions.onCommit,
        )
    }
}

@Composable
private fun OutlineTab(overlay: Overlay, text: OverlayContent.Text, enabled: Boolean, actions: OverlayActions) {
    val style = text.style
    val outline = style.outline
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SwitchRow(R.string.overlay_outline, outline != null, enabled) { on ->
            actions.onSet(overlay.withStyle(text, style.copy(outline = if (on) TextOutline(Argb.BLACK) else null)))
        }
        if (outline != null) {
            ColorSwatches(outline.color, enabled) { actions.onSet(overlay.withStyle(text, style.copy(outline = outline.copy(color = it)))) }
            LabeledSlider(
                label = stringResource(R.string.overlay_outline_width),
                valueText = stringResource(R.string.editor_percent, (outline.widthFraction * 100).roundToInt()),
                value = outline.widthFraction,
                range = TextOutline.MIN_WIDTH..MAX_UI_OUTLINE,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(outline = outline.copy(widthFraction = it)))) },
                onDone = actions.onCommit,
            )
        }
    }
}

@Composable
private fun ShadowTab(overlay: Overlay, text: OverlayContent.Text, enabled: Boolean, actions: OverlayActions) {
    val style = text.style
    val shadow = style.shadow
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SwitchRow(R.string.overlay_shadow, shadow != null, enabled) { on ->
            actions.onSet(overlay.withStyle(text, style.copy(shadow = if (on) TextShadow(Argb.BLACK.withAlpha(160)) else null)))
        }
        if (shadow != null) {
            ColorSwatches(shadow.color, enabled) { actions.onSet(overlay.withStyle(text, style.copy(shadow = shadow.copy(color = it.withAlpha(shadow.color.alpha))))) }
            LabeledSlider(
                label = stringResource(R.string.overlay_shadow_blur),
                valueText = stringResource(R.string.editor_percent, (shadow.blurFraction * 100).roundToInt()),
                value = shadow.blurFraction,
                range = 0f..MAX_UI_SHADOW_BLUR,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(shadow = shadow.copy(blurFraction = it)))) },
                onDone = actions.onCommit,
            )
            // One distance, down and to the right, as light usually falls.
            val distance = maxOf(shadow.offsetXFraction, shadow.offsetYFraction).coerceAtLeast(0f)
            LabeledSlider(
                label = stringResource(R.string.overlay_shadow_distance),
                valueText = stringResource(R.string.editor_percent, (distance * 100).roundToInt()),
                value = distance.coerceAtMost(MAX_UI_SHADOW_DISTANCE),
                range = 0f..MAX_UI_SHADOW_DISTANCE,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(shadow = shadow.copy(offsetXFraction = it, offsetYFraction = it)))) },
                onDone = actions.onCommit,
            )
        }
    }
}

@Composable
private fun BoxTab(overlay: Overlay, text: OverlayContent.Text, enabled: Boolean, actions: OverlayActions) {
    val style = text.style
    val background = style.background
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SwitchRow(R.string.overlay_background, background != null, enabled) { on ->
            actions.onSet(overlay.withStyle(text, style.copy(background = if (on) TextBackground(Argb.BLACK.withAlpha(160)) else null)))
        }
        if (background != null) {
            ColorSwatches(background.color, enabled) {
                actions.onSet(overlay.withStyle(text, style.copy(background = background.copy(color = it.withAlpha(background.color.alpha)))))
            }
            LabeledSlider(
                label = stringResource(R.string.overlay_background_opacity),
                valueText = stringResource(R.string.editor_percent, (background.color.alpha * 100 / 255f).roundToInt()),
                value = background.color.alpha / 255f,
                range = 0f..1f,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(background = background.copy(color = background.color.withAlpha((it * 255).roundToInt()))))) },
                onDone = actions.onCommit,
            )
            LabeledSlider(
                label = stringResource(R.string.overlay_background_padding),
                valueText = stringResource(R.string.editor_percent, (background.paddingFraction * 100).roundToInt()),
                value = background.paddingFraction.coerceAtMost(1f),
                range = 0f..1f,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(background = background.copy(paddingFraction = it)))) },
                onDone = actions.onCommit,
            )
            LabeledSlider(
                label = stringResource(R.string.overlay_background_corners),
                valueText = stringResource(R.string.editor_percent, (background.cornerFraction * 100).roundToInt()),
                value = background.cornerFraction.coerceAtMost(1f),
                range = 0f..1f,
                enabled = enabled,
                onChange = { actions.onPreview(overlay.withStyle(text, style.copy(background = background.copy(cornerFraction = it)))) },
                onDone = actions.onCommit,
            )
        }
    }
}

/** Opacity, size, rotation and position: the same changes as the preview gestures, without gestures. */
@Composable
private fun AdjustTab(overlay: Overlay, enabled: Boolean, actions: OverlayActions) {
    val transform = overlay.transform
    fun changed(change: (OverlayTransform) -> OverlayTransform) = overlay.copy(transform = change(transform))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LabeledSlider(
            label = stringResource(R.string.overlay_opacity),
            valueText = stringResource(R.string.editor_percent, (transform.opacity * 100).roundToInt()),
            value = transform.opacity,
            range = 0f..1f,
            enabled = enabled,
            onChange = { value -> actions.onPreview(changed { it.copy(opacity = value) }) },
            onDone = actions.onCommit,
        )
        LabeledSlider(
            label = stringResource(R.string.overlay_scale),
            valueText = stringResource(R.string.editor_percent, (transform.scale * 100).roundToInt()),
            value = transform.scale.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
            range = MIN_UI_SCALE..MAX_UI_SCALE,
            enabled = enabled,
            onChange = { value -> actions.onPreview(changed { it.copy(scale = value) }) },
            onDone = actions.onCommit,
        )
        LabeledSlider(
            label = stringResource(R.string.overlay_rotation),
            valueText = stringResource(R.string.overlay_degrees, transform.rotationDegrees.roundToInt()),
            value = transform.rotationDegrees,
            range = -179f..180f,
            enabled = enabled,
            onChange = { value -> actions.onPreview(changed { OverlayTransform.coerced(it.centerX, it.centerY, it.scale, value, it.opacity) }) },
            onDone = actions.onCommit,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { actions.onSet(changed { it.copy(centerX = 0.5f, centerY = 0.5f) }) }, enabled = enabled) {
                Text(stringResource(R.string.overlay_center))
            }
            OutlinedButton(onClick = { actions.onSet(changed { it.copy(rotationDegrees = 0f) }) }, enabled = enabled) {
                Text(stringResource(R.string.overlay_straighten))
            }
        }
    }
}

@Composable
private fun LayerTab(enabled: Boolean, actions: OverlayActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedButton(onClick = { actions.onArrange(LayerMove.FORWARD) }, enabled = enabled) { Text(stringResource(R.string.overlay_forward)) }
        OutlinedButton(onClick = { actions.onArrange(LayerMove.BACKWARD) }, enabled = enabled) { Text(stringResource(R.string.overlay_backward)) }
        OutlinedButton(onClick = { actions.onArrange(LayerMove.TO_FRONT) }, enabled = enabled) { Text(stringResource(R.string.overlay_to_front)) }
        OutlinedButton(onClick = { actions.onArrange(LayerMove.TO_BACK) }, enabled = enabled) { Text(stringResource(R.string.overlay_to_back)) }
        OutlinedButton(onClick = actions.onDuplicate, enabled = enabled) { Text(stringResource(R.string.overlay_duplicate)) }
        OutlinedButton(onClick = actions.onDelete, enabled = enabled) { Text(stringResource(R.string.overlay_delete)) }
    }
}

// ---- Building blocks ----

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** A row of color circles. The selection is matched on the color, ignoring alpha (opacity has its own slider). */
@Composable
private fun ColorSwatches(selected: Argb?, enabled: Boolean, onPick: (Argb) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        PALETTE.forEach { (color, name) ->
            val isSelected = selected != null && (selected.value and 0xFFFFFF) == (color.value and 0xFFFFFF)
            val description = stringResource(name)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(enabled = enabled) { onPick(color) }
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                        contentDescription = description
                    },
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Color(color.value), CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@StringRes
private fun fontLabel(font: OverlayFont): Int = when (font) {
    OverlayFont.SANS -> R.string.overlay_font_sans
    OverlayFont.SANS_CONDENSED -> R.string.overlay_font_condensed
    OverlayFont.SANS_LIGHT -> R.string.overlay_font_light
    OverlayFont.SANS_BLACK -> R.string.overlay_font_heavy
    OverlayFont.SERIF -> R.string.overlay_font_serif
    OverlayFont.MONOSPACE -> R.string.overlay_font_mono
    OverlayFont.HANDWRITING -> R.string.overlay_font_handwriting
    OverlayFont.CASUAL -> R.string.overlay_font_casual
    OverlayFont.SMALL_CAPS -> R.string.overlay_font_small_caps
}

private val PALETTE: List<Pair<Argb, Int>> = listOf(
    Argb.WHITE to R.string.overlay_color_white,
    Argb.BLACK to R.string.overlay_color_black,
    Argb(0xFF9E9E9E.toInt()) to R.string.overlay_color_gray,
    Argb(0xFFE53935.toInt()) to R.string.overlay_color_red,
    Argb(0xFFFB8C00.toInt()) to R.string.overlay_color_orange,
    Argb(0xFFFDD835.toInt()) to R.string.overlay_color_yellow,
    Argb(0xFF43A047.toInt()) to R.string.overlay_color_green,
    Argb(0xFF00ACC1.toInt()) to R.string.overlay_color_cyan,
    Argb(0xFF1E88E5.toInt()) to R.string.overlay_color_blue,
    Argb(0xFF8E24AA.toInt()) to R.string.overlay_color_purple,
    Argb(0xFFD81B60.toInt()) to R.string.overlay_color_pink,
)

private const val MIN_UI_TEXT_SIZE = 0.02f
private const val MAX_UI_TEXT_SIZE = 0.3f
private const val MAX_UI_OUTLINE = 0.2f
private const val MAX_UI_SHADOW_BLUR = 0.3f
private const val MAX_UI_SHADOW_DISTANCE = 0.2f
private const val MIN_UI_SCALE = 0.1f
private const val MAX_UI_SCALE = 5f
