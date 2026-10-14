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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.editor.veyro.core.designsystem.theme.Layout
import com.editor.veyro.core.designsystem.theme.Spacing
import com.editor.veyro.core.model.project.ProjectId

@Composable
public fun ProjectsRoute(
    viewModel: LibraryViewModel,
    contentWindowInsets: WindowInsets,
    onNewProject: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProjectsScreen(
        state = state,
        contentWindowInsets = contentWindowInsets,
        onNewProject = onNewProject,
        onOpenProject = onOpenProject,
        onSortChange = viewModel::setSort,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onMessageShown = viewModel::messageShown,
    )
}

/** Every saved project, sortable, with rename and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsScreen(
    state: LibraryUiState,
    contentWindowInsets: WindowInsets,
    onNewProject: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    onSortChange: (ProjectSort) -> Unit,
    onRename: (ProjectId, String) -> Unit,
    onDelete: (ProjectId) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogs = rememberProjectDialogState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_projects_title)) },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.library_sort))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            ProjectSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(sort.labelRes)) },
                                    trailingIcon = {
                                        if (sort == state.sort) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        onSortChange(sort)
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewProject,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.library_new_project)) },
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
                    // Keep the last row clear of the floating action button.
                    bottom = Spacing.xxxl + Spacing.xxxl,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = Modifier
                    .widthIn(max = Layout.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                projectListContent(
                    state = state,
                    projects = state.projects,
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

private val ProjectSort.labelRes: Int
    get() = when (this) {
        ProjectSort.LAST_EDITED -> R.string.library_sort_last_edited
        ProjectSort.CREATED -> R.string.library_sort_created
        ProjectSort.NAME -> R.string.library_sort_name
    }
