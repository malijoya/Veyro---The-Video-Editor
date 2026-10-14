package com.editor.veyro.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.component.ActionTile
import com.editor.veyro.core.designsystem.component.EmptyState
import com.editor.veyro.core.designsystem.component.HeroActionCard
import com.editor.veyro.core.designsystem.component.SectionHeader
import com.editor.veyro.core.designsystem.component.VeyroWordmark
import com.editor.veyro.core.designsystem.icon.VeyroIcons
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.designsystem.theme.VeyroTheme
import com.editor.veyro.core.model.project.ProjectId

/** How many projects the home screen lists before pointing to the Projects tab. */
private const val RECENT_LIMIT = 6

@Composable
public fun HomeRoute(
    viewModel: LibraryViewModel,
    contentWindowInsets: WindowInsets,
    onNewFromGallery: () -> Unit,
    onNewFromFiles: () -> Unit,
    onNewFromAudio: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    onSeeAllProjects: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        contentWindowInsets = contentWindowInsets,
        onNewFromGallery = onNewFromGallery,
        onNewFromFiles = onNewFromFiles,
        onNewFromAudio = onNewFromAudio,
        onOpenProject = onOpenProject,
        onSeeAllProjects = onSeeAllProjects,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onMessageShown = viewModel::messageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: LibraryUiState,
    contentWindowInsets: WindowInsets,
    onNewFromGallery: () -> Unit,
    onNewFromFiles: () -> Unit,
    onNewFromAudio: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    onSeeAllProjects: () -> Unit,
    onRename: (ProjectId, String) -> Unit,
    onDelete: (ProjectId) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogs = rememberProjectDialogState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { VeyroWordmark(stringResource(R.string.library_app_name)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Layout.projectCardMinWidth),
                contentPadding = PaddingValues(
                    start = Layout.screenGutter,
                    end = Layout.screenGutter,
                    top = Spacing.sm,
                    bottom = Spacing.xl,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = Modifier
                    .widthIn(max = Layout.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                fullWidthItem("hero") {
                    HeroActionCard(
                        title = stringResource(R.string.library_new_project),
                        subtitle = stringResource(R.string.library_new_project_subtitle),
                        icon = Icons.Filled.Add,
                        onClick = onNewFromGallery,
                    )
                }
                // One grid cell per source, so the tiles reflow from 2 to 3+ columns with the window width.
                item(key = "import-gallery", contentType = "import") {
                    ActionTile(
                        label = stringResource(R.string.library_import_gallery),
                        supportingText = stringResource(R.string.library_import_gallery_hint),
                        icon = VeyroIcons.PhotoLibrary,
                        onClick = onNewFromGallery,
                    )
                }
                item(key = "import-files", contentType = "import") {
                    ActionTile(
                        label = stringResource(R.string.library_import_files),
                        supportingText = stringResource(R.string.library_import_files_hint),
                        icon = VeyroIcons.FolderOpen,
                        onClick = onNewFromFiles,
                    )
                }
                item(key = "import-audio", contentType = "import") {
                    ActionTile(
                        label = stringResource(R.string.library_import_audio),
                        supportingText = stringResource(R.string.library_import_audio_hint),
                        icon = VeyroIcons.LibraryMusic,
                        onClick = onNewFromAudio,
                    )
                }
                val recent = state.projects.take(RECENT_LIMIT)
                fullWidthItem("recent-header") {
                    SectionHeader(
                        title = stringResource(R.string.library_recent_projects),
                        actionLabel = if (state.projects.size > recent.size) {
                            stringResource(R.string.library_see_all, state.projects.size)
                        } else {
                            null
                        },
                        onAction = onSeeAllProjects,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                projectListContent(
                    state = state,
                    projects = recent,
                    onOpenProject = onOpenProject,
                    onRename = { dialogs.renaming.value = it.value },
                    onDelete = { dialogs.deleting.value = it.value },
                )
            }
        }
    }

    if (!state.isLoading) {
        ProjectDialogs(dialogs, state.projects, onRename, onDelete)
    }
    LibraryMessageEffect(state.message, snackbarHostState, onMessageShown)
}

/** Loading, error, empty or the project cards. Shared by Home and Projects. */
internal fun LazyGridScope.projectListContent(
    state: LibraryUiState,
    projects: List<ProjectCardUi>,
    onOpenProject: (ProjectId) -> Unit,
    onRename: (ProjectId) -> Unit,
    onDelete: (ProjectId) -> Unit,
) {
    when {
        state.isLoading -> fullWidthItem("loading") {
            Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.loadFailed -> fullWidthItem("error") {
            EmptyState(
                icon = VeyroIcons.BrokenImage,
                title = stringResource(R.string.library_error_load_title),
                message = stringResource(R.string.library_error_load_message),
            )
        }
        projects.isEmpty() -> fullWidthItem("empty") {
            EmptyState(
                icon = VeyroIcons.VideoLibrary,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_message),
            )
        }
        else -> items(projects, key = { it.id.value }, contentType = { "project" }) { project ->
            ProjectCard(
                project = project,
                onOpen = { onOpenProject(project.id) },
                onRename = { onRename(project.id) },
                onDelete = { onDelete(project.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

internal fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

@Preview(widthDp = 400, heightDp = 860)
@Composable
private fun HomeScreenEmptyPreview() {
    VeyroTheme {
        HomeScreen(
            state = LibraryUiState(isLoading = false),
            contentWindowInsets = WindowInsets(0),
            onNewFromGallery = {}, onNewFromFiles = {}, onNewFromAudio = {}, onOpenProject = {}, onSeeAllProjects = {},
            onRename = { _, _ -> }, onDelete = {}, onMessageShown = {},
        )
    }
}
