package com.editor.veyro.feature.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import com.editor.veyro.core.designsystem.component.ConfirmDialog
import com.editor.veyro.core.designsystem.component.TextInputDialog
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectName

/** Which project dialog is open. Saved across configuration changes as the project ID string. */
internal class ProjectDialogState(
    val renaming: MutableState<String?>,
    val deleting: MutableState<String?>,
)

@Composable
internal fun rememberProjectDialogState() = ProjectDialogState(
    renaming = rememberSaveable { mutableStateOf<String?>(null) },
    deleting = rememberSaveable { mutableStateOf<String?>(null) },
)

/** Shows the rename and delete dialogs for whichever project [state] points at. */
@Composable
internal fun ProjectDialogs(
    state: ProjectDialogState,
    projects: List<ProjectCardUi>,
    onRename: (ProjectId, String) -> Unit,
    onDelete: (ProjectId) -> Unit,
) {
    state.renaming.value?.let { id ->
        val project = projects.firstOrNull { it.id.value == id }
        if (project == null) {
            // The project disappeared (deleted elsewhere). Close the dialog.
            LaunchedEffect(id) { state.renaming.value = null }
        } else {
            TextInputDialog(
                title = stringResource(R.string.library_rename_title),
                label = stringResource(R.string.library_rename_label),
                initialValue = project.name,
                confirmLabel = stringResource(R.string.library_rename_confirm),
                dismissLabel = stringResource(R.string.library_cancel),
                isValid = { ProjectName.normalize(it) != null },
                onConfirm = { name ->
                    state.renaming.value = null
                    onRename(project.id, name)
                },
                onDismiss = { state.renaming.value = null },
            )
        }
    }
    state.deleting.value?.let { id ->
        val project = projects.firstOrNull { it.id.value == id }
        if (project == null) {
            // The project disappeared (deleted elsewhere). Close the dialog.
            LaunchedEffect(id) { state.deleting.value = null }
        } else {
            ConfirmDialog(
                title = stringResource(R.string.library_delete_title, project.name),
                message = stringResource(R.string.library_delete_message),
                confirmLabel = stringResource(R.string.library_action_delete),
                dismissLabel = stringResource(R.string.library_cancel),
                destructive = true,
                onConfirm = {
                    state.deleting.value = null
                    onDelete(project.id)
                },
                onDismiss = { state.deleting.value = null },
            )
        }
    }
}

/** Shows [message] once in [snackbarHostState], then reports it consumed. */
@Composable
internal fun LibraryMessageEffect(
    message: LibraryMessage?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val text = when (message) {
        LibraryMessage.RENAME_FAILED -> stringResource(R.string.library_error_rename)
        LibraryMessage.DELETE_FAILED -> stringResource(R.string.library_error_delete)
        LibraryMessage.IMPORT_NOT_PROJECT -> stringResource(R.string.library_import_not_project)
        LibraryMessage.IMPORT_NEWER_VERSION -> stringResource(R.string.library_import_newer)
        LibraryMessage.IMPORT_DAMAGED -> stringResource(R.string.library_import_damaged)
        LibraryMessage.IMPORT_FAILED -> stringResource(R.string.library_import_failed)
        null -> null
    }
    LaunchedEffect(message) {
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onShown()
        }
    }
}
