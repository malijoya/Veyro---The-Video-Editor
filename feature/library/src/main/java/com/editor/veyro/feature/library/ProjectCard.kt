package com.editor.veyro.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.component.OverlayBadge
import com.editor.veyro.core.designsystem.component.ThumbnailKind
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.time.DurationFormat

/**
 * A saved project in a grid: cover thumbnail, name, item count and last edit.
 * Tapping opens it. Rename and delete are in the overflow menu, and are also
 * exposed as accessibility actions so TalkBack users don't need the menu.
 */
@Composable
internal fun ProjectCard(
    project: ProjectCardUi,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val renameLabel = stringResource(R.string.library_action_rename)
    val deleteLabel = stringResource(R.string.library_action_delete)
    val itemsText = pluralStringResource(R.plurals.library_item_count, project.mediaCount, project.mediaCount)
    val editedText = stringResource(R.string.library_edited, relativeTime(project.updatedAtEpochMs))

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.library_action_open), onClick = onOpen)
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            MediaThumbnail(
                uri = project.coverUri,
                kind = when (project.coverKind) {
                    MediaKind.VIDEO -> ThumbnailKind.VIDEO
                    MediaKind.AUDIO -> ThumbnailKind.AUDIO
                    MediaKind.IMAGE, null -> ThumbnailKind.IMAGE
                },
                contentDescription = null,
                unavailableDescription = stringResource(R.string.library_cover_unavailable),
                modifier = Modifier.fillMaxSize(),
            )
            OverlayBadge(
                text = when {
                    project.videoDurationUs > 0 -> DurationFormat.clock(project.videoDurationUs)
                    project.hasImages -> stringResource(R.string.library_badge_photos)
                    else -> stringResource(R.string.library_badge_audio)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.sm),
            )
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm),
        ) {
            Column(Modifier.weight(1f).padding(top = Spacing.xs)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$itemsText · $editedText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_more_options, project.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(renameLabel) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Localized "5 min. ago" style text. */
internal fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
