package com.editor.veyro.feature.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.component.OverlayBadge
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.core.model.time.FrameRate
import java.util.Locale

/**
 * Square media cell: thumbnail, a badge (duration, "Photo" or "Audio"), a
 * title and a detail line. [overlay] draws extra state on top of the
 * thumbnail (selection, progress, missing).
 */
@Composable
internal fun MediaTile(
    uri: String,
    kind: ThumbnailKind,
    title: String,
    subtitle: String?,
    badge: String?,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small),
        ) {
            MediaThumbnail(
                uri = uri,
                kind = kind,
                contentDescription = null,
                unavailableDescription = stringResource(R.string.project_media_unavailable),
                modifier = Modifier.fillMaxSize(),
            )
            if (badge != null) {
                OverlayBadge(
                    badge,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.xs + Spacing.xxs),
                )
            }
            overlay()
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val MediaKind.thumbnailKind: ThumbnailKind
    get() = when (this) {
        MediaKind.VIDEO -> ThumbnailKind.VIDEO
        MediaKind.IMAGE -> ThumbnailKind.IMAGE
        MediaKind.AUDIO -> ThumbnailKind.AUDIO
    }

@Composable
internal fun MediaRef.badgeText(): String = when (kind) {
    MediaKind.VIDEO, MediaKind.AUDIO -> DurationFormat.clock(durationUs)
    MediaKind.IMAGE -> stringResource(R.string.project_badge_photo)
}

/** Track title for tagged audio, otherwise the file name. */
@Composable
internal fun MediaRef.titleText(): String =
    tags?.title?.takeIf { kind == MediaKind.AUDIO }
        ?: displayName
        ?: stringResource(R.string.project_media_untitled)

/**
 * One line of technical detail:
 * - video: "1920×1080 · 29.97 fps"
 * - image: "4000×3000"
 * - audio: "Artist · 44.1 kHz · Stereo"
 *
 * Returns null when nothing is known.
 */
@Composable
internal fun MediaRef.detailText(): String? {
    val parts = buildList {
        when (kind) {
            MediaKind.VIDEO, MediaKind.IMAGE -> {
                if (displayWidthPx > 0 && displayHeightPx > 0) add("${displayWidthPx}×$displayHeightPx")
                video?.frameRate?.let { add(stringResource(R.string.project_fps, formatFps(it))) }
            }
            MediaKind.AUDIO -> {
                tags?.artist?.let(::add)
                audio?.sampleRateHz?.let { add(stringResource(R.string.project_khz, formatKhz(it))) }
                audio?.channelCount?.let {
                    add(
                        when (it) {
                            1 -> stringResource(R.string.project_mono)
                            2 -> stringResource(R.string.project_stereo)
                            else -> stringResource(R.string.project_channels, it)
                        },
                    )
                }
            }
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "30", "29.97", "23.976". At most three decimals, with trailing zeros dropped. */
internal fun formatFps(rate: FrameRate): String = trimDecimals(String.format(Locale.ROOT, "%.3f", rate.fps))

/** "44.1", "48", "22.05". */
internal fun formatKhz(sampleRateHz: Int): String = trimDecimals(String.format(Locale.ROOT, "%.2f", sampleRateHz / 1000.0))

private fun trimDecimals(value: String): String = value.trimEnd('0').trimEnd('.')
