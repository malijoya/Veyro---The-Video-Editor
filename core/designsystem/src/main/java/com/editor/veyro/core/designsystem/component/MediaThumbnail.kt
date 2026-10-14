package com.editor.veyro.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.icon.VeyroIcons

/** The kind of media a thumbnail is for. Decides how it is decoded and which placeholder shows. */
public enum class ThumbnailKind { VIDEO, IMAGE, AUDIO }

/**
 * What to load: a media URI rendered at about [sizePx] on its longer side.
 * [timeUs] picks the video frame. It's ignored for images and audio.
 */
@Immutable
public data class ThumbnailRequest(val uri: String, val kind: ThumbnailKind, val sizePx: Int, val timeUs: Long = 0)

/**
 * Supplies thumbnail bitmaps. The app provides the real media-backed
 * implementation through [LocalThumbnailProvider]. The default returns
 * nothing, so previews and tests show the placeholder.
 */
public fun interface ThumbnailProvider {
    public suspend fun load(request: ThumbnailRequest): ImageBitmap?
}

public val LocalThumbnailProvider: ProvidableCompositionLocal<ThumbnailProvider> =
    staticCompositionLocalOf { ThumbnailProvider { null } }

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data class Loaded(val bitmap: ImageBitmap) : ThumbnailState
    data object Unavailable : ThumbnailState
}

/**
 * Shows the thumbnail of a media file, cropped to fill its bounds.
 *
 * - While loading, the tinted background shows.
 * - If the file can't be read (deleted, or access revoked), [unavailableIcon]
 *   is shown, and [unavailableDescription] replaces [contentDescription] for
 *   accessibility services.
 * - Audio without cover art shows a music note. That's normal, not an error,
 *   so the description is unchanged.
 * - Pass `contentDescription = null` when the surrounding item already
 *   describes the media.
 */
@Composable
public fun MediaThumbnail(
    uri: String?,
    kind: ThumbnailKind,
    contentDescription: String?,
    unavailableDescription: String,
    modifier: Modifier = Modifier,
    unavailableIcon: ImageVector = VeyroIcons.BrokenImage,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val provider = LocalThumbnailProvider.current
    var sizePx by remember { mutableIntStateOf(0) }
    val state by produceState<ThumbnailState>(ThumbnailState.Loading, uri, kind, sizePx, provider) {
        if (sizePx == 0) return@produceState
        value = uri?.let { provider.load(ThumbnailRequest(it, kind, sizePx)) }
            ?.let { ThumbnailState.Loaded(it) }
            ?: ThumbnailState.Unavailable
    }
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription =
                if (state == ThumbnailState.Unavailable && kind != ThumbnailKind.AUDIO) {
                    unavailableDescription
                } else {
                    contentDescription
                }
            role = Role.Image
        }
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onSizeChanged { size ->
                // Round up to 128 px steps so nearby sizes share cache entries.
                val longest = maxOf(size.width, size.height).coerceIn(64, 1_024)
                sizePx = ((longest + 127) / 128) * 128
            }
            .then(semantics),
    ) {
        when (val current = state) {
            ThumbnailState.Loading -> Unit
            is ThumbnailState.Loaded -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            ThumbnailState.Unavailable -> Icon(
                imageVector = if (kind == ThumbnailKind.AUDIO) VeyroIcons.MusicNote else unavailableIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
