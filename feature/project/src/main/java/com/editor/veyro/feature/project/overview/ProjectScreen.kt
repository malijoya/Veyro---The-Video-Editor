package com.editor.veyro.feature.project.overview

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.graphics.Color
import com.editor.veyro.core.designsystem.component.OverlayBadge
import com.editor.veyro.core.designsystem.theme.VeyroColors
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.designsystem.component.AvailabilityBadge
import com.editor.veyro.core.designsystem.component.ConfirmDialog
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.component.MediaThumbnail
import com.editor.veyro.core.designsystem.component.SectionHeader
import com.editor.veyro.core.designsystem.component.TextInputDialog
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectName
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.feature.project.MediaTile
import com.editor.veyro.feature.project.R
import com.editor.veyro.feature.project.badgeText
import com.editor.veyro.feature.project.detailText
import com.editor.veyro.feature.project.importmedia.MediaSource
import com.editor.veyro.feature.project.importmedia.SourceMenuItems
import com.editor.veyro.feature.project.thumbnailKind
import com.editor.veyro.feature.project.titleText

@Composable
public fun ProjectRoute(
    viewModel: ProjectViewModel,
    onBack: () -> Unit,
    onAddMedia: (MediaSource) -> Unit,
    onOpenMedia: (MediaId) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == ProjectUiState.Deleted) onBack()
    }
    // Source files can be deleted or moved while Veyro is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAvailability()
        onPauseOrDispose { }
    }
    ProjectScreen(
        state = state,
        onBack = onBack,
        onAddMedia = onAddMedia,
        onOpenMedia = onOpenMedia,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onActionErrorShown = viewModel::actionErrorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectScreen(
    state: ProjectUiState,
    onBack: () -> Unit,
    onAddMedia: (MediaSource) -> Unit,
    onOpenMedia: (MediaId) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onActionErrorShown: () -> Unit,
) {
    val project = (state as? ProjectUiState.Loaded)?.project
    var addMenuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(project?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.project_back))
                    }
                },
                actions = {
                    if (project != null) {
                        Box {
                            IconButton(onClick = { addMenuOpen = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.project_add_media_title))
                            }
                            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                                SourceMenuItems { addMenuOpen = false; onAddMedia(it) }
                            }
                        }
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.project_rename))
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.project_delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (state) {
                ProjectUiState.Loading -> CircularProgressIndicator(Modifier.padding(Spacing.xxl))
                ProjectUiState.Deleted -> Unit
                ProjectUiState.NotFound -> EmptyState(
                    icon = VeyroIcons.VideoLibrary,
                    title = stringResource(R.string.project_not_found_title),
                    message = stringResource(R.string.project_not_found_message),
                )
                ProjectUiState.LoadFailed -> EmptyState(
                    icon = VeyroIcons.BrokenImage,
                    title = stringResource(R.string.project_load_failed_title),
                    message = stringResource(R.string.project_load_failed_message),
                )
                is ProjectUiState.Loaded -> ProjectContent(state.project, state.missingUris.orEmpty(), onOpenMedia)
            }
        }
    }

    if (project != null && renaming) {
        TextInputDialog(
            title = stringResource(R.string.project_rename_title),
            label = stringResource(R.string.project_name_label),
            initialValue = project.name,
            confirmLabel = stringResource(R.string.project_save),
            dismissLabel = stringResource(R.string.project_cancel),
            isValid = { ProjectName.normalize(it) != null },
            onConfirm = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (project != null && deleting) {
        ConfirmDialog(
            title = stringResource(R.string.project_delete_title, project.name),
            message = stringResource(R.string.project_delete_message),
            confirmLabel = stringResource(R.string.project_delete),
            dismissLabel = stringResource(R.string.project_cancel),
            destructive = true,
            onConfirm = {
                deleting = false
                onDelete()
            },
            onDismiss = { deleting = false },
        )
    }
    val actionFailed = (state as? ProjectUiState.Loaded)?.actionFailed == true
    val errorText = stringResource(R.string.project_action_failed)
    LaunchedEffect(actionFailed) {
        if (actionFailed) {
            snackbarHostState.showSnackbar(errorText)
            onActionErrorShown()
        }
    }
}

@Composable
private fun ProjectContent(project: Project, missingUris: Set<String>, onOpenMedia: (MediaId) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Layout.mediaTileMinWidth),
        contentPadding = PaddingValues(start = Layout.screenGutter, end = Layout.screenGutter, bottom = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier
            .widthIn(max = Layout.maxContentWidth)
            .fillMaxWidth(),
    ) {
        fullWidth("cover") {
            val cover = project.coverMedia
            MediaThumbnail(
                uri = cover?.uri,
                kind = (cover?.kind ?: MediaKind.IMAGE).thumbnailKind,
                contentDescription = stringResource(R.string.project_cover_description, project.name),
                unavailableDescription = stringResource(R.string.project_media_unavailable),
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.large),
            )
        }
        fullWidth("stats") { ProjectStats(project) }
        val missingCount = project.media.count { it.uri in missingUris }
        if (missingCount > 0) {
            fullWidth("missing") { MissingMediaBanner(missingCount) }
        }
        fullWidth("editor-status") { EditorStatusCard() }
        fullWidth("media-header") {
            SectionHeader(stringResource(R.string.project_media_section, project.media.size))
        }
        items(project.media, key = { it.id.value }) { media ->
            ProjectMediaTile(media, missing = media.uri in missingUris, onOpen = { onOpenMedia(media.id) })
        }
    }
}

@Composable
private fun ProjectMediaTile(media: MediaRef, missing: Boolean, onOpen: () -> Unit) {
    MediaTile(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.preview_open), onClick = onOpen),
        uri = media.uri,
        kind = media.kind.thumbnailKind,
        title = media.titleText(),
        subtitle = if (missing) stringResource(R.string.project_media_missing_detail) else media.detailText(),
        subtitleColor = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        badge = if (missing) null else media.badgeText(),
    ) {
        if (missing) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .background(VeyroColors.MediaScrim),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
            }
            OverlayBadge(
                stringResource(R.string.project_media_missing),
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xs + Spacing.xxs),
            )
        }
    }
}

/** Explains missing source files. Relinking arrives with the editor, so this only informs. */
@Composable
private fun MissingMediaBanner(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(Spacing.lg)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                pluralStringResource(R.plurals.project_missing_banner, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ProjectStats(project: Project) {
    val context = LocalContext.current
    val created = DateUtils.formatDateTime(
        context,
        project.createdAtEpochMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val parts = buildList {
        add(pluralStringResource(R.plurals.project_item_count, project.media.size, project.media.size))
        if (project.mediaDurationUs > 0) {
            add(stringResource(R.string.project_total_video, DurationFormat.clock(project.mediaDurationUs)))
        }
        add(stringResource(R.string.project_created_on, created))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        parts.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** States plainly that editing isn't built yet, rather than offering buttons that do nothing. */
@Composable
private fun EditorStatusCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.project_editor_status_title), style = MaterialTheme.typography.titleSmall)
                    AvailabilityBadge(stringResource(R.string.project_coming_soon))
                }
                Text(
                    stringResource(R.string.project_editor_status_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}
