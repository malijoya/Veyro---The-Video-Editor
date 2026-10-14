package com.editor.veyro.feature.project.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.feature.project.R
import com.editor.veyro.feature.project.detailText
import com.editor.veyro.feature.project.thumbnailKind
import com.editor.veyro.feature.project.titleText
import com.editor.veyro.media.playback.PreviewError
import com.editor.veyro.media.playback.PreviewState
import com.editor.veyro.media.playback.PreviewStatus
import com.editor.veyro.media.playback.ui.BindPreviewLifecycle
import com.editor.veyro.media.playback.ui.PreviewControls
import com.editor.veyro.media.playback.ui.PreviewSurface

@Composable
public fun MediaPreviewRoute(viewModel: MediaPreviewViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    val player by viewModel.controller.player.collectAsStateWithLifecycle()
    BindPreviewLifecycle(viewModel.controller)

    MediaPreviewScreen(
        uiState = uiState,
        playback = playback,
        surface = { modifier -> PreviewSurface(player, modifier) },
        onBack = onBack,
        onSelect = viewModel::select,
        onPlayPause = viewModel.controller::togglePlayPause,
        onSeekTo = viewModel.controller::seekTo,
        onSeekBy = viewModel.controller::seekBy,
        onRetry = viewModel.controller::retry,
    )
}

/**
 * Full-screen preview of one source asset: video with transport controls,
 * audio with artwork and controls, or a still image. A strip at the bottom
 * switches between the project's assets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPreviewScreen(
    uiState: MediaPreviewUiState,
    playback: PreviewState,
    surface: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onSelect: (MediaId) -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val ready = uiState as? MediaPreviewUiState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready?.media?.titleText().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ready?.media?.detailText()?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.project_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (uiState) {
                MediaPreviewUiState.Loading -> CircularProgressIndicator()
                MediaPreviewUiState.NotFound -> EmptyState(
                    icon = VeyroIcons.VideoLibrary,
                    title = stringResource(R.string.preview_not_found_title),
                    message = stringResource(R.string.preview_not_found_message),
                )
                MediaPreviewUiState.LoadFailed -> EmptyState(
                    icon = VeyroIcons.BrokenImage,
                    title = stringResource(R.string.project_load_failed_title),
                    message = stringResource(R.string.project_load_failed_message),
                )
                is MediaPreviewUiState.Ready -> Column(Modifier.fillMaxSize()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Stage(uiState.media, playback, surface, onRetry)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = Layout.screenGutter, vertical = Spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (uiState.media.kind == MediaKind.IMAGE) {
                            Text(
                                stringResource(R.string.preview_image_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = Spacing.md),
                            )
                        } else {
                            PreviewControls(
                                state = playback,
                                onPlayPause = onPlayPause,
                                onSeekTo = onSeekTo,
                                onSeekBy = onSeekBy,
                                modifier = Modifier.widthIn(max = 720.dp),
                            )
                        }
                        if (uiState.projectMedia.size > 1) {
                            MediaStrip(uiState.projectMedia, uiState.media.id, onSelect)
                        }
                    }
                }
            }
        }
    }
}

/** The area above the controls: the video surface, audio artwork, or the still image. */
@Composable
private fun Stage(media: MediaRef, playback: PreviewState, surface: @Composable (Modifier) -> Unit, onRetry: () -> Unit) {
    when (media.kind) {
        // The surface stays in the tree while loading so the player can attach to it.
        MediaKind.VIDEO -> surface(Modifier.fillMaxSize())
        MediaKind.AUDIO -> MediaThumbnail(
            uri = media.uri,
            kind = media.kind.thumbnailKind,
            contentDescription = null,
            unavailableDescription = stringResource(R.string.project_media_unavailable),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth(0.7f)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large),
        )
        MediaKind.IMAGE -> MediaThumbnail(
            uri = media.uri,
            kind = media.kind.thumbnailKind,
            contentDescription = media.titleText(),
            unavailableDescription = stringResource(R.string.project_media_unavailable),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        )
    }
    if (media.kind != MediaKind.IMAGE) {
        when (playback.status) {
            PreviewStatus.LOADING -> CircularProgressIndicator()
            PreviewStatus.ERROR -> PlaybackErrorPanel(playback.error ?: PreviewError.UNKNOWN, onRetry)
            else -> Unit
        }
    }
}

@Composable
private fun PlaybackErrorPanel(error: PreviewError, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .padding(Layout.screenGutter)
            .widthIn(max = 360.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .padding(Spacing.xl),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.preview_error_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(error.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.preview_retry)) }
    }
}

@Composable
private fun MediaStrip(media: List<MediaRef>, selected: MediaId, onSelect: (MediaId) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(vertical = Spacing.sm),
    ) {
        items(media, key = { it.id.value }) { item ->
            val isSelected = item.id == selected
            val title = item.titleText()
            MediaThumbnail(
                uri = item.uri,
                kind = item.kind.thumbnailKind,
                contentDescription = title,
                unavailableDescription = stringResource(R.string.project_media_unavailable),
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                        } else {
                            Modifier
                        },
                    )
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab, onClickLabel = stringResource(R.string.preview_show)) { onSelect(item.id) },
            )
        }
    }
}

private val PreviewError.messageRes: Int
    get() = when (this) {
        PreviewError.FILE_NOT_FOUND -> R.string.preview_error_not_found
        PreviewError.NO_PERMISSION -> R.string.preview_error_permission
        PreviewError.UNSUPPORTED_FORMAT -> R.string.preview_error_unsupported
        PreviewError.EXCEEDS_DEVICE_CAPABILITIES -> R.string.preview_error_capabilities
        PreviewError.DECODER_FAILED -> R.string.preview_error_decoder
        PreviewError.CORRUPT_FILE -> R.string.preview_error_corrupt
        PreviewError.AUDIO_OUTPUT_FAILED -> R.string.preview_error_audio
        PreviewError.UNKNOWN -> R.string.preview_error_unknown
    }
