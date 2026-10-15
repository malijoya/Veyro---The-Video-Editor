package com.editor.veyro.feature.editor.overlay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.feature.editor.R
import com.editor.veyro.feature.editor.StickerChoice

/**
 * Picks a sticker: one of the project's images, or a new image from the
 * system photo picker. PNG and WebP images keep their transparency. A new
 * image joins the project's media but not the main track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerSheet(
    choices: List<StickerChoice>,
    onPickProjectImage: (MediaId) -> Unit,
    onPickDeviceImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickDeviceImage(uri.toString())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Layout.screenGutter).padding(bottom = Spacing.lg),
        ) {
            Text(stringResource(R.string.sticker_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.sticker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sticker_from_device)) }
            if (choices.isNotEmpty()) {
                Text(stringResource(R.string.sticker_from_project), style = MaterialTheme.typography.labelLarge)
                val unavailable = stringResource(R.string.editor_media_unavailable)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.heightIn(max = 280.dp),
                ) {
                    items(choices, key = { it.mediaId.value }) { choice ->
                        MediaThumbnail(
                            uri = choice.uri,
                            kind = ThumbnailKind.IMAGE,
                            contentDescription = choice.title.ifBlank { null },
                            unavailableDescription = unavailable,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(role = Role.Button) { onPickProjectImage(choice.mediaId) },
                        )
                    }
                }
            }
        }
    }
}
