package com.editor.veyro.feature.project.importmedia

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.ConfirmDialog
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.designsystem.theme.VeyroColors
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.time.DurationFormat
import com.editor.veyro.feature.project.MediaTile
import com.editor.veyro.feature.project.R
import com.editor.veyro.feature.project.badgeText
import com.editor.veyro.feature.project.detailText
import com.editor.veyro.feature.project.thumbnailKind
import com.editor.veyro.feature.project.titleText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A default name such as "Project Oct 14, 3:45 PM", localized. */
public fun defaultProjectName(context: Context, nowEpochMs: Long = System.currentTimeMillis()): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "MMMdHHmm" else "MMMdhmma"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return context.getString(R.string.project_default_name, SimpleDateFormat(pattern, locale).format(Date(nowEpochMs)))
}

/** MIME filters for the Storage Access Framework picker. */
private val FILE_TYPES = arrayOf("video/*", "image/*", "audio/*", "application/ogg")
private val AUDIO_TYPES = arrayOf("audio/*", "application/ogg")

@Composable
public fun MediaImportRoute(
    viewModel: MediaImportViewModel,
    initialSource: MediaSource,
    onCreated: (ProjectId) -> Unit,
    onAdded: (ProjectId) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.onPicked(uris.map { it.toString() })
    }
    // OpenMultipleDocuments grants persistable access, which the ViewModel takes immediately.
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onPicked(uris.map { it.toString() })
    }
    val launch: (MediaSource) -> Unit = { source ->
        when (source) {
            MediaSource.GALLERY -> galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
            MediaSource.FILES -> filesLauncher.launch(FILE_TYPES)
            MediaSource.AUDIO -> filesLauncher.launch(AUDIO_TYPES)
        }
    }

    LaunchedEffect(Unit) {
        if (!state.initialPickerLaunched) {
            viewModel.onInitialPickerLaunched()
            launch(initialSource)
        }
    }
    LaunchedEffect(state.outcome) {
        when (val outcome = state.outcome) {
            is ImportOutcome.Created -> onCreated(outcome.projectId)
            is ImportOutcome.Added -> onAdded(outcome.projectId)
            null -> Unit
        }
    }
    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    MediaImportScreen(
        state = state,
        onNameChange = viewModel::setName,
        onAddMedia = launch,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onImport = viewModel::importSelected,
        onDiscard = viewModel::discard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaImportScreen(
    state: MediaImportUiState,
    onNameChange: (String) -> Unit,
    onAddMedia: (MediaSource) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onImport: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (state.items.isEmpty()) onDiscard() else confirmDiscard = true }
    BackHandler(onBack = requestClose)
    val isNewProject = state.target is ImportTarget.NewProject

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (isNewProject) R.string.project_new_title else R.string.project_add_media_title))
                },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.project_discard_action))
                    }
                },
                actions = { AddMediaMenu(onAddMedia) },
            )
        },
        bottomBar = { ImportBar(state, onImport) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Layout.mediaTileMinWidth),
                contentPadding = PaddingValues(Layout.screenGutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier
                    .widthIn(max = Layout.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                if (state.targetMissing) {
                    fullWidth("missing") {
                        EmptyState(
                            icon = VeyroIcons.VideoLibrary,
                            title = stringResource(R.string.project_not_found_title),
                            message = stringResource(R.string.project_not_found_message),
                        )
                    }
                    return@LazyVerticalGrid
                }
                if (isNewProject) {
                    fullWidth("name") {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = onNameChange,
                            label = { Text(stringResource(R.string.project_name_label)) },
                            singleLine = true,
                            isError = !state.nameValid,
                            supportingText = if (!state.nameValid) {
                                { Text(stringResource(R.string.project_name_required)) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.items.isEmpty()) {
                    fullWidth("empty") {
                        EmptyState(
                            icon = VeyroIcons.VideoLibrary,
                            title = stringResource(R.string.project_draft_empty_title),
                            message = stringResource(R.string.project_draft_empty_message),
                        )
                    }
                } else {
                    fullWidth("summary") { SelectionSummary(state, onSelectAll, onClearSelection) }
                    items(state.items, key = { it.uri }) { item ->
                        ImportTile(state, item, onToggle = { onToggle(item.uri) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.project_discard_title),
            message = stringResource(R.string.project_discard_message),
            confirmLabel = stringResource(R.string.project_discard_confirm),
            dismissLabel = stringResource(R.string.project_keep_editing),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun AddMediaMenu(onAddMedia: (MediaSource) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.project_add_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SourceMenuItems { open = false; onAddMedia(it) }
        }
    }
}

/** The three import sources as menu items. Shared by the import screen and the project screen. */
@Composable
public fun SourceMenuItems(onSelect: (MediaSource) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.project_source_gallery)) },
        leadingIcon = { Icon(VeyroIcons.PhotoLibrary, contentDescription = null) },
        onClick = { onSelect(MediaSource.GALLERY) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.project_source_files)) },
        leadingIcon = { Icon(VeyroIcons.FolderOpen, contentDescription = null) },
        onClick = { onSelect(MediaSource.FILES) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.project_source_audio)) },
        leadingIcon = { Icon(VeyroIcons.LibraryMusic, contentDescription = null) },
        onClick = { onSelect(MediaSource.AUDIO) },
    )
}

@Composable
private fun SelectionSummary(state: MediaImportUiState, onSelectAll: () -> Unit, onClearSelection: () -> Unit) {
    val selected = state.selectedMedia
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.project_selected_count, selected.size, state.items.size),
                style = MaterialTheme.typography.titleSmall,
            )
            val videoUs = selected.filter { it.video != null }.sumOf { it.durationUs }
            if (videoUs > 0) {
                Text(
                    stringResource(R.string.project_total_video, DurationFormat.clock(videoUs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.selectableCount > 0) {
            if (selected.size < state.selectableCount) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.project_select_all)) }
            } else {
                TextButton(onClick = onClearSelection) { Text(stringResource(R.string.project_clear_selection)) }
            }
        }
    }
}

@Composable
private fun ImportTile(state: MediaImportUiState, item: ImportItem, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    when (item) {
        is ImportItem.Ready -> {
            val media = item.media
            val already = state.isAlreadyInProject(item)
            val selected = state.isSelected(item)
            val temporary = item.uri in state.temporaryAccess
            val stateText = when {
                already -> stringResource(R.string.project_already_in_project)
                selected -> stringResource(R.string.project_state_selected)
                else -> stringResource(R.string.project_state_not_selected)
            }
            MediaTile(
                uri = media.uri,
                kind = media.kind.thumbnailKind,
                title = media.titleText(),
                subtitle = when {
                    already -> stringResource(R.string.project_already_in_project)
                    temporary -> stringResource(R.string.project_temporary_access)
                    else -> media.detailText()
                },
                subtitleColor = if (temporary && !already) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                badge = media.badgeText(),
                modifier = modifier
                    .alpha(if (already) 0.5f else 1f)
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(value = selected, enabled = !already, role = Role.Checkbox, onValueChange = { onToggle() })
                    .semantics { stateDescription = stateText },
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
                    )
                }
                if (!already) SelectionMark(selected)
            }
        }

        // The kind isn't known until probing finishes, so no thumbnail is requested yet.
        is ImportItem.Probing -> Column(modifier) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            Text(
                stringResource(R.string.project_reading_media),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        is ImportItem.Failed -> Column(modifier) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.sm)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        stringResource(item.reason.messageRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            Text(
                stringResource(R.string.project_not_added),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** Checkbox-style mark in the tile corner: filled check when selected, empty ring when not. */
@Composable
private fun BoxScope.SelectionMark(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(Spacing.xs + Spacing.xxs)
            .size(24.dp)
            .background(if (selected) Color.White else VeyroColors.MediaScrim, CircleShape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ImportBar(state: MediaImportUiState, onImport: () -> Unit) {
    if (state.targetMissing) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.importFailed) {
                Text(
                    stringResource(R.string.project_create_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Layout.screenGutter, vertical = Spacing.sm),
                )
            }
            Button(
                onClick = onImport,
                enabled = state.canImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Layout.screenGutter, vertical = Spacing.md)
                    .heightIn(min = 52.dp),
            ) {
                val count = state.selectedMedia.size
                when {
                    state.isImporting -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    state.isProbing -> Text(stringResource(R.string.project_reading_media))
                    count == 0 -> Text(stringResource(R.string.project_select_to_import))
                    state.target is ImportTarget.NewProject ->
                        Text(pluralStringResource(R.plurals.project_create, count, count))
                    else -> Text(pluralStringResource(R.plurals.project_add_items, count, count))
                }
            }
        }
    }
}

private val ImportFailure.messageRes: Int
    get() = when (this) {
        ImportFailure.UNSUPPORTED_TYPE -> R.string.project_failure_unsupported
        ImportFailure.NOT_ACCESSIBLE -> R.string.project_failure_not_accessible
        ImportFailure.UNREADABLE -> R.string.project_failure_unreadable
    }

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}
